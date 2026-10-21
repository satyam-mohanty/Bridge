package com.bridge.android.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import com.bridge.android.service.BridgeService
import com.bridge.android.util.LogManager

class TransparentClipReaderActivity : Activity() {
    private var hasSucceeded = false
    private var isExecuting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        )
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)

        val view = android.view.View(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        setContentView(view)
        view.requestFocus()

        // Safety timeout to ensure activity dismisses cleanly without lingering
        window.decorView.postDelayed({
            if (!isFinishing && !hasSucceeded) {
                attemptReadAndFinish(2)
            }
        }, 400)
    }

    override fun onResume() {
        super.onResume()
        window.decorView.post {
            if (hasWindowFocus() && !hasSucceeded) {
                attemptReadAndFinish(0)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !hasSucceeded) {
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
            if (service != null && service.isConnectedFlow.value) {
                val clipManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val force = intent?.getBooleanExtra("force", false) ?: false
                sent = service.getClipboardSyncManager().sendLocalClipboardToMac(
                    service.getClient(),
                    clipManager,
                    force = force,
                    activeContext = this
                )
                LogManager.i("TransparentClipReader: Captured clipboard with focus (force=$force, sent=$sent, retry=$retryCount)")
            }
        } catch (e: Exception) {
            LogManager.e("Error reading clipboard in TransparentClipReader", e)
        } finally {
            isExecuting = false
        }

        if (sent || retryCount >= 2) {
            hasSucceeded = true
            finish()
            overridePendingTransition(0, 0)
        } else {
            // Source app might be finishing write; wait 60ms and retry
            window.decorView.postDelayed({
                if (!isFinishing && !hasSucceeded) {
                    attemptReadAndFinish(retryCount + 1)
                }
            }, 60)
        }
    }
}

