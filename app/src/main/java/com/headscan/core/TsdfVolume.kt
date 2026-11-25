package com.headscan.core

import java.util.stream.IntStream
import kotlin.math.min

/**
 * Truncated signed distance field over an axis-aligned box in world space.
 * Positive values are in front of the observed surface (free space), negative behind it.
 */
class TsdfVolume(
    val originX: Float,
    val originY: Float,
    val originZ: Float,
    val voxelSize: Float,
    val nx: Int,
    val ny: Int,
    val nz: Int,
    val truncation: Float,
) {
    val tsdf = FloatArray(nx * ny * nz) { 1f }
    val weight = FloatArray(nx * ny * nz)

    fun index(i: Int, j: Int, k: Int) = (k * ny + j) * nx + i

    fun pointX(i: Float) = originX + i * voxelSize
    fun pointY(j: Float) = originY + j * voxelSize
    fun pointZ(k: Float) = originZ + k * voxelSize

    /** Fuses one depth map into the volume. Runs in parallel over Z slices. */
    fun integrate(kf: Keyframe, maxDepth: Float = 1.5f, maxWeight: Float = 64f) {
        val m = kf.worldToCam
        val intr = kf.depthIntrinsics
        val w = kf.depthWidth
        val h = kf.depthHeight
        val depth = kf.depthMm
        val trunc = truncation
        IntStream.range(0, nz).parallel().forEach { k ->
            val wz = pointZ(k.toFloat())
            for (j in 0 until ny) {
                val wy = pointY(j.toFloat())
                val baseX = m[4] * wy + m[8] * wz + m[12]
                val baseY = m[5] * wy + m[9] * wz + m[13]
                val baseZ = m[6] * wy + m[10] * wz + m[14]
                var idx = index(0, j, k)
                for (i in 0 until nx) {
                    val wx = pointX(i.toFloat())
                    val cx = m[0] * wx + baseX
                    val cy = m[1] * wx + baseY
                    val cz = m[2] * wx + baseZ
                    val z = -cz
                    if (z > 0.05f) {
                        val u = intr.fx * cx / z + intr.cx
                        val v = intr.cy - intr.fy * cy / z
                        if (u >= 0f && v >= 0f && u < w && v < h) {
                            val raw = depth[v.toInt() * w + u.toInt()].toInt() and 0xFFFF
                            if (raw != 0) {
                                val d = raw * 0.001f
                                if (d <= maxDepth) {
                                    val sdf = d - z
                                    if (sdf >= -trunc) {
                                        val t = min(1f, sdf / trunc)
                                        val ow = weight[idx]
                                        // Down-weight far measurements: ARCore depth error grows ~quadratically with range.
                                        val nwt = 1f / (0.25f + d * d)
                                        tsdf[idx] = (tsdf[idx] * ow + t * nwt) / (ow + nwt)
                                        weight[idx] = min(ow + nwt, maxWeight)
                                    }
                                }
                            }
                        }
                    }
                    idx++
                }
            }
        }
    }
}
