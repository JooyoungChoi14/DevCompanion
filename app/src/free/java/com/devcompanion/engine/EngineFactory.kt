package com.devcompanion.engine

import android.content.Context
import android.webkit.WebView
import com.devcompanion.debug.WebViewDebugger

/**
 * Factory to create the browser engine for the free flavor (WebView).
 */
object EngineFactory {
    fun create(context: Context, debugger: WebViewDebugger): BrowserEngine {
        val webView = WebView(context.applicationContext)
        return WebViewEngine(webView, debugger)
    }
}