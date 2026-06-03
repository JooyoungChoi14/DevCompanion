package com.devcompanion.logging

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Foreground session logger for DevCompanion.
 *
 * Logs all agent/LLM events to an in-memory JSONL buffer.
 * Flushes to disk on [flush] (called from onPause/onStop or explicit export).
 *
 * Design goals:
 * - Zero interference with agent loop or streaming (all writes are append-only)
 * - Structured events for later analysis
 * - Minimal overhead: in-memory buffer, async disk flush
 * - Exportable from Settings screen
 */
object SessionLog {

    private const val TAG = "SessionLog"
    private const val LOG_DIR = "session_logs"
    private const val MAX_BUFFER_SIZE = 5000 // events

    private val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .create()

    private val buffer = mutableListOf<LogEvent>()
    private var currentSessionId: String = ""
    private var sessionStartTime: Long = 0L

    private var flushJob: Job? = null
    private var appContext: Context? = null

    /** Start a new session. Called when the app starts or user explicitly begins. */
    fun startSession(sessionId: String? = null) {
        flushToMemory() // keep in-memory for immediate export
        currentSessionId = sessionId ?: UUID.randomUUID().toString()
        sessionStartTime = System.currentTimeMillis()
        buffer.clear()
        log(EventType.SESSION_START, mapOf(
            "sessionId" to currentSessionId,
            "startTime" to sessionStartTime.toString()
        ))
    }

    /** Log a single event. Thread-safe via synchronized buffer. */
    fun log(type: EventType, data: Map<String, String> = emptyMap()) {
        synchronized(buffer) {
            if (buffer.size >= MAX_BUFFER_SIZE) {
                buffer.removeAt(0) // drop oldest
            }
            buffer.add(LogEvent(
                timestamp = System.currentTimeMillis(),
                sessionId = currentSessionId,
                type = type,
                data = data
            ))
        }
    }

    // ── Convenience methods for common event types ──────────────────────

    fun llmRequest(provider: String, model: String, messageCount: Int, hasTools: Boolean,
                    systemPromptLength: Int? = null, toolNames: String? = null) {
        log(EventType.LLM_REQUEST, mapOf(
            "provider" to provider,
            "model" to model,
            "messageCount" to messageCount.toString(),
            "hasTools" to hasTools.toString(),
            "systemPromptLength" to (systemPromptLength?.toString() ?: ""),
            "toolNames" to (toolNames ?: "")
        ))
    }

    fun llmResponse(provider: String, hasToolCalls: Boolean, inputTokens: Int?, outputTokens: Int?, latencyMs: Long?,
                     model: String? = null, iteration: Int? = null) {
        log(EventType.LLM_RESPONSE, mapOf(
            "provider" to provider,
            "model" to (model ?: ""),
            "hasToolCalls" to hasToolCalls.toString(),
            "inputTokens" to (inputTokens?.toString() ?: ""),
            "outputTokens" to (outputTokens?.toString() ?: ""),
            "latencyMs" to (latencyMs?.toString() ?: ""),
            "iteration" to (iteration?.toString() ?: "")
        ))
    }

    fun llmError(provider: String, model: String, error: String, code: Int? = null, iteration: Int? = null) {
        log(EventType.LLM_ERROR, mapOf(
            "provider" to provider,
            "model" to model,
            "error" to error,
            "code" to (code?.toString() ?: ""),
            "iteration" to (iteration?.toString() ?: "")
        ))
    }

    fun toolCall(name: String, arguments: String, callId: String) {
        log(EventType.TOOL_CALL, mapOf(
            "toolName" to name,
            "callId" to callId,
            "arguments" to arguments.take(500) // truncate large args
        ))
    }

    fun toolResult(callId: String, output: String, isError: Boolean) {
        log(EventType.TOOL_RESULT, mapOf(
            "callId" to callId,
            "output" to output.take(2000), // truncate large results
            "isError" to isError.toString()
        ))
    }

    fun confirmation(callId: String, toolName: String, action: String, riskLevel: String, approved: Boolean) {
        log(EventType.CONFIRMATION, mapOf(
            "callId" to callId,
            "toolName" to toolName,
            "action" to action,
            "riskLevel" to riskLevel,
            "approved" to approved.toString()
        ))
    }

    fun stateChange(from: String, to: String, iteration: Int? = null) {
        log(EventType.STATE_CHANGE, mapOf(
            "from" to from,
            "to" to to,
            "iteration" to (iteration?.toString() ?: "")
        ))
    }

    fun capture(mode: String, url: String?, hasScreenshot: Boolean, screenshotStripped: Boolean = false) {
        log(EventType.CAPTURE, mapOf(
            "mode" to mode,
            "url" to (url ?: ""),
            "hasScreenshot" to hasScreenshot.toString(),
            "screenshotStripped" to screenshotStripped.toString()
        ))
    }

    fun urlChange(from: String, to: String, trigger: String) {
        log(EventType.URL_CHANGE, mapOf(
            "from" to from,
            "to" to to,
            "trigger" to trigger
        ))
    }

    fun providerChange(from: String, to: String, model: String) {
        log(EventType.PROVIDER_CHANGE, mapOf(
            "from" to from,
            "to" to to,
            "model" to model
        ))
    }

