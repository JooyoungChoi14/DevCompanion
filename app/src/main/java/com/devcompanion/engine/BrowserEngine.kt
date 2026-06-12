package com.devcompanion.engine

import android.graphics.Bitmap
import android.view.View

/**
 * Abstraction over browser engine implementations.
 *
 * Free flavor: WebViewEngine wrapping android.webkit.WebView
 * Gecko flavor: GeckoEngine wrapping org.mozilla.geckoview.GeckoView + GeckoSession
 *
 * GeckoView eliminates JS injection needs:
 * - vh/dvh computed correctly → VH_FIX unnecessary
 * - Keyboard handling built-in → KEYBOARD_FIX unnecessary
 * - Overflow/scroll handled correctly → OVERFLOW_FIX unnecessary
 * - text-size-adjust controllable via GeckoSettings → TEXT_SIZE_FIX unnecessary
 * - Autofill supported natively → AUTOFILL_INJECTION unnecessary
 * - No MutationObserver → no infinite loop freeze risk
 */
interface BrowserEngine {

    /** The Android View to embed in Compose (WebView or GeckoView). */
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
     * method exists for compatibility with WebView.evaluateJavascript.
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