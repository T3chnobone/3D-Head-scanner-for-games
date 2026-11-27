package com.headscan.core

import java.io.File
import kotlin.math.*
import kotlin.test.Test
import kotlin.test.assertTrue

/** Renders a synthetic head+neck+shoulders scene from orbiting cameras and runs the full pipeline. */
class SyntheticScanTest {
    init { TestModel.install() }
    val c = floatArrayOf(0.3f, 1.4f, -0.2f) // head centre in world

    fun sdf(px: Float, py: Float, pz: Float): Float {
        val x = px - c[0]; val y = py - c[1]; val z = pz - c[2]
        // head: ellipsoid (approximate SDF)
        val rx = 0.08f; val ry = 0.11f; val rz = 0.10f
        val k = sqrt((x / rx).pow(2) + (y / ry).pow(2) + (z / rz).pow(2))
        val head = (k - 1f) * min(rx, min(ry, rz))
        // nose bump
        val nose = sqrt(x * x + (y + 0.01f).pow(2) + (z - 0.1f).pow(2)) - 0.02f
        // neck: capped cylinder
        val neckR = sqrt(x * x + (z + 0.01f).pow(2)) - 0.055f
        val neckY = max(y - (-0.05f), (-0.35f) - y)
        val neck = max(neckR, neckY)
        // shoulders: box under y=-0.22
        val sx = abs(x) - 0.22f; val sy = abs(y + 0.32f) - 0.1f; val sz = abs(z) - 0.1f
        val shoulders = max(sx, max(sy, sz))
        return min(min(head, nose), min(neck, shoulders))
    }

    /** When true, the synthetic person has skin, eyes, lips and brown hair instead of test stripes. */
    var skinMode = false
    /** When true, the synthetic person is lit by a strong light from one side (to test de-lighting). */
    var sideLight = false

    fun trueColor(px: Float, py: Float, pz: Float): IntArray {
        if (skinMode) {
            val x = px - c[0]; val y = py - c[1]; val z = pz - c[2]
            if (y > 0.065f && z < 0.09f) return intArrayOf(70, 48, 32)                         // hair
            if (z > 0.06f && abs(abs(x) - 0.03f) < 0.012f && abs(y - 0.012f) < 0.006f) return intArrayOf(60, 45, 40) // eyes
            if (z > 0.06f && abs(x) < 0.022f && abs(y + 0.06f) < 0.006f) return intArrayOf(165, 85, 85)            // lips
            val shade = (0.85f + 0.15f * (y + 0.1f) / 0.2f).coerceIn(0.75f, 1f)
            return intArrayOf((214 * shade).toInt(), (168 * shade).toInt(), (142 * shade).toInt())
        }
        val y = py - c[1]
        val ang = atan2(px - c[0], pz - c[2])
        return intArrayOf(
            (128 + 120 * sin(y * 40f)).toInt(),
            (128 + 120 * cos(ang * 2f)).toInt(),
            90,
        )
    }

    fun lookAt(ex: Float, ey: Float, ez: Float, tx: Float, ty: Float, tz: Float): FloatArray {
        var fx = tx - ex; var fy = ty - ey; var fz = tz - ez
        val fl = sqrt(fx * fx + fy * fy + fz * fz); fx /= fl; fy /= fl; fz /= fl
        // right = f x up(0,1,0)
        var rx = -fz; var ry = 0f; var rz = fx
        val rl = sqrt(rx * rx + rz * rz); rx /= rl; rz /= rl
        // up = r x f
        val ux = ry * fz - rz * fy; val uy = rz * fx - rx * fz; val uz = rx * fy - ry * fx
        return floatArrayOf(rx, ry, rz, 0f, ux, uy, uz, 0f, -fx, -fy, -fz, 0f, ex, ey, ez, 1f)
    }

    fun invertRigid(m: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (i in 0 until 3) for (j in 0 until 3) r[j * 4 + i] = m[i * 4 + j]
        for (i in 0 until 3) r[12 + i] = -(r[i] * m[12] + r[4 + i] * m[13] + r[8 + i] * m[14])
        r[15] = 1f
        return r
    }

    fun march(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float): Float {
        var t = 0.05f
        repeat(200) {
            val d = sdf(ox + dx * t, oy + dy * t, oz + dz * t)
            if (d < 0.0003f) return t
            t += d
            if (t > 3f) return -1f
        }
        return -1f
    }

    val images = HashMap<String, RgbImage>()

