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

    private var here = -1
    private var walk = 0
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 213, 79); style = Paint.Style.FILL }
    private val path = android.graphics.Path()

    fun update(newBins: BooleanArray, crosshair: Boolean) {
        bins = newBins.copyOf()
        showCrosshair = crosshair
        invalidate()
    }

    /** Where the phone is on the ring ([bin], -1 = hide) and which way to walk to the nearest gap (+1 / -1, 0 = none). */
    fun setPosition(bin: Int, direction: Int) {
        if (bin == here && direction == walk) return
        here = bin; walk = direction
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
        if (here !in bins.indices) return
        // You are here: a dot on the ring; an arrow beside it points the way to walk.
        val a = Math.toRadians((90.0 + (here + 0.5) * sweep))
        val px = cx + r * kotlin.math.cos(a).toFloat(); val py = cy + r * kotlin.math.sin(a).toFloat()
        canvas.drawCircle(px, py, 9f * density, markPaint)
        if (walk != 0) {
            val tx = -kotlin.math.sin(a).toFloat() * walk; val ty = kotlin.math.cos(a).toFloat() * walk   // along the ring
            val nx = kotlin.math.cos(a).toFloat(); val ny = kotlin.math.sin(a).toFloat()
            val base = 18f * density; val len = 22f * density; val half = 11f * density
            val bx = px + tx * base; val by = py + ty * base
            path.reset()
            path.moveTo(bx + tx * len, by + ty * len)
            path.lineTo(bx + nx * half, by + ny * half)
            path.lineTo(bx - nx * half, by - ny * half)
            path.close()
            canvas.drawPath(path, arrowPaint)
        }
    }
}
