package com.survey.totalstationbt.model

import android.graphics.PointF
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class SnapType { ENDPOINT, MIDPOINT, CENTER, INTERSECTION }

data class DxfSnapPoint(
    val worldE: Double,
    val worldN: Double,
    val dxfX: Double,
    val dxfY: Double,
    val type: SnapType,
    val label: String
)

enum class DxfTapMode { NONE, QUERY, SNAP, MEASURE, RELATIVE }

sealed class DxfEntity {
    abstract val layer: String
    data class Line(val x1: Double, val y1: Double, val x2: Double, val y2: Double, override val layer: String = "0") : DxfEntity()
    data class Polyline(val vertices: List<PointF>, val closed: Boolean = false, override val layer: String = "0") : DxfEntity()
    data class Circle(val cx: Double, val cy: Double, val radius: Double, override val layer: String = "0") : DxfEntity()
    data class Arc(val cx: Double, val cy: Double, val radius: Double, val startDeg: Double, val endDeg: Double, override val layer: String = "0") : DxfEntity()
}

data class DxfData(
    val entities: List<DxfEntity>,
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double
) {
    val isEmpty get() = entities.isEmpty()
    val centerX get() = (minX + maxX) / 2.0
    val centerY get() = (minY + maxY) / 2.0
    // TWD97 / TWD67 E: 100000~500000, N: 2000000~3000000
    val isRealWorldCoord get() =
        centerX in 100_000.0..500_000.0 && centerY in 2_000_000.0..3_000_000.0
}

data class DxfTransform(
    val dx: Double = 0.0,
    val dy: Double = 0.0,
    val scale: Double = 1.0,
    val rotationRad: Double = 0.0
) {
    fun toWorld(x: Double, y: Double): Pair<Double, Double> {
        val c = cos(rotationRad); val s = sin(rotationRad)
        return (x * c - y * s) * scale + dx to (x * s + y * c) * scale + dy
    }

    fun inverse(worldE: Double, worldN: Double): Pair<Double, Double> {
        if (scale < 1e-12) return 0.0 to 0.0
        val c = cos(rotationRad); val s = sin(rotationRad)
        val u = (worldE - dx) / scale
        val v = (worldN - dy) / scale
        return (u * c + v * s) to (-u * s + v * c)
    }

    companion object {
        val IDENTITY = DxfTransform()

        fun fromTwoPoints(
            dxfX1: Double, dxfY1: Double, worldE1: Double, worldN1: Double,
            dxfX2: Double, dxfY2: Double, worldE2: Double, worldN2: Double
        ): DxfTransform? {
            val ddX = dxfX2 - dxfX1; val ddY = dxfY2 - dxfY1
            val dwE = worldE2 - worldE1; val dwN = worldN2 - worldN1
            val dLen = sqrt(ddX * ddX + ddY * ddY)
            val wLen = sqrt(dwE * dwE + dwN * dwN)
            if (dLen < 1e-10 || wLen < 1e-10) return null
            val scale = wLen / dLen
            val rot = atan2(dwN, dwE) - atan2(ddY, ddX)
            val c = cos(rot); val s = sin(rot)
            val dx = worldE1 - (dxfX1 * c - dxfY1 * s) * scale
            val dy = worldN1 - (dxfX1 * s + dxfY1 * c) * scale
            return DxfTransform(dx, dy, scale, rot)
        }
    }
}