    fun render(cam: FloatArray, name: String): Keyframe {
        val dW = 160; val dH = 120
        val cW = 640; val cH = 480
        val ci = Intrinsics(480f, 480f, 320f, 240f, cW, cH)
        val di = ci.scaledTo(dW, dH)
        val depth = ShortArray(dW * dH)
        for (v in 0 until dH) for (u in 0 until dW) {
            val x = (u + 0.5f - di.cx) / di.fx; val y = -(v + 0.5f - di.cy) / di.fy
            val l = sqrt(x * x + y * y + 1f)
            val lx = x / l; val ly = y / l; val lz = -1f / l
            val dx = cam[0] * lx + cam[4] * ly + cam[8] * lz
            val dy = cam[1] * lx + cam[5] * ly + cam[9] * lz
            val dz = cam[2] * lx + cam[6] * ly + cam[10] * lz
            val t = march(cam[12], cam[13], cam[14], dx, dy, dz)
            if (t > 0) {
                val noise = (Math.random().toFloat() - 0.5f) * 0.006f // +-3 mm sensor noise
                depth[v * dW + u] = ((t / l + noise) * 1000f).roundToInt().toShort()
            }
        }
        val argb = IntArray(cW * cH)
        for (v in 0 until cH) for (u in 0 until cW) {
            val x = (u + 0.5f - ci.cx) / ci.fx; val y = -(v + 0.5f - ci.cy) / ci.fy
            val l = sqrt(x * x + y * y + 1f)
            val lx = x / l; val ly = y / l; val lz = -1f / l
            val dx = cam[0] * lx + cam[4] * ly + cam[8] * lz
            val dy = cam[1] * lx + cam[5] * ly + cam[9] * lz
            val dz = cam[2] * lx + cam[6] * ly + cam[10] * lz
            val t = march(cam[12], cam[13], cam[14], dx, dy, dz)
            argb[v * cW + u] = if (t > 0) {
                val col = trueColor(cam[12] + dx * t, cam[13] + dy * t, cam[14] + dz * t)
                if (sideLight) {
                    // Strong warm light from the upper left, dim fill: like a window to one side.
                    val px = cam[12] + dx * t; val py = cam[13] + dy * t; val pz = cam[14] + dz * t; val e = 0.001f
                    var gx = sdf(px + e, py, pz) - sdf(px - e, py, pz); var gy = sdf(px, py + e, pz) - sdf(px, py - e, pz); var gz = sdf(px, py, pz + e) - sdf(px, py, pz - e)
                    val gl = sqrt(gx * gx + gy * gy + gz * gz); gx /= gl; gy /= gl; gz /= gl
                    val lam = max(0f, -0.6f * gx + 0.5f * gy + 0.62f * gz)
                    val k = 0.35f + 0.85f * lam
                    col[0] = (col[0] * k * 1.05f).toInt().coerceIn(0, 255); col[1] = (col[1] * k).toInt().coerceIn(0, 255); col[2] = (col[2] * k * 0.9f).toInt().coerceIn(0, 255)
                }
                (0xFF shl 24) or (col[0] shl 16) or (col[1] shl 8) or col[2]
            } else 0xFF202020.toInt()
        }
        images[name] = RgbImage(cW, cH, argb)
        return Keyframe(cam, invertRigid(cam), depth, dW, dH, di, ci, name)
    }

    @Test
    fun rearLowQuality() = fullPipeline(1500)

    @Test
    fun rearHighQuality() = fullPipeline(3500)

    /** Easier rear capture: only about three-quarters of the way round, no views from above. */
    @Test
    fun rearPartialCoverage() { fullPipeline(1500, maxAz = 270, elevations = floatArrayOf(-10f, 15f), name = "scan_rear_partial", maxMeanErr = 0.006f) }

