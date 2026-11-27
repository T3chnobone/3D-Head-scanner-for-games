package com.headscan.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Image mode: turns a face mesh found in a single photo (468 points in the MediaPipe / ARCore face mesh layout, in
 * pixels, with a relative depth in the same units) into the face frame Selfie mode uses (metres, +Y up, +Z out of
 * the face), plus the camera that took the photo, so the photo can texture the head.
 *
 * The size comes from the distance between the eyes (an average 63 mm), and the camera is assumed to have a
 * typical phone field of view. One photo can't show the sides and back of the head: those come from the head model.
 */
object PhotoFace {
    class Result(val vertices: FloatArray, val triangles: IntArray, val keyframe: Keyframe)

    // Eye corners and nose tip in the face mesh layout.
    private const val R_OUT = 33; private const val R_IN = 133; private const val L_IN = 362; private const val L_OUT = 263
    private const val NOSE = 1
    private const val EYE_DISTANCE = 0.063f

    /**
     * @param px pixel x, [py] pixel y (down), [pz] relative depth (same scale as x; either sign convention).
     * @param width, [height] of the photo saved at [colorRef].
     */
    fun build(px: FloatArray, py: FloatArray, pz: FloatArray, triangles: IntArray, width: Int, height: Int, colorRef: String): Result {
        val n = px.size
        require(n >= 468) { "face mesh needs 468 points" }
        val f = 1.1f * max(width, height)          // ~ 50° across the long side, like most phone cameras
        val cx = width / 2f; val cy = height / 2f
        fun mid(a: Int, b: Int) = floatArrayOf((px[a] + px[b]) / 2, (py[a] + py[b]) / 2)
        val er = mid(R_OUT, R_IN); val el = mid(L_IN, L_OUT)
        val eyePx = sqrt((er[0] - el[0]) * (er[0] - el[0]) + (er[1] - el[1]) * (er[1] - el[1]))
        require(eyePx > 8f) { "face too small" }
        val ppm = eyePx / EYE_DISTANCE                 // pixels per metre at the face
        val dist = f / ppm                             // camera to face (m)
        // Which way the depth values grow: the nose tip is nearer the camera than the average point.
        var mean = 0f; for (i in 0 until n) mean += pz[i]; mean /= n
        val k = if (pz[NOSE] < mean) 1f else -1f
        // Camera space (OpenGL: x right, y up, looking down -z).
        val cam = FloatArray(n * 3)
        for (i in 0 until n) {
            val d = dist + k * (pz[i] - mean) / ppm
            cam[3 * i] = (px[i] - cx) * d / f
            cam[3 * i + 1] = -(py[i] - cy) * d / f
            cam[3 * i + 2] = -d
        }
        // Face frame: origin on the midline just below eye level, 6 cm behind the nose tip; turned so the eyes are level.
        val ex0 = (cam[3 * R_OUT] + cam[3 * R_IN]) / 2; val ey0 = (cam[3 * R_OUT + 1] + cam[3 * R_IN + 1]) / 2
        val ex1 = (cam[3 * L_IN] + cam[3 * L_OUT]) / 2; val ey1 = (cam[3 * L_IN + 1] + cam[3 * L_OUT + 1]) / 2
        // The person's right eye is on the left of the photo; "right" in the face frame is +X as seen by the camera.
        val (ax, ay, bx, by) = if (ex0 < ex1) listOf(ex0, ey0, ex1, ey1) else listOf(ex1, ey1, ex0, ey0)
        val roll = atan2(by - ay, bx - ax)
        val c = cos(roll); val s = sin(roll)
        val ox = (ax + bx) / 2; val oy = (ay + by) / 2 - 0.01f; val oz = cam[3 * NOSE + 2] - 0.06f
        val verts = FloatArray(n * 3)
        for (i in 0 until n) {
            val x = cam[3 * i] - ox; val y = cam[3 * i + 1] - oy; val z = cam[3 * i + 2] - oz
            verts[3 * i] = c * x + s * y
            verts[3 * i + 1] = -s * x + c * y
            verts[3 * i + 2] = z
        }
        // Camera pose in the face frame (column-major 4x4): rotation Rᵀ, position Rᵀ(0 - o).
        val tx = -ox; val ty = -oy; val tz = -oz
        val camToFace = floatArrayOf(
            c, -s, 0f, 0f,
            s, c, 0f, 0f,
            0f, 0f, 1f, 0f,
            c * tx + s * ty, -s * tx + c * ty, tz, 1f,
        )
        val faceToCam = invertRigid(camToFace)
        // Triangles wound so they face the camera (+Z in the face frame).
        val tris = triangles.copyOf()
        for (t in 0 until tris.size / 3) {
            val a = tris[3 * t]; val b = tris[3 * t + 1]; val d = tris[3 * t + 2]
            val ux = verts[3 * b] - verts[3 * a]; val uy = verts[3 * b + 1] - verts[3 * a + 1]
            val vx = verts[3 * d] - verts[3 * a]; val vy = verts[3 * d + 1] - verts[3 * a + 1]
            if (ux * vy - uy * vx < 0f) { tris[3 * t + 1] = d; tris[3 * t + 2] = b }
        }
        val intr = Intrinsics(f, f, cx, cy, width, height)
        val kf = Keyframe(camToFace, faceToCam, ShortArray(1), 1, 1, intr.scaledTo(1, 1), intr, colorRef, priority = 1.1f)
        return Result(verts, tris, kf)
    }

    private fun invertRigid(m: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (i in 0 until 3) for (j in 0 until 3) r[j * 4 + i] = m[i * 4 + j]
        for (i in 0 until 3) r[12 + i] = -(r[i] * m[12] + r[4 + i] * m[13] + r[8 + i] * m[14])
        r[15] = 1f
        return r
    }
}
