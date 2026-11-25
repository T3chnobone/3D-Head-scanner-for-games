package com.headscan.core

import kotlin.math.atan2
import kotlin.math.sqrt

/** Tunables for the reconstruction. Defaults produce a 1500-triangle mesh with a 1024x1024 texture. */
data class ScanSettings(
    val targetTriangles: Int = 1500,
    val textureSize: Int = 1024,
    val voxelSize: Float = 0.004f,
    val truncation: Float = 0.015f,
    /** Half width of the capture box around the head centre (x and z), metres. */
    val halfWidth: Float = 0.15f,
    /** How far above the head centre the box reaches. */
    val above: Float = 0.17f,
    /** How far below the head centre the box reaches (includes the neck). */
    val below: Float = 0.27f,
    /** Below this distance under the head centre only a neck-sized cylinder is kept (cuts the shoulders). */
    val neckStart: Float = 0.11f,
    val neckRadius: Float = 0.085f,
    /** Below this distance under the head centre, near-horizontal surfaces (shoulder tops) are removed. */
    val collarStart: Float = 0.16f,
    val minWeight: Float = 1.5f,
    val smoothingIterations: Int = 8,
    val style: HeadStyle = HeadStyle.REALISTIC,
    /** Selfie mode only: hair added to the generic head. */
    val hairStyle: HairStyle = HairStyle.BUZZ,
    /** Selfie mode only: where the face sits on the generic head (metres, +X right, +Y up, +Z forward). */
    val faceOffsetX: Float = 0f,
    val faceOffsetY: Float = 0f,
    val faceOffsetZ: Float = 0f,
)

/** Visual style of the generated head. */
enum class HeadStyle {
    /** Smooth shading, natural photo texture. */
    REALISTIC,
    /** GoldenEye: Source (Half-Life 2 mod) character look: smooth head, crisp graded photo face with baked soft light and AO. */
    GOLDENEYE_SOURCE,
}

class ScanResult(
    /** Head-frame mesh: metres, +Y up, face toward +Z. */
    val mesh: Mesh,
    /** Per-vertex normals (smooth) or per-face normals (when [flatShaded]). */
    val normals: FloatArray,
    val flatShaded: Boolean,
    val atlas: UvAtlas,
    /** ARGB, row 0 = top, textureSize x textureSize. */
    val texture: IntArray,
    val textureSize: Int,
) {
    val triangleCount get() = mesh.faceCount
    val vertexCount get() = mesh.vertexCount

    fun objText(mtlFileName: String = "head.mtl"): String =
        ObjWriter.obj(mesh, normals, atlas, mtlFileName, HeadScanPipeline.MATERIAL, flatShaded)

    fun mtlText(textureFileName: String = "head_texture.png"): String = ObjWriter.mtl(HeadScanPipeline.MATERIAL, textureFileName)

    /** A copy with the mesh stretched about the head origin (texture and UVs unchanged). */
    fun scaled(sx: Float, sy: Float, sz: Float): ScanResult = transformed(sx, sy, sz, 0f, 0f, 0f)

    /** A copy scaled about the head origin, then moved by (dx, dy, dz) metres. */
    fun transformed(sx: Float, sy: Float, sz: Float, dx: Float, dy: Float, dz: Float): ScanResult {
        if (sx == 1f && sy == 1f && sz == 1f && dx == 0f && dy == 0f && dz == 0f) return this
        val p = mesh.positions.copyOf()
        for (v in 0 until mesh.vertexCount) {
            p[3 * v] = p[3 * v] * sx + dx; p[3 * v + 1] = p[3 * v + 1] * sy + dy; p[3 * v + 2] = p[3 * v + 2] * sz + dz
        }
        val m = Mesh(p, mesh.faces)
        val n = if (flatShaded) MeshOps.faceNormals(m) else MeshOps.vertexNormals(m)
        return ScanResult(m, n, flatShaded, atlas, texture, textureSize)
    }

    /** Un-indexed triangle arrays for real-time preview: xyz, normal, texture st (t = 0 at the top row). */
    fun previewArrays(): Triple<FloatArray, FloatArray, FloatArray> {
        val n = mesh.faceCount * 3
        val pos = FloatArray(n * 3); val nrm = FloatArray(n * 3); val st = FloatArray(n * 2)
        for (f in 0 until mesh.faceCount) for (c in 0 until 3) {
            val v = mesh.faces[3 * f + c]
            val o = (3 * f + c)
            for (k in 0 until 3) {
                pos[3 * o + k] = mesh.positions[3 * v + k]
                nrm[3 * o + k] = if (flatShaded) normals[3 * f + k] else normals[3 * v + k]
            }
            st[2 * o] = atlas.pixelUv[f * 6 + c * 2] / atlas.size
            st[2 * o + 1] = atlas.pixelUv[f * 6 + c * 2 + 1] / atlas.size
        }
        return Triple(pos, nrm, st)
    }
}

