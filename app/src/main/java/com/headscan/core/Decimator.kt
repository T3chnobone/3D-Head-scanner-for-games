package com.headscan.core

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Quadric error metric edge-collapse simplification (Garland & Heckbert), with
 * boundary-preserving constraint planes, a manifold link check and a normal-flip check.
 */
class Decimator(mesh: Mesh) {
    private val nv = mesh.vertexCount
    private val nf = mesh.faceCount
    // Work in millimetres so quadric coefficients stay in a comfortable numeric range.
    private val pos = DoubleArray(nv * 3) { mesh.positions[it] * 1000.0 }
    private val faces = mesh.faces.copyOf()
    private val faceAlive = BooleanArray(nf) { true }
    private val vertAlive = BooleanArray(nv) { true }
    private val version = IntArray(nv)
    private val vFaces = Array(nv) { IntList(6) }
    private val q = DoubleArray(nv * 10)
    private var aliveFaces = nf
    private val qtmp = DoubleArray(10)
    private val nOld = DoubleArray(4)
    private val nNew = DoubleArray(4)

    private class Candidate(val cost: Double, val a: Int, val b: Int, val va: Int, val vb: Int,
                            val x: Double, val y: Double, val z: Double)

    private val heap = PriorityQueue<Candidate>(maxOf(16, nf * 2)) { l, r -> l.cost.compareTo(r.cost) }

    init {
        for (f in 0 until nf) for (e in 0 until 3) vFaces[faces[3 * f + e]].add(f)

        // Face plane quadrics, area weighted.
        val edgeUse = HashMap<Long, Int>()
        val edgeFace = HashMap<Long, Int>()
        for (f in 0 until nf) {
            val n = faceNormal(f, -1, 0.0, 0.0, 0.0) ?: continue
            val area = n[3]
            val a = faces[3 * f]
            val d = -(n[0] * pos[3 * a] + n[1] * pos[3 * a + 1] + n[2] * pos[3 * a + 2])
            for (e in 0 until 3) addPlane(faces[3 * f + e], n[0], n[1], n[2], d, area)
            for (e in 0 until 3) {
                val key = MeshOps.edgeKey(faces[3 * f + e], faces[3 * f + (e + 1) % 3])
                edgeUse[key] = (edgeUse[key] ?: 0) + 1
                edgeFace[key] = f
            }
        }
        // Boundary constraint planes: perpendicular to the face, through the boundary edge.
        for ((key, count) in edgeUse) {
            if (count != 1) continue
            val f = edgeFace[key]!!
            val a = (key and 0xffffffffL).toInt(); val b = (key shr 32).toInt()
            val n = faceNormal(f, -1, 0.0, 0.0, 0.0) ?: continue
            val ex = pos[3 * b] - pos[3 * a]; val ey = pos[3 * b + 1] - pos[3 * a + 1]; val ez = pos[3 * b + 2] - pos[3 * a + 2]
            var px = ey * n[2] - ez * n[1]; var py = ez * n[0] - ex * n[2]; var pz = ex * n[1] - ey * n[0]
            val l = sqrt(px * px + py * py + pz * pz)
            if (l < 1e-12) continue
            px /= l; py /= l; pz /= l
            val d = -(px * pos[3 * a] + py * pos[3 * a + 1] + pz * pos[3 * a + 2])
            val w = BOUNDARY_WEIGHT * (ex * ex + ey * ey + ez * ez)
            addPlane(a, px, py, pz, d, w)
            addPlane(b, px, py, pz, d, w)
        }
        for (key in edgeUse.keys) {
            pushCandidate((key and 0xffffffffL).toInt(), (key shr 32).toInt())
        }
    }

    /** Simplifies until at most [targetFaces] faces remain (or no valid collapse is left). */
    fun simplify(targetFaces: Int): Mesh {
        val nbA = HashSet<Int>()
        val nbB = HashSet<Int>()
        while (aliveFaces > targetFaces) {
            val c = heap.poll() ?: break
            if (!vertAlive[c.a] || !vertAlive[c.b]) continue
            if (version[c.a] != c.va || version[c.b] != c.vb) continue
            if (!canCollapse(c.a, c.b, c.x, c.y, c.z, nbA, nbB)) continue
            collapse(c.a, c.b, c.x, c.y, c.z)
        }
        return result()
    }

    private fun addPlane(v: Int, a: Double, b: Double, c: Double, d: Double, w: Double) {
        val o = v * 10
        q[o] += w * a * a; q[o + 1] += w * a * b; q[o + 2] += w * a * c; q[o + 3] += w * a * d
        q[o + 4] += w * b * b; q[o + 5] += w * b * c; q[o + 6] += w * b * d
        q[o + 7] += w * c * c; q[o + 8] += w * c * d; q[o + 9] += w * d * d
    }

