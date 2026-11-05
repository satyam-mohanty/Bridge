package com.bridge.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver

/** Draws a live, blurred snapshot of the page beneath the floating navigation dock. */
class LiquidGlassBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var backdropView: View? = null
    private var backdropBitmap: Bitmap? = null
    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bitmapRect = Rect()
    private val blurRadius = 24f * context.resources.displayMetrics.density

    private val scrollListener = ViewTreeObserver.OnScrollChangedListener {
        invalidate()
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setRenderEffect(RenderEffect.createBlurEffect(
                blurRadius,
                blurRadius,
                Shader.TileMode.CLAMP
            ))
        }
        isFocusable = false
        isClickable = false
    }

    fun setBackdropView(view: View) {
        if (backdropView === view) return
        backdropView?.viewTreeObserver?.removeOnScrollChangedListener(scrollListener)
        backdropView = view
        view.viewTreeObserver.addOnScrollChangedListener(scrollListener)
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> invalidate() }
        view.viewTreeObserver.addOnGlobalLayoutListener { invalidate() }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val source = backdropView ?: return
        if (width <= 0 || height <= 0) return

        try {
            val bitmap = ensureBitmap()
            bitmap.eraseColor(Color.TRANSPARENT)

            val sourcePosition = IntArray(2)
            val dockPosition = IntArray(2)
            source.getLocationInWindow(sourcePosition)
            getLocationInWindow(dockPosition)

            val snapshotCanvas = Canvas(bitmap)
            snapshotCanvas.translate(
                (sourcePosition[0] - dockPosition[0]).toFloat(),
                (sourcePosition[1] - dockPosition[1]).toFloat()
            )
            source.draw(snapshotCanvas)

            bitmapRect.set(0, 0, bitmap.width, bitmap.height)
            canvas.drawBitmap(bitmap, bitmapRect, bitmapRect, blurPaint)
        } catch (_: Exception) {
            // Fallback gracefully
        }
    }

    private fun ensureBitmap(): Bitmap {
        val current = backdropBitmap
        if (current != null && current.width == width && current.height == height) return current
        current?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            backdropBitmap = it
        }
    }

    override fun onDetachedFromWindow() {
        backdropView?.viewTreeObserver?.removeOnScrollChangedListener(scrollListener)
        backdropBitmap?.recycle()
        backdropBitmap = null
        super.onDetachedFromWindow()
    }
}
