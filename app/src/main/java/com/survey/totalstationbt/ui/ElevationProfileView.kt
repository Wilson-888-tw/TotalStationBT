package com.survey.totalstationbt.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.survey.totalstationbt.db.PointEntity
import kotlin.math.max
import kotlin.math.min

class ElevationProfileView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var points: List<PointEntity> = emptyList()
    private val dataPoints = mutableListOf<PointF>() // X: 累積距離, Y: 高程

    private val linePaint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }
    
    private val fillPaint = Paint().apply {
        color = Color.parseColor("#4400FF00")
        style = Paint.Style.FILL
    }

    private val axisPaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 2f
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 24f
    }

    fun setProfilePoints(selectedPoints: List<PointEntity>) {
        if (selectedPoints.size < 2) return
        this.points = selectedPoints
        
        dataPoints.clear()
        var totalDist = 0.0
        
        // 第一個點
        dataPoints.add(PointF(0f, selectedPoints[0].elevation?.toFloat() ?: 0f))
        
        for (i in 0 until selectedPoints.size - 1) {
            val p1 = selectedPoints[i]
            val p2 = selectedPoints[i+1]
            
            val n1 = p1.northing ?: 0.0; val e1 = p1.easting ?: 0.0
            val n2 = p2.northing ?: 0.0; val e2 = p2.easting ?: 0.0
            
            val d = kotlin.math.sqrt(Math.pow(n2 - n1, 2.0) + Math.pow(e2 - e1, 2.0))
            totalDist += d
            dataPoints.add(PointF(totalDist.toFloat(), p2.elevation?.toFloat() ?: 0f))
        }
        
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dataPoints.isEmpty()) return

        val w = width.toFloat(); val h = height.toFloat()
        val padding = 100f
        
        val minX = 0f
        val maxX = dataPoints.last().x
        val minY = dataPoints.minOf { it.y }
        val maxY = dataPoints.maxOf { it.y }
        
        val rangeX = maxX - minX
        val rangeY = if (maxY != minY) maxY - minY else 1f
        
        val scaleX = (w - 2 * padding) / rangeX
        val scaleY = (h - 2 * padding) / rangeY

        // 轉換座標的輔助函數
        fun getCanvasX(x: Float) = padding + (x - minX) * scaleX
        fun getCanvasY(y: Float) = h - padding - (y - minY) * scaleY

        // 畫坐標軸
        canvas.drawLine(padding, h - padding, w - padding, h - padding, axisPaint) // X
        canvas.drawLine(padding, padding, padding, h - padding, axisPaint) // Y

        // 畫剖面線
        val path = Path()
        val fillPath = Path()
        fillPath.moveTo(getCanvasX(dataPoints[0].x), h - padding)
        
        dataPoints.forEachIndexed { index, pt ->
            val cx = getCanvasX(pt.x)
            val cy = getCanvasY(pt.y)
            if (index == 0) path.moveTo(cx, cy) else path.lineTo(cx, cy)
            fillPath.lineTo(cx, cy)
        }
        
        fillPath.lineTo(getCanvasX(dataPoints.last().x), h - padding)
        fillPath.close()
        
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)
        
        // 標註
        canvas.drawText("累積距離 (m)", w / 2, h - 30f, textPaint)
        canvas.drawText("高程 (H)", 20f, padding - 20f, textPaint)
        canvas.drawText(String.format("%.1f", minY), 10f, h - padding, textPaint)
        canvas.drawText(String.format("%.1f", maxY), 10f, padding, textPaint)
    }
}
