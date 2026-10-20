package com.bridge.android.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.bridge.android.service.BridgeService
import com.bridge.android.util.LogManager

object OverlayClipReader {
    private var isReading = false
    private var lastReadTime = 0L

    fun readClipboardWithOverlay(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (isReading || (now - lastReadTime < 250)) {
            LogManager.d("OverlayClipReader: Skipping duplicate read request (isReading=$isReading)")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            LogManager.i("OverlayClipReader: SYSTEM_ALERT_WINDOW not granted, falling back to activity")
            BridgeService.instance?.triggerTransparentClipReader(force)
            return
        }

        isReading = true
        lastReadTime = now

        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            var targetView: View? = null
            var windowMgr: WindowManager? = null

            fun cleanup() {
                try {
                    val v = targetView
                    val w = windowMgr
                    if (v != null && v.isAttachedToWindow && w != null) {
                        w.removeViewImmediate(v)
                    }
                } catch (_: Exception) {}
                targetView = null
                windowMgr = null
                isReading = false
            }

            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                windowMgr = wm
                val dummyView = View(context).apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                }
                targetView = dummyView

                val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    1, 1,
                    layoutType,
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

                // Wait 40ms for WindowManager to establish focus
                mainHandler.postDelayed({
                    try {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val service = BridgeService.instance
                        if (service != null && service.isConnectedFlow.value) {
                            val sent = service.getClipboardSyncManager().sendLocalClipboardToMac(
                                service.getClient(),
                                cm,
                                force = force
                            )
                            LogManager.i("OverlayClipReader: Read clipboard with focus (sent=$sent)")
                        }
                    } catch (e: Exception) {
                        LogManager.e("OverlayClipReader: Error reading clipboard", e)
                    } finally {
                        cleanup()
                    }
                }, 40)

                // Safety timeout to ensure view is removed even if delay is stalled
                mainHandler.postDelayed({
                    cleanup()
                }, 350)

            } catch (e: Exception) {
                LogManager.e("OverlayClipReader: Failed to add overlay view", e)
                cleanup()
                BridgeService.instance?.triggerTransparentClipReader(force)
            }
        }
    }
}
