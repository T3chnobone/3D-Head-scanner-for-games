package com.headscan

import android.graphics.ImageFormat
import android.os.SystemClock
import com.google.ar.core.AugmentedFace
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.headscan.core.Keyframe
import java.io.File
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Selfie mode capture (front camera + ARCore Augmented Faces). Runs on the GL thread.
 *
 * Every keyframe is stored in the *face* frame (ARCore face centre pose: +Y up, +Z out of the face),
 * so the person turning their head becomes a camera orbiting a still head. The face mesh is averaged
 * over all keyframes to smooth out expression changes.
 */
class SelfieCapture(private val workDir: File, private val saveJpeg: (ByteArray, Int, Int, File) -> Unit) {
    val keyframes = ArrayList<Keyframe>()
    private val dirs = ArrayList<FloatArray>()
    private var vertexSum: DoubleArray? = null
    private var vertexCount = 0
    var triangles: IntArray? = null
        private set
    val coverage = BooleanArray(YAW_BINS)
    private var lastMs = 0L
    private var sideA = false
    private var sideB = false
    private var up = false
    private var down = false

    fun reset() {
        keyframes.clear(); dirs.clear(); vertexSum = null; vertexCount = 0; triangles = null
        coverage.fill(false); lastMs = 0; sideA = false; sideB = false; up = false; down = false
    }

    fun findFace(session: Session): AugmentedFace? =
        session.getAllTrackables(AugmentedFace::class.java).firstOrNull { it.trackingState == TrackingState.TRACKING }

    /** Averaged face mesh vertices (face frame), or null if nothing captured. */
    fun averagedVertices(): FloatArray? {
        val sum = vertexSum ?: return null
        return FloatArray(sum.size) { (sum[it] / vertexCount).toFloat() }
    }

    /** Called every frame while capturing. Returns the hint to show. */
    fun update(session: Session, frame: Frame, force: Boolean = false): String {
        val face = findFace(session) ?: return progressText("Can't see your face – look at the screen, with your whole face in view.")
        val camInFace = face.centerPose.inverse().compose(frame.camera.pose)
        val t = camInFace.translation
        val dist = sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2])
        if (dist < 0.15f) return progressText("Too close – hold the phone a bit further away.")
        if (dist > 0.8f) return progressText("Too far – bring the phone closer.")
        val dir = floatArrayOf(t[0] / dist, t[1] / dist, t[2] / dist)
        val now = SystemClock.uptimeMillis()
        if (!force) {
            if (now - lastMs < MIN_INTERVAL_MS || keyframes.size >= MAX_KEYFRAMES) return progressText(null)
            var minAngle = 180.0
            for (d in dirs) {
                val dot = (d[0] * dir[0] + d[1] * dir[1] + d[2] * dir[2]).coerceIn(-1f, 1f)
                minAngle = minOf(minAngle, Math.toDegrees(acos(dot.toDouble())))
            }
            if (minAngle < NEW_VIEW_DEG) return progressText(null)
        }
        if (capture(frame, face, camInFace)) {
            dirs.add(dir)
            lastMs = now
            val yaw = Math.toDegrees(atan2(dir[0].toDouble(), dir[2].toDouble()))
            val pitch = Math.toDegrees(asin(dir[1].toDouble()))
            if (yaw < -25) sideA = true
            if (yaw > 25) sideB = true
            if (pitch < -12) up = true      // camera below the face = chin up
            if (pitch > 12) down = true
            val bin = ((yaw + 60) / 120 * YAW_BINS).toInt()
            if (bin in 0 until YAW_BINS) coverage[bin] = true
        }
        return progressText(null)
    }

    val isComplete get() = sideA && sideB && up && down

    private fun progressText(problem: String?): String {
        val sb = StringBuilder("Captured ${keyframes.size} views\n")
        if (problem != null) return sb.append(problem).toString()
        val missing = ArrayList<String>()
        if (!sideA) missing.add("turn your head one way")
        if (!sideB) missing.add("turn it the other way")
        if (!up) missing.add("tilt your chin up")
        if (!down) missing.add("tilt your chin down")
        if (missing.isEmpty()) sb.append("All angles captured. Tap Finish.")
        else sb.append("Keep the phone still and slowly ").append(missing.joinToString(", then ")).append('.')
        return sb.toString()
    }

    private fun capture(frame: Frame, face: AugmentedFace, camInFace: com.google.ar.core.Pose): Boolean {
        val image = try { frame.acquireCameraImage() } catch (e: NotYetAvailableException) { return false }
        val raw = try {
            if (image.format != ImageFormat.YUV_420_888) null
            else Triple(ImageUtils.yuvToNv21(image), image.width, image.height)
        } finally {
            image.close()
        } ?: return false
        val (nv21, w, h) = raw
        // Face mesh (in face-local coordinates).
        val vb = face.meshVertices
        vb.rewind()
        val n = vb.remaining()
        val sum = vertexSum ?: DoubleArray(n).also { vertexSum = it }
        if (sum.size != n) return false
        for (i in 0 until n) sum[i] += vb.get(i).toDouble()
        vertexCount++
        if (triangles == null) {
            val ib = face.meshTriangleIndices
            ib.rewind()
            triangles = IntArray(ib.remaining()) { ib.get(it).toInt() and 0xFFFF }
        }

        val camToFace = FloatArray(16).also { camInFace.toMatrix(it, 0) }
        val faceToCam = FloatArray(16).also { camInFace.inverse().toMatrix(it, 0) }
        val colorIntr = ImageUtils.toIntrinsics(frame.camera.imageIntrinsics)
        val file = File(workDir, "selfie_%03d.jpg".format(keyframes.size))
        saveJpeg(nv21, w, h, file)
        // No depth on the front camera: a 1x1 empty depth map disables the occlusion test.
        keyframes.add(Keyframe(camToFace, faceToCam, ShortArray(1), 1, 1, colorIntr.scaledTo(1, 1), colorIntr, file.absolutePath))
        return true
    }

    companion object {
        const val YAW_BINS = 12
        private const val NEW_VIEW_DEG = 6.0
        private const val MIN_INTERVAL_MS = 200L
        private const val MAX_KEYFRAMES = 40
        const val MIN_KEYFRAMES = 6
    }
}
