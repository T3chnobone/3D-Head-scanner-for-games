package com.headscan.core

import java.util.Locale

/** Writes Wavefront OBJ + MTL text for a textured mesh with a per-face UV atlas. */
object ObjWriter {
    /** [normals] are per vertex, or per face when [flat] (flat shading, no smoothing groups). */
    fun obj(mesh: Mesh, normals: FloatArray, atlas: UvAtlas, mtlFileName: String, materialName: String, flat: Boolean = false): String {
        val sb = StringBuilder(mesh.vertexCount * 40 + mesh.faceCount * 120)
        sb.append("# Head scan - ").append(mesh.faceCount).append(" triangles, ")
            .append(mesh.vertexCount).append(" vertices\n")
        sb.append("# Units: metres. +Y up, face looks toward +Z.\n")
        sb.append("mtllib ").append(mtlFileName).append('\n')
        sb.append("o head\n")
        val p = mesh.positions
        for (v in 0 until mesh.vertexCount) {
            sb.append(String.format(Locale.US, "v %.5f %.5f %.5f\n", p[3 * v], p[3 * v + 1], p[3 * v + 2]))
        }
        for (f in 0 until mesh.faceCount) for (c in 0 until 3) {
            sb.append(String.format(Locale.US, "vt %.6f %.6f\n", atlas.objU(f, c), atlas.objV(f, c)))
        }
        val nCount = normals.size / 3
        for (v in 0 until nCount) {
            sb.append(String.format(Locale.US, "vn %.4f %.4f %.4f\n", normals[3 * v], normals[3 * v + 1], normals[3 * v + 2]))
        }
        sb.append("usemtl ").append(materialName).append('\n')
        sb.append(if (flat) "s off\n" else "s 1\n")
        for (f in 0 until mesh.faceCount) {
            sb.append('f')
            for (c in 0 until 3) {
                val v = mesh.faces[3 * f + c] + 1
                val t = 3 * f + c + 1
                val n = if (flat) f + 1 else v
                sb.append(' ').append(v).append('/').append(t).append('/').append(n)
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    fun mtl(materialName: String, textureFileName: String): String = buildString {
        append("# Head scan material\n")
        append("newmtl ").append(materialName).append('\n')
        append("Ka 1.000 1.000 1.000\n")
        append("Kd 1.000 1.000 1.000\n")
        append("Ks 0.000 0.000 0.000\n")
        append("Ns 10.0\n")
        append("d 1.0\n")
        append("illum 1\n")
        append("map_Kd ").append(textureFileName).append('\n')
    }
}
