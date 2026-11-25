package com.headscan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Overlay with an aiming crosshair and a ring showing which angles around the head have been captured. */
class CoverageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var bins: BooleanArray = BooleanArray(0)
    private var showCrosshair = true

    private val density = resources.displayMetrics.density
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f * density
        strokeCap = Paint.Cap.BUTT
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val oval = RectF()

    fun update(newBins: BooleanArray, crosshair: Boolean) {
        bins = newBins.copyOf()
        showCrosshair = crosshair
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        if (showCrosshair) {
            val s = 18f * density
            canvas.drawLine(cx - s, cy, cx + s, cy, crossPaint)
            canvas.drawLine(cx, cy - s, cx, cy + s, crossPaint)
            canvas.drawCircle(cx, cy, s * 0.4f, crossPaint)
        }
        if (bins.isEmpty()) return
        val r = min(width, height) * 0.42f
        oval.set(cx - r, cy - r, cx + r, cy + r)
        val sweep = 360f / bins.size
        for (i in bins.indices) {
            ringPaint.color = if (bins[i]) Color.argb(230, 67, 160, 71) else Color.argb(120, 255, 255, 255)
            // Bin 0 = the starting (front) view, drawn at the bottom of the ring, i.e. toward the user.
            canvas.drawArc(oval, 90f + i * sweep + 1f, sweep - 2f, false, ringPaint)
        }
    }
}
