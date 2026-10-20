package com.bridge.android.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.bridge.android.clipboard.ClipboardSyncManager
import com.bridge.android.network.BridgeClient
import com.bridge.android.util.LogManager

class BridgeAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var instance: BridgeAccessibilityService? = null
            private set

        fun triggerSyncFromService() {
            instance?.triggerSync()
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastTriggerTime = 0L
    private var lastSelectedText: String? = null
    private var isOverlayReading = false

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        LogManager.i("Accessibility: ClipboardManager onPrimaryClipChanged event detected")
        scheduleSync()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        instance = this
        LogManager.i("BridgeAccessibilityService connected and monitoring copy events")

        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.addPrimaryClipChangedListener(clipListener)
            LogManager.i("Accessibility: Registered OnPrimaryClipChangedListener")
        } catch (e: Exception) {
            LogManager.e("Accessibility: Failed to register OnPrimaryClipChangedListener", e)
        }

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 50
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        serviceInfo = info
    }

    override fun onDestroy() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.removePrimaryClipChangedListener(clipListener)
        } catch (_: Exception) {}

        isRunning = false
        instance = null
        LogManager.i("BridgeAccessibilityService destroyed")
        super.onDestroy()
    }

    override fun onInterrupt() {
        LogManager.i("BridgeAccessibilityService interrupted")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkgName = event.packageName?.toString() ?: ""
        if (pkgName == packageName) return // Ignore events from Bridge itself

        var detectedCopyAction = false

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val node = try { event.source } catch (_: Exception) { null }
                if (node != null) {
                    try {
                        val text = node.text
                        if (text != null && text.isNotEmpty()) {
                            val start = node.textSelectionStart
                            val end = node.textSelectionEnd
                            if (start >= 0 && end >= 0 && start != end) {
                                val min = minOf(start, end)
                                val max = maxOf(start, end)
                                if (min >= 0 && max <= text.length) {
                                    lastSelectedText = text.subSequence(min, max).toString()
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                val text = event.text.joinToString(" ").lowercase()
                val desc = (event.contentDescription ?: "").toString().lowercase()
                val sourceNode = try { event.source } catch (_: Exception) { null }
                val viewId = (sourceNode?.viewIdResourceName ?: "").lowercase()
                val nodeText = (sourceNode?.text ?: "").toString().lowercase()
                val nodeDesc = (sourceNode?.contentDescription ?: "").toString().lowercase()

                if (isCopyOrCutIdentifier(text, desc, viewId) ||
                    isCopyOrCutIdentifier(nodeText, nodeDesc, viewId)
                ) {
                    LogManager.i("Accessibility: Copy/Cut action clicked in $pkgName ($text / $desc / $viewId)")
                    detectedCopyAction = true
                }
            }

            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val text = event.text.joinToString(" ").lowercase()
                if (text.contains("copi") || text.contains("copied") || text.contains("clipboard") || text.contains("copy")) {
                    LogManager.i("Accessibility: Clipboard toast notification detected from $pkgName ($text)")
                    detectedCopyAction = true
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val className = (event.className ?: "").toString()
                // Android 13+ SystemUI clipboard overlay popup detection
                if (pkgName == "com.android.systemui") {
                    val text = event.text.joinToString(" ").lowercase()
                    val desc = (event.contentDescription ?: "").toString().lowercase()
                    if (className.contains("Clipboard", ignoreCase = true) ||
                        className.contains("Overlay", ignoreCase = true) ||
                        className.contains("Toast", ignoreCase = true) ||
                        text.contains("copi") || desc.contains("copi") ||
                        text.contains("clip") || desc.contains("clip")
                    ) {
                        LogManager.i("Accessibility: SystemUI Clipboard overlay appeared ($className, text=$text)")
                        detectedCopyAction = true
                    }
                }
            }
        }

        if (detectedCopyAction) {
            scheduleSync()
        }
    }

    private fun scheduleSync() {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime > 150) {
            lastTriggerTime = now
            LogManager.i("Accessibility: Scheduling clipboard sync to Mac")
            // Allow source app 60ms to commit data to system ClipboardService
            mainHandler.postDelayed({
                triggerSync()
            }, 60)
        }
    }

    fun triggerSync() {
        val bridge = BridgeService.instance ?: return
        if (!bridge.isConnectedFlow.value) return
        val client = bridge.getClient()
        val syncManager = bridge.getClipboardSyncManager()

        // 1. Try reading directly using AccessibilityService context
        try {
            val clipManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val sent = syncManager.sendLocalClipboardToMac(client, clipManager, force = false)
            if (sent) {
                LogManager.i("Accessibility: Clipboard sent to Mac directly")
                return
            }
        } catch (e: Exception) {
            LogManager.d("Accessibility: Direct clipboard read returned false or threw: ${e.message}")
        }

        // 2. Direct read failed (e.g. Android 10+ background restriction).
        // Use TYPE_ACCESSIBILITY_OVERLAY to gain instantaneous window focus without requiring SYSTEM_ALERT_WINDOW permission.
        readClipboardWithAccessibilityOverlay(client, syncManager)
    }

    private fun readClipboardWithAccessibilityOverlay(
        client: BridgeClient,
        syncManager: ClipboardSyncManager
    ) {
        if (isOverlayReading) return
        isOverlayReading = true

        mainHandler.post {
            var overlayView: View? = null
            var windowManager: WindowManager? = null

            fun cleanup() {
                try {
                    val v = overlayView
                    val wm = windowManager
                    if (v != null && v.isAttachedToWindow && wm != null) {
                        wm.removeViewImmediate(v)
                    }
                } catch (_: Exception) {}
                overlayView = null
                windowManager = null
                isOverlayReading = false
            }

            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                windowManager = wm

                val dummyView = View(this).apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                }
                overlayView = dummyView

                // TYPE_ACCESSIBILITY_OVERLAY is a privileged window type granted natively
                // to AccessibilityServices without needing SYSTEM_ALERT_WINDOW permission.
                val params = WindowManager.LayoutParams(
                    1, 1,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.START or Gravity.TOP
                    x = 0
                    y = 0
                }

                wm.addView(dummyView, params)
                dummyView.requestFocus()

                // Allow 35ms for WindowManager to establish focus
                mainHandler.postDelayed({
                    try {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val sent = syncManager.sendLocalClipboardToMac(client, cm, force = false)
                        LogManager.i("Accessibility: Read clipboard via overlay (sent=$sent)")

                        // Fallback: If primaryClip was empty/null, check lastSelectedText
                        if (!sent && !lastSelectedText.isNullOrEmpty()) {
                            val textSent = syncManager.sendLocalTextToMac(client, lastSelectedText!!, force = false)
                            LogManager.i("Accessibility: Sent lastSelectedText fallback to Mac (sent=$textSent)")
                        }
                    } catch (e: Exception) {
                        LogManager.e("Accessibility: Error reading overlay clipboard", e)
                    } finally {
                        cleanup()
                    }
                }, 35)
            } catch (e: Exception) {
                LogManager.e("Accessibility: Failed to add overlay view", e)
                cleanup()

                // Fallback: If overlay creation failed, send lastSelectedText if available
                if (!lastSelectedText.isNullOrEmpty()) {
                    syncManager.sendLocalTextToMac(client, lastSelectedText!!, force = false)
                }
            }
        }
    }

    private fun isCopyOrCutIdentifier(text: String, desc: String, viewId: String): Boolean {
        // Standard English identifiers
        if (text.contains("copy") || text.contains("cut")) return true
        if (desc.contains("copy") || desc.contains("cut")) return true
        if (viewId.contains("copy") || viewId.contains("cut")) return true
        if (viewId.contains("clipboard")) return true

        // Multilingual identifiers
        if (text.contains("कॉपी") || desc.contains("कॉपी")) return true
        if (text.contains("copier") || text.contains("copiar") || text.contains("kopieren") || text.contains("copia") || text.contains("salin")) return true
        return false
    }
}
