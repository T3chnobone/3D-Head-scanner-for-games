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

    fun toIntrinsics(ci: CameraIntrinsics): Intrinsics {
        val f = ci.focalLength
        val p = ci.principalPoint
        val d = ci.imageDimensions
        return Intrinsics(f[0], f[1], p[0], p[1], d[0], d[1])
    }
}
