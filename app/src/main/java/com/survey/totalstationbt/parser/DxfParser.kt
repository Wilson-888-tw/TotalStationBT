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
        val bytes = try { inputStream.readBytes() } catch (e: Exception) { 
            android.util.Log.e("DxfParser", "Error reading bytes", e)
            return groups 
        }
        if (bytes.isEmpty()) return groups

        // Try decoding with different charsets: UTF-8 (common), Big5 (Traditional Chinese CAD), ISO-8859-1 (fallback)
        val charsets = listOf(Charsets.UTF_8, java.nio.charset.Charset.forName("Big5"), Charsets.ISO_8859_1)
        
        var decodedGroups: List<Group>? = null
        for (charset in charsets) {
            try {
                val currentGroups = mutableListOf<Group>()
                val reader = bytes.inputStream().bufferedReader(charset)
                var line = reader.readLine()
                while (line != null) {
                    val codeStr = line.trim()
                    if (codeStr.isEmpty()) {
                        line = reader.readLine()
                        continue
                    }
                    val code = codeStr.toIntOrNull()
                    val value = reader.readLine()?.trim() ?: break
                    
                    if (code == 2 && value == "THUMBNAILIMAGE") break
                    if (code != null) currentGroups.add(Group(code, value))
                    line = reader.readLine()
                }
                if (currentGroups.isNotEmpty()) {
                    decodedGroups = currentGroups
                    break
                }
            } catch (_: Exception) {
                continue
            }
        }
        
        return decodedGroups ?: emptyList()
    }

    private fun parseGroups(groups: List<Group>): DxfData {
        val entities = mutableListOf<DxfEntity>()
        val blocks = mutableMapOf<String, List<DxfEntity>>()
        var i = 0
        var inEntities = false
        var inBlocks = false
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
                is DxfEntity.Line -> { updateBounds(e.x1, e.y1); updateBounds(e.x2, e.y2) }
                is DxfEntity.Polyline -> e.vertices.forEach { updateBounds(it.x.toDouble(), it.y.toDouble()) }
                is DxfEntity.Circle -> { updateBounds(e.cx - e.radius, e.cy - e.radius); updateBounds(e.cx + e.radius, e.cy + e.radius) }
                is DxfEntity.Arc -> { updateBounds(e.cx - e.radius, e.cy - e.radius); updateBounds(e.cx + e.radius, e.cy + e.radius) }
                is DxfEntity.Point -> updateBounds(e.x, e.y)
                is DxfEntity.Text -> updateBounds(e.x, e.y)
                is DxfEntity.Ellipse -> {
                    val r = Math.sqrt(e.mx * e.mx + e.my * e.my)
                    updateBounds(e.cx - r, e.cy - r); updateBounds(e.cx + r, e.cy + r)
                }
                is DxfEntity.Insert -> updateBounds(e.x, e.y)
                is DxfEntity.Spline -> e.controlPoints.forEach { updateBounds(it.x.toDouble(), it.y.toDouble()) }
                is DxfEntity.Dimension -> { updateBounds(e.x1, e.y1); updateBounds(e.x2, e.y2) }
                is DxfEntity.Hatch -> e.loops.flatten().forEach { updateBounds(it.x.toDouble(), it.y.toDouble()) }
                is DxfEntity.Leader -> e.vertices.forEach { updateBounds(it.x.toDouble(), it.y.toDouble()) }
            }
        }

        fun parseEntity(type: String, eg: List<Group>): DxfEntity? {
            val layer = eg.firstOrNull { it.code == 8 }?.value ?: "0"
            return when (type) {
                "LINE" -> {
                    val p = eg.groupBy { it.code }
                    val x1 = p[10]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
                    val y1 = p[20]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
                    val x2 = p[11]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
                    val y2 = p[21]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
                    DxfEntity.Line(x1, y1, x2, y2, layer)
                }
                "LWPOLYLINE" -> {
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
                    if (vertices.size >= 2) DxfEntity.Polyline(vertices, closed, layer) else null
                }
                "CIRCLE" -> {
                    val p = eg.groupBy { it.code }
                    val cx = p[10]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
                    val cy = p[20]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
                    val r  = p[40]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
                    DxfEntity.Circle(cx, cy, r, layer)
                }
                "ARC" -> {
                    val p = eg.groupBy { it.code }
                    val cx    = p[10]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
                    val cy    = p[20]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
                    val r     = p[40]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
                    val start = p[50]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
                    val end   = p[51]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
                    DxfEntity.Arc(cx, cy, r, start, end, layer)
                }
                "POINT" -> {
                    val p = eg.groupBy { it.code }
                    val x = p[10]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
                    val y = p[20]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
                    DxfEntity.Point(x, y, layer)
                }
                "TEXT" -> {
                    val p = eg.groupBy { it.code }
                    val x = p[10]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
                    val y = p[20]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
                    val text = p[1]?.firstOrNull()?.value ?: ""
                    val h = p[40]?.firstOrNull()?.value?.toDoubleOrNull() ?: 1.0
                    val r = p[50]?.firstOrNull()?.value?.toDoubleOrNull() ?: 0.0
                    DxfEntity.Text(x, y, text, h, r, layer)
                }
                "MTEXT" -> {
                    val p = eg.groupBy { it.code }
                    val x = p[10]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
                    val y = p[20]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
                    val text = eg.filter { it.code == 1 || it.code == 3 }.joinToString("") { it.value }
                        .replace(Regex("\\\\[^;]+;"), "").replace("{", "").replace("}", "")
                    val h = p[40]?.firstOrNull()?.value?.toDoubleOrNull() ?: 1.0
                    DxfEntity.Text(x, y, text, h, 0.0, layer)
                }
                "ELLIPSE" -> {
                    val p = eg.groupBy { it.code }
                    val cx = p[10]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
                    val cy = p[20]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
                    val mx = p[11]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
                    val my = p[21]?.firstOrNull()?.value?.toDoubleOrNull() ?: return null
                    val ratio = p[40]?.firstOrNull()?.value?.toDoubleOrNull() ?: 1.0
                    DxfEntity.Ellipse(cx, cy, mx, my, ratio, layer)
                }
                "INSERT" -> {
                    val p = eg.groupBy { it.code }
                    val name = p[2]?.firstOrNull()?.value ?: return null
                    val x = p[10]?.firstOrNull()?.value?.toDoubleOrNull() ?: 0.0
                    val y = p[20]?.firstOrNull()?.value?.toDoubleOrNull() ?: 0.0
                    val sx = p[41]?.firstOrNull()?.value?.toDoubleOrNull() ?: 1.0
                    val sy = p[42]?.firstOrNull()?.value?.toDoubleOrNull() ?: 1.0
                    val rot = p[50]?.firstOrNull()?.value?.toDoubleOrNull() ?: 0.0
                    DxfEntity.Insert(name, x, y, sx, sy, rot, layer)
                }
                "SPLINE" -> {
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
                    if (vertices.size >= 2) DxfEntity.Spline(vertices, 3, layer) else null
                }
                "DIMENSION" -> {
                    val p = eg.groupBy { it.code }
                    val x1 = p[13]?.firstOrNull()?.value?.toDoubleOrNull() ?: 0.0
                    val y1 = p[23]?.firstOrNull()?.value?.toDoubleOrNull() ?: 0.0
                    val x2 = p[14]?.firstOrNull()?.value?.toDoubleOrNull() ?: 0.0
                    val y2 = p[24]?.firstOrNull()?.value?.toDoubleOrNull() ?: 0.0
                    val text = p[1]?.firstOrNull()?.value ?: ""
                    DxfEntity.Dimension(x1, y1, x2, y2, text, layer)
                }
                "LEADER" -> {
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
                    if (vertices.isNotEmpty()) DxfEntity.Leader(vertices, layer) else null
                }
                "HATCH" -> {
                    val loops = mutableListOf<List<PointF>>()
                    var currentLoop = mutableListOf<PointF>()
                    var curX: Double? = null
                    // Only collect vertices between code 91 (boundary start) and
                    // code 75/98 (post-boundary hatch style / seed points).
                    // Without this guard the elevation-plane normal (10:0/20:0)
                    // and seed-point coordinates are mistakenly added as vertices,
                    // producing spurious lines to the drawing origin.
                    var inBoundary = false
                    eg.forEach { g ->
                        when {
                            g.code == 91 -> inBoundary = true
                            g.code == 75 || g.code == 98 -> inBoundary = false
                            g.code == 92 && inBoundary -> {
                                if (currentLoop.isNotEmpty()) loops.add(currentLoop)
                                currentLoop = mutableListOf()
                                curX = null
                            }
                            g.code == 10 && inBoundary -> curX = g.value.toDoubleOrNull()
                            g.code == 20 && inBoundary -> {
                                val x = curX; val y = g.value.toDoubleOrNull()
                                if (x != null && y != null) currentLoop.add(PointF(x.toFloat(), y.toFloat()))
                                curX = null
                            }
                        }
                    }
                    if (currentLoop.isNotEmpty()) loops.add(currentLoop)
                    if (loops.isNotEmpty()) DxfEntity.Hatch(loops, layer) else null
                }
                else -> null
            }
        }

        // ── Main parse loop ───────────────────────
        while (i < groups.size) {
            val g = groups[i]
            when {
                g.code == 2 && g.value == "ENTITIES" -> { inEntities = true; i++ }
                g.code == 0 && g.value == "ENDSEC"   -> { inEntities = false; inBlocks = false; i++ }
                g.code == 2 && g.value == "BLOCKS"   -> { inBlocks = true; i++ }
                inEntities && g.code == 0 -> {
                    val type = g.value; i++
                    if (type == "POLYLINE") {
                        val header = collectUntilNextEntity()
                        val flags  = header.firstOrNull { it.code == 70 }?.value?.toIntOrNull() ?: 0
                        val closed = (flags and 1) != 0
                        val layer  = header.firstOrNull { it.code == 8 }?.value ?: "0"
                        val vertices = mutableListOf<PointF>()
                        outer@ while (i < groups.size) {
                            if (groups[i].code != 0) { i++; continue }
                            val sub = groups[i].value; i++
                            if (sub == "SEQEND") { collectUntilNextEntity(); break@outer }
                            if (sub == "VERTEX") {
                                val vg = collectUntilNextEntity().groupBy { it.code }
                                val x = vg[10]?.firstOrNull()?.value?.toDoubleOrNull()
                                val y = vg[20]?.firstOrNull()?.value?.toDoubleOrNull()
                                if (x != null && y != null) { vertices.add(PointF(x.toFloat(), y.toFloat())); updateBounds(x, y) }
                            } else collectUntilNextEntity()
                        }
                        if (vertices.size >= 2) entities.add(DxfEntity.Polyline(vertices, closed, layer))
                    } else {
                        val eg = collectUntilNextEntity()
                        parseEntity(type, eg)?.also { entities.add(it); addBoundsForEntity(it) }
                    }
                }
                inBlocks && g.code == 0 && g.value == "BLOCK" -> {
                    val blockHeader = collectUntilNextEntity()
                    val blockName = blockHeader.firstOrNull { it.code == 2 }?.value ?: ""
                    val blockEntities = mutableListOf<DxfEntity>()
                    while (i < groups.size) {
                        if (groups[i].code == 0 && groups[i].value == "ENDBLK") {
                            i++; collectUntilNextEntity(); break
                        }
                        if (groups[i].code == 0) {
                            val type = groups[i].value; i++
                            val eg = collectUntilNextEntity()
                            parseEntity(type, eg)?.also { blockEntities.add(it) }
                        } else i++
                    }
                    if (blockName.isNotEmpty()) blocks[blockName] = blockEntities
                }
                else -> i++
            }
        }

        if (minX == Double.MAX_VALUE) { minX = 0.0; maxX = 0.0; minY = 0.0; maxY = 0.0 }
        return DxfData(entities, minX, maxX, minY, maxY, blocks)
    }
}
