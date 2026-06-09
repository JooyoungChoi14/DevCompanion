package com.devcompanion.engine

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebResourceError
import com.devcompanion.debug.WebViewDebugger
import com.devcompanion.logging.SessionLog
import com.devcompanion.logging.EventType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * BrowserEngine implementation wrapping a WebView.
 *
 * Used by the `free` flavor. Encapsulates all WebView-specific setup:
 * settings, client callbacks, JS injections, debugger, and heartbeat.
 * BrowserTab receives high-level callbacks via [BrowserEngine.Callbacks].
 */
class WebViewEngine(
    private val webView: WebView,
    private val debugger: WebViewDebugger
) : BrowserEngine {

    override val view: android.view.View get() = webView

    /**
     * Direct access to the underlying WebView for flavor-specific operations.
     * @suppress Internal use only — do not use outside the free flavor source set.
     */
    val underlyingWebView: WebView get() = webView

    /**
     * Direct access to the debugger for flavor-specific operations.
     * Internal — only for use within the free flavor source set.
     */
    internal val underlyingDebugger: WebViewDebugger get() = debugger

    /** WebView tracks loading state internally via WebViewClient callbacks. */
    override val isLoading: Boolean
        get() = _isLoading
    @Volatile
    private var _isLoading: Boolean = false

    // ── Callbacks ──────────────────────────────────────────────────

    @Volatile
    private var browserCallbacks: BrowserEngine.Callbacks? = null

    /** Set BrowserEngine-level callbacks (universal across flavors). Must be called on UI thread. */
    override fun setCallbacks(callbacks: BrowserEngine.Callbacks) {
        browserCallbacks = callbacks
    }

    // ── Configuration ───────────────────────────────────────────────

    private var urlHistoryStore: com.devcompanion.data.UrlHistoryStore? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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

    /**
     * Install WebViewClient, WebChromeClient, and JavascriptInterfaces.
     * Must be called after [configureDefaults] and from the UI thread.
     */
    fun installClients(
        viewportScale: Int,
        urlHistoryStore: com.devcompanion.data.UrlHistoryStore
    ) {
        this.urlHistoryStore = urlHistoryStore
        webView.webChromeClient = debugger.DebugChromeClient()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, pageUrl: String, favicon: Bitmap?) {
                _isLoading = true
                browserCallbacks?.onPageStarted(pageUrl)
                debugger.addUrlToHistory(pageUrl)
                urlHistoryStore?.addUrl(pageUrl)
                debugger.markPageStart()
                SessionLog.uiWebviewState(
                    pageUrl, view.width, view.height,
                    view.scrollX, view.scrollY, view.contentHeight
                )
            }

            override fun onPageFinished(view: WebView, url: String) {
                _isLoading = false
                val canBack = view.canGoBack()
                val canFwd = view.canGoForward()
                val title = view.title ?: ""
                browserCallbacks?.onPageFinished(url, title, canBack, canFwd)

                SessionLog.uiWebviewState(
                    url, view.width, view.height,
                    view.scrollX, view.scrollY, view.contentHeight
                )

                // Zoom CSS
                view.evaluateJavascript(
                    "(function(){document.documentElement.style.zoom='${viewportScale / 100.0}';})();",
                    null
                )

                // Flavor-conditional JS injections with sentinel guards
                if (InjectionConfig.needsInjections) {
                    view.evaluateJavascript(InjectionConfig.AUTOFILL_INJECTION, null)
                    view.evaluateJavascript(InjectionConfig.VH_FIX_INJECTION, null)
                    view.evaluateJavascript(InjectionConfig.TEXT_SIZE_FIX_INJECTION, null)
                }
                if (InjectionConfig.needsHeartbeat) {
                    view.evaluateJavascript(InjectionConfig.HEARTBEAT_INJECTION, null)
                }
                if (debugger.inspectorEnabled) {
                    view.evaluateJavascript(InjectionConfig.INSPECTOR_IFRAME_INJECTION, null)
                }

                debugger.DebugWebViewClient().onPageFinished(view, url)
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return debugger.DebugWebViewClient().shouldInterceptRequest(view, request)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                debugger.DebugWebViewClient().onReceivedError(view, request, error)
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                val url = request.url.toString()
                val statusCode = errorResponse.statusCode
                val reasonPhrase = errorResponse.reasonPhrase ?: "HTTP error"
                debugger.trackHttpError(url, statusCode, reasonPhrase)
                SessionLog.networkError(url, statusCode, reasonPhrase)
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                Log.e(TAG, "WebView render process gone: didCrash=${detail.didCrash()}")
                SessionLog.log(
                    EventType.WEBVIEW_CRASH,
                    mapOf("didCrash" to detail.didCrash().toString())
                )
                browserCallbacks?.onRenderProcessGone()
                return true
            }
        }

        debugger.attachWebView(webView)

        // JavascriptInterfaces for inspector and performance
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun post(json: String) {
                debugger.onInspectorResult(json)
            }
        }, "__devCompanionInspector")

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun post(json: String) {
                debugger.onPerformanceResult(json)
            }
        }, "__devCompanionPerf")
    }

    // ── BrowserEngine contract ──────────────────────────────────────

    override fun setup(viewportScale: Int, urlHistoryStore: com.devcompanion.data.UrlHistoryStore) {
        configureDefaults(viewportScale)
        installClients(viewportScale, urlHistoryStore)
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
            withContext(Dispatchers.Main) {
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
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun evalJs(js: String, timeoutMs: Long): String {
        return try {
            kotlinx.coroutines.withTimeout(timeoutMs) {
                val deferred = CompletableDeferred<String>()
                // Use Handler(Looper.getMainLooper()) as fallback if webView.post fails
                val posted = webView.post {
                    webView.evaluateJavascript(js) { result ->
                        deferred.complete(result ?: "")
                    }
                }
                if (!posted) {
                    // WebView not attached — try main handler
                    mainHandler.post {
                        webView.evaluateJavascript(js) { result ->
                            deferred.complete(result ?: "")
                        }
                    }
                }
                deferred.await()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            SessionLog.log(
                EventType.WEBVIEW_CRASH,
                mapOf("reason" to "eval_timeout", "timeoutMs" to timeoutMs.toString())
            )
            """{"t":"error","v":"WebView unresponsive: JavaScript evaluation timed out after ${timeoutMs}ms."}"""
        }
    }

    override suspend fun screenshotBase64(): String {
        val bitmap = screenshot() ?: return ""
        return try {
            BrowserEngine.bitmapToBase64(bitmap)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    override fun destroy() {
        debugger.detachWebView()
        // C5 fix: destroy the WebView to release native resources
        // Callers must remove the view from composition before calling this
        try {
            webView.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "WebView.destroy() failed", e)
        }
    }

    companion object {
        private const val TAG = "WebViewEngine"
    }
}