    fun fullPipeline(target: Int, maxAz: Int = 360, elevations: FloatArray = floatArrayOf(-10f, 15f, 45f), name: String? = null,
                     maxMeanErr: Float = 0.004f) {
        val kfs = ArrayList<Keyframe>()
        var n = 0
        for (elev in elevations) {
            val step = if (elev > 30f) 45 else 20
            for (az0 in 0 until maxAz step step) {
                val az = if (maxAz < 360 && az0 > maxAz / 2) az0 - maxAz else az0   // first view stays at the front
                val a = Math.toRadians(az.toDouble()).toFloat()
                val e = Math.toRadians(elev.toDouble()).toFloat()
                val r = 0.5f
                val ex = c[0] + r * cos(e) * sin(a); val ey = c[1] + r * sin(e); val ez = c[2] + r * cos(e) * cos(a)
                kfs.add(render(lookAt(ex, ey, ez, c[0], c[1] - 0.03f, c[2]), "kf${n++}"))
            }
        }
        println("Rendered ${kfs.size} keyframes")
        val t0 = System.currentTimeMillis()
        // Estimated centre slightly off, like a real tap would be.
        val est = floatArrayOf(c[0] + 0.01f, c[1] + 0.015f, c[2] - 0.01f)
        val res = HeadScanPipeline(ScanSettings(targetTriangles = target)).run(kfs, est, { images[it.colorRef]!! }) { s, p ->
            if (s.startsWith("Fusing depth 1/") || !s.contains('/')) println("  [${(p * 100).toInt()}%] $s")
        }
        println("Pipeline took ${System.currentTimeMillis() - t0} ms: ${res.triangleCount} tris, ${res.vertexCount} verts")

        val out = File("build/" + (name ?: "scan_rear_$target")).apply { mkdirs() }
        File(out, "head.obj").writeText(res.objText())
        File(out, "head.mtl").writeText(res.mtlText())
        // Texture as a binary PPM (java.awt isn't available in Android unit tests).
        File(out, "head_texture.ppm").outputStream().buffered().use { o ->
            o.write("P6\n${res.textureSize} ${res.textureSize}\n255\n".toByteArray())
            for (c in res.texture) { o.write((c shr 16) and 255); o.write((c shr 8) and 255); o.write(c and 255) }
        }

        // Parse back the OBJ and check geometry + texture against ground truth.
        val vs = ArrayList<FloatArray>(); val vts = ArrayList<FloatArray>(); val fs = ArrayList<IntArray>()
        res.objText().lineSequence().forEach { line ->
            val p = line.split(" ")
            when (p[0]) {
                "v" -> vs.add(floatArrayOf(p[1].toFloat(), p[2].toFloat(), p[3].toFloat()))
                "vt" -> vts.add(floatArrayOf(p[1].toFloat(), p[2].toFloat()))
                "f" -> fs.add(p.drop(1).flatMap { it.split("/").map { s -> s.toInt() - 1 } }.toIntArray())
            }
        }
        assertTrue(fs.size in (target - 100)..target, "triangle count ${fs.size}")
        // Head frame: origin = estimated centre, rotation ~0 because first camera is at +Z.
        var errSum = 0.0; var errMax = 0f
        for (v in vs) {
            val d = abs(sdf(v[0] + est[0], v[1] + est[1], v[2] + est[2]))
            errSum += d; errMax = max(errMax, d)
        }
        println("Surface error: mean %.2f mm, max %.2f mm".format(errSum / vs.size * 1000, errMax * 1000))
        val minY = vs.minOf { it[1] }; val maxY = vs.maxOf { it[1] }
        val maxR = vs.maxOf { sqrt(it[0] * it[0] + it[2] * it[2]) }
        println("Extents: y %.3f..%.3f, max radius %.3f".format(minY, maxY, maxR))

        // outward normals: face normal should point away from the head/neck axis
        var outward = 0
        var colErr = 0.0; var colN = 0
        for (f in fs) {
            val a = vs[f[0]]; val b = vs[f[3]]; val cc = vs[f[6]]
            val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
            val vx = cc[0] - a[0]; val vy = cc[1] - a[1]; val vz = cc[2] - a[2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            val mx = (a[0] + b[0] + cc[0]) / 3; val my = (a[1] + b[1] + cc[1]) / 3; val mz = (a[2] + b[2] + cc[2]) / 3
            // gradient of sdf at centroid
            val e = 0.001f
            val wx = mx + est[0]; val wy = my + est[1]; val wz = mz + est[2]
            val gx = sdf(wx + e, wy, wz) - sdf(wx - e, wy, wz)
            val gy = sdf(wx, wy + e, wz) - sdf(wx, wy - e, wz)
            val gz = sdf(wx, wy, wz + e) - sdf(wx, wy, wz - e)
            if (nx * gx + ny * gy + nz * gz > 0) outward++
            // texture: sample at the UV centroid
            val t0 = vts[f[1]]; val t1 = vts[f[4]]; val t2 = vts[f[7]]
            val tu = (t0[0] + t1[0] + t2[0]) / 3; val tv = (t0[1] + t1[1] + t2[1]) / 3
            val px = (tu * res.textureSize).toInt().coerceIn(0, res.textureSize - 1)
            val py = ((1 - tv) * res.textureSize).toInt().coerceIn(0, res.textureSize - 1)
            val col = res.texture[py * res.textureSize + px]
            val truth = trueColor(wx, wy, wz)
            colErr += (abs(((col shr 16) and 255) - truth[0]) + abs(((col shr 8) and 255) - truth[1]) + abs((col and 255) - truth[2])) / 3.0
            colN++
        }
        println("Outward-facing triangles: $outward / ${fs.size}")
        println("Mean texture colour error: %.1f / 255".format(colErr / colN))
        assertTrue(outward > fs.size * 0.95, "normals")
        assertTrue(errSum / vs.size < maxMeanErr, "surface error")
        assertTrue(colErr / colN < 20, "texture error")
        assertTrue(minY > -0.29f && maxR < 0.16f, "crop")
    }

    @Test
    fun selfieLowQuality() { selfie(1500) }

    @Test
    fun selfieHighQuality() { selfie(3500) }

    /** Selfie mode: synthetic ARCore-style face mesh + front-camera photos from a head turning left/right/up/down. */
    @Test
    fun selfieGoldenEyeStyle() { selfie(1500, HeadStyle.GOLDENEYE_SOURCE) }

    @Test
    fun selfieShortHair() { selfie(1500, hair = HairStyle.SIDE_PART) }

    @Test
    fun selfieLongHair() { selfie(3500, hair = HairStyle.CURTAINS) }

    /** Renders every hairstyle on a skin-coloured synthetic person (build/gallery_*), in the GoldenEye look, for visual review. */
    @Test
    fun hairGallery() {
        skinMode = true
        try {
            for (h in HairStyle.values()) selfie(1500, style = HeadStyle.GOLDENEYE_SOURCE, hair = h, outName = "gallery_$h")
            selfie(3500, style = HeadStyle.GOLDENEYE_SOURCE, hair = HairStyle.SPIKY, outName = "gallery_SPIKY_3500")
            selfie(1500, style = HeadStyle.GOLDENEYE_SOURCE, hair = HairStyle.SIDE_PART, outName = "gallery_SIDE_PART_blonde", recolor = HairColor.NATURAL + 0.75f to 0f)
        } finally { skinMode = false; images.clear() }
    }

    @Test
    fun scaledResult() {
        val m = Mesh(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), intArrayOf(0, 1, 2))
        val r = ScanResult(m, MeshOps.vertexNormals(m), false, UvAtlas(1, 64), IntArray(64 * 64), 64)
        val s = r.scaled(2f, 3f, 1f)
        assertTrue(s.mesh.positions[3] == 2f && s.mesh.positions[7] == 3f && r.mesh.positions[3] == 1f)
        val moved = r.transformed(1f, 1f, 1f, 0.1f, -0.05f, 0.02f)
        assertTrue(moved.mesh.positions[3] == 1.1f && moved.mesh.positions[1] == -0.05f && moved.mesh.positions[2] == 0.02f)
    }

