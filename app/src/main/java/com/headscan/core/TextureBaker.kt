package com.headscan.core

import java.util.stream.IntStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Per-triangle UV atlas: triangles are packed in pairs into square cells of a grid,
 * each triangle taking one half of a cell. Low-poly friendly, uses ~all of the texture,
 * and gives every triangle the same texel budget.
 */
class UvAtlas(val faceCount: Int, val size: Int) {
    /** 6 floats per face: pixel-space (x,y) of its 3 corners, image origin top-left. */
    val pixelUv = FloatArray(faceCount * 6)
    val grid: Int
    val cell: Float

    init {
        val pairs = (faceCount + 1) / 2
        grid = max(1, ceil(sqrt(pairs.toDouble())).toInt())
        cell = size.toFloat() / grid
        val m = 1.0f          // margin to the cell border (px)
        val g = 1.5f          // half-gap across the diagonal (px)
        for (f in 0 until faceCount) {
            val pair = f / 2
            val x0 = (pair % grid) * cell
            val y0 = (pair / grid) * cell
            val o = f * 6
            if (f % 2 == 0) {
                // upper-left half
                set(o, x0 + m, y0 + m, x0 + cell - m - 2 * g, y0 + m, x0 + m, y0 + cell - m - 2 * g)
            } else {
                // lower-right half
                set(o, x0 + cell - m, y0 + cell - m, x0 + m + 2 * g, y0 + cell - m, x0 + cell - m, y0 + m + 2 * g)
            }
        }
    }

    private fun set(o: Int, ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float) {
        pixelUv[o] = ax; pixelUv[o + 1] = ay; pixelUv[o + 2] = bx; pixelUv[o + 3] = by; pixelUv[o + 4] = cx; pixelUv[o + 5] = cy
    }

    /** OBJ texture coordinate (u, v) with v pointing up. */
    fun objU(face: Int, corner: Int) = pixelUv[face * 6 + corner * 2] / size
    fun objV(face: Int, corner: Int) = 1f - pixelUv[face * 6 + corner * 2 + 1] / size
}

/**
 * Projects the captured photos onto the mesh surface and writes an ARGB texture.
 * Each texel is coloured by a visibility-tested, view-angle-weighted blend of the keyframes that saw it.
 */
class TextureBaker(private val mesh: Mesh, private val normals: FloatArray, private val atlas: UvAtlas) {
    private val size = atlas.size

    // Texel samples: image index + world position + normal.
    private val texelIndex: IntArray
    private val texelPos: FloatArray
    private val texelNrm: FloatArray
    private val texelFace: IntArray
    private val texelBary: FloatArray

    init {
        val idx = IntList(size * size)
        val faceOf = IntList(size * size)
        val bary = FloatList(size * size * 2)
        val p = FloatList(size * size * 3)
        val n = FloatList(size * size * 3)
        val pos = mesh.positions
        for (f in 0 until mesh.faceCount) {
            val o = f * 6
            val ax = atlas.pixelUv[o]; val ay = atlas.pixelUv[o + 1]
            val bx = atlas.pixelUv[o + 2]; val by = atlas.pixelUv[o + 3]
            val cx = atlas.pixelUv[o + 4]; val cy = atlas.pixelUv[o + 5]
            val den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
            if (kotlin.math.abs(den) < 1e-9f) continue
            val minX = max(0, kotlin.math.floor(min(ax, min(bx, cx))).toInt())
            val maxX = min(size - 1, ceil(max(ax, max(bx, cx))).toInt())
            val minY = max(0, kotlin.math.floor(min(ay, min(by, cy))).toInt())
            val maxY = min(size - 1, ceil(max(ay, max(by, cy))).toInt())
            val va = mesh.faces[3 * f]; val vb = mesh.faces[3 * f + 1]; val vc = mesh.faces[3 * f + 2]
            for (y in minY..maxY) for (x in minX..maxX) {
                val px = x + 0.5f; val py = y + 0.5f
                val w0 = ((by - cy) * (px - cx) + (cx - bx) * (py - cy)) / den
                val w1 = ((cy - ay) * (px - cx) + (ax - cx) * (py - cy)) / den
                val w2 = 1f - w0 - w1
                if (w0 < -1e-4f || w1 < -1e-4f || w2 < -1e-4f) continue
                idx.add(y * size + x)
                faceOf.add(f); bary.add(w0); bary.add(w1)
                for (k in 0 until 3) {
                    p.add(w0 * pos[3 * va + k] + w1 * pos[3 * vb + k] + w2 * pos[3 * vc + k])
                }
                var nx = w0 * normals[3 * va] + w1 * normals[3 * vb] + w2 * normals[3 * vc]
                var ny = w0 * normals[3 * va + 1] + w1 * normals[3 * vb + 1] + w2 * normals[3 * vc + 1]
                var nz = w0 * normals[3 * va + 2] + w1 * normals[3 * vb + 2] + w2 * normals[3 * vc + 2]
                val l = len3(nx, ny, nz).coerceAtLeast(1e-9f)
                nx /= l; ny /= l; nz /= l
                n.add(nx); n.add(ny); n.add(nz)
            }
        }
        texelIndex = idx.toArray()
        texelFace = faceOf.toArray()
        texelBary = bary.toArray()
        texelPos = p.toArray()
        texelNrm = n.toArray()
    }

