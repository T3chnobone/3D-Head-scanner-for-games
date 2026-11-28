package com.headscan.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameExportTest {
    private fun tetra(): ScanResult {
        val p = floatArrayOf(0f, 0f, 0.1f, 0.05f, 0f, 0f, -0.05f, 0f, 0f, 0f, 0.1f, 0f)
        val f = intArrayOf(0, 1, 3, 1, 2, 3, 2, 0, 3, 0, 2, 1)
        val m = Mesh(p, f)
        val tex = IntArray(64 * 64) { 0xFF000000.toInt() or (it * 977) }
        return ScanResult(m, MeshOps.vertexNormals(m), false, UvAtlas(f.size / 3, 64), tex, 64)
    }

    @Test
    fun glbIsWellFormed() {
        val r = tetra()
        val png = ByteArray(37) { it.toByte() }
        val glb = GameExport.glb(r, png, "test")
        val b = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546C67, b.getInt(0)); assertEquals(2, b.getInt(4)); assertEquals(glb.size, b.getInt(8))
        val jl = b.getInt(12); assertEquals(0x4E4F534A, b.getInt(16)); assertEquals(0, jl % 4)
        val bl = b.getInt(20 + jl); assertEquals(0x004E4942, b.getInt(24 + jl)); assertEquals(glb.size, 28 + jl + bl)
        val json = String(glb, 20, jl)
        assertTrue(json.contains("\"count\":12"))
        File("build/export").mkdirs(); File("build/export/tetra.glb").writeBytes(glb)
    }

    @Test
    fun sourcePack() {
        val r = tetra()
        val smd = GameExport.smdReference(r, "test")
        assertEquals(4, smd.lines().count { it == "test" })
        // Vertex 0 (0, 0, 0.1 m: the face tip toward +Z) lands on +X in Source units.
        assertTrue(smd.contains("0 3.937010 0.000000 0.000000"), smd)
        val vtf = GameExport.vtf(r.texture, 64)
        var mipBytes = 0; var s = 64; while (s >= 1) { mipBytes += s * s * 3; s /= 2 }
        assertEquals(80 + mipBytes, vtf.size)
        assertEquals(7, vtf.size.let { ByteBuffer.wrap(vtf).order(ByteOrder.LITTLE_ENDIAN).getInt(4) })
        assertEquals(18 + 64 * 64 * 3, GameExport.tga(r.texture, 64).size)
        File("build/export").mkdirs()
        File("build/export/test.smd").writeText(smd); File("build/export/test.vtf").writeBytes(vtf)
        File("build/export/test.qc").writeText(GameExport.qc("test")); File("build/export/test.vmt").writeText(GameExport.vmt("test"))
    }

    @Test
    fun depthIntrinsicsKeepSquarePixels() {
        // 16:9 texture, 4:3 depth: crop then scale evenly.
        val tex = Intrinsics(1400f, 1400f, 960f, 540f, 1920, 1080)
        val d = tex.fittedTo(160, 90)
        assertEquals(d.fx, d.fy); assertEquals(80f, d.cx, 1e-3f); assertEquals(45f, d.cy, 1e-3f)
        val img = Intrinsics(500f, 500f, 320f, 240f, 640, 480)
        val d2 = img.fittedTo(160, 120)
        assertEquals(125f, d2.fx, 1e-3f); assertEquals(60f, d2.cy, 1e-3f)
        assertTrue(img.aspectError(160, 120) < tex.aspectError(160, 120))
    }

    @Test
    fun realHeadPack() {
        val r = SyntheticScanTest().selfie(1500)
        val img = java.awt.image.BufferedImage(r.textureSize, r.textureSize, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, r.textureSize, r.textureSize, r.texture, 0, r.textureSize)
        val png = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "png", it) }.toByteArray()
        File("build/export").mkdirs()
        File("build/export/head.glb").writeBytes(GameExport.glb(r, png, "head"))
        File("build/export/head.smd").writeText(GameExport.smdReference(r, "head"))
        File("build/export/head.vtf").writeBytes(GameExport.vtf(r.texture, r.textureSize))
        File("build/export/head.tga").writeBytes(GameExport.tga(r.texture, r.textureSize))
        File("build/export/head.png").writeBytes(png)
    }
}
