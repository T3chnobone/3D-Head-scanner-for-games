package com.headscan.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Rear-camera scans built the same way as Selfie mode: the face is found in the rear photos (a face mesh detector's
 * 468 points per photo, lifted into 3D with the depth camera), the photos are re-expressed in the face's own frame,
 * and the head-shape model is fitted to the face and to the depth measured around the head. Anything below the
 * neck (clothes, shoulders) and the background are left out, and the photos only texture the face.
 */
object RearFace {
    /** The face mesh found in one rear photo: pixel x / y (down) and relative depth, in the photo's own pixels. */
    class Landmarks(val keyframe: Keyframe, val px: FloatArray, val py: FloatArray, val pz: FloatArray)

    class Result(val keyframes: List<Keyframe>, val faceVertices: FloatArray, val faceTriangles: IntArray, val shape: HeadShape?,
                 val framesUsed: Int)

    private const val R_OUT = 33; private const val R_IN = 133; private const val L_IN = 362; private const val L_OUT = 263
    private const val NOSE = 1

    /** Why the last [build] returned null, in words for the user. */
    @Volatile var lastProblem: String? = null

    private fun eyeDistance(w: FloatArray): Float {
        fun c(i: Int, a: Int) = w[3 * i + a]
        var d2 = 0f
        for (a in 0..2) { val e = (c(L_IN, a) + c(L_OUT, a)) / 2 - (c(R_OUT, a) + c(R_IN, a)) / 2; d2 += e * e }
        return sqrt(d2)
    }

    /** How far the face is turned from the camera (0 = straight on), from the nose tip's offset between the eyes. */
    fun turn(l: Landmarks): Float {
        // Along the eye line (works whichever way up the photo is).
        val rx = (l.px[R_OUT] + l.px[R_IN]) / 2; val ry = (l.py[R_OUT] + l.py[R_IN]) / 2
        val lx = (l.px[L_IN] + l.px[L_OUT]) / 2; val ly = (l.py[L_IN] + l.py[L_OUT]) / 2
        val ex = lx - rx; val ey = ly - ry
        val e2 = ex * ex + ey * ey
        if (e2 < 1f) return 9f
        val ox = l.px[NOSE] - (rx + lx) / 2; val oy = l.py[NOSE] - (ry + ly) / 2
        return abs(ox * ex + oy * ey) / e2
    }

    /** The face points in world space, using the depth camera for distance and size; null if depth is missing. */
    fun lift(l: Landmarks): FloatArray? {
        val kf = l.keyframe
        val ci = kf.colorIntrinsics; val di = kf.depthIntrinsics
        val n = l.px.size
        val f = ci.fx
        var mean = 0f; for (i in 0 until n) mean += l.pz[i]; mean /= n
        val k = if (l.pz[NOSE] < mean) 1f else -1f
        // Measured depth at each point (depth image covers the same view at a lower resolution).
        val meas = FloatArray(n)
        var valid = 0
        for (i in 0 until n) {
            // Same camera, so map through the viewing ray (the two images can differ in field of view and aspect).
            val u = (di.cx + di.fx * (l.px[i] - ci.cx) / ci.fx).toInt(); val v = (di.cy + di.fy * (l.py[i] - ci.cy) / ci.fy).toInt()
            meas[i] = kf.depthAt(u, v); if (meas[i] > 0.1f) valid++
        }
        if (valid < n / 3) return null
        // Distance to the face's mean depth: median over the points of (measured depth − its offset from the mean).
        var dist = meas.filter { it > 0.1f }.sorted().let { it[it.size / 2] }
        repeat(3) {
            val est = ArrayList<Float>()
            for (i in 0 until n) if (meas[i] > 0.1f) est.add(meas[i] - k * (l.pz[i] - mean) * dist / f)
            est.sort(); dist = est[est.size / 2]
        }
        val out = FloatArray(n * 3)
        val m = kf.camToWorld
        for (i in 0 until n) {
            val d = dist + k * (l.pz[i] - mean) * dist / f
            val x = (l.px[i] - ci.cx) * d / f; val y = -(l.py[i] - ci.cy) * d / f; val z = -d
            out[3 * i] = m[0] * x + m[4] * y + m[8] * z + m[12]
            out[3 * i + 1] = m[1] * x + m[5] * y + m[9] * z + m[13]
            out[3 * i + 2] = m[2] * x + m[6] * y + m[10] * z + m[14]
        }
        return out
    }

