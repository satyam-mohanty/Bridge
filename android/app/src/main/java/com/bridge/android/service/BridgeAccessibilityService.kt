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

    private val clipListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
        LogManager.i("Accessibility: ClipboardManager onPrimaryClipChanged event detected")
        scheduleSpeculativeSync()
    }

    private val syncRunnable = Runnable {
        val bridge = BridgeService.instance ?: return@Runnable
        bridge.handleClipboardChanged()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        instance = this
        LogManager.i("BridgeAccessibilityService connected and ready")

        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.addPrimaryClipChangedListener(clipListener)
            LogManager.i("Accessibility: Registered OnPrimaryClipChangedListener")
        } catch (e: Throwable) {
            LogManager.d("Failed to register clipListener in A11y: ${e.message}")
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
        isRunning = false
        instance = null
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.removePrimaryClipChangedListener(clipListener)
        } catch (_: Throwable) {}
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
                    val text = event.text.joinToString(" ").lowercase()
                    val desc = (event.contentDescription ?: "").toString().lowercase()
                    if (className.contains("Clipboard", ignoreCase = true) ||
                        className.contains("Overlay", ignoreCase = true) ||
                        className.contains("Toast", ignoreCase = true) ||
                        className.contains("Chooser", ignoreCase = true) ||
                        pkgName.contains("clipboard", ignoreCase = true) ||
                        text.contains("copi") || desc.contains("copi") ||
                        text.contains("clip") || desc.contains("clip")
                    ) {
                        LogManager.i("Accessibility: Clipboard/Share UI state appeared in $pkgName ($className)")
                        detectedCopyAction = true
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
        if (now - lastTriggerTime > 100) {
            lastTriggerTime = now
            LogManager.i("Accessibility: Scheduling copy sync in 80ms")
            mainHandler.removeCallbacks(syncRunnable)
            mainHandler.postDelayed(syncRunnable, 80)
        }
    }

    private fun nodeMatchesCopyAction(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        try {
            val text = (node.text ?: "").toString()
            val desc = (node.contentDescription ?: "").toString()
            val viewId = (node.viewIdResourceName ?: "").toString()
            if (isCopyOrCutIdentifier(text, desc, viewId)) return true

            // Safely check parent node
            var parent: AccessibilityNodeInfo? = null
            try {
                parent = node.parent
                if (parent != null) {
                    val pText = (parent.text ?: "").toString()
                    val pDesc = (parent.contentDescription ?: "").toString()
                    val pViewId = (parent.viewIdResourceName ?: "").toString()
                    if (isCopyOrCutIdentifier(pText, pDesc, pViewId)) return true
                }
            } catch (_: Throwable) {
            } finally {
                try {
                    @Suppress("DEPRECATION")
                    parent?.recycle()
                } catch (_: Throwable) {}
            }

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
