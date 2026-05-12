package com.devcompanion.cdp

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class CdpClient(
    private val scope: CoroutineScope,
    private val gson: Gson = Gson(),
) {
    companion object {
        private const val TAG = "CdpClient"
        private const val DEFAULT_HOST = "localhost"
        private const val DEFAULT_PORT = "9222"
    }

    var discoveryHost: String = DEFAULT_HOST
    var discoveryPort: String = DEFAULT_PORT
    private val discoveryUrl: String get() = "http://$discoveryHost:$discoveryPort/json"

    // ── State ────────────────────────────────────────────────────────────

    private val idCounter = AtomicInteger(0)
    private var webSocket: WebSocket? = null
    private val pendingCommands = mutableMapOf<Int, CompletableDeferred<CdpResponse>>()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    // ── Event flows ──────────────────────────────────────────────────────

    private val _consoleEntries = MutableSharedFlow<ConsoleEntry>(extraBufferCapacity = 64)
    val consoleEntries: SharedFlow<ConsoleEntry> = _consoleEntries.asSharedFlow()

    private val _networkRequests = MutableSharedFlow<NetworkRequest>(extraBufferCapacity = 64)
    val networkRequests: SharedFlow<NetworkRequest> = _networkRequests.asSharedFlow()

    private val _networkResponses = MutableSharedFlow<NetworkResponse>(extraBufferCapacity = 64)
    val networkResponses: SharedFlow<NetworkResponse> = _networkResponses.asSharedFlow()

    private val _networkFailures = MutableSharedFlow<NetworkLoadFailure>(extraBufferCapacity = 32)
    val networkFailures: SharedFlow<NetworkLoadFailure> = _networkFailures.asSharedFlow()

    private val _performanceMetrics = MutableSharedFlow<PerformanceMetric>(extraBufferCapacity = 32)
    val performanceMetrics: SharedFlow<PerformanceMetric> = _performanceMetrics.asSharedFlow()

    private val _rawEvents = MutableSharedFlow<CdpEvent>(extraBufferCapacity = 128)
    val rawEvents: SharedFlow<CdpEvent> = _rawEvents.asSharedFlow()

    // ── OkHttp ───────────────────────────────────────────────────────────

    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30_000, TimeUnit.MILLISECONDS)
        .build()

    // ── Connection ───────────────────────────────────────────────────────

    suspend fun connect(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val targets = discoverTargets()
                val target = targets.firstOrNull() ?: run {
                    Log.w(TAG, "No CDP targets found at $discoveryUrl")
                    return@withContext false
                }
                connectTo(target.wsUrl)
                true
            } catch (e: Exception) {
                Log.e(TAG, "CDP connection failed", e)
                _connected.value = false
                false
            }
        }
    }

    private fun discoverTargets(): List<CdpTarget> {
        return try {
            val request = Request.Builder().url(discoveryUrl).build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return emptyList()
            val type = object : TypeToken<List<CdpTarget>>() {}.type
            gson.fromJson(body, type) ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "CDP discovery failed: ${e.message}")
            emptyList()
        }
    }

    private fun connectTo(wsUrl: String) {
        try {
            val request = Request.Builder().url(wsUrl).build()
            val ws = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "CDP WebSocket connected")
                _connected.value = true
                scope.launch { enableDomains() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    handleMessage(text)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling CDP message", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "CDP WebSocket closed: $code $reason")
                _connected.value = false
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "CDP WebSocket failure", t)
                _connected.value = false
                scope.launch { reconnectWithBackoff() }
            }
        })
        this.webSocket = ws
        } catch (e: Exception) {
            Log.e(TAG, "WebSocket creation failed", e)
        }
    }

    private suspend fun reconnectWithBackoff() {
        var backoffDelay = 1000L
        repeat(10) {
            delay(backoffDelay)
            try {
                if (connect()) return
            } catch (e: Exception) {
                Log.w(TAG, "Reconnect attempt failed", e)
            }
            backoffDelay = (backoffDelay * 2).coerceAtMost(30_000)
        }
    }

    // ── Domain enabling ──────────────────────────────────────────────────

    private suspend fun enableDomains() {
        sendCommand("Runtime.enable")
        sendCommand("Network.enable")
        sendCommand("Performance.enable")
    }

    // ── Send commands ────────────────────────────────────────────────────

    suspend fun sendCommand(method: String, params: Map<String, Any?> = emptyMap()): CdpResponse? {
        val id = idCounter.incrementAndGet()
        val cmd = CdpCommand(id, method, params)
        val json = gson.toJson(cmd)
        val deferred = CompletableDeferred<CdpResponse>()
        pendingCommands[id] = deferred

        val ws = webSocket
        if (ws == null || !_connected.value) {
            pendingCommands.remove(id)
            return null
        }

        withContext(Dispatchers.IO) {
            ws.send(json)
        }

        return try {
            withTimeout(10_000) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            pendingCommands.remove(id)
            Log.w(TAG, "Command timeout: $method")
            null
        }
    }

    // ── Message handling ─────────────────────────────────────────────────

    private fun handleMessage(text: String) {
        val json = try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse CDP message: ${text.take(100)}")
            return
        }

        if (json.has("id")) {
            // Response to a command
            val id = json.get("id").asInt
            val result = if (json.has("result")) {
                gson.fromJson(json.get("result"), object : TypeToken<Map<String, Any?>>() {}.type)
            } else null
            val error = if (json.has("error")) {
                gson.fromJson(json.get("error"), CdpError::class.java)
            } else null
            val response = CdpResponse(id, result, error)
            pendingCommands.remove(id)?.complete(response)
            return
        }

        if (!json.has("method")) return
        val method = json.get("method").asString
        val params = if (json.has("params")) {
            gson.fromJson<Map<String, Any?>>(
                json.get("params"),
                object : TypeToken<Map<String, Any?>>() {}.type
            ) ?: emptyMap()
        } else emptyMap()

        val event = CdpEvent(method, params)
        scope.launch { _rawEvents.emit(event) }

        when (method) {
            "Runtime.consoleAPICalled" -> handleConsoleApi(params)
            "Runtime.exceptionThrown" -> handleException(params)
            "Network.requestWillBeSent" -> handleRequestWillBeSent(params)
            "Network.responseReceived" -> handleResponseReceived(params)
            "Network.loadingFailed" -> handleLoadingFailed(params)
            "Performance.metrics" -> handlePerformanceMetrics(params)
            "Tracing.tracingComplete" -> { /* handled by PerformanceRecorder via rawEvents */ }
        }
    }

    // ── Console event handlers ───────────────────────────────────────────

    private fun handleConsoleApi(params: Map<String, Any?>) {
        val levelStr = params["type"] as? String ?: "log"
        val level = when (levelStr.lowercase()) {
            "warning" -> ConsoleLevel.Warn
            "error" -> ConsoleLevel.Error
            "info" -> ConsoleLevel.Info
            "debug" -> ConsoleLevel.Debug
            else -> ConsoleLevel.Log
        }
        @Suppress("UNCHECKED_CAST")
        val args = params["args"] as? List<Map<String, Any?>> ?: return
        val text = args.joinToString(" ") { arg ->
            arg["value"]?.toString() ?: arg["description"]?.toString() ?: ""
        }
        val entry = ConsoleEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            text = text,
        )
        scope.launch { _consoleEntries.emit(entry) }
    }

    private fun handleException(params: Map<String, Any?>) {
        @Suppress("UNCHECKED_CAST")
        val detail = params["exceptionDetails"] as? Map<String, Any?>
        val text = detail?.let {
            val exc = it["exception"] as? Map<String, Any?>
            exc?.get("description")?.toString() ?: it["text"]?.toString() ?: "Exception"
        } ?: "Exception"
        val entry = ConsoleEntry(
            timestamp = System.currentTimeMillis(),
            level = ConsoleLevel.Error,
            text = text,
        )
        scope.launch { _consoleEntries.emit(entry) }
    }

    // ── Network event handlers ───────────────────────────────────────────

    private fun handleRequestWillBeSent(params: Map<String, Any?>) {
        val requestId = params["requestId"] as? String ?: return
        @Suppress("UNCHECKED_CAST")
        val request = params["request"] as? Map<String, Any?> ?: return
        val method = request["method"] as? String ?: "GET"
        val url = request["url"] as? String ?: ""
        val type = params["type"] as? String ?: ""
        @Suppress("UNCHECKED_CAST")
        val headers = request["headers"] as? Map<String, String> ?: emptyMap()
        val postData = request["postData"] as? String
        val entry = NetworkRequest(
            requestId = requestId,
            method = method,
            url = url,
            timestamp = System.currentTimeMillis(),
            type = type,
            headers = headers,
            postData = postData,
        )
        scope.launch { _networkRequests.emit(entry) }
    }

    private fun handleResponseReceived(params: Map<String, Any?>) {
        val requestId = params["requestId"] as? String ?: return
        @Suppress("UNCHECKED_CAST")
        val response = params["response"] as? Map<String, Any?> ?: return
        val status = (response["status"] as? Double)?.toInt() ?: 0
        val statusText = response["statusText"] as? String ?: ""
        val mimeType = response["mimeType"] as? String ?: ""
        @Suppress("UNCHECKED_CAST")
        val headers = response["headers"] as? Map<String, String> ?: emptyMap()
        val entry = NetworkResponse(
            requestId = requestId,
            status = status,
            statusText = statusText,
            mimeType = mimeType,
            headers = headers,
            timestamp = System.currentTimeMillis(),
        )
        scope.launch { _networkResponses.emit(entry) }
    }

    private fun handleLoadingFailed(params: Map<String, Any?>) {
        val requestId = params["requestId"] as? String ?: return
        val reason = params["blockedReason"] as? String
            ?: params["canceled"]?.let { if (it == true) "Canceled" else null }
            ?: params["errorText"] as? String
            ?: "Failed"
        val canceled = params["canceled"] as? Boolean ?: false
        val entry = NetworkLoadFailure(
            requestId = requestId,
            reason = reason,
            canceled = canceled,
            timestamp = System.currentTimeMillis(),
        )
        scope.launch { _networkFailures.emit(entry) }
    }

    // ── Performance event handler ─────────────────────────────────────────

    private fun handlePerformanceMetrics(params: Map<String, Any?>) {
        @Suppress("UNCHECKED_CAST")
        val metricsList = params["metrics"] as? List<Map<String, Any?>> ?: return
        val metricsMap = metricsList.associate { (it["name"] as? String ?: "") to (it["value"] as? Double ?: 0.0) }
        val metric = PerformanceMetric(
            timestamp = System.currentTimeMillis(),
            fps = metricsMap["Frames"]?.toFloat() ?: 0f,
            jsHeapUsed = metricsMap["JSHeapUsedSize"]?.toFloat() ?: 0f,
            jsHeapTotal = metricsMap["JSHeapTotalSize"]?.toFloat() ?: 0f,
            nodes = metricsMap["Nodes"]?.toFloat() ?: 0f,
            documents = metricsMap["Documents"]?.toFloat() ?: 0f,
            jsEventListeners = metricsMap["JSEventListeners"]?.toFloat() ?: 0f,
        )
        scope.launch { _performanceMetrics.emit(metric) }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────

    fun disconnect() {
        webSocket?.close(1000, "App disconnecting")
        webSocket = null
        _connected.value = false
    }

    // ── Response body retrieval ───────────────────────────────────────────

    suspend fun getResponseBody(requestId: String): String? {
        val response = sendCommand("Network.getResponseBody", mapOf("requestId" to requestId))
        return response?.result?.get("body") as? String
    }

    // ── JS evaluation ─────────────────────────────────────────────────────

    suspend fun evaluateJs(expression: String, returnByValue: Boolean = true): EvaluateResult? {
        val response = sendCommand("Runtime.evaluate", mapOf(
            "expression" to expression,
            "returnByValue" to returnByValue,
            "awaitPromise" to false
        ))
        val result = response?.result ?: return null
        if (result.containsKey("exceptionDetails")) {
            @Suppress("UNCHECKED_CAST")
            val details = result["exceptionDetails"] as? Map<String, Any?>
            val exception = details?.get("exception") as? Map<String, Any?>
            val desc = exception?.get("description") as? String
                ?: details?.get("text") as? String
                ?: "Unknown error"
            return EvaluateResult(success = false, value = desc, type = "error")
        }
        val value = result["result"]
        @Suppress("UNCHECKED_CAST")
        val resultMap = value as? Map<String, Any?>
        return EvaluateResult(
            success = true,
            value = resultMap?.get("value")?.toString() ?: resultMap?.get("description")?.toString() ?: "undefined",
            type = resultMap?.get("type") as? String ?: "undefined"
        )
    }
}