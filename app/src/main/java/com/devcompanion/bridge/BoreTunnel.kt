package com.devcompanion.bridge

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Pure Kotlin bore client — tunnels the local BridgeServer through bore.pub.
 *
 * Protocol (null-delimited JSON over TCP):
 * 1. Connect to bore.pub:7835
 * 2. Send {"Hello":0} (0 = request random port)
 * 3. Receive {"Hello":<port>} — public port assigned
 * 4. For each {"Connection":"<uuid>"}, open new TCP to bore.pub:7835, send {"Accept":"<uuid>"}, proxy data
 * 5. Heartbeat: {"Heartbeat"} from server — no action needed
 *
 * No external binary required. No Rust. No cargo.
 */
class BoreTunnel(
    private val localPort: Int = BridgeServer.DEFAULT_PORT,
    private val boreServer: String = "bore.pub",
    private val boreControlPort: Int = 7835,
) {
    companion object {
        private const val TAG = "BoreTunnel"
        private const val RECONNECT_DELAY_MS = 5000L
        private const val CONNECT_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 60000
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var controlSocket: Socket? = null
    private var running = false

    // Public URL once connected
    var publicUrl: String? = null
        private set
    var publicPort: Int = 0
        private set
    
    // Error state for UI
    var lastError: String? = null
        private set

    private val gson = Gson()

    // Callback for UI updates (runs on caller's context)
    var onStatusChanged: ((connected: Boolean, url: String?, error: String?) -> Unit)? = null

    /**
     * Start the tunnel — connects to bore.pub and maintains the connection.
     * Reconnects automatically on failure.
     */
    fun start() {
        if (running) {
            Log.d(TAG, "Already running")
            return
        }
        running = true
        lastError = null
        
        scope.launch {
            Log.i(TAG, "Tunnel starting...")
            while (running) {
                try {
                    connectAndListen()
                } catch (e: Exception) {
                    val errorMsg = when (e) {
                        is SocketTimeoutException -> "Connection timed out"
                        is java.net.UnknownHostException -> "DNS lookup failed for $boreServer"
                        is java.net.ConnectException -> "Cannot connect to $boreServer:$boreControlPort (port blocked?)"
                        else -> e.javaClass.simpleName + ": " + e.message
                    }
                    Log.w(TAG, "Tunnel disconnected: $errorMsg", e)
                    lastError = errorMsg
                    publicUrl = null
                    publicPort = 0
                    onStatusChanged?.invoke(false, null, errorMsg)
                    
                    if (running) {
                        Log.i(TAG, "Reconnecting in ${RECONNECT_DELAY_MS}ms...")
                        delay(RECONNECT_DELAY_MS)
                    }
                }
            }
            Log.i(TAG, "Tunnel stopped")
        }
    }

    fun stop() {
        Log.i(TAG, "Stopping tunnel...")
        running = false
        scope.cancel()
        try { controlSocket?.close() } catch (_: Exception) {}
        publicUrl = null
        publicPort = 0
        lastError = null
        onStatusChanged?.invoke(false, null, null)
    }

    @Throws(Exception::class)
    private suspend fun connectAndListen() = withContext(Dispatchers.IO) {
        Log.d(TAG, "Connecting to $boreServer:$boreControlPort...")
        
        // Create socket with explicit timeout
        val socket = Socket().apply {
            soTimeout = READ_TIMEOUT_MS
        }
        controlSocket = socket
        
        // Connect with timeout
        socket.connect(InetSocketAddress(boreServer, boreControlPort), CONNECT_TIMEOUT_MS)
        Log.d(TAG, "TCP connected to $boreServer:$boreControlPort")
        
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        // Send Hello(0) — request random port
        sendMessage(output, BoreClientMessage.Hello(0))
        Log.d(TAG, "Sent Hello(0)")

        // Read server response
        val response = readMessage(input)
        Log.d(TAG, "Received: $response")
        
        when (response) {
            is BoreServerMessage.Hello -> {
                publicPort = response.port
                publicUrl = "$boreServer:$publicPort"
                lastError = null
                Log.i(TAG, "Tunnel active → $publicUrl")
                onStatusChanged?.invoke(true, publicUrl, null)
            }
            is BoreServerMessage.Error -> {
                throw RuntimeException("Server error: ${response.message}")
            }
            is BoreServerMessage.Challenge -> {
                throw RuntimeException("Server requires authentication — not supported")
            }
            null -> {
                throw RuntimeException("Empty response from server")
            }
            else -> {
                throw RuntimeException("Unexpected response: $response")
            }
        }

        // Listen for connections and heartbeats
        while (running && isActive) {
            try {
                val msg = readMessage(input) ?: break // null = EOF
                when (msg) {
                    is BoreServerMessage.Heartbeat -> {
                        Log.v(TAG, "Heartbeat received")
                    }
                    is BoreServerMessage.Connection -> {
                        Log.d(TAG, "Incoming connection: ${msg.id}")
                        launch { handleConnection(msg.id) }
                    }
                    is BoreServerMessage.Hello -> {
                        Log.w(TAG, "Unexpected Hello during listen")
                    }
                    is BoreServerMessage.Error -> {
                        Log.e(TAG, "Server error: ${msg.message}")
                    }
                    is BoreServerMessage.Challenge -> {
                        Log.w(TAG, "Unexpected Challenge during listen")
                    }
                }
            } catch (e: SocketTimeoutException) {
                // Expected — bore sends heartbeats every ~30s, but timeout is 60s
                Log.v(TAG, "Read timeout (no heartbeat), continuing...")
            }
        }
        
        Log.w(TAG, "Listen loop exited")
    }

    private suspend fun handleConnection(connectionId: String) = withContext(Dispatchers.IO) {
        var proxySocket: Socket? = null
        var localSocket: Socket? = null
        
        try {
            // Open new TCP connection to bore server
            proxySocket = Socket().apply {
                soTimeout = CONNECT_TIMEOUT_MS
            }
            proxySocket.connect(InetSocketAddress(boreServer, boreControlPort), CONNECT_TIMEOUT_MS)
            val proxyOutput = proxySocket.getOutputStream()

            // Send Accept message
            sendMessage(proxyOutput, BoreClientMessage.Accept(connectionId))
            Log.d(TAG, "Sent Accept for $connectionId")

            // Connect to local BridgeServer
            localSocket = Socket().apply {
                soTimeout = CONNECT_TIMEOUT_MS
            }
            localSocket.connect(InetSocketAddress("127.0.0.1", localPort), CONNECT_TIMEOUT_MS)
            Log.d(TAG, "Connected to local BridgeServer on port $localPort")

            // Bidirectional proxy: remote ↔ local
            val job1 = async { 
                try {
                    proxySocket.getInputStream().copyTo(localSocket.getOutputStream())
                } catch (e: Exception) {
                    Log.d(TAG, "Proxy→Local closed: ${e.message}")
                }
            }
            val job2 = async { 
                try {
                    localSocket.getInputStream().copyTo(proxySocket.getOutputStream())
                } catch (e: Exception) {
                    Log.d(TAG, "Local→Proxy closed: ${e.message}")
                }
            }

            // Wait for either side to close
            awaitFirst(job1, job2)
            Log.d(TAG, "Connection $connectionId closed normally")
            
        } catch (e: Exception) {
            Log.w(TAG, "Connection $connectionId error: ${e.message}")
        } finally {
            runCatching { proxySocket?.close() }
            runCatching { localSocket?.close() }
        }
    }

    private suspend fun awaitFirst(job1: Deferred<*>, job2: Deferred<*>) {
        try {
            select<Unit> {
                job1.onAwait { }
                job2.onAwait { }
            }
        } catch (_: CancellationException) {}
    }

    // ── Protocol serialization ──────────────────────────────────────

    private fun sendMessage(output: OutputStream, msg: BoreClientMessage) {
        val json = when (msg) {
            is BoreClientMessage.Hello -> """{"Hello":${msg.port}}"""
            is BoreClientMessage.Accept -> """{"Accept":"${msg.id}"}"""
            is BoreClientMessage.Authenticate -> """{"Authenticate":"${msg.tag}"}"""
        }
        val bytes = (json + "\u0000").toByteArray(Charsets.UTF_8)
        output.write(bytes)
        output.flush()
        Log.v(TAG, "Sent: $json")
    }

    private fun readMessage(input: InputStream): BoreServerMessage? {
        // Read until null byte
        val buffer = mutableListOf<Byte>()
        while (true) {
            val b = input.read()
            if (b == -1) {
                Log.d(TAG, "EOF reached")
                return null // EOF
            }
            if (b == 0) break
            buffer.add(b.toByte())
            if (buffer.size > 4096) throw RuntimeException("Frame too large: ${buffer.size} bytes")
        }

        val json = buffer.toByteArray().toString(Charsets.UTF_8)
        Log.v(TAG, "Raw message: $json")
        return parseServerMessage(json)
    }

    private fun parseServerMessage(json: String): BoreServerMessage? {
        return try {
            // bore protocol uses enum-style JSON: {"Hello":12345}, {"Connection":"uuid"}, etc.
            val map: Map<String, Any> = gson.fromJson(json, object : TypeToken<Map<String, Any>>() {}.type)
            when {
                "Hello" in map -> BoreServerMessage.Hello((map["Hello"] as? Number)?.toInt() ?: 0)
                "Connection" in map -> BoreServerMessage.Connection(map["Connection"] as? String ?: "")
                "Heartbeat" in map -> BoreServerMessage.Heartbeat
                "Error" in map -> BoreServerMessage.Error(map["Error"] as? String ?: "unknown")
                "Challenge" in map -> BoreServerMessage.Challenge(map["Challenge"] as? String ?: "")
                else -> {
                    Log.w(TAG, "Unknown message type: $map")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse server message: '$json'", e)
            null
        }
    }
}

// ── Protocol message types ───────────────────────────────────────────

sealed class BoreClientMessage {
    data class Hello(val port: Int) : BoreClientMessage()
    data class Accept(val id: String) : BoreClientMessage()
    data class Authenticate(val tag: String) : BoreClientMessage()
}

sealed class BoreServerMessage {
    data class Hello(val port: Int) : BoreServerMessage()
    data class Connection(val id: String) : BoreServerMessage()
    object Heartbeat : BoreServerMessage()
    data class Error(val message: String) : BoreServerMessage()
    data class Challenge(val id: String) : BoreServerMessage()
}