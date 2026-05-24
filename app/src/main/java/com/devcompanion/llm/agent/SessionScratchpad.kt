package com.devcompanion.llm.agent

import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * In-memory scratchpad that stores full tool results during an agent session.
 *
 * Lifecycle: tied to a single agent loop invocation.
 * Created when [AgentLoop.start] is called, discarded when the loop completes or is stopped.
 * A new conversation (newConversation) creates a fresh scratchpad.
 *
 * Purpose:
 * - Preserve full tool outputs that are truncated when sent to the LLM
 * - Allow the LLM to recall earlier results via the `recall` tool
 * - Provide context framing for every tool result sent to the LLM
 *
 * Memory safety:
 * - Maximum [MAX_ENTRIES] entries per session (oldest evicted)
 * - Maximum [MAX_ENTRY_SIZE] bytes per entry (oversized entries are truncated)
 * - Total memory bounded by ~MAX_ENTRIES * MAX_ENTRY_SIZE
 */
class SessionScratchpad {

    companion object {
        private const val TAG = "Scratchpad"
        /** Maximum number of entries. Oldest entries are evicted when exceeded. */
        private const val MAX_ENTRIES = 50
        /** Maximum size of a single entry's rawOutput in bytes. */
        private const val MAX_ENTRY_SIZE = 100_000 // ~100KB
        /** Maximum length of userIntent summary. */
        private const val MAX_INTENT_LENGTH = 200
    }

    data class Entry(
        val index: Int,
        val toolName: String,
        val timestamp: Long,
        val selector: String?,
        val rawOutput: String,
        val truncated: Boolean,
        val errorType: String?,
        val userIntent: String
    )

    private val _entries = ConcurrentLinkedQueue<Entry>()
    private val _counter = java.util.concurrent.atomic.AtomicInteger(0)
    private var _userIntent: String = ""

    val entries: List<Entry> get() = _entries.toList()
    val userIntent: String get() = _userIntent

    /** Set the user intent for this session (extracted from first user message). */
    fun setUserIntent(intent: String) {
        _userIntent = intent.take(MAX_INTENT_LENGTH)
    }

    /**
     * Store a tool result in the scratchpad.
     * Returns the stored entry (may be truncated if oversized).
     */
    fun store(
        toolName: String,
        selector: String?,
        rawOutput: String,
        truncated: Boolean,
        errorType: String?
    ): Entry {
        // Evict oldest if at capacity
        while (_entries.size >= MAX_ENTRIES) {
            _entries.poll()
        }

        val index = _counter.getAndIncrement()
        val safeOutput = if (rawOutput.length > MAX_ENTRY_SIZE) {
            rawOutput.take(MAX_ENTRY_SIZE) + "\n[SCRATCHPAD: entry truncated at ${MAX_ENTRY_SIZE} chars]"
        } else {
            rawOutput
        }

        val entry = Entry(
            index = index,
            toolName = toolName,
            timestamp = System.currentTimeMillis(),
            selector = selector,
            rawOutput = safeOutput,
            truncated = truncated || rawOutput.length > MAX_ENTRY_SIZE,
            errorType = errorType,
            userIntent = _userIntent
        )

        _entries.add(entry)
        Log.d(TAG, "Stored entry #$index: $toolName${if (selector != null) " selector=$selector" else ""}${if (truncated) " (truncated)" else ""}")
        return entry
    }

    /** Retrieve entries by tool name. */
    fun getByTool(toolName: String): List<Entry> =
        _entries.filter { it.toolName == toolName }

    /** Retrieve a specific entry by index. */
    fun getByIndex(index: Int): Entry? =
        _entries.find { it.index == index }

    /** Search entries for text containing the query. */
    fun search(query: String, limit: Int = 5): List<Entry> {
        val lowerQuery = query.lowercase()
        return _entries.filter { it.rawOutput.lowercase().contains(lowerQuery) }
            .take(limit)
    }

    /** Get a summary of all stored entries (for recall tool). */
    fun summary(): String {
        if (_entries.isEmpty()) return "No tool results stored in this session."
        return buildString {
            appendLine("Session scratchpad: ${_entries.size} entries, intent: \"${_userIntent}\"")
            appendLine("---")
            _entries.forEach { entry ->
                val preview = entry.rawOutput.take(80).replace("\n", " ")
                appendLine("#${entry.index} ${entry.toolName}" +
                    "${if (entry.selector != null) " selector=${entry.selector}" else ""}" +
                    "${if (entry.truncated) " [TRUNCATED]" else ""}" +
                    "${if (entry.errorType != null) " [ERROR: ${entry.errorType}]" else ""}" +
                    " → ${preview}...")
            }
        }
    }

    /** Clear all entries (called when a new conversation starts). */
    fun clear() {
        _entries.clear()
        _counter.set(0)
        _userIntent = ""
    }

    /** Approximate total size of all stored entries. */
    fun totalSizeBytes(): Int = _entries.sumOf { it.rawOutput.length * 2 } // UTF-16 approx
}