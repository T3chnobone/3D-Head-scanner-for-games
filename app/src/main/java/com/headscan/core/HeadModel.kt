package com.headscan.core

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A statistical head model (3D morphable model): an average human head and neck plus [k] shape controls learned from
 * real head scans. Any setting of the controls gives a plausible whole head, so fitting the controls to the scanned
 * face also predicts the rest of the head (skull, jaw, ears, neck) in proportion to it.
 *
 * The data is the ICT Face Model (USC Institute for Creative Technologies, MIT licence): the face and the
 * head-and-neck parts, closed at the eyes, mouth and neck base so the surface is watertight. Metres, +Y up, +Z out
 * of the face. See tools/build_ict_asset.py for how the asset is made.
 */
class HeadModel private constructor(
    val n: Int,
    val k: Int,
    private val neutral: FloatArray,
    private val modes: FloatArray,
    val tris: IntArray,
    val flags: ByteArray,
) {
    companion object {
        const val FACE = 1; const val EAR = 2; const val CAP = 8

        /** The model the app loaded at start-up (from assets/ict_head.bin); null = use the simple generic head. */
        @Volatile var default: HeadModel? = null

        fun load(input: InputStream): HeadModel {
            val b = ByteBuffer.wrap(input.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { b.get(it) }
            require(String(magic) == "ICTH") { "not a head model file" }
            b.int  // version
            val n = b.int; val nt = b.int; val k = b.int
            val neutral = FloatArray(n * 3) { b.float }
            val sc = FloatArray(k) { b.float }
            val modes = FloatArray(k * n * 3)
            for (m in 0 until k) for (i in 0 until n * 3) modes[m * n * 3 + i] = b.short * sc[m]
            val tris = IntArray(nt * 3) { b.int }
            val flags = ByteArray(n).also { b.get(it) }
            return HeadModel(n, k, neutral, modes, tris, flags)
        }
    }

    private fun has(i: Int, f: Int) = (flags[i].toInt() and f) != 0

    /** Model-space shape for the controls [c] (missing controls are 0). */
    fun shape(c: FloatArray): FloatArray {
        val out = neutral.copyOf()
        for (m in 0 until min(k, c.size)) {
            val w = c[m]; if (w == 0f) continue
            val o = m * n * 3
            for (i in 0 until n * 3) out[i] += w * modes[o + i]
        }
        return out
    }

    // Landmarks on the average head (indices stay meaningful for every shape).
    private val noseTip: Int = (0 until n).filter { has(it, FACE) && !has(it, CAP) }.maxBy { neutral[3 * it + 2] }
    private val chin: Int = (0 until n).filter { has(it, FACE) && !has(it, CAP) && abs(neutral[3 * it]) < 0.01f && neutral[3 * it + 2] > 0.03f }
        .minBy { neutral[3 * it + 1] }
    private val skull = (0 until n).filter { !has(it, FACE) && !has(it, EAR) && !has(it, CAP) }
    private val topV: Int = skull.maxBy { neutral[3 * it + 1] }
    private val backV: Int = skull.filter { neutral[3 * it + 1] > 0f }.minBy { neutral[3 * it + 2] }
    private val sideR: Int = skull.filter { neutral[3 * it + 1] > 0.03f }.maxBy { neutral[3 * it] }
    private val sideL: Int = skull.filter { neutral[3 * it + 1] > 0.03f }.minBy { neutral[3 * it] }
    /** Model-space height below which the neck starts to flare into the shoulders. */
    val neckCutY = -0.095f
    /** Model-space height of the flat cut at the base of the neck: a slight flare, like a game head. */
    val neckBaseY = -0.115f

    /** Measurements of the skull the fit should match (face frame, metres): from [HeadFit]'s photo outline. */
    class Targets(val cx: Float, val halfWidth: Float, val top: Float, val back: Float)

    /** A head fitted to a face: vertex positions in the face frame. */
    class Fitted(val verts: FloatArray, val coeffs: FloatArray, val scale: Float, val rmsMm: Float, val used: Int,
                 val neckCutY: Float, val neckBaseY: Float)

    /**
     * Fits the model to the scanned face: shape controls, size, position and a small rotation are adjusted so the
     * face part of the model lies on the scanned face surface ([face] gives its depth at x, y: out[0] = z,
     * out[1] = confidence 0..1, out[2], out[3] = dz/dx, dz/dy; false where there is no face). [nose] is the
     * scanned nose tip and [scale0] the starting size. [targets] add the skull size measured from the photos.
     */
    fun fit(face: (Float, Float, FloatArray) -> Boolean, nose: FloatArray, scale0: Float, targets: Targets? = null,
            controls: Int = min(k, 40), iterations: Int = 30, damping: Double = 0.5, start: FloatArray? = null): Fitted {
        val kk = controls
        val np = kk + 7                       // controls, scale, translation (3), rotation (3)
        val c = FloatArray(kk)
        start?.let { for (m in 0 until min(kk, it.size)) c[m] = it[m] }
        var s = scale0
        val r = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val t = FloatArray(3)
        var shp = shape(c)
        fun rot(i: Int, out: FloatArray) {
            val x = shp[3 * i]; val y = shp[3 * i + 1]; val z = shp[3 * i + 2]
            out[0] = r[0] * x + r[1] * y + r[2] * z; out[1] = r[3] * x + r[4] * y + r[5] * z; out[2] = r[6] * x + r[7] * y + r[8] * z
        }
        val q = FloatArray(3)
        rot(noseTip, q)
        if (start == null) for (a in 0..2) t[a] = nose[a] - s * q[a]

        val faceIdx = (0 until n).filter { has(it, FACE) && !has(it, CAP) && !has(it, EAR) }.filterIndexed { j, _ -> j % 2 == 0 }.toIntArray()
        val ata = DoubleArray(np * np); val atb = DoubleArray(np)
        val row = DoubleArray(np)
        val fs = FloatArray(4)
        val bm = FloatArray(3)
        var rms = 0f; var used = 0
        var lastMad = 0.004f
        fun add(res: Double, wgt: Double) {
            for (a in 0 until np) {
                val ra = row[a]; if (ra == 0.0) continue
                atb[a] -= wgt * ra * res
                for (b2 in a until np) ata[a * np + b2] += wgt * ra * row[b2]
            }
        }
        // Many neighbouring points don't make independent measurements: the data counts as about 400 of them.
        val sigma = 0.0015
        for (it in 0 until iterations) {
            java.util.Arrays.fill(ata, 0.0); java.util.Arrays.fill(atb, 0.0)
            var sum = 0.0; var cnt = 0
            val pts = ArrayList<FloatArray>()
            val vn = vertexNormals(shp)
            // Outliers (parts of the model hidden in the scan, e.g. under the nose) are dropped: the cut-off
            // tightens to 3 robust standard deviations once the fit has settled.
            val cut = if (it < 3) 0.03f else max(0.003f, min(0.012f, 3f * 1.4826f * lastMad))
            for (i in faceIdx) {
                // Only parts of the face that look forward: undersides (nose, lips) are hidden behind other parts
                // in the face scan's height field.
                if (vn[3 * i + 2] < 0.4f) continue
                rot(i, q)
                val px = s * q[0] + t[0]; val py = s * q[1] + t[1]; val pz = s * q[2] + t[2]
                if (!face(px, py, fs) || fs[1] < 0.25f) continue
                var nx = -fs[2]; var ny = -fs[3]; var nz = 1f
                val nl = sqrt(nx * nx + ny * ny + nz * nz); nx /= nl; ny /= nl; nz /= nl
                val res = nx * 0f + ny * 0f + nz * (pz - fs[0])
                if (abs(res) > cut) continue
                pts.add(floatArrayOf(i.toFloat(), nx, ny, nz, res, fs[1]))
                sum += res.toDouble() * res; cnt++
            }
            if (cnt < 50) break
            lastMad = pts.map { abs(it[4]) }.sorted()[pts.size / 2]
            rms = sqrt(sum / cnt).toFloat(); used = cnt
            val wData = 1.0 / (sigma * sigma) * 400.0 / cnt
            for (p in pts) {
                val i = p[0].toInt(); val nx = p[1]; val ny = p[2]; val nz = p[3]
                rot(i, q)
                java.util.Arrays.fill(row, 0.0)
                for (m in 0 until kk) {
                    val o = m * n * 3 + 3 * i
                    val bx = modes[o]; val by = modes[o + 1]; val bz = modes[o + 2]
                    bm[0] = r[0] * bx + r[1] * by + r[2] * bz; bm[1] = r[3] * bx + r[4] * by + r[5] * bz; bm[2] = r[6] * bx + r[7] * by + r[8] * bz
                    row[m] = (s * (nx * bm[0] + ny * bm[1] + nz * bm[2])).toDouble()
                }
                row[kk] = (nx * q[0] + ny * q[1] + nz * q[2]).toDouble()
                row[kk + 1] = nx.toDouble(); row[kk + 2] = ny.toDouble(); row[kk + 3] = nz.toDouble()
                // d/dω of n·(ω × v) = v × n, v = s·q
                val vx = s * q[0]; val vy = s * q[1]; val vz = s * q[2]
                row[kk + 4] = (vy * nz - vz * ny).toDouble(); row[kk + 5] = (vz * nx - vx * nz).toDouble(); row[kk + 6] = (vx * ny - vy * nx).toDouble()
                add(p[4].toDouble(), wData * p[5])
            }
            // Skull measured from the photos (3 mm tolerance).
            if (targets != null) {
                val wT = 1.0 / (0.003 * 0.003)
                fun target(v: Int, axis: Int, goal: Float) {
                    rot(v, q)
                    val cur = s * q[axis] + t[axis]
                    java.util.Arrays.fill(row, 0.0)
                    for (m in 0 until kk) {
                        val o = m * n * 3 + 3 * v
                        row[m] = (s * (r[axis * 3] * modes[o] + r[axis * 3 + 1] * modes[o + 1] + r[axis * 3 + 2] * modes[o + 2])).toDouble()
                    }
                    row[kk] = q[axis].toDouble(); row[kk + 1 + axis] = 1.0
                    val vx = s * q[0]; val vy = s * q[1]; val vz = s * q[2]
                    // d(ω × v)[axis]/dω
                    when (axis) {
                        0 -> { row[kk + 5] = vz.toDouble(); row[kk + 6] = -vy.toDouble() }
                        1 -> { row[kk + 4] = -vz.toDouble(); row[kk + 6] = vx.toDouble() }
                        else -> { row[kk + 4] = vy.toDouble(); row[kk + 5] = -vx.toDouble() }
                    }
                    add((cur - goal).toDouble(), wT)
                }
                target(sideR, 0, targets.cx + targets.halfWidth)
                target(sideL, 0, targets.cx - targets.halfWidth)
                target(topV, 1, targets.top)
                target(backV, 2, targets.back)
            }
            // Priors: controls are unit-variance; size within about ±12 %; rotation within a few degrees.
            for (m in 0 until kk) { ata[m * np + m] += 1.0; atb[m] -= c[m].toDouble() }
            val ws = 1.0 / (0.12 * scale0 * 0.12 * scale0)
            ata[kk * np + kk] += ws; atb[kk] -= ws * (s - scale0)
            for (a in 0..2) { val wr = 1.0 / (0.08 * 0.08); ata[(kk + 4 + a) * np + kk + 4 + a] += wr }
            for (a in 0 until np) for (b2 in 0 until a) ata[a * np + b2] = ata[b2 * np + a]
            // Levenberg damping: the correspondences change as the head moves, so full steps overshoot.
            for (a in 0 until np) ata[a * np + a] *= 1.0 + damping
            val d = solve(ata, atb, np) ?: break
            for (m in 0 until kk) c[m] = (c[m] + d[m].toFloat()).coerceIn(-3f, 3f)
            s = (s + d[kk].toFloat()).coerceIn(scale0 * 0.7f, scale0 * 1.4f)
            for (a in 0..2) t[a] += d[kk + 1 + a].toFloat()
            // Rotate by the small angle ω on top of the current rotation (and re-orthonormalise).
            val wx = d[kk + 4].toFloat(); val wy = d[kk + 5].toFloat(); val wz = d[kk + 6].toFloat()
            val dr = rotation(wx, wy, wz)
            val nr = FloatArray(9)
            for (a in 0..2) for (b2 in 0..2) nr[a * 3 + b2] = dr[a * 3] * r[b2] + dr[a * 3 + 1] * r[3 + b2] + dr[a * 3 + 2] * r[6 + b2]
            System.arraycopy(nr, 0, r, 0, 9)
            shp = shape(c)
        }
        val out = FloatArray(n * 3)
        for (i in 0 until n) { rot(i, q); for (a in 0..2) out[3 * i + a] = s * q[a] + t[a] }
        return Fitted(out, c, s, rms * 1000f, used, s * neckCutY + t[1], s * neckBaseY + t[1])
    }

    /** Unit vertex normals (area-weighted) of a shape. */
    fun vertexNormals(v: FloatArray): FloatArray {
        val nrm = FloatArray(n * 3)
        for (t in 0 until tris.size / 3) {
            val a = tris[3 * t]; val b = tris[3 * t + 1]; val c = tris[3 * t + 2]
            val ux = v[3 * b] - v[3 * a]; val uy = v[3 * b + 1] - v[3 * a + 1]; val uz = v[3 * b + 2] - v[3 * a + 2]
            val wx = v[3 * c] - v[3 * a]; val wy = v[3 * c + 1] - v[3 * a + 1]; val wz = v[3 * c + 2] - v[3 * a + 2]
            val nx = uy * wz - uz * wy; val ny = uz * wx - ux * wz; val nz = ux * wy - uy * wx
            for (i in intArrayOf(a, b, c)) { nrm[3 * i] += nx; nrm[3 * i + 1] += ny; nrm[3 * i + 2] += nz }
        }
        for (i in 0 until n) {
            val l = sqrt(nrm[3 * i] * nrm[3 * i] + nrm[3 * i + 1] * nrm[3 * i + 1] + nrm[3 * i + 2] * nrm[3 * i + 2]).coerceAtLeast(1e-12f)
            nrm[3 * i] /= l; nrm[3 * i + 1] /= l; nrm[3 * i + 2] /= l
        }
        return nrm
    }

    private fun rotation(wx: Float, wy: Float, wz: Float): FloatArray {
        val a = sqrt(wx * wx + wy * wy + wz * wz)
        if (a < 1e-9f) return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val x = wx / a; val y = wy / a; val z = wz / a
        val co = cos(a); val si = sin(a); val oc = 1 - co
        return floatArrayOf(
            co + x * x * oc, x * y * oc - z * si, x * z * oc + y * si,
            y * x * oc + z * si, co + y * y * oc, y * z * oc - x * si,
            z * x * oc - y * si, z * y * oc + x * si, co + z * z * oc,
        )
    }

    private fun solve(a0: DoubleArray, b0: DoubleArray, n: Int): DoubleArray? {
        val a = a0.copyOf(); val b = b0.copyOf()
        for (col in 0 until n) {
            var p = col
            for (rr in col + 1 until n) if (abs(a[rr * n + col]) > abs(a[p * n + col])) p = rr
            if (abs(a[p * n + col]) < 1e-12) return null
            if (p != col) {
                for (cc in 0 until n) { val tmp = a[col * n + cc]; a[col * n + cc] = a[p * n + cc]; a[p * n + cc] = tmp }
                val tb = b[col]; b[col] = b[p]; b[p] = tb
            }
            for (rr in col + 1 until n) {
                val f = a[rr * n + col] / a[col * n + col]
                if (f == 0.0) continue
                for (cc in col until n) a[rr * n + cc] -= f * a[col * n + cc]
                b[rr] -= f * b[col]
            }
        }
        val x = DoubleArray(n)
        for (rr in n - 1 downTo 0) {
            var sum = b[rr]
            for (cc in rr + 1 until n) sum -= a[rr * n + cc] * x[cc]
            x[rr] = sum / a[rr * n + rr]
        }
        return x
    }

    fun isEar(i: Int) = has(i, EAR)

    /**
     * The outer ear itself (rim, bowl and the lobe), from the average head: what lies inside the ear's outline seen from
     * the side and out at the side of the head, but not the cheek skin in front of the ear that the [EAR] marking also
     * takes in. Fits the shape of the fitted head too, since identity changes move these points with the ear.
     */
    private val pinnaMask: BooleanArray by lazy {
        val v = shape(FloatArray(k))
        BooleanArray(n) { i ->
            if (has(i, CAP)) return@BooleanArray false
            val ax = kotlin.math.abs(v[3 * i]); val y = v[3 * i + 1]; val z = v[3 * i + 2]
            val ez = (z + 0.001f) / 0.0175f; val ey = (y - 0.01f) / 0.031f
            ax > 0.06f && ez * ez + ey * ey < 1f && z < 0.013f
        }
    }
    fun isPinna(i: Int) = pinnaMask[i]
    fun isFace(i: Int) = has(i, FACE)
    fun isCap(i: Int) = has(i, CAP)
}

/**
 * Signed distance to a closed triangle mesh, sampled on a grid (negative inside). Distances are exact next to the
 * surface and approximate further out; inside/outside comes from counting surface crossings along Z.
 */
class MeshSdf(verts: FloatArray, tris: IntArray, val voxel: Float, pad: Float, minY: Float = -Float.MAX_VALUE) {
    val x0: Float; val y0: Float; val z0: Float
    val nx: Int; val ny: Int; val nz: Int
    private val d: FloatArray

    init {
        var ax = Float.MAX_VALUE; var ay = Float.MAX_VALUE; var az = Float.MAX_VALUE
        var bx = -Float.MAX_VALUE; var by = -Float.MAX_VALUE; var bz = -Float.MAX_VALUE
        for (i in 0 until verts.size / 3) {
            if (verts[3 * i + 1] < minY) continue
            ax = min(ax, verts[3 * i]); bx = max(bx, verts[3 * i])
            ay = min(ay, verts[3 * i + 1]); by = max(by, verts[3 * i + 1])
            az = min(az, verts[3 * i + 2]); bz = max(bz, verts[3 * i + 2])
        }
        x0 = ax - pad; y0 = ay - pad; z0 = az - pad
        nx = ceil((bx + pad - x0) / voxel).toInt() + 1
        ny = ceil((by + pad - y0) / voxel).toInt() + 1
        nz = ceil((bz + pad - z0) / voxel).toInt() + 1
        val cells = nx * ny * nz
        d = FloatArray(cells) { Float.MAX_VALUE }
        val near = IntArray(cells) { -1 }
        val nt = tris.size / 3
        val cp = FloatArray(3)
        // Exact distance in a thin band round every triangle.
        for (t in 0 until nt) {
            val a = tris[3 * t]; val b = tris[3 * t + 1]; val c = tris[3 * t + 2]
            val lx = min(verts[3 * a], min(verts[3 * b], verts[3 * c])); val hx = max(verts[3 * a], max(verts[3 * b], verts[3 * c]))
            val ly = min(verts[3 * a + 1], min(verts[3 * b + 1], verts[3 * c + 1])); val hy = max(verts[3 * a + 1], max(verts[3 * b + 1], verts[3 * c + 1]))
            val lz = min(verts[3 * a + 2], min(verts[3 * b + 2], verts[3 * c + 2])); val hz = max(verts[3 * a + 2], max(verts[3 * b + 2], verts[3 * c + 2]))
            val i0 = max(0, ((lx - x0) / voxel - 1.5f).toInt()); val i1 = min(nx - 1, ((hx - x0) / voxel + 2.5f).toInt())
            val j0 = max(0, ((ly - y0) / voxel - 1.5f).toInt()); val j1 = min(ny - 1, ((hy - y0) / voxel + 2.5f).toInt())
            val k0 = max(0, ((lz - z0) / voxel - 1.5f).toInt()); val k1 = min(nz - 1, ((hz - z0) / voxel + 2.5f).toInt())
            for (kk in k0..k1) for (jj in j0..j1) for (ii in i0..i1) {
                val id = (kk * ny + jj) * nx + ii
                val dist = triDist(verts, a, b, c, x0 + ii * voxel, y0 + jj * voxel, z0 + kk * voxel, cp)
                if (dist < d[id]) { d[id] = dist; near[id] = t }
            }
        }
        // Spread the nearest triangle outwards (forward and backward sweeps), giving approximate distances everywhere.
        fun relax(id: Int, nb: Int, px: Float, py: Float, pz: Float) {
            val t = near[nb]; if (t < 0 || t == near[id]) return
            val dist = triDist(verts, tris[3 * t], tris[3 * t + 1], tris[3 * t + 2], px, py, pz, cp)
            if (dist < d[id]) { d[id] = dist; near[id] = t }
        }
        repeat(2) {
            for (kk in 0 until nz) for (jj in 0 until ny) for (ii in 0 until nx) {
                val id = (kk * ny + jj) * nx + ii
                val px = x0 + ii * voxel; val py = y0 + jj * voxel; val pz = z0 + kk * voxel
                if (ii > 0) relax(id, id - 1, px, py, pz)
                if (jj > 0) relax(id, id - nx, px, py, pz)
                if (kk > 0) relax(id, id - nx * ny, px, py, pz)
            }
            for (kk in nz - 1 downTo 0) for (jj in ny - 1 downTo 0) for (ii in nx - 1 downTo 0) {
                val id = (kk * ny + jj) * nx + ii
                val px = x0 + ii * voxel; val py = y0 + jj * voxel; val pz = z0 + kk * voxel
                if (ii < nx - 1) relax(id, id + 1, px, py, pz)
                if (jj < ny - 1) relax(id, id + nx, px, py, pz)
                if (kk < nz - 1) relax(id, id + nx * ny, px, py, pz)
            }
        }
        // Inside/outside: crossings of the surface along each Z column.
        val count = IntArray(nx * ny)
        val jx = 1.3e-6f; val jy = 0.7e-6f     // tiny offset so columns don't run exactly through vertices
        fun columns(t: Int, emit: (Int, Float) -> Unit) {
            val a = tris[3 * t]; val b = tris[3 * t + 1]; val c = tris[3 * t + 2]
            val ax2 = verts[3 * a]; val ay2 = verts[3 * a + 1]; val az2 = verts[3 * a + 2]
            val bx2 = verts[3 * b]; val by2 = verts[3 * b + 1]; val bz2 = verts[3 * b + 2]
            val cx2 = verts[3 * c]; val cy2 = verts[3 * c + 1]; val cz2 = verts[3 * c + 2]
            val den = (by2 - cy2) * (ax2 - cx2) + (cx2 - bx2) * (ay2 - cy2)
            if (abs(den) < 1e-14f) return
            val i0 = max(0, ceil((min(ax2, min(bx2, cx2)) - x0 - jx) / voxel).toInt())
            val i1 = min(nx - 1, ((max(ax2, max(bx2, cx2)) - x0 - jx) / voxel).toInt())
            val j0 = max(0, ceil((min(ay2, min(by2, cy2)) - y0 - jy) / voxel).toInt())
            val j1 = min(ny - 1, ((max(ay2, max(by2, cy2)) - y0 - jy) / voxel).toInt())
            for (jj in j0..j1) for (ii in i0..i1) {
                val px = x0 + ii * voxel + jx; val py = y0 + jj * voxel + jy
                val l1 = ((by2 - cy2) * (px - cx2) + (cx2 - bx2) * (py - cy2)) / den
                val l2 = ((cy2 - ay2) * (px - cx2) + (ax2 - cx2) * (py - cy2)) / den
                val l3 = 1f - l1 - l2
                if (l1 < 0f || l2 < 0f || l3 < 0f) continue
                emit(jj * nx + ii, l1 * az2 + l2 * bz2 + l3 * cz2)
            }
        }
        for (t in 0 until nt) columns(t) { col, _ -> count[col]++ }
        val start = IntArray(nx * ny + 1)
        for (i in 0 until nx * ny) start[i + 1] = start[i] + count[i]
        val zs = FloatArray(start[nx * ny]); val fill = start.copyOf()
        for (t in 0 until nt) columns(t) { col, z -> zs[fill[col]++] = z }
        for (col in 0 until nx * ny) {
            val s0 = start[col]; val s1 = start[col + 1]
            if (s1 - s0 < 2) continue
            java.util.Arrays.sort(zs, s0, s1)
            val ii = col % nx; val jj = col / nx
            var p = s0
            for (kk in 0 until nz) {
                val z = z0 + kk * voxel
                while (p < s1 && zs[p] < z) p++
                if ((p - s0) % 2 == 1) { val id = (kk * ny + jj) * nx + ii; d[id] = -d[id] }
            }
        }
    }

    /** Trilinear sample; outside the grid, the edge value plus the distance to the grid. */
    fun sample(x: Float, y: Float, z: Float): Float {
        val fx = (x - x0) / voxel; val fy = (y - y0) / voxel; val fz = (z - z0) / voxel
        val cx = fx.coerceIn(0f, nx - 1.001f); val cy = fy.coerceIn(0f, ny - 1.001f); val cz = fz.coerceIn(0f, nz - 1.001f)
        val ox = (fx - cx) * voxel; val oy = (fy - cy) * voxel; val oz = (fz - cz) * voxel
        val i = cx.toInt(); val j = cy.toInt(); val k = cz.toInt()
        val tx = cx - i; val ty = cy - j; val tz = cz - k
        val id = (k * ny + j) * nx + i
        val sx = 1; val sy = nx; val sz = nx * ny
        fun l(a: Float, b: Float, t: Float) = a + (b - a) * t
        val v = l(
            l(l(d[id], d[id + sx], tx), l(d[id + sy], d[id + sy + sx], tx), ty),
            l(l(d[id + sz], d[id + sz + sx], tx), l(d[id + sz + sy], d[id + sz + sy + sx], tx), ty), tz)
        return if (ox == 0f && oy == 0f && oz == 0f) v else v + sqrt(ox * ox + oy * oy + oz * oz)
    }

    private fun triDist(v: FloatArray, ia: Int, ib: Int, ic: Int, px: Float, py: Float, pz: Float, out: FloatArray): Float {
        closestOnTriangle(v[3 * ia], v[3 * ia + 1], v[3 * ia + 2], v[3 * ib], v[3 * ib + 1], v[3 * ib + 2],
            v[3 * ic], v[3 * ic + 1], v[3 * ic + 2], px, py, pz, out)
        val dx = px - out[0]; val dy = py - out[1]; val dz = pz - out[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Closest point on triangle abc to p (Ericson, Real-Time Collision Detection 5.1.5). */
    private fun closestOnTriangle(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float,
                                  px: Float, py: Float, pz: Float, out: FloatArray) {
        val abx = bx - ax; val aby = by - ay; val abz = bz - az
        val acx = cx - ax; val acy = cy - ay; val acz = cz - az
        val apx = px - ax; val apy = py - ay; val apz = pz - az
        val d1 = abx * apx + aby * apy + abz * apz; val d2 = acx * apx + acy * apy + acz * apz
        if (d1 <= 0f && d2 <= 0f) { out[0] = ax; out[1] = ay; out[2] = az; return }
        val bpx = px - bx; val bpy = py - by; val bpz = pz - bz
        val d3 = abx * bpx + aby * bpy + abz * bpz; val d4 = acx * bpx + acy * bpy + acz * bpz
        if (d3 >= 0f && d4 <= d3) { out[0] = bx; out[1] = by; out[2] = bz; return }
        val vc = d1 * d4 - d3 * d2
        if (vc <= 0f && d1 >= 0f && d3 <= 0f) { val v = d1 / (d1 - d3); out[0] = ax + v * abx; out[1] = ay + v * aby; out[2] = az + v * abz; return }
        val cpx = px - cx; val cpy = py - cy; val cpz = pz - cz
        val d5 = abx * cpx + aby * cpy + abz * cpz; val d6 = acx * cpx + acy * cpy + acz * cpz
        if (d6 >= 0f && d5 <= d6) { out[0] = cx; out[1] = cy; out[2] = cz; return }
        val vb = d5 * d2 - d1 * d6
        if (vb <= 0f && d2 >= 0f && d6 <= 0f) { val w = d2 / (d2 - d6); out[0] = ax + w * acx; out[1] = ay + w * acy; out[2] = az + w * acz; return }
        val va = d3 * d6 - d5 * d4
        if (va <= 0f && (d4 - d3) >= 0f && (d5 - d6) >= 0f) {
            val w = (d4 - d3) / ((d4 - d3) + (d5 - d6))
            out[0] = bx + w * (cx - bx); out[1] = by + w * (cy - by); out[2] = bz + w * (cz - bz); return
        }
        val den = 1f / (va + vb + vc); val v = vb * den; val w = vc * den
        out[0] = ax + abx * v + acx * w; out[1] = ay + aby * v + acy * w; out[2] = az + abz * v + acz * w
    }
}
