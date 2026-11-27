package com.headscan.core

import java.io.File
import java.util.Random
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/** Loads the head model asset for the JVM tests. */
object TestModel {
    val model: HeadModel by lazy {
        val f = listOf("src/main/assets/ict_head.bin", "app/src/main/assets/ict_head.bin",
            "/home/user/3d-head-scanner-for-games/app/src/main/assets/ict_head.bin").map { File(it) }.first { it.exists() }
        HeadModel.load(f.inputStream())
    }
    fun install() { if (HeadModel.default == null) HeadModel.default = model }
}

class HeadModelTest {
    /**
     * Ground truth from the model itself: random people's faces (only the front of the face, like the phone's face
     * mesh) are fitted, and the predicted back and sides of the head are compared with the real ones.
     */
    @Test
    fun predictsHeadFromFace() {
        val m = TestModel.model
        val rnd = Random(7)
        var errFit = 0.0; var errAvg = 0.0
        repeat(4) { trial ->
            val c = FloatArray(40) { (rnd.nextGaussian() * 0.8).toFloat() }
            val truth = m.shape(c)
            // Face mesh: model face triangles in the central face area (like ARCore's face mesh coverage).
            val keep = BooleanArray(m.n) { i -> m.isFace(i) && !m.isCap(i) && truth[3 * i + 2] > 0.035f && truth[3 * i + 1] > -0.105f && truth[3 * i + 1] < 0.075f && kotlin.math.abs(truth[3 * i]) < 0.068f }
            val tris = ArrayList<Int>()
            for (t in 0 until m.tris.size / 3) {
                val a = m.tris[3 * t]; val b = m.tris[3 * t + 1]; val d = m.tris[3 * t + 2]
                if (keep[a] && keep[b] && keep[d]) tris.addAll(listOf(a, b, d))
            }
            // Compact to the face vertices only, like the phone's face mesh.
            val remap = IntArray(m.n) { -1 }; val fv = ArrayList<Float>()
            for (i in 0 until m.n) if (keep[i]) { remap[i] = fv.size / 3; fv.add(truth[3 * i]); fv.add(truth[3 * i + 1]); fv.add(truth[3 * i + 2]) }
            val ft = tris.map { remap[it] }.toIntArray()
            val head = SelfieHead(fv.toFloatArray(), ft, HairStyle.NONE, model = m)
            val fit = head.fitted!!
            val avg = SelfieHead(fv.toFloatArray(), ft, HairStyle.NONE, model = null)
            var e1 = 0.0; var e2 = 0.0; var n = 0
            for (i in 0 until m.n) {
                if (m.isFace(i) || m.isCap(i) || truth[3 * i + 1] < -0.08f) continue
                val dx = fit.verts[3 * i] - truth[3 * i]; val dy = fit.verts[3 * i + 1] - truth[3 * i + 1]; val dz = fit.verts[3 * i + 2] - truth[3 * i + 2]
                e1 += sqrt((dx * dx + dy * dy + dz * dz).toDouble())
                // The simple head: distance from the true skull point to its surface.
                e2 += kotlin.math.abs(avg.genericSdf(truth[3 * i], truth[3 * i + 1], truth[3 * i + 2]).toDouble())
                n++
            }
            e1 /= n; e2 /= n
            println("trial $trial: fit rms ${fit.rmsMm} mm on ${fit.used} pts, scale ${fit.scale}; skull error model ${e1 * 1000} mm, simple head ${e2 * 1000} mm")
            errFit += e1; errAvg += e2
        }
        assertTrue(errFit / 4 < 0.006, "predicted skull within 6 mm on average (${errFit / 4 * 1000} mm)")
        assertTrue(errFit < errAvg, "better than the simple head")
    }
}
