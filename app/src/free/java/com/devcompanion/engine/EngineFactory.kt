package com.devcompanion.engine

import android.content.Context
import android.webkit.WebView

/**
 * Factory to create a WebView-based browser engine.
 * This is the free flavor implementation using the system WebView.
 */
object EngineFactory {

    fun createView(context: Context, block: WebView.() -> Unit): android.view.View {
        return WebView(context.applicationContext).apply(block)
    }
}