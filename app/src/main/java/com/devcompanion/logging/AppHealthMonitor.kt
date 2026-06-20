package com.devcompanion.logging

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlin.random.Random

/**
 * Monitors app health: main thread blocking, frame drops, memory pressure,
 * and input latency. Routes all events through [SessionLog].
 *
 * Design principles:
 * - All events include screen/component/trigger for causal tracing
 * - Debug builds: 10% sampling. Release builds: 1% sampling via [SessionLog.healthSamplingAllowed]
 * - Minimal overhead: Choreographer.FrameCallback for frame drops,
 *   Handler post delay for ANR-level block detection only
 *
 * v2 fixes:
 * - Removed self-referential block detector (50ms polling was causing the blocks it detected)
 * - Added sampling to health events to prevent log flooding
 * - Frame drop detector now samples every 10th frame instead of every frame
 * - Block detector now only detects ANR-level blocks (>1000ms) with 2s cooldown
 */
object AppHealthMonitor {

    private const val TAG = "AppHealthMonitor"

    // ── Thresholds ──────────────────────────────────────────────────
    /** ANR-level block threshold in ms. Only detect blocks this severe. */
    private const val ANR_BLOCK_THRESHOLD_MS = 1000L
    /** Cooldown between ANR block reports in ms. Prevents log flooding. */
    private const val ANR_BLOCK_COOLDOWN_MS = 2000L
    /** Frame drop threshold. Only log when consecutive dropped frames exceed this. */
    private const val FRAME_DROP_THRESHOLD = 5
    /** Input latency threshold in ms. Delays shorter than this are not logged. */
    private const val INPUT_LATENCY_THRESHOLD_MS = 100L
    /** Frame sampling rate: only check every Nth frame. */
    private const val FRAME_SAMPLE_INTERVAL = 10

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isInstalled = false
    private var choreographerPosted = false

    // ── Frame drop tracking ────────────────────────────────────────
    private var consecutiveDroppedFrames = 0
    private var lastFrameTimeNanos: Long = 0L
    private var frameCount = 0
    private val frameIntervalNanos: Long
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (lastFrameTimeNanos > 0L) {
                frameCount++
                // Only check every FRAME_SAMPLE_INTERVAL frames
                if (frameCount % FRAME_SAMPLE_INTERVAL == 0) {
                    val delta = frameTimeNanos - lastFrameTimeNanos
                    val expectedFrames = delta / frameIntervalNanos
                    val droppedFrames = (expectedFrames - FRAME_SAMPLE_INTERVAL).toInt().coerceAtLeast(0)
                    if (droppedFrames >= 1) {
                        consecutiveDroppedFrames += droppedFrames
                        if (consecutiveDroppedFrames >= FRAME_DROP_THRESHOLD) {
                            if (SessionLog.healthSamplingAllowed()) {
                                SessionLog.appFrameDrop(
                                    droppedFrames = consecutiveDroppedFrames,
                                    screen = SessionLog.currentScreen,
                                    component = "Choreographer"
                                )
                            }
                            consecutiveDroppedFrames = 0
                        }
                    } else {
                        consecutiveDroppedFrames = 0
                    }
                    lastFrameTimeNanos = frameTimeNanos
                }
            } else {
                lastFrameTimeNanos = frameTimeNanos
            }
            if (choreographerPosted) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    init {
        // 60fps = 16.67ms per frame.
        val refreshRate = 60f
        frameIntervalNanos = (1_000_000_000L / refreshRate).toLong()
    }

    // ── Install all monitors ───────────────────────────────────────

    fun install(application: Application) {
        if (isInstalled) return
        isInstalled = true

        // 1. Coroutine exception handler
        installCoroutineExceptionHandler()

        // 2. ANR-level block detector (replaces 50ms polling)
        installAnrBlockDetector()

        // 3. Frame drop detector (Choreographer)
        installFrameDropDetector()

        // 4. Memory pressure callback
        installMemoryPressureMonitor(application)

        Log.d(TAG, "AppHealthMonitor installed")
    }

    fun uninstall() {
        choreographerPosted = false
        blockDetectorRunnable?.let { mainHandler.removeCallbacks(it) }
        blockDetectorRunnable = null
        try {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        } catch (_: Exception) { /* Choreographer not initialized on non-UI thread */ }
        isInstalled = false
    }

    // ── 1. Coroutine exception handler ─────────────────────────────

    /**
     * Global CoroutineExceptionHandler that logs uncaught coroutine exceptions.
     * Install this as the default handler for ViewModelScope and other scopes.
     */
    val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        val scope = try {
            throwable.stackTraceToString().take(100)
        } catch (_: Exception) { "unknown" }

