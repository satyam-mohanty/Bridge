package com.bridge.android.notification

import android.app.Notification
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.bridge.android.network.BridgeClient
import com.bridge.android.protocol.BridgeProtocol
import com.bridge.android.service.BridgeService
import com.bridge.android.util.LogManager
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.LinkedList

object NotificationSyncManager {

    private data class QueuedNotification(
        val message: BridgeProtocol.NotifPostMessage,
        val queuedAt: Long
    )

    private val offlineQueue = Collections.synchronizedList(LinkedList<QueuedNotification>())
    private const val MAX_OFFLINE_ITEMS = 20
    private const val MAX_OFFLINE_AGE_MS = 2 * 60 * 1000L // 2 minutes

    // In-memory cache for debouncing identical notification repeats: key -> content hash
    private val recentContentHashes = Collections.synchronizedMap(object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean {
            return size > 200
        }
    })

    fun processNotificationPosted(context: Context, sbn: StatusBarNotification) {
        val key = sbn.key
        val pkg = sbn.packageName
        LogManager.i("Notif received: pkg=$pkg key=$key")

        val prefs = NotificationPreferences(context)
        if (!prefs.isSyncEnabled) {
            LogManager.i("Notif filtered: pkg=$pkg reason=sync_disabled")
            return
        }

        // Record package as recently active
        prefs.recordRecentPackage(pkg)

        // 1. Skip our own app
        if (pkg == context.packageName) {
            LogManager.i("Notif filtered: pkg=$pkg reason=own_app")
            return
        }

        // 2. Skip apps user has not enabled
        if (!prefs.isPackageEnabled(pkg)) {
            LogManager.i("Notif filtered: pkg=$pkg reason=app_not_enabled")
            return
        }

        val notification = sbn.notification ?: run {
            LogManager.i("Notif filtered: pkg=$pkg reason=null_notification")
            return
        }
        val flags = notification.flags
        val extras = notification.extras

        // 3. Skip group summaries
        val isGroupSummary = (flags and Notification.FLAG_GROUP_SUMMARY) != 0 ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && sbn.isGroup && notification.group != null && (flags and Notification.FLAG_GROUP_SUMMARY) != 0)
        if (isGroupSummary) {
            LogManager.i("Notif filtered: pkg=$pkg reason=group_summary")
            return
        }

        // 4. Skip media notifications
        if (extras != null && extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
            LogManager.i("Notif filtered: pkg=$pkg reason=media_session")
            return
        }
        val category = notification.category
        if (category == Notification.CATEGORY_TRANSPORT || category == "service") {
            LogManager.i("Notif filtered: pkg=$pkg reason=media_category")
            return
        }

        // 5. Skip progress notifications
        if (extras != null) {
            val progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
            val hasProgress = extras.containsKey(Notification.EXTRA_PROGRESS) || extras.containsKey(Notification.EXTRA_PROGRESS_MAX)
            if (hasProgress && progressMax > 0) {
                LogManager.i("Notif filtered: pkg=$pkg reason=progress_notification")
                return
            }
        }

        // 6. Skip foreground service notifications
        if ((flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) {
            LogManager.i("Notif filtered: pkg=$pkg reason=foreground_service")
            return
        }

        // 7. Skip ongoing notifications EXCEPT category call (WhatsApp style call notifications)
        val isOngoing = (flags and Notification.FLAG_ONGOING_EVENT) != 0
        val isCallCategory = Notification.CATEGORY_CALL.equals(category, ignoreCase = true)
        if (isOngoing && !isCallCategory) {
            LogManager.i("Notif filtered: pkg=$pkg reason=ongoing_non_call")
            return
        }

        // 8. Skip dialer and telecom packages (handled in Stage B)
        if (isDialerOrTelecom(context, pkg)) {
            LogManager.i("Notif filtered: pkg=$pkg reason=dialer_or_telecom")
            return
        }

        // Extraction
        val pm = context.packageManager
        val appName = try {
            val appInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            pkg
        }

        val rawTitle = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        var rawText = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
        val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()
        var convTitle = extras?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()
        var sender: String? = null

        // MessagingStyle extraction: conversation title and sender plus the LATEST message only
        try {
            val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
            if (messagingStyle != null) {
                if (messagingStyle.conversationTitle != null) {
                    convTitle = messagingStyle.conversationTitle.toString().trim()
                }
                val messages = messagingStyle.messages
                if (!messages.isNullOrEmpty()) {
                    val latestMessage = messages.last()
                    sender = latestMessage.person?.name?.toString()?.trim()
                    val msgText = latestMessage.text?.toString()?.trim()
                    if (!msgText.isNullOrEmpty()) {
                        rawText = msgText
                    }
                }
            }
        } catch (e: Exception) {
            LogManager.d("MessagingStyle extraction note: ${e.message}")
        }

        if (sender == null && extras != null) {
            sender = extras.getCharSequence(Notification.EXTRA_MESSAGING_PERSON)?.toString()?.trim()
        }

        // App version code for Mac cache key: package + versionCode
        val versionCode = try {
            val pInfo = pm.getPackageInfo(pkg, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                pInfo.versionCode.toLong()
            }
        } catch (_: Exception) {
            0L
        }

        // Contact photo / large icon extraction (scaled to 96 px JPEG)
        val largeIconBase64 = extractLargeIconBase64(context, notification)

        // Show previews check: off sends only app name and "New message"
        val showPreviews = prefs.showPreviews
        val titleToSend: String
        val textToSend: String
        val bigTextToSend: String?
        val subTextToSend: String?
        val convTitleToSend: String?
        val senderToSend: String?
        val largeIconToSend: String?

        if (showPreviews) {
            titleToSend = rawTitle
            textToSend = rawText
            bigTextToSend = bigText
            subTextToSend = subText
            convTitleToSend = convTitle
            senderToSend = sender
            largeIconToSend = largeIconBase64
        } else {
            titleToSend = appName
            textToSend = "New message"
            bigTextToSend = null
            subTextToSend = null
            convTitleToSend = null
            senderToSend = null
            largeIconToSend = null
        }

        // Debounce identical repeats
        val contentHash = (titleToSend + textToSend + (bigTextToSend ?: "") + (senderToSend ?: "") + (largeIconToSend ?: "")).hashCode()
        val previousHash = recentContentHashes[key]
        if (previousHash != null && previousHash == contentHash) {
            LogManager.d("Notif filtered: pkg=$pkg reason=debounce_identical_repeat")
            return
        }
        recentContentHashes[key] = contentHash

        val postMsg = BridgeProtocol.NotifPostMessage(
            key = key,
            pkg = pkg,
            app = appName,
            title = titleToSend,
            text = textToSend,
            bigText = bigTextToSend,
            subText = subTextToSend,
            convTitle = convTitleToSend,
            sender = senderToSend,
            ts = sbn.postTime,
            category = category,
            versionCode = versionCode,
            largeIcon = largeIconToSend
        )

        dispatchNotification(postMsg)
    }

    fun processNotificationRemoved(sbn: StatusBarNotification) {
        val key = sbn.key
        recentContentHashes.remove(key)

        val service = BridgeService.instance
        if (service != null && service.isConnectedFlow.value) {
            service.getClient().sendNotifRemove(key)
            LogManager.i("Notif sent: remove key=$key")
        }
    }

    private fun dispatchNotification(msg: BridgeProtocol.NotifPostMessage) {
        val service = BridgeService.instance
        if (service != null && service.isConnectedFlow.value) {
            service.getClient().sendNotifPost(msg)
            LogManager.i("Notif sent: pkg=${msg.pkg} key=${msg.key} title=${msg.title}")
        } else {
            // Buffer in offline queue
            synchronized(offlineQueue) {
                val now = System.currentTimeMillis()
                // Drop expired items (> 2 min)
                offlineQueue.removeAll { now - it.queuedAt > MAX_OFFLINE_AGE_MS }
                while (offlineQueue.size >= MAX_OFFLINE_ITEMS) {
                    offlineQueue.removeAt(0)
                }
                offlineQueue.add(QueuedNotification(msg, now))
                LogManager.i("Notif buffered in offline queue (size=${offlineQueue.size}): pkg=${msg.pkg} key=${msg.key}")
            }
        }
    }

    fun flushOfflineQueue(client: BridgeClient) {
        synchronized(offlineQueue) {
            val now = System.currentTimeMillis()
            val valid = offlineQueue.filter { now - it.queuedAt <= MAX_OFFLINE_AGE_MS }
            val count = valid.size
            if (count > 0) {
                LogManager.i("Flushing $count notifications from offline queue to Mac")
                for (item in valid) {
                    client.sendNotifPost(item.message)
                    LogManager.i("Notif sent (flushed): pkg=${item.message.pkg} key=${item.message.key}")
                }
            }
            offlineQueue.clear()
        }
    }

    fun handleIconNeed(context: Context, pkg: String, client: BridgeClient) {
        LogManager.i("Processing icon.need request for pkg=$pkg")
        val iconResult = getAppIconBase64(context, pkg)
        if (iconResult != null) {
            val (versionCode, iconBase64) = iconResult
            client.sendIconResp(pkg, versionCode, iconBase64)
            LogManager.i("Sent icon.resp for pkg=$pkg versionCode=$versionCode")
        } else {
            LogManager.e("Failed to generate icon for $pkg")
        }
    }

    private fun isDialerOrTelecom(context: Context, pkg: String): Boolean {
        if (pkg.contains("telecom") || pkg.contains("dialer") || pkg.contains("telephony") || pkg == "com.android.phone") {
            return true
        }
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        val defaultDialer = telecomManager?.defaultDialerPackage
        if (defaultDialer != null && defaultDialer == pkg) {
            return true
        }
        return false
    }

    private fun extractLargeIconBase64(context: Context, notification: Notification): String? {
        try {
            val bitmap: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                notification.getLargeIcon()?.loadDrawable(context)?.let { drawableToBitmap(it, 96, 96) }
            } else {
                @Suppress("DEPRECATION")
                notification.extras.getParcelable(Notification.EXTRA_LARGE_ICON) as? Bitmap
            }
            if (bitmap == null) return null

            // Scale down to max 96x96 maintaining aspect ratio
            val maxDim = 96
            val width = bitmap.width
            val height = bitmap.height
            val scaledBitmap = if (width > maxDim || height > maxDim) {
                val ratio = width.toFloat() / height.toFloat()
                val targetW: Int
                val targetH: Int
                if (ratio > 1) {
                    targetW = maxDim
                    targetH = (maxDim / ratio).toInt().coerceAtLeast(1)
                } else {
                    targetH = maxDim
                    targetW = (maxDim * ratio).toInt().coerceAtLeast(1)
                }
                Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
            } else {
                bitmap
            }

            val stream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            LogManager.d("Large icon extraction note: ${e.message}")
            return null
        }
    }

    fun getAppIconBase64(context: Context, pkg: String): Pair<Long, String>? {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(pkg, 0)
            val packageInfo = pm.getPackageInfo(pkg, 0)
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                packageInfo.versionCode.toLong()
            }
            val drawable = pm.getApplicationIcon(appInfo)
            val bitmap = drawableToBitmap(drawable, 128, 128)
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            Pair(versionCode, base64)
        } catch (e: Exception) {
            LogManager.e("Failed to get app icon for $pkg", e)
            null
        }
    }

    fun drawableToBitmap(drawable: Drawable, width: Int = 128, height: Int = 128): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return if (drawable.bitmap.width == width && drawable.bitmap.height == height) {
                drawable.bitmap
            } else {
                Bitmap.createScaledBitmap(drawable.bitmap, width, height, true)
            }
        }

        val bitmap = Bitmap.createBitmap(
            if (width > 0) width else 128,
            if (height > 0) height else 128,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
