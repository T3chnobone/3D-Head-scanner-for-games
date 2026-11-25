package com.headscan.core

import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Lines up a full-resolution still photo with a tracked (low-resolution) camera frame taken from the same spot,
 * so the still can be projected onto the mesh with the tracked frame's pose.
 *
 * The model is a similarity without rotation, allowing mirroring:
 *   stillU = signX * scale * anchorU + tx,  stillV = signY * scale * anchorV + ty   (pixel units, centres at +0.5).
 * Found by exhaustive normalised cross-correlation on a coarse pyramid level, refined level by level.
 */
object ImageRegistration {

    class Result(
        val scale: Float,
        val tx: Float,
        val ty: Float,
        val mirrorX: Boolean,
        val mirrorY: Boolean,
        /** Normalised cross-correlation of the fit at the finest level (1 = perfect). */
        val score: Float,
        /** Per-channel gains (r, g, b) that bring the still's colours in line with the anchor's. */
        val gain: FloatArray,
    ) {
        fun mapU(u: Float) = (if (mirrorX) -scale else scale) * u + tx
        fun mapV(v: Float) = (if (mirrorY) -scale else scale) * v + ty

        /** Intrinsics of the still, given the anchor's intrinsics (any resolution) and the anchor image size used here. */
        fun stillIntrinsics(anchor: Intrinsics, anchorW: Int, anchorH: Int, stillW: Int, stillH: Int): Intrinsics {
            val a = anchor.scaledTo(anchorW, anchorH)
            val sx = if (mirrorX) -scale else scale
            val sy = if (mirrorY) -scale else scale
            return Intrinsics(sx * a.fx, sy * a.fy, sx * a.cx + tx, sy * a.cy + ty, stillW, stillH)
        }
    }

    const val MIN_SCORE = 0.55f

    private class Gray(val w: Int, val h: Int, val px: FloatArray) {
        fun half(): Gray {
            val nw = max(1, w / 2); val nh = max(1, h / 2)
            val out = FloatArray(nw * nh)
            for (y in 0 until nh) for (x in 0 until nw) {
                val x0 = min(w - 1, 2 * x); val x1 = min(w - 1, 2 * x + 1)
                val y0 = min(h - 1, 2 * y); val y1 = min(h - 1, 2 * y + 1)
                out[y * nw + x] = 0.25f * (px[y0 * w + x0] + px[y0 * w + x1] + px[y1 * w + x0] + px[y1 * w + x1])
            }
            return Gray(nw, nh, out)
        }

        /** Bilinear sample at continuous pixel coords (centres at +0.5); NaN outside. */
        fun sample(u: Float, v: Float): Float {
            val x = u - 0.5f; val y = v - 0.5f
            if (x < 0f || y < 0f || x > w - 1f || y > h - 1f) return Float.NaN
            val x0 = min(x.toInt(), w - 2).coerceAtLeast(0); val y0 = min(y.toInt(), h - 2).coerceAtLeast(0)
            val fx = x - x0; val fy = y - y0
            val i = y0 * w + x0
            val x1 = if (w > 1) 1 else 0
            val y1 = if (h > 1) w else 0
            val a = px[i] + (px[i + x1] - px[i]) * fx
            val b = px[i + y1] + (px[i + y1 + x1] - px[i + y1]) * fx
            return a + (b - a) * fy
        }
    }