    fun streamEvent(event: String, detail: String = "") {
        log(EventType.STREAM, mapOf(
            "event" to event,
            "detail" to detail
        ))
    }

    // ── Persistence ─────────────────────────────────────────────────────

    /**
     * Flush the in-memory buffer to a JSONL file.
     * Call from onPause/onStop or when exporting.
     * Safe to call multiple times — appends new events only.
     */
    suspend fun flush(context: Context) = withContext(Dispatchers.IO) {
        val events: List<LogEvent>
        synchronized(buffer) {
            events = buffer.toList()
        }
        if (events.isEmpty()) return@withContext

        val logDir = File(context.filesDir, LOG_DIR)
        if (!logDir.exists()) logDir.mkdirs()

        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(sessionStartTime))
        val logFile = File(logDir, "$dateStr-$currentSessionId.jsonl")

        try {
            logFile.appendText(events.joinToString("\n", postfix = "\n") { gson.toJson(it) })
            // Clear flushed events from buffer
            synchronized(buffer) {
                buffer.removeAll(events.toSet())
            }
            Log.d(TAG, "Flushed ${events.size} events to ${logFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to flush session log", e)
        }
    }

    /** Keep in-memory only — for immediate export without disk write. */
    private fun flushToMemory() {
        // No-op: buffer is already in memory
    }

    /**
     * Export current session log as a single JSONL string.
     * Used for Settings "Export" button via share sheet.
     */
    fun exportAsString(): String {
        val events: List<LogEvent>
        synchronized(buffer) {
            events = buffer.toList()
        }
        return events.joinToString("\n") { gson.toJson(it) }
    }

    /**
     * Export full session log history (all flushed files + current buffer).
     */
    fun exportFullHistory(context: Context): String {
        val sb = StringBuilder()
        // Read flushed files
        val logDir = File(context.filesDir, LOG_DIR)
        if (logDir.exists()) {
            logDir.listFiles()?.sortedBy { it.name }?.forEach { file ->
                try {
                    sb.append(file.readText())
                } catch (_: Exception) {}
            }
        }
        // Append current buffer
        sb.append(exportAsString())
        return sb.toString()
    }

    /**
     * Save session log to Downloads folder via MediaStore.
     * Returns the content URI on success, null on failure.
     */
    fun saveToDownloads(context: Context): Uri? {
        val content = exportFullHistory(context)
        if (content.isBlank()) return null

        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(sessionStartTime))
        val fileName = "devcompanion-log-$dateStr-$currentSessionId.jsonl"

        return try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/jsonl")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            }

            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: return null

            context.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(content.toByteArray(Charsets.UTF_8))
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)
            }

            Log.d(TAG, "Saved log to Downloads: $fileName")
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save log to Downloads", e)
            null
        }
    }

    /** List all saved session log files. */
    fun listLogFiles(context: Context): List<File> {
        val logDir = File(context.filesDir, LOG_DIR)
        if (!logDir.exists()) return emptyList()
        return logDir.listFiles()?.sortedByDescending { it.lastModified() }?.toList() ?: emptyList()
    }

    /** Delete all saved session logs. Returns number of files deleted. */
    fun clearLogs(context: Context): Int {
        val logDir = File(context.filesDir, LOG_DIR)
        if (!logDir.exists()) return 0
        var count = 0
        logDir.listFiles()?.forEach { if (it.delete()) count++ }
        return count
    }

    /** Set app context for auto-flush. Call once in Application.onCreate(). */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Start periodic auto-flush (every 30 seconds). Call from Activity.onCreate(). */
    fun startAutoFlush(scope: kotlinx.coroutines.CoroutineScope) {
        flushJob?.cancel()
        flushJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(30_000L)
                appContext?.let { flush(it) }
            }
        }
    }

    /** Stop periodic auto-flush. Call from Activity.onDestroy(). */
    fun stopAutoFlush() {
        flushJob?.cancel()
        flushJob = null
    }

    /** Current buffer size (for display). */
    fun bufferSize(): Int = synchronized(buffer) { buffer.size }
}

// ── Data classes ────────────────────────────────────────────────────────

enum class EventType(val key: String) {
    SESSION_START("session_start"),
    SESSION_END("session_end"),
    AGENT_START("agent_start"),
    AGENT_END("agent_end"),
    LLM_REQUEST("llm_request"),
    LLM_RESPONSE("llm_response"),
    LLM_ERROR("llm_error"),
    TOOL_CALL("tool_call"),
    TOOL_RESULT("tool_result"),
    CONFIRMATION("confirmation"),
    STATE_CHANGE("state_change"),
    CAPTURE("capture"),
    URL_CHANGE("url_change"),
    PROVIDER_CHANGE("provider_change"),
    STREAM("stream"),
    AGENT_ANALYSIS("agent_analysis"),
    WEBVIEW_CRASH("webview_crash"),
    WEBVIEW_RECOVER("webview_recover"),
    NETWORK_ERROR("network_error"),
    ANR_DETECTED("anr_detected"),
    ERROR("error")
}

data class LogEvent(
    val timestamp: Long,
    val sessionId: String,
    val type: EventType,
    val data: Map<String, String> = emptyMap()
)