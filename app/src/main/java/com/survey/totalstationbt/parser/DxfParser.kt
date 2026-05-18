package com.survey.totalstationbt.parser

import android.graphics.PointF
import com.survey.totalstationbt.model.DxfData
import com.survey.totalstationbt.model.DxfEntity
import java.io.InputStream

object DxfParser {

    private data class Group(val code: Int, val value: String)

    fun parse(inputStream: InputStream): DxfData {
        val groups = readGroups(inputStream)
        return parseGroups(groups)
    }

    private fun readGroups(inputStream: InputStream): List<Group> {
        val groups = mutableListOf<Group>()
        val reader = try {
            inputStream.bufferedReader(Charsets.UTF_8)
        } catch (e: Exception) {
            inputStream.bufferedReader(Charsets.ISO_8859_1)
        }
        try {
            var line = reader.readLine()
            while (line != null) {
                val code = line.trim().toIntOrNull()
                val value = reader.readLine()?.trim() ?: break
                if (code != null) groups.add(Group(code, value))
                line = reader.readLine()
            }
        } catch (_: Exception) {}
        return groups
    }

    private fun parseGroups(groups: List<Group>): DxfData {
        val entities = mutableListOf<DxfEntity>()
        var i = 0
        var inEntities = false
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE

        fun updateBounds(x: Double, y: Double) {
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }

        fun collectUntilNextEntity(): List<Group> {
            val result = mutableListOf<Group>()
            while (i < groups.size && groups[i].code != 0) result.add(groups[i++])
            return result
        }

        fun addBoundsForEntity(e: DxfEntity) {
            when (e) {
                is DxfEntity.Line -> {
                    updateBounds(e.x1, e.y1); updateBounds(e.x2, e.y2)
                }
                is DxfEntity.Polyline -> e.vertices.forEach { updateBounds(it.x.toDouble(), it.y.toDouble()) }
                is DxfEntity.Circle -> {
                    updateBounds(e.cx - e.radius, e.cy - e.radius)
                    updateBounds(e.cx + e.radius, e.cy + e.radius)
                }
                is DxfEntity.Arc -> {
                    updateBounds(e.cx - e.radius, e.cy - e.radius)
                    updateBounds(e.cx + e.radius, e.cy + e.radius)
                }
            }
        }

        fun parseLine(eg: List<Group>, layer: String): DxfEntity.Line? {
            val p = eg.groupBy { it.code }
            val x1 = p[10]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
            val y1 = p[20]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
            val x2 = p[11]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
            val y2 = p[21]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
            return DxfEntity.Line(x1, y1, x2, y2, layer)
        }

        fun parseLwPolyline(eg: List<Group>, layer: String): DxfEntity.Polyline? {
            val flags = eg.firstOrNull { it.code == 70 }?.value?.toIntOrNull() ?: 0
            val closed = (flags and 1) != 0
            val vertices = mutableListOf<PointF>()
            var curX: Double? = null
            eg.forEach { g ->
                when (g.code) {
                    10 -> curX = g.value.toDoubleOrNull()
                    20 -> {
                        val x = curX; val y = g.value.toDoubleOrNull()
                        if (x != null && y != null) vertices.add(PointF(x.toFloat(), y.toFloat()))
                        curX = null
                    }
                }
            }
            return if (vertices.size >= 2) DxfEntity.Polyline(vertices, closed, layer) else null
        }

        fun parseCircle(eg: List<Group>, layer: String): DxfEntity.Circle? {
            val p = eg.groupBy { it.code }
            val cx = p[10]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
            val cy = p[20]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
            val r  = p[40]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
            return DxfEntity.Circle(cx, cy, r, layer)
        }

        fun parseArc(eg: List<Group>, layer: String): DxfEntity.Arc? {
            val p = eg.groupBy { it.code }
            val cx    = p[10]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
            val cy    = p[20]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
            val r     = p[40]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
            val start = p[50]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
            val end   = p[51]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
            return DxfEntity.Arc(cx, cy, r, start, end, layer)
        }

        // ── Main parse loop ───────────────────────
        while (i < groups.size) {
            val g = groups[i]
            when {
                g.code == 2 && g.value == "ENTITIES" -> { inEntities = true; i++ }
                g.code == 0 && g.value == "ENDSEC"   -> { inEntities = false; i++ }
                inEntities && g.code == 0 -> {
                    val type = g.value; i++
                    when (type) {
                        "LINE" -> {
                            val eg = collectUntilNextEntity()
                            val layer = eg.firstOrNull { it.code == 8 }?.value ?: "0"
                            parseLine(eg, layer)?.also { entities.add(it); addBoundsForEntity(it) }
                        }
                        "LWPOLYLINE" -> {
                            val eg = collectUntilNextEntity()
                            val layer = eg.firstOrNull { it.code == 8 }?.value ?: "0"
                            parseLwPolyline(eg, layer)?.also { entities.add(it); addBoundsForEntity(it) }
                        }
                        "CIRCLE" -> {
                            val eg = collectUntilNextEntity()
                            val layer = eg.firstOrNull { it.code == 8 }?.value ?: "0"
                            parseCircle(eg, layer)?.also { entities.add(it); addBoundsForEntity(it) }
                        }
                        "ARC" -> {
                            val eg = collectUntilNextEntity()
                            val layer = eg.firstOrNull { it.code == 8 }?.value ?: "0"
                            parseArc(eg, layer)?.also { entities.add(it); addBoundsForEntity(it) }
                        }
                        "POLYLINE" -> {
                            // Old-style: collect header, then scan VERTEX/SEQEND
                            val header = collectUntilNextEntity()
                            val flags  = header.firstOrNull { it.code == 70 }?.value?.toIntOrNull() ?: 0
                            val closed = (flags and 1) != 0
                            val layer  = header.firstOrNull { it.code == 8 }?.value ?: "0"
                            val vertices = mutableListOf<PointF>()

                            outer@ while (i < groups.size) {
                                if (groups[i].code != 0) { i++; continue }
                                val sub = groups[i].value; i++
                                when (sub) {
                                    "SEQEND" -> { collectUntilNextEntity(); break@outer }
                                    "VERTEX" -> {
                                        val vg = collectUntilNextEntity().groupBy { it.code }
                                        val x = vg[10]?.firstOrNull()?.value?.toDoubleOrNull()
                                        val y = vg[20]?.firstOrNull()?.value?.toDoubleOrNull()
                                        if (x != null && y != null) {
                                            vertices.add(PointF(x.toFloat(), y.toFloat()))
                                            updateBounds(x, y)
                                        }
                                    }
                                    else -> collectUntilNextEntity()
                                }
                            }
                            if (vertices.size >= 2) {
                                val poly = DxfEntity.Polyline(vertices, closed, layer)
                                entities.add(poly)
                            }
                        }
                        else -> collectUntilNextEntity() // skip TEXT, INSERT, SPLINE…
                    }
                }
                else -> i++
            }
        }

        if (minX == Double.MAX_VALUE) { minX = 0.0; maxX = 0.0; minY = 0.0; maxY = 0.0 }
        return DxfData(entities, minX, maxX, minY, maxY)
    }
}
