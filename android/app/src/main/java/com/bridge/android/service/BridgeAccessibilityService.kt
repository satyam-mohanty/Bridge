package com.bridge.android.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        instance = this
        LogManager.i("BridgeAccessibilityService connected and monitoring copy events")

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 80
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        serviceInfo = info
    }

    override fun onDestroy() {
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

            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                // In some editors, selection collapse or context menu dismissal occurs on copy
            }
        }

        if (detectedCopyAction) {
            val now = System.currentTimeMillis()
            if (now - lastTriggerTime > 200) {
                lastTriggerTime = now
                LogManager.i("Accessibility: Triggering clipboard sync to Mac")
                // Allow source app 75ms to commit data to system ClipboardService
                mainHandler.postDelayed({
                    triggerSync()
                }, 75)
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
        if (text.contains("copier") || text.contains("copiar") || text.contains("kopieren")) return true
        return false
    }

    private fun triggerSync() {
        val bridge = BridgeService.instance
        if (bridge != null && bridge.isConnectedFlow.value) {
            bridge.handleClipboardChanged()
        }
    }
}
