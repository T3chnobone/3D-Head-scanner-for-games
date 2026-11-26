package com.headscan.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Game-style UV layout: the head is split into a few large islands (front/face, sides, back, top,
 * under the chin...) by which way the surface faces, each island is flattened by projecting it onto its
 * own plane, and the islands are packed into the texture with the face given extra resolution.
 * Compared with one cell per triangle this gives continuous texture across the face, far fewer seams,
 * and texels spent where the surface area is.
 */
object ChartAtlas {
    private const val PAD = 4f          // px between islands

    private class Chart(val faces: IntArray, val u: FloatArray, val v: FloatArray, val importance: Float) {
        var minU = 0f; var minV = 0f; var w = 0f; var h = 0f
        var ox = 0f; var oy = 0f; var scale = 0f
    }

    /**
     * @param frontAngle yaw that turns the mesh into the head frame (+Z = face), as in [MeshOps.transformToHeadFrame].
     * @param importance per-face texel-density weight (linear); e.g. 1.8 on the face, 1 elsewhere.
     */
    fun build(mesh: Mesh, size: Int, frontAngle: Float = 0f, importance: (Int) -> Float = { 1f }): UvAtlas {
        val fc = mesh.faceCount
        val p = mesh.positions
        val c = cos(frontAngle); val s = sin(frontAngle)
        // Face normals in the head frame + areas.
        val fn = FloatArray(fc * 3)
        val area = FloatArray(fc)
        for (f in 0 until fc) {
            val a = mesh.faces[3 * f]; val b = mesh.faces[3 * f + 1]; val cc = mesh.faces[3 * f + 2]
            val ux = p[3 * b] - p[3 * a]; val uy = p[3 * b + 1] - p[3 * a + 1]; val uz = p[3 * b + 2] - p[3 * a + 2]
            val vx = p[3 * cc] - p[3 * a]; val vy = p[3 * cc + 1] - p[3 * a + 1]; val vz = p[3 * cc + 2] - p[3 * a + 2]
            var nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val l = len3(nx, ny, nz)
            area[f] = 0.5f * l
            val hx = nx * c - nz * s; val hz = nx * s + nz * c
            nx = hx; nz = hz
            if (l > 1e-12f) { fn[3 * f] = nx / l; fn[3 * f + 1] = ny / l; fn[3 * f + 2] = nz / l }
        }
        // Projection directions (head frame): front, four diagonals, sides, back, top, bottom.
        val r = 0.7071f
        val dirs = arrayOf(
            floatArrayOf(0f, 0f, 1f), floatArrayOf(r, 0f, r), floatArrayOf(-r, 0f, r),
            floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f),
            floatArrayOf(r, 0f, -r), floatArrayOf(-r, 0f, -r), floatArrayOf(0f, 0f, -1f),
            floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, -1f, 0f),
        )
        val label = IntArray(fc)
        for (f in 0 until fc) {
            var best = 0; var bd = -2f
            for ((k, d) in dirs.withIndex()) {
                // A slight preference for the front so the whole face lands in one island.
                val dot = fn[3 * f] * d[0] + fn[3 * f + 1] * d[1] + fn[3 * f + 2] * d[2] + (if (k == 0) 0.12f else 0f)
                if (dot > bd) { bd = dot; best = k }
            }
            label[f] = best
        }
        // Connected components of same-label faces (sharing an edge).
        val edgeFaces = HashMap<Long, IntList>()
        for (f in 0 until fc) for (k in 0 until 3) {
            val a = mesh.faces[3 * f + k]; val b = mesh.faces[3 * f + (k + 1) % 3]
            edgeFaces.getOrPut(MeshOps.edgeKey(a, b)) { IntList(2) }.add(f)
        }
        val comp = IntArray(fc) { -1 }
        val charts = ArrayList<Chart>()
        val loose = IntList()
        // Vertex positions in the head frame (for projection).
        val hp = FloatArray(p.size)
        for (v in 0 until mesh.vertexCount) {
            val x = p[3 * v]; val z = p[3 * v + 2]
            hp[3 * v] = x * c - z * s; hp[3 * v + 1] = p[3 * v + 1]; hp[3 * v + 2] = x * s + z * c
        }
        for (seed in 0 until fc) {
            if (comp[seed] >= 0) continue
            val members = IntList()
            val stack = IntList(); stack.add(seed); comp[seed] = charts.size + loose.size + 1_000_000
            while (stack.size > 0) {
                val f = stack.data[--stack.size]
                members.add(f)
                for (k in 0 until 3) {
                    val a = mesh.faces[3 * f + k]; val b = mesh.faces[3 * f + (k + 1) % 3]
                    val list = edgeFaces[MeshOps.edgeKey(a, b)] ?: continue
                    for (i in 0 until list.size) {
                        val g = list.data[i]
                        if (comp[g] < 0 && label[g] == label[f]) { comp[g] = comp[seed]; stack.add(g) }
                    }
                }
            }
            val d = dirs[label[seed]]
            makeChart(members.toArray(), d, mesh, hp, area, importance, charts, loose)
        }
        // Faces that would overlap inside their island get their own little island.
        for (i in 0 until loose.size) {
            val f = loose.data[i]
            makeChart(intArrayOf(f), floatArrayOf(fn[3 * f], fn[3 * f + 1], fn[3 * f + 2]), mesh, hp, area, importance, charts, null)
        }
        return pack(charts, fc, size)
    }

    /** Projects [faces] onto the plane facing [d]; faces that flip or overlap go to [loose] (if given). */
    private fun makeChart(faces: IntArray, d: FloatArray, mesh: Mesh, hp: FloatArray, area: FloatArray,
                          importance: (Int) -> Float, out: ArrayList<Chart>, loose: IntList?) {
        // Plane basis: v = "up" projected, u = up x d.
        var upx = 0f; var upy = 1f; var upz = 0f
        if (abs(d[1]) > 0.9f) { upx = 0f; upy = 0f; upz = if (d[1] > 0) -1f else 1f }
        var ux = upy * d[2] - upz * d[1]; var uy = upz * d[0] - upx * d[2]; var uz = upx * d[1] - upy * d[0]
        val ul = len3(ux, uy, uz).coerceAtLeast(1e-9f); ux /= ul; uy /= ul; uz /= ul
        val vx = d[1] * uz - d[2] * uy; val vy = d[2] * ux - d[0] * uz; val vz = d[0] * uy - d[1] * ux
        fun pu(v: Int) = hp[3 * v] * ux + hp[3 * v + 1] * uy + hp[3 * v + 2] * uz
        fun pv(v: Int) = hp[3 * v] * vx + hp[3 * v + 1] * vy + hp[3 * v + 2] * vz

        val kept = IntList(); val ku = FloatList(); val kv = FloatList()
        // Coarse occupancy grid (1.5 mm) to catch folds (ears, nostrils) that would overlap.
        val cell = 0.0015f
        val occ = HashMap<Long, Int>()
        fun key(i: Int, j: Int) = (i.toLong() shl 32) xor (j.toLong() and 0xffffffffL)
        for (f in faces) {
            val a = mesh.faces[3 * f]; val b = mesh.faces[3 * f + 1]; val cc = mesh.faces[3 * f + 2]
            val au = pu(a); val av = pv(a); val bu = pu(b); val bv = pv(b); val cu = pu(cc); val cv = pv(cc)
            val pArea = 0.5f * ((bu - au) * (cv - av) - (cu - au) * (bv - av))
            val ok = if (loose == null) true else {
                // Must face the projection plane with a sensible size (no flips, no slivers).
                if (pArea < 0.25f * area[f]) false else {
                    var clash = false
                    val cells = ArrayList<Long>()
                    val i0 = floor(min(au, min(bu, cu)) / cell).toInt(); val i1 = floor(max(au, max(bu, cu)) / cell).toInt()
                    val j0 = floor(min(av, min(bv, cv)) / cell).toInt(); val j1 = floor(max(av, max(bv, cv)) / cell).toInt()
                    for (j in j0..j1) for (i in i0..i1) {
                        val x = (i + 0.5f) * cell; val y = (j + 0.5f) * cell
                        val w0 = ((bu - x) * (cv - y) - (cu - x) * (bv - y)) / (2 * pArea)
                        val w1 = ((cu - x) * (av - y) - (au - x) * (cv - y)) / (2 * pArea)
                        val w2 = 1f - w0 - w1
                        if (w0 < 0.02f || w1 < 0.02f || w2 < 0.02f) continue     // interior cells only
                        val kk = key(i, j)
                        if (occ.containsKey(kk)) { clash = true; break }
                        cells.add(kk)
                    }
                    if (!clash) for (kk in cells) occ[kk] = f
                    !clash
                }
            }
            if (ok) {
                kept.add(f)
                ku.add(au); kv.add(av); ku.add(bu); kv.add(bv); ku.add(cu); kv.add(cv)
            } else loose?.add(f)
        }
        if (kept.size == 0) return
        var imp = 0f; var wsum = 0f
        for (i in 0 until kept.size) { val f = kept.data[i]; imp += importance(f) * area[f]; wsum += area[f] }
        val ch = Chart(kept.toArray(), ku.toArray(), kv.toArray(), if (wsum > 0f) imp / wsum else 1f)
        ch.minU = ch.u.minOrNull()!!; ch.minV = ch.v.minOrNull()!!
        ch.w = ch.u.maxOrNull()!! - ch.minU; ch.h = ch.v.maxOrNull()!! - ch.minV
        out.add(ch)
    }

    /** Shelf-packs the islands; the largest texel density that fits is found by bisection. */
    private fun pack(charts: List<Chart>, faceCount: Int, size: Int): UvAtlas {
        val order = charts.sortedByDescending { it.h * it.importance }
        fun tryPack(k: Float, commit: Boolean): Boolean {
            var x = PAD; var y = PAD; var shelf = 0f
            for (ch in order) {
                val sc = k * ch.importance
                val w = ch.w * sc; val hh = ch.h * sc
                if (w + 2 * PAD > size || hh + 2 * PAD > size) return false
                if (x + w + PAD > size) { x = PAD; y += shelf + PAD; shelf = 0f }
                if (y + hh + PAD > size) return false
                if (commit) { ch.ox = x; ch.oy = y; ch.scale = sc }
                x += w + PAD
                shelf = max(shelf, hh)
            }
            return true
        }
        var lo = 1f; var hi = 200000f
        repeat(40) { val mid = sqrt(lo * hi); if (tryPack(mid, false)) lo = mid else hi = mid }
        tryPack(lo, true)
        val uv = FloatArray(faceCount * 6)
        for (ch in charts) for ((i, f) in ch.faces.withIndex()) for (k in 0 until 3) {
            // v up in the plane -> image y down
            uv[f * 6 + 2 * k] = ch.ox + (ch.u[3 * i + k] - ch.minU) * ch.scale
            uv[f * 6 + 2 * k + 1] = ch.oy + (ch.h - (ch.v[3 * i + k] - ch.minV)) * ch.scale
        }
        return UvAtlas(faceCount, size, uv)
    }

}
