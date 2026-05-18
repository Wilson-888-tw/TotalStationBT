package com.survey.totalstationbt.model



/**
 * 測量點資料模型
 */
data class SurveyPoint(
    val id: Long = System.currentTimeMillis(),
    val pointName: String = "",           // 點號
    val easting: Double? = null,          // E（橫坐標）
    val northing: Double? = null,         // N（縱坐標）
    val elevation: Double? = null,        // Z（高程）
    val code: String = "",                // 特徵編碼
    val horizontalAngle: Double? = null,  // HA
    val verticalAngle: Double? = null,    // VA
    val slopeDistance: Double? = null,    // SD
    val horizontalDistance: Double? = null,
    val verticalDistance: Double? = null,
    val rawData: String = "",
    val format: DataFormat = DataFormat.UNKNOWN,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toCSVRow(): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return listOf(
            pointName,
            easting?.toString() ?: "",
            northing?.toString() ?: "",
            elevation?.toString() ?: "",
            code,
            fmt.format(java.util.Date(timestamp))
        ).joinToString(",")
    }

    companion object {
        val CSV_HEADER = "點號,E(橫坐標),N(縱坐標),Z(高程),編碼,時間\n"
    }
}

enum class DataFormat {
    GSI8,       // Leica GSI-8
    GSI16,      // Leica GSI-16
    SDR33,      // Sokkia SDR33
    NIKON_RAW,  // Nikon RAW
    TOPCON,     // Topcon GTS
    SOKKIA,     // Sokkia general
    NMEA,       // NMEA-like
    CUSTOM,     // 自訂
    UNKNOWN
}

/**
 * 藍牙連線狀態
 */
sealed class ConnectionState {
    object Disconnected : ConnectionState()
    data class Connecting(val deviceName: String) : ConnectionState()
    data class Connected(val deviceName: String, val deviceAddress: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/**
 * 接收到的藍牙訊息
 */
sealed class BluetoothMessage {
    data class DataReceived(val raw: String, val point: SurveyPoint?) : BluetoothMessage()
    data class ConnectionChanged(val state: ConnectionState) : BluetoothMessage()
    data class Error(val message: String) : BluetoothMessage()
}

/**
 * 藍牙配對裝置資訊
 */
data class BluetoothDeviceInfo(
    val name: String,
    val address: String,
    val isPaired: Boolean,
    val rssi: Int = -1
)
