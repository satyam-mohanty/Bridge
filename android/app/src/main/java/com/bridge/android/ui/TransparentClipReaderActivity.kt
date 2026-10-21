package com.bridge.android.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import com.bridge.android.service.BridgeService
import com.bridge.android.util.LogManager

class TransparentClipReaderActivity : Activity() {
    private var readAttempted = false

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
            if (!isFinishing && !readAttempted) {
                attemptReadAndFinish()
            }
        }, 350)
    }

    override fun onResume() {
        super.onResume()
        window.decorView.post {
            if (hasWindowFocus() && !readAttempted) {
                attemptReadAndFinish()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !readAttempted) {
            attemptReadAndFinish()
        }
    }

    private fun attemptReadAndFinish() {
        if (readAttempted) return
        readAttempted = true
        try {
            val service = BridgeService.instance
            if (service != null && service.isConnectedFlow.value) {
                val clipManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val force = intent?.getBooleanExtra("force", false) ?: false
                val sent = service.getClipboardSyncManager().sendLocalClipboardToMac(
                    service.getClient(),
                    clipManager,
                    force = force,
                    activeContext = this
                )
                LogManager.i("TransparentClipReader: Captured clipboard with focus (force=$force, sent=$sent)")
            }
        } catch (e: Exception) {
            LogManager.e("Error reading clipboard in TransparentClipReader", e)
        } finally {
            finish()
            overridePendingTransition(0, 0)
        }
    }
}

