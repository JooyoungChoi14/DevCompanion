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
     */
    fun evaluateJavascript(script: String, callback: ((String?) -> Unit)? = null)

    /** Current horizontal scroll position. */
    fun scrollX(): Int

    /** Current vertical scroll position. */
    fun scrollY(): Int

    /** Page content height in pixels. */
    fun contentHeight(): Int

    /** Viewport width in pixels. */
    fun viewportWidth(): Int

    /** Viewport height in pixels. */
    fun viewportHeight(): Int

    /** Set text zoom percentage (e.g., 100, 120, 150, 200). */
    fun setTextZoom(percent: Int)

    /** Capture a screenshot of the current page. Returns null on failure. */
    suspend fun screenshot(): Bitmap?

    /** Destroy the engine and release resources. */
    fun destroy()
}