package com.headscan.core

import kotlin.math.atan2
import kotlin.math.max
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
    /** Selfie mode only: skull measured from the photos (null = measure it if outlines are given, else average). */
    val headShape: HeadShape? = null,
    /** Selfie mode only: AI sharpen / denoise of the photo face (needs [AiModels.sharpen] / [AiModels.denoise]). */
    val enhance: Enhance = Enhance.NONE,
    /** Selfie mode only: AI segmenter tells skin from hair, eyebrows and clothes when measuring and evening the skin. */
    val aiSkin: Boolean = false,
    /** Selfie mode only: shift the painted head skin onto the finished face colour. */
    val matchHeadSkin: Boolean = false,
    /** Debug, selfie mode: use the photos on the whole head wherever they saw skin, not only on the face. */
    val wholeHeadPhoto: Boolean = false,
    /** Debug, selfie mode: where the segmenter saw hair in the photos, use the photo hair. */
    val photoHair: Boolean = false,
    /** Debug, selfie mode: cover parts no photo saw with a large patch of the person's real cheek skin. */
    val realSkinFill: Boolean = false,
    /** Debug, selfie mode: wider face-to-head blend and the head skin always matched to the face. */
    val wideBlend: Boolean = false,
    /** Selfie mode: copy the better-seen side of the face onto a side the photos only saw at an angle. */
    val mirrorFace: Boolean = true,
    /** Debug, selfie mode: real skin laid as many small randomly placed and turned pieces (no repeating pattern). */
    val skinScatter: Boolean = false,
    /** Debug, selfie mode: natural colour variation on the painted skin (blotches, redder ears, paler scalp, neck). */
    val skinTint: Boolean = false,
    /** Selfie mode: facial hair painted on, coloured from the photo. */
    val beard: Beard = Beard.NONE,
    /** Fix photo, selfie mode: carry the facial hair seen at the edge of the face on over the painted cheeks, jaw and neck. */
    val continueFacialHair: Boolean = false,
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
    /** Per-texel hair amount 0..1 (Selfie mode), used to recolour the hair; null if not available. */
    val hairMask: FloatArray? = null,
    /** The hair colour the texture was painted with (ARGB). */
    val hairBase: Int = 0,
    /**
     * Per face: smooth-shaded (true) or flat (false), for the low-poly game look where big planes are
     * faceted but eyes and lips stay smooth. Null = all faces follow [flatShaded]. [normals] are per vertex then.
     */
    val smoothFaces: BooleanArray? = null,
    /** Selfie mode: the skull used (measured or average), and how it was measured (null = average proportions). */
    val headShape: HeadShape? = null,
    val headFit: HeadFit.Result? = null,
    /** Selfie mode: head width / height (top to chin) / depth (nose to back), metres. */
    val headSize: FloatArray? = null,
) {
    val canRecolorHair get() = hairMask != null

    /** A copy with the hair recoloured (see [HairColor.recolor]). */
    fun withHairColor(hue: Float?, tone: Float): ScanResult {
        val mask = hairMask ?: return this
        return ScanResult(mesh, normals, flatShaded, atlas, HairColor.recolor(texture, mask, hairBase, hue, tone),
            textureSize, hairMask, hairBase, smoothFaces, headShape, headFit, headSize)
    }

    val triangleCount get() = mesh.faceCount
    val vertexCount get() = mesh.vertexCount

    fun objText(mtlFileName: String = "head.mtl"): String =
        ObjWriter.obj(mesh, normals, atlas, mtlFileName, HeadScanPipeline.MATERIAL, flatShaded, smoothFaces)

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
        return ScanResult(m, n, flatShaded, atlas, texture, textureSize, hairMask, hairBase, smoothFaces, headShape, headFit, headSize)
    }

    /** Un-indexed triangle arrays for real-time preview: xyz, normal, texture st (t = 0 at the top row). */
    fun previewArrays(): Triple<FloatArray, FloatArray, FloatArray> {
        val n = mesh.faceCount * 3
        val pos = FloatArray(n * 3); val nrm = FloatArray(n * 3); val st = FloatArray(n * 2)
        val fn = if (smoothFaces != null) MeshOps.faceNormals(mesh) else null
        for (f in 0 until mesh.faceCount) for (c in 0 until 3) {
            val v = mesh.faces[3 * f + c]
            val o = (3 * f + c)
            for (k in 0 until 3) {
                pos[3 * o + k] = mesh.positions[3 * v + k]
                nrm[3 * o + k] = when {
                    fn != null -> if (smoothFaces!![f]) normals[3 * v + k] else fn[3 * f + k]
                    flatShaded -> normals[3 * f + k]
                    else -> normals[3 * v + k]
                }
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
        // Close the surface where the scan didn't reach (it only needs to go about three-quarters of the way round).
        progress("Filling unseen areas", 0.35f)
        vol.fillUnseen(cx, cz, s.minWeight)

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
        /** The person's outline in the photos, for measuring the head (see [HeadFit]). */
        silhouettes: List<SilhouetteView>? = null,
    ): ScanResult {
        if (keyframes.isEmpty()) throw ScanException("No photos to build the head from.")
        progress("Fitting head to your face", 0.05f)
        var shape = settings.headShape
        var fitNote: HeadFit.Result? = null
        if (shape == null && silhouettes != null && silhouettes.isNotEmpty()) {
            progress("Measuring your head from the photos", 0.07f)
            val avg = SelfieHead(faceVertices, faceTriangles, HairStyle.NONE, floatArrayOf(settings.faceOffsetX, settings.faceOffsetY, settings.faceOffsetZ), model = null)
            val start = HeadShape(avg.rx, avg.headCy + avg.ry, avg.headCz + avg.rz, avg.headCz - avg.rz)
            fitNote = try { HeadFit.fit(silhouettes, faceVertices, faceTriangles, start, avg.headCy, avg.minY, avg.maxY) } catch (e: Throwable) { null }
            shape = fitNote?.shape
        }
        val head = SelfieHead(faceVertices, faceTriangles, settings.hairStyle,
            floatArrayOf(settings.faceOffsetX, settings.faceOffsetY, settings.faceOffsetZ),
            shape)
        val vol = head.buildVolume(0.003f, 0.012f)
        val hair = HairFill(head, settings)
        val r = finishMesh(vol, keyframes, head.headCx, head.headCy, head.headCz, 0f, colorSource, hair, crop = false, progress, smoothing = 3)
        val used = HeadShape(head.rx, head.headCy + head.ry, head.headCz + head.rz, head.headCz - head.rz)
        val size = floatArrayOf(2 * head.rx, used.top - head.minY, head.noseZ - used.zBack)
        val fit = fitNote
        return ScanResult(r.mesh, r.normals, r.flatShaded, r.atlas, r.texture, r.textureSize, r.hairMask, r.hairBase, r.smoothFaces,
            used, fit, size)
    }

    /**
     * Paints the generic head: skin tone measured from the cheeks and forehead, hair colour from just above the
     * forehead, a soft hairline, and the photo face blended into the skin at its border.
     */
    private class HairFill(val head: SelfieHead, val s: ScanSettings) {
        var hair = 0xFF3A2A1E.toInt()
        var skin = 0xFFC89F86.toInt()
        /** AI segmenter classes of the surface, when [ScanSettings.aiSkin] is on. */
        var labels: LabelGrid? = null
        fun isSkin(x: Float, y: Float, z: Float) = labels?.skinOrUnknown(x, y, z) ?: true
        /** How much of the photo to use away from the face (per texel, and as a texture map); null = face only. */
        var photoT: FloatArray? = null
        var photoMap: FloatArray? = null
        fun photoAt(t: Int) = photoT?.get(t) ?: 0f
        fun prepare(baker: TextureBaker) {
            labels = baker.labelGrid() ?: labels
            if (s.wholeHeadPhoto || s.photoHair) {
                val (pt, pm) = baker.texelField(4) { t ->
                    val score = baker.seenScore(t)
                    if (score <= 0f) return@texelField 0f
                    val x = baker.texelX(t); val y = baker.texelY(t); val z = baker.texelZ(t)
                    // Only well-seen texels: oblique or far views smear.
                    val q = smoothstep(0.2f, 0.45f, score)
                    val l = baker.texelLabel(t)
                    var w = 0f
                    if (s.wholeHeadPhoto && (l < 0 || l == SkinLabels.FACE_SKIN || l == SkinLabels.BODY_SKIN))
                        w = q * (1f - head.hairColorAmount(x, y, z))       // a chosen hairstyle stays on top
                    if (s.photoHair && l == SkinLabels.HAIR) w = q
                    w * (1f - head.lockCover(x, y, z))
                }
                photoT = pt; photoMap = pm
            }
            val h = head.maxY - head.minY
            baker.robustSeenColor { x, y, z ->
                if (head.faceMask(x, y, z) < 0.9f || !isSkin(x, y, z)) return@robustSeenColor false
                val ax = kotlin.math.abs(x)
                val cheek = y > head.minY + 0.38f * h && y < head.minY + 0.55f * h && ax > 0.022f && ax < 0.048f
                val forehead = y > head.minY + 0.78f * h && y < head.maxY - 0.015f && ax < 0.03f
                cheek || forehead
            }?.let { skin = it }
            val lab = labels
            // With the segmenter: hair is wherever it saw hair above the eyes; otherwise just above the forehead.
            val aiHair = if (lab == null) null else baker.robustSeenColor(30) { x, y, z ->
                y > head.minY + 0.7f * h && z > head.headCz - 0.3f * head.rz && lab.at(x, y, z) == SkinLabels.HAIR
            }
            (aiHair ?: baker.robustSeenColor(30) { x, y, z ->
                y > head.maxY + 0.004f && y < head.maxY + 0.035f && z > head.headCz && kotlin.math.abs(x) < 0.05f
            })?.let { hair = it }
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
            head.spikeShade(x, y, z)?.let { return scale(hair, it * (0.97f + 0.03f * n)) }
            head.lockShade(x, y, z)?.let { return scale(hair, it * (0.97f + 0.03f * n)) }
            head.paintedHair(x, y, z)?.let { return scale(hair, it * (0.98f + 0.02f * n)) }
            return scale(hair, 0.9f + 0.12f * strands + 0.05f * n)
        }

        fun color(x: Float, y: Float, z: Float): Int {
            // Necks are usually a touch darker / more shaded than the cheeks.
            val neckShade = if (y < head.minY) 0.93f else 1f
            val base = scale(skin, neckShade)
            return mix(base, hairAt(x, y, z), head.hairColorAmount(x, y, z))
        }

        fun override(x: Float, y: Float, z: Float, seen: Int?, t: Int): Int {
            val painted = color(x, y, z)
            // Photo only where the shape really is the scanned face (the border band is blended geometry,
            // where photos land slightly off and smear into streaks), unless the whole-head photo option is on.
            val lo = if (s.wideBlend) 0.2f else 0.45f
            val m = ((head.faceMask(x, y, z) - lo) / (1f - lo)).coerceIn(0f, 1f)
            val fm = kotlin.math.max(m * m * (3f - 2f * m) * (1f - head.lockCover(x, y, z)), photoAt(t))
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
        // Game-style layout: big islands, with the face given about twice the texel density of the rest.
        val fNormals = MeshOps.faceNormals(mesh)
        val ca = kotlin.math.cos(frontAngle); val sa = kotlin.math.sin(frontAngle)
        val atlas = ChartAtlas.build(mesh, s.textureSize, frontAngle) { f ->
            val nz = fNormals[3 * f] * sa + fNormals[3 * f + 2] * ca          // head-frame forward component
            var imp = 1f + 0.5f * kotlin.math.max(0f, nz)
            if (hairFill != null) {
                val v0 = mesh.faces[3 * f]
                if (hairFill.head.faceMask(mesh.positions[3 * v0], mesh.positions[3 * v0 + 1], mesh.positions[3 * v0 + 2]) > 0.3f) imp = 2f
            }
            imp
        }
        val baker = TextureBaker(mesh, normals, atlas)
        val quality = TextureBaker.qualities(keyframes)
        val segment = if (hairFill != null && (s.aiSkin || s.wholeHeadPhoto || s.photoHair)) AiModels.segment else null
        keyframes.forEachIndexed { i, kf ->
            progress("Projecting photo ${i + 1}/${keyframes.size}", 0.55f + 0.3f * i / keyframes.size)
            val img = colorSource.load(kf)
            val labels = if (segment != null) {
                progress("AI skin detection ${i + 1}/${keyframes.size}", 0.55f + 0.3f * i / keyframes.size)
                try { SkinLabels.of(img, kf, segment) } catch (e: Exception) { null }
            } else null
            baker.addView(kf, img, quality = quality[i], labels = labels)
        }
        hairFill?.prepare(baker)
        val texture = if (hairFill != null) baker.finish(
            fillColor = { x, y, z -> hairFill.color(x, y, z) },
            overrideColor = { x, y, z, seen, t -> hairFill.override(x, y, z, seen, t) },
        ) else baker.finish()
        if (hairFill != null) improveSkin(baker, texture, hairFill)
        if (hairFill != null && (s.beard != Beard.NONE || s.continueFacialHair)) {
            progress("Facial hair", 0.86f)
            facialHair(baker, texture, hairFill)
        }
        val net = if (hairFill == null) null else when (s.enhance) {
            Enhance.SHARPEN -> AiModels.sharpen
            Enhance.DENOISE -> AiModels.denoise
            Enhance.NONE -> null
        }
        if (net != null && hairFill != null) {
            val head = hairFill.head
            val label = if (s.enhance == Enhance.SHARPEN) "AI sharpen" else "AI denoise"
            val mask = baker.texelMap { x, y, z -> smoothstep(0.2f, 0.7f, head.faceMask(x, y, z)) }
            try {
                TextureEnhance.apply(texture, s.textureSize, mask, net) { p -> progress("$label (Real-ESRGAN) ${(p * 100).toInt()}%", 0.86f + 0.1f * p) }
            } catch (e: Exception) { /* keep the un-enhanced texture */ }
        }
        if (s.style == HeadStyle.GOLDENEYE_SOURCE) {
            baker.bakeShading(texture, SourceStyle.vertexOcclusion(mesh, normals), SourceStyle::shade)
            SourceStyle.grade(texture, s.textureSize)
        }
        baker.refreshPadding(texture)
        val flat = false
        // Low-poly game look (GoldenEye: Source style): big planes flat-shaded, eyes, lips and ears smooth.
        val smoothFaces = if (s.style == HeadStyle.GOLDENEYE_SOURCE) smoothMask(mesh, normals, hairFill?.head) else null

        // 5. Move into a head-centred frame, face toward +Z, and write OBJ.
        progress("Writing OBJ", 0.97f)
        MeshOps.transformToHeadFrame(mesh, cx, cy, cz, frontAngle)
        val outNormals = if (flat) MeshOps.faceNormals(mesh) else MeshOps.vertexNormals(mesh)
        progress("Done", 1f)
        val hairMask = hairFill?.let { hf -> baker.texelMap { x, y, z -> hf.head.hairColorAmount(x, y, z) } }
        return ScanResult(mesh, outNormals, flat, atlas, texture, s.textureSize, hairMask, hairFill?.hair ?: 0, smoothFaces)
    }

    /**
     * Selfie head skin: evens out the lighting on the photo face, mirrors the better-seen side of the face onto
     * a badly seen side, and gives the painted skin (sides, back, neck) real pore/stubble detail cut from the
     * photo instead of a flat colour.
     */
    private fun improveSkin(baker: TextureBaker, texture: IntArray, hf: HairFill) {
        val head = hf.head
        // Only the photo face is de-lit: the rest of the head is painted skin, matched to the face afterwards
        // (de-lighting part of it left a visible line down the side of the head).
        val faceAmt = baker.texelMap { x, y, z -> head.faceMask(x, y, z) }
        // With the whole-head photo option, the photo on the rest of the head is de-lit too.
        hf.photoMap?.let { pm -> for (i in faceAmt.indices) faceAmt[i] = kotlin.math.max(faceAmt[i], pm[i]) }
        val sk = hf.skin
        val skinLuma = 0.299f * ((sk shr 16) and 255) + 0.587f * ((sk shr 8) and 255) + 0.114f * (sk and 255)
        // Take the lighting out of the photo: plain skin only for the fit (not eyes, brows, lips or hair).
        baker.removeLighting(texture, faceAmt, skinLuma) { x, y, z ->
            !head.isDetailArea(x, y, z) && head.hairColorAmount(x, y, z) < 0.05f && y < head.maxY - 0.02f && hf.isSkin(x, y, z)
        }
        if (settings.mirrorFace) baker.mirrorWeakSide(texture, (head.minX + head.maxX) / 2) { x, y, z -> smoothstep(0.35f, 0.75f, head.faceMask(x, y, z)) }
        // Carry the real skin colour at the edge of the face out onto the sides of the head (cheeks, temples,
        // towards the ears) so there's no colour change at the face edge.
        extendFaceColour(baker, texture, hf)
        val h = head.maxY - head.minY
        if (settings.matchHeadSkin || settings.wideBlend) matchHeadSkinToFace(baker, texture, hf)
        val cheek = { x: Float, y: Float, z: Float ->
            head.faceMask(x, y, z) > 0.95f && hf.isSkin(x, y, z) && y > head.minY + 0.3f * h && y < head.minY + 0.5f * h &&
                kotlin.math.abs(x - (head.minX + head.maxX) / 2) > 0.2f * (head.maxX - head.minX)
        }
        earSkin(baker, texture, hf)
        if (settings.skinTint) tintSkin(baker, texture, hf)
        if (settings.realSkinFill || settings.skinScatter) {
            // Any plain skin on the face: cheeks, forehead, chin sides (not eyes, brows, nose, lips).
            val cx = (head.minX + head.maxX) / 2; val fw = head.maxX - head.minX
            val cp = baker.skinColourPatch(texture, { x, y, z ->
                head.faceMask(x, y, z) > 0.9f && hf.isSkin(x, y, z) && !head.isDetailArea(x, y, z) &&
                    y > head.minY + 0.2f * h && y < head.maxY - 0.01f &&
                    (kotlin.math.abs(x - cx) > 0.14f * fw || y > head.minY + 0.72f * h)
            }, intArrayOf(112, 96, 80, 64, 48, 32))
            if (cp != null) { paintRealSkin(baker, texture, hf, cp); return }
        }
        val patchSize = 40
        val patch = baker.skinDetailPatch(texture, cheek, patchSize) ?: return
        val tile = 0.03f     // metres covered by one repeat of the patch
        fun sample(a: Float, b: Float): Float {
            val u = ((a / tile) % 1f + 1f) % 1f * patchSize; val v = ((b / tile) % 1f + 1f) % 1f * patchSize
            return patch[(v.toInt() % patchSize) * patchSize + (u.toInt() % patchSize)]
        }
        baker.mapTexelsT(texture) { t, x, y, z, nx, ny, nz, c ->
            val k = (1f - head.faceMask(x, y, z)) * (1f - head.hairColorAmount(x, y, z)) * (1f - hf.photoAt(t)) * (1f - head.onEar(x, y, z))
            if (k <= 0.02f) return@mapTexelsT c
            // triplanar: blend the patch projected along the three axes by the normal
            val wx = nx * nx; val wy = ny * ny; val wz = nz * nz
            val d = (sample(y, z) * wx + sample(x, z) * wy + sample(x, y) * wz) / (wx + wy + wz) * 0.85f * k
            fun ch(sh: Int) = (((c shr sh) and 255) + d).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }

    /**
     * Facial hair: [ScanSettings.continueFacialHair] measures how much beard or stubble the photo shows at the edge
     * of the face and carries it on over the painted cheeks, jaw and under the chin; [ScanSettings.beard] paints a
     * chosen beard over the whole beard area. Both use the facial hair colour from the photo.
     */
    private fun facialHair(baker: TextureBaker, texture: IntArray, hf: HairFill) {
        val head = hf.head
        val h = head.maxY - head.minY
        fun luma(c: Int) = 0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)
        // Plain skin brightness (upper cheeks, away from any beard) and the photo just inside the face edge in the beard area.
        val skinL = ArrayList<Float>(); val edge = ArrayList<Int>()
        baker.mapTexels(texture) { x, y, z, _, _, _, c ->
            val fm = head.faceMask(x, y, z)
            if (!head.isDetailArea(x, y, z) && hf.isSkin(x, y, z)) {
                val zone = head.beardZone(x, y, z, 0.02f)
                if (fm > 0.95f && zone < 0.02f && y > head.minY + 0.35f * h && y < head.minY + 0.6f * h) skinL.add(luma(c))
                else if (fm in 0.6f..0.97f && zone > 0.6f) edge.add(c)
            }
            c
        }
        if (skinL.size < 50 || edge.size < 50) return
        skinL.sort()
        val sl = skinL[skinL.size / 2].coerceAtLeast(1f)
        val byL = edge.sortedBy { luma(it) }
        val el = luma(byL[byL.size / 2])
        // Facial hair colour: the darker third of the edge texels.
        var r = 0f; var g = 0f; var b = 0f
        val n = (byL.size / 3).coerceAtLeast(1)
        for (i in 0 until n) { val c = byL[i]; r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255 }
        var hairCol = (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
        // How dark the edge is compared with plain skin: about 0 = clean shaven, 1 = full beard.
        val density = ((sl - el) / (0.45f * sl)).coerceIn(0f, 1f)
        if (luma(hairCol) > 0.85f * sl) hairCol = hf.hair      // couldn't see any facial hair: use the head hair colour
        if (settings.continueFacialHair && density > 0.08f) {
            val style = when { density < 0.35f -> Beard.STUBBLE; density < 0.7f -> Beard.SHORT; else -> Beard.FULL }
            paintBeard(baker, texture, hf, style, hairCol, (density * 1.6f).coerceAtMost(1f), onlyPainted = true)
        }
        if (settings.beard != Beard.NONE) paintBeard(baker, texture, hf, settings.beard, hairCol, 1f, onlyPainted = false)
    }

    private fun paintBeard(baker: TextureBaker, texture: IntArray, hf: HairFill, style: Beard, colour: Int, strength: Float, onlyPainted: Boolean) {
        val head = hf.head
        val reach = when (style) { Beard.STUBBLE -> 0.015f; Beard.SHORT -> 0.025f; else -> 0.035f }
        val base = when (style) { Beard.STUBBLE -> 0.1f; Beard.SHORT -> 0.5f; else -> 0.8f }
        val dots = when (style) { Beard.STUBBLE -> 0.3f; Beard.SHORT -> 0.6f; else -> 0.4f }
        fun hash3(i: Int, j: Int, k: Int): Float {
            var hh = i * 374761393 + j * 668265263 + k * 2147483629
            hh = (hh xor (hh ushr 13)) * 1274126177
            return ((hh xor (hh ushr 16)) and 0xFFFFFF) / 16777215f
        }
        // Smooth value noise 0..1 (trilinear between hashed lattice points).
        fun vnoise(fx: Float, fy: Float, fz: Float): Float {
            val ix = kotlin.math.floor(fx).toInt(); val iy = kotlin.math.floor(fy).toInt(); val iz = kotlin.math.floor(fz).toInt()
            fun sm(t: Float) = t * t * (3f - 2f * t)
            val tx = sm(fx - ix); val ty = sm(fy - iy); val tz = sm(fz - iz)
            var v = 0f
            for (c in 0 until 8) {
                val dx = c and 1; val dy = (c shr 1) and 1; val dz = (c shr 2) and 1
                v += (if (dx == 1) tx else 1f - tx) * (if (dy == 1) ty else 1f - ty) * (if (dz == 1) tz else 1f - tz) * hash3(ix + dx, iy + dy, iz + dz)
            }
            return v
        }
        val cr = (colour shr 16) and 255; val cg = (colour shr 8) and 255; val cb = colour and 255
        baker.mapTexelsT(texture) { t, x, y, z, _, _, _, c ->
            var w = head.beardZone(x, y, z, reach) * strength
            if (onlyPainted) w *= (1f - head.faceMask(x, y, z)) * (1f - hf.photoAt(t))
            if (w <= 0.01f) return@mapTexelsT c
            // Hairs: a coverage tone plus a soft hair pattern (smooth noise, no blocks; drawn out downwards for
            // longer beards so it reads as strands).
            val cell = if (style == Beard.STUBBLE) 0.0012f else 0.0016f
            val stretch = if (style == Beard.STUBBLE) 1f else 2.2f
            val v = 0.6f * vnoise(x / cell, y / (cell * stretch), z / cell) + 0.4f * vnoise(x / (0.5f * cell) + 17f, y / (0.5f * cell * stretch), z / (0.5f * cell))
            val hairs = smoothstep(1f - dots - 0.12f, 1f - dots + 0.12f, v)
            val a = base + (1f - base) * hairs * (if (style == Beard.STUBBLE) 0.55f else 0.85f)
            val shade = 0.85f + 0.3f * v
            val k = (a * w).coerceIn(0f, 1f)
            fun ch(sh: Int, hc: Int) = (((c shr sh) and 255) * (1f - k) + (hc * shade).coerceAtMost(255f) * k).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (ch(16, cr) shl 16) or (ch(8, cg) shl 8) or ch(0, cb)
        }
    }

    /**
     * Natural colour variation on the painted skin: soft large blotches, redder ears, a paler scalp with very faint
     * stubble dots, and a slightly darker neck, so the head doesn't look evenly painted.
     */
    private fun tintSkin(baker: TextureBaker, texture: IntArray, hf: HairFill) {
        val head = hf.head
        fun hash3(i: Int, j: Int, k: Int): Float {
            var h = i * 374761393 + j * 668265263 + k * 2147483629
            h = (h xor (h ushr 13)) * 1274126177
            return ((h xor (h ushr 16)) and 0xFFFFFF) / 16777215f
        }
        // Smooth 3D value noise, about -1..1.
        fun noise(x: Float, y: Float, z: Float, scale: Float, salt: Int): Float {
            val fx = x / scale; val fy = y / scale; val fz = z / scale
            val ix = kotlin.math.floor(fx).toInt(); val iy = kotlin.math.floor(fy).toInt(); val iz = kotlin.math.floor(fz).toInt()
            fun sm(t: Float) = t * t * (3f - 2f * t)
            val tx = sm(fx - ix); val ty = sm(fy - iy); val tz = sm(fz - iz)
            var v = 0f
            for (c in 0 until 8) {
                val dx = c and 1; val dy = (c shr 1) and 1; val dz = (c shr 2) and 1
                val w = (if (dx == 1) tx else 1f - tx) * (if (dy == 1) ty else 1f - ty) * (if (dz == 1) tz else 1f - tz)
                v += w * hash3(ix + dx + salt, iy + dy, iz + dz)
            }
            return v * 2f - 1f
        }
        val scalpY = head.headCy + 0.35f * head.ry
        baker.mapTexelsT(texture) { t, x, y, z, _, _, _, c ->
            val k = (1f - head.faceMask(x, y, z)) * (1f - head.hairColorAmount(x, y, z)) * (1f - hf.photoAt(t))
            if (k <= 0.02f) return@mapTexelsT c
            var r = 1f; var g = 1f; var b = 1f
            // Blotches: brightness and a little redness, at two sizes.
            val big = noise(x, y, z, 0.07f, 11); val small = noise(x, y, z, 0.025f, 37)
            val light = 0.035f * big + 0.02f * small
            val red = 0.025f * noise(x, y, z, 0.04f, 71)
            r *= 1f + light + red; g *= 1f + light - 0.4f * red; b *= 1f + light - 0.6f * red
            // Ears have their own look (see earSkin); no blotches or scalp dots there.
            val ear = head.onEar(x, y, z)
            if (ear >= 1f) return@mapTexelsT c
            // Scalp: slightly paler and less red, with very faint dots where hair would grow.
            val scalp = smoothstep(scalpY - 0.02f, scalpY + 0.02f, y)
            if (scalp > 0f) {
                r *= 1f + 0.01f * scalp; g *= 1f + 0.025f * scalp; b *= 1f + 0.035f * scalp
                val dot = hash3(kotlin.math.floor(x / 0.0014f).toInt(), kotlin.math.floor(y / 0.0014f).toInt(), kotlin.math.floor(z / 0.0014f).toInt())
                if (dot < 0.05f) { val d = 1f - 0.06f * scalp; r *= d; g *= d; b *= d }
            }
            // Neck: a little darker and warmer below the jaw.
            val neck = smoothstep(head.minY + 0.01f, head.minY - 0.04f, y)
            r *= 1f - 0.03f * neck; g *= 1f - 0.05f * neck; b *= 1f - 0.06f * neck
            val ke = k * (1f - ear)
            fun ch(sh: Int, m: Float): Int { val v = ((c shr sh) and 255).toFloat(); return (v * (1f + (m - 1f) * ke)).toInt().coerceIn(0, 255) }
            (0xFF shl 24) or (ch(16, r) shl 16) or (ch(8, g) shl 8) or ch(0, b)
        }
    }

    /**
     * Ears get their own skin rather than the head's: a little redder and pinker (thin skin), smooth (no repeated skin
     * pattern), and darker in the hollows (where the surface faces into the ear) and lighter on the rim.
     */
    private fun earSkin(baker: TextureBaker, texture: IntArray, hf: HairFill) {
        val head = hf.head
        baker.mapTexelsT(texture) { t, x, y, z, nx, _, nz, c ->
            val e = head.onEar(x, y, z) * (1f - head.faceMask(x, y, z)) * (1f - hf.photoAt(t)) * (1f - head.hairColorAmount(x, y, z))
            if (e <= 0.01f) return@mapTexelsT c
            // Facing outward-sideways = the rim and outer surface; facing forward/back = inside the folds.
            val side = kotlin.math.abs(nx) / (kotlin.math.abs(nx) + kotlin.math.abs(nz) + 1e-3f)
            val depth = (head.earSdf(x, y, z) + 0.002f).coerceIn(-0.004f, 0f) / -0.004f     // how far inside the ear shape
            val light = 0.94f + 0.1f * side - 0.08f * depth
            val r = 1.07f * light; val g = 0.97f * light; val b = 0.95f * light
            fun ch(sh: Int, m: Float): Int { val v = ((c shr sh) and 255).toFloat(); return (v * (1f + (m - 1f) * e)).toInt().coerceIn(0, 255) }
            (0xFF shl 24) or (ch(16, r) shl 16) or (ch(8, g) shl 8) or ch(0, b)
        }
    }

    /**
     * Covers the painted skin (parts no photo saw: back of the head, under the chin...) with real skin: a large patch
     * cut from the cheek, repeated mirror-wise (so it has no seams) and projected along the three axes.
     */
    private fun paintRealSkin(baker: TextureBaker, texture: IntArray, hf: HairFill, cp: Triple<FloatArray, Int, Float>) {
        val head = hf.head
        val (pat, n, metres) = cp
        fun idx(v: Float): Int {
            val u = ((v / metres) % 2f + 2f) % 2f * n
            val i = u.toInt().coerceIn(0, 2 * n - 1)
            return if (i < n) i else 2 * n - 1 - i
        }
        fun tiled(a: Float, b: Float, k: Int): Float = pat[3 * (idx(b) * n + idx(a)) + k]
        // Scattered: the surface is split into cells smaller than the patch; each cell shows the patch from a random
        // place and at a random angle, and neighbouring cells blend smoothly, so nothing repeats.
        val cellSize = metres * 0.4f
        fun hash(i: Int, j: Int, salt: Int): Float {
            var h = i * 374761393 + j * 668265263 + salt * 1274126177
            h = (h xor (h ushr 13)) * 1274126177
            return ((h xor (h ushr 16)) and 0xFFFFFF) / 16777215f
        }
        fun scattered(a: Float, b: Float, k: Int, plane: Int): Float {
            val fa = a / cellSize - 0.5f; val fb = b / cellSize - 0.5f
            val i0 = kotlin.math.floor(fa).toInt(); val j0 = kotlin.math.floor(fb).toInt()
            val ta = fa - i0; val tb = fb - j0
            var sum = 0f; var w2 = 0f
            for (dj in 0..1) for (di in 0..1) {
                val i = i0 + di; val j = j0 + dj
                val w = (if (di == 0) 1f - ta else ta) * (if (dj == 0) 1f - tb else tb)
                if (w <= 0f) continue
                val ang = hash(i, j, plane * 3) * 6.2831853f
                val ox = hash(i, j, plane * 3 + 1) * metres; val oy = hash(i, j, plane * 3 + 2) * metres
                val la = a - (i + 0.5f) * cellSize; val lb = b - (j + 0.5f) * cellSize
                val ca = kotlin.math.cos(ang); val sa = kotlin.math.sin(ang)
                sum += w * tiled(ca * la - sa * lb + ox, sa * la + ca * lb + oy, k)
                w2 += w * w
            }
            // Blending pieces lowers the contrast: scale it back so the skin grain stays as strong.
            return if (w2 > 0f) sum / kotlin.math.sqrt(w2) else 0f
        }
        fun sample(a: Float, b: Float, k: Int, plane: Int): Float =
            if (settings.skinScatter) scattered(a, b, k, plane) else tiled(a, b, k)
        baker.mapTexelsT(texture) { t, x, y, z, nx, ny, nz, c ->
            val k = (1f - head.faceMask(x, y, z)) * (1f - head.hairColorAmount(x, y, z)) * (1f - hf.photoAt(t)) * (1f - head.onEar(x, y, z))
            if (k <= 0.02f) return@mapTexelsT c
            val wx = nx * nx; val wy = ny * ny; val wz = nz * nz; val ws = wx + wy + wz
            fun ch(ci: Int): Int {
                // Different offsets per projection so the three repeats don't line up.
                val d = (sample(y + 0.013f, z + 0.031f, ci, 0) * wx + sample(x + 0.027f, z + 0.007f, ci, 1) * wy + sample(x, y, ci, 2) * wz) / ws * k
                return (((c shr (16 - 8 * ci)) and 255) + d).toInt().coerceIn(0, 255)
            }
            (0xFF shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
        }
    }

    /**
     * The skin painted on the rest of the head was measured from the photo before the lighting was taken out, so it
     * could be lighter or pinker than the finished face. Measure both now and shift the head skin onto the face's
     * colour (away from the face edge, where [extendFaceColour] already carries the real colour across).
     */
    private fun matchHeadSkinToFace(baker: TextureBaker, texture: IntArray, hf: HairFill) {
        val head = hf.head
        val h = head.maxY - head.minY
        val face = ArrayList<Int>(); val rest = ArrayList<Int>()
        baker.mapTexelsT(texture) { t, x, y, z, _, _, _, c ->
            val hair = head.hairColorAmount(x, y, z)
            if (hair < 0.05f && !head.isDetailArea(x, y, z) && hf.isSkin(x, y, z) && hf.photoAt(t) < 0.05f) {
                val m = head.faceMask(x, y, z)
                val ax = kotlin.math.abs(x - (head.minX + head.maxX) / 2)
                if (m > 0.9f && ((y > head.minY + 0.38f * h && y < head.minY + 0.55f * h && ax > 0.022f && ax < 0.048f) ||
                        (y > head.minY + 0.78f * h && y < head.maxY - 0.015f && ax < 0.03f))) face.add(c)
                else if (m < 0.02f && extensionWeight(head, x, y, z) < 0.02f && y > head.minY - 0.06f) rest.add(c)
            }
            c
        }
        if (face.size < 50 || rest.size < 50) return
        fun median(list: List<Int>, sh: Int): Float {
            val v = IntArray(list.size) { (list[it] shr sh) and 255 }; v.sort(); return v[v.size / 2].toFloat().coerceAtLeast(1f)
        }
        val gain = FloatArray(3) { i -> val sh = 16 - 8 * i; (median(face, sh) / median(rest, sh)).coerceIn(0.6f, 1.6f) }
        baker.mapTexelsT(texture) { t, x, y, z, _, _, _, c ->
            val k = (1f - head.faceMask(x, y, z)) * (1f - head.hairColorAmount(x, y, z)) * (1f - extensionWeight(head, x, y, z)) * (1f - hf.photoAt(t))
            if (k <= 0.01f) return@mapTexelsT c
            fun ch(i: Int): Int { val sh = 16 - 8 * i; val v = ((c shr sh) and 255).toFloat(); return (v * (1f + (gain[i] - 1f) * k)).toInt().coerceIn(0, 255) }
            (0xFF shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
        }
    }

    /** How much of the face-edge colour [extendFaceColour] carries to a point: fades out away from the face, softly. */
    private fun extensionWeight(head: SelfieHead, x: Float, y: Float, z: Float): Float =
        if (settings.wideBlend) (1f - smoothstep(0.02f, 0.1f, head.distanceOutsideFace(x, y))) * smoothstep(head.headCz - 0.8f * head.rz, head.headCz - 0.2f * head.rz, z)
        else (1f - smoothstep(0.015f, 0.06f, head.distanceOutsideFace(x, y))) * smoothstep(head.headCz - 0.5f * head.rz, head.headCz - 0.1f * head.rz, z)

    private fun extendFaceColour(baker: TextureBaker, texture: IntArray, hf: HairFill) {
        val head = hf.head
        val step = 0.003f; val margin = 0.07f
        val x0 = head.minX - margin; val y0 = head.minY - margin
        val w = ((head.maxX - head.minX + 2 * margin) / step).toInt() + 1
        val h = ((head.maxY - head.minY + 2 * margin) / step).toInt() + 1
        val r = FloatArray(w * h); val g = FloatArray(w * h); val b = FloatArray(w * h); val n = FloatArray(w * h)
        // Photo colour just inside the face edge, by position across the face.
        baker.mapTexels(texture) { x, y, z, _, _, _, c ->
            val m = head.faceMask(x, y, z)
            if (m in 0.55f..0.97f && head.hairColorAmount(x, y, z) < 0.05f && !head.isDetailArea(x, y, z) && hf.isSkin(x, y, z)) {
                val i = ((x - x0) / step).toInt(); val j = ((y - y0) / step).toInt()
                if (i in 0 until w && j in 0 until h) {
                    val k = j * w + i
                    r[k] += ((c shr 16) and 255).toFloat(); g[k] += ((c shr 8) and 255).toFloat(); b[k] += (c and 255).toFloat(); n[k]++
                }
            }
            c
        }
        val near = IntArray(w * h) { if (n[it] > 0f) it else -1 }
        if (near.none { it >= 0 }) return
        fun d2(a: Int, c: Int): Int { val dx = a % w - c % w; val dy = a / w - c / w; return dx * dx + dy * dy }
        fun relax(k: Int, o: Int) { val c = near[o]; if (c >= 0 && (near[k] < 0 || d2(k, c) < d2(k, near[k]))) near[k] = c }
        for (j in 0 until h) for (i in 0 until w) { val k = j * w + i; if (i > 0) relax(k, k - 1); if (j > 0) relax(k, k - w); if (i > 0 && j > 0) relax(k, k - w - 1); if (i < w - 1 && j > 0) relax(k, k - w + 1) }
        for (j in h - 1 downTo 0) for (i in w - 1 downTo 0) { val k = j * w + i; if (i < w - 1) relax(k, k + 1); if (j < h - 1) relax(k, k + w); if (i < w - 1 && j < h - 1) relax(k, k + w + 1); if (i > 0 && j < h - 1) relax(k, k + w - 1) }
        val cr = FloatArray(w * h); val cg = FloatArray(w * h); val cb = FloatArray(w * h)
        for (k in 0 until w * h) { val c = near[k]; if (c >= 0) { cr[k] = r[c] / n[c]; cg[k] = g[c] / n[c]; cb[k] = b[c] / n[c] } }
        // Smooth along the edge so single noisy texels don't streak outward.
        repeat(4) {
            for (arr in arrayOf(cr, cg, cb)) {
                val src = arr.copyOf()
                for (j in 1 until h - 1) for (i in 1 until w - 1) {
                    var sum = 0f
                    for (dj in -1..1) for (di in -1..1) sum += src[(j + dj) * w + i + di]
                    arr[j * w + i] = sum / 9f
                }
            }
        }
        baker.mapTexelsT(texture) { t, x, y, z, _, _, _, c ->
            val out = head.distanceOutsideFace(x, y)
            if (out <= 0f && head.faceMask(x, y, z) > 0.6f) return@mapTexelsT c
            if (y < head.minY - 0.03f) return@mapTexelsT c
            val wgt = extensionWeight(head, x, y, z) * (1f - head.hairColorAmount(x, y, z)) * (1f - head.faceMask(x, y, z)) * (1f - hf.photoAt(t)) *
                (1f - head.onEar(x, y, z))
            if (wgt <= 0.01f) return@mapTexelsT c
            val i = ((x - x0) / step).toInt().coerceIn(0, w - 1); val j = ((y - y0) / step).toInt().coerceIn(0, h - 1)
            val k = j * w + i
            fun ch(v: Int, t: Float) = (v + (t - v) * wgt).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (ch((c shr 16) and 255, cr[k]) shl 16) or (ch((c shr 8) and 255, cg[k]) shl 8) or ch(c and 255, cb[k])
        }
    }

    /**
     * Which faces stay smooth in the low-poly look. With a selfie head the eyes, lips and ears are known;
     * otherwise faces are kept smooth where the surface bends sharply (detail), and flat on big planes.
     */
    private fun smoothMask(mesh: Mesh, vertexNormals: FloatArray, head: SelfieHead?): BooleanArray {
        val fn = MeshOps.faceNormals(mesh)
        val p = mesh.positions
        return BooleanArray(mesh.faceCount) { f ->
            val a = mesh.faces[3 * f]; val b = mesh.faces[3 * f + 1]; val c = mesh.faces[3 * f + 2]
            if (head != null) {
                val x = (p[3 * a] + p[3 * b] + p[3 * c]) / 3; val y = (p[3 * a + 1] + p[3 * b + 1] + p[3 * c + 1]) / 3
                val z = (p[3 * a + 2] + p[3 * b + 2] + p[3 * c + 2]) / 3
                head.isDetailArea(x, y, z)
            } else {
                // Bend: how far the corner normals turn away from the face normal.
                var minDot = 1f
                for (v in intArrayOf(a, b, c)) {
                    val d = vertexNormals[3 * v] * fn[3 * f] + vertexNormals[3 * v + 1] * fn[3 * f + 1] + vertexNormals[3 * v + 2] * fn[3 * f + 2]
                    minDot = kotlin.math.min(minDot, d)
                }
                minDot < 0.9f      // > ~25 degrees
            }
        }
    }

    companion object {
        const val MATERIAL = "head_material"
    }
}

private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
