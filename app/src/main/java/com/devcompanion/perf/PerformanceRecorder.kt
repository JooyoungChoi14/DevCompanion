package com.devcompanion.perf

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.devcompanion.cdp.CdpClient
import com.devcompanion.cdp.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

class PerformanceRecorder(
    private val cdpClient: CdpClient,
    private val scope: CoroutineScope,
    private val context: Context,
) {
    companion object {
        private const val TAG = "PerformanceRecorder"
        private const val TRACING_CATEGORIES =
            "blink,devtools.timeline,disabled-by-default-devtools.timeline," +
            "disabled-by-default-devtools.timeline.frame," +
            "v8.execute,disabled-by-default-devtools.timeline.stack"

        private const val METRICS_POLL_INTERVAL_MS = 1_000L
    }

    private val gson = Gson()
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    // Collected trace events during a recording session
    private val traceEvents = mutableListOf<String>()
    private var metricsPollJob: Job? = null

    // Real-time metrics for UI
    private val _latestMetrics = MutableStateFlow(PerformanceMetric())
    val latestMetrics: StateFlow<PerformanceMetric> = _latestMetrics.asStateFlow()

    private val _metricsHistory = MutableSharedFlow<PerformanceMetric>(extraBufferCapacity = 300)
    val metricsHistory: SharedFlow<PerformanceMetric> = _metricsHistory.asSharedFlow()

    // Listen to raw CDP events for trace data
    private var rawEventJob: Job? = null

    init {
        // Collect performance metrics from CDP events
        scope.launch {
            cdpClient.performanceMetrics.collect { metric ->
                _latestMetrics.value = metric
                _metricsHistory.emit(metric)
            }
        }
    }

    // ── Metrics polling ──────────────────────────────────────────────────

    fun startMetricsPolling() {
        if (metricsPollJob?.isActive == true) return
        metricsPollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (cdpClient.connected.value) {
                    cdpClient.sendCommand("Performance.getMetrics")
                }
                delay(METRICS_POLL_INTERVAL_MS)
            }
        }
    }

    fun stopMetricsPolling() {
        metricsPollJob?.cancel()
        metricsPollJob = null
    }

    // ── Tracing ───────────────────────────────────────────────────────────

    fun startRecording() {
        if (_recording.value) return
        traceEvents.clear()
        _recording.value = true

        // Subscribe to raw CDP events for trace data collection
        rawEventJob = scope.launch {
            cdpClient.rawEvents.collect { event ->
                if (_recording.value) {
                    traceEvents.add(gson.toJson(event))
                }
            }
        }

        scope.launch(Dispatchers.IO) {
            cdpClient.sendCommand("Tracing.start", mapOf(
                "traceConfig" to mapOf(
                    "includedCategories" to TRACING_CATEGORIES.split(","),
                    "excludedCategories" to listOf("*"),
                )
            ))
            Log.i(TAG, "Tracing started")
        }
    }

    fun stopRecording(): File? {
        if (!_recording.value) return null
        _recording.value = false
        rawEventJob?.cancel()
        rawEventJob = null

        // Stop tracing synchronously
        scope.launch(Dispatchers.IO) {
            cdpClient.sendCommand("Tracing.end")
        }

        // Build trace JSON in DevTools format
        val traceJson = buildTraceFile()
        val file = saveTraceFile(traceJson)
        Log.i(TAG, "Trace saved: ${file?.absolutePath}")
        return file
    }

    private fun buildTraceFile(): String {
        val eventsArray = traceEvents.joinToString(",", prefix = "[", postfix = "]")
        // DevTools trace format
        val wrapper = """{"traceEvents":$eventsArray}"""
        return wrapper
    }

    private fun saveTraceFile(json: String): File? {
        return try {
            val dir = File(context.cacheDir, "traces").also { it.mkdirs() }
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(System.currentTimeMillis())
            val file = File(dir, "devcompanion_trace_$timestamp.json")
            file.writeText(json)
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save trace file", e)
            null
        }
    }

    fun shareTraceFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share trace file"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share trace file", e)
            // Fallback: show toast with error
            try {
                Toast.makeText(context, "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }
    }
}