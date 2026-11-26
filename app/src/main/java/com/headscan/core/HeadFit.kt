package com.headscan.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Measured skull for Selfie mode, in the face frame (metres): half width, top of head, front and back of the skull. */
data class HeadShape(val rx: Float, val top: Float, val zFront: Float, val zBack: Float)

/**
 * The person's outline in one photo: [mask] is 1 where the person is, 0 for background, [w] x [h] pixels,
 * with [intrinsics] for that size and the camera pose in the face frame.
 */
class SilhouetteView(val mask: ByteArray, val w: Int, val h: Int, val intrinsics: Intrinsics, val worldToCam: FloatArray)

/**
 * Measures the head from the person's outline in the photos taken while they turn their head.
 *
 * The model is the generic skull (an ellipsoid: width, top, front and back) plus the scanned face mesh. It is
 * projected into every photo and adjusted until it covers the same pixels as the person's outline. Only the part
 * of the outline above the brows is used, so ears, neck and shoulders don't count. Hair makes the outline bigger
 * than the skull, so a few millimetres are taken off at the end.
 */
object HeadFit {
    class Result(val shape: HeadShape, val mismatch: Float, val viewsUsed: Int, val depthMeasured: Boolean)

    /** Millimetres of hair assumed on the top / back / sides when going from the outline to the skull. */
    private const val HAIR_TOP = 0.006f
    private const val HAIR_BACK = 0.005f
    private const val HAIR_SIDE = 0.003f

    private class Px(val ox: Float, val oy: Float, val oz: Float, val dx: Float, val dy: Float, val dz: Float, val target: Float)

