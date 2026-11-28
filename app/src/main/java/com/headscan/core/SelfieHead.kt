package com.headscan.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Hair added to the generic head in Selfie mode. */
enum class HairStyle { NONE, SIDE_PART, BUZZ, MOHICAN, CURTAINS, SPIKY }

/** Facial hair painted on the head (texture only). */
enum class Beard { NONE, STUBBLE, SHORT, FULL }

/**
 * Builds a full head + neck volume for Selfie mode: the person's real face surface (the ARCore
 * Augmented Faces mesh, averaged over the capture) is merged onto a generic head and neck that is
 * sized to fit the face.
 *
 * Coordinates are in the face frame: +Y up, +Z out of the face, metres.
 */
class SelfieHead(
    faceVertices: FloatArray,
    private val faceTriangles: IntArray,
    val hairStyle: HairStyle = HairStyle.BUZZ,
    /** Moves the face relative to the generic head (metres, +X right, +Y up, +Z forward). */
    faceOffset: FloatArray = FloatArray(3),
    /** The skull measured from the photos (see [HeadFit]); null = average proportions for the face size. */
    shape: HeadShape? = null,
    /** Statistical head model fitted to the face for the head shape; null = the simple built-in head. */
    model: HeadModel? = HeadModel.default,
) {
    private val verts = faceVertices
    private val headModel = model

    val minX: Float; val maxX: Float; val minY: Float; val maxY: Float; val noseZ: Float
    private val faceW: Float
    private val widthScale: Float

    // Generic head parameters
    val headCx: Float
    val headCy: Float
    val headCz: Float
    val rx: Float; val ry: Float; val rz: Float
    private val neckR: Float
    private val neckCz: Float
    val neckBottom: Float
    val chinY get() = minY

    /** The head model fitted to this face (null when the simple built-in head is used). */
    val fitted: HeadModel.Fitted?
    private val modelSdf: MeshSdf?
    /** Ear centres from the fitted model (x is the right ear's distance from the midline). */
    private val modelEar: FloatArray?

    private companion object {
        /** Distance outside the face over which the head is bent to meet the face edge. */
        const val FIT_FADE = 0.03f
        /** Part position for the classic side part, as a fraction of the head half-width (+X side). */
        const val PART_X = 0.35f
        /** Grid cell for looking up the model's ear points. */
        const val EAR_CELL = 0.005f
    }

    // Height field of the face surface (max z per x/y cell) + blend weight toward the face border.
    private val cell = 0.0015f
    private val gw: Int; private val gh: Int
    private val zFace: FloatArray
    private val blend: FloatArray
    private val sampleBuf = object : ThreadLocal<FloatArray>() { override fun initialValue() = FloatArray(2) }

    init {
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE
        var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        var z1 = -Float.MAX_VALUE
        for (i in 0 until verts.size / 3) {
            x0 = min(x0, verts[3 * i]); x1 = max(x1, verts[3 * i])
            y0 = min(y0, verts[3 * i + 1]); y1 = max(y1, verts[3 * i + 1])
            z1 = max(z1, verts[3 * i + 2])
        }
        minX = x0; maxX = x1; minY = y0; maxY = y1; noseZ = z1
        faceW = maxX - minX
        val scale = faceW / 0.14f    // typical ARCore face mesh width

        gw = ceil(faceW / cell).toInt() + 3
        gh = ceil((maxY - minY) / cell).toInt() + 3
        zFace = FloatArray(gw * gh) { Float.NaN }
        rasterizeFace()
        // Depth of the upper forehead: the skull is placed so its front meets the face there, instead of sticking
        // out in front of it (which left a dent along the top of the face).
        val yF = maxY - 0.015f
        var fSum = 0f; var fN = 0
        val midX = (minX + maxX) / 2
        for (j in 0 until gh) for (i in 0 until gw) {
            val z = zFace[j * gw + i]
            if (z.isNaN()) continue
            val x = minX + (i - 1) * cell; val y = minY + (j - 1) * cell
            if (y > maxY - 0.022f && y < maxY - 0.008f && abs(x - midX) < 0.025f) { fSum += z; fN++ }
        }
        val zForehead = if (fN > 0) fSum / fN else noseZ - 0.03f * scale
        blend = FloatArray(gw * gh)
        computeBlend(borderCells = (0.02f / cell).toInt())

        val topOfHead = maxY + 0.045f * scale
        val jawLine = minY + 0.025f * scale
        // Moving the face by +offset on the head = moving the head by -offset around the face.
        val ox = faceOffset[0]; val oy = faceOffset[1]; val oz = faceOffset[2]
        val rx0 = faceW / 2 * 1.05f
        val rz0 = 0.10f * scale
        val fit = if (model == null) null else try { fitModel(model, shape, scale, ox, oy, oz) } catch (e: Exception) { null }
        if (fit != null && model != null) {
            // The head comes from the model fitted to the face: the skull measurements below are only used to
            // place hair and to size the volume.
            val v = fit.verts
            var top = -Float.MAX_VALUE; var back = Float.MAX_VALUE; var xl = Float.MAX_VALUE; var xr = -Float.MAX_VALUE
            val ear = FloatArray(4); var nl = 0; var nr = 0
            for (i in 0 until model.n) {
                val x = v[3 * i]; val y = v[3 * i + 1]; val z = v[3 * i + 2]
                if (model.isEar(i)) {
                    if (x > 0) { ear[0] += x; ear[1] += y; ear[2] += z; nr++ } else { ear[3] += -x; nl++ }
                    continue
                }
                if (model.isFace(i) || model.isCap(i) || y < fit.neckCutY) continue
                top = max(top, y)
                if (y > jawLine) { back = min(back, z); xl = min(xl, x); xr = max(xr, x) }
            }
            headCx = (xl + xr) / 2
            rx = (xr - xl) / 2
            headCy = (top + jawLine) / 2
            ry = (top - jawLine) / 2
            rz = (zForehead - back) / 2
            headCz = back + rz
            modelEar = if (nr > 0 && nl > 0) floatArrayOf((ear[0] / nr + ear[3] / nl) / 2, ear[1] / nr, ear[2] / nr) else null
            modelSdf = MeshSdf(fit.verts, model.tris, 0.0025f, 0.045f, minY = fit.neckBaseY - 0.03f)
        } else {
            headCx = -ox
            if (shape == null) {
                headCy = (topOfHead + jawLine) / 2 - oy
                ry = (topOfHead - jawLine) / 2
                rx = rx0
                rz = rz0
            } else {
                // The bottom of the skull stays at the jaw; top, width, front and back come from the measurement.
                val top = shape.top.coerceIn(maxY + 0.025f * scale, maxY + 0.075f * scale)
                headCy = (top + jawLine - oy) / 2
                ry = (top - (jawLine - oy)) / 2
                // Keep the head in proportion to the face: thick hair in the outline can't make it too big.
                rx = shape.rx.coerceIn(faceW / 2 * 0.98f, faceW / 2 + 0.02f * scale)
                rz = (shape.zFront - shape.zBack) / 2
            }
            // Front of the skull just behind the upper forehead (the measured depth front-to-back is kept).
            val eyF = ((yF - headCy) / ry).coerceIn(-0.95f, 0.95f)
            headCz = zForehead - 0.002f - rz * sqrt(1f - eyF * eyF) - oz
            modelEar = null; modelSdf = null
        }
        fitted = fit
        widthScale = (rx / rx0).coerceIn(0.7f, 1.4f)
        neckR = 0.056f * scale * ((widthScale + (rz / rz0).coerceIn(0.7f, 1.4f)) / 2)
        neckCz = headCz - 0.012f * scale
        // With the head model, its own neck is used down to just where it starts to widen into the shoulders.
        neckBottom = fit?.neckBaseY ?: (minY - 0.10f * scale - oy)
    }

    /** Fits [model] to the scanned face (and the skull measured from the photos, if any), in the face frame. */
    private fun fitModel(model: HeadModel, shape: HeadShape?, scale: Float, ox: Float, oy: Float, oz: Float): HeadModel.Fitted? {
        var nose = 0
        for (i in 0 until verts.size / 3) if (verts[3 * i + 2] > verts[3 * nose + 2]) nose = i
        val tip = floatArrayOf(verts[3 * nose], verts[3 * nose + 1], verts[3 * nose + 2])
        val targets = shape?.let {
            // Thick hair in the outline can't make the head too big for the face.
            HeadModel.Targets(-ox, it.rx.coerceIn(faceW / 2 * 0.98f, faceW / 2 + 0.02f * scale),
                it.top.coerceIn(maxY + 0.025f * scale, maxY + 0.075f * scale), it.zBack)
        }
        val f = model.fit(::faceDepth, tip, scale, targets)
        if (f.used < 200 || f.rmsMm > 6f) return null
        // The face offset moves the face on the head, i.e. the head the other way.
        if (ox != 0f || oy != 0f || oz != 0f) for (i in 0 until model.n) { f.verts[3 * i] -= ox; f.verts[3 * i + 1] -= oy; f.verts[3 * i + 2] -= oz }
        return HeadModel.Fitted(f.verts, f.coeffs, f.scale, f.rmsMm, f.used, f.neckCutY - oy, f.neckBaseY - oy)
    }

    /** Face depth at (x, y) for fitting: out = z, confidence, dz/dx, dz/dy; false where there is no face. */
    fun faceDepth(x: Float, y: Float, out: FloatArray): Boolean {
        val c = faceSample(x, y) ?: return false
        val z = c[0]; val w = c[1]
        val ax = faceSample(x + cell, y)?.get(0); val bx = faceSample(x - cell, y)?.get(0)
        val ay = faceSample(x, y + cell)?.get(0); val by = faceSample(x, y - cell)?.get(0)
        out[0] = z; out[1] = w
        out[2] = if (ax != null && bx != null) (ax - bx) / (2 * cell) else 0f
        out[3] = if (ay != null && by != null) (ay - by) / (2 * cell) else 0f
        return true
    }

    /**
     * Face-mesh triangles that are edge-on or face away (the mesh wrapping round the cheeks and under the jaw) are left out:
     * as a front-facing height field they turn into blade-like spikes, and the generic head covers there anyway.
     */
    private fun frontFacing(a: Int, b: Int, c: Int): Boolean {
        val ux = verts[3 * b] - verts[3 * a]; val uy = verts[3 * b + 1] - verts[3 * a + 1]; val uz = verts[3 * b + 2] - verts[3 * a + 2]
        val vx = verts[3 * c] - verts[3 * a]; val vy = verts[3 * c + 1] - verts[3 * a + 1]; val vz = verts[3 * c + 2] - verts[3 * a + 2]
        val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        return l < 1e-12f || nz / l >= 0.15f
    }

    private fun rasterizeFace() {
        for (t in 0 until faceTriangles.size / 3) {
            val a = faceTriangles[3 * t]; val b = faceTriangles[3 * t + 1]; val c = faceTriangles[3 * t + 2]
            if (!frontFacing(a, b, c)) continue
            val ax = gx(verts[3 * a]); val ay = gy(verts[3 * a + 1]); val az = verts[3 * a + 2]
            val bx = gx(verts[3 * b]); val by = gy(verts[3 * b + 1]); val bz = verts[3 * b + 2]
            val cx = gx(verts[3 * c]); val cy = gy(verts[3 * c + 1]); val cz = verts[3 * c + 2]
            val den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
            if (abs(den) < 1e-9f) continue
            val i0 = max(0, kotlin.math.floor(min(ax, min(bx, cx))).toInt())
            val i1 = min(gw - 1, ceil(max(ax, max(bx, cx))).toInt())
            val j0 = max(0, kotlin.math.floor(min(ay, min(by, cy))).toInt())
            val j1 = min(gh - 1, ceil(max(ay, max(by, cy))).toInt())
            for (j in j0..j1) for (i in i0..i1) {
                val w0 = ((by - cy) * (i - cx) + (cx - bx) * (j - cy)) / den
                val w1 = ((cy - ay) * (i - cx) + (ax - cx) * (j - cy)) / den
                val w2 = 1 - w0 - w1
                if (w0 < -1e-3f || w1 < -1e-3f || w2 < -1e-3f) continue
                val z = w0 * az + w1 * bz + w2 * cz
                val k = j * gw + i
                if (zFace[k].isNaN() || z > zFace[k]) zFace[k] = z
            }
        }
    }

    /** Blend weight: 0 at the face border, rising to 1 [borderCells] cells inside (chamfer distance). */
    private fun computeBlend(borderCells: Int) {
        val big = 1e6f
        val d = FloatArray(gw * gh) { if (zFace[it].isNaN()) 0f else big }
        for (j in 0 until gh) for (i in 0 until gw) {
            val k = j * gw + i
            if (d[k] == 0f) continue
            var v = d[k]
            if (i > 0) v = min(v, d[k - 1] + 1)
            if (j > 0) v = min(v, d[k - gw] + 1)
            if (i > 0 && j > 0) v = min(v, d[k - gw - 1] + 1.414f)
            if (i < gw - 1 && j > 0) v = min(v, d[k - gw + 1] + 1.414f)
            if (i == 0 || j == 0 || i == gw - 1 || j == gh - 1) v = min(v, 1f)
            d[k] = v
        }
        for (j in gh - 1 downTo 0) for (i in gw - 1 downTo 0) {
            val k = j * gw + i
            if (d[k] == 0f) continue
            var v = d[k]
            if (i < gw - 1) v = min(v, d[k + 1] + 1)
            if (j < gh - 1) v = min(v, d[k + gw] + 1)
            if (i < gw - 1 && j < gh - 1) v = min(v, d[k + gw + 1] + 1.414f)
            if (i > 0 && j < gh - 1) v = min(v, d[k + gw - 1] + 1.414f)
            d[k] = v
        }
        for (k in d.indices) blend[k] = min(1f, d[k] / borderCells)
        // Soften the weight so the jagged (row-by-row) border of the face mesh can't print ridges onto the cheeks.
        repeat(3) {
            val src = blend.copyOf()
            for (j in 0 until gh) for (i in 0 until gw) {
                val k = j * gw + i
                if (zFace[k].isNaN()) continue
                var sum = 0f; var n = 0
                for (dj in -2..2) for (di in -2..2) {
                    val ii = i + di; val jj = j + dj
                    if (ii < 0 || jj < 0 || ii >= gw || jj >= gh) { n++; continue }
                    sum += src[jj * gw + ii]; n++
                }
                blend[k] = min(src[k], sum / n)
            }
        }
    }


    /**
     * Face height and blend weight at (x, y), bilinearly interpolated between grid cells so the face surface
     * has no terraces on steep parts (cheeks, jaw). Null outside the face.
     */
    private fun faceSample(x: Float, y: Float): FloatArray? {
        val fi = gx(x) - 0.5f; val fj = gy(y) - 0.5f
        val i0 = kotlin.math.floor(fi).toInt(); val j0 = kotlin.math.floor(fj).toInt()
        val tx = fi - i0; val ty = fj - j0
        var zs = 0f; var ws = 0f; var wt = 0f
        for (dj in 0..1) for (di in 0..1) {
            val i = i0 + di; val j = j0 + dj
            if (i < 0 || j < 0 || i >= gw || j >= gh) continue
            val k = j * gw + i
            val z = zFace[k]
            if (z.isNaN()) continue
            val bw = (if (di == 0) 1 - tx else tx) * (if (dj == 0) 1 - ty else ty)
            zs += z * bw; ws += blend[k] * bw; wt += bw
        }
        if (wt < 0.25f) return null
        val out = sampleBuf.get()!!
        out[0] = zs / wt
        out[1] = ws * min(1f, wt) / wt * smoothstep(0.25f, 1f, wt)    // fade out where cells are missing
        return out
    }

    private fun gx(x: Float) = (x - minX) / cell + 1f
    private fun gy(y: Float) = (y - minY) / cell + 1f

    private fun smin(a: Float, b: Float, k: Float): Float {
        // exponential smooth minimum
        return -ln(exp(-a / k) + exp(-b / k)) * k
    }

    /** Approximate signed distance of the generic head + neck (negative inside). */
    fun genericSdf(x: Float, y: Float, z: Float): Float {
        modelSdf?.let { m ->
            // Below where the model's neck starts to flare into the shoulders, the neck carries straight down.
            var d = m.sample(x, y, z)
            // The model's ears are thin; thickened a little so they survive the low-poly mesh without holes.
            modelEar?.let { e ->
                val dx = abs(x - headCx) - e[0]; val dy = y - e[1]; val dz = z - e[2]
                val r = sqrt(dx * dx + dy * dy + dz * dz)
                if (r < 0.04f) d -= 0.0018f * (1f - smoothstep(0.025f, 0.04f, r))
            }
            return max(d, neckBottom - y)
        }
        val sc = earScale
        // Cranium: main ellipsoid plus a rounded back of the skull (occiput), a little narrower at the temples.
        val ex = (x - headCx) / rx; val ey = (y - headCy) / ry; val ez = (z - headCz) / rz
        val temple = 1f - 0.06f * smoothstep(0.0f, 0.7f, ez) * smoothstep(-0.2f, 0.4f, ey)
        // Below the ears the skull narrows towards the neck (instead of a wide flat underside that leaves a shelf).
        val low = smoothstep(-0.15f, -0.95f, ey)
        val exT = ex / (temple * (1f - 0.3f * low))
        val cranium = (sqrt(exT * exT + ey * ey + ez * ez) - 1f) * min(rx, min(ry, rz))
        val occ = ellipsoid(x - headCx, y - (headCy + 0.1f * ry), z - (headCz - 0.35f * rz), rx * 0.88f, ry * 0.72f, rz * 0.7f)
        var head = smin(cranium, occ, 0.01f)
        // No separate jaw shape: the scanned face has the chin and jaw, and extra jaw shapes showed as bulges
        // either side of the neck.
        // Cheekbones bridging the face to the sides of the head.
        val cbY = minY + 0.56f * (maxY - minY); val cbZ = noseZ - 0.065f * sc
        val cheek = min(
            ellipsoid(x - (headCx - 0.048f * sc), y - cbY, z - cbZ, 0.012f * sc, 0.01f * sc, 0.018f * sc),
            ellipsoid(x - (headCx + 0.048f * sc), y - cbY, z - cbZ, 0.012f * sc, 0.01f * sc, 0.018f * sc),
        )
        head = smin(head, cheek, 0.008f * sc)
        // Neck: an oval column, a little slimmer side to side than front to back, leaning forward towards its
        // base like a real neck, flaring slightly into the shoulders; flat cut at the bottom.
        val topY = earY - 0.01f * sc
        val tN = ((topY - y) / (topY - (neckBottom - 0.02f))).coerceIn(0f, 1f)      // 0 under the skull, 1 at the base
        val axisZ = neckCz + 0.024f * sc * tN
        val flare = smoothstep(0.65f, 1f, tN)
        val na = neckR * 0.9f * (1f + 0.16f * flare); val nb = neckR * 0.98f * (1f + 0.08f * flare)
        val qx = (x - headCx) / na; val qz = (z - axisZ) / nb
        val nr = (sqrt(qx * qx + qz * qz) - 1f) * min(na, nb)
        val neck = max(nr, max(y - headCy, neckBottom - 0.02f - y))
        // A wide, soft join: fills under the jaw and behind the ears, no ridge where neck meets head.
        return sminPoly(head, neck, 0.026f * sc)
    }

    /** Polynomial smooth minimum: blends only within [k] of the crease, at most k/4 extra material. */
    private fun sminPoly(a: Float, b: Float, k: Float): Float {
        val h = (k - abs(a - b)).coerceAtLeast(0f) / k
        return min(a, b) - h * h * k * 0.25f
    }

    /** Capsule from a to b with radius going from ra to rb. */
    private fun capsule(x: Float, y: Float, z: Float, ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, ra: Float, rb: Float): Float {
        val ux = bx - ax; val uy = by - ay; val uz = bz - az
        val vx = x - ax; val vy = y - ay; val vz = z - az
        val t = ((vx * ux + vy * uy + vz * uz) / (ux * ux + uy * uy + uz * uz)).coerceIn(0f, 1f)
        val dx = vx - t * ux; val dy = vy - t * uy; val dz = vz - t * uz
        return sqrt(dx * dx + dy * dy + dz * dz) - (ra + (rb - ra) * t)
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun smax(a: Float, b: Float, k: Float) = -smin(-a, -b, k)

    /** 0 outside the real face, rising smoothly to 1 a little inside its border. */
    fun faceMask(x: Float, y: Float, z: Float): Float {
        val fs = faceSample(x, y) ?: return 0f
        if (z < fs[0] - 0.03f) return 0f
        return smoothstep(0f, 1f, fs[1])
    }

    /** Height of the hairline around the head: forehead in front, sideburns at the sides, nape at the back. */
    private fun hairlineY(x: Float, z: Float): Float {
        // Measured from the ear, front to back: the forehead line carries across the temples, drops to a short
        // sideburn just in front of the ear, goes round the ear, and comes down to the nape at the back.
        val s = abs(x - headCx) / rx
        val forehead = maxY - 0.008f + 0.008f * smoothstep(0.35f, 0.85f, s)   // slight temple recession
        val sideburn = earY - 0.005f * earScale
        val nape = minY + 0.04f
        val dz = (z - earZ) / earScale
        return if (dz > 0.012f) sideburn + (forehead - sideburn) * smoothstep(0.018f, 0.04f, dz)
        else nape + (sideburn - nape) * smoothstep(-0.075f, 0f, dz)
    }

    /** 0..1: how much of the scalp at this point is covered by hair (soft hairline, never on the face). */
    private fun scalpCoverage(x: Float, y: Float, z: Float): Float {
        // Classic styles get a wider, painted-looking fade at the hairline.
        val soft = 0.006f
        return smoothstep(-soft, soft * 0.6f, y - hairlineY(x, z)) * (1f - faceMask(x, y, z))
    }

    /** Hair thickness grown out from the scalp for the chosen style (metres). */
    fun hairThickness(x: Float, y: Float, z: Float): Float {
        if (hairStyle == HairStyle.NONE) return 0f
        val cov = scalpCoverage(x, y, z)
        if (cov <= 0f) return 0f
        val u = (y - headCy) / ry
        val w = (z - headCz) / rz
        val lx = x - headCx
        val t = when (hairStyle) {
            HairStyle.BUZZ -> 0.0015f
            HairStyle.MOHICAN -> {
                // Shaved sides; a raised strip down the middle that the spikes grow out of.
                val g = lx / 0.02f
                0.001f + 0.007f * exp(-g * g * g * g) * smoothstep(-0.3f, 0.2f, u)
            }
            HairStyle.SIDE_PART -> {
                // Combed over from a side part: fuller on top and on the side it's combed towards, short at the sides.
                val big = smoothstep(0.3f * rx, -0.6f * rx, lx)
                val g = (lx - PART_X * rx) / 0.006f
                val part = 0.003f * exp(-g * g) * smoothstep(0.35f, 0.7f, u) * smoothstep(-0.5f, 0.2f, w)
                0.004f + 0.011f * smoothstep(0.1f, 0.8f, u) + 0.004f * big * smoothstep(0.0f, 0.6f, u) +
                    0.005f * smoothstep(0.3f, 0.8f, w) * smoothstep(0.4f, 0.85f, u) - part
            }
            HairStyle.CURTAINS -> {
                // Centre part, full on top; the long sides are the curtain shape in [extraHairSdf].
                val g = lx / 0.005f
                val part = 0.0035f * exp(-g * g) * smoothstep(0.35f, 0.7f, u) * smoothstep(-0.6f, 0.2f, w)
                0.008f + 0.007f * smoothstep(0.1f, 0.8f, u) - part
            }
            HairStyle.SPIKY -> 0.004f + 0.006f * smoothstep(0.2f, 0.9f, u)   // short base under the spikes
            HairStyle.NONE -> 0f
        }
        return t * cov * (1f - hairCutAroundEars(x, y, z))
    }

    /** Hair shapes that hang beyond the scalp (the long sides of the curtains cut). 1 = none. */
    private fun extraHairSdf(x: Float, y: Float, z: Float): Float {
        if (hairStyle != HairStyle.CURTAINS) return 1f
        val lx = x - headCx; val ly = y - headCy; val lz = z - headCz
        // Jaw-length hair around the sides and back, tapering in a little at the ends, open at the face.
        val bottom = minY + 0.03f
        val taper = 1f - 0.1f * smoothstep(headCy - 0.02f, bottom, y)
        val ex = lx / ((rx + 0.02f) * taper); val ez = (lz + 0.004f) / ((rz + 0.014f) * taper)
        val tube = (sqrt(ex * ex + ez * ez) - 1f) * rx
        val front = lz - rz * 0.28f
        // Ragged, pointed ends instead of a straight cut.
        val ang = kotlin.math.atan2(lx, lz)
        val ragged = 0.012f * abs(kotlin.math.sin(ang * 9f))
        val shell = smax(smax(tube, front, 0.008f), smax(bottom + ragged - y, ly, 0.01f), 0.006f)
        return max(shell, faceMask(x, y, z) * 0.02f - 0.001f)
    }

    /** 0..1 hair colour amount for texturing (scalp coverage, or 1 on the hanging hair), with stubble speckle on shaved hair. */
    fun hairColorAmount(x: Float, y: Float, z: Float): Float {
        if (hairStyle == HairStyle.CURTAINS) return hairColorRaw(x, y, z)   // ears are under the hair
        val onEar = 1f - smoothstep(0.001f, 0.006f, earSdf(x, y, z))
        return hairColorRaw(x, y, z) * (1f - onEar)
    }

    private fun hairColorRaw(x: Float, y: Float, z: Float): Float {
        if (locks.isNotEmpty()) {
            val on = lockCover(x, y, z)
            if (on > 0.5f) return 1f
        }
        if (spikes.isNotEmpty() && spikesSdf(x, y, z) < 0.003f) return 1f
        if (extraHairSdf(x, y, z) < 0.004f && genericSdf(x, y, z) > 0.003f) return 1f - faceMask(x, y, z)
        val c = scalpCoverage(x, y, z)
        if (c <= 0f) return 0f
        return when (hairStyle) {
            HairStyle.NONE -> 0f
            HairStyle.BUZZ -> c * stubble(x, y, z, 0.62f)
            HairStyle.MOHICAN -> {
                // Full colour on the strip, stubble on the shaved sides.
                val g = (x - headCx) / 0.022f
                val crest = exp(-g * g * g * g) * smoothstep(-0.3f, 0.2f, (y - headCy) / ry)
                c * (crest + (1f - crest) * stubble(x, y, z, 0.42f))
            }
            else -> c
        }
    }

    /** Shaved-hair speckle: [density] on average, broken up into dots about a millimetre across. */
    private fun stubble(x: Float, y: Float, z: Float, density: Float): Float {
        val n = valueNoise(x / 0.0011f, y / 0.0011f, z / 0.0011f) * 0.65f + valueNoise(x / 0.004f, y / 0.004f, z / 0.004f) * 0.35f
        return (density * (0.35f + 1.3f * n)).coerceIn(0f, 1f)
    }

    private fun hash(i: Int, j: Int, k: Int): Float {
        var h = i * 374761393 + j * 668265263 + k * 1274126177
        h = (h xor (h ushr 13)) * 1103515245
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f
    }

    /** Smooth 3D value noise, 0..1. */
    private fun valueNoise(x: Float, y: Float, z: Float): Float {
        val ix = kotlin.math.floor(x).toInt(); val iy = kotlin.math.floor(y).toInt(); val iz = kotlin.math.floor(z).toInt()
        var fx = x - ix; var fy = y - iy; var fz = z - iz
        fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy); fz = fz * fz * (3 - 2 * fz)
        fun l(a: Float, b: Float, t: Float) = a + (b - a) * t
        val x00 = l(hash(ix, iy, iz), hash(ix + 1, iy, iz), fx)
        val x10 = l(hash(ix, iy + 1, iz), hash(ix + 1, iy + 1, iz), fx)
        val x01 = l(hash(ix, iy, iz + 1), hash(ix + 1, iy, iz + 1), fx)
        val x11 = l(hash(ix, iy + 1, iz + 1), hash(ix + 1, iy + 1, iz + 1), fx)
        return l(l(x00, x10, fy), l(x01, x11, fy), fz)
    }

    // ------------------------------------------------------------------ ears

    private val earScale = faceW / 0.14f
    /** Ear centre height: between eye level and the bottom of the nose. */
    private val earY = modelEar?.get(1) ?: (minY + 0.47f * (maxY - minY))
    private val earZ = modelEar?.get(2) ?: (headCz - 0.012f * earScale)
    /** Skull surface X at the ear centre. */
    private val earSkullX: Float = modelEar?.let { it[0] - 0.008f * earScale } ?: run {
        val ey = (earY - headCy) / ry; val ez = (earZ - headCz) / rz
        rx * sqrt((1f - ey * ey - ez * ez).coerceAtLeast(0.05f))
    }
    private val earTiltS = -0.26f    // sin(-15°): the top of the ear leans back
    private val earTiltC = 0.966f

    private fun ellipsoid(px: Float, py: Float, pz: Float, ax: Float, ay: Float, az: Float): Float {
        val qx = px / ax; val qy = py / ay; val qz = pz / az
        return (sqrt(qx * qx + qy * qy + qz * qz) - 1f) * min(ax, min(ay, az))
    }

    /** Signed distance to the two ears: a flattened shell, leaning back, with a hollow bowl on the outside. */
    fun earSdf(x: Float, y: Float, z: Float): Float {
        val s = earScale
        val lx = abs(x - headCx) - earSkullX     // outward from the skull
        val ly = y - earY; val lz = z - earZ
        if (lx < -0.03f * s || abs(ly) > 0.06f * s || abs(lz) > 0.05f * s) return 1f
        val yy = ly * earTiltC + lz * earTiltS
        val zz = -ly * earTiltS + lz * earTiltC
        // Stands further out at the back edge, like real ears.
        val flare = (-zz / (0.018f * s)).coerceIn(-1f, 1f) * 0.006f * s
        val body = ellipsoid(lx - 0.007f * s - flare, yy, zz + 0.002f * s, 0.0105f * s, 0.034f * s, 0.02f * s)
        val bowl = ellipsoid(lx - 0.016f * s - flare, yy + 0.002f * s, zz + 0.004f * s, 0.0082f * s, 0.024f * s, 0.012f * s)
        return max(body, -bowl)
    }

    /** 0..1: short styles are cut back around the ears so the ears show. */
    private fun hairCutAroundEars(x: Float, y: Float, z: Float): Float {
        if (hairStyle == HairStyle.CURTAINS) return 0f
        val dx = abs(x - headCx) - earSkullX; val dy = (y - earY) / 1.4f; val dz = z - earZ
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        return 1f - smoothstep(0.024f * earScale, 0.036f * earScale, d)
    }

    /** Eyes, lips and ears: kept smooth-shaded in the low-poly game look. */
    /** True when the face mesh is the standard 468-point layout (ARCore / ML Kit face mesh), so its points can be named. */
    private val namedPoints = verts.size / 3 in 468..478
    private fun px(i: Int) = verts[3 * i]
    private fun py(i: Int) = verts[3 * i + 1]

    /** Lips from the face mesh: centre, half width and half height (mouth corners 61 / 291, lip top 0, lip bottom 17). */
    private val lips: FloatArray? = if (!namedPoints) null else floatArrayOf(
        (px(61) + px(291)) / 2, (py(0) + py(17)) / 2, abs(px(291) - px(61)) / 2, abs(py(0) - py(17)) / 2)

    /**
     * The fitted head model's own ear points, for telling ear from head skin: (x, y, z, isEar) per point near the ears,
     * bucketed in a 5 mm grid.
     */
    private class EarPoints(val pts: FloatArray, val ear: BooleanArray, val x0: Float, val y0: Float, val z0: Float,
                            val nx: Int, val ny: Int, val nz: Int, val cells: Array<IntArray?>, val shade: FloatArray)

    private val earPoints: EarPoints? by lazy {
        val fit = fitted ?: return@lazy null
        val m = headModel ?: return@lazy null
        val ex = modelEar ?: return@lazy null
        val v = fit.verts
        val idx = (0 until m.n).filter { i ->
            abs(v[3 * i] - headCx) > ex[0] - headCx - 0.035f && abs(v[3 * i + 1] - ex[1]) < 0.06f && abs(v[3 * i + 2] - ex[2]) < 0.06f && !m.isCap(i)
        }
        if (idx.none { m.isPinna(it) }) return@lazy null
        val pts = FloatArray(idx.size * 3) { v[3 * idx[it / 3] + it % 3] }
        val ear = BooleanArray(idx.size) { k -> m.isPinna(idx[k]) }
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        for (k in idx.indices) {
            x0 = min(x0, pts[3 * k]); x1 = max(x1, pts[3 * k]); y0 = min(y0, pts[3 * k + 1]); y1 = max(y1, pts[3 * k + 1])
            z0 = min(z0, pts[3 * k + 2]); z1 = max(z1, pts[3 * k + 2])
        }
        val c = EAR_CELL
        val nx = ((x1 - x0) / c).toInt() + 1; val ny = ((y1 - y0) / c).toInt() + 1; val nz = ((z1 - z0) / c).toInt() + 1
        val lists = Array(nx * ny * nz) { IntList(4) }
        for (k in idx.indices) {
            val i = ((pts[3 * k] - x0) / c).toInt(); val j = ((pts[3 * k + 1] - y0) / c).toInt(); val l = ((pts[3 * k + 2] - z0) / c).toInt()
            lists[(l * ny + j) * nx + i].add(k)
        }
        val cells = Array(lists.size) { if (lists[it].size == 0) null else lists[it].toArray() }
        // Shading from the ear's real folds on the detailed model: creases (the surface curving in) and the bowl
        // (surface with lots of ear in front of it) darker, the rim (curving out) lighter.
        val nrm = m.vertexNormals(v)
        val sum = FloatArray(m.n * 3); val cnt = IntArray(m.n)
        val tr = m.tris
        for (f in 0 until tr.size / 3) for (a in 0 until 3) {
            val i = tr[3 * f + a]; val j = tr[3 * f + (a + 1) % 3]
            for (c in 0 until 3) { sum[3 * i + c] += v[3 * j + c]; sum[3 * j + c] += v[3 * i + c] }
            cnt[i]++; cnt[j]++
        }
        val shade = FloatArray(idx.size)
        val reach = 0.012f
        for (k in idx.indices) {
            val i = idx[k]
            val px = pts[3 * k]; val py = pts[3 * k + 1]; val pz = pts[3 * k + 2]
            val nx0 = nrm[3 * i]; val ny0 = nrm[3 * i + 1]; val nz0 = nrm[3 * i + 2]
            var cav = 0f
            if (cnt[i] > 0) {
                val ax = sum[3 * i] / cnt[i] - px; val ay = sum[3 * i + 1] / cnt[i] - py; val az = sum[3 * i + 2] / cnt[i] - pz
                cav = (ax * nx0 + ay * ny0 + az * nz0) / 0.0015f          // about -1 (rim) .. +1 (crease)
            }
            var occ = 0f
            val ci = ((px - x0) / c).toInt(); val cj = ((py - y0) / c).toInt(); val ck = ((pz - z0) / c).toInt()
            for (dk in -3..3) for (dj in -3..3) for (di in -3..3) {
                val a = ci + di; val b = cj + dj; val l = ck + dk
                if (a < 0 || b < 0 || l < 0 || a >= nx || b >= ny || l >= nz) continue
                val list = cells[(l * ny + b) * nx + a] ?: continue
                for (q in list) {
                    val dx = pts[3 * q] - px; val dy = pts[3 * q + 1] - py; val dz = pts[3 * q + 2] - pz
                    val d = sqrt(dx * dx + dy * dy + dz * dz)
                    if (d > reach || d < 1e-4f) continue
                    val front = (dx * nx0 + dy * ny0 + dz * nz0) / d
                    if (front > 0.3f) occ += front * (1f - d / reach)
                }
            }
            shade[k] = (1f - 0.035f * occ - 0.25f * cav.coerceIn(-1f, 1.5f)).coerceIn(0.5f, 1.15f)
        }
        EarPoints(pts, ear, x0, y0, z0, nx, ny, nz, cells, shade)
    }

    /** Shading of the ear at a point from its real folds (about 0.5 in deep creases .. 1.15 on the rim), or null. */
    fun earShade(x: Float, y: Float, z: Float): Float? {
        val e = earPoints ?: return null
        val c = EAR_CELL
        val ci = ((x - e.x0) / c).toInt(); val cj = ((y - e.y0) / c).toInt(); val ck = ((z - e.z0) / c).toInt()
        var ws = 0f; var vs = 0f
        for (dk in -1..1) for (dj in -1..1) for (di in -1..1) {
            val i = ci + di; val j = cj + dj; val k = ck + dk
            if (i < 0 || j < 0 || k < 0 || i >= e.nx || j >= e.ny || k >= e.nz) continue
            val list = e.cells[(k * e.ny + j) * e.nx + i] ?: continue
            for (p in list) {
                val dx = e.pts[3 * p] - x; val dy = e.pts[3 * p + 1] - y; val dz = e.pts[3 * p + 2] - z
                val d2 = dx * dx + dy * dy + dz * dz
                if (d2 > 0.004f * 0.004f) continue
                val w = 1f / (d2 + 1e-6f)
                ws += w; vs += w * e.shade[p]
            }
        }
        return if (ws > 0f) vs / ws else null
    }

    /**
     * How much a point is on the ear (0..1). With the fitted head model this comes from the model's own ear (nearest
     * model point is an ear point), so it follows the real ear shape and stops where the ear meets the head.
     */
    /**
     * Head skin right around the ear (within about a centimetre of it, not on it): where the ear's colour is matched
     * from. False without the head model's ear points.
     */
    fun aroundEar(x: Float, y: Float, z: Float): Boolean {
        val e = earPoints ?: return false
        val c = EAR_CELL
        val ci = ((x - e.x0) / c).toInt(); val cj = ((y - e.y0) / c).toInt(); val ck = ((z - e.z0) / c).toInt()
        var de = Float.MAX_VALUE; var dn = Float.MAX_VALUE
        for (dk in -2..2) for (dj in -2..2) for (di in -2..2) {
            val i = ci + di; val j = cj + dj; val k = ck + dk
            if (i < 0 || j < 0 || k < 0 || i >= e.nx || j >= e.ny || k >= e.nz) continue
            val list = e.cells[(k * e.ny + j) * e.nx + i] ?: continue
            for (p in list) {
                val dx = e.pts[3 * p] - x; val dy = e.pts[3 * p + 1] - y; val dz = e.pts[3 * p + 2] - z
                val d = dx * dx + dy * dy + dz * dz
                if (e.ear[p]) { if (d < de) de = d } else if (d < dn) dn = d
            }
        }
        if (de == Float.MAX_VALUE || dn == Float.MAX_VALUE) return false
        val dE = sqrt(de)
        return sqrt(dn) + 0.002f < dE && dE < 0.012f
    }

    fun onEar(x: Float, y: Float, z: Float): Float {
        val e = earPoints ?: return 1f - smoothstep(0.002f, 0.006f, earSdf(x, y, z))
        val c = EAR_CELL
        val ci = ((x - e.x0) / c).toInt(); val cj = ((y - e.y0) / c).toInt(); val ck = ((z - e.z0) / c).toInt()
        if (ci < -1 || cj < -1 || ck < -1 || ci > e.nx || cj > e.ny || ck > e.nz) return 0f
        var de = Float.MAX_VALUE; var dn = Float.MAX_VALUE
        for (dk in -1..1) for (dj in -1..1) for (di in -1..1) {
            val i = ci + di; val j = cj + dj; val k = ck + dk
            if (i < 0 || j < 0 || k < 0 || i >= e.nx || j >= e.ny || k >= e.nz) continue
            val list = e.cells[(k * e.ny + j) * e.nx + i] ?: continue
            for (p in list) {
                val dx = e.pts[3 * p] - x; val dy = e.pts[3 * p + 1] - y; val dz = e.pts[3 * p + 2] - z
                val d = dx * dx + dy * dy + dz * dz
                if (e.ear[p]) { if (d < de) de = d } else if (d < dn) dn = d
            }
        }
        if (de == Float.MAX_VALUE) return 0f
        if (dn == Float.MAX_VALUE) return 1f
        // Soft 1.5 mm edge where the nearest point changes from head to ear.
        return smoothstep(-0.0015f, 0.0015f, sqrt(dn) - sqrt(de))
    }

    /**
     * Where facial hair grows (0..1): the cheeks below a line from the sideburn to just under the nose, the upper lip,
     * chin and jaw, and under the jaw fading out [neckReach] metres down the neck. Not on the lips or the ears.
     * Placed from the person's own face points (mouth corners, lips, nose base, chin, jaw, face edge by the ear).
     */
    fun beardZone(x: Float, y: Float, z: Float, neckReach: Float, soft: Float = 0.005f, jawWeighted: Boolean = false): Float {
        val s = earScale
        val behind = smoothstep(earZ - 0.002f * s, earZ + 0.01f * s, z)
        if (behind <= 0f) return 0f
        val ear = onEar(x, y, z)
        if (ear >= 1f) return 0f
        if (!namedPoints) return genericBeardZone(x, y, z, neckReach, soft) * behind * (1f - ear)
        val cx = (px(61) + px(291)) / 2
        // The side of the face this point is on: mouth corner, face edge by the ear, jaw angle.
        val plusSide = px(291) > px(61)
        val right = x >= cx
        val mc = if (right == plusSide) 291 else 61
        val fe = if (right == plusSide) 454 else 234
        val ja = if (right == plusSide) 397 else 172
        val span = abs(px(fe) - cx).coerceAtLeast(0.01f)
        val u = abs(x - cx) / span                         // 0 at the middle, 1 at the face edge by the ear
        val um = (abs(px(mc) - cx) / span).coerceIn(0.1f, 0.8f)
        // Top edge: just under the nose in the middle, a little above the mouth corner, up to the sideburn by the ear.
        val underNose = py(2) - 0.003f
        val aboveCorner = py(mc) + 0.45f * (py(2) - py(mc))
        val sideburn = py(fe) - 0.004f
        val upper = when {
            u <= um -> underNose + (aboveCorner - underNose) * (u / um)
            u <= 1f -> aboveCorner + (sideburn - aboveCorner) * ((u - um) / (1f - um))
            else -> sideburn
        }
        val top = 1f - smoothstep(upper - soft, upper + 0.004f, y)
        // Bottom edge: chin in the middle, jaw angle at the side, rising towards the ear; then down the neck.
        val jaw = if (u <= 1f) py(152) + (py(ja) - py(152)) * u else py(ja)
        val bottom = smoothstep(jaw - neckReach - 0.6f * soft - 0.012f, jaw - neckReach + 0.004f, y)
        // Thickest along the jaw and chin, as beards grow.
        val jawW = if (jawWeighted) 0.6f + 0.4f * (1f - smoothstep(0.004f, 0.03f, abs(y - jaw))) else 1f
        // Lips stay clear.
        val l = lips!!
        val lx = (x - l[0]) / (l[2] + 0.003f); val ly = (y - l[1]) / (l[3] + 0.002f)
        val lipsW = if (z > noseZ - 0.05f) smoothstep(0.9f, 1.15f, sqrt(lx * lx + ly * ly)) else 1f
        return behind * top * bottom * lipsW * (1f - ear) * jawW
    }

    /** [beardZone] from typical proportions, when the face points can't be named. */
    private fun genericBeardZone(x: Float, y: Float, z: Float, neckReach: Float, soft: Float): Float {
        val h = maxY - minY
        val cx = (minX + maxX) / 2
        val ax = abs(x - cx)
        val s = earScale
        val mouthY = minY + 0.21f * h
        val mouthHalf = 0.22f * faceW
        val front = smoothstep(earZ, noseZ - 0.03f, z)
        val upper = earY - 0.004f * s + (minY + 0.31f * h - (earY - 0.004f * s)) * Math.pow(front.toDouble(), 0.7).toFloat()
        val top = 1f - smoothstep(upper - max(0.006f, soft), upper + 0.004f, y)
        val jaw = minY + (earY - 0.07f * s - minY) * (1f - front)
        val bottom = smoothstep(jaw - neckReach - 0.012f, jaw - neckReach + 0.004f, y)
        val lx = ax / (1.12f * mouthHalf); val ly = (y - mouthY - 0.003f * h) / (0.04f * h)
        val lipsW = if (z > noseZ - 0.05f) smoothstep(0.85f, 1.15f, sqrt(lx * lx + ly * ly)) else 1f
        return top * bottom * lipsW
    }

    fun isDetailArea(x: Float, y: Float, z: Float): Boolean {
        if (earSdf(x, y, z) < 0.004f) return true
        if (z < noseZ - 0.06f) return false
        val h = maxY - minY
        val cx = (minX + maxX) / 2
        val ex = (abs(x - cx) - 0.23f * faceW) / (0.17f * faceW); val ey = (y - (minY + 0.6f * h)) / (0.1f * h)
        if (ex * ex + ey * ey < 1f) return true
        val l = lips
        if (l != null) {
            val lx = (x - l[0]) / (l[2] + 0.004f); val ly = (y - l[1]) / (l[3] + 0.004f)
            return lx * lx + ly * ly < 1f
        }
        val lx = (x - cx) / (0.22f * faceW); val ly = (y - (minY + 0.21f * h)) / (0.09f * h)
        return lx * lx + ly * ly < 1f
    }

    /** Head + neck + hair, before the face is merged in. */
    private fun headWithHair(x: Float, y: Float, z: Float, shift: Float = 0f): Float {
        // [shift]: the skull (and hair on it) is bent along Z to meet the face edge; the ears stay put.
        val zs = z - shift
        var g = genericSdf(x, y, zs) - hairThickness(x, y, zs)
        // Curtains cover the ears, so they're left off (they would poke through the hair).
        // (The fitted model has its own ears.)
        if (hairStyle != HairStyle.CURTAINS && modelSdf == null) g = smin(g, earSdf(x, y, z), 0.003f)
        val extra = extraHairSdf(x, y, z)
        if (extra < 0.05f) g = smin(g, extra, 0.006f)
        return g
    }

    /** Signed distance of the personalised head: generic head with the real face surface merged in front. */
    fun sdf(x: Float, y: Float, z: Float): Float {
        var base = headAndFace(x, y, z)
        if (spikes.isNotEmpty()) base = smin(base, spikesSdf(x, y, z), 0.0025f)
        if (locks.isEmpty()) return base
        // Locks lie on top of everything, so a fringe can fall over the upper forehead.
        return smin(base, locksSdf(x, y, z), 0.0025f)
    }

    // ------------------------------------------------------------------ fitting the head to the face edge

    /**
     * The generic head is bent forward or back (along Z) so its surface meets the edge of the scanned face all
     * the way round (forehead, temples, cheeks, jaw), fading out over [FIT_FADE] outside the face. Without this
     * the face could sit in front of the head like a mask, or behind it.
     */
    private class EdgeFit(val x0: Float, val y0: Float, val step: Float, val w: Int, val h: Int, val shift: FloatArray, val outside: FloatArray)

    private val edgeFit: EdgeFit by lazy { buildEdgeFit() }

    private fun buildEdgeFit(): EdgeFit {
        val step = 0.002f; val margin = 0.05f
        val x0 = minX - margin; val y0 = minY - margin
        val w = ceil((maxX - minX + 2 * margin) / step).toInt() + 1
        val h = ceil((maxY - minY + 2 * margin) / step).toInt() + 1
        val inside = BooleanArray(w * h)
        val zf = FloatArray(w * h)
        for (j in 0 until h) for (i in 0 until w) {
            val fs = faceSample(x0 + i * step, y0 + j * step) ?: continue
            inside[j * w + i] = true; zf[j * w + i] = fs[0]
        }
        // Mismatch at each border cell: face depth minus the generic skull's front surface there.
        val delta = FloatArray(w * h) { Float.NaN }
        for (j in 0 until h) for (i in 0 until w) {
            val k = j * w + i
            if (!inside[k]) continue
            val border = (i == 0 || j == 0 || i == w - 1 || j == h - 1) ||
                !inside[k - 1] || !inside[k + 1] || !inside[k - w] || !inside[k + w]
            if (!border) continue
            val x = x0 + i * step; val y = y0 + j * step
            var z = zf[k] + 0.06f
            var zg = Float.NaN
            while (z > zf[k] - 0.1f) {
                if (genericSdf(x, y, z) < 0f) { zg = z; break }
                z -= 0.001f
            }
            if (zg.isNaN()) continue
            // Only where the skull faces forward there (forehead, cheeks, chin): at the sides of the face moving the
            // head along Z would shear it into fins.
            val e = 0.002f
            val gxv = genericSdf(x + e, y, zg) - genericSdf(x - e, y, zg)
            val gyv = genericSdf(x, y + e, zg) - genericSdf(x, y - e, zg)
            val gzv = genericSdf(x, y, zg + e) - genericSdf(x, y, zg - e)
            val nz = gzv / sqrt(gxv * gxv + gyv * gyv + gzv * gzv).coerceAtLeast(1e-6f)
            delta[k] = (zf[k] - zg).coerceIn(-0.015f, 0.015f) * smoothstep(0.45f, 0.8f, nz)
        }
        // Nearest border cell for every cell (two-pass chamfer), and the distance outside the face.
        val near = IntArray(w * h) { if (!delta[it].isNaN()) it else -1 }
        fun d2(a: Int, b: Int): Float { val dx = (a % w - b % w).toFloat(); val dy = (a / w - b / w).toFloat(); return dx * dx + dy * dy }
        fun relax(k: Int, n: Int) {
            val c = near[n]; if (c < 0) return
            if (near[k] < 0 || d2(k, c) < d2(k, near[k])) near[k] = c
        }
        for (j in 0 until h) for (i in 0 until w) {
            val k = j * w + i
            if (i > 0) relax(k, k - 1); if (j > 0) relax(k, k - w)
            if (i > 0 && j > 0) relax(k, k - w - 1); if (i < w - 1 && j > 0) relax(k, k - w + 1)
        }
        for (j in h - 1 downTo 0) for (i in w - 1 downTo 0) {
            val k = j * w + i
            if (i < w - 1) relax(k, k + 1); if (j < h - 1) relax(k, k + w)
            if (i < w - 1 && j < h - 1) relax(k, k + w + 1); if (i > 0 && j < h - 1) relax(k, k + w - 1)
        }
        val shift = FloatArray(w * h); val outside = FloatArray(w * h)
        for (k in 0 until w * h) {
            val c = near[k]
            if (c < 0) { outside[k] = margin; continue }
            val dist = sqrt(d2(k, c)) * step
            outside[k] = if (inside[k]) 0f else dist
            val fade = if (inside[k]) 1f else 1f - smoothstep(0f, FIT_FADE, dist)
            shift[k] = delta[c] * fade
        }
        // Smooth, so the bend has no kinks from the jagged face outline.
        repeat(3) {
            val src = shift.copyOf()
            for (j in 1 until h - 1) for (i in 1 until w - 1) {
                var sum = 0f
                for (dj in -1..1) for (di in -1..1) sum += src[(j + dj) * w + i + di]
                shift[j * w + i] = sum / 9f
            }
        }
        return EdgeFit(x0, y0, step, w, h, shift, outside)
    }

    private fun sampleGrid(e: EdgeFit, a: FloatArray, x: Float, y: Float, outsideValue: Float): Float {
        val fi = (x - e.x0) / e.step; val fj = (y - e.y0) / e.step
        if (fi < 0f || fj < 0f || fi > e.w - 1.001f || fj > e.h - 1.001f) return outsideValue
        val i = fi.toInt(); val j = fj.toInt(); val tx = fi - i; val ty = fj - j
        val k = j * e.w + i
        return (a[k] * (1 - tx) + a[k + 1] * tx) * (1 - ty) + (a[k + e.w] * (1 - tx) + a[k + e.w + 1] * tx) * ty
    }

    /** How far the generic head is moved forward (+) or back at (x, y) to meet the face edge. */
    // Not under the chin: the face already fades out there, and bending the neck would leave ridges round it.
    private fun edgeShift(x: Float, y: Float) =
        sampleGrid(edgeFit, edgeFit.shift, x, y, 0f) * smoothstep(minY + 0.005f, minY + 0.035f, y)

    /** Distance (m) from (x, y) to the scanned face's outline, 0 on the face. */
    fun distanceOutsideFace(x: Float, y: Float) = sampleGrid(edgeFit, edgeFit.outside, x, y, 0.05f)

    private fun headAndFace(x: Float, y: Float, z: Float): Float {
        // Only the front of the head bends; the back of the head and neck stay where they are.
        val g = headWithHair(x, y, z, edgeShift(x, y) * smoothstep(headCz - 0.03f, headCz + 0.03f, z))
        val fs = faceSample(x, y) ?: return g
        // Under the chin the face mesh turns edge-on and would leave a shelf: fade it out there so the jaw and
        // neck take over smoothly.
        val zf = fs[0]; val w = fs[1] * smoothstep(minY + 0.002f, minY + 0.022f, y)
        val fz = (z - zf) * 0.85f
        // The face may only add material close to the generic head, so steep mesh borders can't form flaps.
        // Near the border the allowance shrinks to a few mm, so nothing sticks out at the sides of the face.
        // Near the bottom of the face (chin and jaw underside) the allowance is small too, or the face would form a
        // shelf sticking out round the neck.
        val low = smoothstep(minY + 0.004f, minY + 0.045f, y)
        val faceSlab = max(max(fz, (zf - 0.07f) - z), g - (0.004f + 0.026f * w * low))
        // In front of the face surface the face may cut into the generic head only well inside the face (eye
        // sockets, under the nose); towards its border it can only add, so where the face sits a little behind the
        // skull (top of the forehead, temples) no groove is cut along the join.
        val faceField = if (z > zf) min(fz, g + 0.03f * smoothstep(0.65f, 1f, w)) else min(g, faceSlab)
        // Fade from the face to the plain head across the border band (in x/y), so the jagged edge of the
        // face mesh can't leave ridges or blades on the cheeks and jaw.
        return w * faceField + (1 - w) * g
    }


    // ------------------------------------------------------------------ sculpted locks (spikes, curtain strands)

    /** One clump of hair: a flattened, tapering tube along a curve over the scalp, ending in a point. */
    private class Lock(val p: FloatArray, val r: FloatArray, val n: FloatArray, val cx: Float, val cy: Float, val cz: Float, val bound: Float)

    private class LockHit(var d: Float = 1f, var across: Float = 0f, var along: Float = 0f, var lock: Int = -1)

    private val locks: List<Lock> by lazy {
        when (hairStyle) {
            HairStyle.CURTAINS -> buildCurtainLocks()
            else -> emptyList()
        }
    }

    /** Point on the generic skull in unit-sphere direction (dx,dy,dz), lifted [lift] m along the surface normal. */
    private fun scalpPoint(dx: Float, dy: Float, dz: Float, lift: Float, out: FloatArray, o: Int, nOut: FloatArray) {
        val l = sqrt(dx * dx + dy * dy + dz * dz)
        val ux = dx / l; val uy = dy / l; val uz = dz / l
        var nx = ux / rx; var ny = uy / ry; var nz = uz / rz
        val nl = sqrt(nx * nx + ny * ny + nz * nz)
        nx /= nl; ny /= nl; nz /= nl
        out[o] = headCx + ux * rx + nx * lift
        out[o + 1] = headCy + uy * ry + ny * lift
        out[o + 2] = headCz + uz * rz + nz * lift
        nOut[o] = nx; nOut[o + 1] = ny; nOut[o + 2] = nz
    }

    /**
     * A lock from unit-sphere direction [a] to [b] (great-circle path), with the tip flicking [flick] m off the
     * scalp. Width [w] (m) at its fullest; it tapers to a point at the tip and narrows at the root.
     */
    private fun lock(a: FloatArray, b: FloatArray, w: Float, lift: Float = 0.007f, flick: Float = 0.004f, drop: Float = 0f): Lock {
        val skull = 9
        val hang = if (drop > 0f) 4 else 0
        val steps = skull + hang
        val p = FloatArray(steps * 3); val n = FloatArray(steps * 3); val r = FloatArray(steps)
        val al = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]); val bl = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        val ax = a[0] / al; val ay = a[1] / al; val az = a[2] / al
        val bx = b[0] / bl; val by = b[1] / bl; val bz = b[2] / bl
        val om = kotlin.math.acos((ax * bx + ay * by + az * bz).coerceIn(-1f, 1f)).coerceAtLeast(1e-4f)
        for (i in 0 until skull) {
            val t = i / (skull - 1f)
            val s0 = kotlin.math.sin((1 - t) * om) / kotlin.math.sin(om); val s1 = kotlin.math.sin(t * om) / kotlin.math.sin(om)
            val h = lift + 0.004f * kotlin.math.sin(Math.PI.toFloat() * t) + flick * t * t * t
            scalpPoint(s0 * ax + s1 * bx, s0 * ay + s1 * by, s0 * az + s1 * bz, h, p, 3 * i, n)
        }
        // Long hair: carry on straight down from the end of the skull path, drifting slightly outward.
        val e = 3 * (skull - 1)
        var ox = n[e]; var oz = n[e + 2]
        val ol = sqrt(ox * ox + oz * oz).coerceAtLeast(1e-4f); ox /= ol; oz /= ol
        for (k in 1..hang) {
            val o = 3 * (skull - 1 + k)
            val f = k / hang.toFloat()
            p[o] = p[e] + ox * 0.006f * f; p[o + 1] = p[e + 1] - drop * f; p[o + 2] = p[e + 2] + oz * 0.006f * f
            n[o] = ox; n[o + 1] = 0f; n[o + 2] = oz
        }
        for (i in 0 until steps) {
            val t = i / (steps - 1f)
            // fullest a third of the way along, a point at the tip
            r[i] = w * 0.5f * (0.55f + 0.45f * kotlin.math.sin(Math.PI.toFloat() * min(1f, t * 1.5f))) * (1f - t * t * t).coerceAtLeast(0f)
        }
        var cx = 0f; var cy = 0f; var cz = 0f
        for (i in 0 until steps) { cx += p[3 * i]; cy += p[3 * i + 1]; cz += p[3 * i + 2] }
        cx /= steps; cy /= steps; cz /= steps
        var bound = 0f
        for (i in 0 until steps) {
            val dx = p[3 * i] - cx; val dy = p[3 * i + 1] - cy; val dz = p[3 * i + 2] - cz
            bound = max(bound, sqrt(dx * dx + dy * dy + dz * dz) + r[i])
        }
        return Lock(p, r, n, cx, cy, cz, bound)
    }

    /** Unit-sphere direction for a point on the skull with height u (-1..1), around-angle [deg] (0 = front, +90 = +X side). */
    private fun dir(u: Float, deg: Float): FloatArray {
        val c = sqrt((1f - u * u).coerceAtLeast(0f))
        val a = Math.toRadians(deg.toDouble())
        return floatArrayOf(c * kotlin.math.sin(a).toFloat(), u, c * kotlin.math.cos(a).toFloat())
    }

    // ------------------------------------------------------------------ spikes (SPIKY, MOHICAN)

    /** One spike: a cone from base a (radius [ra]) to a sharp tip b, with a bounding sphere for quick rejects. */
    private class Spike(val ax: Float, val ay: Float, val az: Float, val bx: Float, val by: Float, val bz: Float, val ra: Float) {
        val mx = (ax + bx) / 2; val my = (ay + by) / 2; val mz = (az + bz) / 2
        val bound = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az)) / 2 + ra
    }

    private val spikes: List<Spike> by lazy {
        when (hairStyle) {
            HairStyle.SPIKY -> buildSpiky()
            HairStyle.MOHICAN -> buildMohican()
            else -> emptyList()
        }
    }

    /** A spike rooted on the skull at unit direction (dx,dy,dz), leaning [up] towards +Y and [back] towards -Z. */
    private fun spike(dx: Float, dy: Float, dz: Float, len: Float, ra: Float, up: Float, back: Float, jx: Float, jz: Float): Spike {
        val p = FloatArray(3); val n = FloatArray(3)
        scalpPoint(dx, dy, dz, 0.003f, p, 0, n)
        var vx = n[0] + jx; var vy = n[1] + up; var vz = n[2] - back + jz
        val l = sqrt(vx * vx + vy * vy + vz * vz); vx /= l; vy /= l; vz /= l
        return Spike(p[0] - n[0] * 0.004f, p[1] - n[1] * 0.004f, p[2] - n[2] * 0.004f, p[0] + vx * len, p[1] + vy * len, p[2] + vz * len, ra)
    }

    /** Spiky cut: lots of sharp spikes all over the top, standing up and out; shorter ones round the sides. */
    private fun buildSpiky(): List<Spike> {
        val out = ArrayList<Spike>()
        val n = 190
        val golden = Math.PI.toFloat() * (3f - sqrt(5f))
        for (i in 0 until n) {
            // Fibonacci points over the sphere, kept where the scalp has hair.
            val dy = 1f - 2f * (i + 0.5f) / n
            val rr = sqrt((1f - dy * dy).coerceAtLeast(0f))
            val a = golden * i
            val dx = rr * kotlin.math.sin(a); val dz = rr * kotlin.math.cos(a)
            if (dy < 0.05f) continue
            val px = headCx + dx * rx; val py = headCy + dy * ry; val pz = headCz + dz * rz
            if (py < hairlineY(px, pz) + 0.008f) continue
            val j1 = hash(i, 1, 7) - 0.5f; val j2 = hash(i, 3, 5) - 0.5f; val j3 = hash(i, 9, 2)
            val top = smoothstep(0.15f, 0.7f, dy)
            val len = (0.02f + 0.026f * top) * (0.8f + 0.4f * j3)
            out.add(spike(dx, dy, dz, len, 0.009f + 0.003f * top, up = 0.35f * top, back = 0.2f, jx = 0.35f * j1, jz = 0.35f * j2))
        }
        return out
    }

    /** Mohican: a strip of tall spikes from the forehead over the top to the crown, standing up and leaning back. */
    private fun buildMohican(): List<Spike> {
        val out = ArrayList<Spike>()
        val front = Math.toDegrees(kotlin.math.asin(((maxY - headCy) / ry).coerceIn(0.3f, 0.85f).toDouble())).toFloat()
        var phi = front + 4f
        var k = 0
        while (phi < 150f) {
            val r = Math.toRadians(phi.toDouble())
            val tall = 0.55f + 0.45f * kotlin.math.sin(Math.toRadians(((phi - front) / (150f - front) * 180f).toDouble())).toFloat()
            for (col in listOf(-1f, 0f, 1f)) {
                val j1 = hash(k, col.toInt() + 5, 3) - 0.5f; val j3 = hash(k, col.toInt() + 2, 8)
                out.add(spike(0.1f * col + 0.04f * (k % 2), kotlin.math.sin(r).toFloat(), kotlin.math.cos(r).toFloat(),
                    len = (0.026f + 0.024f * tall) * (0.8f + 0.4f * j3) * (if (col == 0f) 1f else 0.8f), ra = 0.008f,
                    up = 0.25f, back = 0.25f, jx = 0.25f * col + 0.2f * j1, jz = 0f))
            }
            phi += 9f; k++
        }
        return out
    }

    private class SpikeHit(var d: Float = 1f, var t: Float = 0f, var idx: Int = -1)

    /** Signed distance to the spikes (union). */
    private fun spikesSdf(x: Float, y: Float, z: Float, hit: SpikeHit? = null): Float {
        var best = 1f
        for ((i, sp) in spikes.withIndex()) {
            val qx = x - sp.mx; val qy = y - sp.my; val qz = z - sp.mz
            if (sqrt(qx * qx + qy * qy + qz * qz) - sp.bound > min(best, 0.01f)) continue
            val d = capsule(x, y, z, sp.ax, sp.ay, sp.az, sp.bx, sp.by, sp.bz, sp.ra, 0.0003f)
            if (d < best) {
                best = d
                if (hit != null) {
                    val ux = sp.bx - sp.ax; val uy = sp.by - sp.ay; val uz = sp.bz - sp.az
                    hit.t = (((x - sp.ax) * ux + (y - sp.ay) * uy + (z - sp.az) * uz) / (ux * ux + uy * uy + uz * uz)).coerceIn(0f, 1f)
                    hit.d = d; hit.idx = i
                }
            }
        }
        return best
    }

    /** Shading on a spike (around 1): dark at the root, lighter towards the tip. Null if not on a spike. */
    fun spikeShade(x: Float, y: Float, z: Float): Float? {
        if (spikes.isEmpty()) return null
        val h = SpikeHit()
        spikesSdf(x, y, z, h)
        if (h.idx < 0 || h.d > 0.003f) return null
        val strands = 0.06f * kotlin.math.sin((x * 1300f + z * 1100f) + h.idx * 2.3f)
        return 0.72f + 0.42f * smoothstep(0f, 0.9f, h.t) + strands
    }

    /** Curtains: thin pointed strands falling from the centre part to frame the face. */
    private fun buildCurtainLocks(): List<Lock> {
        val out = ArrayList<Lock>()
        val tipU = ((maxY - 0.02f - headCy) / ry).coerceIn(0.2f, 0.8f)
        for (side in listOf(-1f, 1f)) {
            for ((k, d) in listOf(30f, 48f, 64f).withIndex()) {
                out.add(lock(dir(0.94f, side * 6f), dir(tipU + 0.06f - 0.1f * k, side * d), w = 0.036f, lift = 0.011f, flick = 0.003f,
                    drop = if (k == 2) 0.045f else 0f))
            }
        }
        return out
    }

    /** Signed distance to the union of locks; fills [hit] with the nearest lock's local coordinates if given. */
    private fun locksSdf(x: Float, y: Float, z: Float, hit: LockHit? = null): Float {
        var best = 1f
        for ((li, lk) in locks.withIndex()) {
            val bx = x - lk.cx; val by = y - lk.cy; val bz = z - lk.cz
            if (sqrt(bx * bx + by * by + bz * bz) - lk.bound > min(best, 0.02f)) continue
            val p = lk.p
            val segs = lk.r.size - 1
            for (i in 0 until segs) {
                val o = 3 * i
                val ex = p[o + 3] - p[o]; val ey = p[o + 4] - p[o + 1]; val ez = p[o + 5] - p[o + 2]
                val vx = x - p[o]; val vy = y - p[o + 1]; val vz = z - p[o + 2]
                val ee = ex * ex + ey * ey + ez * ez
                val t = ((vx * ex + vy * ey + vz * ez) / ee).coerceIn(0f, 1f)
                val qx = vx - t * ex; val qy = vy - t * ey; val qz = vz - t * ez
                val rad = lk.r[i] + (lk.r[i + 1] - lk.r[i]) * t
                if (rad < 1e-4f) continue
                val nx = lk.n[o] + (lk.n[o + 3] - lk.n[o]) * t
                val ny = lk.n[o + 1] + (lk.n[o + 4] - lk.n[o + 1]) * t
                val nz = lk.n[o + 2] + (lk.n[o + 5] - lk.n[o + 2]) * t
                val vr = qx * nx + qy * ny + qz * nz                      // off-scalp component
                val tx = qx - vr * nx; val ty = qy - vr * ny; val tz = qz - vr * nz
                val vt = sqrt(tx * tx + ty * ty + tz * tz)                // across the lock
                val k = 0.36f                                                // thickness / width (flat clumps)
                val d = (sqrt((vt / rad) * (vt / rad) + (vr / (k * rad)) * (vr / (k * rad))) - 1f) * k * rad
                if (d < best) {
                    best = d
                    if (hit != null) {
                        // sign of "across": which side of the lock centre line
                        // across = offset along the lock's binormal only, so strands run parallel to the lock
                        val sx = ey * nz - ez * ny; val sy = ez * nx - ex * nz; val sz = ex * ny - ey * nx
                        val sl = sqrt(sx * sx + sy * sy + sz * sz).coerceAtLeast(1e-9f)
                        hit.d = d; hit.across = (qx * sx + qy * sy + qz * sz) / sl / rad; hit.along = (i + t) / segs; hit.lock = li
                    }
                }
            }
        }
        return best
    }

    /** 0..1: how much this surface point is on a lock (for hair colour). */
    fun lockCover(x: Float, y: Float, z: Float): Float {
        if (locks.isEmpty()) return 0f
        val d = locksSdf(x, y, z)
        return 1f - smoothstep(0.0015f, 0.004f, d)
    }

    /**
     * Shading for painted hair on a lock (around 1): fine strands running along the lock, darker towards its
     * edges so clumps read separately. Null if the point is not on a lock.
     */
    fun lockShade(x: Float, y: Float, z: Float): Float? {
        if (locks.isEmpty()) return null
        val h = LockHit()
        locksSdf(x, y, z, h)
        if (h.lock < 0 || h.d > 0.004f) return null
        val a = h.across.coerceIn(-1f, 1f)
        // Fine strands along the lock, a soft sheen down its middle, darker edges so each clump reads.
        val strands = kotlin.math.sin(a * 37f + h.lock * 1.7f) * 0.6f + kotlin.math.sin(a * 71f + h.lock * 0.9f) * 0.4f
        val sheen = 1f + 0.18f * (1f - a * a) * smoothstep(0.1f, 0.5f, h.along) * (1f - smoothstep(0.75f, 1f, h.along))
        val edge = 1f - 0.45f * smoothstep(0.5f, 1f, abs(a))
        return (0.93f + 0.07f * strands) * sheen * edge
    }

    // ------------------------------------------------------------------ painted hair

    /**
     * Painted hair shading around 1 for the combed styles (side parting, curtains): fine strands following the
     * comb direction, a soft sheen across the crown, darker roots near the hairline and a visible part line.
     * Null for other styles.
     */
    fun paintedHair(x: Float, y: Float, z: Float): Float? {
        if (hairStyle != HairStyle.SIDE_PART && hairStyle != HairStyle.CURTAINS) return null
        val lx = x - headCx; val ly = y - headCy; val lz = z - headCz
        val u = (ly / ry).coerceIn(-1f, 1f)
        val w = (lz / rz).coerceIn(-1.2f, 1.2f)
        val partX = if (hairStyle == HairStyle.SIDE_PART) PART_X * rx else 0f
        // Coordinate that is constant along a strand: on top the hair is combed sideways away from the part,
        // on the sides it falls down and back.
        val top = lz + 0.25f * (lx - partX) * (if (hairStyle == HairStyle.CURTAINS) kotlin.math.sign(lx) * -0.6f else 1f)
        val side = kotlin.math.atan2(lx, lz) * 0.06f + (if (hairStyle == HairStyle.CURTAINS) 0.05f else 0.2f) * ly
        val blendTop = smoothstep(0.1f, 0.55f, u)
        val across = top * blendTop + side * (1f - blendTop)
        val wobble = 0.0006f * kotlin.math.sin(x * 230f + y * 170f + z * 90f)
        val f = (across + wobble) * 1700f
        val strands = 0.5f * kotlin.math.sin(f) + 0.3f * kotlin.math.sin(f * 2.3f + 1.1f) + 0.2f * kotlin.math.sin(f * 5.1f + 0.4f)
        // Clumps: broader light/dark bands so the hair reads as locks, not a flat helmet.
        val clump = kotlin.math.sin((across + wobble) * 260f + 0.7f)
        val sb = (u - 0.72f + 0.1f * w) / 0.12f
        val sheen = 1f + 0.2f * exp(-sb * sb)
        val edge = 0.8f + 0.2f * smoothstep(0f, 0.025f, y - hairlineYPublic(x, z))
        var shade = (0.9f + 0.1f * strands + 0.04f * clump) * sheen * edge
        val g = (lx - partX) / 0.0022f
        // Part line: scalp shows through.
        shade *= 1f - 0.5f * exp(-g * g) * smoothstep(0.4f, 0.75f, u) * smoothstep(-0.5f, 0.2f, w)
        return shade
    }

    private fun hairlineYPublic(x: Float, z: Float) = hairlineY(x, z)

    /** Fills a TSDF volume (bounds chosen to hold head + neck) from [sdf]. */
    fun buildVolume(voxel: Float, truncation: Float): TsdfVolume {
        val pad = when (hairStyle) { HairStyle.BUZZ, HairStyle.NONE -> 0.035f; HairStyle.CURTAINS -> 0.06f; HairStyle.SPIKY, HairStyle.MOHICAN -> 0.085f; else -> 0.05f }
        val x0 = min(headCx - rx, minX) - pad; val x1 = max(headCx + rx, maxX) + pad
        val y0 = neckBottom; val y1 = max(headCy + ry, maxY) + pad
        val z0 = headCz - rz - pad; val z1 = max(noseZ, headCz + rz) + pad
        val nx = ceil((x1 - x0) / voxel).toInt() + 1
        val ny = ceil((y1 - y0) / voxel).toInt() + 1
        val nz = ceil((z1 - z0) / voxel).toInt() + 1
        val vol = TsdfVolume(x0, y0, z0, voxel, nx, ny, nz, truncation)
        java.util.stream.IntStream.range(0, nz).parallel().forEach { kk ->
            val z = vol.pointZ(kk.toFloat())
            for (jj in 0 until ny) {
                val y = vol.pointY(jj.toFloat())
                for (ii in 0 until nx) {
                    val id = vol.index(ii, jj, kk)
                    vol.tsdf[id] = (sdf(vol.pointX(ii.toFloat()), y, z) / truncation).coerceIn(-1f, 1f)
                    vol.weight[id] = 10f
                }
            }
        }
        return vol
    }

}