    /**
     * [found]: face meshes from the rear photos; [triangles]: the face mesh triangles. Returns null when no usable
     * front view was found (the caller falls back to the plain depth scan).
     */
    fun build(keyframes: List<Keyframe>, found: List<Landmarks>, triangles: IntArray): Result? {
        // The three most front-on views with depth (front-on views preferred; a turned view is better than none).
        lastProblem = null
        val ordered = found.sortedBy { turn(it) }
        val front = ordered.filter { turn(it) < 0.25f }
        val lifted = (front.ifEmpty { ordered.filter { turn(it) < 0.45f } }).mapNotNull { lift(it) }
            .filter { eyeDistance(it) in 0.04f..0.09f }.take(3)
        if (lifted.isEmpty()) {
            lastProblem = when {
                found.isEmpty() -> "no face was found in any photo"
                ordered.none { turn(it) < 0.45f } -> "the face was only seen from the side (stand in front of the face before tapping Finish)"
                else -> "the face was found but the depth camera didn't measure it (move a little closer, 30-80 cm)"
            }
            return null
        }
        val n = lifted[0].size / 3
        val w = FloatArray(n * 3)
        for (p in lifted) for (i in w.indices) w[i] += p[i] / lifted.size
        fun pt(i: Int) = floatArrayOf(w[3 * i], w[3 * i + 1], w[3 * i + 2])
        fun mid(a: Int, b: Int): FloatArray { val p = pt(a); val q = pt(b); return floatArrayOf((p[0] + q[0]) / 2, (p[1] + q[1]) / 2, (p[2] + q[2]) / 2) }
        val er = mid(R_OUT, R_IN); val el = mid(L_IN, L_OUT); val nose = pt(NOSE)
        val eyeDist = dist(er, el)
        if (eyeDist !in 0.04f..0.09f) { lastProblem = "the face size came out wrong from the depth"; return null }
        // Face frame: X across the eyes, Y up (gravity), Z out of the face.
        var x = norm(floatArrayOf(el[0] - er[0], el[1] - er[1], el[2] - er[2]))
        val up = floatArrayOf(0f, 1f, 0f)
        var y = norm(sub(up, scale(x, dot(up, x))))
        var z = cross(x, y)
        val em = floatArrayOf((er[0] + el[0]) / 2, (er[1] + el[1]) / 2, (er[2] + el[2]) / 2)
        if (dot(sub(nose, em), z) < 0f) { x = scale(x, -1f); z = cross(x, y) }
        y = cross(z, x)
        val noseOut = dot(sub(nose, em), z)
        val o = FloatArray(3) { em[it] - 0.01f * y[it] + (noseOut - 0.06f) * z[it] }
        // World -> face: rows x, y, z; translation -R·o.
        val r = floatArrayOf(x[0], x[1], x[2], y[0], y[1], y[2], z[0], z[1], z[2])
        val t = FloatArray(3) { -(r[3 * it] * o[0] + r[3 * it + 1] * o[1] + r[3 * it + 2] * o[2]) }
        fun toFace(px: Float, py: Float, pz: Float, out: FloatArray, at: Int) {
            for (a in 0..2) out[at + a] = r[3 * a] * px + r[3 * a + 1] * py + r[3 * a + 2] * pz + t[a]
        }
        val faceVerts = FloatArray(n * 3)
        for (i in 0 until n) toFace(w[3 * i], w[3 * i + 1], w[3 * i + 2], faceVerts, 3 * i)
        // Triangles wound to face +Z.
        val tris = triangles.copyOf()
        for (tt in 0 until tris.size / 3) {
            val a = tris[3 * tt]; val b = tris[3 * tt + 1]; val d = tris[3 * tt + 2]
            val ux = faceVerts[3 * b] - faceVerts[3 * a]; val uy = faceVerts[3 * b + 1] - faceVerts[3 * a + 1]
            val vx = faceVerts[3 * d] - faceVerts[3 * a]; val vy = faceVerts[3 * d + 1] - faceVerts[3 * a + 1]
            if (ux * vy - uy * vx < 0f) { tris[3 * tt + 1] = d; tris[3 * tt + 2] = b }
        }
        // Keyframes in the face frame: camToFace = F · camToWorld.
        val fm = floatArrayOf(r[0], r[3], r[6], 0f, r[1], r[4], r[7], 0f, r[2], r[5], r[8], 0f, t[0], t[1], t[2], 1f)
        val moved = keyframes.map { kf ->
            val c2f = mul(fm, kf.camToWorld)
            Keyframe(c2f, invertRigid(c2f), kf.depthMm, kf.depthWidth, kf.depthHeight, kf.depthIntrinsics, kf.colorIntrinsics,
                kf.colorRef, kf.sharpness, kf.priority)
        }
        return Result(moved, faceVerts, tris, measureSkull(moved, faceVerts), lifted.size)
    }