class ScanException(message: String) : Exception(message)

/**
 * Turns captured keyframes into a textured low-poly head + neck mesh.
 * Pure Kotlin/JVM: no Android dependencies, so it can be unit tested on a desktop.
 */
class HeadScanPipeline(private val settings: ScanSettings = ScanSettings()) {

    fun run(
        keyframes: List<Keyframe>,
        headCenter: FloatArray,
        colorSource: ColorSource,
        progress: (String, Float) -> Unit = { _, _ -> },
    ): ScanResult {
        if (keyframes.size < 4) throw ScanException("Need at least 4 captured views, got ${keyframes.size}.")
        val s = settings
        val cx = headCenter[0]; val cy = headCenter[1]; val cz = headCenter[2]

        // 1. Depth fusion
        val nx = (2 * s.halfWidth / s.voxelSize).toInt() + 1
        val ny = ((s.above + s.below) / s.voxelSize).toInt() + 1
        val vol = TsdfVolume(cx - s.halfWidth, cy - s.below, cz - s.halfWidth, s.voxelSize, nx, ny, nx, s.truncation)
        keyframes.forEachIndexed { i, kf ->
            progress("Fusing depth ${i + 1}/${keyframes.size}", 0.35f * i / keyframes.size)
            vol.integrate(kf)
        }

        val front = keyframes.first()
        var fx = front.camX - cx; var fz = front.camZ - cz
        val fl = sqrt(fx * fx + fz * fz)
        if (fl > 1e-6f) { fx /= fl; fz /= fl } else { fx = 0f; fz = 1f }
        return finishMesh(vol, keyframes, cx, cy, cz, atan2(fx, fz), colorSource, null, crop = true, progress)
    }

    /**
     * Selfie mode: [keyframes] must already be expressed in the face frame (+Y up, +Z out of the face).
     * [faceVertices]/[faceTriangles] is the averaged ARCore face mesh in that same frame.
     */
    fun runSelfie(
        keyframes: List<Keyframe>,
        faceVertices: FloatArray,
        faceTriangles: IntArray,
        colorSource: ColorSource,
        progress: (String, Float) -> Unit = { _, _ -> },
    ): ScanResult {
        if (keyframes.size < 3) throw ScanException("Need at least 3 captured views, got ${keyframes.size}.")
        progress("Fitting head to your face", 0.05f)
        val head = SelfieHead(faceVertices, faceTriangles, settings.hairStyle,
            floatArrayOf(settings.faceOffsetX, settings.faceOffsetY, settings.faceOffsetZ))
        val vol = head.buildVolume(0.003f, 0.012f)
        val hair = HairFill(head)
        return finishMesh(vol, keyframes, head.headCx, head.headCy, head.headCz, 0f, colorSource, hair, crop = false, progress, smoothing = 3)
    }

    /**
     * Paints the generic head: skin tone measured from the cheeks and forehead, hair colour from just above the
     * forehead, a soft hairline, and the photo face blended into the skin at its border.
     */
    private class HairFill(val head: SelfieHead) {
        var hair = 0xFF3A2A1E.toInt()
        var skin = 0xFFC89F86.toInt()
        fun prepare(baker: TextureBaker) {
            val h = head.maxY - head.minY
            baker.robustSeenColor { x, y, z ->
                if (head.faceMask(x, y, z) < 0.9f) return@robustSeenColor false
                val ax = kotlin.math.abs(x)
                val cheek = y > head.minY + 0.38f * h && y < head.minY + 0.55f * h && ax > 0.022f && ax < 0.048f
                val forehead = y > head.minY + 0.78f * h && y < head.maxY - 0.015f && ax < 0.03f
                cheek || forehead
            }?.let { skin = it }
            baker.robustSeenColor(30) { x, y, z ->
                y > head.maxY + 0.004f && y < head.maxY + 0.035f && z > head.headCz && kotlin.math.abs(x) < 0.05f
            }?.let { hair = it }
        }