    private fun error(qq: DoubleArray, x: Double, y: Double, z: Double): Double =
        qq[0] * x * x + 2 * qq[1] * x * y + 2 * qq[2] * x * z + 2 * qq[3] * x +
            qq[4] * y * y + 2 * qq[5] * y * z + 2 * qq[6] * y +
            qq[7] * z * z + 2 * qq[8] * z + qq[9]

    private fun pushCandidate(a: Int, b: Int) {
        for (i in 0 until 10) qtmp[i] = q[a * 10 + i] + q[b * 10 + i]
        val qq = qtmp
        val ax = pos[3 * a]; val ay = pos[3 * a + 1]; val az = pos[3 * a + 2]
        val bx = pos[3 * b]; val by = pos[3 * b + 1]; val bz = pos[3 * b + 2]
        val edgeLen2 = (ax - bx) * (ax - bx) + (ay - by) * (ay - by) + (az - bz) * (az - bz)

        var bestX = (ax + bx) / 2; var bestY = (ay + by) / 2; var bestZ = (az + bz) / 2
        var best = error(qq, bestX, bestY, bestZ)
        val ea = error(qq, ax, ay, az)
        if (ea < best) { best = ea; bestX = ax; bestY = ay; bestZ = az }
        val eb = error(qq, bx, by, bz)
        if (eb < best) { best = eb; bestX = bx; bestY = by; bestZ = bz }

        // Optimal position: solve the 3x3 system A x = -b.
        val a00 = qq[0]; val a01 = qq[1]; val a02 = qq[2]
        val a11 = qq[4]; val a12 = qq[5]; val a22 = qq[7]
        val det = a00 * (a11 * a22 - a12 * a12) - a01 * (a01 * a22 - a12 * a02) + a02 * (a01 * a12 - a11 * a02)
        val scale = (a00 + a11 + a22) / 3
        if (abs(det) > 1e-9 * scale * scale * scale && scale > 0) {
            val inv = 1.0 / det
            val b0 = -qq[3]; val b1 = -qq[6]; val b2 = -qq[8]
            val x = inv * (b0 * (a11 * a22 - a12 * a12) - a01 * (b1 * a22 - a12 * b2) + a02 * (b1 * a12 - a11 * b2))
            val y = inv * (a00 * (b1 * a22 - a12 * b2) - b0 * (a01 * a22 - a12 * a02) + a02 * (a01 * b2 - b1 * a02))
            val z = inv * (a00 * (a11 * b2 - b1 * a12) - a01 * (a01 * b2 - b1 * a02) + b0 * (a01 * a12 - a11 * a02))
            val mx = (ax + bx) / 2; val my = (ay + by) / 2; val mz = (az + bz) / 2
            val dist2 = (x - mx) * (x - mx) + (y - my) * (y - my) + (z - mz) * (z - mz)
            if (dist2 <= edgeLen2 * 1.0) {
                val e = error(qq, x, y, z)
                if (e < best) { best = e; bestX = x; bestY = y; bestZ = z }
            }
        }
        // Tiny length term keeps collapses local on perfectly flat regions.
        val cost = maxOf(0.0, best) + 1e-6 * edgeLen2
        heap.add(Candidate(cost, a, b, version[a], version[b], bestX, bestY, bestZ))
    }

    /** Unit normal + area of face f; if [moved] >= 0 that vertex is evaluated at (x,y,z). Null if degenerate. */
    private fun faceNormal(f: Int, moved: Int, x: Double, y: Double, z: Double, out: DoubleArray = DoubleArray(4)): DoubleArray? {
        val i0 = faces[3 * f]; val i1 = faces[3 * f + 1]; val i2 = faces[3 * f + 2]
        fun px(i: Int) = if (i == moved) x else pos[3 * i]
        fun py(i: Int) = if (i == moved) y else pos[3 * i + 1]
        fun pz(i: Int) = if (i == moved) z else pos[3 * i + 2]
        val ux = px(i1) - px(i0); val uy = py(i1) - py(i0); val uz = pz(i1) - pz(i0)
        val vx = px(i2) - px(i0); val vy = py(i2) - py(i0); val vz = pz(i2) - pz(i0)
        val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        if (l < 1e-14) return null
        out[0] = nx / l; out[1] = ny / l; out[2] = nz / l; out[3] = l / 2
        return out
    }

