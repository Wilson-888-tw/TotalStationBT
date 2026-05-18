package com.survey.totalstationbt.utils

import com.survey.totalstationbt.db.PointEntity
import kotlin.math.*

object StakeoutCalculator {

    data class Guidance(
        val deltaN: Double,
        val deltaE: Double,
        val deltaZ: Double,
        val distance2D: Double,
        val azimuth: Double,
        val directionArrow: Float // Angle in degrees for arrow
    )

    /**
     * 計算當前點位相對於目標點位的偏差
     */
    fun getGuidance(current: PointEntity, target: PointEntity): Guidance? {
        val nC = current.northing ?: return null
        val eC = current.easting ?: return null
        val zC = current.elevation ?: 0.0
        
        val nT = target.northing ?: return null
        val eT = target.easting ?: return null
        val zT = target.elevation ?: 0.0
        
        val dN = nT - nC
        val dE = eT - eC
        val dZ = zT - zC
        
        val dist = sqrt(dN.pow(2) + dE.pow(2))
        val azimuth = Math.toDegrees(atan2(dE, dN))
        
        return Guidance(
            deltaN = dN,
            deltaE = dE,
            deltaZ = dZ,
            distance2D = dist,
            azimuth = if (azimuth < 0) azimuth + 360 else azimuth,
            directionArrow = azimuth.toFloat()
        )
    }

    /**
     * 將偏差轉換為語音指令字串
     */
    fun getVoiceCommand(guidance: Guidance, elevationOnly: Boolean = false): String {
        val res = StringBuilder()
        
        if (elevationOnly) {
            // 僅高程導引
            if (abs(guidance.deltaZ) < 0.01) {
                return "高程已達設計值"
            }
            res.append(if (guidance.deltaZ > 0) "向上填方 ${String.format("%.2f", guidance.deltaZ)} 公尺" else "向下挖方 ${String.format("%.2f", abs(guidance.deltaZ))} 公尺")
            return res.toString()
        }

        if (guidance.distance2D < 0.05) {
            return "點位已接近，誤差小於五公分"
        }
        
        // 簡單的 N/E 導引
        if (abs(guidance.deltaN) > 0.1) {
            res.append(if (guidance.deltaN > 0) "向北移動 ${String.format("%.1f", guidance.deltaN)} 公尺 " else "向南移動 ${String.format("%.1f", abs(guidance.deltaN))} 公尺 ")
        }
        if (abs(guidance.deltaE) > 0.1) {
            res.append(if (guidance.deltaE > 0) "向東移動 ${String.format("%.1f", guidance.deltaE)} 公尺 " else "向西移動 ${String.format("%.1f", abs(guidance.deltaE))} 公尺 ")
        }
        
        return res.toString()
    }
}
