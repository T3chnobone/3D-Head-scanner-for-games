package com.headscan.core

import kotlin.math.max
import kotlin.math.min

/**
 * Mid-2000s Source-engine character look (as in the GoldenEye: Source mod): smooth-shaded head with a
 * crisp, slightly desaturated and warm photo texture that has soft lighting and ambient occlusion
 * baked into it (darker eye sockets, nostrils, under the chin and jaw).
 */
object SourceStyle {

    /** Per-vertex ambient occlusion estimate (1 = open, lower = in a crease), from local concavity. */
    fun vertexOcclusion(mesh: Mesh, normals: FloatArray): FloatArray {
        val nb = MeshOps.neighbors(mesh)
        val p = mesh.positions
        val raw = FloatArray(mesh.vertexCount)
        for (v in 0 until mesh.vertexCount) {
            var sum = 0f; var n = 0
            for (u in nb[v]) {
                val dx = p[3 * u] - p[3 * v]; val dy = p[3 * u + 1] - p[3 * v + 1]; val dz = p[3 * u + 2] - p[3 * v + 2]
                val l = len3(dx, dy, dz)
                if (l < 1e-9f) continue
                sum += (dx * normals[3 * v] + dy * normals[3 * v + 1] + dz * normals[3 * v + 2]) / l
                n++
            }
            raw[v] = if (n > 0) sum / n else 0f     // > 0: neighbours rise in front of the surface = concave
        }
        // Blur twice over the mesh so the occlusion is soft, like a baked AO pass.
        var ao = raw
        repeat(2) {
            val next = FloatArray(ao.size)
            for (v in ao.indices) {
                var s = ao[v]; for (u in nb[v]) s += ao[u]
                next[v] = s / (nb[v].size + 1)
            }
            ao = next
        }
        return FloatArray(ao.size) { (1f - 2.2f * max(0f, ao[it])).coerceIn(0.55f, 1f) }
    }

    /** Soft top-down key light times occlusion. */
    fun shade(normalY: Float, ao: Float): Float = (0.86f + 0.14f * normalY) * ao

    /** Colour grade + sharpening for that crisp mid-2000s texture look. Modifies [argb] in place. */
    fun grade(argb: IntArray, size: Int) {
        val src = argb.copyOf()
        for (y in 0 until size) for (x in 0 until size) {
            val i = y * size + x
            var r = 0f; var g = 0f; var b = 0f
            // 3x3 box blur for the unsharp mask
            for (dy in -1..1) for (dx in -1..1) {
                val c = src[min(size - 1, max(0, y + dy)) * size + min(size - 1, max(0, x + dx))]
                r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255
            }
            r /= 9f; g /= 9f; b /= 9f
            val c = src[i]
            var cr = ((c shr 16) and 255).toFloat(); var cg = ((c shr 8) and 255).toFloat(); var cb = (c and 255).toFloat()
            val amount = 0.7f
            cr += (cr - r) * amount; cg += (cg - g) * amount; cb += (cb - b) * amount
            // slight desaturation + warm skin tint
            val l = 0.299f * cr + 0.587f * cg + 0.114f * cb
            cr = l + (cr - l) * 0.88f + 5f
            cg = l + (cg - l) * 0.88f + 1f
            cb = l + (cb - l) * 0.88f - 5f
            argb[i] = (0xFF shl 24) or (curve(cr) shl 16) or (curve(cg) shl 8) or curve(cb)
        }
    }

    /** Gentle S-curve contrast. */
    private fun curve(v: Float): Int {
        val x = (v / 255f).coerceIn(0f, 1f)
        val s = x * x * (3f - 2f * x)
        return ((x * 0.6f + s * 0.4f) * 255f).toInt().coerceIn(0, 255)
    }
}
