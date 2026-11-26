package com.headscan

import android.media.Image
import com.google.ar.core.CameraIntrinsics
import com.headscan.core.Intrinsics
import java.nio.ByteOrder

object ImageUtils {
    /** Converts a YUV_420_888 camera image to an NV21 byte array (handles any row/pixel stride). */
    fun yuvToNv21(image: Image): ByteArray {
        val w = image.width
        val h = image.height
        val out = ByteArray(w * h + 2 * (w / 2) * (h / 2))
        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer
        val yRow = yPlane.rowStride
        val yPix = yPlane.pixelStride
        var pos = 0
        for (row in 0 until h) {
            if (yPix == 1) {
                yBuf.position(row * yRow)
                yBuf.get(out, pos, w)
                pos += w
            } else {
                for (col in 0 until w) out[pos++] = yBuf.get(row * yRow + col * yPix)
            }
        }
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uRow = uPlane.rowStride; val uPix = uPlane.pixelStride
        val vRow = vPlane.rowStride; val vPix = vPlane.pixelStride
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                out[pos++] = vBuf.get(row * vRow + col * vPix)
                out[pos++] = uBuf.get(row * uRow + col * uPix)
            }
        }
        return out
    }

    /** Copies a DEPTH16 image (millimetres) into a tightly packed ShortArray. */
    fun depthToShorts(image: Image): ShortArray {
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        val buf = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride
        val out = ShortArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            out[y * w + x] = buf.getShort(y * rowStride + x * pixStride)
        }
        return out
    }

    /** Median depth (metres) in a small window at the image centre, or 0 if none. */
    fun centerDepth(depth: ShortArray, w: Int, h: Int, radius: Int = 4): Float {
        val values = ArrayList<Int>()
        for (dy in -radius..radius) for (dx in -radius..radius) {
            val x = w / 2 + dx
            val y = h / 2 + dy
            if (x < 0 || y < 0 || x >= w || y >= h) continue
            val d = depth[y * w + x].toInt() and 0xFFFF
            if (d > 0) values.add(d)
        }
        if (values.isEmpty()) return 0f
        values.sort()
        return values[values.size / 2] * 0.001f
    }

    /**
     * Finds the head without aiming: the nearest solid patch of depth (20 cm to 1.3 m) in the middle 70% of the
     * view. Returns (u, v, depth m) in depth-image pixels, or null.
     */
    fun nearestHead(depth: ShortArray, w: Int, h: Int): Triple<Float, Float, Float>? {
        val cells = 12
        val cw = w / cells; val ch = h / cells
        if (cw < 1 || ch < 1) return null
        val med = FloatArray(cells * cells)
        val vals = ArrayList<Int>()
        for (gy in 0 until cells) for (gx in 0 until cells) {
            vals.clear()
            for (y in gy * ch until (gy + 1) * ch) for (x in gx * cw until (gx + 1) * cw) {
                val d = depth[y * w + x].toInt() and 0xFFFF
                if (d in 200..1300) vals.add(d)
            }
            // Mostly filled cells only, so specks of noise don't count.
            med[gy * cells + gx] = if (vals.size > cw * ch / 2) { vals.sort(); vals[vals.size / 2] * 0.001f } else Float.NaN
        }
        var best = -1; var bestD = Float.MAX_VALUE
        val lo = (cells * 0.15f).toInt(); val hi = cells - 1 - lo
        for (gy in lo..hi) for (gx in lo..hi) {
            val d = med[gy * cells + gx]
            if (d.isNaN()) continue
            // Needs neighbours at a similar distance: a head, not a stray edge.
            var near = 0
            for (oy in -1..1) for (ox in -1..1) {
                val n = med[(gy + oy) * cells + gx + ox]
                if (!n.isNaN() && kotlin.math.abs(n - d) < 0.08f) near++
            }
            if (near >= 5 && d < bestD) { bestD = d; best = gy * cells + gx }
        }
        if (best < 0) return null
        return Triple(((best % cells) + 0.5f) * cw, ((best / cells) + 0.5f) * ch, bestD)
    }

    fun toIntrinsics(ci: CameraIntrinsics): Intrinsics {
        val f = ci.focalLength
        val p = ci.principalPoint
        val d = ci.imageDimensions
        return Intrinsics(f[0], f[1], p[0], p[1], d[0], d[1])
    }
}
