package com.godcross.magnifier

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs

/**
 * Image view with pinch-zoom, pan and double-tap zoom. Up to 12x past "fit to screen",
 * so a full-resolution photo can be inspected down to individual pixels.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {

    private val drawMatrix = Matrix()
    private val values = FloatArray(9)
    private var fitScale = 1f
    private val maxZoom = 12f

    /** Called with -1 (previous) or +1 (next) when the user flings sideways at fit zoom. */
    var onSwipe: ((Int) -> Unit)? = null
    var onSingleTap: (() -> Unit)? = null

    init {
        scaleType = ScaleType.MATRIX
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val current = currentScale()
            var factor = detector.scaleFactor
            val target = (current * factor).coerceIn(fitScale, fitScale * maxZoom)
            factor = target / current
            drawMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
            clampAndApply()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (currentScale() <= fitScale * 1.01f) return false
            drawMatrix.postTranslate(-dx, -dy)
            clampAndApply()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val current = currentScale()
            val target = if (current < fitScale * 1.5f) fitScale * 3f else fitScale
            val factor = target / current
            drawMatrix.postScale(factor, factor, e.x, e.y)
            clampAndApply()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTap?.invoke()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (currentScale() > fitScale * 1.01f || e1 == null) return false
            val dx = e2.x - e1.x
            if (abs(dx) > width / 5f && abs(vx) > abs(vy)) {
                onSwipe?.invoke(if (dx < 0) 1 else -1)
                return true
            }
            return false
        }
    })

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        resetZoom()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetZoom()
    }

    fun resetZoom() {
        val d = drawable ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (vw <= 0 || vh <= 0 || dw <= 0 || dh <= 0) return
        fitScale = minOf(vw / dw, vh / dh)
        drawMatrix.reset()
        drawMatrix.postScale(fitScale, fitScale)
        drawMatrix.postTranslate((vw - dw * fitScale) / 2f, (vh - dh * fitScale) / 2f)
        imageMatrix = drawMatrix
    }

    private fun currentScale(): Float {
        drawMatrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun clampAndApply() {
        val d = drawable ?: return
        val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        drawMatrix.mapRect(rect)
        var dx = 0f
        var dy = 0f
        val vw = width.toFloat()
        val vh = height.toFloat()
        dx = if (rect.width() <= vw) (vw - rect.width()) / 2f - rect.left
        else if (rect.left > 0) -rect.left
        else if (rect.right < vw) vw - rect.right
        else 0f
        dy = if (rect.height() <= vh) (vh - rect.height()) / 2f - rect.top
        else if (rect.top > 0) -rect.top
        else if (rect.bottom < vh) vh - rect.bottom
        else 0f
        drawMatrix.postTranslate(dx, dy)
        imageMatrix = drawMatrix
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        parent?.requestDisallowInterceptTouchEvent(currentScale() > fitScale * 1.01f)
        return true
    }
}
