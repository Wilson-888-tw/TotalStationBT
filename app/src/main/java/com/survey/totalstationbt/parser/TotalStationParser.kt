package com.survey.totalstationbt.parser

import com.survey.totalstationbt.model.DataFormat
import com.survey.totalstationbt.model.SurveyPoint

/**
 * 全站儀資料解析器
 * 支援：Leica GSI-8/16、Sokkia SDR33、Nikon RAW、Topcon GTS
 */
object TotalStationParser {

    fun parse(raw: String): SurveyPoint? {
        val line = raw.trim()
        if (line.isEmpty()) return null

        return when {
            isGSI8(line)   -> parseGSI(line, DataFormat.GSI8)
            isGSI16(line)  -> parseGSI(line, DataFormat.GSI16)
            isSDR33(line)  -> parseSDR33(line)
            isNikon(line)  -> parseNikon(line)
            isTopcon(line) -> parseTopcon(line)
            else           -> parseGeneric(line)
        }
    }

    // ─────────────────────────────────────────
    // GSI-8 / GSI-16 (Leica)
    // 格式範例：
    //   *110001+00000001 21.324+00012345 22.324+00098765 31..10+00056789
    // ─────────────────────────────────────────
    private fun isGSI8(line: String)  = line.matches(Regex("\\*?\\d{2}\\d{4}[+-].{8}.*"))
    private fun isGSI16(line: String) = line.matches(Regex("\\*?\\d{2}\\d{6}[+-].{16}.*"))

    private fun parseGSI(line: String, format: DataFormat): SurveyPoint? {
        return try {
            val tokens = line.trim('*').split(Regex("\\s+"))
            var pointName = ""
            var ha: Double? = null; var va: Double? = null
            var sd: Double? = null; var hd: Double? = null; var vd: Double? = null
            var n: Double? = null;  var e: Double? = null;  var h: Double? = null

            for (token in tokens) {
                if (token.length < 7) continue
                val wi   = token.substring(0, 2).trim()          // Word Index
                val value = token.substring(token.indexOf('+').coerceAtLeast(token.indexOf('-'))).toDoubleOrNull() ?: continue

                // 點號欄位(11) -> 取最後8碼點名
                val pn = if (token.length >= 10) token.substring(2, 6).trim() else ""

                when (wi) {
                    "11" -> pointName = pn.trimStart('0').ifEmpty { "0" }
                    "21" -> ha = gsiToDegrees(value)    // 水平角（度分秒*10000）
                    "22" -> va = gsiToDegrees(value)    // 垂直角
                    "31" -> sd = value / 10000.0         // 斜距 (mm→m)
                    "32" -> hd = value / 10000.0         // 水平距
                    "33" -> vd = value / 10000.0         // 垂直距
                    "81" -> n  = value / 10000.0         // N坐標
                    "82" -> e  = value / 10000.0         // E坐標
                    "83" -> h  = value / 10000.0         // H高程
                }
            }

            SurveyPoint(
                pointName = pointName,
                northing = n, easting = e, elevation = h,
                horizontalAngle = ha, verticalAngle = va,
                slopeDistance = sd, horizontalDistance = hd, verticalDistance = vd,
                rawData = line, format = format
            )
        } catch (e: Exception) { null }
    }

    private fun gsiToDegrees(raw: Double): Double {
        // GSI 角度單位：DDDMMSS.s * 10000（或gon）
        val abs = Math.abs(raw / 10000.0)
        val deg = abs.toInt()
        val minRaw = (abs - deg) * 100
        val min = minRaw.toInt()
        val sec = (minRaw - min) * 100
        return (if (raw < 0) -1 else 1) * (deg + min / 60.0 + sec / 3600.0)
    }

    // ─────────────────────────────────────────
    // SDR33 (Sokkia / Pentax)
    // 格式範例：
    //   00NMSDR33
    //   02CP1234  0001
    //   07TR1234  0001 03 123456  876543  001234
    // ─────────────────────────────────────────
    private fun isSDR33(line: String) = line.startsWith("00NM") ||
            line.matches(Regex("0[0-9][A-Z]{2}.+"))