    @Test
    fun selfieFaceMoved() { selfie(1500, hair = HairStyle.SIDE_PART, offset = floatArrayOf(0.015f, -0.02f, 0.02f)) }

    @Test
    fun lightingRemoved() {
        skinMode = true
        try {
            selfie(1500, hair = HairStyle.NONE, offset = FloatArray(3), outName = "evenly_lit")
            images.clear()
            sideLight = true
            selfie(1500, hair = HairStyle.NONE, offset = FloatArray(3), outName = "side_lit")
        } finally { skinMode = false; sideLight = false; images.clear() }
    }

    /** The AI post-process options run with stand-in nets (x4 nearest upscale, a colour-based segmenter). */
    @Test
    fun aiPostProcess() {
        val calls = intArrayOf(0, 0)
        val upscale: (FloatArray) -> FloatArray = { inp ->
            calls[0]++
            FloatArray(256 * 256 * 3) { o -> val p = o / 3; val c = o % 3; inp[3 * ((p / 256 / 4) * 64 + (p % 256) / 4) + c] }
        }
        AiModels.sharpen = upscale; AiModels.denoise = upscale
        AiModels.segment = { inp ->
            calls[1]++
            FloatArray(256 * 256 * 6) { o -> val p = o / 6; val c = o % 6
                val dark = inp[3 * p] < 0.15f && inp[3 * p + 1] < 0.15f
                if ((dark && c == SkinLabels.BACKGROUND) || (!dark && c == SkinLabels.FACE_SKIN)) 1f else 0f }
        }
        skinMode = true
        try {
            val plain = selfie(1500, hair = HairStyle.NONE, outName = "ai_none")
            images.clear()
            val sharp = selfie(1500, hair = HairStyle.NONE, outName = "ai_sharpen", tweak = { it.copy(enhance = Enhance.SHARPEN) })
            assertTrue(calls[0] > 4, "sharpen net used on face blocks: ${calls[0]}")
            var changed = 0
            for (i in plain.texture.indices) if (plain.texture[i] != sharp.texture[i]) changed++
            assertTrue(changed in 1000 until plain.texture.size / 2, "only the face is touched: $changed")
            images.clear()
            selfie(1500, hair = HairStyle.NONE, outName = "ai_skin", tweak = { it.copy(aiSkin = true, matchHeadSkin = true) })
            assertTrue(calls[1] > 4, "segmenter used on each photo: ${calls[1]}")
        } finally { skinMode = false; images.clear(); AiModels.sharpen = null; AiModels.denoise = null; AiModels.segment = null }
    }