        SessionLog.appCoroutineError(
            scope = scope,
            exception = throwable::class.simpleName ?: "Unknown",
            message = throwable.message?.take(200) ?: "",
            component = throwable.stackTraceToString().lineSequence().firstOrNull()?.take(80) ?: "",
            screen = SessionLog.currentScreen
        )
        Log.w(TAG, "Uncaught coroutine exception: ${throwable.message}", throwable)
    }

    private fun installCoroutineExceptionHandler() {
        // Note: We don't set a global Thread.defaultUncaughtExceptionHandler here
        // because CrashHandler already handles that. This handler is for coroutine scopes.
    }

    // ── 2. ANR-level block detector ─────────────────────────────────

    /**
     * Detects ANR-level main thread blocking only (>1s).
     *
     * Previous implementation posted a Runnable every 50ms, which caused:
     * - 10 blocks/sec self-referential detection (the detector itself was the block)
     * - 2294 block events in 3.7min, masking real performance issues
     * - Frame drops caused by the polling overhead
     *
     * v2: Only detects ANR-level blocks (>1s) with 2s cooldown between reports.
     * This eliminates the self-referential loop while still catching genuine freezes.
     */
    private var blockDetectorRunnable: Runnable? = null
    private var lastBlockReportTime: Long = 0L

    private fun createBlockDetectorRunnable(): Runnable {
        return Runnable {
            if (!isInstalled) return@Runnable
            val postedAt = lastBlockDetectionTime
            if (postedAt == 0L) {
                // Reschedule for next cycle
                scheduleAnrBlockDetection()
                return@Runnable
            }
            val elapsed = System.currentTimeMillis() - postedAt
            lastBlockDetectionTime = 0L

            if (elapsed >= ANR_BLOCK_THRESHOLD_MS) {
                val now = System.currentTimeMillis()
                // Cooldown: only report one ANR block per 2s window
                if (now - lastBlockReportTime >= ANR_BLOCK_COOLDOWN_MS) {
                    val stackTrace = Looper.getMainLooper().thread.stackTrace
                        .take(8)
                        .joinToString(" | ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                    if (SessionLog.healthSamplingAllowed()) {
                        SessionLog.appMainThreadBlock(
                            durationMs = elapsed,
                            screen = SessionLog.currentScreen,
                            component = "MainThread",
                            trigger = "handler_delay:$stackTrace"
                        )
                    }
                    lastBlockReportTime = now
                }
            }
            // Schedule next check
            scheduleAnrBlockDetection()
        }
    }

    private var lastBlockDetectionTime: Long = 0L

    private fun scheduleAnrBlockDetection() {
        if (!isInstalled) return
        val runnable = blockDetectorRunnable ?: return
        lastBlockDetectionTime = System.currentTimeMillis()
        mainHandler.postDelayed(runnable, ANR_BLOCK_THRESHOLD_MS)
    }

    private fun installAnrBlockDetector() {
        blockDetectorRunnable = createBlockDetectorRunnable()
        scheduleAnrBlockDetection()
    }

    // ── 3. Frame drop detector ─────────────────────────────────────

    private fun installFrameDropDetector() {
        try {
            choreographerPosted = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } catch (_: Exception) {
            // Choreographer not available (non-UI thread or testing)
            Log.w(TAG, "Choreographer not available, frame drop detection disabled")
        }
    }

    // ── 4. Memory pressure monitor ─────────────────────────────────

    private fun installMemoryPressureMonitor(application: Application) {
        object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                val label = when {
                    level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "critical"
                    level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "moderate"
                    else -> "low"
                }
                val runtime = Runtime.getRuntime()
                val freeMb = (runtime.freeMemory()) / (1024 * 1024)
                val totalMb = (runtime.totalMemory()) / (1024 * 1024)

                SessionLog.appMemoryPressure(
                    level = level,
                    levelLabel = label,
                    freeMb = freeMb,
                    totalMb = totalMb,
                    screen = SessionLog.currentScreen
                )
            }

            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
            override fun onLowMemory() {
                val runtime = Runtime.getRuntime()
                SessionLog.appMemoryPressure(
                    level = ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
                    levelLabel = "critical",
                    freeMb = runtime.freeMemory() / (1024 * 1024),
                    totalMb = runtime.totalMemory() / (1024 * 1024),
                    screen = SessionLog.currentScreen
                )
            }
        }.also { callback ->
            application.registerComponentCallbacks(callback)
        }
    }

    // ── 5. Input latency measurement ───────────────────────────────

    /**
     * Measure input latency by timestamping the start of input handling
     * and comparing with the next frame render.
     *
     * Usage in Composable:
     * ```
     * val inputStart = remember { mutableStateOf(0L) }
     * TextField(
     *     onValueChange = { newValue ->
     *         inputStart.value = System.currentTimeMillis()
     *         // ... process value
     *     },
     *     modifier = Modifier.onGloballyPositioned {
     *         if (inputStart.value > 0) {
     *             val latency = System.currentTimeMillis() - inputStart.value
     *             if (latency >= INPUT_LATENCY_THRESHOLD_MS) {
     *                 AppHealthMonitor.reportInputLatency(latency, "ai_chat", "MessageInput", "keyboard")
     *             }
     *             inputStart.value = 0L
     *         }
     *     }
     * )
     * ```
     */
    fun reportInputLatency(latencyMs: Long, screen: String, component: String, inputType: String) {
        if (latencyMs >= INPUT_LATENCY_THRESHOLD_MS) {
            SessionLog.appInputLatency(latencyMs, screen, component, inputType)
        }
    }

    // ── 6. Network latency measurement ─────────────────────────────

    /**
     * OkHttp interceptor that measures TTFB and total request latency.
     * Install this in OkHttpClient.Builder via addInterceptor().
     */
    class NetworkLatencyInterceptor : okhttp3.Interceptor {
        override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
            val request = chain.request()
            val startTime = System.nanoTime()

            val response: okhttp3.Response
            try {
                response = chain.proceed(request)
            } catch (e: Exception) {
                val totalMs = (System.nanoTime() - startTime) / 1_000_000
                SessionLog.appNetworkLatency(
                    endpoint = request.url.host,
                    ttfbMs = null,
                    totalMs = totalMs,
                    statusCode = null,
                    screen = SessionLog.currentScreen
                )
                throw e
            }

            val ttfbMs = (System.nanoTime() - startTime) / 1_000_000
            val statusCode = response.code

            // Note: total time is measured at TTFB level since we can't consume
            // the body here without breaking streaming. TTFB is the meaningful metric.
            SessionLog.appNetworkLatency(
                endpoint = request.url.host,
                ttfbMs = ttfbMs,
                totalMs = ttfbMs, // Will be updated by caller if needed
                statusCode = statusCode,
                screen = SessionLog.currentScreen
            )

            return response
        }
    }
}