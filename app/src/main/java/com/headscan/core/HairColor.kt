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

    /**
     * [hue] values from this up to +1 select a natural shade instead of a rainbow hue:
     * NATURAL + t picks position t (0..1) along [NATURAL_SHADES], from black to white.
     */
    const val NATURAL = 1000f

    /** Real hair colours, darkest to lightest: black, browns, auburn, ginger, blondes, grey, white. */
    val NATURAL_SHADES = intArrayOf(
        0xFF141110.toInt(), 0xFF2B1D15.toInt(), 0xFF4A3121.toInt(), 0xFF6B4630.toInt(), 0xFF7A3A22.toInt(),
        0xFFA4532B.toInt(), 0xFFB57A52.toInt(), 0xFF9C7E5A.toInt(), 0xFFC9A46A.toInt(), 0xFFE3CFA0.toInt(),
        0xFF8E8A86.toInt(), 0xFFD9D7D3.toInt(),
    )

    fun naturalShade(t: Float): Int {
        val x = t.coerceIn(0f, 1f) * (NATURAL_SHADES.size - 1)
        val i = x.toInt().coerceAtMost(NATURAL_SHADES.size - 2); val f = x - i
        val a = NATURAL_SHADES[i]; val b = NATURAL_SHADES[i + 1]
        fun ch(sh: Int) = (((a shr sh) and 255) * (1 - f) + ((b shr sh) and 255) * f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** The flat hair colour for the given slider values. */
    fun target(base: Int, hue: Float?, tone: Float): Int {
        if (hue != null && hue >= NATURAL) {
            val c = naturalShade(hue - NATURAL)
            // Tone: darker by scaling, lighter by mixing toward white.
            fun ch(sh: Int): Int {
                val v = ((c shr sh) and 255).toFloat()
                return (if (tone < 0) v * (1f + tone * 0.7f) else v + (255f - v) * tone * 0.5f).toInt().coerceIn(0, 255)
            }
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
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
