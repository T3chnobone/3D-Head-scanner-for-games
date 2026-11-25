package com.headscan.core

/**
 * Naive Surface Nets iso-surface extraction (zero level set of a [TsdfVolume]).
 * Produces one vertex per surface-crossing cell and a quad (two triangles) per sign-changing grid edge.
 * Triangles are wound counter-clockwise when seen from outside (the positive / free-space side).
 */
object SurfaceNets {
    // Cube corner offsets, corner c = (c&1, (c>>1)&1, (c>>2)&1)
    private val EDGES = arrayOf(
        intArrayOf(0, 1), intArrayOf(2, 3), intArrayOf(4, 5), intArrayOf(6, 7), // x edges
        intArrayOf(0, 2), intArrayOf(1, 3), intArrayOf(4, 6), intArrayOf(5, 7), // y edges
        intArrayOf(0, 4), intArrayOf(1, 5), intArrayOf(2, 6), intArrayOf(3, 7), // z edges
    )

    fun extract(vol: TsdfVolume, minWeight: Float): Mesh {
        val nx = vol.nx; val ny = vol.ny; val nz = vol.nz
        val cx = nx - 1; val cy = ny - 1; val cz = nz - 1
        val cellVertex = IntArray(cx * cy * cz) { -1 }
        val pos = FloatList(1 shl 16)
        val vals = FloatArray(8)
        var vcount = 0

        for (k in 0 until cz) for (j in 0 until cy) for (i in 0 until cx) {
            var valid = true
            var neg = 0
            for (c in 0 until 8) {
                val id = vol.index(i + (c and 1), j + ((c shr 1) and 1), k + ((c shr 2) and 1))
                if (vol.weight[id] < minWeight) { valid = false; break }
                vals[c] = vol.tsdf[id]
                if (vals[c] < 0f) neg++
            }
            if (!valid || neg == 0 || neg == 8) continue
            var sx = 0f; var sy = 0f; var sz = 0f; var n = 0
            for (e in EDGES) {
                val a = e[0]; val b = e[1]
                val va = vals[a]; val vb = vals[b]
                if ((va < 0f) == (vb < 0f)) continue
                val t = va / (va - vb)
                val ax = (a and 1).toFloat(); val ay = ((a shr 1) and 1).toFloat(); val az = ((a shr 2) and 1).toFloat()
                val bx = (b and 1).toFloat(); val by = ((b shr 1) and 1).toFloat(); val bz = ((b shr 2) and 1).toFloat()
                sx += ax + t * (bx - ax); sy += ay + t * (by - ay); sz += az + t * (bz - az)
                n++
            }
            pos.add(vol.pointX(i + sx / n)); pos.add(vol.pointY(j + sy / n)); pos.add(vol.pointZ(k + sz / n))
            cellVertex[(k * cy + j) * cx + i] = vcount++
        }

        fun cell(i: Int, j: Int, k: Int): Int {
            if (i < 0 || j < 0 || k < 0 || i >= cx || j >= cy || k >= cz) return -1
            return cellVertex[(k * cy + j) * cx + i]
        }

        val faces = IntList(1 shl 17)
        val p = IntArray(3)
        val q = IntArray(3)
        for (k in 0 until nz) for (j in 0 until ny) for (i in 0 until nx) {
            val id0 = vol.index(i, j, k)
            if (vol.weight[id0] < minWeight) continue
            val inside0 = vol.tsdf[id0] < 0f
            for (axis in 0 until 3) {
                p[0] = i; p[1] = j; p[2] = k
                q[0] = i; q[1] = j; q[2] = k
                q[axis]++
                if (q[0] >= nx || q[1] >= ny || q[2] >= nz) continue
                val id1 = vol.index(q[0], q[1], q[2])
                if (vol.weight[id1] < minWeight) continue
                val inside1 = vol.tsdf[id1] < 0f
                if (inside0 == inside1) continue
                val b = (axis + 1) % 3
                val c = (axis + 2) % 3
                // The four cells around this edge: p-eb-ec, p-ec, p, p-eb (CCW around +axis)
                val off = IntArray(3)
                fun cellAt(db: Int, dc: Int): Int {
                    off[0] = p[0]; off[1] = p[1]; off[2] = p[2]
                    off[b] += db; off[c] += dc
                    return cell(off[0], off[1], off[2])
                }
                val va = cellAt(-1, -1)
                val vb = cellAt(0, -1)
                val vc = cellAt(0, 0)
                val vd = cellAt(-1, 0)
                if (va < 0 || vb < 0 || vc < 0 || vd < 0) continue
                if (inside0) {
                    // surface normal points along +axis
                    faces.add(va); faces.add(vb); faces.add(vc)
                    faces.add(va); faces.add(vc); faces.add(vd)
                } else {
                    faces.add(va); faces.add(vc); faces.add(vb)
                    faces.add(va); faces.add(vd); faces.add(vc)
                }
            }
        }
        return Mesh(pos.toArray(), faces.toArray())
    }
}
