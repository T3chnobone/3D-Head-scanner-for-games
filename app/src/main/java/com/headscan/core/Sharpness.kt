package com.headscan.core

import kotlin.math.max

/**
 * Blur metric: variance of the Laplacian of the luminance, measured at a fixed working scale
 * (about 640 px wide) over the central part of the frame, so photos of different resolutions compare fairly.
 * Motion blur and missed focus give low values.
 */
object Sharpness {
    const val WORK_WIDTH = 640

    /** [luma] is an 8-bit luminance plane (e.g. the first w*h bytes of NV21), [rowStride] bytes per row. */
    fun of(luma: ByteArray, width: Int, height: Int, rowStride: Int = width): Float {
        val f = max(1, width / WORK_WIDTH)
        val w = width / f; val h = height / f
        if (w < 8 || h < 8) return 0f
        val img = FloatArray(w * h)
        val inv = 1f / (f * f)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0
            for (dy in 0 until f) {
                val row = (y * f + dy) * rowStride + x * f
                for (dx in 0 until f) s += luma[row + dx].toInt() and 0xFF
            }
            img[y * w + x] = s * inv
        }
        return laplacianVariance(img, w, h)
    }

    /** Same metric on an ARGB image. */
    fun of(image: RgbImage): Float {
        val luma = ByteArray(image.width * image.height)
        for (i in luma.indices) {
            val c = image.argb[i]
            luma[i] = ((77 * ((c shr 16) and 255) + 150 * ((c shr 8) and 255) + 29 * (c and 255)) shr 8).toByte()
        }
        return of(luma, image.width, image.height)
    }

    private fun laplacianVariance(img: FloatArray, w: Int, h: Int): Float {
        // Central 60 % of the frame: where the head is.
        val x0 = max(1, w / 5); val x1 = w - max(1, w / 5)
        val y0 = max(1, h / 5); val y1 = h - max(1, h / 5)
        var sum = 0.0; var sum2 = 0.0; var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val i = y * w + x
            val l = img[i - 1] + img[i + 1] + img[i - w] + img[i + w] - 4 * img[i]
            sum += l; sum2 += l * l; n++
        }
        if (n == 0) return 0f
        val mean = sum / n
        return (sum2 / n - mean * mean).toFloat()
    }
}
