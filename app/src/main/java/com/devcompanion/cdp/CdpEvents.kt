package com.devcompanion.cdp

import com.google.gson.annotations.SerializedName

// ── Console ──────────────────────────────────────────────────────────────

enum class ConsoleLevel { Log, Warn, Error, Info, Debug }

data class ConsoleEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: ConsoleLevel = ConsoleLevel.Log,
    val text: String = "",
    val url: String? = null,
    val line: Int? = null,
    val column: Int? = null,
)

// ── Network ──────────────────────────────────────────────────────────────

data class NetworkRequest(
    val requestId: String,
    val method: String,
    val url: String,
    val timestamp: Long = System.currentTimeMillis(),
    val type: String = "",
    val headers: Map<String, String> = emptyMap(),
    val postData: String? = null,
)

data class NetworkResponse(
    val requestId: String,
    val status: Int = 0,
    val statusText: String = "",
    val mimeType: String = "",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val encodedBodyLength: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
)

data class NetworkLoadFailure(
    val requestId: String,
    val reason: String = "",
    val canceled: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
)

data class NetworkEntry(
    val request: NetworkRequest,
    val response: NetworkResponse? = null,
    val failure: NetworkLoadFailure? = null,
    val completedAt: Long? = null,
) {
    val durationMs: Long?
        get() = completedAt?.let { it - request.timestamp }

    val statusDisplay: String
        get() = when {
            failure != null -> "ERR"
            response != null -> response.status.toString()
            else -> "…"
        }
}

// ── Performance ───────────────────────────────────────────────────────────

data class PerformanceMetric(
    val timestamp: Long = System.currentTimeMillis(),
    val fps: Float = 0f,
    val jsHeapUsed: Float = 0f,
    val jsHeapTotal: Float = 0f,
    val nodes: Float = 0f,
    val documents: Float = 0f,
    val jsEventListeners: Float = 0f,
)

// ── CDP wire ──────────────────────────────────────────────────────────────

data class CdpEvent(
    val method: String,
    val params: Map<String, Any?>,
)

data class CdpCommand(
    val id: Int,
    val method: String,
    val params: Map<String, Any?> = emptyMap(),
)

data class CdpResponse(
    val id: Int,
    val result: Map<String, Any?>?,
    val error: CdpError?,
)

data class CdpError(
    val code: Int,
    val message: String,
)

// ── JS Evaluation ─────────────────────────────────────────────────────────

data class EvaluateResult(
    val success: Boolean,
    val value: String,
    val type: String,
)

// ── Discovery ─────────────────────────────────────────────────────────────

data class CdpTarget(
    val id: String,
    @SerializedName("webSocketDebuggerUrl") val wsUrl: String,
    val title: String = "",
    val url: String = "",
    val type: String = "",
)