    private val accR = FloatArray(texelIndex.size)
    private val accG = FloatArray(texelIndex.size)
    private val accB = FloatArray(texelIndex.size)
    private val accW = FloatArray(texelIndex.size)

    val texelCount get() = texelIndex.size

    private val scratch = object : ThreadLocal<FloatArray>() { override fun initialValue() = FloatArray(3) }

    /**
     * Adds one keyframe's contribution.
     *
     * Each texel ends up dominated by the single best photo for it: the score combines how front-on the view is,
     * how many image pixels land on the surface there, the photo's relative sharpness ([quality], 0..1) and its
     * [Keyframe.priority]. The score is raised to a high power, so the best view wins outright and photos only
     * blend where their scores are close (a narrow, soft seam) instead of averaging everything into a blur.
     *
     * @param occlusionTolerance metres a point may lie behind the measured depth and still count as visible.
     */
    fun addView(kf: Keyframe, image: RgbImage, occlusionTolerance: Float = 0.03f, minCos: Float = 0.2f, quality: Float = 1f) {
        val m = kf.worldToCam
        val ci = kf.colorIntrinsics.scaledTo(image.width, image.height)
        val di = kf.depthIntrinsics
        val camX = kf.camX; val camY = kf.camY; val camZ = kf.camZ
        val w = image.width; val h = image.height
        val border = 0.04f * min(w, h)
        val focal = 0.5f * (kotlin.math.abs(ci.fx) + kotlin.math.abs(ci.fy))
        val viewScore = quality.coerceIn(0.02f, 1f) * kf.priority
        IntStream.range(0, texelIndex.size).parallel().forEach { t ->
            val px = texelPos[3 * t]; val py = texelPos[3 * t + 1]; val pz = texelPos[3 * t + 2]
            var vx = camX - px; var vy = camY - py; var vz = camZ - pz
            val l = len3(vx, vy, vz)
            if (l < 1e-6f) return@forEach
            vx /= l; vy /= l; vz /= l
            val cos = vx * texelNrm[3 * t] + vy * texelNrm[3 * t + 1] + vz * texelNrm[3 * t + 2]
            if (cos < minCos) return@forEach
            val cx = m[0] * px + m[4] * py + m[8] * pz + m[12]
            val cy = m[1] * px + m[5] * py + m[9] * pz + m[13]
            val cz = m[2] * px + m[6] * py + m[10] * pz + m[14]
            val z = -cz
            if (z < 0.05f) return@forEach
            // Occlusion test against this view's depth map.
            val du = (di.fx * cx / z + di.cx).toInt()
            val dv = (di.cy - di.fy * cy / z).toInt()
            val d = kf.depthAt(du, dv)
            if (d > 0f && z - d > occlusionTolerance) return@forEach
            val u = ci.fx * cx / z + ci.cx
            val v = ci.cy - ci.fy * cy / z
            val rgb = scratch.get()!!
            if (!image.sample(u, v, rgb)) return@forEach
            // Prefer pixels away from the image border.
            val edge = min(min(u, w - u), min(v, h - v))
            val edgeW = if (edge < border) max(0.05f, edge / border) else 1f
            // Image pixels per metre of surface here; beyond the texture's own density it no longer helps.
            val density = min(1f, focal * cos / z / TEXEL_DENSITY)
            val score = cos * sqrt(density) * viewScore
            val s2 = score * score
            val s4 = s2 * s2
            var wt = s4 * s4 * s4 * edgeW      // score^12
            if (d == 0f && kf.depthWidth > 1) wt *= 0.3f
            wt = max(wt, 1e-30f)
            accR[t] += rgb[0] * wt; accG[t] += rgb[1] * wt; accB[t] += rgb[2] * wt; accW[t] += wt
        }
    }

