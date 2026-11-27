package com.headscan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.facemesh.FaceMeshDetection
import com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions
import com.headscan.core.Keyframe
import com.headscan.core.RearFace
import kotlin.math.abs
import kotlin.math.max

/**
 * Rear-camera scans: finds the face mesh in each scan photo with ML Kit's on-device face mesh detector, in the
 * photo's own pixels (see [RearFace]). Runs on a background thread.
 */
object RearFaces {
    private const val MAX_SIDE = 720

    class Found(val landmarks: List<RearFace.Landmarks>, val triangles: IntArray)

    fun find(frames: List<Keyframe>): Found {
        val detector = FaceMeshDetection.getClient(
            FaceMeshDetectorOptions.Builder().setUseCase(FaceMeshDetectorOptions.FACE_MESH).build())
        val out = ArrayList<RearFace.Landmarks>()
        var tris = IntArray(0)
        try {
            for (kf in frames) {
                val r = try { one(detector, kf) } catch (e: Throwable) { null } ?: continue
                out.add(r.first)
                if (tris.isEmpty()) tris = r.second
            }
        } finally {
            detector.close()
        }
        return Found(out, tris)
    }

    private fun one(detector: com.google.mlkit.vision.facemesh.FaceMeshDetector, kf: Keyframe): Pair<RearFace.Landmarks, IntArray>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(kf.colorRef, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val img = BitmapFactory.decodeFile(kf.colorRef, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val w = img.width; val h = img.height
        // Turn the photo so the world's up points up (the detector wants upright faces).
        val m = kf.worldToCam
        val ux = m[4]; val uy = -m[5]
        val rot = when {
            abs(ux) > abs(uy) -> if (ux > 0) 270 else 90
            else -> if (uy < 0) 0 else 180
        }
        val upright = if (rot == 0) img else Bitmap.createBitmap(img, 0, 0, w, h, Matrix().apply { postRotate(rot.toFloat()) }, true)
        try {
            val meshes = Tasks.await(detector.process(InputImage.fromBitmap(upright, 0)))
            val mesh = meshes.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return null
            val pts = mesh.allPoints
            val n = pts.maxOf { it.index } + 1
            if (n < 468) return null
            val ci = kf.colorIntrinsics
            val sx = ci.width.toFloat() / w; val sy = ci.height.toFloat() / h
            val px = FloatArray(n); val py = FloatArray(n); val pz = FloatArray(n)
            for (p in pts) {
                val a = p.position.x; val b = p.position.y
                // Upright pixel back to the photo's own orientation.
                val (x, y) = when (rot) {
                    90 -> Pair(b, h - 1 - a)
                    180 -> Pair(w - 1 - a, h - 1 - b)
                    270 -> Pair(w - 1 - b, a)
                    else -> Pair(a, b)
                }
                px[p.index] = x * sx; py[p.index] = y * sy; pz[p.index] = p.position.z * sx
            }
            val tris = ArrayList<Int>()
            for (t in mesh.allTriangles) {
                val q = t.allPoints
                if (q.size == 3) { tris.add(q[0].index); tris.add(q[1].index); tris.add(q[2].index) }
            }
            return RearFace.Landmarks(kf, px, py, pz) to tris.toIntArray()
        } finally {
            if (upright !== img) upright.recycle()
            img.recycle()
        }
    }
}
