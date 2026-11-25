package com.headscan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.headscan.core.Keyframe

/** Draws the face mesh vertices onto a captured photo using the same projection the texture baker uses. */
object AlignmentCheck {
    fun draw(kf: Keyframe, faceVertices: FloatArray): Bitmap? {
        val src = BitmapFactory.decodeFile(kf.colorRef) ?: return null
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        src.recycle()
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GREEN }
        val m = kf.worldToCam
        val ci = kf.colorIntrinsics.scaledTo(bmp.width, bmp.height)
        for (i in 0 until faceVertices.size / 3) {
            val x = faceVertices[3 * i]; val y = faceVertices[3 * i + 1]; val z = faceVertices[3 * i + 2]
            val cx = m[0] * x + m[4] * y + m[8] * z + m[12]
            val cy = m[1] * x + m[5] * y + m[9] * z + m[13]
            val cz = m[2] * x + m[6] * y + m[10] * z + m[14]
            if (cz > -0.01f) continue
            val u = ci.fx * cx / -cz + ci.cx
            val v = ci.cy - ci.fy * cy / -cz
            canvas.drawCircle(u, v, 2f, paint)
        }
        return bmp
    }
}
