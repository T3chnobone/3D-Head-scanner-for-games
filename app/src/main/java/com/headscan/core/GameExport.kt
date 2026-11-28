package com.headscan.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.max

/**
 * Other save formats: glTF binary (.glb) and a Half-Life 2 / Source engine pack (SMD reference + idle SMD, QC,
 * VMT, VTF and TGA). Plain Kotlin so it runs in unit tests; PNG encoding is passed in by the caller.
 */
object GameExport {
    /** 1 Source unit = 1 inch. */
    const val SOURCE_UNITS_PER_METRE = 39.3701f

    /** The normal used at corner [c] of face [f], the same rule as [ObjWriter]. */
    fun cornerNormals(r: ScanResult): FloatArray {
        val mesh = r.mesh
        val out = FloatArray(mesh.faceCount * 9)
        val fn = if (r.smoothFaces != null) MeshOps.faceNormals(mesh) else null
        for (f in 0 until mesh.faceCount) for (c in 0 until 3) {
            val v = mesh.faces[3 * f + c]
            val (src, i) = when {
                fn != null -> if (r.smoothFaces!![f]) r.normals to v else fn to f
                r.flatShaded -> r.normals to f
                else -> r.normals to v
            }
            val o = (3 * f + c) * 3
            out[o] = src[3 * i]; out[o + 1] = src[3 * i + 1]; out[o + 2] = src[3 * i + 2]
        }
        return out
    }

    // ---------------------------------------------------------------- glTF

