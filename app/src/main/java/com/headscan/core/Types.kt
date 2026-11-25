package com.headscan.core

import kotlin.math.sqrt

/**
 * Pinhole camera intrinsics in pixel units.
 *
 * Projection convention (matches ARCore's OpenGL-style camera pose, where the camera
 * looks down -Z, +X is right and +Y is up relative to the image readout):
 *   u = fx * x / -z + cx
 *   v = cy - fy * y / -z
 */
class Intrinsics(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val width: Int,
    val height: Int,
) {
    /** Returns intrinsics rescaled to an image of a different resolution covering the same field of view. */
    fun scaledTo(newWidth: Int, newHeight: Int): Intrinsics {
        val sx = newWidth.toFloat() / width
        val sy = newHeight.toFloat() / height
        return Intrinsics(fx * sx, fy * sy, cx * sx, cy * sy, newWidth, newHeight)
    }
}

/**
 * One captured view of the subject: a metric depth map, a colour photo and the camera pose.
 *
 * Matrices are 4x4, column-major (OpenGL / ARCore `Pose.toMatrix` layout).
 */
class Keyframe(
    val camToWorld: FloatArray,
    val worldToCam: FloatArray,
    /** Depth in millimetres, row-major, 0 = no data. */
    val depthMm: ShortArray,
    val depthWidth: Int,
    val depthHeight: Int,
    val depthIntrinsics: Intrinsics,
    val colorIntrinsics: Intrinsics,
    /** Opaque reference used by a [ColorSource] to load the colour image (e.g. a JPEG path). */
    val colorRef: String,
    /** Blur score of the photo (see [Sharpness]); 0 = unknown. Higher = sharper. */
    val sharpness: Float = 0f,
    /** Extra preference for this photo when texturing (e.g. 1.1 for the full-resolution detail photo). */
    val priority: Float = 1f,
) {
    val camX get() = camToWorld[12]
    val camY get() = camToWorld[13]
    val camZ get() = camToWorld[14]

    /** Depth in metres at integer pixel, or 0 if unknown / out of bounds. */
    fun depthAt(u: Int, v: Int): Float {
        if (u < 0 || v < 0 || u >= depthWidth || v >= depthHeight) return 0f
        return (depthMm[v * depthWidth + u].toInt() and 0xFFFF) * 0.001f
    }
}

/** A decoded ARGB image. */
class RgbImage(val width: Int, val height: Int, val argb: IntArray) {
    /** Bilinear sample at pixel coordinates (pixel centres at +0.5). Writes r,g,b (0..255) into [out]. */
    fun sample(u: Float, v: Float, out: FloatArray): Boolean {
        val x = u - 0.5f
        val y = v - 0.5f
        if (x < 0f || y < 0f || x > width - 1.001f || y > height - 1.001f) return false
        val x0 = x.toInt()
        val y0 = y.toInt()
        val fx = x - x0
        val fy = y - y0
        val i00 = argb[y0 * width + x0]
        val i10 = argb[y0 * width + x0 + 1]
        val i01 = argb[(y0 + 1) * width + x0]
        val i11 = argb[(y0 + 1) * width + x0 + 1]
        val w00 = (1 - fx) * (1 - fy)
        val w10 = fx * (1 - fy)
        val w01 = (1 - fx) * fy
        val w11 = fx * fy
        out[0] = ((i00 shr 16) and 255) * w00 + ((i10 shr 16) and 255) * w10 + ((i01 shr 16) and 255) * w01 + ((i11 shr 16) and 255) * w11
        out[1] = ((i00 shr 8) and 255) * w00 + ((i10 shr 8) and 255) * w10 + ((i01 shr 8) and 255) * w01 + ((i11 shr 8) and 255) * w11
        out[2] = (i00 and 255) * w00 + (i10 and 255) * w10 + (i01 and 255) * w01 + (i11 and 255) * w11
        return true
    }
}

/** Loads the colour photo of a keyframe. Implemented with BitmapFactory on Android. */
fun interface ColorSource {
    fun load(keyframe: Keyframe): RgbImage
}

/** Indexed triangle mesh. */
class Mesh(var positions: FloatArray, var faces: IntArray) {
    val vertexCount get() = positions.size / 3
    val faceCount get() = faces.size / 3
}

/** Growable int list, avoids boxing in hot loops. */
class IntList(capacity: Int = 8) {
    var data = IntArray(capacity)
    var size = 0
    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(maxOf(8, size * 2))
        data[size++] = v
    }
    operator fun get(i: Int) = data[i]
    fun clear() { size = 0 }
    fun toArray(): IntArray = data.copyOf(size)
}

/** Growable float list. */
class FloatList(capacity: Int = 64) {
    var data = FloatArray(capacity)
    var size = 0
    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(maxOf(64, size * 2))
        data[size++] = v
    }
    fun toArray(): FloatArray = data.copyOf(size)
}

internal fun len3(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)