    private fun gray(img: RgbImage): Gray {
        val out = FloatArray(img.width * img.height)
        for (i in out.indices) {
            val c = img.argb[i]
            out[i] = 0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)
        }
        return Gray(img.width, img.height, out)
    }

    private fun pyramid(g: Gray, minWidth: Int): List<Gray> {
        val levels = arrayListOf(g)
        while (levels.last().w / 2 >= minWidth) levels.add(levels.last().half())
        return levels
    }

    private class Candidate(val scale: Float, val tx: Float, val ty: Float, val mx: Boolean, val my: Boolean, val score: Float)

    /**
     * @param anchor tracked frame (any size; ~480 px wide is plenty).
     * @param still the high-resolution photo.
     * @return the fit, or null if nothing matched well enough.
     */
    fun register(anchor: RgbImage, still: RgbImage): Result? {
        val aPyr = pyramid(gray(anchor), 40)
        val sPyr = pyramid(gray(still), 40)
        val a0 = still.width.toFloat() / anchor.width
        // Coarse: exhaustive search on the smallest anchor level.
        val coarse = aPyr.size - 1
        val lvl = aPyr[coarse]
        val f = (1 shl coarse).toFloat()
        val scaleSteps = 17
        val sMin = 0.75f * a0; val sMax = 1.35f * a0
        val ratio = (sMax / sMin).toDouble().pow(1.0 / (scaleSteps - 1)).toFloat()
        val shiftRange = (0.15f * lvl.w).roundToInt().coerceAtLeast(3)
        val variants = listOf(false to false, true to false, false to true, true to true)
        val jobs = ArrayList<Pair<Int, Int>>()
        for (si in 0 until scaleSteps) for (vi in variants.indices) jobs.add(si to vi)
        val results = arrayOfNulls<Candidate>(jobs.size)
        IntStream.range(0, jobs.size).parallel().forEach { j ->
            val (si, vi) = jobs[j]
            val (mx, my) = variants[vi]
            val s = sMin * ratio.toDouble().pow(si).toFloat()
            val step = s * f    // still pixels per coarse anchor pixel
            // Centred placement: anchor centre -> still centre.
            val cx = still.width / 2f - (if (mx) -s else s) * anchor.width / 2f
            val cy = still.height / 2f - (if (my) -s else s) * anchor.height / 2f
            var best: Candidate? = null
            for (dy in -shiftRange..shiftRange) for (dx in -shiftRange..shiftRange) {
                val tx = cx + dx * step; val ty = cy + dy * step
                val sc = ncc(lvl, f, sPyr, s, tx, ty, mx, my)
                if (best == null || sc > best.score) best = Candidate(s, tx, ty, mx, my, sc)
            }
            results[j] = best
        }
        // Keep the best few coarse hypotheses and refine each through the pyramid.
        val seeds = results.filterNotNull().sortedByDescending { it.score }.take(3)
        var bestFinal: Candidate? = null
        for (seed in seeds) {
            var c = seed
            var sStep = ratio
            // The finest level is skipped when there is a level above it: half-pixel accuracy there is plenty.
            val finest = if (coarse >= 2) 1 else 0
            for (level in coarse downTo finest) {
                val lv = aPyr[level]
                val lf = (1 shl level).toFloat()
                // Local search: scale ±2 steps, shift ±2 level pixels; repeat a few times (hill-climb).
                sStep = sqrt(sStep)
                repeat(3) {
                    var improved = c
                    for (k in -2..2) {
                        val s = c.scale * sStep.toDouble().pow(k).toFloat()
                        // keep the anchor centre fixed while scaling
                        val sxSign = if (c.mx) -1f else 1f; val sySign = if (c.my) -1f else 1f
                        val acx = anchor.width / 2f; val acy = anchor.height / 2f
                        val baseTx = c.tx + sxSign * (c.scale - s) * acx
                        val baseTy = c.ty + sySign * (c.scale - s) * acy
                        val step = 0.5f * s * lf
                        for (dy in -2..2) for (dx in -2..2) {
                            val tx = baseTx + dx * step; val ty = baseTy + dy * step
                            val sc = ncc(lv, lf, sPyr, s, tx, ty, c.mx, c.my)
                            if (sc > improved.score + 1e-5f) improved = Candidate(s, tx, ty, c.mx, c.my, sc)
                        }
                    }
                    if (improved === c) return@repeat
                    c = improved
                }
                // Score at the next level is not comparable; re-evaluate there.
                if (level > finest) {
                    val nl = aPyr[level - 1]; val nf = (1 shl (level - 1)).toFloat()
                    c = Candidate(c.scale, c.tx, c.ty, c.mx, c.my, ncc(nl, nf, sPyr, c.scale, c.tx, c.ty, c.mx, c.my))
                }
            }
            if (bestFinal == null || c.score > bestFinal.score) bestFinal = c
        }
        val b = bestFinal ?: return null
        if (b.score < MIN_SCORE) return null
        val res = Result(b.scale, b.tx, b.ty, b.mx, b.my, b.score, floatArrayOf(1f, 1f, 1f))
        return Result(b.scale, b.tx, b.ty, b.mx, b.my, b.score, colourGain(anchor, still, res))
    }

    /** NCC between an anchor pyramid level (pixel size [f] in anchor pixels) and the still under the given mapping. */
    private fun ncc(a: Gray, f: Float, sPyr: List<Gray>, s: Float, tx: Float, ty: Float, mx: Boolean, my: Boolean): Float {
        // Pick the still level whose pixels are about as big as one anchor-level pixel.
        val footprint = s * f
        val sl = (ln(footprint.toDouble()) / ln(2.0)).toInt().coerceIn(0, sPyr.size - 1)
        val sImg = sPyr[sl]
        val inv = 1f / (1 shl sl)
        val sx = if (mx) -s else s; val sy = if (my) -s else s
        var n = 0
        var sa = 0.0; var sb = 0.0; var saa = 0.0; var sbb = 0.0; var sab = 0.0
        // Skip the outer 10 % of the anchor (edges are least reliable).
        val x0 = a.w / 10; val x1 = a.w - a.w / 10
        val y0 = a.h / 10; val y1 = a.h - a.h / 10
        for (y in y0 until y1) {
            val av = (y + 0.5f) * f
            val v = (sy * av + ty) * inv
            for (x in x0 until x1) {
                val au = (x + 0.5f) * f
                val u = (sx * au + tx) * inv
                val bv = sImg.sample(u, v)
                if (bv.isNaN()) continue
                val avv = a.px[y * a.w + x]
                sa += avv; sb += bv; saa += avv * avv; sbb += bv * bv; sab += avv * bv; n++
            }
        }
        val total = (x1 - x0) * (y1 - y0)
        if (n < max(30, total / 2)) return -1f
        val ma = sa / n; val mb = sb / n
        val va = saa / n - ma * ma; val vb = sbb / n - mb * mb
        if (va < 1e-6 || vb < 1e-6) return -1f
        val r = ((sab / n - ma * mb) / sqrt(va * vb)).toFloat()
        // Mild penalty for low overlap so the search can't win by sliding mostly off the image.
        return r * (0.9f + 0.1f * n / total)
    }

    private fun colourGain(anchor: RgbImage, still: RgbImage, r: Result): FloatArray {
        val sumA = DoubleArray(3); val sumS = DoubleArray(3)
        val tmp = FloatArray(3)
        val stepX = max(1, anchor.width / 120)
        for (y in anchor.height / 5 until anchor.height * 4 / 5 step stepX) for (x in anchor.width / 5 until anchor.width * 4 / 5 step stepX) {
            val u = r.mapU(x + 0.5f); val v = r.mapV(y + 0.5f)
            if (!still.sample(u, v, tmp)) continue
            val c = anchor.argb[y * anchor.width + x]
            sumA[0] += (c shr 16) and 255; sumA[1] += (c shr 8) and 255; sumA[2] += c and 255
            sumS[0] += tmp[0]; sumS[1] += tmp[1]; sumS[2] += tmp[2]
        }
        return FloatArray(3) { k -> if (sumS[k] < 1.0) 1f else (sumA[k] / sumS[k]).toFloat().coerceIn(0.7f, 1.4f) }
    }

    /** Multiplies the still's colours by the gains in place. */
    fun applyGain(img: RgbImage, gain: FloatArray) {
        if (gain.all { abs(it - 1f) < 0.01f }) return
        val lut = Array(3) { k -> IntArray(256) { (it * gain[k]).roundToInt().coerceIn(0, 255) } }
        for (i in img.argb.indices) {
            val c = img.argb[i]
            img.argb[i] = (c and -0x1000000) or (lut[0][(c shr 16) and 255] shl 16) or (lut[1][(c shr 8) and 255] shl 8) or lut[2][c and 255]
        }
    }

}
