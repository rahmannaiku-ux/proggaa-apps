package com.prooggaa.app

import android.content.Context
import android.webkit.CookieManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Fetches new notifications from the site and shows them as phone
 * notifications. Runs about every 15 minutes (Android's shortest interval
 * for background work) whenever there is a connection, even when the app is
 * closed. It signs in with the same session as the app's web view, so it
 * only ever sees the signed-in person's own notifications.
 */
class NotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        announceUpdate()
        val cookies = withContext(Dispatchers.Main) { CookieManager.getInstance().getCookie(Site.BASE) }
        if (cookies == null || !cookies.contains("proggaa_session=")) return Result.success() // signed out

        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val after = prefs.getString(KEY_CURSOR, null)
        val url = Site.NOTIFICATIONS_API + if (after != null) "?after=" + URLEncoder.encode(after, "UTF-8") else ""

        val json = when (val fetched = withContext(Dispatchers.IO) { fetch(url, cookies) }) {
            is Fetched.Ok -> fetched.text
            Fetched.SignedOut -> return Result.success() // signed out or session ended: nothing to do
            Fetched.Failed -> return Result.retry()
        }

        val body = runCatching { JSONObject(json) }.getOrNull() ?: return Result.success()
        val items = body.optJSONArray("notifications")
        // While the app is open the site shows notifications itself; only move the cursor on.
        if (items != null && !MainActivity.inForeground) {
            for (i in 0 until minOf(items.length(), MAX_SHOWN)) {
                val n = items.optJSONObject(i) ?: continue
                Notifications.show(
                    applicationContext,
                    key = n.optString("id"),
                    title = n.optString("title", "Proggaa"),
                    body = n.optString("body"),
                    link = n.optString("url").ifBlank { null },
                )
            }
        }
        body.optString("cursor").takeIf { it.isNotBlank() }?.let { prefs.edit().putString(KEY_CURSOR, it).apply() }
        return Result.success()
    }

    /**
     * About twice a day, even when the app isn't opened: if a newer version is
     * out, says so once in a notification (tapping it opens the update prompt).
     */
    private suspend fun announceUpdate() {
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_UPDATE_CHECK, 0) < UPDATE_CHECK_EVERY_MS) return
        val release = withContext(Dispatchers.IO) { runCatching { AppUpdater.fetchLatestRelease() }.getOrNull() } ?: return
        prefs.edit().putLong(KEY_UPDATE_CHECK, now).apply()
        if (release.versionCode <= BuildConfig.VERSION_CODE) return
        if (prefs.getInt(KEY_UPDATE_ANNOUNCED, 0) >= release.versionCode) return
        prefs.edit().putInt(KEY_UPDATE_ANNOUNCED, release.versionCode).apply()
        Notifications.showUpdate(applicationContext, release.versionName, release.notes)
    }

    private sealed interface Fetched {
        data class Ok(val text: String) : Fetched
        data object SignedOut : Fetched
        data object Failed : Fetched
    }

    private fun fetch(url: String, cookies: String): Fetched {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Cookie", cookies)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ProggaaApp/Android/${BuildConfig.VERSION_NAME}")
        }
        return try {
            when (conn.responseCode) {
                200 -> Fetched.Ok(conn.inputStream.bufferedReader().use { it.readText() })
                401, 403 -> Fetched.SignedOut
                else -> Fetched.Failed
            }
        } catch (_: Exception) {
            Fetched.Failed
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val PREFS = "notifications"
        private const val KEY_CURSOR = "cursor"
        private const val WORK_NAME = "proggaa-notifications"
        private const val MAX_SHOWN = 8
        private const val KEY_UPDATE_CHECK = "updateCheck"
        private const val KEY_UPDATE_ANNOUNCED = "updateAnnounced"
        private const val UPDATE_CHECK_EVERY_MS = 12 * 60 * 60 * 1000L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NotificationWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
