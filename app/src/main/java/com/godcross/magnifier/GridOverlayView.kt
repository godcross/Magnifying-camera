package com.godcross.magnifier

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Rule-of-thirds grid plus a centre crosshair for lining up tiny parts like a stylus tip. */
class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        strokeWidth = 1f * density
        style = Paint.Style.STROKE
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFD54F.toInt()
        strokeWidth = 1.5f * density
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 1..2) {
            canvas.drawLine(w * i / 3f, 0f, w * i / 3f, h, linePaint)
            canvas.drawLine(0f, h * i / 3f, w, h * i / 3f, linePaint)
        }
        val cx = w / 2f
        val cy = h / 2f
        val arm = 18f * density
        val gap = 5f * density
        canvas.drawLine(cx - arm, cy, cx - gap, cy, crossPaint)
        canvas.drawLine(cx + gap, cy, cx + arm, cy, crossPaint)
        canvas.drawLine(cx, cy - arm, cx, cy - gap, crossPaint)
        canvas.drawLine(cx, cy + gap, cx, cy + arm, crossPaint)
    }
}
