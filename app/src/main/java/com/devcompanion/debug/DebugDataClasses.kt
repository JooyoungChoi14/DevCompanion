package com.devcompanion.debug

// ── Console ────────────────────────────────────────────────────────────

enum class ConsoleLevel { Log, Warn, Error, Info, Debug }

// ── Network ────────────────────────────────────────────────────────────

data class NetworkRequest(
    val requestId: String,
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val timestamp: Long,
)

data class NetworkResponse(
    val requestId: String,
    val url: String,
    val statusCode: Int,
    val headers: Map<String, String> = emptyMap(),
    val timestamp: Long,
    val durationMs: Long = 0,
)

data class NetworkLoadFailure(
    val requestId: String,
    val url: String,
    val errorCode: Int,
    val description: String,
    val timestamp: Long,
)

data class NetworkEntry(
    val request: NetworkRequest,
    val response: NetworkResponse? = null,
    val failure: NetworkLoadFailure? = null,
    val completedAt: Long? = null,
) {
    val durationMs: Long? get() = completedAt?.let { it - request.timestamp }
    val statusDisplay: String get() = when {
        failure != null -> "ERR"
        response != null -> response.statusCode.toString()
        else -> "…"
    }
}

// ── Performance ────────────────────────────────────────────────────────

data class PerformanceMetric(
    val timestamp: Long = System.currentTimeMillis(),
    val fps: Float = 0f,
    val jsHeapUsed: Float = 0f,
    val jsHeapTotal: Float = 0f,
    val domNodes: Int = 0,
    val loadTimeMs: Long = 0,
)

// ── JS Evaluation ──────────────────────────────────────────────────────

data class JsEvalResult(
    val success: Boolean,
    val value: String,
    val type: String = "unknown",
    val rawJson: String? = null,
) {
    val isExpandable: Boolean get() = type == "object" || type == "function"
    val displayValue: String get() = if (isExpandable) {
        value.take(80).replace(Regex("\\s+"), " ") + if (value.length > 80) "…" else ""
    } else value
}