package com.headscan

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.headscan.core.ScanResult
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves <name>.obj, <name>.mtl and <name>_texture.png into Downloads/HeadScans/<name>/. */
object ScanExporter {
    fun defaultName(): String = "HeadScan_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** Makes a user-typed name safe for file names. */
    fun sanitize(input: String): String {
        val cleaned = input.trim().replace(Regex("[^A-Za-z0-9 _-]"), "").replace(' ', '_').take(40)
        return cleaned.ifEmpty { defaultName() }
    }

    class Saved(val location: String, val uris: List<Uri>)

    class Entry(val fileName: String, val mime: String, val write: (OutputStream) -> Unit)

    const val CHECK_NAME = "alignment_check.jpg"

    fun save(context: Context, result: ScanResult, requestedName: String, alignmentCheck: Bitmap? = null): Saved {
        val name = uniqueName(context, sanitize(requestedName))
        val objName = "$name.obj"; val mtlName = "$name.mtl"; val texName = "${name}_texture.png"
        val texture = Bitmap.createBitmap(result.texture, result.textureSize, result.textureSize, Bitmap.Config.ARGB_8888)
        val writers = listOf(
            Entry(objName, "application/octet-stream") { out -> out.write(result.objText(mtlName).toByteArray()) },
            Entry(mtlName, "application/octet-stream") { out -> out.write(result.mtlText(texName).toByteArray()) },
            Entry(texName, "image/png") { out -> texture.compress(Bitmap.CompressFormat.PNG, 100, out) },
        ) + listOfNotNull(alignmentCheck?.let { bmp ->
            Entry(CHECK_NAME, "image/jpeg") { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 90, out) }
        })
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveMediaStore(context, name, writers)
            else saveLegacy(context, name, writers)
        } finally {
            texture.recycle()
            alignmentCheck?.recycle()
        }
    }

    /** Appends _2, _3… if a scan folder with this name already exists (so files never get renamed by Android). */
    private fun uniqueName(context: Context, base: String): String {
        var name = base
        var i = 2
        while (exists(context, name)) name = "${base}_${i++}"
        return name
    }

    private fun exists(context: Context, name: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val path = Environment.DIRECTORY_DOWNLOADS + "/HeadScans/" + name + "/"
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                arrayOf(path), null,
            )?.use { return it.count > 0 }
            return false
        }
        return File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "HeadScans/$name").exists()
    }

    private fun saveMediaStore(context: Context, name: String, files: List<Entry>): Saved {
        val resolver = context.contentResolver
        val relativePath = Environment.DIRECTORY_DOWNLOADS + "/HeadScans/" + name
        val uris = ArrayList<Uri>()
        for (e in files) {
            val fileName = e.fileName; val mime = e.mime; val write = e.write
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create $fileName in Downloads")
            resolver.openOutputStream(uri)!!.use { write(it) }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uris.add(uri)
        }
        return Saved("Downloads/HeadScans/$name", uris)
    }

    private fun saveLegacy(context: Context, name: String, files: List<Entry>): Saved {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "HeadScans/$name")
        dir.mkdirs()
        val uris = ArrayList<Uri>()
        for (e in files) {
            val fileName = e.fileName; val write = e.write
            val f = File(dir, fileName)
            f.outputStream().use { write(it) }
            uris.add(FileProvider.getUriForFile(context, context.packageName + ".files", f))
        }
        return Saved(dir.absolutePath, uris)
    }
}
