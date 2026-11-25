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
)

class ScanResult(
    val objText: String,
    val mtlText: String,
    /** ARGB, row 0 = top, textureSize x textureSize. */
    val texture: IntArray,
    val textureSize: Int,
    val triangleCount: Int,
    val vertexCount: Int,
)

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
        textureFileName: String,
        mtlFileName: String,
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
        return finishMesh(vol, keyframes, cx, cy, cz, atan2(fx, fz), colorSource, textureFileName, mtlFileName, null, crop = true, progress)
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
        textureFileName: String,
        mtlFileName: String,
        progress: (String, Float) -> Unit = { _, _ -> },
    ): ScanResult {
        if (keyframes.size < 3) throw ScanException("Need at least 3 captured views, got ${keyframes.size}.")
        progress("Fitting head to your face", 0.05f)
        val head = SelfieHead(faceVertices, faceTriangles)
        val vol = head.buildVolume(0.003f, 0.012f)
        val hair = HairFill(head)
        return finishMesh(vol, keyframes, head.headCx, head.headCy, head.headCz, 0f, colorSource,
            textureFileName, mtlFileName, hair, crop = false, progress, smoothing = 3)
    }

    /** Picks hair / skin colours for parts of the generic head that no photo saw. */
    private class HairFill(val head: SelfieHead) {
        var hair = 0xFF3A2A1E.toInt()
        var skin = 0xFFC89F86.toInt()
        fun prepare(baker: TextureBaker) {
            baker.meanSeenColor { x, y, z -> y < head.maxY - 0.03f && y > head.minY + 0.03f && z > head.headCz }?.let { skin = it }
            baker.meanSeenColor(30) { _, y, z -> y > head.maxY + 0.005f && z > head.headCz }?.let { hair = it }
        }
        fun color(x: Float, y: Float, z: Float) = if (head.isHairRegion(x, y, z)) hair else skin
    }

    private fun finishMesh(
        vol: TsdfVolume,
        keyframes: List<Keyframe>,
        cx: Float, cy: Float, cz: Float,
        frontAngle: Float,
        colorSource: ColorSource,
        textureFileName: String,
        mtlFileName: String,
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
        val texture = if (hairFill != null) baker.finish { x, y, z -> hairFill.color(x, y, z) } else baker.finish()

        // 5. Move into a head-centred frame, face toward +Z, and write OBJ.
        progress("Writing OBJ", 0.97f)
        MeshOps.transformToHeadFrame(mesh, cx, cy, cz, frontAngle)
        val outNormals = MeshOps.vertexNormals(mesh)
        val obj = ObjWriter.obj(mesh, outNormals, atlas, mtlFileName, MATERIAL)
        val mtl = ObjWriter.mtl(MATERIAL, textureFileName)
        progress("Done", 1f)
        return ScanResult(obj, mtl, texture, s.textureSize, mesh.faceCount, mesh.vertexCount)
    }

    companion object {
        const val MATERIAL = "head_material"
    }
}
