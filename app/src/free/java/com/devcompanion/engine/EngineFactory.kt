package com.devcompanion.engine

import android.content.Context
import android.view.View
import android.webkit.WebView
import com.devcompanion.debug.WebViewDebugger

/**
 * Factory to create the browser View for the free flavor (WebView).
 */
object EngineFactory {
    fun createWebView(context: Context, debugger: WebViewDebugger): WebView {
        return WebView(context.applicationContext)
    }
}