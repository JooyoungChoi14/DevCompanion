package com.devcompanion.debug

import java.util.concurrent.atomic.AtomicLong

/**
 * Unified console timeline item — replaces separate entries + jsResults lists.
 * All items share a single chronological timeline with stable UIDs.
 */
sealed class ConsoleItem {
    abstract val uid: Long
    abstract val timestamp: Long

    /** JS expression submitted by the user */
    data class Input(
        override val uid: Long,
        override val timestamp: Long,
        val expression: String,
    ) : ConsoleItem()

    /** Console log from WebChromeClient.onConsoleMessage */
    data class Log(
        override val uid: Long,
        override val timestamp: Long,
        val level: ConsoleLevel,
        val text: String,
        val source: String? = null,
        val line: Int? = null,
    ) : ConsoleItem()

    /** JS evaluation result, linked to its Input via inputUid */
    data class Result(
        override val uid: Long,
        override val timestamp: Long,
        val inputUid: Long,
        val expression: String,
        val evalResult: JsEvalResult,
    ) : ConsoleItem()
}

private val consoleItemIdCounter = AtomicLong(0)

fun nextConsoleItemId(): Long = consoleItemIdCounter.incrementAndGet()