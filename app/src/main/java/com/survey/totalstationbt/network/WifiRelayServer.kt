package com.survey.totalstationbt.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

class WifiRelayServer(val port: Int = DEFAULT_PORT) {

    sealed class State {
        object Stopped : State()
        data class Running(val ip: String, val port: Int, val clientCount: Int) : State()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private val clients = mutableListOf<PrintWriter>()
    private val lock = Any()

    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state.asStateFlow()

    fun start() {
        if (_state.value is State.Running) return
        scope.launch {
            try {
                val ss = ServerSocket(port)
                serverSocket = ss
                _state.value = State.Running(getLocalIpAddress(), port, 0)
                while (isActive) {
                    val client = try { ss.accept() } catch (e: IOException) { break }
                    handleClient(client)
                }
            } catch (e: Exception) {
                _state.value = State.Stopped
            }
        }
    }

    private fun handleClient(socket: Socket) {
        scope.launch {
            val writer = PrintWriter(socket.getOutputStream(), true)
            synchronized(lock) {
                clients.add(writer)
                updateCount()
            }
            try {
                // Block until client disconnects (read loop)
                val reader = socket.getInputStream().bufferedReader()
                while (isActive) {
                    withContext(Dispatchers.IO) {
                        try { reader.readLine() } catch (e: IOException) { null }
                    } ?: break
                }
            } finally {
                synchronized(lock) {
                    clients.remove(writer)
                    updateCount()
                }
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    fun broadcast(rawLine: String) {
        synchronized(lock) {
            val dead = mutableListOf<PrintWriter>()
            for (w in clients) {
                try {
                    w.println(rawLine)
                    if (w.checkError()) dead.add(w)
                } catch (e: Exception) {
                    dead.add(w)
                }
            }
            if (dead.isNotEmpty()) {
                clients.removeAll(dead.toSet())
                updateCount()
            }
        }
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        synchronized(lock) { clients.clear() }
        _state.value = State.Stopped
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    private fun updateCount() {
        val s = _state.value
        if (s is State.Running) _state.value = s.copy(clientCount = clients.size)
    }

    companion object {
        const val DEFAULT_PORT = 7890

        fun getLocalIpAddress(): String {
            try {
                NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                    if (!ni.isLoopback && ni.isUp) {
                        ni.inetAddresses?.toList()?.forEach { addr ->
                            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                                addr.hostAddress?.let { return it }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
            return "192.168.43.1"
        }
    }
}
