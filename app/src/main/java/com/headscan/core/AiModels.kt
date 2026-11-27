package com.headscan.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * On-device neural networks used by the post-process options. The app fills these in (TensorFlow Lite);
 * when one is missing the option is skipped and the head is built as normal.
 *
 * All images are float RGB 0..1, row-major, row 0 = top, 3 values per pixel.
 */
object AiModels {
    /** Real-ESRGAN (realesr-general-x4v3): 64x64 in, 256x256 out (x4 super-resolution). */
    @Volatile var sharpen: ((FloatArray) -> FloatArray)? = null
    /** Real-ESRGAN with denoise (realesr-general-wdn-x4v3): 64x64 in, 256x256 out. */
    @Volatile var denoise: ((FloatArray) -> FloatArray)? = null
    /** MediaPipe selfie multiclass segmenter: 256x256 in, 256x256x6 class scores out (see [SkinLabels]). */
    @Volatile var segment: ((FloatArray) -> FloatArray)? = null
}

/** AI clean-up of the photo face in the texture. */
enum class Enhance { NONE, SHARPEN, DENOISE }

/**
 * Runs a x4 super-resolution net over the face part of the texture at the texture's own resolution:
 * each 128 px block is halved to 64 px, enlarged x4 by the net to 256 px and halved back to 128 px. The net redraws
 * edges and skin detail sharply (and, for the denoise net, without camera noise), then the result is blended in
 * where [mask] (0..1, size x size) says the photo face is. Blocks overlap and are feathered so there are no seams.
 */
object TextureEnhance {
    const val BLOCK = 128
    private const val STRIDE = 96
    private const val IN = 64
    private const val OUT = 256

    fun apply(texture: IntArray, size: Int, mask: FloatArray, net: (FloatArray) -> FloatArray, progress: (Float) -> Unit = {}) {
        val starts = ArrayList<Int>()
        var s = 0
        while (true) { starts.add(min(s, size - BLOCK)); if (s + BLOCK >= size) break; s += STRIDE }
        val blocks = ArrayList<Pair<Int, Int>>()
        for (by in starts.distinct()) for (bx in starts.distinct()) {
            var any = false
            loop@ for (y in by until by + BLOCK step 4) for (x in bx until bx + BLOCK step 4) if (mask[y * size + x] > 0.05f) { any = true; break@loop }
            if (any) blocks.add(bx to by)
        }
        if (blocks.isEmpty()) return
        val n = size * size
        val accR = FloatArray(n); val accG = FloatArray(n); val accB = FloatArray(n); val accW = FloatArray(n)
        val input = FloatArray(IN * IN * 3)
        val feather = 16
        blocks.forEachIndexed { bi, (bx, by) ->
            progress(bi.toFloat() / blocks.size)
            for (j in 0 until IN) for (i in 0 until IN) {
                var r = 0f; var g = 0f; var b = 0f
                for (dy in 0..1) for (dx in 0..1) {
                    val c = texture[(by + 2 * j + dy) * size + bx + 2 * i + dx]
                    r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255
                }
                val o = 3 * (j * IN + i)
                input[o] = r / 1020f; input[o + 1] = g / 1020f; input[o + 2] = b / 1020f
            }
            val out = net(input)
            if (out.size < OUT * OUT * 3) return@forEachIndexed
            for (j in 0 until BLOCK) for (i in 0 until BLOCK) {
                var r = 0f; var g = 0f; var b = 0f
                for (dy in 0..1) for (dx in 0..1) {
                    val o = 3 * ((2 * j + dy) * OUT + 2 * i + dx)
                    r += out[o]; g += out[o + 1]; b += out[o + 2]
                }
                val x = bx + i; val y = by + j
                // Feather the block edges, except where the block touches the texture border.
                fun ramp(p: Int, lo: Boolean, hi: Boolean): Float {
                    val a = if (lo) 1f else min(1f, (p + 0.5f) / feather)
                    val c = if (hi) 1f else min(1f, (BLOCK - p - 0.5f) / feather)
                    return min(a, c)
                }
                val w = ramp(i, bx == 0, bx + BLOCK >= size) * ramp(j, by == 0, by + BLOCK >= size)
                val k = y * size + x
                accR[k] += r * 0.25f * w; accG[k] += g * 0.25f * w; accB[k] += b * 0.25f * w; accW[k] += w
            }
        }
        for (k in 0 until n) {
            val m = mask[k].coerceIn(0f, 1f)
            if (m <= 0.01f || accW[k] < 1e-4f) continue
            val c = texture[k]
            fun ch(sh: Int, v: Float): Int {
                val o = ((c shr sh) and 255).toFloat()
                return (o + ((v / accW[k]) * 255f - o) * m).toInt().coerceIn(0, 255)
            }
            texture[k] = (0xFF shl 24) or (ch(16, accR[k]) shl 16) or (ch(8, accG[k]) shl 8) or ch(0, accB[k])
        }
        progress(1f)
    }
}

/**
 * What each pixel of a photo shows (hair, face skin, clothes, ...), from the AI segmenter, in the photo's own
 * orientation. The photo is turned upright for the net first (from the camera pose), since it is trained on upright people.
 */
