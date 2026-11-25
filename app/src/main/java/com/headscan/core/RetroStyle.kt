package com.headscan.core

/**
 * Late-90s shooter texture look: boosted contrast and saturation, a slight warm tint and
 * posterised colour (fewer shades per channel), like the photo-mapped faces of that era.
 */
object RetroStyle {
    fun apply(argb: IntArray, levels: Int = 24, contrast: Float = 1.25f, saturation: Float = 1.2f): IntArray {
        val out = IntArray(argb.size)
        val step = 255f / (levels - 1)
        for (i in argb.indices) {
            val c = argb[i]
            var r = ((c shr 16) and 255).toFloat()
            var g = ((c shr 8) and 255).toFloat()
            var b = (c and 255).toFloat()
            val l = 0.299f * r + 0.587f * g + 0.114f * b
            r = l + (r - l) * saturation; g = l + (g - l) * saturation; b = l + (b - l) * saturation
            r = (r - 128f) * contrast + 128f + 6f
            g = (g - 128f) * contrast + 128f + 2f
            b = (b - 128f) * contrast + 128f - 4f
            val rr = (Math.round(r.coerceIn(0f, 255f) / step) * step).toInt().coerceIn(0, 255)
            val gg = (Math.round(g.coerceIn(0f, 255f) / step) * step).toInt().coerceIn(0, 255)
            val bb = (Math.round(b.coerceIn(0f, 255f) / step) * step).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        return out
    }
}
