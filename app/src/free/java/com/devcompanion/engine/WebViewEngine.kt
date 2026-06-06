package com.devcompanion.engine

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebView
import android.webkit.WebViewClient
import com.devcompanion.debug.WebViewDebugger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * BrowserEngine implementation wrapping a WebView.
 *
 * Used by the `free` flavor. All WebView-specific setup (settings, client,
 * JS injection, etc.) is orchestrated by [BrowserTab] via the [view] property
 * and [evaluateJavascript] calls. This class focuses on the common
 * BrowserEngine contract while providing access to the underlying WebView
 * for flavor-specific operations (debugger, JS injections, heartbeat).
 */
class WebViewEngine(
    private val webView: WebView,
    private val debugger: WebViewDebugger
) : BrowserEngine {

    override val view: android.view.View get() = webView

    /** Direct access to the underlying WebView for flavor-specific operations. */
    val underlyingWebView: WebView get() = webView

    /** The debugger instance for WebView-specific debugging. */
    val underlyingDebugger: WebViewDebugger get() = debugger

    @SuppressLint("SetJavaScriptEnabled")
    fun configureDefaults(viewportScale: Int) {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false
        webView.settings.textZoom = viewportScale
        webView.setInitialScale(viewportScale)
        webView.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.6422.113 Mobile Safari/537.36"
        webView.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_YES
        webView.settings.saveFormData = true
        @Suppress("DEPRECATION")
        webView.settings.savePassword = true
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
    }

    fun setWebViewClient(client: WebViewClient) {
        webView.webViewClient = client
    }

    fun setWebChromeClient(client: android.webkit.WebChromeClient) {
        webView.webChromeClient = client
    }

    fun addJavascriptInterface(obj: Any, name: String) {
        webView.addJavascriptInterface(obj, name)
    }

    fun attachDebugger() {
        debugger.attachWebView(webView)
    }

    override fun loadUrl(url: String) {
        webView.loadUrl(url)
    }

    override fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?) {
        webView.evaluateJavascript(script, callback)
    }

    override fun goBack() {
        webView.goBack()
    }

    override fun goForward() {
        webView.goForward()
    }

    override fun reload() {
        webView.reload()
    }

    override fun canGoBack(): Boolean = webView.canGoBack()

    override fun canGoForward(): Boolean = webView.canGoForward()

    override fun getTitle(): String? = webView.title

    override fun getUrl(): String? = webView.url

    override fun scrollX(): Int = webView.scrollX

    override fun scrollY(): Int = webView.scrollY

    override fun contentHeight(): Int = webView.contentHeight

    override fun viewportWidth(): Int = webView.width

    override fun viewportHeight(): Int = webView.height

    override fun setTextZoom(percent: Int) {
        webView.settings.textZoom = percent
    }

    override suspend fun screenshot(): Bitmap? {
        return try {
            val bitmap = withContext(Dispatchers.Main) {
                if (webView.width <= 0 || webView.height <= 0) return@withContext null
                val bmp = Bitmap.createBitmap(
                    webView.width.coerceAtLeast(1),
                    webView.height.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bmp)
                webView.draw(canvas)
                bmp
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    override fun destroy() {
        debugger.detachWebView()
        // Don't destroy the WebView — Compose manages its lifecycle
    }
}