class SkinLabels private constructor(
    private val labels: ByteArray,
    private val width: Int,
    private val height: Int,
    /** Image direction of "up" for the head (unit, along an image axis). */
    private val ux: Int, private val uy: Int,
) {
    /** Class at image pixel (u, v), or -1 outside the photo. */
    fun at(u: Float, v: Float): Int {
        val cx = width / 2f; val cy = height / 2f
        val rx = -uy; val ry = ux                      // "right" in the upright view
        val extR = if (rx != 0) width else height
        val extU = if (ux != 0) width else height
        val s = ((u - cx) * rx + (v - cy) * ry) / extR + 0.5f
        val t = -((u - cx) * ux + (v - cy) * uy) / extU + 0.5f
        val i = (s * N).toInt(); val j = (t * N).toInt()
        if (i < 0 || j < 0 || i >= N || j >= N) return -1
        return labels[j * N + i].toInt()
    }

    companion object {
        const val N = 256
        const val BACKGROUND = 0
        const val HAIR = 1
        const val BODY_SKIN = 2
        const val FACE_SKIN = 3
        const val CLOTHES = 4
        const val OTHER = 5

        /** Labels the photo of [kf] with [net]. The head's up direction is +Y of the keyframe's world. */
        fun of(image: RgbImage, kf: Keyframe, net: (FloatArray) -> FloatArray): SkinLabels? {
            val m = kf.worldToCam
            // World +Y in camera space, then in the image (u right, v down).
            val du = m[4] * kotlin.math.sign(kf.colorIntrinsics.fx); val dv = -m[5] * kotlin.math.sign(kf.colorIntrinsics.fy)
            val ux: Int; val uy: Int
            if (abs(du) > abs(dv)) { ux = if (du > 0) 1 else -1; uy = 0 } else { ux = 0; uy = if (dv > 0) 1 else -1 }
            val rx = -uy; val ry = ux
            val w = image.width; val h = image.height
            val extR = if (rx != 0) w else h
            val extU = if (ux != 0) w else h
            val input = FloatArray(N * N * 3)
            val rgb = FloatArray(3)
            for (j in 0 until N) for (i in 0 until N) {
                val s = (i + 0.5f) / N - 0.5f; val t = (j + 0.5f) / N - 0.5f
                val u = w / 2f + s * extR * rx - t * extU * ux
                val v = h / 2f + s * extR * ry - t * extU * uy
                val o = 3 * (j * N + i)
                if (image.sample(max(0.5f, min(w - 0.6f, u)), max(0.5f, min(h - 0.6f, v)), rgb)) {
                    input[o] = rgb[0] / 255f; input[o + 1] = rgb[1] / 255f; input[o + 2] = rgb[2] / 255f
                }
            }
            val out = net(input)
            if (out.size < N * N * 6) return null
            val labels = ByteArray(N * N)
            for (p in 0 until N * N) {
                var best = 0; var bv = out[6 * p]
                for (c in 1 until 6) if (out[6 * p + c] > bv) { bv = out[6 * p + c]; best = c }
                labels[p] = best.toByte()
            }
            return SkinLabels(labels, w, h, ux, uy)
        }
    }
}

/** Segmenter class of the head surface at a point (from the best photo of each texel), on a coarse 3D grid. */
class LabelGrid(private val x0: Float, private val y0: Float, private val z0: Float, private val step: Float,
                private val nx: Int, private val ny: Int, private val nz: Int, private val cells: ByteArray) {
    /** Class at (x, y, z) in the head build frame, or -1 if unknown. */
    fun at(x: Float, y: Float, z: Float): Int {
        val i = ((x - x0) / step).toInt(); val j = ((y - y0) / step).toInt(); val k = ((z - z0) / step).toInt()
        if (i < 0 || j < 0 || k < 0 || i >= nx || j >= ny || k >= nz) return -1
        return cells[(k * ny + j) * nx + i].toInt()
    }

    /** True unless the segmenter said this is something other than skin (hair, clothes, glasses...). */
    fun skinOrUnknown(x: Float, y: Float, z: Float): Boolean {
        val l = at(x, y, z)
        return l < 0 || l == SkinLabels.FACE_SKIN || l == SkinLabels.BODY_SKIN
    }

    companion object {
        fun build(pos: FloatArray, label: ByteArray, step: Float = 0.004f): LabelGrid? {
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            var any = false
            for (t in label.indices) if (label[t] >= 0) {
                any = true
                minX = min(minX, pos[3 * t]); maxX = max(maxX, pos[3 * t])
                minY = min(minY, pos[3 * t + 1]); maxY = max(maxY, pos[3 * t + 1])
                minZ = min(minZ, pos[3 * t + 2]); maxZ = max(maxZ, pos[3 * t + 2])
            }
            if (!any) return null
            val x0 = minX - step; val y0 = minY - step; val z0 = minZ - step
            val nx = ((maxX - x0) / step).toInt() + 2; val ny = ((maxY - y0) / step).toInt() + 2; val nz = ((maxZ - z0) / step).toInt() + 2
            // Majority class per cell.
            val votes = IntArray(nx * ny * nz * 6)
            for (t in label.indices) {
                val l = label[t].toInt(); if (l < 0) continue
                val i = ((pos[3 * t] - x0) / step).toInt(); val j = ((pos[3 * t + 1] - y0) / step).toInt(); val k = ((pos[3 * t + 2] - z0) / step).toInt()
                votes[((k * ny + j) * nx + i) * 6 + l]++
            }
            val cells = ByteArray(nx * ny * nz) { c ->
                var best = -1; var bv = 0
                for (l in 0 until 6) if (votes[c * 6 + l] > bv) { bv = votes[c * 6 + l]; best = l }
                best.toByte()
            }
            return LabelGrid(x0, y0, z0, step, nx, ny, nz, cells)
        }
    }
}
