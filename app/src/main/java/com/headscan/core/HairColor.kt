package com.headscan.core

import kotlin.math.max

/** Recolours the hair area of a texture, keeping its strand light/dark detail. */
object HairColor {
    /**
     * @param hue null = keep the natural (photo) hair colour, else 0..360 degrees around the colour wheel.
     * @param tone -1 (darkest) .. 0 (unchanged) .. +1 (lightest).
     */
    fun recolor(texture: IntArray, mask: FloatArray, base: Int, hue: Float?, tone: Float): IntArray {
        if (hue == null && tone == 0f) return texture
        val baseL = max(8f, luma(base))
        val target = target(base, hue, tone)
        val tr = (target shr 16) and 255; val tg = (target shr 8) and 255; val tb = target and 255
        val out = texture.copyOf()
        for (i in texture.indices) {
            val a = mask[i]
            if (a <= 0.001f) continue
            val c = texture[i]
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            // Keep the strand pattern: scale the target by how light this texel is relative to the hair base.
            val k = (luma(c) / baseL).coerceIn(0.4f, 1.8f)
            val nr = (tr * k).coerceIn(0f, 255f); val ng = (tg * k).coerceIn(0f, 255f); val nb = (tb * k).coerceIn(0f, 255f)
            out[i] = (0xFF shl 24) or
                ((r + (nr - r) * a).toInt().coerceIn(0, 255) shl 16) or
                ((g + (ng - g) * a).toInt().coerceIn(0, 255) shl 8) or
                (b + (nb - b) * a).toInt().coerceIn(0, 255)
        }
        return out
    }

    /** The flat hair colour for the given slider values. */
    fun target(base: Int, hue: Float?, tone: Float): Int {
        if (hue == null) {
            // Natural colour, made darker or lighter.
            val k = if (tone < 0) 1f + tone * 0.75f else 1f + tone * 1.6f
            fun ch(sh: Int) = (((base shr sh) and 255) * k + (if (tone > 0) tone * 40f else 0f)).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        val v = (0.5f + tone * 0.45f).coerceIn(0.05f, 1f)
        val s = (0.8f - max(0f, tone) * 0.35f).coerceIn(0f, 1f)
        return hsv(hue, s, v)
    }

    fun hsv(h: Float, s: Float, v: Float): Int {
        val hh = ((h % 360f) + 360f) % 360f / 60f
        val i = hh.toInt(); val f = hh - i
        val p = v * (1 - s); val q = v * (1 - s * f); val t = v * (1 - s * (1 - f))
        val (r, g, b) = when (i) { 0 -> Triple(v, t, p); 1 -> Triple(q, v, p); 2 -> Triple(p, v, t); 3 -> Triple(p, q, v); 4 -> Triple(t, p, v); else -> Triple(v, p, q) }
        return (0xFF shl 24) or ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
    }

    private fun luma(c: Int) = 0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)
}
