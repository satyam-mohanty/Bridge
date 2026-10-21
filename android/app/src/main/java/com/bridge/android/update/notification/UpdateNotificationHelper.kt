package com.bridge.android.update.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bridge.android.R
import com.bridge.android.ui.MainActivity
import com.bridge.android.update.data.UpdateConfig
import com.bridge.android.update.model.UpdateManifest
import com.bridge.android.util.LogManager

object UpdateNotificationHelper {

    const val EXTRA_OPEN_UPDATE = "extra_open_update"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                UpdateConfig.NOTIFICATION_CHANNEL_ID,
                UpdateConfig.NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = UpdateConfig.NOTIFICATION_CHANNEL_DESC
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun showUpdateAvailableNotification(context: Context, manifest: UpdateManifest) {
        createNotificationChannel(context)

        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            LogManager.w("Notifications are disabled by user, skipping update notification")
            return
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_UPDATE, true)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            UpdateConfig.NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notesSummary = if (manifest.releaseNotes.isNotBlank()) {
            manifest.releaseNotes.lines().take(3).joinToString("\n")
        } else {
            "Tap to download and install version ${manifest.versionName}"
        }

        val notification = NotificationCompat.Builder(context, UpdateConfig.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bridge_notification)
            .setContentTitle("Bridge ${manifest.versionName} Available")
            .setContentText("A new version of Bridge is ready to install.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("A new version of Bridge (${manifest.versionName}) is available!\n\n$notesSummary")
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(UpdateConfig.NOTIFICATION_ID, notification)
            LogManager.i("Posted update notification for version ${manifest.versionName}")
        } catch (e: SecurityException) {
            LogManager.w("Missing notification permission to post update alert: ${e.message}")
        } catch (e: Exception) {
            LogManager.e("Failed to post update notification", e)
        }
    }

    fun cancelUpdateNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(UpdateConfig.NOTIFICATION_ID)
    }
}