    /** A self-contained .glb: metres, +Y up, face toward +Z (glTF's own convention), texture embedded as PNG. */
    fun glb(r: ScanResult, png: ByteArray, name: String = "head"): ByteArray {
        val mesh = r.mesh
        val n = mesh.faceCount * 3
        val nrm = cornerNormals(r)
        val pos = FloatArray(n * 3); val uv = FloatArray(n * 2)
        val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val maxv = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (f in 0 until mesh.faceCount) for (c in 0 until 3) {
            val k = 3 * f + c; val v = mesh.faces[k]
            for (a in 0 until 3) {
                val x = mesh.positions[3 * v + a]; pos[3 * k + a] = x
                if (x < min[a]) min[a] = x; if (x > maxv[a]) maxv[a] = x
            }
            uv[2 * k] = r.atlas.objU(f, c); uv[2 * k + 1] = 1f - r.atlas.objV(f, c)   // glTF: v = 0 at the top
        }
        // Normalise normals (glTF validators insist).
        for (k in 0 until n) {
            val x = nrm[3 * k]; val y = nrm[3 * k + 1]; val z = nrm[3 * k + 2]
            val l = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
            if (l > 1e-8f) { nrm[3 * k] = x / l; nrm[3 * k + 1] = y / l; nrm[3 * k + 2] = z / l } else { nrm[3 * k + 1] = 1f }
        }
        val posBytes = n * 12; val uvBytes = n * 8
        val imgOff = pad4(posBytes * 2 + uvBytes)
        val binLen = pad4(imgOff + png.size)
        val bin = ByteBuffer.allocate(binLen).order(ByteOrder.LITTLE_ENDIAN)
        for (x in pos) bin.putFloat(x)
        for (x in nrm) bin.putFloat(x)
        for (x in uv) bin.putFloat(x)
        bin.position(imgOff); bin.put(png)

        fun f(x: Float) = String.format(Locale.US, "%.6f", x)
        val safe = name.replace("\"", "")
        val json = """{"asset":{"version":"2.0","generator":"3D Head Scanner"},"scene":0,"scenes":[{"nodes":[0]}],""" +
            """"nodes":[{"name":"$safe","mesh":0}],""" +
            """"meshes":[{"name":"$safe","primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"TEXCOORD_0":2},"material":0,"mode":4}]}],""" +
            """"materials":[{"name":"${HeadScanPipeline.MATERIAL}","pbrMetallicRoughness":{"baseColorTexture":{"index":0},"metallicFactor":0.0,"roughnessFactor":0.9}}],""" +
            """"textures":[{"source":0,"sampler":0}],"samplers":[{"magFilter":9729,"minFilter":9987,"wrapS":33071,"wrapT":33071}],""" +
            """"images":[{"bufferView":3,"mimeType":"image/png"}],""" +
            """"accessors":[""" +
            """{"bufferView":0,"componentType":5126,"count":$n,"type":"VEC3","min":[${f(min[0])},${f(min[1])},${f(min[2])}],"max":[${f(maxv[0])},${f(maxv[1])},${f(maxv[2])}]},""" +
            """{"bufferView":1,"componentType":5126,"count":$n,"type":"VEC3"},""" +
            """{"bufferView":2,"componentType":5126,"count":$n,"type":"VEC2"}],""" +
            """"bufferViews":[""" +
            """{"buffer":0,"byteOffset":0,"byteLength":$posBytes,"target":34962},""" +
            """{"buffer":0,"byteOffset":$posBytes,"byteLength":$posBytes,"target":34962},""" +
            """{"buffer":0,"byteOffset":${posBytes * 2},"byteLength":$uvBytes,"target":34962},""" +
            """{"buffer":0,"byteOffset":$imgOff,"byteLength":${png.size}}],""" +
            """"buffers":[{"byteLength":$binLen}]}"""
        var jsonBytes = json.toByteArray(Charsets.UTF_8)
        val jsonLen = pad4(jsonBytes.size)
        jsonBytes = jsonBytes.copyOf(jsonLen).also { for (i in json.toByteArray().size until jsonLen) it[i] = ' '.code.toByte() }
        val total = 12 + 8 + jsonLen + 8 + binLen
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67); out.putInt(2); out.putInt(total)
        out.putInt(jsonLen); out.putInt(0x4E4F534A); out.put(jsonBytes)
        out.putInt(binLen); out.putInt(0x004E4942); out.put(bin.array())
        return out.array()
    }

    private fun pad4(x: Int) = (x + 3) and 3.inv()

    // ---------------------------------------------------------------- Source engine

    /** Head frame (Y up, face +Z, metres) to Source (Z up, face +X, inches). A rotation, so winding is kept. */
    private fun toSource(x: Float, y: Float, z: Float, scale: Float) = floatArrayOf(z * scale, x * scale, y * scale)

    /** Reference SMD: one bone, every triangle with material [material]. */
    fun smdReference(r: ScanResult, material: String): String {
        val mesh = r.mesh
        val nrm = cornerNormals(r)
        val sb = StringBuilder(mesh.faceCount * 260)
        sb.append("version 1\nnodes\n0 \"root\" -1\nend\nskeleton\ntime 0\n0 0.000000 0.000000 0.000000 0.000000 0.000000 0.000000\nend\ntriangles\n")
        val p = mesh.positions
        for (f in 0 until mesh.faceCount) {
            sb.append(material).append('\n')
            for (c in 0 until 3) {
                val v = mesh.faces[3 * f + c]; val k = 3 * f + c
                val q = toSource(p[3 * v], p[3 * v + 1], p[3 * v + 2], SOURCE_UNITS_PER_METRE)
                val nn = toSource(nrm[3 * k], nrm[3 * k + 1], nrm[3 * k + 2], 1f)
                sb.append(String.format(Locale.US, "0 %.6f %.6f %.6f %.6f %.6f %.6f %.6f %.6f\n",
                    q[0], q[1], q[2], nn[0], nn[1], nn[2], r.atlas.objU(f, c), r.atlas.objV(f, c)))
            }
        }
        sb.append("end\n")
        return sb.toString()
    }

    /** The idle animation studiomdl needs: one frame of the root bone at rest. */
    fun smdIdle(): String =
        "version 1\nnodes\n0 \"root\" -1\nend\nskeleton\ntime 0\n0 0.000000 0.000000 0.000000 0.000000 0.000000 0.000000\nend\n"

    /** Where the materials go under the game folder, e.g. materials/models/headscans/<name>/. */
    fun materialDir(name: String) = "models/headscans/$name/"

    fun qc(name: String): String = buildString {
        append("// Compile with studiomdl (Source SDK): studiomdl.exe -game \"<path to hl2>\" $name.qc\n")
        append("// Copy the materials folder into your game folder first (hl2/ or your mod).\n")
        append("\$modelname \"headscans/$name.mdl\"\n")
        append("\$body \"head\" \"$name.smd\"\n")
        append("\$cdmaterials \"${materialDir(name)}\"\n")
        append("\$surfaceprop \"flesh\"\n")
        append("\$staticprop\n")
        append("\$sequence \"idle\" \"${name}_idle.smd\" fps 30\n")
        append("\$collisionmodel \"$name.smd\" {\n    \$mass 5\n}\n")
    }

    fun vmt(name: String): String =
        "\"VertexLitGeneric\"\n{\n\t\"\$basetexture\" \"${materialDir(name)}$name\"\n\t\"\$surfaceprop\" \"flesh\"\n}\n"

    /** 24-bit uncompressed TGA (bottom-up rows), for tools such as vtex or VTFEdit. */
    fun tga(argb: IntArray, size: Int): ByteArray {
        val out = ByteArray(18 + size * size * 3)
        out[2] = 2
        out[12] = (size and 255).toByte(); out[13] = (size shr 8).toByte()
        out[14] = (size and 255).toByte(); out[15] = (size shr 8).toByte()
        out[16] = 24
        var o = 18
        for (y in size - 1 downTo 0) for (x in 0 until size) {
            val c = argb[y * size + x]
            out[o++] = (c and 255).toByte(); out[o++] = ((c shr 8) and 255).toByte(); out[o++] = ((c shr 16) and 255).toByte()
        }
        return out
    }

    /**
     * VTF 7.2, BGR888 (uncompressed, so no colour loss), with a full mipmap chain. Source loads it directly;
     * no vtex step is needed.
     */
    fun vtf(argb: IntArray, size: Int): ByteArray {
        require(size > 0 && size and (size - 1) == 0) { "VTF needs a power-of-two texture" }
        val mips = ArrayList<IntArray>()
        mips.add(argb)
        var s = size
        while (s > 1) {
            val prev = mips.last(); val h = s / 2
            val next = IntArray(h * h)
            for (y in 0 until h) for (x in 0 until h) {
                var r = 0; var g = 0; var b = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val c = prev[(2 * y + dy) * s + 2 * x + dx]
                    r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255
                }
                next[y * h + x] = (0xFF shl 24) or ((r / 4) shl 16) or ((g / 4) shl 8) or (b / 4)
            }
            mips.add(next); s = h
        }
        var dataLen = 0
        for (m in mips) dataLen += m.size * 3
        val buf = ByteBuffer.allocate(80 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put('V'.code.toByte()); buf.put('T'.code.toByte()); buf.put('F'.code.toByte()); buf.put(0)
        buf.putInt(7); buf.putInt(2)              // version 7.2
        buf.putInt(80)                            // header size
        buf.putShort(size.toShort()); buf.putShort(size.toShort())
        buf.putInt(0x0100 or 0x0200)              // flags: NOMIP off; CLAMPS | CLAMPT
        buf.putShort(1); buf.putShort(0)          // frames, first frame
        buf.put(ByteArray(4))
        var rr = 0.0; var gg = 0.0; var bb = 0.0
        val last = mips[max(0, mips.size - 1)][0]
        rr = ((last shr 16) and 255) / 255.0; gg = ((last shr 8) and 255) / 255.0; bb = (last and 255) / 255.0
        buf.putFloat(Math.pow(rr, 2.2).toFloat()); buf.putFloat(Math.pow(gg, 2.2).toFloat()); buf.putFloat(Math.pow(bb, 2.2).toFloat())
        buf.put(ByteArray(4))
        buf.putFloat(1f)                          // bumpmap scale
        buf.putInt(3)                             // high-res format: BGR888
        buf.put(mips.size.toByte())
        buf.putInt(-1)                            // no low-res thumbnail
        buf.put(0); buf.put(0)
        buf.putShort(1)                           // depth
        buf.position(80)
        for (m in mips.indices.reversed()) for (c in mips[m]) {   // smallest mip first
            buf.put((c and 255).toByte()); buf.put(((c shr 8) and 255).toByte()); buf.put(((c shr 16) and 255).toByte())
        }
        return buf.array()
    }

    fun sourceReadme(name: String): String = """Half-Life 2 / Source engine pack for "$name"

1. Copy the "materials" folder into your game folder (for example ...\Half-Life 2\hl2\ or your mod's folder),
   so the files end up in materials/${materialDir(name)}.
   $name.vtf is ready to use. $name.tga is the same texture if you want to re-make the VTF yourself (VTFEdit or vtex).
2. Compile the model with studiomdl from the Source SDK:
     studiomdl.exe -game "<path to hl2 folder>" $name.qc
   It is written to models/headscans/$name.mdl.
3. In Hammer use it as prop_static or prop_physics, or open it in the model viewer (HLMV).

The model is in Source units (1 unit = 1 inch), Z up, facing +X.
"""

    @Suppress("unused") private fun bytes(write: (ByteArrayOutputStream) -> Unit) = ByteArrayOutputStream().also(write).toByteArray()
}
