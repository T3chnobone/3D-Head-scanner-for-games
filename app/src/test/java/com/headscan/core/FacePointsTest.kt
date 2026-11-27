package com.headscan.core

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Beard and lips placed from the named points of a real 468-point face mesh (the MediaPipe canonical face, the same
 * layout ARCore and ML Kit give), not from typical proportions.
 */
class FacePointsTest {
    init { TestModel.install() }

    private fun canonical(): Pair<FloatArray, IntArray> {
        val text = javaClass.classLoader.getResourceAsStream("canonical_face_model.obj")!!.bufferedReader().readText()
        val v = ArrayList<Float>(); val f = ArrayList<Int>()
        for (line in text.lineSequence()) {
            val p = line.trim().split(Regex("\\s+"))
            if (p[0] == "v") for (k in 1..3) v.add(p[k].toFloat() * 0.01f)        // cm -> m
            if (p[0] == "f") for (k in 1..3) f.add(p[k].substringBefore('/').toInt() - 1)
        }
        return v.toFloatArray() to f.toIntArray()
    }

    @Test
    fun beardFollowsFacePoints() {
        val (v, t) = canonical()
        assertTrue(v.size / 3 == 468)
        val head = SelfieHead(v, t, HairStyle.NONE)
        fun zoneAt(i: Int, dy: Float = 0f) = head.beardZone(v[3 * i], v[3 * i + 1] + dy, v[3 * i + 2], 0.025f)
        fun mid(a: Int, b: Int) = head.beardZone((v[3 * a] + v[3 * b]) / 2, (v[3 * a + 1] + v[3 * b + 1]) / 2, (v[3 * a + 2] + v[3 * b + 2]) / 2, 0.025f)
        val lips = mid(13, 14); val upperLip = mid(2, 0); val chin = zoneAt(152); val jawL = zoneAt(172); val jawR = zoneAt(397)
        val belowLip = mid(17, 152); val cheekBone = zoneAt(50); val forehead = zoneAt(10); val nose = zoneAt(1); val underEye = zoneAt(118)
        println("lips $lips upperLip $upperLip chin $chin jaw $jawL/$jawR belowLip $belowLip cheekBone $cheekBone forehead $forehead nose $nose underEye $underEye")
        assertTrue(lips < 0.05f, "no beard on the lips: $lips")
        assertTrue(upperLip > 0.5f, "moustache between nose and lip: $upperLip")
        assertTrue(chin > 0.5f && belowLip > 0.5f, "beard on the chin: $chin $belowLip")
        assertTrue(jawL > 0.5f && jawR > 0.5f, "beard along the jaw: $jawL $jawR")
        assertTrue(cheekBone < 0.05f && forehead < 0.01f && nose < 0.05f && underEye < 0.05f, "none on the upper face")
        // Ears: the model's own ear points count as ear, the head skin right round them doesn't.
        val fit = head.fitted!!; val m = HeadModel.default!!
        var earSum = 0f; var earN = 0; var nearSum = 0f; var nearN = 0
        val ears = (0 until m.n).filter { m.isPinna(it) }
        for (i in 0 until m.n) {
            if (m.isCap(i)) continue
            val x = fit.verts[3 * i]; val y = fit.verts[3 * i + 1]; val z = fit.verts[3 * i + 2]
            val e = head.onEar(x, y, z)
            if (m.isPinna(i)) { earSum += e; earN++ }
            else {
                val d = ears.minOf { j -> val dx = fit.verts[3 * j] - x; val dy = fit.verts[3 * j + 1] - y; val dz = fit.verts[3 * j + 2] - z; dx * dx + dy * dy + dz * dz }
                if (d in 0.008f * 0.008f..0.03f * 0.03f) { nearSum += e; nearN++ }
            }
        }
        println("ear points ${earSum / earN}, head skin 8-30 mm from the ear ${nearSum / nearN} ($nearN)")
        assertTrue(earSum / earN > 0.8f, "ear points count as ear")
        assertTrue(nearSum / nearN < 0.05f, "head skin round the ear doesn't")
        // The lips are a detail area (kept out of skin evening) where the face points put them.
        assertTrue(head.isDetailArea(v[3 * 13], v[3 * 13 + 1], v[3 * 13 + 2]))
    }
}
