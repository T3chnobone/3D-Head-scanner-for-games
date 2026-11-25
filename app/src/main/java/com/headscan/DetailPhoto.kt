package com.headscan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.headscan.core.ImageRegistration
import com.headscan.core.Keyframe
import com.headscan.core.RgbImage
import com.headscan.core.Sharpness
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The full-resolution "detail photo" taken when the user taps Finish.
 *
 * ARCore only hands out small tracking frames, so once the scan is done ARCore is paused, the same camera is
 * opened with CameraX and one maximum-quality still is taken. The still is then lined up with the last tracked
 * frame (same spot, so same pose) by image registration, and becomes an extra, preferred keyframe for the texture.
 */
object DetailPhoto {
    private const val TIMEOUT_MS = 9000L
    private const val MAX_SIDE = 2600
    const val PRIORITY = 1.1f

    class Shot(val jpeg: ByteArray, /** Clockwise rotation the camera already applied to the pixels, vs the sensor. */ val appliedRotation: Int)

    /** Takes the photo on the main thread; [onDone] gets null on any failure or after a timeout. */
    @OptIn(ExperimentalCamera2Interop::class)
    fun capture(activity: AppCompatActivity, front: Boolean, cameraId: String?, onDone: (Shot?) -> Unit) {
        val main = ContextCompat.getMainExecutor(activity)
        val done = AtomicBoolean(false)
        var provider: ProcessCameraProvider? = null
        fun finish(shot: Shot?) {
            if (!done.compareAndSet(false, true)) return
            try { provider?.unbindAll() } catch (_: Exception) { }
            onDone(shot)
        }
        Handler(Looper.getMainLooper()).postDelayed({ finish(null) }, TIMEOUT_MS)
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                if (done.get()) { p.unbindAll(); return@addListener }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                    .setJpegQuality(95)
                    .setTargetRotation(Surface.ROTATION_0)
                    .build()
                val selector = CameraSelector.Builder()
                    .requireLensFacing(if (front) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK)
                    .addCameraFilter { infos ->
                        val same = infos.filter { cameraId != null && Camera2CameraInfo.from(it).cameraId == cameraId }
                        same.ifEmpty { infos }
                    }
                    .build()
                p.unbindAll()
                val camera = p.bindToLifecycle(activity, selector, capture)
                val sensorDeg = camera.cameraInfo.sensorRotationDegrees
                capture.takePicture(main, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val shot = try {
                            if (image.format != ImageFormat.JPEG) null
                            else {
                                val buf = image.planes[0].buffer
                                buf.rewind()
                                val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                                Shot(bytes, (((sensorDeg - image.imageInfo.rotationDegrees) % 360) + 360) % 360)
                            }
                        } catch (e: Exception) {
                            null
                        } finally {
                            image.close()
                        }
                        finish(shot)
                    }

                    override fun onError(exception: ImageCaptureException) = finish(null)
                })
            } catch (e: Exception) {
                finish(null)
            }
        }, main)
    }

    /**
     * Lines the shot up with [anchor] (the last tracked frame, taken from the same spot) and returns it as a
     * keyframe with the anchor's pose, or null if it can't be matched (e.g. the phone or head moved).
     * Runs on a background thread.
     */
    fun toKeyframe(shot: Shot, anchor: Keyframe, workDir: File): Keyframe? {
        val still = decodeStill(shot) ?: return null
        val anchorImg = decodeScaled(anchor.colorRef, 640) ?: return null
        val reg = ImageRegistration.register(anchorImg, still) ?: return null
        ImageRegistration.applyGain(still, reg.gain)
        val intr = reg.stillIntrinsics(anchor.colorIntrinsics, anchorImg.width, anchorImg.height, still.width, still.height)
        val file = File(workDir, "detail.jpg")
        val bmp = Bitmap.createBitmap(still.argb, still.width, still.height, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        return Keyframe(
            anchor.camToWorld, anchor.worldToCam, anchor.depthMm, anchor.depthWidth, anchor.depthHeight,
            anchor.depthIntrinsics, intr, file.absolutePath,
            sharpness = Sharpness.of(still), priority = PRIORITY,
        )
    }

    /** Decodes the still at up to [MAX_SIDE] px, undoing any rotation so it is in sensor orientation like ARCore frames. */
    private fun decodeStill(shot: Shot): RgbImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(shot.jpeg, 0, shot.jpeg.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(shot.jpeg, 0, shot.jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        if (shot.appliedRotation != 0) {
            val m = Matrix().apply { postRotate(-shot.appliedRotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            bmp.recycle()
            bmp = rotated
        }
        return toRgb(bmp)
    }

    private fun decodeScaled(path: String, maxWidth: Int): RgbImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxWidth) sample *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return toRgb(bmp)
    }

    private fun toRgb(bmp: Bitmap): RgbImage {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return RgbImage(bmp.width, bmp.height, px).also { bmp.recycle() }
    }
}