    /**
     * Robust colour (ARGB) of seen texels matching [where]: the middle half by brightness is averaged,
     * so shadows, highlights, eyes and eyebrows don't skew it. Null if fewer than [minCount] texels.
     */
    fun robustSeenColor(minCount: Int = 50, where: (Float, Float, Float) -> Boolean): Int? {
        val cols = IntList()
        for (t in texelIndex.indices) {
            if (accW[t] <= 0f || !where(texelPos[3 * t], texelPos[3 * t + 1], texelPos[3 * t + 2])) continue
            val r = (accR[t] / accW[t]).toInt().coerceIn(0, 255)
            val g = (accG[t] / accW[t]).toInt().coerceIn(0, 255)
            val b = (accB[t] / accW[t]).toInt().coerceIn(0, 255)
            cols.add((r shl 16) or (g shl 8) or b)
        }
        if (cols.size < minCount) return null
        val arr = cols.toArray().sortedBy { c -> 299 * ((c shr 16) and 255) + 587 * ((c shr 8) and 255) + 114 * (c and 255) }
        val lo = arr.size / 4; val hi = arr.size * 3 / 4
        var r = 0L; var g = 0L; var b = 0L
        for (k in lo until hi) { val c = arr[k]; r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255 }
        val n = (hi - lo).coerceAtLeast(1)
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    /** Average colour (ARGB) of the texels seen in photos whose position matches [where], or null if fewer than [minCount]. */
    fun meanSeenColor(minCount: Int = 50, where: (Float, Float, Float) -> Boolean): Int? {
        var r = 0.0; var g = 0.0; var b = 0.0; var n = 0
        for (t in texelIndex.indices) {
            if (accW[t] <= 0f || !where(texelPos[3 * t], texelPos[3 * t + 1], texelPos[3 * t + 2])) continue
            r += accR[t] / accW[t]; g += accG[t] / accW[t]; b += accB[t] / accW[t]; n++
        }
        if (n < minCount) return null
        return (0xFF shl 24) or ((r / n).toInt().coerceIn(0, 255) shl 16) or ((g / n).toInt().coerceIn(0, 255) shl 8) or (b / n).toInt().coerceIn(0, 255)
    }

    /**
     * Produces the final ARGB texture (row 0 = top). Unseen texels get [fillColor] at their position
     * (or the mean seen colour), gaps are dilated.
     */
    fun finish(fillColor: ((Float, Float, Float) -> Int)? = null, overrideColor: ((Float, Float, Float, Int?) -> Int?)? = null): IntArray {
        val out = IntArray(size * size)
        val filled = BooleanArray(size * size)
        var sr = 0.0; var sg = 0.0; var sb = 0.0; var sn = 0
        for (t in texelIndex.indices) if (accW[t] > 0f) {
            sr += accR[t] / accW[t]; sg += accG[t] / accW[t]; sb += accB[t] / accW[t]; sn++
        }
        val meanR = if (sn > 0) (sr / sn).toInt() else 180
        val meanG = if (sn > 0) (sg / sn).toInt() else 150
        val meanB = if (sn > 0) (sb / sn).toInt() else 130
        for (t in texelIndex.indices) {
            val i = texelIndex[t]
            val r: Int; val g: Int; val b: Int
            val seen = if (accW[t] > 0f) (0xFF shl 24) or
                ((accR[t] / accW[t]).toInt().coerceIn(0, 255) shl 16) or
                ((accG[t] / accW[t]).toInt().coerceIn(0, 255) shl 8) or
                (accB[t] / accW[t]).toInt().coerceIn(0, 255) else null
            val forced = overrideColor?.invoke(texelPos[3 * t], texelPos[3 * t + 1], texelPos[3 * t + 2], seen)
            if (forced != null) {
                r = (forced shr 16) and 255; g = (forced shr 8) and 255; b = forced and 255
            } else if (accW[t] > 0f) {
                r = (accR[t] / accW[t]).toInt().coerceIn(0, 255)
                g = (accG[t] / accW[t]).toInt().coerceIn(0, 255)
                b = (accB[t] / accW[t]).toInt().coerceIn(0, 255)
            } else if (fillColor != null) {
                val c = fillColor(texelPos[3 * t], texelPos[3 * t + 1], texelPos[3 * t + 2])
                r = (c shr 16) and 255; g = (c shr 8) and 255; b = c and 255
            } else { r = meanR; g = meanG; b = meanB }
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            filled[i] = true
        }
        dilate(out, filled, 6)
        // Anything left (unused atlas cells) gets the mean colour.
        val bg = (0xFF shl 24) or (meanR shl 16) or (meanG shl 8) or meanB
        for (i in out.indices) if (!filled[i]) out[i] = bg
        return out
    }

    /**
     * Multiplies each texel of [texture] by [shade](normalY, scalar), where scalar is [vertexScalar]
     * interpolated across the triangle. Used to bake soft lighting / ambient occlusion into the texture.
     */
    fun bakeShading(texture: IntArray, vertexScalar: FloatArray, shade: (Float, Float) -> Float) {
        for (t in texelIndex.indices) {
            val f = texelFace[t]
            val w0 = texelBary[2 * t]; val w1 = texelBary[2 * t + 1]; val w2 = 1f - w0 - w1
            val sc = w0 * vertexScalar[mesh.faces[3 * f]] + w1 * vertexScalar[mesh.faces[3 * f + 1]] + w2 * vertexScalar[mesh.faces[3 * f + 2]]
            val k = shade(texelNrm[3 * t + 1], sc)
            val i = texelIndex[t]
            val c = texture[i]
            val r = (((c shr 16) and 255) * k).toInt().coerceIn(0, 255)
            val g = (((c shr 8) and 255) * k).toInt().coerceIn(0, 255)
            val b = ((c and 255) * k).toInt().coerceIn(0, 255)
            texture[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** Evaluates [f] at every texel's surface point into a size x size map, padded outward like the texture. */
    fun texelMap(f: (Float, Float, Float) -> Float): FloatArray {
        val out = FloatArray(size * size)
        val has = BooleanArray(size * size)
        for (t in texelIndex.indices) {
            out[texelIndex[t]] = f(texelPos[3 * t], texelPos[3 * t + 1], texelPos[3 * t + 2])
            has[texelIndex[t]] = true
        }
        repeat(6) {
            val next = has.copyOf()
            for (y in 0 until size) for (x in 0 until size) {
                val i = y * size + x
                if (has[i]) continue
                var sum = 0f; var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= size || yy >= size) continue
                    val j = yy * size + xx
                    if (has[j]) { sum += out[j]; n++ }
                }
                if (n > 0) { out[i] = sum / n; next[i] = true }
            }
            System.arraycopy(next, 0, has, 0, has.size)
        }
        return out
    }

    companion object {
        /** Image pixels per metre of surface at which a photo stops getting extra credit for resolution. */
        const val TEXEL_DENSITY = 2600f

        /** Relative quality (0..1] of each keyframe from its blur score, for [addView]. */
        fun qualities(keyframes: List<Keyframe>): FloatArray {
            val best = keyframes.maxOfOrNull { it.sharpness } ?: 0f
            return FloatArray(keyframes.size) { i ->
                val s = keyframes[i].sharpness
                if (best <= 0f || s <= 0f) 1f else sqrt(s / best).coerceIn(0.05f, 1f)
            }
        }
    }

    private fun dilate(img: IntArray, filled: BooleanArray, passes: Int) {
        val next = BooleanArray(filled.size)
        repeat(passes) {
            System.arraycopy(filled, 0, next, 0, filled.size)
            for (y in 0 until size) for (x in 0 until size) {
                val i = y * size + x
                if (filled[i]) continue
                var r = 0; var g = 0; var b = 0; var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= size || yy >= size) continue
                    val j = yy * size + xx
                    if (!filled[j]) continue
                    val c = img[j]
                    r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255; n++
                }
                if (n > 0) {
                    img[i] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                    next[i] = true
                }
            }
            System.arraycopy(next, 0, filled, 0, filled.size)
        }
    }
}