        private fun mix(a: Int, b: Int, t: Float): Int {
            val u = t.coerceIn(0f, 1f)
            fun ch(sh: Int) = (((a shr sh) and 255) * (1 - u) + ((b shr sh) and 255) * u).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        private fun scale(c: Int, k: Float): Int {
            fun ch(sh: Int) = (((c shr sh) and 255) * k).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        /** Hair colour with a fine strand pattern: front-to-back on top, downward on the sides. */
        private fun hairAt(x: Float, y: Float, z: Float): Int {
            val lx = x - head.headCx; val lz = z - head.headCz
            val top = kotlin.math.sin(lx * 900f + kotlin.math.sin(lz * 70f) * 2.5f)
            val side = kotlin.math.sin(kotlin.math.atan2(lx, lz) * 120f + kotlin.math.sin(y * 80f) * 2f)
            val u = ((y - head.headCy) / 0.08f).coerceIn(0f, 1f)
            val strands = top * u + side * (1 - u)
            val n = kotlin.math.sin(x * 371f + y * 533f + z * 197f) * 0.5f     // cheap variation
            return scale(hair, 0.9f + 0.12f * strands + 0.05f * n)
        }

        fun color(x: Float, y: Float, z: Float): Int {
            // Necks are usually a touch darker / more shaded than the cheeks.
            val neckShade = if (y < head.minY) 0.93f else 1f
            val base = scale(skin, neckShade)
            return mix(base, hairAt(x, y, z), head.hairColorAmount(x, y, z))
        }

        fun override(x: Float, y: Float, z: Float, seen: Int?): Int {
            val painted = color(x, y, z)
            val fm = head.faceMask(x, y, z)
            return if (fm > 0f && seen != null) mix(painted, seen, fm) else painted
        }
    }

    private fun finishMesh(
        vol: TsdfVolume,
        keyframes: List<Keyframe>,
        cx: Float, cy: Float, cz: Float,
        frontAngle: Float,
        colorSource: ColorSource,
        hairFill: HairFill?,
        crop: Boolean,
        progress: (String, Float) -> Unit,
        smoothing: Int = settings.smoothingIterations,
    ): ScanResult {
        val s = settings
        // 2. Surface extraction and clean-up
        progress("Extracting surface", 0.36f)
        var mesh = SurfaceNets.extract(vol, s.minWeight)
        if (mesh.faceCount == 0) throw ScanException("No surface found. Keep the head centred and the phone 30-80 cm away.")
        val neckTop = cy - s.neckStart
        val r2 = s.neckRadius * s.neckRadius
        val collarTop = cy - s.collarStart
        val src = mesh
        if (crop) mesh = MeshOps.filterFaces(src) { f ->
            var keep = true
            for (c in 0 until 3) {
                val v = src.faces[3 * f + c]
                val x = src.positions[3 * v] - cx; val y = src.positions[3 * v + 1]; val z = src.positions[3 * v + 2] - cz
                if (y < neckTop && x * x + z * z > r2) keep = false
            }
            // Below the chin, drop horizontal surfaces (tops of shoulders / collars) so the neck ends cleanly.
            if (keep && MeshOps.faceCentroidY(src, f) < collarTop && kotlin.math.abs(MeshOps.faceNormalY(src, f)) > 0.6f) keep = false
            keep
        }
        mesh = MeshOps.largestComponent(mesh)
        if (mesh.faceCount < s.targetTriangles / 4) throw ScanException("Scan too sparse (${mesh.faceCount} faces). Capture more angles.")
        progress("Smoothing", 0.42f)
        MeshOps.taubinSmooth(mesh, smoothing)

        // 3. Decimation
        progress("Reducing to ${s.targetTriangles} triangles", 0.45f)
        mesh = Decimator(mesh).simplify(s.targetTriangles)
        mesh = MeshOps.compact(mesh)
        val normals = MeshOps.vertexNormals(mesh)

        // 4. UV atlas + texture bake
        progress("Baking texture", 0.55f)
        val atlas = UvAtlas(mesh.faceCount, s.textureSize)
        val baker = TextureBaker(mesh, normals, atlas)
        keyframes.forEachIndexed { i, kf ->
            progress("Projecting photo ${i + 1}/${keyframes.size}", 0.55f + 0.4f * i / keyframes.size)
            baker.addView(kf, colorSource.load(kf))
        }
        hairFill?.prepare(baker)
        val texture = if (hairFill != null) baker.finish(
            fillColor = { x, y, z -> hairFill.color(x, y, z) },
            overrideColor = { x, y, z, seen -> hairFill.override(x, y, z, seen) },
        ) else baker.finish()
        if (s.style == HeadStyle.GOLDENEYE_SOURCE) {
            baker.bakeShading(texture, SourceStyle.vertexOcclusion(mesh, normals), SourceStyle::shade)
            SourceStyle.grade(texture, s.textureSize)
        }
        val flat = false

        // 5. Move into a head-centred frame, face toward +Z, and write OBJ.
        progress("Writing OBJ", 0.97f)
        MeshOps.transformToHeadFrame(mesh, cx, cy, cz, frontAngle)
        val outNormals = if (flat) MeshOps.faceNormals(mesh) else MeshOps.vertexNormals(mesh)
        progress("Done", 1f)
        return ScanResult(mesh, outNormals, flat, atlas, texture, s.textureSize)
    }

    companion object {
        const val MATERIAL = "head_material"
    }
}
