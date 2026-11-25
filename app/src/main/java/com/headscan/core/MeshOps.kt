package com.headscan.core

import kotlin.math.sqrt

object MeshOps {

    /** Drops faces whose predicate is false, then removes unreferenced vertices. */
    fun filterFaces(mesh: Mesh, keep: (Int) -> Boolean): Mesh {
        val out = IntList(mesh.faces.size)
        for (f in 0 until mesh.faceCount) {
            if (keep(f)) {
                out.add(mesh.faces[3 * f]); out.add(mesh.faces[3 * f + 1]); out.add(mesh.faces[3 * f + 2])
            }
        }
        return compact(Mesh(mesh.positions, out.toArray()))
    }

    /** Removes vertices not referenced by any face and degenerate faces. */
    fun compact(mesh: Mesh): Mesh {
        val remap = IntArray(mesh.vertexCount) { -1 }
        val faces = IntList(mesh.faces.size)
        val pos = FloatList(mesh.positions.size)
        var n = 0
        for (f in 0 until mesh.faceCount) {
            val a = mesh.faces[3 * f]; val b = mesh.faces[3 * f + 1]; val c = mesh.faces[3 * f + 2]
            if (a == b || b == c || a == c) continue
            for (v in intArrayOf(a, b, c)) {
                if (remap[v] < 0) {
                    remap[v] = n++
                    pos.add(mesh.positions[3 * v]); pos.add(mesh.positions[3 * v + 1]); pos.add(mesh.positions[3 * v + 2])
                }
                faces.add(remap[v])
            }
        }
        return Mesh(pos.toArray(), faces.toArray())
    }

    /** Keeps only the connected component with the most faces (removes floating noise blobs). */
    fun largestComponent(mesh: Mesh): Mesh {
        val parent = IntArray(mesh.vertexCount) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var y = x
            while (parent[y] != r) { val nx = parent[y]; parent[y] = r; y = nx }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
        for (f in 0 until mesh.faceCount) {
            union(mesh.faces[3 * f], mesh.faces[3 * f + 1])
            union(mesh.faces[3 * f], mesh.faces[3 * f + 2])
        }
        val count = HashMap<Int, Int>()
        for (f in 0 until mesh.faceCount) {
            val r = find(mesh.faces[3 * f])
            count[r] = (count[r] ?: 0) + 1
        }
        val best = count.maxByOrNull { it.value }?.key ?: return mesh
        return filterFaces(mesh) { find(mesh.faces[3 * it]) == best }
    }

    /** Builds vertex adjacency (unique neighbour lists). */
    fun neighbors(mesh: Mesh): Array<IntArray> {
        val sets = Array(mesh.vertexCount) { HashSet<Int>(8) }
        for (f in 0 until mesh.faceCount) {
            val a = mesh.faces[3 * f]; val b = mesh.faces[3 * f + 1]; val c = mesh.faces[3 * f + 2]
            sets[a].add(b); sets[a].add(c); sets[b].add(a); sets[b].add(c); sets[c].add(a); sets[c].add(b)
        }
        return Array(mesh.vertexCount) { sets[it].toIntArray() }
    }

    /** Vertices on an open boundary (edges used by exactly one face). */
    fun boundaryVertices(mesh: Mesh): BooleanArray {
        val edgeCount = HashMap<Long, Int>()
        for (f in 0 until mesh.faceCount) for (e in 0 until 3) {
            val a = mesh.faces[3 * f + e]; val b = mesh.faces[3 * f + (e + 1) % 3]
            val key = edgeKey(a, b)
            edgeCount[key] = (edgeCount[key] ?: 0) + 1
        }
        val out = BooleanArray(mesh.vertexCount)
        for ((k, c) in edgeCount) if (c == 1) {
            out[(k shr 32).toInt()] = true; out[(k and 0xffffffffL).toInt()] = true
        }
        return out
    }

    fun edgeKey(a: Int, b: Int): Long {
        val lo = minOf(a, b).toLong(); val hi = maxOf(a, b).toLong()
        return (hi shl 32) or lo
    }

