package com.survey.totalstationbt.utils

import com.survey.totalstationbt.model.LineShape
import com.survey.totalstationbt.model.LineVertex
import com.survey.totalstationbt.model.RotatePivot
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 線段編輯幾何運算（純函式，方便單獨驗算）。
 *
 * 兩個核心行為：
 *  1. 旋轉：以起點 / 中點 / 終點為軸心轉動單一線段，鄰接線段因共用接點而自動跟隨。
 *  2. 平行拉伸：線段沿法線方向平移，鄰接（垂直方向）線段保持原方向，
 *     以直線交點重算接點 → 鄰線自動變長或縮短，接點依舊相連。
 */
object LineEditMath {

    private const val EPS = 1e-9

    // ── 基本量測 ─────────────────────────────────
    fun segmentLength(shape: LineShape, index: Int): Double {
        if (!shape.isValidSegment(index)) return 0.0
        val a = shape.segStart(index); val b = shape.segEnd(index)
        return hypot(b.e - a.e, b.n - a.n)
    }

    /** 方位角（度）：由 N 軸順時針起算 0–360 */
    fun segmentAzimuthDeg(shape: LineShape, index: Int): Double {
        if (!shape.isValidSegment(index)) return 0.0
        val a = shape.segStart(index); val b = shape.segEnd(index)
        var deg = Math.toDegrees(atan2(b.e - a.e, b.n - a.n))
        if (deg < 0) deg += 360.0
        return deg
    }

    /** 線段左法線單位向量（面向線段前進方向的左側） */
    fun segmentNormal(shape: LineShape, index: Int): Pair<Double, Double>? {
        if (!shape.isValidSegment(index)) return null
        val a = shape.segStart(index); val b = shape.segEnd(index)
        val len = hypot(b.e - a.e, b.n - a.n)
        if (len < EPS) return null
        return (-(b.n - a.n) / len) to ((b.e - a.e) / len)
    }

    fun perimeter(shape: LineShape): Double =
        (0 until shape.segmentCount).sumOf { segmentLength(shape, it) }

    /** 封閉圖形面積（Shoelace，取絕對值） */
    fun area(shape: LineShape): Double {
        if (!shape.closed || shape.vertices.size < 3) return 0.0
        var sum = 0.0
        val v = shape.vertices
        for (i in v.indices) {
            val p = v[i]; val q = v[(i + 1) % v.size]
            sum += p.e * q.n - q.e * p.n
        }
        return abs(sum) / 2.0
    }

    fun centroid(shape: LineShape): LineVertex {
        if (shape.vertices.isEmpty()) return LineVertex(0.0, 0.0)
        return LineVertex(
            shape.vertices.sumOf { it.e } / shape.vertices.size,
            shape.vertices.sumOf { it.n } / shape.vertices.size
        )
    }

    // ── 直線交點 ─────────────────────────────────
    /**
     * 兩條無限長直線交點：直線 1 過 (p1e, p1n) 方向 (d1e, d1n)，直線 2 過 (p2e, p2n) 方向 (d2e, d2n)。
     * 平行或方向為零向量時回傳 null。
     */
    fun lineIntersection(
        p1e: Double, p1n: Double, d1e: Double, d1n: Double,
        p2e: Double, p2n: Double, d2e: Double, d2n: Double
    ): LineVertex? {
        val cross = d1e * d2n - d1n * d2e
        if (abs(cross) < 1e-12) return null
        if (hypot(d1e, d1n) < EPS || hypot(d2e, d2n) < EPS) return null
        val t = ((p2e - p1e) * d2n - (p2n - p1n) * d2e) / cross
        return LineVertex(p1e + t * d1e, p1n + t * d1n)
    }

    // ── 核心：以新的「線段所在直線」重算接點 ──────
    /**
     * 將線段 index 換成通過 (ae, an)、方向 (ue, un) 的新直線。
     *
     * [extendNeighbors] = true 時，鄰接線段保持原方向不動，接點改由直線交點決定，
     * 因此鄰線（常見情形為垂直方向的線）會自動變長或縮短，而接點仍然相連；
     * 若鄰線與新直線平行（無交點）或該端沒有鄰線，則退回使用 [fallbackA] / [fallbackB]。
     */
    private fun replaceSegmentLine(
        shape: LineShape, index: Int,
        ae: Double, an: Double, ue: Double, un: Double,
        fallbackA: LineVertex, fallbackB: LineVertex,
        extendNeighbors: Boolean
    ): LineShape {
        if (!shape.isValidSegment(index)) return shape
        val v = shape.vertices.toMutableList()
        val count = v.size
        val iA = index
        val iB = (index + 1) % count

        var newA = fallbackA
        var newB = fallbackB

        if (extendNeighbors && shape.hasPrevSegment(index)) {
            val prev = v[(iA - 1 + count) % count]
            val cur = v[iA]
            lineIntersection(prev.e, prev.n, cur.e - prev.e, cur.n - prev.n, ae, an, ue, un)
                ?.let { newA = it }
        }
        if (extendNeighbors && shape.hasNextSegment(index)) {
            val after = v[(iB + 1) % count]
            val cur = v[iB]
            lineIntersection(after.e, after.n, cur.e - after.e, cur.n - after.n, ae, an, ue, un)
                ?.let { newB = it }
        }

        v[iA] = newA
        v[iB] = newB
        return shape.copy(vertices = v)
    }

