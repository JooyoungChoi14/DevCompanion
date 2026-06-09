package com.devcompanion.engine

import android.content.Context
import android.webkit.WebView
import com.devcompanion.debug.BrowserDebugger
import com.devcompanion.debug.WebViewDebugger

/**
 * Factory to create the browser engine for the free flavor (WebView).
 */
object EngineFactory {
    /** Create the flavor-appropriate debugger instance. */
    fun createDebugger(): BrowserDebugger = WebViewDebugger()

    fun create(context: Context, debugger: BrowserDebugger?): BrowserEngine {
        val webView = WebView(context.applicationContext)
        val wvd = (debugger as? WebViewDebugger) ?: WebViewDebugger()
        return WebViewEngine(webView, wvd)
    }
}