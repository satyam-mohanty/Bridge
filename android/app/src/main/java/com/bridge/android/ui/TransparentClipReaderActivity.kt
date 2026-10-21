package com.bridge.android.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.bridge.android.service.BridgeService
import com.bridge.android.util.LogManager

class TransparentClipReaderActivity : Activity() {
    private var hasSucceeded = false
    private var isExecuting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasSucceeded = false
        isExecuting = false
        LogManager.i("TransparentClipReader: onCreate")

        // Ensure window is focusable and can receive input focus cleanly
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)

        val view = View(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.requestFocus()

        val setClipText = intent?.getStringExtra("set_clip")
        if (!setClipText.isNullOrEmpty()) {
            try {
                val clipManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipManager.setPrimaryClip(android.content.ClipData.newPlainText("test", setClipText))
                LogManager.i("TransparentClipReader: Set clipboard while in foreground ($setClipText)")
            } catch (e: Throwable) {
                LogManager.e("Failed to set clip in TransparentClipReader", e)
            }
        }

        // Immediate read attempt
        attemptReadAndFinish(0)

        // Safety timeout to ensure activity dismisses cleanly without lingering
        window.decorView.postDelayed({
            if (!isFinishing && !hasSucceeded) {
                LogManager.i("TransparentClipReader: Safety timeout (500ms) fired")
                safeDismiss()
            }
        }, 500)
    }

    override fun onResume() {
        super.onResume()
        LogManager.i("TransparentClipReader: onResume (hasWindowFocus=${hasWindowFocus()})")
        if (!hasSucceeded && !isFinishing) {
            attemptReadAndFinish(0)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        LogManager.i("TransparentClipReader: onWindowFocusChanged (hasFocus=$hasFocus)")
        if (hasFocus && !hasSucceeded && !isFinishing) {
            attemptReadAndFinish(0)
        }
    }

    private fun attemptReadAndFinish(retryCount: Int) {
        if (hasSucceeded || isFinishing) return
        if (isExecuting && retryCount == 0) return
        isExecuting = true

        var sent = false
        try {
            val service = BridgeService.instance
            if (service == null) {
                LogManager.w("TransparentClipReader: BridgeService.instance is null")
            } else if (!service.isConnectedFlow.value) {
                LogManager.w("TransparentClipReader: BridgeService is not connected to Mac")
            } else {
                val clipManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = try { clipManager.primaryClip } catch (t: Throwable) {
                    LogManager.e("TransparentClipReader: clipManager.primaryClip threw exception", t)
                    null
                }
                if (clip == null) {
                    LogManager.d("TransparentClipReader: primaryClip is null (retry=$retryCount, focus=${hasWindowFocus()})")
                } else if (clip.itemCount == 0) {
                    LogManager.d("TransparentClipReader: primaryClip has 0 items (retry=$retryCount)")
                } else {
                    val force = intent?.getBooleanExtra("force", false) ?: false
                    sent = service.getClipboardSyncManager().sendLocalClipboardToMac(
                        service.getClient(),
                        clipManager,
                        force = force,
                        activeContext = this
                    )
                    LogManager.i("TransparentClipReader: Dispatched clipboard with focus (sent=$sent, force=$force, retry=$retryCount, items=${clip.itemCount})")
                }
            }
        } catch (e: Exception) {
            LogManager.e("Error reading clipboard in TransparentClipReader", e)
        } finally {
            isExecuting = false
        }

        if (sent || retryCount >= 4) {
            hasSucceeded = true
            safeDismiss()
        } else {
            // Source app might be finishing write or window focus arriving; wait 40ms and retry
            window.decorView.postDelayed({
                if (!isFinishing && !hasSucceeded) {
                    attemptReadAndFinish(retryCount + 1)
                }
            }, 40)
        }
    }

    private fun safeDismiss() {
        finish()
        overridePendingTransition(0, 0)
    }
}
