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

    fun load(context: Context, uri: Uri, workDir: File): PhotoFace.Result {
        val bmp = decodeUpright(context, uri) ?: throw ScanException("Couldn't open that picture.")
        val file = File(workDir, "photo_import.jpg")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        val detector = FaceMeshDetection.getClient(
            FaceMeshDetectorOptions.Builder().setUseCase(FaceMeshDetectorOptions.FACE_MESH).build())
        try {
            val meshes = Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0)))
            val mesh = meshes.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                ?: throw ScanException("No face found in that picture. Pick a clear photo of a face looking at the camera.")
            val pts = mesh.allPoints
            val n = pts.maxOf { it.index } + 1
            val px = FloatArray(n); val py = FloatArray(n); val pz = FloatArray(n)
            for (p in pts) { px[p.index] = p.position.x; py[p.index] = p.position.y; pz[p.index] = p.position.z }
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