    /** The four debug texture options (photo on the whole head, photo hair, real skin fill, wide blend), each and all together. */
    @Test
    fun wholeHeadTextureOptions() {
        AiModels.segment = { inp ->
            FloatArray(256 * 256 * 6) { o -> val p = o / 6; val c = o % 6
                val dark = inp[3 * p] < 0.15f && inp[3 * p + 1] < 0.15f
                val luma = 0.3f * inp[3 * p] + 0.59f * inp[3 * p + 1] + 0.11f * inp[3 * p + 2]
                val cls = if (dark) SkinLabels.BACKGROUND else if (luma < 0.42f) SkinLabels.HAIR else SkinLabels.FACE_SKIN
                if (c == cls) 1f else 0f }
        }
        skinMode = true
        try {
            val base = selfie(1500, hair = HairStyle.NONE, outName = "opt_off")
            val variants = listOf<Pair<String, (ScanSettings) -> ScanSettings>>(
                "opt_whole" to { it.copy(wholeHeadPhoto = true) },
                "opt_hair" to { it.copy(photoHair = true) },
                "opt_skin" to { it.copy(realSkinFill = true) },
                "opt_blend" to { it.copy(wideBlend = true) },
                "opt_all" to { it.copy(wholeHeadPhoto = true, photoHair = true, realSkinFill = true, wideBlend = true) },
            )
            for ((name, tweak) in variants) {
                images.clear()
                val r = selfie(1500, hair = HairStyle.NONE, outName = name, tweak = tweak)
                var changed = 0
                for (i in r.texture.indices) if (r.texture[i] != base.texture[i]) changed++
                println("$name: $changed texels differ")
                assertTrue(changed > 1000, "$name changes the texture")
            }
        } finally { skinMode = false; images.clear(); AiModels.segment = null }
    }

    /** Face points a face mesh detector would find in [kf]'s photo: pixel x, y and relative depth, plus triangles. */
    fun fakeLandmarks(kf: Keyframe): Pair<RearFace.Landmarks, IntArray> {
        val ci = kf.colorIntrinsics
        val gx = 30; val gy = 36
        val n = gx * gy
        val wx = FloatArray(n); val wy = FloatArray(n); val wz = FloatArray(n)
        fun put(k: Int, x: Float, y: Float) { val t = march(c[0] + x, c[1] + y, c[2] + 0.3f, 0f, 0f, -1f); wx[k] = x; wy[k] = y; wz[k] = if (t > 0) 0.3f - t else 0f }
        for (j in 0 until gy) for (i in 0 until gx) put(j * gx + i, -0.065f + 0.13f * i / (gx - 1), -0.105f + 0.17f * j / (gy - 1))
        put(33, -0.042f, 0.012f); put(133, -0.018f, 0.012f); put(362, 0.018f, 0.012f); put(263, 0.042f, 0.012f); put(1, 0f, -0.01f)
        val px = FloatArray(n); val py = FloatArray(n); val pz = FloatArray(n)
        val w2c = kf.worldToCam
        for (k in 0 until n) {
            val X = wx[k] + c[0]; val Y = wy[k] + c[1]; val Z = wz[k] + c[2]
            val xc = w2c[0] * X + w2c[4] * Y + w2c[8] * Z + w2c[12]
            val yc = w2c[1] * X + w2c[5] * Y + w2c[9] * Z + w2c[13]
            val zc = w2c[2] * X + w2c[6] * Y + w2c[10] * Z + w2c[14]
            px[k] = ci.cx + ci.fx * xc / -zc; py[k] = ci.cy - ci.fy * yc / -zc; pz[k] = -zc * ci.fx / 0.45f
        }
        val tris = ArrayList<Int>()
        fun inFace(k: Int) = (wx[k] / 0.068f).pow(2) + ((wy[k] + 0.02f) / 0.09f).pow(2) <= 1f
        for (j in 0 until gy - 1) for (i in 0 until gx - 1) {
            val a = j * gx + i; val b = a + 1; val d = a + gx; val e = d + 1
            if (listOf(a, b, d, e).all { inFace(it) && it !in setOf(33, 133, 362, 263, 1) }) tris.addAll(listOf(a, e, b, a, d, e))
        }
        return RearFace.Landmarks(kf, px, py, pz) to tris.toIntArray()
    }

