package com.headscan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.headscan.core.Keyframe
import com.headscan.core.SilhouetteView
import kotlin.math.abs
import kotlin.math.max

/**
 * Finds the person's outline in each Selfie-mode photo with ML Kit's on-device selfie segmentation, for
 * measuring the head (see [com.headscan.core.HeadFit]). Runs on a background thread.
 */
object Silhouettes {
    private const val MAX_SIDE = 256

    fun compute(frames: List<Keyframe>): List<SilhouetteView> {
        val segmenter = Segmentation.getClient(
            SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build())
        try {
            val out = ArrayList<SilhouetteView>()
            for (kf in frames) {
                val view = try { one(segmenter, kf) } catch (e: Throwable) { null }
                if (view != null) out.add(view)
            }
            return out
        } finally {
            segmenter.close()
        }
    }

    private fun one(segmenter: com.google.mlkit.vision.segmentation.Segmenter, kf: Keyframe): SilhouetteView? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(kf.colorRef, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val full = BitmapFactory.decodeFile(kf.colorRef, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = MAX_SIDE.toFloat() / max(full.width, full.height)
        val w = max(1, (full.width * scale).toInt()); val h = max(1, (full.height * scale).toInt())
        val small = Bitmap.createScaledBitmap(full, w, h, true)
        if (small !== full) full.recycle()

        // The segmenter expects an upright person: turn the photo so the face's up direction points up.
        // Face +Y in camera space is column 1 of worldToCam; image v grows downward.
        val m = kf.worldToCam
        val ux = m[4]; val uy = -m[5]
        val rot = when {
            abs(ux) > abs(uy) -> if (ux > 0) 270 else 90      // head top to the right -> turn anticlockwise
            else -> if (uy < 0) 0 else 180
        }
        val upright = if (rot == 0) small else Bitmap.createBitmap(small, 0, 0, w, h, Matrix().apply { postRotate(rot.toFloat()) }, true)
        val result = Tasks.await(segmenter.process(InputImage.fromBitmap(upright, 0)))
        val rw = result.width; val rh = result.height
        val buf = result.buffer
        buf.rewind()
        val conf = FloatArray(rw * rh)
        buf.asFloatBuffer().get(conf)
        if (upright !== small) upright.recycle()
        small.recycle()
        if (rw != (if (rot % 180 == 0) w else h) || rh != (if (rot % 180 == 0) h else w)) return null

        // Back to the photo's own orientation (clockwise rotation by rot maps (x, y) to these upright pixels).
        val mask = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val (rx, ry) = when (rot) {
                90 -> Pair(h - 1 - y, x)
                180 -> Pair(w - 1 - x, h - 1 - y)
                270 -> Pair(y, w - 1 - x)
                else -> Pair(x, y)
            }
            if (conf[ry * rw + rx] > 0.5f) mask[y * w + x] = 1
        }
        return SilhouetteView(mask, w, h, kf.colorIntrinsics.scaledTo(w, h), kf.worldToCam)
    }
}
