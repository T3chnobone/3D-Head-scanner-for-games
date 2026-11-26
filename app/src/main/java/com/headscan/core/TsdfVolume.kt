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

    /**
     * Fills parts of the volume that no camera saw (e.g. the back of the head when the scan only went part of the
     * way round) so the surface closes instead of leaving a hole. For every height and every direction around the
     * vertical axis through ([cx], [cz]) the radius of the seen surface is measured; unseen directions take the
     * radius interpolated from the seen directions on either side at that height. Unseen voxels then get the
     * distance to that shape, with weight [minWeight] so the surface is extracted there.
     */
    fun fillUnseen(cx: Float, cz: Float, minWeight: Float, sectors: Int = 16) {
        val samples = Array(ny * sectors) { ArrayList<Float>() }
        val twoPi = (2 * Math.PI).toFloat()
        fun sectorOf(dx: Float, dz: Float): Int {
            var a = kotlin.math.atan2(dx, dz)
            if (a < 0) a += twoPi
            return ((a / twoPi) * sectors).toInt().coerceIn(0, sectors - 1)
        }
        for (k in 0 until nz) for (j in 0 until ny) for (i in 0 until nx) {
            val id = index(i, j, k)
            if (weight[id] < minWeight || kotlin.math.abs(tsdf[id]) > 0.3f) continue
            val dx = pointX(i.toFloat()) - cx; val dz = pointZ(k.toFloat()) - cz
            samples[j * sectors + sectorOf(dx, dz)].add(kotlin.math.sqrt(dx * dx + dz * dz))
        }
        val radius = FloatArray(ny * sectors) { Float.NaN }
        for (j in 0 until ny) for (s in 0 until sectors) {
            val l = samples[j * sectors + s]
            if (l.size >= 1) { l.sort(); radius[j * sectors + s] = l[l.size / 2] }
        }
        // Unseen directions: interpolate round the circle between the nearest seen directions at that height.
        val known = BooleanArray(sectors)
        for (j in 0 until ny) {
            var any = false
            for (s in 0 until sectors) { known[s] = !radius[j * sectors + s].isNaN(); any = any || known[s] }
            if (!any) continue
            for (s in 0 until sectors) {
                if (known[s]) continue
                var a = 1; while (!known[(s - a + sectors) % sectors]) a++
                var b = 1; while (!known[(s + b) % sectors]) b++
                val ra = radius[j * sectors + (s - a + sectors) % sectors]; val rb = radius[j * sectors + (s + b) % sectors]
                radius[j * sectors + s] = ra + (rb - ra) * a / (a + b).toFloat()
            }
        }
        // Heights with nothing seen below the lowest seen one carry it on down (the neck continues).
        var lowest = -1
        for (j in 0 until ny) if (!radius[j * sectors].isNaN()) { lowest = j; break }
        if (lowest < 0) return
        for (j in 0 until lowest) for (s in 0 until sectors) radius[j * sectors + s] = radius[lowest * sectors + s]
        for (k in 0 until nz) for (j in 0 until ny) for (i in 0 until nx) {
            val id = index(i, j, k)
            if (weight[id] >= minWeight) continue
            val dx = pointX(i.toFloat()) - cx; val dz = pointZ(k.toFloat()) - cz
            val r = radius[j * sectors + sectorOf(dx, dz)]
            // Above the top of what was seen: outside, so an unseen crown is capped instead of left open.
            tsdf[id] = if (r.isNaN()) 1f else ((kotlin.math.sqrt(dx * dx + dz * dz) - r) / truncation).coerceIn(-1f, 1f)
            weight[id] = minWeight
        }
    }
}