    private fun parseSDR33(line: String): SurveyPoint? {
        return try {
            val code = if (line.length >= 4) line.substring(0, 4) else return null
            when (code) {
                "07TR", "08TR" -> {  // 量測記錄
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size < 7) return null
                    val pn  = parts[1].trim()
                    val ha  = sdrAngle(parts[3])
                    val va  = sdrAngle(parts[4])
                    val sd  = parts[5].toDoubleOrNull()?.div(1000.0)
                    SurveyPoint(
                        pointName = pn,
                        horizontalAngle = ha, verticalAngle = va, slopeDistance = sd,
                        rawData = line, format = DataFormat.SDR33
                    )
                }
                "13CO" -> {  // 坐標記錄
                    val parts = line.split(Regex("\\s+"))
                    val pn = parts.getOrNull(1) ?: ""
                    val n  = parts.getOrNull(2)?.toDoubleOrNull()
                    val e  = parts.getOrNull(3)?.toDoubleOrNull()
                    val h  = parts.getOrNull(4)?.toDoubleOrNull()
                    SurveyPoint(
                        pointName = pn, northing = n, easting = e, elevation = h,
                        rawData = line, format = DataFormat.SDR33
                    )
                }
                else -> null
            }
        } catch (ex: Exception) { null }
    }

    private fun sdrAngle(s: String): Double? {
        // DDDMMSS.s 格式
        val v = s.toDoubleOrNull() ?: return null
        val deg = (v / 10000).toInt()
        val min = ((v / 100) % 100).toInt()
        val sec = v % 100
        return deg + min / 60.0 + sec / 3600.0
    }

    // ─────────────────────────────────────────
    // Nikon RAW
    // 格式範例：
    //   CO,1,,0.000,90.0000,0.000,N,E,Z,PPM,R
    //   SS,PT1,0.000,90.3000,270.1234,56.789,N,E
    // ─────────────────────────────────────────
    private fun isNikon(line: String) = line.matches(Regex("(CO|SS|SP|RE|BK|SO),.*"))

    private fun parseNikon(line: String): SurveyPoint? {
        return try {
            val parts = line.split(",")
            val code  = parts[0]
            when (code) {
                "SS", "SP" -> {
                    val pn = parts.getOrNull(1) ?: ""
                    val hi = parts.getOrNull(2)?.toDoubleOrNull()
                    val va = parts.getOrNull(3)?.toDoubleOrNull()
                    val ha = parts.getOrNull(4)?.toDoubleOrNull()
                    val sd = parts.getOrNull(5)?.toDoubleOrNull()
                    val hd = if (sd != null && va != null) sd * Math.sin(Math.toRadians(va)) else null
                    val vd = if (sd != null && va != null) sd * Math.cos(Math.toRadians(va)) else null
                    SurveyPoint(
                        pointName = pn, horizontalAngle = ha, verticalAngle = va,
                        slopeDistance = sd, horizontalDistance = hd, verticalDistance = vd,
                        rawData = line, format = DataFormat.NIKON_RAW
                    )
                }
                "CO" -> {  // 坐標
                    val pn = parts.getOrNull(1) ?: ""
                    val n  = parts.getOrNull(5)?.toDoubleOrNull()
                    val e  = parts.getOrNull(6)?.toDoubleOrNull()
                    val h  = parts.getOrNull(7)?.toDoubleOrNull()
                    SurveyPoint(
                        pointName = pn, northing = n, easting = e, elevation = h,
                        rawData = line, format = DataFormat.NIKON_RAW
                    )
                }
                else -> null
            }
        } catch (ex: Exception) { null }
    }

    // ─────────────────────────────────────────
    // Topcon GTS 格式
    // 格式範例：
    //   HA=123.4567 VA=89.1234 SD=123.456 HD=123.400 VD=1.234
    // ─────────────────────────────────────────
    private fun isTopcon(line: String) = line.contains("HA=") || line.contains("VA=") ||
            line.contains("SD=")

    private fun parseTopcon(line: String): SurveyPoint? {
        return try {
            fun extract(key: String) = Regex("$key=([+-]?[\\d.]+)").find(line)
                ?.groupValues?.get(1)?.toDoubleOrNull()

            val ha = extract("HA"); val va = extract("VA")
            val sd = extract("SD"); val hd = extract("HD")
            val vd = extract("VD"); val n  = extract("N")
            val e  = extract("E");  val h  = extract("H")
            val pn = Regex("PT=([\\w-]+)").find(line)?.groupValues?.get(1) ?: ""

            SurveyPoint(
                pointName = pn,
                northing = n, easting = e, elevation = h,
                horizontalAngle = ha, verticalAngle = va,
                slopeDistance = sd, horizontalDistance = hd, verticalDistance = vd,
                rawData = line, format = DataFormat.TOPCON
            )
        } catch (ex: Exception) { null }
    }

    // ─────────────────────────────────────────
    // 通用解析（逗號/空白分隔數值）
    // ─────────────────────────────────────────
    private fun parseGeneric(line: String): SurveyPoint? {
        val parts = line.split(Regex("[,;\\s]+")).filter { it.isNotEmpty() }
        if (parts.size < 2) return null

        // 智慧型欄位判斷 (優先支持 E, N, Z)
        return if (parts.size >= 5) {
            // 5個欄位以上: 點號, E, N, Z, Code
            val pName = parts[0]
            val vE = parts[1].toDoubleOrNull()
            val vN = parts[2].toDoubleOrNull()
            val vZ = parts[3].toDoubleOrNull()
            val code = parts[4]
            
            SurveyPoint(
                pointName = pName,
                easting = vE, northing = vN, elevation = vZ,
                code = code, rawData = line, format = DataFormat.UNKNOWN
            )
        } else if (parts.size == 4) {
            // 4個欄位: 點號, E, N, Z
            SurveyPoint(
                pointName = parts[0],
                easting = parts[1].toDoubleOrNull(),
                northing = parts[2].toDoubleOrNull(),
                elevation = parts[3].toDoubleOrNull(),
                rawData = line
            )
        } else if (parts.size == 3) {
            // 3個欄位: 點號, E, N
            SurveyPoint(
                pointName = parts[0],
                easting = parts[1].toDoubleOrNull(),
                northing = parts[2].toDoubleOrNull(),
                rawData = line
            )
        } else {
            // 2個欄位: E, N
            SurveyPoint(
                pointName = "",
                easting = parts[0].toDoubleOrNull(),
                northing = parts[1].toDoubleOrNull(),
                rawData = line
            )
        }
    }
}
