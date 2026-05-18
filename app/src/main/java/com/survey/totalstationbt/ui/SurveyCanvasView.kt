package com.survey.totalstationbt.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.survey.totalstationbt.db.PointEntity
import com.survey.totalstationbt.utils.SurveyMathUtils
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class SurveyCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var points: List<PointEntity> = emptyList()
    private val visiblePointIds = mutableSetOf<Long>()
    private var showOriginalLines = true
    private var elevationBasePoint: PointEntity? = null
    private var baselinePoints: Pair<PointEntity, PointEntity>? = null

    // 海拔色帶模式
    var elevationColorMode = false
        set(value) { field = value; invalidate() }
    private var minZ = 0.0
    private var maxZ = 0.0

    // 3D 模式
    var is3DMode = false
        set(value) { field = value; if (points.isNotEmpty()) autoFit(); invalidate() }

    // 3D 旋轉角度（弧度）：viewAzimuth 水平旋轉，viewPitch 仰角
    var viewAzimuth = Math.PI.toFloat() / 4f   // 預設 45°
    var viewPitch   = Math.PI.toFloat() / 6f   // 預設 30°
    private var cosAz = cos(Math.PI.toFloat() / 4f)
    private var sinAz = sin(Math.PI.toFloat() / 4f)
    private var sinEl = sin(Math.PI.toFloat() / 6f)
    private var cosEl = cos(Math.PI.toFloat() / 6f)

    private val PI_HALF = (Math.PI / 2.0).toFloat()
    private val ROTATE_SENS = 0.005f

    // 外部回呼
    var onSelectionChanged: ((List<PointEntity>) -> Unit)? = null
    var onLongPressPoint: ((PointEntity) -> Unit)? = null

    // ── Setters ──────────────────────────────────
    fun setVisiblePointIds(ids: Set<Long>) {
        visiblePointIds.clear()
        visiblePointIds.addAll(ids)
        invalidate()
    }

    fun setBaseline(p1: PointEntity?, p2: PointEntity?) {
        this.baselinePoints = if (p1 != null && p2 != null) Pair(p1, p2) else null
        invalidate()
    }

    fun setElevationBase(point: PointEntity?) {
        this.elevationBasePoint = point
        invalidate()
    }

    fun setShowOriginalLines(show: Boolean) {
        this.showOriginalLines = show
        invalidate()
    }

    // ── Paints ───────────────────────────────────
    private val pointPaint = Paint().apply {
        color = Color.CYAN; strokeWidth = 10f; strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE; textSize = 30f; isAntiAlias = true
    }
    private val linePaint = Paint().apply {
        color = Color.GRAY; strokeWidth = 2f; style = Paint.Style.STROKE
    }
    private val drawPath = Path()

    private val baselinePaint = Paint().apply {
        color = Color.rgb(255, 165, 0); strokeWidth = 3f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
    }
    private val selectionPaint = Paint().apply {
        color = Color.YELLOW; strokeWidth = 15f; strokeCap = Paint.Cap.ROUND
    }
    private val selectionLinePaint = Paint().apply {
        color = Color.YELLOW; strokeWidth = 6f; style = Paint.Style.STROKE; isAntiAlias = true
    }
    private val symbolPaint = Paint().apply {
        style = Paint.Style.FILL; isAntiAlias = true
    }
    private val notePaint = Paint().apply {
        color = Color.rgb(255, 200, 0); style = Paint.Style.FILL; isAntiAlias = true
    }

    // 3D 軸線
    private val axisPaintE = Paint().apply {
        color = Color.RED; style = Paint.Style.STROKE; isAntiAlias = true; strokeCap = Paint.Cap.ROUND
    }
    private val axisPaintN = Paint().apply {
        color = Color.GREEN; style = Paint.Style.STROKE; isAntiAlias = true; strokeCap = Paint.Cap.ROUND
    }
    private val axisPaintZ = Paint().apply {
        color = Color.rgb(80, 160, 255); style = Paint.Style.STROKE; isAntiAlias = true; strokeCap = Paint.Cap.ROUND
    }
    private val dropLinePaint = Paint().apply {
        color = Color.argb(80, 150, 150, 150); strokeWidth = 1f; style = Paint.Style.STROKE
    }

    // 比例尺
    private val scaleBarPaint = Paint().apply {
        color = Color.WHITE; strokeWidth = 3f; style = Paint.Style.STROKE; isAntiAlias = true; strokeCap = Paint.Cap.ROUND
    }
    private val scaleBarBgPaint = Paint().apply {
        color = Color.argb(120, 0, 0, 0); style = Paint.Style.FILL
    }
    private val scaleBarTextPaint = Paint().apply {
        color = Color.WHITE; textSize = 28f; textAlign = Paint.Align.CENTER; isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }

    // 指北箭頭
    private val northFillPaint = Paint().apply {
        color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true
    }
    private val northTextPaint = Paint().apply {
        color = Color.WHITE; textSize = 32f; textAlign = Paint.Align.CENTER; isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }
    private val northBgPaint = Paint().apply {
        color = Color.argb(140, 0, 0, 0); style = Paint.Style.FILL
    }

    // ── View transforms ──────────────────────────
    private var scaleFactor = 1.0f
    private var translateX = 0.0f
    private var translateY = 0.0f

    private var minE = 0.0; private var maxE = 0.0
    private var minN = 0.0; private var maxN = 0.0

    // ── Selection ────────────────────────────────
    private val selectedPoints = mutableListOf<PointEntity>()

    // ── Gesture detectors ────────────────────────
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val last = scaleFactor
                scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(0.001f, 500000f)
                val ratio = scaleFactor / last
                translateX = detector.focusX - (detector.focusX - translateX) * ratio
                translateY = detector.focusY - (detector.focusY - translateY) * ratio
                invalidate()
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent,
                                  distanceX: Float, distanceY: Float): Boolean {
                if (is3DMode && !scaleDetector.isInProgress) {
                    viewAzimuth -= distanceX * ROTATE_SENS
                    viewPitch    = (viewPitch - distanceY * ROTATE_SENS).coerceIn(0.05f, PI_HALF)
                    updateProjection()
                    invalidate()
                } else if (!is3DMode) {
                    translateX -= distanceX; translateY -= distanceY; invalidate()
                }
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                findClickedPoint(e.x, e.y)?.let { clicked ->
                    if (selectedPoints.contains(clicked)) selectedPoints.remove(clicked)
                    else selectedPoints.add(clicked)
                    onSelectionChanged?.invoke(selectedPoints)
                    invalidate()
                }
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                findClickedPoint(e.x, e.y)?.let { clicked ->
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onLongPressPoint?.invoke(clicked)
                }
            }
        })

    // ── Touch ────────────────────────────────────
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    // ── Hit-test ─────────────────────────────────
    private fun findClickedPoint(screenX: Float, screenY: Float): PointEntity? {
        if (is3DMode) {
            return points.filter { visiblePointIds.contains(it.id) }
                .minByOrNull { pt ->
                    val pos = screenPos3D(pt)
                    val dx = pos.x - screenX; val dy = pos.y - screenY
                    dx * dx + dy * dy
                }?.let { pt ->
                    val pos = screenPos3D(pt)
                    val dx = pos.x - screenX; val dy = pos.y - screenY
                    if (sqrt(dx * dx + dy * dy) < 80f) pt else null
                }
        } else {
            val wx = (screenX - translateX) / scaleFactor
            val wy = (screenY - translateY) / (-scaleFactor)
            return points.minByOrNull { pt ->
                val dx = ((pt.easting ?: 0.0) - minE).toFloat() - wx
                val dy = ((pt.northing ?: 0.0) - minN).toFloat() - wy
                dx * dx + dy * dy
            }?.let { pt ->
                val dx = (((pt.easting ?: 0.0) - minE).toFloat() - wx) * scaleFactor
                val dy = (((pt.northing ?: 0.0) - minN).toFloat() - wy) * scaleFactor
                if (sqrt(dx * dx + dy * dy) < 80f) pt else null
            }
        }
    }

    // ── Public API ───────────────────────────────
    fun clearSelection() {
        selectedPoints.clear(); onSelectionChanged?.invoke(selectedPoints); invalidate()
    }

    fun getSelectedPoints(): List<PointEntity> = selectedPoints

    fun setPoints(newPoints: List<PointEntity>) {
        this.points = newPoints
        calculateBounds()
        invalidate()
    }

    private fun calculateBounds() {
        if (points.isEmpty()) return
        minE = points.mapNotNull { it.easting  }.minOrNull() ?: 0.0
        maxE = points.mapNotNull { it.easting  }.maxOrNull() ?: 0.0
        minN = points.mapNotNull { it.northing }.minOrNull() ?: 0.0
        maxN = points.mapNotNull { it.northing }.maxOrNull() ?: 0.0
        minZ = points.mapNotNull { it.elevation }.minOrNull() ?: 0.0
        maxZ = points.mapNotNull { it.elevation }.maxOrNull() ?: 0.0
        autoFit()
    }

    fun autoFit() {
        if (is3DMode) { autoFit3D(); return }
        if (points.isEmpty()) return
        val rangeE = maxE - minE; val rangeN = maxN - minN
        val viewW = width.toFloat(); val viewH = height.toFloat()
        if (viewW == 0f || viewH == 0f) return
        val scaleE = if (rangeE != 0.0) viewW * 0.8f / rangeE.toFloat() else 1f
        val scaleN = if (rangeN != 0.0) viewH * 0.8f / rangeN.toFloat() else 1f
        scaleFactor = min(scaleE, scaleN)
        translateX = viewW / 2f - (rangeE / 2.0).toFloat() * scaleFactor
        translateY = viewH / 2f + (rangeN / 2.0).toFloat() * scaleFactor
        invalidate()
    }

    // ── 3D 投影 ──────────────────────────────────
    private fun updateProjection() {
        cosAz = cos(viewAzimuth); sinAz = sin(viewAzimuth)
        sinEl = sin(viewPitch);   cosEl = cos(viewPitch)
    }

    /** 設定預設視角（角度單位：度數） */
    fun setViewPreset(azDeg: Float, elDeg: Float) {
        viewAzimuth = Math.toRadians(azDeg.toDouble()).toFloat()
        viewPitch   = Math.toRadians(elDeg.toDouble()).toFloat()
        updateProjection()
        if (width > 0 && height > 0) autoFit3D()
        invalidate()
    }

    private fun project3D(e: Float, n: Float, z: Float): PointF {
        val sx =  e * cosAz - n * sinAz
        val sy = -(e * sinAz * sinEl + n * cosAz * sinEl + z * cosEl)
        return PointF(sx, sy)
    }

    private fun screenPos3D(pt: PointEntity): PointF {
        val e = ((pt.easting ?: 0.0) - minE).toFloat()
        val n = ((pt.northing ?: 0.0) - minN).toFloat()
        val z = ((pt.elevation ?: 0.0) - minZ).toFloat()
        val proj = project3D(e, n, z)
        return PointF(proj.x * scaleFactor + translateX, proj.y * scaleFactor + translateY)
    }

    private fun autoFit3D() {
        if (points.isEmpty() || width == 0 || height == 0) return
        var pMinX = Float.MAX_VALUE; var pMaxX = -Float.MAX_VALUE
        var pMinY = Float.MAX_VALUE; var pMaxY = -Float.MAX_VALUE
        points.forEach { pt ->
            val e = ((pt.easting ?: 0.0) - minE).toFloat()
            val n = ((pt.northing ?: 0.0) - minN).toFloat()
            val z = ((pt.elevation ?: 0.0) - minZ).toFloat()
            val p = project3D(e, n, z)
            if (p.x < pMinX) pMinX = p.x; if (p.x > pMaxX) pMaxX = p.x
            if (p.y < pMinY) pMinY = p.y; if (p.y > pMaxY) pMaxY = p.y
        }
        val rangeX = pMaxX - pMinX; val rangeY = pMaxY - pMinY
        val viewW = width.toFloat(); val viewH = height.toFloat()
        val scaleX = if (rangeX > 0) viewW * 0.72f / rangeX else 1f
        val scaleY = if (rangeY > 0) viewH * 0.72f / rangeY else 1f
        scaleFactor = min(scaleX, scaleY)
        translateX = viewW / 2f - ((pMinX + pMaxX) / 2f) * scaleFactor
        translateY = viewH / 2f - ((pMinY + pMaxY) / 2f) * scaleFactor
        invalidate()
    }

    // ── 海拔色彩 ─────────────────────────────────
    private fun elevationToColor(z: Double): Int {
        val range = maxZ - minZ
        if (range < 0.001) return Color.CYAN
        val t = ((z - minZ) / range).toFloat().coerceIn(0f, 1f)
        val hue = 240f * (1f - t)
        return Color.HSVToColor(floatArrayOf(hue, 0.85f, 0.90f))
    }

    // ── 點位符號 ─────────────────────────────────
    private fun drawPointSymbol(canvas: Canvas, x: Float, y: Float,
                                code: String, basePaint: Paint, radius: Float) {
        when (code.uppercase()) {
            "TREE", "T" -> {
                symbolPaint.color = Color.GREEN
                canvas.drawCircle(x, y, radius * 1.5f, symbolPaint)
                canvas.drawRect(x - radius/4, y, x + radius/4, y + radius * 2, symbolPaint)
            }
            "BM", "CP" -> {
                symbolPaint.color = Color.MAGENTA
                val path = Path()
                path.moveTo(x, y - radius * 2)
                path.lineTo(x + radius * 2, y + radius)
                path.lineTo(x - radius * 2, y + radius)
                path.close()
                canvas.drawPath(path, symbolPaint)
            }
            "MH" -> {
                symbolPaint.color = Color.GRAY
                canvas.drawCircle(x, y, radius * 2, symbolPaint)
                symbolPaint.color = Color.DKGRAY
                canvas.drawCircle(x, y, radius * 1.2f, symbolPaint)
            }
            else -> canvas.drawCircle(x, y, radius, basePaint)
        }
    }

    // ── onDraw 分派 ───────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return
        if (is3DMode) draw3D(canvas) else draw2D(canvas)
        drawScaleBar(canvas)
        drawNorthArrow(canvas)
    }

    // ── 2D 繪製 ───────────────────────────────────
    private fun draw2D(canvas: Canvas) {
        canvas.save()
        canvas.translate(translateX, translateY)
        canvas.scale(scaleFactor, -scaleFactor)

        val strokeWidth = 3f / scaleFactor
        val pointRadius = 6f / scaleFactor
        linePaint.strokeWidth     = strokeWidth
        pointPaint.strokeWidth    = strokeWidth
        selectionPaint.strokeWidth = strokeWidth
        selectionLinePaint.strokeWidth = 5f / scaleFactor

        // 原始連線
        if (showOriginalLines) {
            drawPath.reset(); var first = true
            points.forEach { pt ->
                if (!visiblePointIds.contains(pt.id)) return@forEach
                val e = ((pt.easting  ?: 0.0) - minE).toFloat()
                val n = ((pt.northing ?: 0.0) - minN).toFloat()
                if (first) { drawPath.moveTo(e, n); first = false } else drawPath.lineTo(e, n)
            }
            canvas.drawPath(drawPath, linePaint)
        }

        // 手動選取連線
        if (selectedPoints.size >= 2) {
            drawPath.reset()
            selectedPoints.forEachIndexed { idx, pt ->
                val e = ((pt.easting  ?: 0.0) - minE).toFloat()
                val n = ((pt.northing ?: 0.0) - minN).toFloat()
                if (idx == 0) drawPath.moveTo(e, n) else drawPath.lineTo(e, n)
            }
            if (selectedPoints.size >= 3) drawPath.close()
            canvas.drawPath(drawPath, selectionLinePaint)
        }

        // 基準線
        baselinePoints?.let { (p1, p2) ->
            baselinePaint.strokeWidth = 3f / scaleFactor
            canvas.drawLine(
                ((p1.easting  ?: 0.0) - minE).toFloat(), ((p1.northing ?: 0.0) - minN).toFloat(),
                ((p2.easting  ?: 0.0) - minE).toFloat(), ((p2.northing ?: 0.0) - minN).toFloat(),
                baselinePaint
            )
        }

        // 點位
        points.forEach { pt ->
            if (!visiblePointIds.contains(pt.id)) return@forEach
            val e = ((pt.easting  ?: 0.0) - minE).toFloat()
            val n = ((pt.northing ?: 0.0) - minN).toFloat()

            val paint = when {
                selectedPoints.contains(pt) -> selectionPaint
                elevationColorMode -> pointPaint.also { it.color = elevationToColor(pt.elevation ?: minZ) }
                else -> pointPaint.also { it.color = Color.CYAN }
            }
            drawPointSymbol(canvas, e, n, pt.code, paint, pointRadius)

            canvas.save()
            canvas.translate(e, n)
            canvas.scale(1f / scaleFactor, -1f / scaleFactor)
            canvas.drawText(pt.pointName, 15f, -15f, textPaint)
            if (pt.note.isNotEmpty()) canvas.drawCircle(8f, 8f, 6f, notePaint)
            drawElevationBaseLabel(canvas, pt)
            drawBaselineOffsetLabel(canvas, pt)
            canvas.restore()
        }

        canvas.restore()
    }

    // ── 3D 等角繪製 ──────────────────────────────
    private fun draw3D(canvas: Canvas) {
        canvas.save()
        canvas.translate(translateX, translateY)
        canvas.scale(scaleFactor, scaleFactor)

        val strokeWidth = 3f / scaleFactor
        val pointRadius = 6f / scaleFactor
        linePaint.strokeWidth          = strokeWidth
        pointPaint.strokeWidth         = strokeWidth
        selectionPaint.strokeWidth     = strokeWidth
        selectionLinePaint.strokeWidth = 5f / scaleFactor

        // 座標軸
        drawAxes3D(canvas, strokeWidth)

        // 原始連線
        if (showOriginalLines) {
            drawPath.reset(); var first = true
            points.forEach { pt ->
                if (!visiblePointIds.contains(pt.id)) return@forEach
                val proj = proj3D(pt)
                if (first) { drawPath.moveTo(proj.x, proj.y); first = false } else drawPath.lineTo(proj.x, proj.y)
            }
            canvas.drawPath(drawPath, linePaint)
        }

        // 手動選取連線
        if (selectedPoints.size >= 2) {
            drawPath.reset()
            selectedPoints.forEachIndexed { idx, pt ->
                val proj = proj3D(pt)
                if (idx == 0) drawPath.moveTo(proj.x, proj.y) else drawPath.lineTo(proj.x, proj.y)
            }
            if (selectedPoints.size >= 3) drawPath.close()
            canvas.drawPath(drawPath, selectionLinePaint)
        }

        // 垂線（增加立體感）
        val hasElevationRange = (maxZ - minZ) > 0.01
        if (hasElevationRange) {
            dropLinePaint.strokeWidth = 1f / scaleFactor
            points.forEach { pt ->
                if (!visiblePointIds.contains(pt.id)) return@forEach
                val top    = proj3D(pt)
                val bottom = project3D(
                    ((pt.easting  ?: 0.0) - minE).toFloat(),
                    ((pt.northing ?: 0.0) - minN).toFloat(),
                    0f
                )
                canvas.drawLine(top.x, top.y, bottom.x, bottom.y, dropLinePaint)
            }
        }

        // 點位
        points.forEach { pt ->
            if (!visiblePointIds.contains(pt.id)) return@forEach
            val proj = proj3D(pt)
            val paint = when {
                selectedPoints.contains(pt) -> selectionPaint
                elevationColorMode -> pointPaint.also { it.color = elevationToColor(pt.elevation ?: minZ) }
                else -> pointPaint.also { it.color = Color.CYAN }
            }
            drawPointSymbol(canvas, proj.x, proj.y, pt.code, paint, pointRadius)

            canvas.save()
            canvas.translate(proj.x, proj.y)
            canvas.scale(1f / scaleFactor, 1f / scaleFactor)
            canvas.drawText(pt.pointName, 15f, -15f, textPaint)
            if (pt.note.isNotEmpty()) canvas.drawCircle(8f, 8f, 6f, notePaint)
            drawElevationBaseLabel(canvas, pt)
            canvas.restore()
        }

        canvas.restore()
    }

    // 將 PointEntity 轉成等角投影座標
    private fun proj3D(pt: PointEntity) = project3D(
        ((pt.easting  ?: 0.0) - minE).toFloat(),
        ((pt.northing ?: 0.0) - minN).toFloat(),
        ((pt.elevation ?: 0.0) - minZ).toFloat()
    )

    private fun drawAxes3D(canvas: Canvas, strokeWidth: Float) {
        val rangeE = (maxE - minE).toFloat().coerceAtLeast(1f)
        val rangeN = (maxN - minN).toFloat().coerceAtLeast(1f)
        val rangeZ = (maxZ - minZ).toFloat().coerceAtLeast(1f)

        val origin = project3D(0f, 0f, 0f)
        val eEnd   = project3D(rangeE, 0f, 0f)
        val nEnd   = project3D(0f, rangeN, 0f)
        val zEnd   = project3D(0f, 0f, rangeZ)

        val axisW = strokeWidth * 2.5f
        axisPaintE.strokeWidth = axisW
        axisPaintN.strokeWidth = axisW
        axisPaintZ.strokeWidth = axisW

        canvas.drawLine(origin.x, origin.y, eEnd.x, eEnd.y, axisPaintE)
        canvas.drawLine(origin.x, origin.y, nEnd.x, nEnd.y, axisPaintN)
        canvas.drawLine(origin.x, origin.y, zEnd.x, zEnd.y, axisPaintZ)

        val off = 24f / scaleFactor
        drawAxisLabel(canvas, eEnd.x + off, eEnd.y, "E", Color.RED)
        drawAxisLabel(canvas, nEnd.x - off, nEnd.y, "N", Color.GREEN)
        drawAxisLabel(canvas, zEnd.x,       zEnd.y - off, "Z", Color.rgb(80, 160, 255))
    }

    private fun drawAxisLabel(canvas: Canvas, x: Float, y: Float, label: String, color: Int) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(1f / scaleFactor, 1f / scaleFactor)
        val old = textPaint.color
        textPaint.color = color
        canvas.drawText(label, 0f, 0f, textPaint)
        textPaint.color = old
        canvas.restore()
    }

    // ── 共用標注輔助 ─────────────────────────────
    private fun drawElevationBaseLabel(canvas: Canvas, pt: PointEntity) {
        elevationBasePoint?.let { base ->
            if (pt.id != base.id) {
                val dz = (pt.elevation ?: 0.0) - (base.elevation ?: 0.0)
                val oldColor = textPaint.color; val oldSize = textPaint.textSize
                textPaint.color = if (dz >= 0) Color.RED else Color.BLUE
                textPaint.textSize = 24f
                canvas.drawText(String.format(java.util.Locale.US, " ΔZ:%+.3f", dz), 15f, 15f, textPaint)
                textPaint.color = oldColor; textPaint.textSize = oldSize
            } else {
                canvas.drawText(" [基準]", 15f, 15f, textPaint)
            }
        }
    }

    private fun drawBaselineOffsetLabel(canvas: Canvas, pt: PointEntity) {
        baselinePoints?.let { (b1, b2) ->
            if (pt.id != b1.id && pt.id != b2.id) {
                SurveyMathUtils.calculateBaselineOffset(b1, b2, pt)?.let { res ->
                    val oldColor = textPaint.color; val oldSize = textPaint.textSize
                    textPaint.color = Color.YELLOW; textPaint.textSize = 20f
                    canvas.drawText(String.format(java.util.Locale.US, " S:%.2f O:%+.2f", res.station, res.offset),
                        15f, 40f, textPaint)
                    textPaint.color = oldColor; textPaint.textSize = oldSize
                }
            }
        }
    }

    // ── 比例尺 ───────────────────────────────────
    private fun drawScaleBar(canvas: Canvas) {
        val dp = resources.displayMetrics.density
        val targetPx = 90f * dp
        val worldDist = (targetPx / scaleFactor).toDouble()
        val niceWorld: Double = listOf(
            0.01, 0.05, 0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 25.0, 50.0,
            100.0, 250.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0
        ).firstOrNull { it >= worldDist } ?: worldDist

        val barPx = (niceWorld * scaleFactor.toDouble()).toFloat().coerceAtMost(width * 0.4f)
        val margin = 16f * dp
        val barBottom = height.toFloat() - margin
        val barLeft  = margin
        val barRight = barLeft + barPx
        val capH = 5f * dp

        canvas.drawRoundRect(
            barLeft - 8f * dp, barBottom - capH - 24f * dp,
            barRight + 8f * dp, barBottom + capH + 4f * dp,
            4f * dp, 4f * dp, scaleBarBgPaint
        )
        canvas.drawLine(barLeft, barBottom, barRight, barBottom, scaleBarPaint)
        canvas.drawLine(barLeft, barBottom - capH, barLeft, barBottom + capH, scaleBarPaint)
        canvas.drawLine(barRight, barBottom - capH, barRight, barBottom + capH, scaleBarPaint)

        val label = when {
            niceWorld >= 1000.0 -> "${(niceWorld / 1000.0).toInt()} km"
            niceWorld < 1.0     -> "${(niceWorld * 100.0).toInt()} cm"
            else                -> "${niceWorld.toInt()} m"
        }
        canvas.drawText(label, barLeft + barPx / 2f, barBottom - capH - 6f * dp, scaleBarTextPaint)
    }

    // ── 指北箭頭 ─────────────────────────────────
    private fun drawNorthArrow(canvas: Canvas) {
        val dp  = resources.displayMetrics.density
        val cx  = width - 30 * dp
        val cy  = 70 * dp
        val len = 22 * dp
        val w   = 7 * dp

        canvas.drawCircle(cx, cy, len * 1.2f, northBgPaint)

        val pathN = Path()
        pathN.moveTo(cx, cy - len)
        pathN.lineTo(cx - w, cy)
        pathN.lineTo(cx, cy - w * 0.4f)
        pathN.close()
        northFillPaint.color = Color.WHITE
        canvas.drawPath(pathN, northFillPaint)

        val pathS = Path()
        pathS.moveTo(cx, cy + len)
        pathS.lineTo(cx + w, cy)
        pathS.lineTo(cx, cy - w * 0.4f)
        pathS.close()
        northFillPaint.color = Color.LTGRAY
        canvas.drawPath(pathS, northFillPaint)

        canvas.drawText("N", cx, cy - len - 6 * dp, northTextPaint)
    }
}
