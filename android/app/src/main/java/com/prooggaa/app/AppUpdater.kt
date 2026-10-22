package com.prooggaa.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The in-app updater. Reads the manifest of the newest GitHub Release, and
 * when it has a higher versionCode offers to download the APK, checks its
 * SHA-256 against the manifest and hands it to the system installer.
 * Android itself refuses an APK that isn't signed with the same key as the
 * installed app, so an update can only ever come from Proggaa's own builds.
 */
class AppUpdater(private val activity: ComponentActivity) {

    data class Release(val versionCode: Int, val versionName: String, val url: String, val sha256: String, val notes: String)

    private val prefs = activity.getSharedPreferences("updater", Context.MODE_PRIVATE)
    private var pendingInstall: File? = null
    private var running: Job? = null
    /** Until a check succeeds after the app starts, every opening checks (no half-hour wait). */
    private var checkedSinceStart = false

    /**
     * Automatic check (always when the app starts, then each time it comes to
     * the front, at most every half hour), or `manual` when the person asked.
     */
    fun check(manual: Boolean) {
        if (running?.isActive == true) return
        val now = System.currentTimeMillis()
        if (!manual && checkedSinceStart && now - prefs.getLong(KEY_LAST_CHECK, 0) < AUTO_CHECK_EVERY_MS) return
        running = activity.lifecycleScope.launch {
            val release = withContext(Dispatchers.IO) { runCatching { fetchRelease() } }
            // Only a successful check counts: without a connection the next opening tries again.
            if (release.isSuccess) {
                checkedSinceStart = true
                prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
            }
            release.onFailure {
                if (manual) toast("Couldn't check for updates. Check your connection and try again.")
            }
            val latest = release.getOrNull() ?: return@launch
            if (latest.versionCode <= BuildConfig.VERSION_CODE) {
                if (manual) toast("You have the latest version (${BuildConfig.VERSION_NAME}).")
                return@launch
            }
            // "Later" quiets automatic prompts for this version for a day.
            val snoozedUntil = prefs.getLong(KEY_SNOOZE_PREFIX + latest.versionCode, 0)
            if (!manual && now < snoozedUntil) return@launch
            offer(latest)
        }
    }

    /** Call from onResume: finishes an install that was waiting for "Install unknown apps". */
    fun onResume() {
        val file = pendingInstall ?: return
        if (canInstall()) {
            pendingInstall = null
            install(file)
        }
    }

    private fun fetchRelease(): Release = fetchLatestRelease()

    companion object {
        private const val KEY_LAST_CHECK = "lastCheck"
        private const val KEY_SNOOZE_PREFIX = "snooze-"
        private const val AUTO_CHECK_EVERY_MS = 30 * 60 * 1000L
        private const val SNOOZE_MS = 24 * 60 * 60 * 1000L

        /** The newest release's manifest (also used by NotificationWorker to announce updates). */
        fun fetchLatestRelease(): Release {
        val conn = (URL(Site.UPDATE_MANIFEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val url = json.getString("url")
            require(url.startsWith("https://github.com/rahmannaiku-ux/proggaa-apps/")) { "unexpected download address" }
            return Release(
                versionCode = json.getInt("versionCode"),
                versionName = json.getString("versionName"),
                url = url,
                sha256 = json.getString("sha256").lowercase(),
                notes = json.optString("notes"),
            )
        } finally {
            conn.disconnect()
        }
        }
    }

    private fun offer(release: Release) {
        if (activity.isFinishing) return
        var job: Job? = null
        lateinit var dialog: UpdateDialog
        dialog = UpdateDialog(
            activity,
            currentVersion = BuildConfig.VERSION_NAME,
            newVersion = release.versionName,
            notes = release.notes.trim(),
            onUpdate = { job = download(release, dialog) },
            onLater = {
                prefs.edit().putLong(KEY_SNOOZE_PREFIX + release.versionCode, System.currentTimeMillis() + SNOOZE_MS).apply()
            },
            onCancelDownload = { job?.cancel() },
        )
        dialog.show()
    }

    private fun download(release: Release, dialog: UpdateDialog): Job {
        dialog.showDownloading()
        return activity.lifecycleScope.launch {
            val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() } // an older, half-finished or installed download
            val file = File(dir, "proggaa-${release.versionName}.apk")
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    downloadTo(release, file, cancelled = { !isActive }) { percent ->
                        activity.runOnUiThread { dialog.setProgress(percent) }
                    }
                }
            }
            if (!isActive) return@launch
            ok.onSuccess {
                dialog.dismiss()
                install(file)
            }.onFailure {
                file.delete()
                dialog.showError("The update didn't download completely. Check your connection and try again.")
            }
        }
    }

    /** Streams the APK to `file`, reporting percent (or -1 when the size is unknown), and verifies its SHA-256. */
    private fun downloadTo(release: Release, file: File, cancelled: () -> Boolean, progress: (Int) -> Unit) {
        val conn = (URL(release.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastPercent = -2
            conn.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) error("cancelled")
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt() else -1
                        if (percent != lastPercent) {
                            lastPercent = percent
                            progress(percent)
                        }
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == release.sha256) { "checksum mismatch" }
        } finally {
            conn.disconnect()
        }
    }

    private fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()

    private fun install(file: File) {
        if (!file.exists()) return
        if (!canInstall()) {
            // One-time: Android asks the person to allow Proggaa to install its own updates.
            pendingInstall = file
            AlertDialog.Builder(activity)
                .setTitle("One more step")
                .setMessage("To install updates, allow Proggaa to install apps. Turn on \"Allow from this source\", then come back.")
                .setPositiveButton("Open settings") { _, _ ->
                    runCatching {
                        activity.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                        )
                    }
                }
                .setNegativeButton("Not now") { _, _ -> pendingInstall = null }
                .show()
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { activity.startActivity(intent) }.onFailure { toast("Couldn't open the installer.") }
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

}