    /**
     * The skull's width, top and back from the depth points around the head (face frame), with a few millimetres
     * taken off for hair like [HeadFit]. Points below the jaw (neck, clothes) and far from the head are ignored.
     * Null when the scan didn't see enough of the head.
     */
    fun measureSkull(keyframes: List<Keyframe>, face: FloatArray): HeadShape? {
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var noseZ = -Float.MAX_VALUE
        for (i in 0 until face.size / 3) { minY = minOf(minY, face[3 * i + 1]); maxY = maxOf(maxY, face[3 * i + 1]); noseZ = maxOf(noseZ, face[3 * i + 2]) }
        val ys = ArrayList<Float>(); val xs = ArrayList<Float>(); val zs = ArrayList<Float>()
        for (kf in keyframes) {
            val di = kf.depthIntrinsics; val m = kf.camToWorld
            for (v in 0 until kf.depthHeight) for (u in 0 until kf.depthWidth) {
                val d = kf.depthAt(u, v); if (d < 0.1f) continue
                val cx = (u + 0.5f - di.cx) / di.fx * d; val cy = -(v + 0.5f - di.cy) / di.fy * d; val cz = -d
                val px = m[0] * cx + m[4] * cy + m[8] * cz + m[12]
                val py = m[1] * cx + m[5] * cy + m[9] * cz + m[13]
                val pz = m[2] * cx + m[6] * cy + m[10] * cz + m[14]
                if (abs(px) > 0.14f || py < minY + 0.02f || py > maxY + 0.14f || pz > noseZ + 0.02f || pz < -0.26f) continue
                if (py > (minY + maxY) / 2 && abs(pz + 0.04f) < 0.06f) xs.add(abs(px))
                if (abs(px) < 0.04f && pz < noseZ - 0.08f) ys.add(py)
                if (abs(px) < 0.04f && py > minY + 0.05f) zs.add(pz)
            }
        }
        if (xs.size < 200 || ys.size < 200 || zs.size < 200) return null
        xs.sort(); ys.sort(); zs.sort()
        val rx = xs[(xs.size * 0.97f).toInt()] - 0.003f
        val top = ys[(ys.size * 0.98f).toInt()] - 0.006f
        val back = zs[(zs.size * 0.02f).toInt()] + 0.005f
        return HeadShape(rx, top, noseZ, back)
    }

    private fun dist(a: FloatArray, b: FloatArray) = sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))
    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    private fun scale(a: FloatArray, s: Float) = floatArrayOf(a[0] * s, a[1] * s, a[2] * s)
    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun norm(a: FloatArray): FloatArray { val l = max(1e-9f, sqrt(dot(a, a))); return scale(a, 1f / l) }

    /** Column-major 4x4 product a·b. */
    private fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (c in 0..3) for (rr in 0..3) { var s = 0f; for (k in 0..3) s += a[k * 4 + rr] * b[c * 4 + k]; out[c * 4 + rr] = s }
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
