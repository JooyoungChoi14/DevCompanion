package com.devcompanion.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * No-op debugger for GeckoView.
 * DevTools are not yet supported in GeckoView — all operations are silently ignored.
 */
class NoOpDebugger : BrowserDebugger {

    private val _consoleItems = MutableStateFlow<List<ConsoleItem>>(emptyList())
    override val consoleItems: StateFlow<List<ConsoleItem>> = _consoleItems

    private val _networkEntries = MutableStateFlow<Map<String, NetworkEntry>>(emptyMap())
    override val networkEntries: StateFlow<Map<String, NetworkEntry>> = _networkEntries

    private val _performanceMetrics = MutableStateFlow<List<PerformanceMetric>>(emptyList())
    override val performanceMetrics: StateFlow<List<PerformanceMetric>> = _performanceMetrics

    private val _urlHistory = MutableStateFlow<List<String>>(emptyList())
    override val urlHistory: StateFlow<List<String>> = _urlHistory

    override val inspectorEnabled: Boolean = false

    private val _inspectorTarget = MutableStateFlow<InspectorTarget?>(null)
    override val inspectorTarget: StateFlow<InspectorTarget?> = _inspectorTarget

    override fun addConsoleLog(level: ConsoleLevel, text: String, source: String?, line: Int?) {}
    override fun clearConsole() { _consoleItems.value = emptyList() }
    override fun clearNetwork() { _networkEntries.value = emptyMap() }
    override fun trackHttpError(url: String, statusCode: Int, reasonPhrase: String) {}
    override fun addUrlToHistory(url: String) {}
    override fun restoreUrlHistory(urls: List<String>) {}
    override fun collectPerformanceData() {}
    override fun emitMetric(metric: PerformanceMetric) {}
    override fun enableInspector() {}
    override fun disableInspector() {}
    override fun evaluateJs(expression: String) {}
    override fun markPageStart() {}
}