    /**
     * Taubin (lambda/mu) smoothing: removes depth-sensor noise without shrinking the surface.
     * Boundary vertices are kept fixed.
     */
    fun taubinSmooth(mesh: Mesh, iterations: Int, lambda: Float = 0.5f, mu: Float = -0.53f) {
        val nb = neighbors(mesh)
        val boundary = boundaryVertices(mesh)
        val p = mesh.positions
        val tmp = FloatArray(p.size)
        repeat(iterations) {
            for (factor in floatArrayOf(lambda, mu)) {
                for (v in 0 until mesh.vertexCount) {
                    val n = nb[v]
                    if (boundary[v] || n.isEmpty()) {
                        tmp[3 * v] = p[3 * v]; tmp[3 * v + 1] = p[3 * v + 1]; tmp[3 * v + 2] = p[3 * v + 2]
                        continue
                    }
                    var sx = 0f; var sy = 0f; var sz = 0f
                    for (u in n) { sx += p[3 * u]; sy += p[3 * u + 1]; sz += p[3 * u + 2] }
                    val inv = 1f / n.size
                    tmp[3 * v] = p[3 * v] + factor * (sx * inv - p[3 * v])
                    tmp[3 * v + 1] = p[3 * v + 1] + factor * (sy * inv - p[3 * v + 1])
                    tmp[3 * v + 2] = p[3 * v + 2] + factor * (sz * inv - p[3 * v + 2])
                }
                System.arraycopy(tmp, 0, p, 0, p.size)
            }
        }
    }

    /** Unit normal per face (for flat shading). */
    fun faceNormals(mesh: Mesh): FloatArray {
        val p = mesh.positions
        val n = FloatArray(mesh.faceCount * 3)
        for (f in 0 until mesh.faceCount) {
            val a = mesh.faces[3 * f]; val b = mesh.faces[3 * f + 1]; val c = mesh.faces[3 * f + 2]
            val ux = p[3 * b] - p[3 * a]; val uy = p[3 * b + 1] - p[3 * a + 1]; val uz = p[3 * b + 2] - p[3 * a + 2]
            val vx = p[3 * c] - p[3 * a]; val vy = p[3 * c + 1] - p[3 * a + 1]; val vz = p[3 * c + 2] - p[3 * a + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            val l = len3(nx, ny, nz)
            if (l > 1e-12f) { n[3 * f] = nx / l; n[3 * f + 1] = ny / l; n[3 * f + 2] = nz / l } else n[3 * f + 1] = 1f
        }
        return n
    }

    /** Area-weighted vertex normals. */
    fun vertexNormals(mesh: Mesh): FloatArray {
        val p = mesh.positions
        val n = FloatArray(p.size)
        for (f in 0 until mesh.faceCount) {
            val a = mesh.faces[3 * f]; val b = mesh.faces[3 * f + 1]; val c = mesh.faces[3 * f + 2]
            val ux = p[3 * b] - p[3 * a]; val uy = p[3 * b + 1] - p[3 * a + 1]; val uz = p[3 * b + 2] - p[3 * a + 2]
            val vx = p[3 * c] - p[3 * a]; val vy = p[3 * c + 1] - p[3 * a + 1]; val vz = p[3 * c + 2] - p[3 * a + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            for (v in intArrayOf(a, b, c)) { n[3 * v] += nx; n[3 * v + 1] += ny; n[3 * v + 2] += nz }
        }
        for (v in 0 until mesh.vertexCount) {
            val l = sqrt(n[3 * v] * n[3 * v] + n[3 * v + 1] * n[3 * v + 1] + n[3 * v + 2] * n[3 * v + 2])
            if (l > 1e-12f) { n[3 * v] /= l; n[3 * v + 1] /= l; n[3 * v + 2] /= l } else n[3 * v + 1] = 1f
        }
        return n
    }

    fun faceCentroidY(mesh: Mesh, f: Int): Float =
        (mesh.positions[3 * mesh.faces[3 * f] + 1] + mesh.positions[3 * mesh.faces[3 * f + 1] + 1] + mesh.positions[3 * mesh.faces[3 * f + 2] + 1]) / 3f

    /** Y component of the unit face normal. */
    fun faceNormalY(mesh: Mesh, f: Int): Float {
        val p = mesh.positions
        val a = mesh.faces[3 * f]; val b = mesh.faces[3 * f + 1]; val c = mesh.faces[3 * f + 2]
        val ux = p[3 * b] - p[3 * a]; val uy = p[3 * b + 1] - p[3 * a + 1]; val uz = p[3 * b + 2] - p[3 * a + 2]
        val vx = p[3 * c] - p[3 * a]; val vy = p[3 * c + 1] - p[3 * a + 1]; val vz = p[3 * c + 2] - p[3 * a + 2]
        val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
        val l = len3(nx, ny, nz)
        return if (l > 0f) ny / l else 0f
    }

    /** Applies p' = R_y(-angle) * (p - origin), turning [angle] (radians about +Y) to face +Z. */
    fun transformToHeadFrame(mesh: Mesh, ox: Float, oy: Float, oz: Float, angle: Float) {
        val c = kotlin.math.cos(angle); val s = kotlin.math.sin(angle)
        val p = mesh.positions
        for (v in 0 until mesh.vertexCount) {
            val x = p[3 * v] - ox; val y = p[3 * v + 1] - oy; val z = p[3 * v + 2] - oz
            p[3 * v] = x * c - z * s
            p[3 * v + 1] = y
            p[3 * v + 2] = x * s + z * c
        }
    }
}
