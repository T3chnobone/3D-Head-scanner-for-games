package com.headscan.core

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class DetailPhotoTest {

    /** A textured test scene: blobs and stripes, like a face in a room. */
    private fun scene(w: Int, h: Int, seed: Int = 3): RgbImage {
        val rnd = Random(seed)
        val blobs = List(60) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), 0.02f + 0.08f * rnd.nextFloat(), rnd.nextFloat() * 255, rnd.nextFloat() * 255, rnd.nextFloat() * 255) }
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val u = x.toFloat() / w; val v = y.toFloat() / h
            var r = 120f + 40f * sin(u * 23f) * cos(v * 17f); var g = 110f + 30f * sin(v * 31f + u * 7f); var b = 100f + 30f * cos(u * 13f - v * 9f)
            for (bl in blobs) {
                val dx = (u - bl[0]) * w / h; val dy = v - bl[1]
                if (dx * dx + dy * dy < bl[2] * bl[2]) { r = bl[3]; g = bl[4]; b = bl[5] }
            }
            px[y * w + x] = (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
        }
        return RgbImage(w, h, px)
    }

    /** Renders the anchor (low-res tracked frame) from the still under a known mapping, with 4x4 supersampling. */
    private fun anchorFrom(still: RgbImage, aw: Int, ah: Int, s: Float, tx: Float, ty: Float, mx: Boolean, gain: Float, noise: Float): RgbImage {
        val rnd = Random(9)
        val tmp = FloatArray(3)
        val px = IntArray(aw * ah)
        val sx = if (mx) -s else s
        for (y in 0 until ah) for (x in 0 until aw) {
            var r = 0f; var g = 0f; var b = 0f; var n = 0
            for (j in 0 until 4) for (i in 0 until 4) {
                val au = x + (i + 0.5f) / 4f; val av = y + (j + 0.5f) / 4f
                if (still.sample(sx * au + tx, s * av + ty, tmp)) { r += tmp[0]; g += tmp[1]; b += tmp[2]; n++ }
            }
            if (n == 0) { px[y * aw + x] = 0xFF000000.toInt(); continue }
            fun c(v: Float) = (v / n * gain + (rnd.nextFloat() - 0.5f) * noise).toInt().coerceIn(0, 255)
            px[y * aw + x] = (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
        }
        return RgbImage(aw, ah, px)
    }

    @Test
    fun registersMirroredScaledShiftedStill() {
        val still = scene(1600, 1200)
        val aw = 480; val ah = 270
        val s = 1600f / aw * 0.93f
        val mx = true
        // centred, then shifted
        val tx = 800f + s * aw / 2f + 45f
        val ty = 600f - s * ah / 2f - 30f
        val anchor = anchorFrom(still, aw, ah, s, tx, ty, mx, gain = 0.85f, noise = 6f)
        val t0 = System.nanoTime()
        val r = ImageRegistration.register(anchor, still)
        println("registration took ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertNotNull(r, "registration failed")
        println("scale ${r.scale} (true $s) tx ${r.tx} (true $tx) ty ${r.ty} (true $ty) mirrorX ${r.mirrorX} mirrorY ${r.mirrorY} ncc ${r.score} gain ${r.gain.toList()}")
        assertTrue(r.mirrorX); assertTrue(!r.mirrorY)
        var maxErr = 0f
        for ((u, v) in listOf(50f to 40f, 430f to 40f, 240f to 135f, 50f to 230f, 430f to 230f)) {
            val eu = abs(r.mapU(u) - ((-s) * u + tx)); val ev = abs(r.mapV(v) - (s * v + ty))
            maxErr = maxOf(maxErr, eu, ev)
        }
        println("max mapping error ${"%.2f".format(maxErr)} still px (= ${"%.2f".format(maxErr / s)} anchor px)")
        assertTrue(maxErr < 4f, "mapping error $maxErr px")
        for (k in 0 until 3) assertEquals(0.85f, r.gain[k], 0.05f)

        // Intrinsics of the still project like the mapping says.
        val ai = Intrinsics(400f, 400f, 240f, 135f, aw, ah)
        val si = r.stillIntrinsics(ai, aw, ah, still.width, still.height)
        val x = 0.03f; val y = -0.02f; val z = 0.4f
        val ua = ai.fx * x / z + ai.cx; val va = ai.cy - ai.fy * y / z
        assertEquals(r.mapU(ua), si.fx * x / z + si.cx, 1e-2f)
        assertEquals(r.mapV(va), si.cy - si.fy * y / z, 1e-2f)
    }

    @Test
    fun rejectsUnrelatedPhoto() {
        val still = scene(1200, 900, seed = 5)
        val anchor = anchorFrom(scene(1200, 900, seed = 77), 400, 300, 3f, 0f, 0f, false, 1f, 4f)
        val r = ImageRegistration.register(anchor, still)
        println("unrelated photo: ${r?.score}")
        assertTrue(r == null)
    }

    @Test
    fun blurScoresLower() {
        val sharp = scene(1280, 720)
        val blurred = RgbImage(sharp.width, sharp.height, IntArray(sharp.argb.size))
        val w = sharp.width; val h = sharp.height; val rad = 4
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0; var g = 0; var b = 0; var n = 0
            for (dx in -rad..rad) { val xx = (x + dx).coerceIn(0, w - 1); val c = sharp.argb[y * w + xx]; r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255; n++ }
            blurred.argb[y * w + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
        val a = Sharpness.of(sharp); val b = Sharpness.of(blurred)
        println("sharpness: sharp $a, motion-blurred $b")
        assertTrue(a > 2 * b)
        val q = TextureBaker.qualities(listOf(kf(a), kf(b)))
        assertEquals(1f, q[0], 1e-6f); assertTrue(q[1] < 0.75f)
    }

    private fun kf(sharp: Float): Keyframe {
        val id = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }
        val i = Intrinsics(1f, 1f, 0f, 0f, 1, 1)
        return Keyframe(id, id, ShortArray(1), 1, 1, i, i, "", sharpness = sharp)
    }
}