    // ── 1. 手動旋轉單一線段 ───────────────────────
    /**
     * 以 [pivot] 為軸心，將線段 index 旋轉 [deltaRad]（逆時針為正）。
     *
     * 預設（[extendNeighbors] = false）鄰接線段共用接點自動跟隨轉動後的端點；
     * 設為 true 時鄰線保持原方向，接點以交點延伸 → 鄰線只變長度不變方向。
     */
    fun rotateSegment(
        shape: LineShape, index: Int, deltaRad: Double,
        pivot: RotatePivot = RotatePivot.MIDPOINT,
        extendNeighbors: Boolean = false
    ): LineShape {
        if (!shape.isValidSegment(index) || abs(deltaRad) < 1e-12) return shape
        val a = shape.segStart(index); val b = shape.segEnd(index)
        val pv = when (pivot) {
            RotatePivot.START -> a
            RotatePivot.END -> b
            RotatePivot.MIDPOINT -> LineVertex((a.e + b.e) / 2.0, (a.n + b.n) / 2.0)
        }
        val ra = rotatePoint(a, pv, deltaRad)
        val rb = rotatePoint(b, pv, deltaRad)
        val len = hypot(rb.e - ra.e, rb.n - ra.n)
        if (len < EPS) return shape

        if (!extendNeighbors) {
            val v = shape.vertices.toMutableList()
            v[index] = ra
            v[(index + 1) % v.size] = rb
            return shape.copy(vertices = v)
        }
        return replaceSegmentLine(
            shape, index,
            ra.e, ra.n, (rb.e - ra.e) / len, (rb.n - ra.n) / len,
            ra, rb, extendNeighbors = true
        )
    }

    /** 整體旋轉：以 [pivot] 為軸心轉動整個圖形（每段相對關係不變） */
    fun rotateShape(shape: LineShape, deltaRad: Double, pivot: LineVertex = centroid(shape)): LineShape {
        if (abs(deltaRad) < 1e-12) return shape
        return shape.copy(vertices = shape.vertices.map { rotatePoint(it, pivot, deltaRad) })
    }

    private fun rotatePoint(p: LineVertex, pivot: LineVertex, rad: Double): LineVertex {
        val c = cos(rad); val s = sin(rad)
        val de = p.e - pivot.e; val dn = p.n - pivot.n
        return LineVertex(pivot.e + de * c - dn * s, pivot.n + de * s + dn * c)
    }

    // ── 2. 手動平行拉伸 ───────────────────────────
    /**
     * 將線段 index 沿法線方向平移 [offset] 公尺（正值往左法線方向）。
     * 線段方向完全不變（保持平行），鄰接線段保持原方向、以交點重算接點，
     * 因此垂直方向的線會自動變長或縮短，接點依舊相連。
     */
    fun parallelStretch(shape: LineShape, index: Int, offset: Double): LineShape {
        if (!shape.isValidSegment(index) || abs(offset) < 1e-12) return shape
        val a = shape.segStart(index); val b = shape.segEnd(index)
        val len = hypot(b.e - a.e, b.n - a.n)
        if (len < EPS) return shape
        val ue = (b.e - a.e) / len; val un = (b.n - a.n) / len
        val ne = -un; val nn = ue
        val movedA = LineVertex(a.e + ne * offset, a.n + nn * offset)
        val movedB = LineVertex(b.e + ne * offset, b.n + nn * offset)
        return replaceSegmentLine(
            shape, index,
            movedA.e, movedA.n, ue, un,
            movedA, movedB, extendNeighbors = true
        )
    }

    /** 目前線段相對於原始線段的平行位移量（帶正負號，沿原線段左法線） */
    fun offsetBetween(base: LineShape, edited: LineShape, index: Int): Double {
        if (!base.isValidSegment(index) || !edited.isValidSegment(index)) return 0.0
        val n = segmentNormal(base, index) ?: return 0.0
        val a = base.segStart(index); val a2 = edited.segStart(index)
        return (a2.e - a.e) * n.first + (a2.n - a.n) * n.second
    }

    // ── 3. 接點（頂點）搬移 ───────────────────────
    fun moveVertex(shape: LineShape, vertexIndex: Int, e: Double, n: Double): LineShape {
        if (vertexIndex !in shape.vertices.indices) return shape
        val v = shape.vertices.toMutableList()
        v[vertexIndex] = LineVertex(e, n)
        return shape.copy(vertices = v)
    }

    // ── 命中測試 ─────────────────────────────────
    /** 點到線段的最短距離 */
    fun pointToSegmentDist(
        pe: Double, pn: Double, ae: Double, an: Double, be: Double, bn: Double
    ): Double {
        val abe = be - ae; val abn = bn - an
        val len2 = abe * abe + abn * abn
        if (len2 < 1e-18) return hypot(pe - ae, pn - an)
        val t = (((pe - ae) * abe + (pn - an) * abn) / len2).coerceIn(0.0, 1.0)
        return hypot(pe - (ae + t * abe), pn - (an + t * abn))
    }
}
