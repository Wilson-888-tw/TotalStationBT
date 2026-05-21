package com.survey.totalstationbt.network

import com.survey.totalstationbt.bluetooth.BluetoothSerialService
import com.survey.totalstationbt.model.ConnectionState
import com.survey.totalstationbt.parser.TotalStationParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

class WifiRelayClient {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: Socket? = null
    private var readJob: Job? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _receivedLines = MutableStateFlow<List<BluetoothSerialService.ReceivedLine>>(emptyList())
    val receivedLines: StateFlow<List<BluetoothSerialService.ReceivedLine>> = _receivedLines.asStateFlow()

    fun connect(ip: String, port: Int) {
        readJob?.cancel()
        scope.launch {
            _connectionState.value = ConnectionState.Connecting("WiFi")
            try {
                val s = Socket()
                s.connect(InetSocketAddress(ip, port), 5000)
                socket = s
                _connectionState.value = ConnectionState.Connected("WiFi：$ip", ip)
                startReading(s)
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.Error("WiFi 連線失敗：${e.message}")
            }
        }
    }

    private fun startReading(s: Socket) {
        readJob = scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
                while (isActive && s.isConnected) {
                    val line = withContext(Dispatchers.IO) {
                        try { reader.readLine() } catch (e: IOException) { null }
                    } ?: break
                    if (line.isBlank()) continue
                    val point = TotalStationParser.parse(line)
                    val received = BluetoothSerialService.ReceivedLine(raw = line, parsed = point)
                    val current = _receivedLines.value.toMutableList()
                    current.add(received)
                    if (current.size > 2000) current.removeAt(0)
                    _receivedLines.value = current
                }
            } catch (e: Exception) {
                // ignored
            } finally {
                if (_connectionState.value !is ConnectionState.Disconnected) {
                    _connectionState.value = ConnectionState.Error("WiFi 連線已中斷")
                }
                try { s.close() } catch (_: Exception) {}
            }
        }
    }

    fun disconnect() {
        readJob?.cancel()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        _connectionState.value = ConnectionState.Disconnected
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }
}
