package com.devcompanion.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okio.BufferedSource

/**
 * A single Server-Sent Event parsed from an SSE stream.
 *
 * @param name The event type (from the `event:` line), or null if only
 *             a `data:` line was present (data-only mode).
 * @param data The raw JSON payload from the `data:` line.
 */
data class SseEvent(
    val name: String?,
    val data: String
)

/**
 * Generic SSE parser that reads from an OkHttp [BufferedSource].
 *
 * Supports two formats:
 * 1. **Anthropic style** — `event:` and `data:` lines paired, separated by blank lines.
 * 2. **OpenAI style** — `data:` lines only, separated by blank lines.
 *
 * The parser emits [SseEvent] instances as they are fully read.
 * A `data: [DONE]` line signals the end of the stream and is *not* emitted.
 *
 * Provides both a **blocking** batch parser ([parse]) for compatibility
 * and a **reactive** flow parser ([parseFlow]) for real-time streaming.
 */
object SseParser {

    private const val TAG = "SseParser"
    private const val EVENT_PREFIX = "event:"
    private const val DATA_PREFIX = "data:"
    private const val DONE_SIGNAL = "[DONE]"

    // ── Reactive flow parser ─────────────────────────────────────────

    /**
     * Parse SSE events from a [BufferedSource] as a reactive [Flow].
     *
     * Each event is emitted as soon as it is fully parsed (when a blank
     * line or the next event line is encountered). This enables real-time
     * token delivery instead of waiting for the entire stream to finish.
     *
     * Runs on [Dispatchers.IO] to avoid blocking the main thread.
     *
     * @param source The buffered source to read from.
     * @return A cold [Flow] of [SseEvent] instances in stream order.
     */
    fun parseFlow(source: BufferedSource): Flow<SseEvent> = flow {
        var currentEventName: String? = null
        var currentData: StringBuilder? = null

        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break

            when {
                line.startsWith(EVENT_PREFIX) -> {
                    // Flush any pending event before starting a new one
                    flushEvent(currentEventName, currentData)?.let {
                        emit(it)
                    }
                    currentEventName = line.removePrefix(EVENT_PREFIX).trim()
                    currentData = null
                }

                line.startsWith(DATA_PREFIX) -> {
                    val dataContent = line.removePrefix(DATA_PREFIX).trim()
                    if (dataContent == DONE_SIGNAL) {
                        // Stream-end signal — flush any pending event and stop
                        flushEvent(currentEventName, currentData)?.let {
                            emit(it)
                        }
                        break
                    }
                    // Accumulate data lines (multi-line data is concatenated)
                    if (currentData == null) {
                        currentData = StringBuilder(dataContent)
                    } else {
                        currentData.append('\n').append(dataContent)
                    }
                }

                line.isBlank() -> {
                    // Blank line = event boundary — flush accumulated event
                    flushEvent(currentEventName, currentData)?.let {
                        emit(it)
                    }
                    currentEventName = null
                    currentData = null
                }

                line.startsWith(":") -> {
                    // SSE comment line — explicitly skip (per SSE spec)
                }

                // Unknown line formats are silently ignored
            }
        }

        // Flush any trailing event that wasn't terminated by a blank line
        flushEvent(currentEventName, currentData)?.let {
            emit(it)
        }
    }.flowOn(Dispatchers.IO)

    // ── Blocking batch parser (kept for backward compatibility) ────────

    /**
     * Parse all SSE events from a [BufferedSource] in one batch.
     *
     * **Warning**: This is a blocking call that reads until the source
     * is exhausted. For real-time streaming, use [parseFlow] instead.
     *
     * @return A list of parsed [SseEvent] instances in stream order.
     */
    @Deprecated("Use parseFlow() for real-time streaming; this blocks until stream end")
    fun parse(source: BufferedSource): List<SseEvent> {
        val events = mutableListOf<SseEvent>()
        var currentEventName: String? = null
        var currentData: StringBuilder? = null

        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break

            when {
                line.startsWith(EVENT_PREFIX) -> {
                    // Flush any pending event before starting a new one
                    flushEvent(currentEventName, currentData)?.let {
                        events.add(it)
                    }
                    currentEventName = line.removePrefix(EVENT_PREFIX).trim()
                    currentData = null
                }

                line.startsWith(DATA_PREFIX) -> {
                    val dataContent = line.removePrefix(DATA_PREFIX).trim()
                    if (dataContent == DONE_SIGNAL) {
                        // Stream-end signal — flush any pending event and stop
                        flushEvent(currentEventName, currentData)?.let {
                            events.add(it)
                        }
                        break
                    }
                    // Accumulate data lines (multi-line data is concatenated)
                    if (currentData == null) {
                        currentData = StringBuilder(dataContent)
                    } else {
                        currentData.append('\n').append(dataContent)
                    }
                }

                line.isBlank() -> {
                    // Blank line = event boundary — flush accumulated event
                    flushEvent(currentEventName, currentData)?.let {
                        events.add(it)
                    }
                    currentEventName = null
                    currentData = null
                }

                line.startsWith(":") -> {
                    // SSE comment line — explicitly skip (per SSE spec)
                }

                // Unknown line formats are silently ignored
            }
        }

        // Flush any trailing event that wasn't terminated by a blank line
        flushEvent(currentEventName, currentData)?.let {
            events.add(it)
        }

        return events
    }

    /**
     * Build an [SseEvent] from accumulated state, or null if there is no data.
     */
    private fun flushEvent(name: String?, data: StringBuilder?): SseEvent? {
        if (data != null && data.isNotEmpty()) {
            return SseEvent(name = name, data = data.toString())
        }
        return null
    }
}