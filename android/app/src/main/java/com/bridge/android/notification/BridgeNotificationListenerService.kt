package com.bridge.android.notification

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.bridge.android.util.LogManager

class BridgeNotificationListenerService : NotificationListenerService() {

    companion object {
        @Volatile
        var isConnected: Boolean = false
            private set

        fun rebind(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    requestRebind(ComponentName(context, BridgeNotificationListenerService::class.java))
                    LogManager.i("Requested rebind for BridgeNotificationListenerService")
                } catch (e: Exception) {
                    LogManager.e("Failed to requestRebind for BridgeNotificationListenerService", e)
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        LogManager.i("BridgeNotificationListenerService connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        LogManager.i("BridgeNotificationListenerService disconnected; requesting rebind")
        rebind(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            NotificationSyncManager.processNotificationPosted(this, sbn)
        } catch (e: Exception) {
            LogManager.e("Error processing onNotificationPosted", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            NotificationSyncManager.processNotificationRemoved(this, sbn)
        } catch (e: Exception) {
            LogManager.e("Error processing onNotificationRemoved", e)
        }
    }
}