    fun fit(views: List<SilhouetteView>, faceVertices: FloatArray, faceTriangles: IntArray, start: HeadShape, headCy: Float,
            minY: Float, maxY: Float): Result? {
        val cutY = minY + 0.75f * (maxY - minY)           // about brow level
        val c0x = 0f; val c0y = headCy; val c0z = (start.zFront + start.zBack) / 2
        val pixels = ArrayList<Px>()
        var used = 0
        var maxYaw = 0f
        for (v in views) {
            val cam = invertRigid(v.worldToCam)
            val faceHit = rasterFace(v, faceVertices, faceTriangles)
            val i = v.intrinsics
            val before = pixels.size
            var person = 0
            val step = max(1, v.w / 120)
            for (py in 0 until v.h step step) for (px in 0 until v.w step step) {
                val x = (px + 0.5f - i.cx) / i.fx; val y = -(py + 0.5f - i.cy) / i.fy
                var dx = cam[0] * x + cam[4] * y - cam[8]; var dy = cam[1] * x + cam[5] * y - cam[9]; var dz = cam[2] * x + cam[6] * y - cam[10]
                val l = sqrt(dx * dx + dy * dy + dz * dz); dx /= l; dy /= l; dz /= l
                val ox = cam[12]; val oy = cam[13]; val oz = cam[14]
                // Point on the ray closest to the head centre: decides whether this pixel looks at the upper head.
                val t = (c0x - ox) * dx + (c0y - oy) * dy + (c0z - oz) * dz
                if (t <= 0f) continue
                val qx = ox + dx * t; val qy = oy + dy * t; val qz = oz + dz * t
                if (qy < cutY) continue
                val off = sqrt((qx - c0x) * (qx - c0x) + (qy - c0y) * (qy - c0y) + (qz - c0z) * (qz - c0z))
                if (off > 0.2f) continue
                if (faceHit[py * v.w + px]) continue           // the face covers these whatever the skull does
                val m = v.mask[py * v.w + px].toInt()
                if (m != 0) person++
                pixels.add(Px(ox, oy, oz, dx, dy, dz, m.toFloat()))
            }
            val n = pixels.size - before
            // A view whose outline is nearly empty or nearly full around the head is a failed cut-out: drop it.
            if (n < 30 || person < n * 0.1f || person > n * 0.9f) {
                while (pixels.size > before) pixels.removeAt(pixels.size - 1)
                continue
            }
            used++
            maxYaw = max(maxYaw, abs(Math.toDegrees(atan2(cam[12].toDouble(), cam[14].toDouble())).toFloat()))
        }
        if (used < 4 || pixels.size < 400) return null
        val depthOk = maxYaw > 30f

        // Parameters: half width, top, front, back. Without side-on views the back keeps the average depth.
        val defaultDepth = start.zFront - start.zBack
        fun shapeOf(p: FloatArray) = HeadShape(
            p[0].coerceIn(0.055f, 0.11f),
            p[1].coerceIn(maxY - 0.005f, maxY + 0.12f),
            p[2].coerceIn(start.zFront - 0.045f, start.zFront + 0.015f),
            if (depthOk) p[3].coerceIn(p[2] - 0.25f, p[2] - 0.13f) else p[2] - defaultDepth,
        )
        val jaw = 2 * headCy - start.top                 // bottom of the default skull stays put
        fun loss(p: FloatArray): Float {
            val s = shapeOf(p)
            val cy = (s.top + jaw) / 2; val ry = (s.top - jaw) / 2
            val cz = (s.zFront + s.zBack) / 2; val rz = (s.zFront - s.zBack) / 2
            var sum = 0f
            for (q in pixels) {
                // Ray in unit-sphere space; distance of its closest approach from the centre, minus 1.
                val ox = (q.ox - c0x) / s.rx; val oy = (q.oy - cy) / ry; val oz = (q.oz - cz) / rz
                val dx = q.dx / s.rx; val dy = q.dy / ry; val dz = q.dz / rz
                val dd = dx * dx + dy * dy + dz * dz
                val t = -(ox * dx + oy * dy + oz * dz) / dd
                val cx = ox + dx * t; val cyy = oy + dy * t; val cz2 = oz + dz * t
                val dist = sqrt(cx * cx + cyy * cyy + cz2 * cz2) - 1f
                val occ = 1f / (1f + exp(dist / 0.03f))
                val e = occ - q.target
                sum += e * e
            }
            // Gentle pull to average proportions so a poor outline can't produce a strange head.
            val reg = sq((s.rx - start.rx) / 0.03f) + sq((s.top - start.top) / 0.05f) + sq((s.zFront - start.zFront) / 0.04f) +
                sq(((s.zFront - s.zBack) - defaultDepth) / 0.06f)
            return sum / pixels.size + 0.002f * reg
        }
        val best = nelderMead(floatArrayOf(start.rx, start.top, start.zFront, start.zBack), floatArrayOf(0.01f, 0.015f, 0.01f, 0.015f), 220, ::loss)
        val s = shapeOf(best)
        // Count pixels that still disagree, for a quality check.
        val cy = (s.top + jaw) / 2; val ry = (s.top - jaw) / 2
        val cz = (s.zFront + s.zBack) / 2; val rz = (s.zFront - s.zBack) / 2
        var wrong = 0
        for (q in pixels) {
            val ox = (q.ox - c0x) / s.rx; val oy = (q.oy - cy) / ry; val oz = (q.oz - cz) / rz
            val dx = q.dx / s.rx; val dy = q.dy / ry; val dz = q.dz / rz
            val b = ox * dx + oy * dy + oz * dz; val a = dx * dx + dy * dy + dz * dz; val c = ox * ox + oy * oy + oz * oz - 1f
            val hit = b * b - a * c >= 0f
            if (hit != (q.target > 0.5f)) wrong++
        }
        val mismatch = wrong.toFloat() / pixels.size
        if (mismatch > 0.2f) return null
        // Outline includes hair: the skull is a little smaller.
        val skull = HeadShape(s.rx - HAIR_SIDE, s.top - HAIR_TOP, s.zFront, if (depthOk) s.zBack + HAIR_BACK else s.zBack)
        return Result(skull, mismatch, used, depthOk)
    }

    private fun sq(x: Float) = x * x

