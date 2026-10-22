package com.prooggaa.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifications {
    const val CHANNEL_GENERAL = "general"
    const val CHANNEL_UPDATES = "updates"
    private const val GROUP = "proggaa"
    private const val SUMMARY_ID = 1
    private const val UPDATE_ID = 2

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_GENERAL, context.getString(R.string.channel_general), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_general_desc)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATES, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_updates_desc)
            }
        )
    }

    fun allowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** "Proggaa x.y.z is ready": tapping it opens the app's update prompt. */
    fun showUpdate(context: Context, version: String, notes: String) {
        if (!allowed(context)) return
        val open = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_CHECK_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, UPDATE_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = notes.trim().ifEmpty { "Tap to update. It takes a few seconds." }
        val notification = NotificationCompat.Builder(context, CHANNEL_GENERAL)
            .setSmallIcon(R.drawable.ic_stat_proggaa)
            .setColor(ContextCompat.getColor(context, R.color.proggaa_primary))
            .setContentTitle("Prooggaa $version is ready")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(UPDATE_ID, notification)
        } catch (_: SecurityException) {
            // Permission was withdrawn between the check and now.
        }
    }

    /**
     * Shows one Proggaa notification. `key` is the site's notification id, so a
     * notification the site updates in place (e.g. "3 new messages in a chat")
     * replaces its earlier version instead of stacking up.
     */
    fun show(context: Context, key: String, title: String, body: String, link: String?) {
        if (!allowed(context)) return
        val open = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_URL, Site.pageFor(link))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context,
            key.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_GENERAL)
            .setSmallIcon(R.drawable.ic_stat_proggaa)
            .setColor(ContextCompat.getColor(context, R.color.proggaa_primary))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setGroup(GROUP)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            val manager = NotificationManagerCompat.from(context)
            manager.notify(key.hashCode(), notification)
            // A summary keeps several notifications tidy in one Proggaa group.
            manager.notify(
                SUMMARY_ID,
                NotificationCompat.Builder(context, CHANNEL_GENERAL)
                    .setSmallIcon(R.drawable.ic_stat_proggaa)
                    .setColor(ContextCompat.getColor(context, R.color.proggaa_primary))
                    .setGroup(GROUP)
                    .setGroupSummary(true)
                    .setAutoCancel(true)
                    .build(),
            )
        } catch (_: SecurityException) {
            // Permission was withdrawn between the check and now.
        }
    }
}