    private fun collectNeighbors(v: Int, out: HashSet<Int>) {
        out.clear()
        val list = vFaces[v]
        for (i in 0 until list.size) {
            val f = list[i]
            if (!faceAlive[f]) continue
            for (e in 0 until 3) { val u = faces[3 * f + e]; if (u != v) out.add(u) }
        }
    }

    private fun faceHas(f: Int, v: Int) = faces[3 * f] == v || faces[3 * f + 1] == v || faces[3 * f + 2] == v

    private fun canCollapse(a: Int, b: Int, x: Double, y: Double, z: Double, nbA: HashSet<Int>, nbB: HashSet<Int>): Boolean {
        collectNeighbors(a, nbA)
        collectNeighbors(b, nbB)
        if (!nbA.contains(b)) return false
        var common = 0
        for (u in nbA) if (nbB.contains(u)) common++
        var shared = 0
        val la = vFaces[a]
        for (i in 0 until la.size) { val f = la[i]; if (faceAlive[f] && faceHas(f, b)) shared++ }
        if (shared == 0 || common != shared) return false
        // Never let the mesh collapse into a degenerate sliver/tetra.
        if (aliveFaces - shared < 4) return false

        for (v in intArrayOf(a, b)) {
            val list = vFaces[v]
            for (i in 0 until list.size) {
                val f = list[i]
                if (!faceAlive[f]) continue
                if (faceHas(f, a) && faceHas(f, b)) continue
                val before = faceNormal(f, -1, 0.0, 0.0, 0.0, nOld) ?: continue
                val after = faceNormal(f, v, x, y, z, nNew) ?: return false
                val dot = before[0] * after[0] + before[1] * after[1] + before[2] * after[2]
                if (dot < 0.2) return false
                // Avoid creating needle triangles.
                if (!goodShape(f, v, x, y, z, after[3])) return false
            }
        }
        return true
    }

    private fun goodShape(f: Int, moved: Int, x: Double, y: Double, z: Double, area: Double): Boolean {
        var maxEdge2 = 0.0
        for (e in 0 until 3) {
            val i = faces[3 * f + e]; val j = faces[3 * f + (e + 1) % 3]
            val ix = if (i == moved) x else pos[3 * i]; val iy = if (i == moved) y else pos[3 * i + 1]; val iz = if (i == moved) z else pos[3 * i + 2]
            val jx = if (j == moved) x else pos[3 * j]; val jy = if (j == moved) y else pos[3 * j + 1]; val jz = if (j == moved) z else pos[3 * j + 2]
            val d2 = (ix - jx) * (ix - jx) + (iy - jy) * (iy - jy) + (iz - jz) * (iz - jz)
            if (d2 > maxEdge2) maxEdge2 = d2
        }
        // area / maxEdge^2 is ~0.43 for an equilateral triangle.
        return area / maxEdge2 > 0.02
    }

    private fun collapse(a: Int, b: Int, x: Double, y: Double, z: Double) {
        val lb = vFaces[b]
        for (i in 0 until lb.size) {
            val f = lb[i]
            if (!faceAlive[f]) continue
            if (faceHas(f, a)) {
                faceAlive[f] = false
                aliveFaces--
            } else {
                for (e in 0 until 3) if (faces[3 * f + e] == b) faces[3 * f + e] = a
                vFaces[a].add(f)
            }
        }
        lb.clear()
        vertAlive[b] = false
        pos[3 * a] = x; pos[3 * a + 1] = y; pos[3 * a + 2] = z
        for (i in 0 until 10) q[a * 10 + i] += q[b * 10 + i]
        version[a]++

        // Compact a's face list occasionally.
        val la = vFaces[a]
        var w = 0
        for (i in 0 until la.size) { val f = la[i]; if (faceAlive[f]) la.data[w++] = f }
        la.size = w

        val nb = HashSet<Int>()
        collectNeighbors(a, nb)
        for (u in nb) pushCandidate(a, u)
    }

    private fun result(): Mesh {
        val remap = IntArray(nv) { -1 }
        val outPos = FloatList()
        val outFaces = IntList(aliveFaces * 3)
        var n = 0
        for (f in 0 until nf) {
            if (!faceAlive[f]) continue
            for (e in 0 until 3) {
                val v = faces[3 * f + e]
                if (remap[v] < 0) {
                    remap[v] = n++
                    outPos.add((pos[3 * v] / 1000.0).toFloat()); outPos.add((pos[3 * v + 1] / 1000.0).toFloat()); outPos.add((pos[3 * v + 2] / 1000.0).toFloat())
                }
                outFaces.add(remap[v])
            }
        }
        return Mesh(outPos.toArray(), outFaces.toArray())
    }

    companion object {
        private const val BOUNDARY_WEIGHT = 50.0
    }
}
