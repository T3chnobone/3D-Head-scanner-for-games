package com.headscan.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Hair added to the generic head in Selfie mode. */
enum class HairStyle { BUZZ, SHORT, LONG }

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

    /** Height of the hairline: at the forehead in front, sloping down to the nape at the back. */
    private fun hairlineY(z: Float): Float {
        val front = headCz + rz * 0.55f
        val back = headCz - rz
        val t = ((z - back) / (front - back)).coerceIn(0f, 1f)
        val nape = minY + 0.035f
        val forehead = maxY - 0.012f
        return nape + (forehead - nape) * t * t
    }

    /** Signed distance of the hair volume for the chosen style (large positive for BUZZ = no extra volume). */
    fun hairSdf(x: Float, y: Float, z: Float): Float {
        if (hairStyle == HairStyle.BUZZ) return 1f
        val thick = if (hairStyle == HairStyle.SHORT) 0.014f else 0.02f
        // Cap: the cranium grown outward, kept above the hairline and behind the forehead.
        val ex = (x - headCx) / (rx + thick); val ey = (y - headCy) / (ry + thick * 1.6f); val ez = (z - headCz) / (rz + thick)
        val shell = (sqrt(ex * ex + ey * ey + ez * ez) - 1f) * min(rx, min(ry, rz))
        val faceCut = z - (headCz + rz * 0.62f)           // never in front of the forehead
        var cap = max(shell, max(hairlineY(z) - y, faceCut))
        if (hairStyle == HairStyle.LONG) {
            // Curtain: falls behind and beside the face to below the jaw, with a rounded bottom.
            val cx2 = (x - headCx) / (rx + 0.014f); val cz2 = (z - (headCz - 0.008f)) / (rz + 0.012f)
            val tube = (sqrt(cx2 * cx2 + cz2 * cz2) - 1f) * rx
            val bottom = minY - 0.045f
            val vr = (headCy - bottom)
            val ey2 = (y - headCy) / vr; val ex2 = (x - headCx) / (rx + 0.03f)
            val rounded = (sqrt(ey2 * ey2 + ex2 * ex2) - 1f) * rx
            val curtain = max(max(tube, rounded), max(y - headCy, z - (headCz + rz * 0.15f)))
            cap = smin(cap, curtain, 0.015f)
        }
        return cap
    }

    /** Signed distance of the personalised head: generic head with the real face surface merged in front. */
    fun sdf(x: Float, y: Float, z: Float): Float {
        val g = min(genericSdf(x, y, z), hairSdf(x, y, z))
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

    /** Fills a TSDF volume (bounds chosen to hold head + neck) from [sdf]. */
    fun buildVolume(voxel: Float, truncation: Float): TsdfVolume {
        val pad = if (hairStyle == HairStyle.BUZZ) 0.02f else 0.045f
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

    val faceMoved = faceOffset.any { abs(it) > 0.002f }

    /** True on (or just around) the real face surface. */
    fun isFaceArea(x: Float, y: Float, z: Float): Boolean {
        val fi = gx(x); val fj = gy(y)
        val i = fi.toInt(); val j = fj.toInt()
        if (fi < 0 || fj < 0 || i >= gw || j >= gh) return false
        val zf = zFace[j * gw + i]
        return !zf.isNaN() && z > zf - 0.025f
    }

    /** True on hair geometry added by the hairstyle (outside the bald head). */
    fun isAddedHair(x: Float, y: Float, z: Float): Boolean =
        hairStyle != HairStyle.BUZZ && hairSdf(x, y, z) < 0.004f && genericSdf(x, y, z) > 0.002f

    /** True where unseen texels should get hair colour (scalp and back of head) rather than skin colour. */
    fun isHairRegion(x: Float, y: Float, z: Float): Boolean {
        if (y > maxY - 0.01f) return true
        return z < headCz - 0.02f && y > minY + 0.05f
    }
}
