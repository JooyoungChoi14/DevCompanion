package com.devcompanion.debug

import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over browser debugger implementations.
 *
 * GeckoView: NoOpDebugger (DevTools not yet supported in GeckoView)
 *
 * BridgeServer, ConsoleTab, NetworkTab, PerformanceTab all depend on this interface.
 */
interface BrowserDebugger {

    /** Recent console log items. */
    val consoleItems: StateFlow<List<ConsoleItem>>

    /** Network request/response entries keyed by request ID. */
    val networkEntries: StateFlow<Map<String, NetworkEntry>>

    /** Performance metrics samples. */
    val performanceMetrics: StateFlow<List<PerformanceMetric>>

    /** URL history (most recent first). */
    val urlHistory: StateFlow<List<String>>

    /** Whether inspector mode is currently active. */
    val inspectorEnabled: Boolean

    /** Inspector target element (null when inspector is off). */
    val inspectorTarget: StateFlow<InspectorTarget?>

    // ── Console ──

    /** Add a console log entry. */
    fun addConsoleLog(level: ConsoleLevel, text: String, source: String? = null, line: Int? = null)

    /** Clear all console items. */
    fun clearConsole()

    // ── Network ──

    /** Clear all network entries. */
    fun clearNetwork()

    /** Track an HTTP error (4xx/5xx from server). */
    fun trackHttpError(url: String, statusCode: Int, reasonPhrase: String)

    // ── URL History ──

    /** Add a URL to the history (most recent first). */
    fun addUrlToHistory(url: String)

    /** Restore URL history from persistent storage. */
    fun restoreUrlHistory(urls: List<String>)

    // ── Performance ──

    /** Collect performance data from the browser engine. */
    fun collectPerformanceData()

    /** Emit a performance metric sample. */
    fun emitMetric(metric: PerformanceMetric)

    // ── Inspector ──

    /** Enable inspector mode. */
    fun enableInspector()

    /** Disable inspector mode. */
    fun disableInspector()

    // ── JS Evaluation (for console input) ──

    /** Evaluate a JS expression and add result to the console timeline. */
    fun evaluateJs(expression: String)

    // ── Lifecycle ──

    /** Mark page load start for performance tracking. */
    fun markPageStart()
}