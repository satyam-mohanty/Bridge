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
        val view = android.view.View(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        setContentView(view)
        view.requestFocus()

        // Safety timeout to avoid remaining alive
        window.decorView.postDelayed({
            if (!isFinishing) {
                attemptReadAndFinish()
            }
        }, 500)
    }

    override fun onResume() {
        super.onResume()
        if (hasWindowFocus()) {
            attemptReadAndFinish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
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
                val sent = service.getClipboardSyncManager().sendLocalClipboardToMac(service.getClient(), clipManager, force = true)
                LogManager.i("TransparentClipReader: Captured clipboard with focus (sent=$sent)")
            }
        } catch (e: Exception) {
            LogManager.e("Error reading clipboard in TransparentClipReader", e)
        } finally {
            finish()
            overridePendingTransition(0, 0)
        }
    }
}