    private fun nelderMead(x0: FloatArray, step: FloatArray, iters: Int, f: (FloatArray) -> Float): FloatArray {
        val n = x0.size
        val pts = Array(n + 1) { i -> x0.copyOf().also { if (i > 0) it[i - 1] += step[i - 1] } }
        val vals = FloatArray(n + 1) { f(pts[it]) }
        repeat(iters) {
            val order = (0..n).sortedBy { vals[it] }
            val sp = order.map { pts[it] }; val sv = order.map { vals[it] }
            for (i in 0..n) { pts[i] = sp[i]; vals[i] = sv[i] }
            val cen = FloatArray(n)
            for (i in 0 until n) for (k in 0 until n) cen[k] += pts[i][k] / n
            fun along(t: Float) = FloatArray(n) { cen[it] + t * (pts[n][it] - cen[it]) }
            val r = along(-1f); val fr = f(r)
            if (fr < vals[0]) {
                val e = along(-2f); val fe = f(e)
                if (fe < fr) { pts[n] = e; vals[n] = fe } else { pts[n] = r; vals[n] = fr }
            } else if (fr < vals[n - 1]) {
                pts[n] = r; vals[n] = fr
            } else {
                val c = along(0.5f); val fc = f(c)
                if (fc < vals[n]) { pts[n] = c; vals[n] = fc } else {
                    for (i in 1..n) { for (k in 0 until n) pts[i][k] = pts[0][k] + 0.5f * (pts[i][k] - pts[0][k]); vals[i] = f(pts[i]) }
                }
            }
        }
        var bi = 0
        for (i in 1..n) if (vals[i] < vals[bi]) bi = i
        return pts[bi]
    }

    /** Which pixels of the view the face mesh covers. */
    private fun rasterFace(v: SilhouetteView, verts: FloatArray, tris: IntArray): BooleanArray {
        val out = BooleanArray(v.w * v.h)
        val m = v.worldToCam; val i = v.intrinsics
        val n = verts.size / 3
        val pu = FloatArray(n); val pv = FloatArray(n); val ok = BooleanArray(n)
        for (k in 0 until n) {
            val x = verts[3 * k]; val y = verts[3 * k + 1]; val z = verts[3 * k + 2]
            val cx = m[0] * x + m[4] * y + m[8] * z + m[12]
            val cy = m[1] * x + m[5] * y + m[9] * z + m[13]
            val cz = m[2] * x + m[6] * y + m[10] * z + m[14]
            if (cz >= -0.01f) continue
            pu[k] = i.fx * cx / -cz + i.cx; pv[k] = i.cy - i.fy * cy / -cz; ok[k] = true
        }
        for (t in 0 until tris.size / 3) {
            val a = tris[3 * t]; val b = tris[3 * t + 1]; val c = tris[3 * t + 2]
            if (!ok[a] || !ok[b] || !ok[c]) continue
            val x0 = max(0, min(pu[a], min(pu[b], pu[c])).toInt()); val x1 = min(v.w - 1, max(pu[a], max(pu[b], pu[c])).toInt() + 1)
            val y0 = max(0, min(pv[a], min(pv[b], pv[c])).toInt()); val y1 = min(v.h - 1, max(pv[a], max(pv[b], pv[c])).toInt() + 1)
            val area = (pu[b] - pu[a]) * (pv[c] - pv[a]) - (pu[c] - pu[a]) * (pv[b] - pv[a])
            if (abs(area) < 1e-6f) continue
            for (y in y0..y1) for (x in x0..x1) {
                val px = x + 0.5f; val py = y + 0.5f
                val w0 = ((pu[b] - px) * (pv[c] - py) - (pu[c] - px) * (pv[b] - py)) / area
                val w1 = ((pu[c] - px) * (pv[a] - py) - (pu[a] - px) * (pv[c] - py)) / area
                val w2 = 1f - w0 - w1
                if (w0 >= 0f && w1 >= 0f && w2 >= 0f) out[y * v.w + x] = true
            }
        }
        return out
    }

    private fun invertRigid(m: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (i in 0 until 3) for (j in 0 until 3) r[j * 4 + i] = m[i * 4 + j]
        for (i in 0 until 3) r[12 + i] = -(r[i] * m[12] + r[4 + i] * m[13] + r[8 + i] * m[14])
        r[15] = 1f
        return r
    }
}
