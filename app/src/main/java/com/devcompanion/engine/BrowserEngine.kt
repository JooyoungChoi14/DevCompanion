package com.devcompanion.engine

import android.graphics.Bitmap
import android.view.View

/**
 * Represents a resource loaded by a web page.
 * Used by the Sources tab to display page resources.
 */
data class PageResource(
    val url: String,
    val type: String,       // "document", "script", "stylesheet", "image", "font", "xhr", "other"
    val mimeType: String?,  // MIME type from response
    val size: Long,          // bytes, -1 if unknown
    val statusCode: Int,     // HTTP status code, -1 if unknown
    val content: String? = null,  // Pre-captured response body (null if not captured or binary)
    val isBinary: Boolean = false,  // true for images, fonts, audio, video, etc.
    val hasContent: Boolean = false  // true if content exists but wasn't inlined (too large)
)

/**
 * Abstraction over browser engine implementations.
 *
 * GeckoView-based: GeckoEngine wrapping org.mozilla.geckoview.GeckoView + GeckoSession
 *
 * GeckoView eliminates JS injection needs:
 * - vh/dvh computed correctly
 * - Keyboard handling built-in
 * - Overflow/scroll handled correctly
 * - text-size-adjust controllable via GeckoSettings
 * - Autofill supported natively
 * - No MutationObserver → no infinite loop freeze risk
 */
interface BrowserEngine {

    /** The Android View to embed in Compose (GeckoView). */
    val view: View

    /** Current page URL, or null if no page loaded. */
    fun getUrl(): String?

    /** Current page title, or null. */
    fun getTitle(): String?

    /** Whether the engine can navigate back. */
    fun canGoBack(): Boolean

    /** Whether the engine can navigate forward. */
    fun canGoForward(): Boolean

    /** Navigate back in history. */
    fun goBack()

    /** Navigate forward in history. */
    fun goForward()

    /** Reload the current page. */
    fun reload()

    /** Load a URL. */
    fun loadUrl(url: String)

    /**
     * Evaluate JavaScript and return the result via callback.
     * Callback receives the JSON string result (or null on error/no result).
     *
     * Note: prefer [evalJs] for coroutine-based calls. This callback-based
     * method exists for compatibility with callback-based evaluation.
     */
    fun evaluateJavascript(script: String, callback: ((String?) -> Unit)? = null)

    /** Whether the current page is still loading. */
    val isLoading: Boolean
        get() = false

    /** Current horizontal scroll position. -1 if unknown (GeckoView). */
    fun scrollX(): Int

    /** Current vertical scroll position. -1 if unknown (GeckoView). */
    fun scrollY(): Int

    /** Page content height in pixels. -1 if unknown (GeckoView). */
    fun contentHeight(): Int

    /** Viewport width in pixels. */
    fun viewportWidth(): Int

    /** Viewport height in pixels. */
    fun viewportHeight(): Int

    /** Set text zoom percentage (e.g., 100, 120, 150, 200). */
    fun setTextZoom(percent: Int)

    /** Capture a screenshot of the current page. Returns null on failure. */
    suspend fun screenshot(): Bitmap?

    /** Clear browser navigation history and navigate to about:blank.
     * Used to escape redirect loops where goBack keeps returning to the same URL.
     */
    fun clearHistory()

    /** Destroy the engine and release resources. Callers must remove the view from composition first. */
    fun destroy()

    /** Pause the engine (e.g., on Activity onPause). GeckoView needs session.pause(). */
    fun pause()

    /** Resume the engine (e.g., on Activity onResume). GeckoView needs session.resume(). */
    fun resume()

    /**
     * Callbacks for engine lifecycle events.
     * Implemented by BrowserTab to update UI state.
     */
    interface Callbacks {
        fun onPageStarted(url: String)
        fun onPageFinished(url: String, title: String?, canGoBack: Boolean, canGoForward: Boolean)
        fun onRenderProcessGone()
    }

    /**
     * Set callbacks for engine lifecycle events.
     * Must be called before [setup] or on the UI thread.
     */
    fun setCallbacks(callbacks: Callbacks)

    /**
     * Evaluate JavaScript synchronously (coroutine) with timeout.
     * Wraps the engine's evaluateJavascript in a suspend function.
     *
     * Note: timeout cancels the coroutine await but does NOT cancel the
     * pending JS execution. The JS will still run to completion in the engine.
     * Use PermissionGate for dangerous operations.
     *
     * @param js JavaScript code to evaluate.
     * @param timeoutMs Timeout in milliseconds (default 5000).
     * @return The result string, or error JSON on timeout.
     */
    suspend fun evalJs(js: String, timeoutMs: Long = 5_000L): String

    /**
     * Capture a screenshot as a Base64 JPEG string.
     * Uses [screenshot] internally and encodes the result.
     */
    suspend fun screenshotBase64(): String

    /**
     * Collect the list of resources loaded by the current page.
     * Uses the Performance API via evalJs to gather resource timing entries.
     * Only supported by GeckoEngine — WebView implementations should return empty list.
     */
    suspend fun collectPageResources(): List<PageResource>

    /**
     * Fetch the source content of a specific page resource.
     * Only works for text-based resources (HTML, JS, CSS, etc.).
     * Returns null for binary resources (images, fonts) or if the resource is not found.
     * Only supported by GeckoEngine — WebView implementations should return null.
     */
    suspend fun fetchResourceContent(url: String): String?

    /**
     * Perform engine-specific setup after construction.
     * Called by BrowserTab after creating the engine via EngineFactory.
     * Each implementation handles its own client/delegate installation.
     *
     * @param viewportScale Current viewport zoom scale (100/120/150/200).
     * @param urlHistoryStore Persistent URL history store.
     */
    fun setup(viewportScale: Int, urlHistoryStore: com.devcompanion.data.UrlHistoryStore)

    companion object {
        /**
         * Encode a [Bitmap] as Base64 JPEG string.
         * Shared implementation to avoid duplication across engine implementations.
         */
        fun bitmapToBase64(bitmap: Bitmap): String {
            val stream = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
            return android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
        }

        /** Constant indicating a scroll/content value is unknown. */
        const val UNKNOWN = -1
    }
}