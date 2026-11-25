package com.headscan.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Hair added to the generic head in Selfie mode. */
enum class HairStyle { SWEPT, BUZZ, CREW_CUT, SHORT, SIDE_PART, FLAT_TOP, MOHAWK, AFRO, BOB, BUN }

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
) {
    private val verts = faceVertices

    val minX: Float; val maxX: Float; val minY: Float; val maxY: Float; val noseZ: Float
    private val faceW: Float

    // Generic head parameters
    val headCx: Float
    val headCy: Float
    val headCz: Float
    private val rx: Float; private val ry: Float; private val rz: Float
    private val neckR: Float
    private val neckCz: Float
    val neckBottom: Float
    val chinY get() = minY

    // Height field of the face surface (max z per x/y cell) + blend weight toward the face border.
    private val cell = 0.0015f
    private val gw: Int; private val gh: Int
    private val zFace: FloatArray
    private val blend: FloatArray

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

        val topOfHead = maxY + 0.045f * scale
        val jawLine = minY + 0.025f * scale
        // Moving the face by +offset on the head = moving the head by -offset around the face.
        val ox = faceOffset[0]; val oy = faceOffset[1]; val oz = faceOffset[2]
        headCx = -ox
        headCy = (topOfHead + jawLine) / 2 - oy
        ry = (topOfHead - jawLine) / 2
        rx = faceW / 2 * 1.05f
        rz = 0.10f * scale
        headCz = noseZ - 0.025f * scale - rz - oz
        neckR = 0.056f * scale
        neckCz = headCz - 0.012f * scale
        neckBottom = minY - 0.10f * scale - oy

        gw = ceil(faceW / cell).toInt() + 3
        gh = ceil((maxY - minY) / cell).toInt() + 3
        zFace = FloatArray(gw * gh) { Float.NaN }
        rasterizeFace()
        blend = FloatArray(gw * gh)
        computeBlend(borderCells = (0.012f / cell).toInt())
    }

    private fun rasterizeFace() {
        for (t in 0 until faceTriangles.size / 3) {
            val a = faceTriangles[3 * t]; val b = faceTriangles[3 * t + 1]; val c = faceTriangles[3 * t + 2]
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
    }

    private fun gx(x: Float) = (x - minX) / cell + 1f
    private fun gy(y: Float) = (y - minY) / cell + 1f

    private fun smin(a: Float, b: Float, k: Float): Float {
        // exponential smooth minimum
        return -ln(exp(-a / k) + exp(-b / k)) * k
    }

    /** Approximate signed distance of the generic head + neck (negative inside). */
    fun genericSdf(x: Float, y: Float, z: Float): Float {
        val ex = (x - headCx) / rx; val ey = (y - headCy) / ry; val ez = (z - headCz) / rz
        val head = (sqrt(ex * ex + ey * ey + ez * ez) - 1f) * min(rx, min(ry, rz))
        val nr = sqrt((x - headCx) * (x - headCx) + (z - neckCz) * (z - neckCz)) - neckR
        val neck = max(nr, max(y - headCy, neckBottom - 0.02f - y))
        return smin(head, neck, 0.012f)
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun smax(a: Float, b: Float, k: Float) = -smin(-a, -b, k)

    /** 0 outside the real face, rising smoothly to 1 a little inside its border. */
    fun faceMask(x: Float, y: Float, z: Float): Float {
        val fi = gx(x); val fj = gy(y)
        val i = fi.toInt(); val j = fj.toInt()
        if (fi < 0 || fj < 0 || i >= gw || j >= gh) return 0f
        val k = j * gw + i
        val zf = zFace[k]
        if (zf.isNaN() || z < zf - 0.03f) return 0f
        return smoothstep(0f, 1f, blend[k])
    }

    /** Height of the hairline around the head: forehead in front, sideburns at the sides, nape at the back. */
    private fun hairlineY(x: Float, z: Float): Float {
        val w = (z - headCz) / rz
        val s = abs(x - headCx) / rx
        val forehead = maxY - 0.008f + 0.008f * smoothstep(0.35f, 0.85f, s)   // slight temple recession
        val side = headCy - 0.03f
        val nape = minY + 0.04f
        return if (w < 0f) nape + (side - nape) * smoothstep(-1f, 0f, w)
        else side + (forehead - side) * smoothstep(0f, 0.55f, w)
    }

    /** 0..1: how much of the scalp at this point is covered by hair (soft hairline, never on the face). */
    private fun scalpCoverage(x: Float, y: Float, z: Float): Float =
        smoothstep(-0.004f, 0.004f, y - hairlineY(x, z)) * (1f - faceMask(x, y, z))

    /** Hair thickness grown out from the scalp for the chosen style (metres). */
    fun hairThickness(x: Float, y: Float, z: Float): Float {
        if (hairStyle == HairStyle.BUZZ) return 0f
        val cov = scalpCoverage(x, y, z)
        if (cov <= 0f) return 0f
        val u = (y - headCy) / ry
        val w = (z - headCz) / rz
        val lx = x - headCx
        val t = when (hairStyle) {
            HairStyle.CREW_CUT -> 0.005f + 0.003f * smoothstep(0.4f, 0.9f, u)
            HairStyle.SHORT -> {
                // Natural short cut: a little fuller on top, slight forward fringe, tidy at the back.
                0.007f + 0.006f * smoothstep(0.2f, 0.8f, u) + 0.004f * smoothstep(0.1f, 0.6f, w) * smoothstep(0.4f, 0.8f, u)
            }
            HairStyle.MOHAWK -> {
                // Tall strip from forehead to nape, sides shaved.
                val g = lx / 0.014f
                val crest = exp(-g * g * g * g)        // flat-topped strip
                0.0015f + 0.04f * crest * smoothstep(-0.2f, 0.45f, u) * smoothstep(-0.95f, -0.4f, w) *
                    (1f - 0.75f * smoothstep(0.2f, 0.75f, w))     // lower at the front so it doesn't overhang
            }
            HairStyle.AFRO -> {
                // Big rounded volume, fullest at the top and sides.
                (0.03f + 0.022f * smoothstep(-0.3f, 0.6f, u)) * (1f - 0.65f * smoothstep(0.25f, 0.8f, w))
            }
            HairStyle.SIDE_PART -> {
                val top = 0.006f + 0.010f * smoothstep(0.15f, 0.8f, u)
                val quiff = 0.005f * smoothstep(0.2f, 0.7f, w) * smoothstep(0.45f, 0.9f, u)
                val g = (lx - 0.32f * rx) / 0.006f
                val groove = 0.006f * exp(-g * g) * smoothstep(0.35f, 0.7f, u) * smoothstep(-0.4f, 0.1f, w)
                top + quiff - groove
            }
            HairStyle.FLAT_TOP -> {
                // Tall on top but kept inside the skull outline, so the sides stay upright (no brim).
                val radial = sqrt(lx * lx / (rx * rx) + w * w)
                0.005f + 0.024f * smoothstep(0.45f, 0.8f, u) * (1f - smoothstep(0.5f, 0.85f, radial))
            }
            HairStyle.BOB -> 0.009f + 0.008f * smoothstep(0.3f, -0.5f, u)
            HairStyle.BUN -> 0.005f
            HairStyle.SWEPT -> 0.006f + 0.010f * smoothstep(0.2f, 0.9f, u)   // under-layer beneath the locks
            HairStyle.BUZZ -> 0f
        }
        return t * cov
    }

    /** Extra hair shapes that stick out beyond the scalp (bob curtain, bun). 1 = none. */
    private fun extraHairSdf(x: Float, y: Float, z: Float): Float {
        val lx = x - headCx; val ly = y - headCy; val lz = z - headCz
        return when (hairStyle) {
            HairStyle.BOB -> {
                // Chin-length curtain around the back and sides, tapering in at the bottom.
                val bottom = minY + 0.01f
                val taper = 1f - 0.12f * smoothstep(headCy - 0.02f, bottom, y)
                val ex = lx / ((rx + 0.017f) * taper); val ez = (lz + 0.005f) / ((rz + 0.014f) * taper)
                val tube = (sqrt(ex * ex + ez * ez) - 1f) * rx
                val front = lz - rz * 0.18f
                val shell = smax(smax(tube, front, 0.01f), smax(bottom - y, ly, 0.01f), 0.008f)
                max(shell, faceMask(x, y, z) * 0.02f - 0.001f)
            }
            HairStyle.BUN -> {
                val bx = lx; val by = ly - 0.35f * ry; val bz = lz + rz + 0.008f
                sqrt(bx * bx + by * by + bz * bz) - 0.03f
            }
            else -> 1f
        }
    }

    /** 0..1 hair colour amount for texturing (scalp coverage, or 1 on bob / bun shapes). */
    fun hairColorAmount(x: Float, y: Float, z: Float): Float {
        if (locks.isNotEmpty()) {
            val on = lockCover(x, y, z)
            if (on > 0f) return max(on, scalpCoverage(x, y, z))
        }
        if (extraHairSdf(x, y, z) < 0.004f && genericSdf(x, y, z) > 0.003f) return 1f - faceMask(x, y, z)
        val c = scalpCoverage(x, y, z)
        return when (hairStyle) {
            HairStyle.BUZZ -> c * 0.6f
            HairStyle.MOHAWK -> {
                // Full colour on the crest, light stubble on the shaved sides.
                val g = (x - headCx) / 0.016f
                c * (0.35f + 0.65f * exp(-g * g * g * g))
            }
            else -> c
        }
    }

    /** Head + neck + hair, before the face is merged in. */
    private fun headWithHair(x: Float, y: Float, z: Float): Float {
        var g = genericSdf(x, y, z) - hairThickness(x, y, z)
        if (hairStyle == HairStyle.FLAT_TOP) g = max(g, y - (headCy + ry + 0.016f))
        val extra = extraHairSdf(x, y, z)
        if (extra < 0.05f) g = smin(g, extra, 0.006f)
        return g
    }

    /** Signed distance of the personalised head: generic head with the real face surface merged in front. */
    fun sdf(x: Float, y: Float, z: Float): Float {
        val base = headAndFace(x, y, z)
        if (locks.isEmpty()) return base
        // Locks lie on top of everything, so a fringe can fall over the upper forehead.
        return smin(base, locksSdf(x, y, z), 0.0025f)
    }

    private fun headAndFace(x: Float, y: Float, z: Float): Float {
        val g = headWithHair(x, y, z)
        val fi = gx(x); val fj = gy(y)
        val i = fi.toInt(); val j = fj.toInt()
        if (fi < 0 || fj < 0 || i >= gw || j >= gh) return g
        val k = j * gw + i
        val zf = zFace[k]
        if (zf.isNaN()) return g
        val w = blend[k]
        val fz = (z - zf) * 0.85f
        // The face may only add material close to the generic head, so steep mesh borders can't form flaps.
        val faceSlab = max(max(fz, (zf - 0.07f) - z), g - 0.03f)
        val union = min(g, faceSlab)
        return if (z > zf) w * fz + (1 - w) * union else union
    }


    // ------------------------------------------------------------------ sculpted locks (SWEPT)

    /** One clump of hair: a flattened, tapering tube along a curve over the scalp, ending in a point. */
    private class Lock(val p: FloatArray, val r: FloatArray, val n: FloatArray, val cx: Float, val cy: Float, val cz: Float, val bound: Float)

    private class LockHit(var d: Float = 1f, var across: Float = 0f, var along: Float = 0f, var lock: Int = -1)

    private val locks: List<Lock> by lazy { if (hairStyle == HairStyle.SWEPT) buildSweptLocks() else emptyList() }

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
    private fun lock(a: FloatArray, b: FloatArray, w: Float, lift: Float = 0.007f, flick: Float = 0.004f): Lock {
        val steps = 9
        val p = FloatArray(steps * 3); val n = FloatArray(steps * 3); val r = FloatArray(steps)
        val al = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]); val bl = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        val ax = a[0] / al; val ay = a[1] / al; val az = a[2] / al
        val bx = b[0] / bl; val by = b[1] / bl; val bz = b[2] / bl
        val om = kotlin.math.acos((ax * bx + ay * by + az * bz).coerceIn(-1f, 1f)).coerceAtLeast(1e-4f)
        for (i in 0 until steps) {
            val t = i / (steps - 1f)
            val s0 = kotlin.math.sin((1 - t) * om) / kotlin.math.sin(om); val s1 = kotlin.math.sin(t * om) / kotlin.math.sin(om)
            val h = lift + 0.004f * kotlin.math.sin(Math.PI.toFloat() * t) + flick * t * t * t
            scalpPoint(s0 * ax + s1 * bx, s0 * ay + s1 * by, s0 * az + s1 * bz, h, p, 3 * i, n)
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

    /**
     * Game-style swept cut: chunky clumps radiate from a crown whorl at the back of the head; the front ones
     * sweep forward and across into a pointed side fringe, the side ones comb down over the ears and the back
     * ones fall to the nape.
     */
    private fun buildSweptLocks(): List<Lock> {
        val out = ArrayList<Lock>()
        val crown = dir(0.72f, 160f)              // whorl: high at the back, a little to +X
        // Lowest a fringe tip may reach: above the brows.
        val tipU = ((maxY - 0.03f - headCy) / ry).coerceIn(0.2f, 0.8f)
        val fringeU = ((maxY - 0.005f - headCy) / ry).coerceIn(0.3f, 0.9f)
        // Fringe: big clumps sweeping forward and across to -X, pointed tips hanging over the forehead
        // at staggered lengths, the outermost flicking out past the temple.
        // Each starts high on the right of the head and ends lower and further left, so the fringe reads
        // as one diagonal swoop rather than straight bangs.
        val fringe = listOf(
            floatArrayOf(60f, 10f, fringeU + 0.06f, 0.5f), floatArrayOf(40f, -12f, tipU + 0.03f, 1f),
            floatArrayOf(18f, -35f, tipU - 0.02f, 1.2f), floatArrayOf(-5f, -58f, tipU + 0.01f, 1.4f),
            floatArrayOf(-28f, -82f, tipU - 0.14f, 2f),
        )
        for ((k, f) in fringe.withIndex()) {
            val start = dir(0.9f - 0.03f * (k % 2), f[0] + 60f)
            out.add(lock(start, dir(f[2], f[1]), w = 0.055f, lift = 0.013f, flick = 0.006f * f[3]))
        }
        // Top: big clumps from the crown arching forward, giving the cut its volume.
        for ((k, deg) in listOf(60f, 5f, -50f).withIndex()) {
            out.add(lock(crown, dir(0.66f - 0.05f * (k % 2), deg), w = 0.066f, lift = 0.019f, flick = 0.003f))
        }
        // Sides: combed down and back over the ears, pointed tips.
        for (side in listOf(-1f, 1f)) for ((k, a) in listOf(65f, 95f, 125f).withIndex()) {
            val start = dir(0.72f, side * (a - 15f))
            out.add(lock(start, dir(-0.22f - 0.08f * (k % 2), side * (a + 20f)), w = 0.058f, lift = 0.009f, flick = 0.008f))
        }
        // Back: falling from the whorl to the nape.
        for ((k, a) in listOf(-155f, 180f, 155f, 130f, -130f, 105f, -105f).withIndex()) {
            out.add(lock(crown, dir(-0.48f - 0.08f * (k % 2), a), w = 0.062f, lift = 0.009f, flick = 0.006f))
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

    /** Fills a TSDF volume (bounds chosen to hold head + neck) from [sdf]. */
    fun buildVolume(voxel: Float, truncation: Float): TsdfVolume {
        val pad = when (hairStyle) { HairStyle.BUZZ -> 0.02f; HairStyle.SWEPT -> 0.06f; HairStyle.AFRO, HairStyle.MOHAWK -> 0.075f; else -> 0.05f }
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
