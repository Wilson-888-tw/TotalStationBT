package com.survey.totalstationbt.utils

import com.survey.totalstationbt.db.PointEntity
import com.survey.totalstationbt.model.BaselineAnchor
import kotlin.math.*

object SurveyMathUtils {

    /**
     * 計算兩點間的 2D 距離
     */
    fun calculateDistance2D(p1: PointEntity, p2: PointEntity): Double? {
        val n1 = p1.northing ?: return null
        val e1 = p1.easting ?: return null
        val n2 = p2.northing ?: return null
        val e2 = p2.easting ?: return null
        
        return sqrt((n2 - n1).pow(2) + (e2 - e1).pow(2))
    }

    /**
     * 計算方位角 (Azimuth)
     * 返回 0-360 度，由北開始順時針
     */
    fun calculateAzimuth(p1: PointEntity, p2: PointEntity): Double? {
        val n1 = p1.northing ?: return null
        val e1 = p1.easting ?: return null
        val n2 = p2.northing ?: return null
        val e2 = p2.easting ?: return null
        
        val dn = n2 - n1
        val de = e2 - e1
        
        var azimuth = Math.toDegrees(atan2(de, dn))
        if (azimuth < 0) azimuth += 360.0
        return azimuth
    }

    /**
     * 計算多邊形面積 (鞋帶公式)
     */
    fun calculateArea(points: List<PointEntity>): Double {
        if (points.size < 3) return 0.0
        
        var area = 0.0
        for (i in points.indices) {
            val p1 = points[i]
            val p2 = points[(i + 1) % points.size]
            
            val e1 = p1.easting ?: 0.0
            val n1 = p1.northing ?: 0.0
            val e2 = p2.easting ?: 0.0
            val n2 = p2.northing ?: 0.0
            
            area += (e1 * n2 - e2 * n1)
        }
        
        return abs(area) / 2.0
    }

    /**
     * 計算周長 (Perimeter)
     */
    fun calculatePerimeter(points: List<PointEntity>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 0 until points.size - 1) {
            total += calculateDistance2D(points[i], points[i+1]) ?: 0.0
        }
        // 如果是 3 個點以上，自動閉合計算最後一段
        if (points.size >= 3) {
            total += calculateDistance2D(points.last(), points[0]) ?: 0.0
        }
        return total
    }

    /**
     * 基準線計算結果
     */
    data class BaselineResult(
        val station: Double, // 里程
        val offset: Double   // 偏移
    )

    /**
     * 計算點相對基準線 (P1->P2) 的里程與偏移
     */
    fun calculateBaselineOffset(p1: PointEntity, p2: PointEntity, p3: PointEntity): BaselineResult? {
        val x1 = p1.easting ?: return null; val y1 = p1.northing ?: return null
        val x2 = p2.easting ?: return null; val y2 = p2.northing ?: return null
        val x3 = p3.easting ?: return null; val y3 = p3.northing ?: return null
        return baselineOffsetCoords(x1, y1, x2, y2, x3, y3)
    }

    fun calculateBaselineOffset(b1: BaselineAnchor, b2: BaselineAnchor, pt: PointEntity): BaselineResult? {
        val x3 = pt.easting ?: return null; val y3 = pt.northing ?: return null
        return baselineOffsetCoords(b1.easting, b1.northing, b2.easting, b2.northing, x3, y3)
    }

    private fun baselineOffsetCoords(
        x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double
    ): BaselineResult? {
        val dx = x2 - x1; val dy = y2 - y1
        val L2 = dx * dx + dy * dy
        if (L2 == 0.0) return null
        val station = ((x3 - x1) * dx + (y3 - y1) * dy) / sqrt(L2)
        val offset  = ((x2 - x1) * (y3 - y1) - (y2 - y1) * (x3 - x1)) / sqrt(L2)
        return BaselineResult(station, offset)
    }

    /**
     * 計算夾角 (∠P1-P2-P3)
     * P2 是頂點
     */
    fun calculateAngle(p1: PointEntity, p2: PointEntity, p3: PointEntity): Double? {
        val n1 = p1.northing ?: return null; val e1 = p1.easting ?: return null
        val n2 = p2.northing ?: return null; val e2 = p2.easting ?: return null
        val n3 = p3.northing ?: return null; val e3 = p3.easting ?: return null

        // 向量 P2->P1
        val v1n = n1 - n2
        val v1e = e1 - e2
        
        // 向量 P2->P3
        val v2n = n3 - n2
        val v2e = e3 - e2

        val angle1 = atan2(v1e, v1n)
        val angle2 = atan2(v2e, v2n)

        var diff = Math.toDegrees(angle2 - angle1)
        if (diff < 0) diff += 360.0
        
        // 通常取較小角 (內角)
        return if (diff > 180.0) 360.0 - diff else diff
    }
}
