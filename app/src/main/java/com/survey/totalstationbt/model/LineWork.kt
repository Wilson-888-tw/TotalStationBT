package com.survey.totalstationbt.model

/**
 * 可編輯線段圖形（折線 / 封閉多邊形）。
 *
 * 座標一律使用現場實測世界座標（E, N），與測量點同一套系統，
 * 因此線段可以直接與點位、DXF 底圖疊合顯示。
 */
data class LineVertex(val e: Double, val n: Double)

data class LineShape(
    val id: Long,
    val name: String,
    val vertices: List<LineVertex>,
    val closed: Boolean = false
) {
    /** 線段數量：封閉圖形會多一段（末點→首點） */
    val segmentCount: Int
        get() = when {
            vertices.size < 2 -> 0
            closed -> vertices.size
            else -> vertices.size - 1
        }

    fun segStart(index: Int): LineVertex = vertices[index]

    fun segEnd(index: Int): LineVertex = vertices[(index + 1) % vertices.size]

    /** 線段 index 之前是否還有相鄰線段（封閉圖形恆為 true） */
    fun hasPrevSegment(index: Int): Boolean = closed || index > 0

    /** 線段 index 之後是否還有相鄰線段（封閉圖形恆為 true） */
    fun hasNextSegment(index: Int): Boolean = closed || index < segmentCount - 1

    fun isValidSegment(index: Int): Boolean = index in 0 until segmentCount
}

/** 旋轉支點：以線段的起點、中點或終點為軸心 */
enum class RotatePivot { START, MIDPOINT, END }

/** 線段編輯模式 */
enum class LineEditMode {
    /** 關閉編輯 */
    OFF,
    /** 只點選線段（可平移地圖） */
    SELECT,
    /** 手動旋轉線段（拖曳） */
    ROTATE,
    /** 手動平行拉伸（拖曳，垂直方向鄰線自動伸縮） */
    STRETCH,
    /** 手動搬移接點（頂點） */
    VERTEX
}