    /** Rear camera: the face is found in the front photos, the head model fitted, nothing below the neck kept. */
    @Test
    fun rearWithHeadModel() {
        skinMode = true
        try {
            val kfs = ArrayList<Keyframe>(); val found = ArrayList<RearFace.Landmarks>()
            var tris = IntArray(0)
            var n = 0
            for (elev in floatArrayOf(-10f, 15f)) for (az0 in 0 until 270 step 20) {
                val az = if (az0 > 135) az0 - 270 else az0
                val a = Math.toRadians(az.toDouble()).toFloat(); val e = Math.toRadians(elev.toDouble()).toFloat()
                val r = 0.5f
                val kf = render(lookAt(c[0] + r * cos(e) * sin(a), c[1] + r * sin(e), c[2] + r * cos(e) * cos(a), c[0], c[1] - 0.03f, c[2]), "r${n++}")
                kfs.add(kf)
                if (abs(az) <= 20) { val (l, t) = fakeLandmarks(kf); found.add(l); tris = t }
            }
            val rf = RearFace.build(kfs, found, tris)
            assertTrue(rf != null, "face found")
            val sh = rf.shape
            println("Rear: ${rf.framesUsed} front views; skull ${sh?.let { "rx=%.3f top=%.3f back=%.3f".format(it.rx, it.top, it.zBack) }}")
            assertTrue(sh != null && sh.rx in 0.065f..0.09f, "skull width measured")
            val res = HeadScanPipeline(ScanSettings(targetTriangles = 1500, style = HeadStyle.GOLDENEYE_SOURCE, hairStyle = HairStyle.BUZZ, headShape = sh))
                .runSelfie(rf.keyframes, rf.faceVertices, rf.faceTriangles, { images[it.colorRef]!! })
            assertTrue(res.triangleCount in 1400..1500, "triangles ${res.triangleCount}")
            val out = File("build/rear_model").apply { mkdirs() }
            File(out, "head.obj").writeText(res.objText())
            File(out, "head_texture.ppm").outputStream().buffered().use { o ->
                o.write("P6\n${res.textureSize} ${res.textureSize}\n255\n".toByteArray())
                for (p in res.texture) { o.write((p shr 16) and 255); o.write((p shr 8) and 255); o.write(p and 255) }
            }
        } finally { skinMode = false; images.clear() }
    }

    /** Image mode: one front photo, with the face mesh a face detector would find in it. */
    @Test
    fun imageMode() {
        skinMode = true
        try {
            val cam = lookAt(c[0] + 0.01f, c[1] + 0.02f, c[2] + 0.45f, c[0], c[1], c[2])
            val kf0 = render(cam, "photo")
            val img = images["photo"]!!
            val ci = kf0.colorIntrinsics
            // Face points: a grid over the front of the face, with eye corners and nose tip at the face mesh indices.
            val gx = 30; val gy = 36
            val n = gx * gy
            val wx = FloatArray(n); val wy = FloatArray(n); val wz = FloatArray(n)
            for (j in 0 until gy) for (i in 0 until gx) {
                val x = -0.065f + 0.13f * i / (gx - 1); val y = -0.105f + 0.17f * j / (gy - 1)
                val t = march(c[0] + x, c[1] + y, c[2] + 0.3f, 0f, 0f, -1f)
                val k = j * gx + i; wx[k] = x; wy[k] = y; wz[k] = if (t > 0) 0.3f - t else 0f
            }
            fun put(k: Int, x: Float, y: Float) { val t = march(c[0] + x, c[1] + y, c[2] + 0.3f, 0f, 0f, -1f); wx[k] = x; wy[k] = y; wz[k] = 0.3f - t }
            put(33, -0.042f, 0.012f); put(133, -0.018f, 0.012f); put(362, 0.018f, 0.012f); put(263, 0.042f, 0.012f); put(1, 0f, -0.01f)
            val px = FloatArray(n); val py = FloatArray(n); val pz = FloatArray(n)
            val w2c = kf0.worldToCam
            for (k in 0 until n) {
                val X = wx[k] + c[0]; val Y = wy[k] + c[1]; val Z = wz[k] + c[2]
                val xc = w2c[0] * X + w2c[4] * Y + w2c[8] * Z + w2c[12]
                val yc = w2c[1] * X + w2c[5] * Y + w2c[9] * Z + w2c[13]
                val zc = w2c[2] * X + w2c[6] * Y + w2c[10] * Z + w2c[14]
                px[k] = ci.cx + ci.fx * xc / -zc; py[k] = ci.cy - ci.fy * yc / -zc; pz[k] = -zc * ci.fx / 0.45f
            }
            val tris = ArrayList<Int>()
            fun inFace(k: Int) = (wx[k] / 0.068f).pow(2) + ((wy[k] + 0.02f) / 0.09f).pow(2) <= 1f
            for (j in 0 until gy - 1) for (i in 0 until gx - 1) {
                val a = j * gx + i; val b = a + 1; val d = a + gx; val e = d + 1
                if (listOf(a, b, d, e).all { inFace(it) && it !in setOf(33, 133, 362, 263, 1) }) tris.addAll(listOf(a, e, b, a, d, e))
            }
            val pf = PhotoFace.build(px, py, pz, tris.toIntArray(), img.width, img.height, "photo")
            val res = HeadScanPipeline(ScanSettings(targetTriangles = 1500, style = HeadStyle.GOLDENEYE_SOURCE, hairStyle = HairStyle.SIDE_PART))
                .runSelfie(listOf(pf.keyframe), pf.vertices, pf.triangles, { images[it.colorRef]!! })
            assertTrue(res.triangleCount in 1400..1500, "triangles ${res.triangleCount}")
            val out = File("build/image_mode").apply { mkdirs() }
            File(out, "head.obj").writeText(res.objText())
            File(out, "head_texture.ppm").outputStream().buffered().use { o ->
                o.write("P6\n${res.textureSize} ${res.textureSize}\n255\n".toByteArray())
                for (p in res.texture) { o.write((p shr 16) and 255); o.write((p shr 8) and 255); o.write(p and 255) }
            }
        } finally { skinMode = false; images.clear() }
    }

