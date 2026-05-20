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
import com.survey.totalstationbt.model.DxfData
import com.survey.totalstationbt.model.DxfEntity
import com.survey.totalstationbt.model.DxfSnapPoint
import com.survey.totalstationbt.model.DxfTapMode
import com.survey.totalstationbt.model.DxfTransform
import com.survey.totalstationbt.model.BaselineAnchor
import com.survey.totalstationbt.model.SnapType
import com.survey.totalstationbt.utils.SurveyMathUtils
import kotlin.math.abs
import kotlin.math.atan2
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
    private var baselineA1: BaselineAnchor? = null
    private var baselineA2: BaselineAnchor? = null
    private var stakeoutTargetId: Long? = null
    private var currentStakeoutTarget: PointEntity? = null

    // ── DXF 底圖 ──────────────────────────────────
    private var dxfData: DxfData? = null
    private var dxfTransform = DxfTransform.IDENTITY
    var showDxf = true
        set(value) { field = value; invalidate() }
    
    var dxfAlpha = 200 // 0-255
        set(value) {
            field = value.coerceIn(0, 255)
            dxfPaint.alpha = field
            invalidate()
        }
    
    // DXF 圖層管理
    val visibleLayers = mutableSetOf<String>()
    fun setVisibleLayers(layers: Set<String>) {
        visibleLayers.clear()
        visibleLayers.addAll(layers)
        invalidate()
    }

    // 測量記錄回放 (軌跡)
    var showPlayback = false
        set(value) { field = value; invalidate() }
    var playbackProgress = 1.0f // 0.0 to 1.0
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

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

    // 3D 雙指平移追蹤
    private var twoFingerMidX = 0f
    private var twoFingerMidY = 0f
    private var isTwoFingerDragging = false

    // 外部回呼
    var onSelectionChanged: ((List<PointEntity>) -> Unit)? = null
    var onLongPressPoint: ((PointEntity) -> Unit)? = null

    // DXF 互動模式
    var dxfTapMode = DxfTapMode.NONE
        set(v) { field = v; highlightedEntity = null; highlightedSnapPt = null; invalidate() }
    private var highlightedEntity: DxfEntity? = null
    private var highlightedSnapPt: DxfSnapPoint? = null
    private val selectedSnapPoints = mutableListOf<DxfSnapPoint>()
    var onDxfEntityTapped: ((DxfEntity) -> Unit)? = null
    var onDxfSnapPicked: ((DxfSnapPoint) -> Unit)? = null

    // DXF 量測
    private val measurePoints = mutableListOf<DxfSnapPoint>()
    var onMeasureUpdated: ((List<DxfSnapPoint>) -> Unit)? = null

    // DXF 相對位置
    private var relativePointA: DxfSnapPoint? = null
    private var relativePointB: DxfSnapPoint? = null
    var onRelativePointA: ((DxfSnapPoint) -> Unit)? = null
    var onRelativePointsPicked: ((DxfSnapPoint, DxfSnapPoint) -> Unit)? = null

    fun clearRelativePoints() {
        relativePointA = null
        relativePointB = null
        invalidate()
    }

    // ── Setters ──────────────────────────────────
    fun setVisiblePointIds(ids: Set<Long>) {
        visiblePointIds.clear()
        visiblePointIds.addAll(ids)
        invalidate()
    }

    fun setBaseline(p1: PointEntity?, p2: PointEntity?) {
        baselineA1 = p1?.let { BaselineAnchor.from(it) }
        baselineA2 = p2?.let { BaselineAnchor.from(it) }
        invalidate()
    }

    fun setBaseline(a1: BaselineAnchor?, a2: BaselineAnchor?) {
        baselineA1 = a1
        baselineA2 = a2
        invalidate()
    }

    fun getBaselineAnchors(): Pair<BaselineAnchor?, BaselineAnchor?> = Pair(baselineA1, baselineA2)

    fun setStakeoutTarget(pt: PointEntity?) {
        currentStakeoutTarget = pt
        stakeoutTargetId = pt?.id?.takeIf { it != 0L }
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
    private val stakeoutPaint = Paint().apply {
        color = Color.rgb(255, 80, 0); strokeCap = Paint.Cap.ROUND; isAntiAlias = true
    }
    private val stakeoutRingPaint = Paint().apply {
        color = Color.rgb(255, 80, 0); style = Paint.Style.STROKE; isAntiAlias = true
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

    // DXF 底圖筆
    private val dxfPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 90, 140, 200)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dxfHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 255, 200, 0)
        strokeWidth = 3f; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val dxfSnapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 0, 230, 255)
        strokeWidth = 2f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val measureLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 165, 0); strokeWidth = 2f
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val measureFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(35, 255, 165, 0); style = Paint.Style.FILL
    }
    private val measureMarkerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 0, 210, 160); style = Paint.Style.FILL
    }
    private val measureMarkerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = 1.5f; style = Paint.Style.STROKE
    }
    private val measureTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 26f
        typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }
    private val measureLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 255, 220, 80); textSize = 22f
        typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }
    private val relativeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 180, 100, 255); strokeWidth = 2f
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(8f, 5f), 0f)
    }
    private val relativeMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 180, 100, 255); style = Paint.Style.FILL
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

    // 軌跡回放筆
    private val playbackTrailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 0) // 黃色半透明
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        pathEffect = DashPathEffect(floatArrayOf(15f, 10f), 0f)
    }
    private val playbackPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW; style = Paint.Style.FILL
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
                if (is3DMode) {
                    // 單指旋轉；雙指平移由 onTouchEvent 的 midpoint 追蹤處理
                    if (e2.pointerCount == 1 && !scaleDetector.isInProgress) {
                        viewAzimuth -= distanceX * ROTATE_SENS
                        viewPitch    = (viewPitch - distanceY * ROTATE_SENS).coerceIn(0.05f, PI_HALF)
                        updateProjection()
                        invalidate()
                    }
                } else if (!is3DMode) {
                    translateX -= distanceX; translateY -= distanceY; invalidate()
                }
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!is3DMode) {
                    when (dxfTapMode) {
                        DxfTapMode.QUERY -> {
                            hitTestDxf(e.x, e.y)?.let { entity ->
                                highlightedEntity = entity; highlightedSnapPt = null; invalidate()
                                onDxfEntityTapped?.invoke(entity); return true
                            }
                        }
                        DxfTapMode.SNAP -> {
                            findNearestSnapPoint(e.x, e.y)?.let { snap ->
                                highlightedSnapPt = snap; highlightedEntity = null; invalidate()
                                onDxfSnapPicked?.invoke(snap); return true
                            }
                        }
                        DxfTapMode.MEASURE -> {
                            findNearestSnapPoint(e.x, e.y)?.let { snap ->
                                measurePoints.add(snap); invalidate()
                                onMeasureUpdated?.invoke(measurePoints.toList()); return true
                            }
                        }
                        DxfTapMode.RELATIVE -> {
                            findNearestAnyPoint(e.x, e.y)?.let { snap ->
                                if (relativePointA == null || relativePointB != null) {
                                    relativePointA = snap
                                    relativePointB = null
                                    invalidate()
                                    onRelativePointA?.invoke(snap)
                                } else {
                                    relativePointB = snap
                                    invalidate()
                                    onRelativePointsPicked?.invoke(relativePointA!!, snap)
                                }
                                return true
                            }
                        }
                        DxfTapMode.NONE -> {}
                    }
                }
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

        // 3D 雙指平移：追蹤兩指中點位移
        if (is3DMode) {
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount == 2) {
                        twoFingerMidX = (event.getX(0) + event.getX(1)) / 2f
                        twoFingerMidY = (event.getY(0) + event.getY(1)) / 2f
                        isTwoFingerDragging = true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 2 && isTwoFingerDragging) {
                        val midX = (event.getX(0) + event.getX(1)) / 2f
                        val midY = (event.getY(0) + event.getY(1)) / 2f
                        translateX += midX - twoFingerMidX
                        translateY += midY - twoFingerMidY
                        twoFingerMidX = midX
                        twoFingerMidY = midY
                        invalidate()
                    }
                }
                MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> isTwoFingerDragging = false
            }
        }

        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
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

    fun setDxf(data: DxfData?, transform: DxfTransform = DxfTransform.IDENTITY) {
        dxfData = data
        dxfTransform = transform
        // 預設開啟所有圖元所屬圖層
        data?.entities?.map { it.layer }?.distinct()?.let {
            visibleLayers.clear()
            visibleLayers.addAll(it)
        }
        calculateBounds()
        invalidate()
    }

    fun clearDxf() { setDxf(null) }

    private fun calculateBounds() {
        val hasPoints = points.isNotEmpty()
        val hasDxf = dxfData != null && !dxfData!!.isEmpty

        if (!hasPoints && !hasDxf) return

        if (hasPoints) {
            minE = points.mapNotNull { it.easting  }.minOrNull() ?: 0.0
            maxE = points.mapNotNull { it.easting  }.maxOrNull() ?: 0.0
            minN = points.mapNotNull { it.northing }.minOrNull() ?: 0.0
            maxN = points.mapNotNull { it.northing }.maxOrNull() ?: 0.0
            minZ = points.mapNotNull { it.elevation }.minOrNull() ?: 0.0
            maxZ = points.mapNotNull { it.elevation }.maxOrNull() ?: 0.0
        }

        dxfData?.let { dxf ->
            val corners = listOf(
                dxfTransform.toWorld(dxf.minX, dxf.minY),
                dxfTransform.toWorld(dxf.maxX, dxf.minY),
                dxfTransform.toWorld(dxf.minX, dxf.maxY),
                dxfTransform.toWorld(dxf.maxX, dxf.maxY)
            )
            val dMinE = corners.minOf { it.first }
            val dMaxE = corners.maxOf { it.first }
            val dMinN = corners.minOf { it.second }
            val dMaxN = corners.maxOf { it.second }
            if (!hasPoints) {
                minE = dMinE; maxE = dMaxE; minN = dMinN; maxN = dMaxN
            } else {
                minE = min(minE, dMinE); maxE = max(maxE, dMaxE)
                minN = min(minN, dMinN); maxN = max(maxN, dMaxN)
            }
        }
        autoFit()
    }

    fun autoFit() {
        if (is3DMode) { autoFit3D(); return }
        val hasPoints = points.isNotEmpty()
        val hasDxf = dxfData != null && !dxfData!!.isEmpty
        if (!hasPoints && !hasDxf) return
        
        val rangeE = maxE - minE; val rangeN = maxN - minN
        val viewW = width.toFloat(); val viewH = height.toFloat()
        if (viewW == 0f || viewH == 0f) return
        val scaleE = if (rangeE > 0.0) viewW * 0.8f / rangeE.toFloat() else 1f
        val scaleN = if (rangeN > 0.0) viewH * 0.8f / rangeN.toFloat() else 1f
        scaleFactor = min(scaleE, scaleN)
        translateX = viewW / 2f - (rangeE / 2.0).toFloat() * scaleFactor
        translateY = viewH / 2f + (rangeN / 2.0).toFloat() * scaleFactor
        invalidate()
    }

    /** 將視角中心對準特定點位 */
    fun centerOnPoint(pt: PointEntity) {
        val e = pt.easting ?: return
        val n = pt.northing ?: return
        val viewW = width.toFloat(); val viewH = height.toFloat()
        if (viewW == 0f || viewH == 0f) return
        
        // 保持目前縮放，僅平移
        translateX = viewW / 2f - (e - minE).toFloat() * scaleFactor
        translateY = viewH / 2f + (n - minN).toFloat() * scaleFactor
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
        val hasPoints = points.isNotEmpty()
        val hasDxf = dxfData != null && !dxfData!!.isEmpty // Note: DXF 3D support might be limited, but we check anyway
        if ((!hasPoints && !hasDxf) || width == 0 || height == 0) return
        
        var pMinX = Float.MAX_VALUE; var pMaxX = -Float.MAX_VALUE
        var pMinY = Float.MAX_VALUE; var pMaxY = -Float.MAX_VALUE
        
        // Points bounds
        points.forEach { pt ->
            val e = ((pt.easting ?: 0.0) - minE).toFloat()
            val n = ((pt.northing ?: 0.0) - minN).toFloat()
            val z = ((pt.elevation ?: 0.0) - minZ).toFloat()
            val p = project3D(e, n, z)
            if (p.x < pMinX) pMinX = p.x; if (p.x > pMaxX) pMaxX = p.x
            if (p.y < pMinY) pMinY = p.y; if (p.y > pMaxY) pMaxY = p.y
        }
        
        // DXF bounds (treating as Z=0 for now in 3D auto-fit if points are present)
        dxfData?.let { dxf ->
            listOf(
                dxfTransform.toWorld(dxf.minX, dxf.minY),
                dxfTransform.toWorld(dxf.maxX, dxf.minY),
                dxfTransform.toWorld(dxf.minX, dxf.maxY),
                dxfTransform.toWorld(dxf.maxX, dxf.maxY)
            ).forEach { (we, wn) ->
                val p = project3D((we - minE).toFloat(), (wn - minN).toFloat(), 0f)
                if (p.x < pMinX) pMinX = p.x; if (p.x > pMaxX) pMaxX = p.x
                if (p.y < pMinY) pMinY = p.y; if (p.y > pMaxY) pMaxY = p.y
            }
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
        if (points.isEmpty() && dxfData == null) return
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

        // DXF 底圖（最先畫，在最底層）
        if (showDxf) drawDxf2D(canvas)

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
        val b1 = baselineA1
        val b2 = baselineA2
        if (b1 != null || b2 != null) {
            baselinePaint.strokeWidth = 3f / scaleFactor
            if (b1 != null && b2 != null) {
                canvas.drawLine(
                    (b1.easting - minE).toFloat(), (b1.northing - minN).toFloat(),
                    (b2.easting - minE).toFloat(), (b2.northing - minN).toFloat(),
                    baselinePaint
                )
            }
            // 繪製基準線錨點標記
            b1?.let { drawBaselineAnchor(canvas, it, "P1") }
            b2?.let { drawBaselineAnchor(canvas, it, "P2") }
        }

        // 軌跡回放 (在點位下方)
        if (showPlayback) drawPlaybackTrail(canvas)

        // 點位
        points.forEach { pt ->
            if (!visiblePointIds.contains(pt.id)) return@forEach
            val e = ((pt.easting  ?: 0.0) - minE).toFloat()
            val n = ((pt.northing ?: 0.0) - minN).toFloat()

            val isStakeoutTarget = pt.id != 0L && pt.id == stakeoutTargetId
            val paint = when {
                selectedPoints.contains(pt) -> selectionPaint
                isStakeoutTarget -> stakeoutPaint.also { it.strokeWidth = strokeWidth }
                elevationColorMode -> pointPaint.also { it.color = elevationToColor(pt.elevation ?: minZ) }
                else -> pointPaint.also { it.color = Color.CYAN }
            }
            val radius = if (isStakeoutTarget) pointRadius * 1.6f else pointRadius
            drawPointSymbol(canvas, e, n, pt.code, paint, radius)
            if (isStakeoutTarget) {
                stakeoutRingPaint.strokeWidth = 2f / scaleFactor
                canvas.drawCircle(e, n, radius * 2.2f, stakeoutRingPaint)
            }

            canvas.save()
            canvas.translate(e, n)
            canvas.scale(1f / scaleFactor, -1f / scaleFactor)
            canvas.drawText(pt.pointName, 15f, -15f, textPaint)
            if (pt.note.isNotEmpty()) canvas.drawCircle(8f, 8f, 6f, notePaint)
            drawElevationBaseLabel(canvas, pt)
            drawBaselineOffsetLabel(canvas, pt)
            canvas.restore()
        }

        // 單獨處理不在 points 清單中的放樣目標 (例如 DXF 捕捉點)
        currentStakeoutTarget?.let { target ->
            if (target.id != 0L && visiblePointIds.contains(target.id)) return@let
            
            val e = ((target.easting ?: 0.0) - minE).toFloat()
            val n = ((target.northing ?: 0.0) - minN).toFloat()
            
            stakeoutPaint.strokeWidth = strokeWidth
            val radius = pointRadius * 1.6f
            drawPointSymbol(canvas, e, n, target.code, stakeoutPaint, radius)
            
            stakeoutRingPaint.strokeWidth = 2f / scaleFactor
            canvas.drawCircle(e, n, radius * 2.2f, stakeoutRingPaint)

            canvas.save()
            canvas.translate(e, n)
            canvas.scale(1f / scaleFactor, -1f / scaleFactor)
            canvas.drawText(target.pointName, 15f, -15f, textPaint)
            canvas.restore()
        }

        // 量測疊加層
        if (measurePoints.isNotEmpty()) drawMeasureOverlay(canvas)
        // 相對位置疊加層
        if (relativePointA != null) drawRelativeOverlay(canvas)

        // 繪製捕捉點高亮 (Snap Feedback)
        drawSnapHighlight(canvas)

        canvas.restore()
    }

    // ── DXF 底圖繪製（2D 模式）───────────────────
    private fun dxfToLocal(dxfX: Double, dxfY: Double): PointF {
        val (e, n) = dxfTransform.toWorld(dxfX, dxfY)
        return PointF((e - minE).toFloat(), (n - minN).toFloat())
    }

    private fun worldToLocal(worldE: Double, worldN: Double): PointF =
        PointF((worldE - minE).toFloat(), (worldN - minN).toFloat())

    private fun drawDxf2D(canvas: Canvas) {
        val dxf = dxfData ?: return
        dxfPaint.strokeWidth = 1.5f / scaleFactor

        dxf.entities.forEach { entity ->
            if (visibleLayers.contains(entity.layer)) {
                drawSingleDxfEntity(canvas, entity, dxfPaint, dxf.blocks)
            }
        }

        // 高亮選中圖元
        highlightedEntity?.let { entity ->
            dxfHighlightPaint.strokeWidth = 2.5f / scaleFactor
            drawSingleDxfEntity(canvas, entity, dxfHighlightPaint, dxf.blocks)
        }
    }

    private fun drawSingleDxfEntity(canvas: Canvas, entity: DxfEntity, paint: Paint, blocks: Map<String, List<DxfEntity>>) {
        when (entity) {
            is DxfEntity.Line -> {
                val p1 = dxfToLocal(entity.x1, entity.y1)
                val p2 = dxfToLocal(entity.x2, entity.y2)
                canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paint)
            }
            is DxfEntity.Polyline -> {
                if (entity.vertices.isEmpty()) return
                val path = Path()
                entity.vertices.forEachIndexed { idx, v ->
                    val p = dxfToLocal(v.x.toDouble(), v.y.toDouble())
                    if (idx == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                if (entity.closed) path.close()
                canvas.drawPath(path, paint)
            }
            is DxfEntity.Circle -> {
                val c = dxfToLocal(entity.cx, entity.cy)
                val r = (entity.radius * dxfTransform.scale).toFloat()
                canvas.drawCircle(c.x, c.y, r, paint)
            }
            is DxfEntity.Arc -> {
                val path = Path()
                var startDeg = entity.startDeg; var endDeg = entity.endDeg
                if (endDeg <= startDeg) endDeg += 360.0
                val steps = 48; val step = (endDeg - startDeg) / steps
                for (s in 0..steps) {
                    val ar = Math.toRadians(startDeg + s * step)
                    val p = dxfToLocal(entity.cx + entity.radius * cos(ar), entity.cy + entity.radius * sin(ar))
                    if (s == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                canvas.drawPath(path, paint)
            }
            is DxfEntity.Point -> {
                val p = dxfToLocal(entity.x, entity.y)
                canvas.drawPoint(p.x, p.y, paint)
            }
            is DxfEntity.Text -> {
                val p = dxfToLocal(entity.x, entity.y)
                canvas.save()
                canvas.translate(p.x, p.y)
                canvas.scale(1f / scaleFactor, -1f / scaleFactor)
                val oldSize = textPaint.textSize
                val oldColor = textPaint.color
                textPaint.textSize = (entity.height * dxfTransform.scale * scaleFactor).toFloat()
                textPaint.color = paint.color
                canvas.rotate((-entity.rotation).toFloat())
                canvas.drawText(entity.text, 0f, 0f, textPaint)
                textPaint.textSize = oldSize
                textPaint.color = oldColor
                canvas.restore()
            }
            is DxfEntity.Ellipse -> {
                val path = Path()
                val steps = 64
                for (s in 0..steps) {
                    val t = (2 * Math.PI * s) / steps
                    val ex = entity.cx + entity.mx * cos(t) - (entity.my * entity.ratio) * sin(t)
                    val ey = entity.cy + entity.my * cos(t) + (entity.mx * entity.ratio) * sin(t)
                    val p = dxfToLocal(ex, ey)
                    if (s == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                canvas.drawPath(path, paint)
            }
            is DxfEntity.Insert -> {
                val block = blocks[entity.blockName] ?: return
                canvas.save()
                val p = dxfToLocal(entity.x, entity.y)
                canvas.translate(p.x, p.y)
                canvas.scale(entity.scaleX.toFloat(), entity.scaleY.toFloat())
                canvas.rotate(entity.rotation.toFloat())
                block.forEach { drawSingleDxfEntity(canvas, it, paint, blocks) }
                canvas.restore()
            }
            is DxfEntity.Spline -> {
                if (entity.controlPoints.isEmpty()) return
                val path = Path()
                entity.controlPoints.forEachIndexed { idx, v ->
                    val p = dxfToLocal(v.x.toDouble(), v.y.toDouble())
                    if (idx == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                canvas.drawPath(path, paint)
            }
            is DxfEntity.Dimension -> {
                val p1 = dxfToLocal(entity.x1, entity.y1)
                val p2 = dxfToLocal(entity.x2, entity.y2)
                canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paint)
                if (entity.text.isNotEmpty()) {
                    val mx = (p1.x + p2.x) / 2f; val my = (p1.y + p2.y) / 2f
                    canvas.save()
                    canvas.translate(mx, my)
                    canvas.scale(1f / scaleFactor, -1f / scaleFactor)
                    canvas.drawText(entity.text, 0f, 0f, textPaint)
                    canvas.restore()
                }
            }
            is DxfEntity.Hatch -> {
                entity.loops.forEach { loop ->
                    if (loop.isEmpty()) return@forEach
                    val path = Path()
                    loop.forEachIndexed { idx, v ->
                        val p = dxfToLocal(v.x.toDouble(), v.y.toDouble())
                        if (idx == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }
                    path.close()
                    canvas.drawPath(path, paint)
                }
            }
            is DxfEntity.Leader -> {
                if (entity.vertices.isEmpty()) return
                val path = Path()
                entity.vertices.forEachIndexed { idx, v ->
                    val p = dxfToLocal(v.x.toDouble(), v.y.toDouble())
                    if (idx == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                canvas.drawPath(path, paint)
            }
        }
    }

    private fun drawDxfEntityPath(canvas: Canvas, entity: DxfEntity, paint: Paint) {
        drawSingleDxfEntity(canvas, entity, paint, dxfData?.blocks ?: emptyMap())
    }

    // ── DXF 點選 / 捕捉 ──────────────────────────
    fun screenToWorld(screenX: Float, screenY: Float): Pair<Double, Double> {
        val lx = (screenX - translateX) / scaleFactor
        val ly = (screenY - translateY) / (-scaleFactor)
        return (lx.toDouble() + minE) to (ly.toDouble() + minN)
    }

    private fun hitTestDxf(sx: Float, sy: Float): DxfEntity? {
        val dxf = dxfData ?: return null
        val (we, wn) = screenToWorld(sx, sy)
        val tol = 40.0 / scaleFactor // 增加容許誤差至 40px
        var bestDist = tol; var best: DxfEntity? = null

        fun checkEntities(entities: List<DxfEntity>, currentTransform: DxfTransform) {
            entities.forEach { entity ->
                if (!visibleLayers.contains(entity.layer)) return@forEach
                
                if (entity is DxfEntity.Insert) {
                    val blockEntities = dxf.blocks[entity.blockName] ?: return@forEach
                    // 組合變換：先應用 Insert 的平移、縮放、旋轉，再應用目前的變換
                    // 這裡簡化處理，僅考慮 Insert 的平移，後續可擴充完整的 Matrix 運算
                    val (ie, inN) = currentTransform.toWorld(entity.x, entity.y)
                    val blockTransform = DxfTransform(
                        ie, inN, 
                        currentTransform.scale * entity.scaleX, // 簡化：假設 X,Y 縮放一致
                        currentTransform.rotationRad + Math.toRadians(entity.rotation)
                    )
                    checkEntities(blockEntities, blockTransform)
                } else {
                    val d = distToEntity(entity, we, wn, currentTransform)
                    if (d < bestDist) { bestDist = d; best = entity }
                }
            }
        }

        checkEntities(dxf.entities, dxfTransform)
        return best
    }

    private fun distToEntity(entity: DxfEntity, we: Double, wn: Double, transform: DxfTransform): Double = when (entity) {
        is DxfEntity.Line -> {
            val (e1, n1) = transform.toWorld(entity.x1, entity.y1)
            val (e2, n2) = transform.toWorld(entity.x2, entity.y2)
            ptSegDist(we, wn, e1, n1, e2, n2)
        }
        is DxfEntity.Polyline -> {
            var minDist = entity.vertices.zipWithNext().minOfOrNull { (v1, v2) ->
                val (e1, n1) = transform.toWorld(v1.x.toDouble(), v1.y.toDouble())
                val (e2, n2) = transform.toWorld(v2.x.toDouble(), v2.y.toDouble())
                ptSegDist(we, wn, e1, n1, e2, n2)
            } ?: Double.MAX_VALUE
            
            if (entity.closed && entity.vertices.size > 2) {
                val v1 = entity.vertices.last()
                val v2 = entity.vertices.first()
                val (e1, n1) = transform.toWorld(v1.x.toDouble(), v1.y.toDouble())
                val (e2, n2) = transform.toWorld(v2.x.toDouble(), v2.y.toDouble())
                val d = ptSegDist(we, wn, e1, n1, e2, n2)
                if (d < minDist) minDist = d
            }
            minDist
        }
        is DxfEntity.Circle -> {
            val (ce, cn) = transform.toWorld(entity.cx, entity.cy)
            val r = entity.radius * transform.scale
            abs(sqrt((we - ce) * (we - ce) + (wn - cn) * (wn - cn)) - r)
        }
        is DxfEntity.Arc -> {
            val (ce, cn) = transform.toWorld(entity.cx, entity.cy)
            val r = entity.radius * transform.scale
            val distToCirc = abs(sqrt((we - ce) * (we - ce) + (wn - cn) * (wn - cn)) - r)
            if (distToCirc > 40.0 / scaleFactor) {
                distToCirc
            } else {
                val (dxCoord, dyCoord) = transform.inverse(we, wn)
                var angle = Math.toDegrees(atan2(dyCoord - entity.cy, dxCoord - entity.cx))
                if (angle < 0) angle += 360.0
                var end = entity.endDeg; if (end <= entity.startDeg) end += 360.0
                var a = angle; if (a < entity.startDeg) a += 360.0
                if (a <= end) distToCirc else distToCirc + 100.0
            }
        }
        is DxfEntity.Point -> {
            val (pe, pn) = transform.toWorld(entity.x, entity.y)
            sqrt((we - pe) * (we - pe) + (wn - pn) * (wn - pn))
        }
        is DxfEntity.Text -> {
            val (te, tn) = transform.toWorld(entity.x, entity.y)
            sqrt((we - te) * (we - te) + (wn - tn) * (wn - tn))
        }
        is DxfEntity.Ellipse -> {
            val (ce, cn) = transform.toWorld(entity.cx, entity.cy)
            sqrt((we - ce) * (we - ce) + (wn - cn) * (wn - cn))
        }
        is DxfEntity.Spline -> {
            entity.controlPoints.minOfOrNull { v ->
                val (ve, vn) = transform.toWorld(v.x.toDouble(), v.y.toDouble())
                sqrt((we - ve) * (we - ve) + (wn - vn) * (wn - vn))
            } ?: Double.MAX_VALUE
        }
        is DxfEntity.Dimension -> {
            val (e1, n1) = transform.toWorld(entity.x1, entity.y1)
            val (e2, n2) = transform.toWorld(entity.x2, entity.y2)
            ptSegDist(we, wn, e1, n1, e2, n2)
        }
        is DxfEntity.Hatch -> {
            entity.loops.flatten().minOfOrNull { v ->
                val (ve, vn) = transform.toWorld(v.x.toDouble(), v.y.toDouble())
                sqrt((we - ve) * (we - ve) + (wn - vn) * (wn - vn))
            } ?: Double.MAX_VALUE
        }
        is DxfEntity.Leader -> {
            entity.vertices.zipWithNext().minOfOrNull { (v1, v2) ->
                val (e1, n1) = transform.toWorld(v1.x.toDouble(), v1.y.toDouble())
                val (e2, n2) = transform.toWorld(v2.x.toDouble(), v2.y.toDouble())
                ptSegDist(we, wn, e1, n1, e2, n2)
            } ?: Double.MAX_VALUE
        }
        else -> Double.MAX_VALUE
    }

    private fun ptSegDist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val abx = bx - ax; val aby = by - ay
        val len2 = abx * abx + aby * aby
        if (len2 < 1e-12) return sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay))
        val t = ((px - ax) * abx + (py - ay) * aby) / len2
        val cx = ax + t.coerceIn(0.0, 1.0) * abx; val cy = ay + t.coerceIn(0.0, 1.0) * aby
        return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
    }

    // 優先命中既有量測點，fallback 到 DXF 捕捉點
    private fun findNearestAnyPoint(sx: Float, sy: Float): DxfSnapPoint? {
        // 1. 先嘗試尋找測量點 (Survey Points)
        val nearSurvey = findClickedPoint(sx, sy)?.let { pt ->
            com.survey.totalstationbt.model.DxfSnapPoint(
                worldE = pt.easting ?: 0.0,
                worldN = pt.northing ?: 0.0,
                dxfX = 0.0,
                dxfY = 0.0,
                type = com.survey.totalstationbt.model.SnapType.ENDPOINT,
                label = "測量點:${pt.pointName}"
            )
        }
        
        // 2. 尋找 DXF 捕捉點或量測點
        val nearSnap = findNearestMeasureOrSnap(sx, sy)
        
        // 如果兩者都有，由於 findClickedPoint 的容許誤差較大 (80px)，
        // 而 findNearestMeasureOrSnap 較小 (40px)，我們優先回傳捕捉點以利精確點選。
        return nearSnap ?: nearSurvey
    }

    private fun findNearestMeasureOrSnap(sx: Float, sy: Float): DxfSnapPoint? {
        val (we, wn) = screenToWorld(sx, sy)
        val tol = 40.0 / scaleFactor
        val nearMeasure = measurePoints.minByOrNull { s ->
            sqrt((we - s.worldE) * (we - s.worldE) + (wn - s.worldN) * (wn - s.worldN))
        }?.takeIf { s ->
            sqrt((we - s.worldE) * (we - s.worldE) + (wn - s.worldN) * (wn - s.worldN)) < tol
        }
        return nearMeasure ?: findNearestSnapPoint(sx, sy)
    }

    private fun findNearestSnapPoint(sx: Float, sy: Float): DxfSnapPoint? {
        val dxf = dxfData ?: return null
        val (we, wn) = screenToWorld(sx, sy)
        val tol = 40.0 / scaleFactor
        var bestDist = tol; var best: DxfSnapPoint? = null

        fun tryAdd(snapE: Double, snapN: Double, dxX: Double, dxY: Double, type: SnapType, lbl: String) {
            val d = sqrt((we - snapE) * (we - snapE) + (wn - snapN) * (wn - snapN))
            if (d < bestDist) { bestDist = d; best = DxfSnapPoint(snapE, snapN, dxX, dxY, type, lbl) }
        }

        fun checkSnapPoints(entities: List<DxfEntity>, currentTransform: DxfTransform) {
            entities.forEach { entity ->
                if (!visibleLayers.contains(entity.layer)) return@forEach

                when (entity) {
                    is DxfEntity.Insert -> {
                        val blockEntities = dxf.blocks[entity.blockName] ?: return@forEach
                        val ie = entity.x; val inN = entity.y
                        // 簡化 Insert 變換處理：暫不考慮旋轉縮放對 Snap 的完整矩陣運算，僅位移
                        val (weI, wnI) = currentTransform.toWorld(ie, inN)
                        val blockTransform = DxfTransform(weI, wnI, currentTransform.scale * entity.scaleX, currentTransform.rotationRad + Math.toRadians(entity.rotation))
                        checkSnapPoints(blockEntities, blockTransform)
                    }
                    is DxfEntity.Line -> {
                        val (e1, n1) = currentTransform.toWorld(entity.x1, entity.y1)
                        val (e2, n2) = currentTransform.toWorld(entity.x2, entity.y2)
                        tryAdd(e1, n1, entity.x1, entity.y1, SnapType.ENDPOINT, "端點")
                        tryAdd(e2, n2, entity.x2, entity.y2, SnapType.ENDPOINT, "端點")
                        val (em, nm) = currentTransform.toWorld((entity.x1 + entity.x2) / 2, (entity.y1 + entity.y2) / 2)
                        tryAdd(em, nm, (entity.x1 + entity.x2) / 2, (entity.y1 + entity.y2) / 2, SnapType.MIDPOINT, "中點")
                    }
                    is DxfEntity.Polyline -> {
                        entity.vertices.forEach { v ->
                            val (ve, vn) = currentTransform.toWorld(v.x.toDouble(), v.y.toDouble())
                            tryAdd(ve, vn, v.x.toDouble(), v.y.toDouble(), SnapType.ENDPOINT, "頂點")
                        }
                        entity.vertices.zipWithNext().forEach { (v1, v2) ->
                            val mx = (v1.x + v2.x) / 2.0; val my = (v1.y + v2.y) / 2.0
                            val (em, nm) = currentTransform.toWorld(mx, my)
                            tryAdd(em, nm, mx, my, SnapType.MIDPOINT, "中點")
                        }
                        if (entity.closed && entity.vertices.size > 2) {
                            val mx = (entity.vertices.last().x + entity.vertices.first().x) / 2.0
                            val my = (entity.vertices.last().y + entity.vertices.first().y) / 2.0
                            val (em, nm) = currentTransform.toWorld(mx, my)
                            tryAdd(em, nm, mx, my, SnapType.MIDPOINT, "中點")
                        }
                    }
                    is DxfEntity.Circle -> {
                        val (ce, cn) = currentTransform.toWorld(entity.cx, entity.cy)
                        tryAdd(ce, cn, entity.cx, entity.cy, SnapType.CENTER, "圓心")
                    }
                    is DxfEntity.Arc -> {
                        val (ce, cn) = currentTransform.toWorld(entity.cx, entity.cy)
                        tryAdd(ce, cn, entity.cx, entity.cy, SnapType.CENTER, "圓心")
                        val sRad = Math.toRadians(entity.startDeg); val eRad = Math.toRadians(entity.endDeg)
                        val sx1 = entity.cx + entity.radius * cos(sRad); val sy1 = entity.cy + entity.radius * sin(sRad)
                        val ex1 = entity.cx + entity.radius * cos(eRad); val ey1 = entity.cy + entity.radius * sin(eRad)
                        val (se, sn) = currentTransform.toWorld(sx1, sy1); val (ee, en) = currentTransform.toWorld(ex1, ey1)
                        tryAdd(se, sn, sx1, sy1, SnapType.ENDPOINT, "弧端點")
                        tryAdd(ee, en, ex1, ey1, SnapType.ENDPOINT, "弧端點")
                    }
                    is DxfEntity.Point -> {
                        val (pe, pn) = currentTransform.toWorld(entity.x, entity.y)
                        tryAdd(pe, pn, entity.x, entity.y, SnapType.ENDPOINT, "點")
                    }
                    is DxfEntity.Text -> {
                        val (te, tn) = currentTransform.toWorld(entity.x, entity.y)
                        tryAdd(te, tn, entity.x, entity.y, SnapType.ENDPOINT, "文字插入點")
                    }
                    is DxfEntity.Ellipse -> {
                        val (ce, cn) = currentTransform.toWorld(entity.cx, entity.cy)
                        tryAdd(ce, cn, entity.cx, entity.cy, SnapType.CENTER, "橢圓中心")
                        val (me, mn) = currentTransform.toWorld(entity.cx + entity.mx, entity.cy + entity.my)
                        tryAdd(me, mn, entity.cx + entity.mx, entity.cy + entity.my, SnapType.ENDPOINT, "橢圓長軸端點")
                    }
                    is DxfEntity.Spline -> {
                        entity.controlPoints.forEach { v ->
                            val (ve, vn) = currentTransform.toWorld(v.x.toDouble(), v.y.toDouble())
                            tryAdd(ve, vn, v.x.toDouble(), v.y.toDouble(), SnapType.ENDPOINT, "控制點")
                        }
                    }
                    is DxfEntity.Dimension -> {
                        val (e1, n1) = currentTransform.toWorld(entity.x1, entity.y1)
                        val (e2, n2) = currentTransform.toWorld(entity.x2, entity.y2)
                        tryAdd(e1, n1, entity.x1, entity.y1, SnapType.ENDPOINT, "標註端點")
                        tryAdd(e2, n2, entity.x2, entity.y2, SnapType.ENDPOINT, "標註端點")
                    }
                    is DxfEntity.Hatch -> {
                        entity.loops.flatten().forEach { v ->
                            val (ve, vn) = currentTransform.toWorld(v.x.toDouble(), v.y.toDouble())
                            tryAdd(ve, vn, v.x.toDouble(), v.y.toDouble(), SnapType.ENDPOINT, "邊界點")
                        }
                    }
                    is DxfEntity.Leader -> {
                        entity.vertices.forEach { v ->
                            val (ve, vn) = currentTransform.toWorld(v.x.toDouble(), v.y.toDouble())
                            tryAdd(ve, vn, v.x.toDouble(), v.y.toDouble(), SnapType.ENDPOINT, "引線點")
                        }
                    }
                    else -> {}
                }
            }
        }

        checkSnapPoints(dxf.entities, dxfTransform)

        // 直線交點（僅檢查鄰近且可見直線）
        val visibleLineEntities = mutableListOf<Pair<DxfEntity.Line, DxfTransform>>()
        fun collectVisibleLines(entities: List<DxfEntity>, currentTransform: DxfTransform) {
            entities.forEach { entity ->
                if (!visibleLayers.contains(entity.layer)) return@forEach
                if (entity is DxfEntity.Line) {
                    visibleLineEntities.add(entity to currentTransform)
                } else if (entity is DxfEntity.Insert) {
                    val blockEntities = dxf.blocks[entity.blockName] ?: return@forEach
                    val (weI, wnI) = currentTransform.toWorld(entity.x, entity.y)
                    val blockTransform = DxfTransform(weI, wnI, currentTransform.scale * entity.scaleX, currentTransform.rotationRad + Math.toRadians(entity.rotation))
                    collectVisibleLines(blockEntities, blockTransform)
                }
            }
        }
        collectVisibleLines(dxf.entities, dxfTransform)
        
        val nearLines = visibleLineEntities.filter { (line, transform) ->
            val (e1, n1) = transform.toWorld(line.x1, line.y1)
            val (e2, n2) = transform.toWorld(line.x2, line.y2)
            ptSegDist(we, wn, e1, n1, e2, n2) < tol * 3
        }.take(30)

        for (i in nearLines.indices) {
            for (j in i + 1 until nearLines.size) {
                val (l1, t1) = nearLines[i]; val (l2, t2) = nearLines[j]
                val (p1a, p1b) = t1.toWorld(l1.x1, l1.y1) to t1.toWorld(l1.x2, l1.y2)
                val (p2a, p2b) = t2.toWorld(l2.x1, l2.y1) to t2.toWorld(l2.x2, l2.y2)
                computeIntersectWorld(p1a, p1b, p2a, p2b)?.let { (ie, ine) ->
                    // 這裡 dxX, dxY 無法精確對應單一 DXF 空間，設為 0
                    tryAdd(ie, ine, 0.0, 0.0, SnapType.INTERSECTION, "交點")
                }
            }
        }
        return best
    }

    private fun computeIntersectWorld(p1a: Pair<Double, Double>, p1b: Pair<Double, Double>, 
                                      p2a: Pair<Double, Double>, p2b: Pair<Double, Double>): Pair<Double, Double>? {
        val x1 = p1a.first; val y1 = p1a.second; val x2 = p1b.first; val y2 = p1b.second
        val x3 = p2a.first; val y3 = p2a.second; val x4 = p2b.first; val y4 = p2b.second
        val d = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
        if (abs(d) < 1e-12) return null
        val px = ((x1 * y2 - y1 * x2) * (x3 - x4) - (x1 - x2) * (x3 * y4 - y3 * x4)) / d
        val py = ((x1 * y2 - y1 * x2) * (y3 - y4) - (y1 - y2) * (x3 * y4 - y3 * x4)) / d
        // 檢查是否在線段內
        if (px < min(x1, x2) - 1e-6 || px > max(x1, x2) + 1e-6 || px < min(x3, x4) - 1e-6 || px > max(x3, x4) + 1e-6) return null
        if (py < min(y1, y2) - 1e-6 || py > max(y1, y2) + 1e-6 || py < min(y3, y4) - 1e-6 || py > max(y3, y4) + 1e-6) return null
        return px to py
    }

    fun clearDxfHighlight() { highlightedEntity = null; highlightedSnapPt = null; invalidate() }

    fun setSelectedSnapPoints(snaps: List<DxfSnapPoint>) {
        selectedSnapPoints.clear()
        selectedSnapPoints.addAll(snaps)
        invalidate()
    }

    // ── 量測點位公開 API ──────────────────────────
    fun addMeasurePoint(snap: DxfSnapPoint) { measurePoints.add(snap); invalidate() }
    fun removeLastMeasurePoint() { if (measurePoints.isNotEmpty()) { measurePoints.removeAt(measurePoints.lastIndex); invalidate() } }
    fun clearMeasurePoints() { measurePoints.clear(); invalidate() }
    fun getMeasurePoints(): List<DxfSnapPoint> = measurePoints.toList()

    // ── 量測疊加繪圖 ─────────────────────────────
    private fun drawMeasureOverlay(canvas: Canvas) {
        val pts = measurePoints
        if (pts.isEmpty()) return

        val markerR = 8f / scaleFactor
        measureLinePaint.strokeWidth    = 2f / scaleFactor
        measureMarkerStrokePaint.strokeWidth = 1.5f / scaleFactor

        // 多邊形填色（3點以上）
        if (pts.size >= 3) {
            val fillPath = Path()
            pts.forEachIndexed { i, s ->
                val p = worldToLocal(s.worldE, s.worldN)
                if (i == 0) fillPath.moveTo(p.x, p.y) else fillPath.lineTo(p.x, p.y)
            }
            fillPath.close()
            canvas.drawPath(fillPath, measureFillPaint)
        }

        // 連接線 + 距離標籤
        for (i in 1 until pts.size) {
            val s1 = pts[i - 1]; val s2 = pts[i]
            val p1 = worldToLocal(s1.worldE, s1.worldN)
            val p2 = worldToLocal(s2.worldE, s2.worldN)
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, measureLinePaint)

            // 距離標籤（mid-point）
            val dist = sqrt((s2.worldE - s1.worldE) * (s2.worldE - s1.worldE) +
                            (s2.worldN - s1.worldN) * (s2.worldN - s1.worldN))
            val label = if (dist >= 1.0) String.format("%.3f m", dist)
                        else String.format("%.1f mm", dist * 1000.0)
            val mx = (p1.x + p2.x) / 2f; val my = (p1.y + p2.y) / 2f
            canvas.save()
            canvas.translate(mx, my)
            canvas.scale(1f / scaleFactor, -1f / scaleFactor)
            canvas.drawText(label, 0f, -6f, measureLabelPaint)
            canvas.restore()
        }

        // 點位標記 + 序號
        pts.forEachIndexed { i, snap ->
            val p = worldToLocal(snap.worldE, snap.worldN)
            canvas.drawCircle(p.x, p.y, markerR, measureMarkerFillPaint)
            canvas.drawCircle(p.x, p.y, markerR, measureMarkerStrokePaint)
            canvas.save()
            canvas.translate(p.x, p.y)
            canvas.scale(1f / scaleFactor, -1f / scaleFactor)
            canvas.drawText("${i + 1}", 0f, measureTextPaint.textSize * 0.35f, measureTextPaint)
            canvas.restore()
        }
    }

    /** 繪製捕捉點的高亮標記 */
    private fun drawSnapHighlight(canvas: Canvas) {
        // 繪製目前單選點
        highlightedSnapPt?.let { snap ->
            drawSingleSnapMarker(canvas, snap, dxfSnapPaint, true)
        }
        
        // 繪製所有已加入批次選取的點
        selectedSnapPoints.forEach { snap ->
            drawSingleSnapMarker(canvas, snap, dxfSnapPaint, false)
        }
    }

    private fun drawSingleSnapMarker(canvas: Canvas, snap: DxfSnapPoint, paint: Paint, isHighlight: Boolean) {
        val p = worldToLocal(snap.worldE, snap.worldN)
        val r = if (isHighlight) 18f / scaleFactor else 15f / scaleFactor
        paint.strokeWidth = (if (isHighlight) 4f else 2f) / scaleFactor
        
        canvas.drawCircle(p.x, p.y, r, paint)
        canvas.drawLine(p.x - r * 1.5f, p.y, p.x + r * 1.5f, p.y, paint)
        canvas.drawLine(p.x, p.y - r * 1.5f, p.x, p.y + r * 1.5f, paint)
        
        // 如果是批次選取點，標註序號或標籤
        if (!isHighlight) {
            canvas.save()
            canvas.translate(p.x, p.y)
            canvas.scale(1f / scaleFactor, -1f / scaleFactor)
            canvas.drawText(snap.label.take(2), 0f, -r * scaleFactor - 5f, textPaint)
            canvas.restore()
        }
    }

    // ── 相對位置疊加繪圖 ─────────────────────────
    private fun drawRelativeOverlay(canvas: Canvas) {
        val a = relativePointA ?: return
        val r = 10f / scaleFactor
        relativeLinePaint.strokeWidth = 2f / scaleFactor
        relativeLinePaint.pathEffect  = DashPathEffect(floatArrayOf(8f / scaleFactor, 5f / scaleFactor), 0f)

        val pa = worldToLocal(a.worldE, a.worldN)
        // 點 A 標記（紫色，帶 "A" 文字）
        canvas.drawCircle(pa.x, pa.y, r, relativeMarkerPaint)
        canvas.save(); canvas.translate(pa.x, pa.y)
        canvas.scale(1f / scaleFactor, -1f / scaleFactor)
        canvas.drawText("A", 0f, measureTextPaint.textSize * 0.35f, measureTextPaint)
        canvas.restore()

        relativePointB?.let { b ->
            val pb = worldToLocal(b.worldE, b.worldN)
            // 繪製 A-B 連線
            canvas.drawLine(pa.x, pa.y, pb.x, pb.y, relativeLinePaint)
            
            // 點 B 標記
            canvas.drawCircle(pb.x, pb.y, r, relativeMarkerPaint)
            canvas.save(); canvas.translate(pb.x, pb.y)
            canvas.scale(1f / scaleFactor, -1f / scaleFactor)
            canvas.drawText("B", 0f, measureTextPaint.textSize * 0.35f, measureTextPaint)
            canvas.restore()
        }
    }

    // ── 軌跡回放 ─────────────────────────────
    private fun drawPlaybackTrail(canvas: Canvas) {
        val visiblePoints = points.filter { visiblePointIds.contains(it.id) }
        if (visiblePoints.size < 2) return
        
        val totalSegments = visiblePoints.size - 1
        val showCount = (totalSegments * playbackProgress).toInt().coerceAtLeast(1)
        
        playbackTrailPaint.strokeWidth = 4f / scaleFactor
        val ptRadius = 4f / scaleFactor
        
        for (i in 0 until showCount) {
            val p1 = visiblePoints[i]
            val p2 = visiblePoints[i + 1]
            
            val e1 = ((p1.easting ?: 0.0) - minE).toFloat()
            val n1 = ((p1.northing ?: 0.0) - minN).toFloat()
            val e2 = ((p2.easting ?: 0.0) - minE).toFloat()
            val n2 = ((p2.northing ?: 0.0) - minN).toFloat()
            
            // 漸層淡出效果 (較舊的軌跡較透明)
            val ageFactor = (i.toFloat() / showCount)
            val alpha = (100 + 155 * ageFactor).toInt()
            playbackTrailPaint.alpha = alpha
            playbackPointPaint.alpha = alpha
            
            canvas.drawLine(e1, n1, e2, n2, playbackTrailPaint)
            canvas.drawCircle(e1, n1, ptRadius, playbackPointPaint)
            
            // 在最後一點畫大一點的標記
            if (i == showCount - 1) {
                playbackPointPaint.alpha = 255
                canvas.drawCircle(e2, n2, ptRadius * 1.5f, playbackPointPaint)
            }
            
            // 繪製前進方向箭頭
            drawDirectionArrow(canvas, e1, n1, e2, n2, alpha)
        }
    }

    private fun drawDirectionArrow(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, alpha: Int) {
        val dx = x2 - x1
        val dy = y2 - y1
        val len = sqrt(dx * dx + dy * dy)
        val arrowSize = 12f / scaleFactor
        
        // 線段夠長才畫箭頭，避免過度擁擠
        if (len > arrowSize * 4) {
            val midX = x1 + dx * 0.6f // 稍微偏向終點
            val midY = y1 + dy * 0.6f
            val angle = atan2(dy, dx)
            
            val path = Path()
            path.moveTo(midX, midY)
            path.lineTo(
                midX - arrowSize * cos(angle - 0.5f).toFloat(),
                midY - arrowSize * sin(angle - 0.5f).toFloat()
            )
            path.lineTo(
                midX - arrowSize * cos(angle + 0.5f).toFloat(),
                midY - arrowSize * sin(angle + 0.5f).toFloat()
            )
            path.close()
            
            val oldStyle = symbolPaint.style
            val oldColor = symbolPaint.color
            val oldAlpha = symbolPaint.alpha
            
            symbolPaint.style = Paint.Style.FILL
            symbolPaint.color = playbackTrailPaint.color
            symbolPaint.alpha = alpha
            canvas.drawPath(path, symbolPaint)
            
            symbolPaint.style = oldStyle
            symbolPaint.color = oldColor
            symbolPaint.alpha = oldAlpha
        }
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

        // 單獨處理不在 points 清單中的 3D 放樣目標
        currentStakeoutTarget?.let { target ->
            if (target.id != 0L && visiblePointIds.contains(target.id)) return@let
            val proj = proj3D(target)
            
            stakeoutPaint.strokeWidth = strokeWidth
            val radius = pointRadius * 1.6f
            drawPointSymbol(canvas, proj.x, proj.y, target.code, stakeoutPaint, radius)
            
            stakeoutRingPaint.strokeWidth = 2f / scaleFactor
            canvas.drawCircle(proj.x, proj.y, radius * 2.2f, stakeoutRingPaint)

            canvas.save()
            canvas.translate(proj.x, proj.y)
            canvas.scale(1f / scaleFactor, 1f / scaleFactor)
            canvas.drawText(target.pointName, 15f, -15f, textPaint)
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
    private fun drawBaselineAnchor(canvas: Canvas, anchor: BaselineAnchor, label: String) {
        val p = worldToLocal(anchor.easting, anchor.northing)
        val r = 10f / scaleFactor
        symbolPaint.color = Color.rgb(255, 165, 0)
        canvas.drawCircle(p.x, p.y, r, symbolPaint)
        canvas.save()
        canvas.translate(p.x, p.y)
        canvas.scale(1f / scaleFactor, -1f / scaleFactor)
        canvas.drawText(label, 0f, -r * scaleFactor - 5f, textPaint)
        canvas.restore()
    }

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
        val b1 = baselineA1
        val b2 = baselineA2
        if (b1 != null && b2 != null) {
            if (pt.pointName == b1.name && pt.easting == b1.easting && pt.northing == b1.northing) return
            if (pt.pointName == b2.name && pt.easting == b2.easting && pt.northing == b2.northing) return
            SurveyMathUtils.calculateBaselineOffset(b1, b2, pt)?.let { res ->
                val oldColor = textPaint.color; val oldSize = textPaint.textSize
                textPaint.color = Color.YELLOW
                textPaint.textSize = 28f  // constant screen px — already in scaled canvas space
                canvas.drawText(
                    String.format(java.util.Locale.US, " S:%.3f  O:%+.3f", res.station, res.offset),
                    15f, 55f, textPaint
                )
                textPaint.color = oldColor; textPaint.textSize = oldSize
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
