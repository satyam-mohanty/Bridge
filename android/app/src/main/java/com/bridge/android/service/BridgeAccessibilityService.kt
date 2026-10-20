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
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastTriggerTime = 0L
    @Volatile
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
                // Delegate to BridgeService which has the full fallback chain
                val bridge = BridgeService.instance ?: return@postDelayed
                bridge.handleClipboardChanged()
            }, 60)
        }
    }

    /**
     * Attempts to read the clipboard using a TYPE_ACCESSIBILITY_OVERLAY window.
     * This is a privileged window type that AccessibilityServices can create without
     * SYSTEM_ALERT_WINDOW permission, and it can gain window focus to bypass Android 10+
     * background clipboard restrictions.
     *
     * @param onResult Called with true if clipboard was read and sent, false otherwise.
     */
    fun readClipboardViaAccessibilityOverlay(
        client: BridgeClient,
        syncManager: ClipboardSyncManager,
        onResult: (Boolean) -> Unit
    ) {
        if (isOverlayReading) {
            onResult(false)
            return
        }
        isOverlayReading = true

        // Safety timeout: auto-reset isOverlayReading after 2 seconds
        mainHandler.postDelayed({
            if (isOverlayReading) {
                LogManager.d("Accessibility: Overlay safety timeout reached, resetting state")
                isOverlayReading = false
            }
        }, 2000)

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

                // Allow 120ms for WindowManager to establish focus (was 35ms — too short)
                mainHandler.postDelayed({
                    try {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val sent = syncManager.sendLocalClipboardToMac(client, cm, force = false)
                        LogManager.i("Accessibility: Read clipboard via overlay (sent=$sent)")
                        onResult(sent)
                    } catch (e: Exception) {
                        LogManager.e("Accessibility: Error reading overlay clipboard", e)
                        onResult(false)
                    } finally {
                        cleanup()
                    }
                }, 120)
            } catch (e: Exception) {
                LogManager.e("Accessibility: Failed to add overlay view", e)
                cleanup()
                onResult(false)
            }
        }
    }

    private fun isCopyOrCutIdentifier(text: String, desc: String, viewId: String): Boolean {
        // Standard English identifiers
        if (text.contains("copy") || text.contains("cut")) return true
        if (desc.contains("copy") || desc.contains("cut")) return true
        if (viewId.contains("copy") || viewId.contains("cut")) return true
        if (viewId.contains("clipboard")) return true

        // Link copy actions (e.g. "Copy link", "Copy address", "Copy URL", "Copy link to clipboard")
        if (text.contains("link") || desc.contains("link") || viewId.contains("link") ||
            text.contains("url") || desc.contains("url") || viewId.contains("url")
        ) {
            if (text.contains("cop") || desc.contains("cop") || viewId.contains("cop") ||
                text.contains("address") || desc.contains("address")
            ) {
                return true
            }
        }

        // Multilingual identifiers
        if (text.contains("कॉपी") || desc.contains("कॉपी")) return true
        if (text.contains("copier") || text.contains("copiar") || text.contains("kopieren") || text.contains("copia") || text.contains("salin")) return true
        return false
    }
}