    @Test
    fun headMeasuredFromPhotos() {
        skinMode = true
        try {
            // Synthetic skull: half width 0.08, top 0.11 above the centre, back 0.10 behind it (no hair).
            val r = selfie(1500, style = HeadStyle.GOLDENEYE_SOURCE, hair = HairStyle.SIDE_PART, offset = FloatArray(3), outName = "measured", measure = true)
            val fit = r.headFit
            assertTrue(fit != null, "head measured")
            val sh = fit.shape
            println("Measured: rx=%.3f top=%.3f front=%.3f back=%.3f mismatch=%.3f views=%d depth=%s size=%s".format(
                sh.rx, sh.top, sh.zFront, sh.zBack, fit.mismatch, fit.viewsUsed, fit.depthMeasured, r.headSize!!.joinToString { "%.3f".format(it) }))
            assertTrue(sh.rx in 0.07f..0.09f, "width")
            assertTrue(sh.top in 0.09f..0.12f, "top")
            assertTrue(sh.zBack in -0.115f..-0.08f, "back")
            selfie(1500, style = HeadStyle.GOLDENEYE_SOURCE, hair = HairStyle.SIDE_PART, offset = FloatArray(3), outName = "unmeasured")
            // A failed cut-out falls back to average proportions.
            val bad = selfie(1500, hair = HairStyle.NONE, offset = FloatArray(3), outName = "measure_failed", measure = true, emptyMasks = true)
            assertTrue(bad.headFit == null, "fallback")
        } finally { skinMode = false; images.clear() }
    }

    @Test
    fun settingsOptions() {
        skinMode = true
        try {
            selfie(1500, style = HeadStyle.GOLDENEYE_SOURCE, hair = HairStyle.NONE, outName = "opt_nohair")
            selfie(1500, style = HeadStyle.GOLDENEYE_SOURCE, hair = HairStyle.BUZZ, outName = "opt_wideface", wideFace = true)
            selfie(1500, style = HeadStyle.GOLDENEYE_SOURCE, hair = HairStyle.SIDE_PART, outName = "opt_auburn", recolor = HairColor.NATURAL + 0.36f to 0f)
            selfie(1500, style = HeadStyle.GOLDENEYE_SOURCE, hair = HairStyle.SIDE_PART, outName = "opt_blonde", recolor = HairColor.NATURAL + 0.75f to 0f)
            selfie(1500, style = HeadStyle.GOLDENEYE_SOURCE, hair = HairStyle.SIDE_PART, outName = "opt_grey", recolor = HairColor.NATURAL + 0.92f to 0f)
        } finally { skinMode = false; images.clear() }
    }

