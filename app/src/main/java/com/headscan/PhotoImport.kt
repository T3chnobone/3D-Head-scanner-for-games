package com.headscan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.facemesh.FaceMeshDetection
import com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions
import com.headscan.core.PhotoFace
import com.headscan.core.ScanException
import java.io.File
import kotlin.math.max

/**
 * Image mode: loads a photo from the library, finds the face mesh in it with ML Kit's on-device face mesh detector
 * and turns it into the same input Selfie mode produces. Runs on a background thread.
 */
object PhotoImport {
    private const val MAX_SIDE = 1600

    fun load(context: Context, uri: Uri, workDir: File, step: (String) -> Unit = {}): PhotoFace.Result {
        step("opening the picture")
        val decoded = decodeUpright(context, uri) ?: throw ScanException("Couldn't open that picture.")
        // Plain 8-bit colour: HDR / wide-colour photos can decode to formats the face detector can't take.
        val bmp = if (decoded.config == Bitmap.Config.ARGB_8888) decoded else decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() }
            ?: throw ScanException("Couldn't read that picture's colours.")
        val file = File(workDir, "photo_import.jpg")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        step("finding the face in the picture")
        val detector = FaceMeshDetection.getClient(
            FaceMeshDetectorOptions.Builder().setUseCase(FaceMeshDetectorOptions.FACE_MESH).build())
        try {
            // The detector gets a smaller copy (less memory); points are scaled back to the full picture.
            val k = minOf(1f, 960f / max(bmp.width, bmp.height))
            val small = if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true) else bmp
            val meshes = try { Tasks.await(detector.process(InputImage.fromBitmap(small, 0))) } finally { if (small !== bmp) small.recycle() }
            val mesh = meshes.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                ?: throw ScanException("No face found in that picture. Pick a clear photo of a face looking at the camera.")
            val pts = mesh.allPoints
            val n = pts.maxOf { it.index } + 1
            val px = FloatArray(n); val py = FloatArray(n); val pz = FloatArray(n)
            for (p in pts) { px[p.index] = p.position.x / k; py[p.index] = p.position.y / k; pz[p.index] = p.position.z / k }
            val tris = ArrayList<Int>()
            for (t in mesh.allTriangles) {
                val a = t.allPoints
                if (a.size == 3) { tris.add(a[0].index); tris.add(a[1].index); tris.add(a[2].index) }
            }
            if (tris.isEmpty()) throw ScanException("The face in that picture couldn't be read.")
            return PhotoFace.build(px, py, pz, tris.toIntArray(), bmp.width, bmp.height, file.absolutePath)
        } finally {
            detector.close()
            bmp.recycle()
        }
    }

    /** Decodes the picture the right way up (camera photos store their rotation separately), at most 1600 px. */
    private fun decodeUpright(context: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
                val s = info.size
                val big = max(s.width, s.height)
                if (big > MAX_SIDE) decoder.setTargetSize(s.width * MAX_SIDE / big, s.height * MAX_SIDE / big)
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
