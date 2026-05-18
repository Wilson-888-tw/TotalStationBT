package com.survey.totalstationbt.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.survey.totalstationbt.model.ConnectionState
import com.survey.totalstationbt.parser.TotalStationParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.UUID

/**
 * 藍牙 SPP 序列通訊服務
 * Serial Port Profile UUID (標準 SPP)
 */
@SuppressLint("MissingPermission")
class BluetoothSerialService {

    companion object {
        private const val TAG = "BT_Serial"
        // Standard SPP UUID
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var bluetoothSocket: BluetoothSocket? = null
    private var connectJob: Job? = null
    private var readJob: Job? = null

    // ── StateFlows ──────────────────────────────
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _receivedLines = MutableStateFlow<List<ReceivedLine>>(emptyList())
    val receivedLines: StateFlow<List<ReceivedLine>> = _receivedLines.asStateFlow()

    // 收到原始行 → 解析後的資料
    data class ReceivedLine(
        val raw: String,
        val parsed: com.survey.totalstationbt.model.SurveyPoint?,
        val timestamp: Long = System.currentTimeMillis()
    )

    // ── 連線 ────────────────────────────────────
    fun connect(device: BluetoothDevice) {
        connectJob?.cancel()
        connectJob = serviceScope.launch {
            val deviceName = try { device.name ?: "Unknown" } catch (e: SecurityException) { "Unknown" }
            _connectionState.value = ConnectionState.Connecting(deviceName)
            
            try {
                // 先關舊 socket
                safeCloseSocket()

                // 建議在連線前停止搜尋，可提高連線成功率
                BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()

                var socket: BluetoothSocket? = null
                
                try {
                    // 嘗試標準安全連線
                    socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                    bluetoothSocket = socket
                    withContext(Dispatchers.IO) { socket?.connect() }
                } catch (e: IOException) {
                    Log.w(TAG, "Standard connection failed, trying insecure fallback: ${e.message}")
                    safeCloseSocket()
                    
                    // 嘗試不安全連線 (部分裝置/全站儀在安全配對上有問題時可用)
                    socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                    bluetoothSocket = socket
                    withContext(Dispatchers.IO) { socket?.connect() }
                }

                if (socket == null || !socket.isConnected) {
                    throw IOException("無法建立連線")
                }

                _connectionState.value = ConnectionState.Connected(
                    deviceName = deviceName,
                    deviceAddress = device.address
                )
                Log.i(TAG, "Connected to $deviceName")

                startReading(socket)

            } catch (e: Exception) {
                Log.e(TAG, "Connection failed: ${e.message}")
                safeCloseSocket()
                _connectionState.value = ConnectionState.Error("連線失敗：${e.message}")
            }
        }
    }

    // ── 斷線 ────────────────────────────────────
    fun disconnect() {
        readJob?.cancel()
        connectJob?.cancel()
        safeCloseSocket()
        _connectionState.value = ConnectionState.Disconnected
    }

    // ── 讀取迴圈 ────────────────────────────────
    private fun startReading(socket: BluetoothSocket) {
        readJob?.cancel()
        readJob = serviceScope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                while (isActive && socket.isConnected) {
                    val line = withContext(Dispatchers.IO) {
                        try { reader.readLine() } catch (e: IOException) { null }
                    } ?: break

                    if (line.isBlank()) continue

                    val point = TotalStationParser.parse(line)
                    val received = ReceivedLine(raw = line, parsed = point)

                    // 累積最多 2000 筆，保持最新的
                    val current = _receivedLines.value.toMutableList()
                    current.add(received)
                    if (current.size > 2000) current.removeAt(0)
                    _receivedLines.value = current

                    Log.d(TAG, "RX: $line | parsed=${point != null}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Read error: ${e.message}")
            } finally {
                if (_connectionState.value !is ConnectionState.Disconnected) {
                    safeCloseSocket()
                    _connectionState.value = ConnectionState.Error("連線已中斷")
                }
            }
        }
    }

    // ── 傳送指令 ────────────────────────────────
    fun sendCommand(command: String) {
        serviceScope.launch {
            try {
                val out = bluetoothSocket?.outputStream ?: return@launch
                withContext(Dispatchers.IO) {
                    out.write((command + "\r\n").toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                Log.d(TAG, "TX: $command")
            } catch (e: IOException) {
                Log.e(TAG, "Send error: ${e.message}")
            }
        }
    }

    fun clearHistory() {
        _receivedLines.value = emptyList()
    }

    fun loadHistory(newLines: List<ReceivedLine>) {
        _receivedLines.value = newLines.takeLast(2000)
    }

    private fun safeCloseSocket() {
        try { bluetoothSocket?.close() } catch (_: Exception) {}
        bluetoothSocket = null
    }

    fun destroy() {
        disconnect()
        serviceScope.cancel()
    }
}

// ── 掃描工具 ──────────────────────────────────────
@SuppressLint("MissingPermission")
object BluetoothScanner {
    fun getPairedDevices(adapter: BluetoothAdapter): List<com.survey.totalstationbt.model.BluetoothDeviceInfo> {
        return adapter.bondedDevices
            .filter { it.name != null }
            .map {
                com.survey.totalstationbt.model.BluetoothDeviceInfo(
                    name = it.name,
                    address = it.address,
                    isPaired = true
                )
            }
            .sortedBy { it.name }
    }
}