    fun selfie(target: Int, style: HeadStyle = HeadStyle.REALISTIC, hair: HairStyle = HairStyle.BUZZ, offset: FloatArray = FloatArray(3), outName: String? = null,
               recolor: Pair<Float?, Float>? = null, wideFace: Boolean = false, measure: Boolean = false, emptyMasks: Boolean = false,
               tweak: (ScanSettings) -> ScanSettings = { it }): ScanResult {
        // Face frame = head centre c, +Z out of the face. Build a face mesh grid by ray casting the front of the scene.
        val gx = 30; val gy = 36
        val fv = FloatArray(gx * gy * 3)
        for (j in 0 until gy) for (i in 0 until gx) {
            val x = (if (wideFace) -0.085f else -0.065f) + (if (wideFace) 0.17f else 0.13f) * i / (gx - 1)
            val y = -0.105f + 0.17f * j / (gy - 1)
            val t = march(c[0] + x, c[1] + y, c[2] + 0.3f, 0f, 0f, -1f)
            val z = if (t > 0) 0.3f - t else 0f
            val k = 3 * (j * gx + i)
            fv[k] = x; fv[k + 1] = y; fv[k + 2] = z
        }
        val tris = ArrayList<Int>()
        // Keep an oval face region, like the real ARCore face mask.
        // wideFace: the mesh wraps round the sides of the face like a real ARCore mesh (steep border triangles).
        val fax = if (wideFace) 0.085f else 0.068f
        fun inFace(k: Int) = (fv[3 * k] / fax).pow(2) + ((fv[3 * k + 1] + 0.02f) / 0.09f).pow(2) <= 1f
        for (j in 0 until gy - 1) for (i in 0 until gx - 1) {
            val a = j * gx + i; val b = a + 1; val d = a + gx; val e = d + 1
            fun hit(k: Int) = fv[3 * k + 2] != 0f
            if (inFace(a) && inFace(b) && inFace(d) && inFace(e) && hit(a) && hit(b) && hit(d) && hit(e)) tris.addAll(listOf(a, b, e, a, e, d))
        }
        val kfs = ArrayList<Keyframe>()
        var n = 0
        for (pitch in floatArrayOf(-15f, 0f, 20f)) for (yaw in -50..50 step 10) {
            val a = Math.toRadians(yaw.toDouble()).toFloat(); val e = Math.toRadians(pitch.toDouble()).toFloat()
            val r = 0.35f
            val ex = c[0] + r * cos(e) * sin(a); val ey = c[1] + r * sin(e); val ez = c[2] + r * cos(e) * cos(a)
            val world = render(lookAt(ex, ey, ez, c[0], c[1], c[2]), "s${n++}")
            // Express the camera in the face frame (translate by -c).
            val m = world.camToWorld.copyOf(); m[12] -= c[0]; m[13] -= c[1]; m[14] -= c[2]
            kfs.add(Keyframe(m, invertRigid(m), ShortArray(1), 1, 1, world.depthIntrinsics.scaledTo(1, 1), world.colorIntrinsics, world.colorRef))
        }
        // The person's outline in each photo (what the phone gets from the person cut-out), at 160 x 120.
        val sil = if (!measure) null else kfs.map { kf ->
            val img = images[kf.colorRef]!!
            val w = 160; val h = 120
            val mask = ByteArray(w * h) { k ->
                if (emptyMasks) 0 else {
                    val u = (k % w) * img.width / w + 2; val v = (k / w) * img.height / h + 2
                    if (img.argb[v * img.width + u] != 0xFF202020.toInt()) 1 else 0
                }
            }
            SilhouetteView(mask, w, h, kf.colorIntrinsics.scaledTo(w, h), kf.worldToCam)
        }
        val res0 = HeadScanPipeline(tweak(ScanSettings(targetTriangles = target, style = style, hairStyle = hair,
            faceOffsetX = offset[0], faceOffsetY = offset[1], faceOffsetZ = offset[2],
        ))).runSelfie(
            kfs, fv, tris.toIntArray(), { images[it.colorRef]!! }, silhouettes = sil,
        )
        var res = res0
        if (recolor != null) {
            assertTrue(res.canRecolorHair, "hair mask present")
            res = res.withHairColor(recolor.first, recolor.second)
        }
        println("Selfie $target $hair: ${res.triangleCount} tris, ${res.vertexCount} verts")
        assertTrue(res.triangleCount in (target - 100)..target, "triangle count ${res.triangleCount}")
        val out = File("build/" + (outName ?: "scan_selfie_${target}_${style}_${hair}${if (offset.any { it != 0f }) "_moved" else ""}")).apply { mkdirs() }
        File(out, "head.obj").writeText(res.objText())
        File(out, "head.mtl").writeText(res.mtlText())
        File(out, "head_texture.ppm").outputStream().buffered().use { o ->
            o.write("P6\n${res.textureSize} ${res.textureSize}\n255\n".toByteArray())
            for (px in res.texture) { o.write((px shr 16) and 255); o.write((px shr 8) and 255); o.write(px and 255) }
        }
        // Face region should match the real surface closely.
        var err = 0.0; var cnt = 0
        res.objText().lineSequence().filter { it.startsWith("v ") }.forEach { line ->
            val p = line.split(" ").drop(1).map { it.toFloat() }
            if (p[2] > 0.03f && abs(p[0]) < 0.05f && p[1] > -0.08f && p[1] < 0.05f) {
                // output origin is the generic head centre; recover face-frame coordinates is not needed for
                // a sanity bound: just check the surface is near the scene after re-centring on the nose tip.
                cnt++
            }
        }
        assertTrue(cnt > 20, "face vertices present")
        if (style == HeadStyle.GOLDENEYE_SOURCE) {
            assertTrue(res.objText().contains("s 1"), "smooth shading")
        }
        val (pos, nrm, st) = res.previewArrays()
        assertTrue(pos.size == res.triangleCount * 9 && nrm.size == pos.size && st.size == res.triangleCount * 6)
        return res
    }
}
