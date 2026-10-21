package com.bridge.android.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

    private val syncRunnable = Runnable {
        val bridge = BridgeService.instance ?: return@Runnable
        bridge.handleClipboardChanged()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        instance = this
        LogManager.i("BridgeAccessibilityService connected and ready")

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
        isRunning = false
        instance = null
        mainHandler.removeCallbacksAndMessages(null)
        LogManager.i("BridgeAccessibilityService destroyed")
        super.onDestroy()
    }

    override fun onInterrupt() {
        LogManager.i("BridgeAccessibilityService interrupted")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        try {
            val pkgName = event.packageName?.toString() ?: ""
            if (pkgName == packageName) return // Ignore events from Bridge itself

            var detectedCopyAction = false

            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                    val text = event.text.joinToString(" ")
                    val desc = (event.contentDescription ?: "").toString()

                    if (isCopyOrCutIdentifier(text, desc, "")) {
                        LogManager.i("Accessibility: Copy action detected in $pkgName ($text / $desc)")
                        detectedCopyAction = true
                    } else {
                        // Safely inspect source node without leaking AccessibilityNodeInfo references
                        var sourceNode: AccessibilityNodeInfo? = null
                        try {
                            sourceNode = event.source
                            if (sourceNode != null && nodeMatchesCopyAction(sourceNode)) {
                                LogManager.i("Accessibility: Copy action found in node hierarchy of $pkgName")
                                detectedCopyAction = true
                            }
                        } catch (_: Throwable) {
                        } finally {
                            try {
                                @Suppress("DEPRECATION")
                                sourceNode?.recycle()
                            } catch (_: Throwable) {}
                        }
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
                    if (pkgName == "com.android.systemui" || pkgName.contains("launcher") || pkgName.contains("intentresolver")) {
                        val text = event.text.joinToString(" ").lowercase()
                        val desc = (event.contentDescription ?: "").toString().lowercase()
                        if (className.contains("Clipboard", ignoreCase = true) ||
                            className.contains("Overlay", ignoreCase = true) ||
                            className.contains("Toast", ignoreCase = true) ||
                            className.contains("Chooser", ignoreCase = true) ||
                            text.contains("copi") || desc.contains("copi") ||
                            text.contains("clip") || desc.contains("clip")
                        ) {
                            LogManager.i("Accessibility: SystemUI Clipboard/Share overlay appeared ($className)")
                            detectedCopyAction = true
                        }
                    }
                }
            }

            if (detectedCopyAction) {
                scheduleSpeculativeSync()
            }
        } catch (t: Throwable) {
            LogManager.d("Accessibility: Safe event catch: ${t.message}")
        }
    }

    private fun scheduleSpeculativeSync() {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime > 120) {
            lastTriggerTime = now
            LogManager.i("Accessibility: Scheduling copy sync in 100ms")
            mainHandler.removeCallbacks(syncRunnable)
            // Allow source app 100ms to commit data to system ClipboardService
            mainHandler.postDelayed(syncRunnable, 100)
        }
    }

    /**
     * Attempts to read the clipboard using a TYPE_ACCESSIBILITY_OVERLAY window.
     * This is a privileged window type that AccessibilityServices can create without
     * SYSTEM_ALERT_WINDOW permission, and it gains window focus to bypass Android 10+
     * background clipboard restrictions.
     */
    fun readClipboardViaAccessibilityOverlay(
        client: BridgeClient,
        syncManager: ClipboardSyncManager,
        onResult: (Boolean) -> Unit
    ) {
        if (isOverlayReading) {
            mainHandler.postDelayed({
                if (!isOverlayReading) {
                    readClipboardViaAccessibilityOverlay(client, syncManager, onResult)
                } else {
                    onResult(false)
                }
            }, 120)
            return
        }
        isOverlayReading = true

        // Safety timeout: auto-reset isOverlayReading after 1.5s
        mainHandler.postDelayed({
            if (isOverlayReading) {
                LogManager.d("Accessibility: Overlay safety timeout reached, resetting state")
                isOverlayReading = false
            }
        }, 1500)

        mainHandler.post {
            var overlayView: View? = null
            var windowManager: WindowManager? = null
            var focusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null
            var hasCompleted = false

            fun cleanup() {
                if (hasCompleted) return
                hasCompleted = true
                try {
                    val v = overlayView
                    val fl = focusListener
                    if (v != null && fl != null) {
                        try { v.viewTreeObserver.removeOnWindowFocusChangeListener(fl) } catch (_: Throwable) {}
                    }
                    val wm = windowManager
                    if (v != null && v.isAttachedToWindow && wm != null) {
                        wm.removeView(v)
                    }
                } catch (_: Throwable) {}
                focusListener = null
                overlayView = null
                windowManager = null
                isOverlayReading = false
            }

            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                if (wm == null) {
                    cleanup()
                    onResult(false)
                    return@post
                }
                windowManager = wm

                val dummyView = View(this).apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                }
                overlayView = dummyView

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

                fun attemptRead(retryCount: Int) {
                    if (hasCompleted) return
                    try {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val sent = syncManager.sendLocalClipboardToMac(
                            client,
                            cm,
                            force = false,
                            activeContext = this@BridgeAccessibilityService
                        )
                        if (sent) {
                            LogManager.i("Accessibility: Read and sent clipboard via overlay (sent=true)")
                            cleanup()
                            onResult(true)
                            return
                        }
                    } catch (e: Throwable) {
                        LogManager.e("Accessibility: Error reading overlay clipboard", e)
                    }

                    if (retryCount < 2) {
                        val delay = if (retryCount == 0) 100L else 180L
                        mainHandler.postDelayed({
                            attemptRead(retryCount + 1)
                        }, delay)
                    } else {
                        cleanup()
                        onResult(false)
                    }
                }

                val fl = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                    if (hasFocus && !hasCompleted) {
                        attemptRead(0)
                    }
                }
                focusListener = fl
                dummyView.viewTreeObserver.addOnWindowFocusChangeListener(fl)

                mainHandler.postDelayed({
                    if (!hasCompleted) attemptRead(0)
                }, 60)

            } catch (e: Throwable) {
                LogManager.e("Accessibility: Failed to add overlay view", e)
                cleanup()
                onResult(false)
            }
        }
    }

    private fun nodeMatchesCopyAction(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        try {
            val text = (node.text ?: "").toString()
            val desc = (node.contentDescription ?: "").toString()
            val viewId = (node.viewIdResourceName ?: "").toString()
            if (isCopyOrCutIdentifier(text, desc, viewId)) return true

            val count = node.childCount
            for (i in 0 until minOf(count, 6)) {
                var child: AccessibilityNodeInfo? = null
                try {
                    child = node.getChild(i)
                    if (child != null) {
                        val cText = (child.text ?: "").toString()
                        val cDesc = (child.contentDescription ?: "").toString()
                        val cViewId = (child.viewIdResourceName ?: "").toString()
                        if (isCopyOrCutIdentifier(cText, cDesc, cViewId)) {
                            return true
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    try {
                        @Suppress("DEPRECATION")
                        child?.recycle()
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
        return false
    }

    private fun isCopyOrCutIdentifier(text: String, desc: String, viewId: String): Boolean {
        val t = text.lowercase()
        val d = desc.lowercase()
        val v = viewId.lowercase()

        // 1. Core keywords in text, contentDescription, or viewId
        if (t.contains("copy") || t.contains("cut") ||
            d.contains("copy") || d.contains("cut") ||
            v.contains("copy") || v.contains("cut") ||
            v.contains("clipboard") || v.contains("pasteboard")
        ) return true

        // 2. Link & Image copy actions (e.g. "Copy link address", "Copy link", "Copy URL", "Copy image", "Copy photo")
        if (t.contains("link") || d.contains("link") || v.contains("link") ||
            t.contains("url") || d.contains("url") || v.contains("url") ||
            t.contains("image") || d.contains("image") || v.contains("image") ||
            t.contains("photo") || d.contains("photo") || v.contains("photo")
        ) {
            if (t.contains("cop") || d.contains("cop") || v.contains("cop") ||
                t.contains("address") || d.contains("address") ||
                t.contains("clip") || d.contains("clip")
            ) {
                return true
            }
        }

        // 3. Android ShareSheet / Chooser button identifiers
        if (v.contains("chooser_copy") || v.contains("copy_button") || v.contains("copy_link") ||
            v.contains("button_copy") || v.contains("action_copy") || v.contains("context_menu_copy") ||
            v.contains("url_action_container")
        ) return true

        // 4. Multilingual keywords
        if (t.contains("कॉपी") || d.contains("कॉपी") ||
            t.contains("copier") || t.contains("copiar") || t.contains("kopieren") ||
            t.contains("copia") || t.contains("salin") || t.contains("копир") ||
            t.contains("复制") || t.contains("複製") || t.contains("コピー") || t.contains("복사")
        ) return true

        return false
    }
}
