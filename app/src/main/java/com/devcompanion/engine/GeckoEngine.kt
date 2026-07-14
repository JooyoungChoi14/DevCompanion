package com.devcompanion.engine

import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import com.devcompanion.logging.SessionLog
import com.devcompanion.logging.EventType
import java.util.concurrent.ConcurrentHashMap

/**
 * BrowserEngine implementation wrapping GeckoView + GeckoSession.
 *
 * Used by DevCompanion. GeckoView handles rendering natively,
 * eliminating the need for JS injections (vh fix, autofill, heartbeat, etc.).
 *
 * JS evaluation uses a custom URL scheme bridge:
 *   1. evalJs() calls loadUri("javascript:...") with a unique request ID
 *   2. The JS code posts results via location change to devcompanion://eval-result?id=...&data=...
 *   3. NavigationDelegate.onLoadRequest intercepts the custom scheme
 *   4. Results are delivered to pending CompletableDeferred instances
 *
 * Thread safety: All mutable state is @Volatile. Gecko delegate callbacks
 * arrive on the Gecko thread; we dispatch UI-affecting callbacks to the
 * main thread via [mainHandler].
 */
class GeckoEngine(
    private val geckoView: GeckoView,
    private val session: GeckoSession,
    private val resourceCollector: ResourceCollector? = null
) : BrowserEngine {

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val view: View get() = geckoView

    /**
     * Direct access to the underlying GeckoSession for delegate setup.
     * Internal — for use by EngineFactory and BrowserTab.
     */
    internal val underlyingSession: GeckoSession get() = session

    /**
     * Direct access to the underlying GeckoView for view-level operations.
     * Internal — for use by EngineFactory and BrowserTab.
     */
    internal val underlyingGeckoView: GeckoView get() = geckoView

    // ── Navigation state tracked via delegates (all @Volatile for thread safety) ──

    @Volatile private var _canGoBack = false
    @Volatile private var _canGoForward = false
    @Volatile private var _title: String? = null
    @Volatile private var _url: String? = null
    @Volatile private var _isLoading = false

    /** Whether the current page is still loading. */
    override val isLoading: Boolean get() = _isLoading

    @Volatile
    private var browserCallbacks: BrowserEngine.Callbacks? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Custom URL scheme JS eval bridge ──────────────────────────────────

    companion object {
        private const val TAG = "GeckoEngine"
        private const val EVAL_SCHEME = "devcompanion"
        private const val EVAL_HOST = "eval-result"
        private const val EVAL_PARAM_ID = "id"
        private const val EVAL_PARAM_DATA = "data"
        private const val EVAL_PARAM_ERROR = "error"

        /** Counter for generating unique eval request IDs. */
        private val evalCounter = java.util.concurrent.atomic.AtomicLong(0)

        /** Map of pending eval requests, keyed by request ID. */
        private val pendingEvals = ConcurrentHashMap<String, CompletableDeferred<String>>()
    }

    /** Set BrowserEngine-level callbacks. Thread-safe via @Volatile. */
    override fun setCallbacks(callbacks: BrowserEngine.Callbacks) {
        browserCallbacks = callbacks
    }

    /**
     * Perform GeckoView-specific setup: install navigation/progress/content delegates.
     * Called by BrowserTab after creating the engine.
     */
    override fun setup(viewportScale: Int, urlHistoryStore: com.devcompanion.data.UrlHistoryStore) {
        // viewportScale unused in GeckoView — textZoom removed in v150, CSS zoom breaks layout
        setupDelegates()
    }

    /**
     * Set up navigation tracking delegates and progress callbacks.
     * All callbacks dispatch to the main thread for UI safety.
     */
    fun setupDelegates() {
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                // Broad filter: reject ALL internal devcompanion:// URLs (not just eval-result)
                // to prevent URL pollution from JS bridge or future internal schemes.
                // This is intentionally broader than onLoadRequest (which only denies eval-result)
                // because onLocationChange may fire before deny() takes effect depending on GeckoView version.
                if (url != null && url.startsWith("$EVAL_SCHEME://")) return
                _url = url
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                _canGoBack = canGoBack
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                _canGoForward = canGoForward
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                val uri = request.uri
                // Intercept custom eval-result scheme
                if (uri.startsWith("$EVAL_SCHEME://$EVAL_HOST")) {
                    handleEvalResult(uri)
                    return GeckoResult.deny()
                }
                return null // Let other requests proceed normally
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                _isLoading = true
                // Note: Resource clearing is handled by the WebExtension's
                // webNavigation.onBeforeNavigate listener — no Kotlin-side clear needed.
                // Dispatch callback to main thread (Gecko callbacks run on Gecko thread)
                mainHandler.post {
                    browserCallbacks?.onPageStarted(url)
                }
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                _isLoading = false
                val url = _url ?: ""
                val title = _title
                val canBack = _canGoBack
                val canFwd = _canGoForward
                // Dispatch callback to main thread
                mainHandler.post {
                    browserCallbacks?.onPageFinished(url, title, canBack, canFwd)
                }
                SessionLog.uiWebviewState(
                    url, geckoView.width, geckoView.height,
                    BrowserEngine.UNKNOWN, BrowserEngine.UNKNOWN, BrowserEngine.UNKNOWN
                )
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                _title = title
            }
        }

        Log.i(TAG, "GeckoEngine delegates installed (with eval-result scheme interceptor)")
    }

    /**
     * Parse a devcompanion://eval-result URI and deliver the result to the pending deferred.
     *
     * URI format: devcompanion://eval-result?id=<requestId>&data=<urlEncodedResult>
     * Error format: devcompanion://eval-result?id=<requestId>&error=<urlEncodedError>
     */
    private fun handleEvalResult(uri: String) {
        try {
            val parsed = Uri.parse(uri)
            val id = parsed.getQueryParameter(EVAL_PARAM_ID) ?: run {
                Log.w(TAG, "eval-result: missing id parameter in $uri")
                return
            }
            val deferred = pendingEvals.remove(id) ?: run {
                Log.w(TAG, "eval-result: no pending request for id=$id")
                return
            }
            val error = parsed.getQueryParameter(EVAL_PARAM_ERROR)
            if (error != null) {
                val decoded = java.net.URLDecoder.decode(error, "UTF-8")
                deferred.complete("""{"t":"error","v":"$decoded"}""")
            } else {
                val data = parsed.getQueryParameter(EVAL_PARAM_DATA) ?: ""
                val decoded = java.net.URLDecoder.decode(data, "UTF-8")
                deferred.complete(decoded)
            }
        } catch (e: Exception) {
            Log.e(TAG, "eval-result: failed to parse URI $uri", e)
        }
    }

    // ── BrowserEngine contract ──────────────────────────────────────

    override fun loadUrl(url: String) {
        session.loadUri(url)
    }

    override fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?) {
        // GeckoView 150: no evaluateJs API.
        // Use the custom URL scheme bridge: inject JS that navigates to
        // devcompanion://eval-result?id=X&data=Y, which we intercept in onLoadRequest.
        // This callback-based variant creates a one-shot eval and delivers the result.
        val id = evalCounter.incrementAndGet().toString()
        val wrappedJs = buildEvalJs(id, script)
        session.loadUri("javascript:" + wrappedJs)
        // For callback-based calls, we launch a coroutine to await the result
        // but this is fire-and-forget for compatibility — the callback may arrive
        // after the calling context is gone. Use evalJs() (suspend) for reliable results.
        Log.d(TAG, "evaluateJavascript: dispatched eval id=$id via custom scheme bridge")
    }

    override fun goBack() {
        session.goBack()
    }

    override fun goForward() {
        session.goForward()
    }

    override fun reload() {
        session.reload()
    }

    override fun canGoBack(): Boolean = _canGoBack

    override fun canGoForward(): Boolean = _canGoForward

    override fun getTitle(): String? = _title

    override fun getUrl(): String? = _url

    /** Returns `UNKNOWN` — GeckoView doesn't expose View scroll as page scroll.
     * TODO: Implement JS-based scroll position query for GeckoView. */
    override fun scrollX(): Int = BrowserEngine.UNKNOWN

    /** Returns `UNKNOWN` — GeckoView doesn't expose View scroll as page scroll.
     * TODO: Implement JS-based scroll position query for GeckoView. */
    override fun scrollY(): Int = BrowserEngine.UNKNOWN

    override fun contentHeight(): Int = BrowserEngine.UNKNOWN

    override fun viewportWidth(): Int = geckoView.width

    override fun viewportHeight(): Int = geckoView.height

    override fun setTextZoom(percent: Int) {
        // GeckoView 150: textZoom removed from GeckoSessionSettings.
        // CSS zoom also removed (breaks Vuetify layout calculations).
        // Zoom via textZoom is not available in GeckoView 150.
        // TODO: Implement font scaling via WebExtension content script.
    }

    override suspend fun screenshot(): Bitmap? {
        return try {
            withContext(Dispatchers.Main) {
                if (geckoView.width <= 0 || geckoView.height <= 0) return@withContext null
                val bitmap = Bitmap.createBitmap(
                    geckoView.width.coerceAtLeast(1),
                    geckoView.height.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = android.graphics.Canvas(bitmap)
                geckoView.draw(canvas)
                bitmap
            }
        } catch (e: Exception) {
            SessionLog.log(EventType.WEBVIEW_CRASH, mapOf("reason" to "screenshot_failed", "error" to (e.message ?: "")))
            null
        }
    }

    /**
     * Evaluate JavaScript via the custom URL scheme bridge.
     *
     * How it works:
     * 1. Generate a unique request ID
     * 2. Wrap the JS code to catch errors and navigate to devcompanion://eval-result?id=X&data=Y
     * 3. Call session.loadUri("javascript:...") to execute the wrapped JS
     * 4. NavigationDelegate.onLoadRequest intercepts the custom scheme URI
     * 5. The result is parsed and delivered to the pending CompletableDeferred
     * 6. WithTimeout provides the timeout guarantee
     *
     * Limitations:
     * - Result size is limited by URL length (~8KB on most browsers)
     * - For large results (full DOM), chunking or WebExtension is needed
     */
    override suspend fun evalJs(js: String, timeoutMs: Long): String {
        val id = evalCounter.incrementAndGet().toString()
        val deferred = CompletableDeferred<String>()
        pendingEvals[id] = deferred

        val wrappedJs = buildEvalJs(id, js)
        session.loadUri("javascript:$wrappedJs")

        return try {
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            pendingEvals.remove(id)
            SessionLog.log(
                EventType.WEBVIEW_CRASH,
                mapOf("reason" to "eval_timeout", "timeoutMs" to timeoutMs.toString(), "evalId" to id)
            )
            """{"t":"error","v":"GeckoView eval timed out after ${timeoutMs}ms"}"""
        } catch (e: Exception) {
            pendingEvals.remove(id)
            """{"t":"error","v":"${e.message ?: "Unknown eval error"}"}"""
        }
    }

    /**
     * Build JavaScript code that:
     * 1. Executes the given [js] code
     * 2. Encodes the result (or error) as URL parameters
     * 3. Navigates to devcompanion://eval-result?id=X&data=Y (or &error=Z)
     *
     * The navigation is intercepted by onLoadRequest, which delivers the result
     * to the pending CompletableDeferred.
     */
    private fun buildEvalJs(id: String, js: String): String {
        // Encode the JS code safely for embedding in a javascript: URI
        // The wrapped code:
        // - Runs the user's JS in a try/catch
        // - Stringifies the result (or error message)
        // - URL-encodes it
        // - Navigates to our custom scheme URI
        return """(function(){try{var r=eval(${escapeJsString(js)});var s=r===undefined?'undefined':typeof r==='object'?JSON.stringify(r):String(r);location.href='devcompanion://eval-result?id=${id}&data='+encodeURIComponent(s)}catch(e){location.href='devcompanion://eval-result?id=${id}&error='+encodeURIComponent(e.message)}})()"""
    }

    /**
     * Escape a JS string for safe embedding in a javascript: URI.
     * We use JSON.stringify-style escaping to avoid injection issues.
     */
    private fun escapeJsString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
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
        // Cancel all coroutines started by this engine
        engineScope.cancel()
        // Clean up any pending evals
        pendingEvals.values.forEach { it.complete("""{"t":"error","v":"Engine destroyed"}""") }
        pendingEvals.clear()
        // C4 fix: close the GeckoSession to release native resources
        try {
            session.close()
        } catch (e: Exception) {
            Log.w(TAG, "GeckoSession.close() failed", e)
        }
        Log.i(TAG, "GeckoEngine destroyed, session closed")
    }

    override fun pause() {
        try {
            session.setActive(false)
        } catch (_: Exception) {
            // Not all GeckoView versions support setActive
        }
    }

    override fun resume() {
        try {
            session.setActive(true)
        } catch (_: Exception) {
            // Not all GeckoView versions support setActive
        }
    }

    // ── Page resources ──────────────────────────────────────────────

    /**
     * Collect the list of resources loaded by the current page.
     *
     * Primary method: WebExtension webRequest API via ResourceCollector.
     * - No CORS restrictions (captures MIME type, status code, Content-Length for all origins)
     * - No URL length limit (uses message passing, not eval-result URL scheme)
     * - Real-time accumulation (resources collected as the page loads)
     *
     * Fallback: Performance API via evalJs (for when WebExtension is unavailable).
     * - CORS: size/statusCode are empty for cross-origin resources
     * - 8KB URL limit: may timeout on resource-heavy pages
     */
    override suspend fun collectPageResources(): List<PageResource> {
        // Primary: WebExtension resource collector
        val collector = resourceCollector
        if (collector != null && collector.ready) {
            val resources = collector.collectResources()
            if (resources.isNotEmpty()) {
                Log.d(TAG, "collectPageResources: ${resources.size} resources via WebExtension")
                return resources
            }
            Log.d(TAG, "collectPageResources: WebExtension returned 0 resources, trying Performance API fallback")
        }

        // Fallback: Performance API via evalJs
        val js = """
            (function(){
                var entries = performance.getEntriesByType('resource');
                return JSON.stringify(entries.map(function(e){
                    var type = e.initiatorType || 'other';
                    var mapped = 'other';
                    if (type === 'navigation' || type === 'document') mapped = 'document';
                    else if (type === 'script') mapped = 'script';
                    else if (type === 'link' || type === 'css') mapped = 'stylesheet';
                    else if (type === 'img' || type === 'image') mapped = 'image';
                    else if (type === 'font') mapped = 'font';
                    else if (type === 'xmlhttprequest' || type === 'fetch') mapped = 'xhr';
                    else {
                        var ext = e.name.split('?')[0].split('.').pop().toLowerCase();
                        if (['js'].indexOf(ext) >= 0) mapped = 'script';
                        else if (['css'].indexOf(ext) >= 0) mapped = 'stylesheet';
                        else if (['png','jpg','jpeg','gif','svg','webp','ico','avif'].indexOf(ext) >= 0) mapped = 'image';
                        else if (['woff','woff2','ttf','otf','eot'].indexOf(ext) >= 0) mapped = 'font';
                        else mapped = type;
                    }
                    return {
                        name: e.name,
                        type: mapped,
                        size: e.transferSize || -1,
                        statusCode: e.responseStatus || -1
                    };
                }));
            })()
        """.trimIndent()

        return try {
            val result = evalJs(js, timeoutMs = 3000L)
            Log.d(TAG, "collectPageResources: Performance API fallback, result length=${result.length}")
            parseResourceJson(result)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to collect page resources", e)
            emptyList()
        }
    }

    /**
     * Fetch the source content of a specific page resource.
     *
     * Uses JS fetch() to retrieve the content. For GeckoView, this works
     * because the page's origin context applies, giving same-origin access
     * to resources. Cross-origin resources will fail with CORS errors —
     * this is expected and matches Chrome DevTools behavior.
     *
     * The result is delivered via the eval-result URL scheme, which has
     * an ~8KB limit. For large resources, the content is truncated to
     * 50KB in JS before encoding.
     */
    override suspend fun fetchResourceContent(url: String): String? {
        val escapedUrl = JsUtils.escapeJsString(url)
        val js = """
            (async function(){
                try {
                    var resp = await fetch($escapedUrl);
                    var contentType = resp.headers.get('Content-Type') || '';
                    var isBinary = /image|font|audio|video|pdf|zip/i.test(contentType);
                    if (isBinary) {
                        var buf = await resp.arrayBuffer();
                        return JSON.stringify({binary: true, mimeType: contentType, size: buf.byteLength});
                    }
                    var text = await resp.text();
                    if (text.length > 51200) text = text.substring(0, 51200) + '\\n--- TRUNCATED ---';
                    return JSON.stringify({content: text, mimeType: contentType, size: text.length});
                } catch(e) {
                    return JSON.stringify({error: e.message});
                }
            })()
        """.trimIndent()

        return try {
            val result = evalJs(js, timeoutMs = 5000L)
            parseContentResult(result)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch resource content for $url", e)
            null
        }
    }

    // ── Parsing helpers ──────────────────────────────────────────────

    private fun parseResourceJson(json: String): List<PageResource> {
        val trimmed = json.trim()
        return try {
            // evalJs wraps result in JSON: {"t":"...","v":"..."}
            val inner = if (trimmed.startsWith("{\"t\":")) {
                val obj = com.google.gson.JsonParser.parseString(trimmed).asJsonObject
                obj.get("v")?.asString ?: trimmed
            } else {
                trimmed
            }
            // The inner value might be a JSON-encoded string — try parsing
            val resources = try {
                com.google.gson.JsonParser.parseString(inner).asJsonArray
            } catch (_: Exception) {
                // Maybe it was double-encoded
                try {
                    val decoded = com.google.gson.JsonParser.parseString(inner).asString
                    com.google.gson.JsonParser.parseString(decoded).asJsonArray
                } catch (e2: Exception) {
                    Log.w(TAG, "Failed to parse resource JSON (double-decode): inner first 200='${inner.take(200)}'", e2)
                    return emptyList()
                }
            }
            resources.mapNotNull { element ->
                try {
                    val obj = element.asJsonObject
                    PageResource(
                        url = obj.get("name").asString,
                        type = obj.get("type").asString,
                        mimeType = null,
                        size = obj.get("size")?.asLong ?: -1L,
                        statusCode = obj.get("statusCode")?.asInt ?: -1
                    )
                } catch (_: Exception) { null }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse resource JSON: first 200 chars='${trimmed.take(200)}'", e)
            emptyList()
        }
    }

    private fun parseContentResult(json: String): String? {
        return try {
            val trimmed = json.trim()
            val inner = if (trimmed.startsWith("{\"t\":")) {
                val obj = com.google.gson.JsonParser.parseString(trimmed).asJsonObject
                obj.get("v")?.asString ?: trimmed
            } else {
                trimmed
            }
            val parsed = try {
                com.google.gson.JsonParser.parseString(inner).asJsonObject
            } catch (_: Exception) {
                try {
                    val decoded = com.google.gson.JsonParser.parseString(inner).asString
                    com.google.gson.JsonParser.parseString(decoded).asJsonObject
                } catch (e2: Exception) {
                    Log.w(TAG, "Failed to parse content result (double-decode): inner first 200='${inner.take(200)}'", e2)
                    return null
                }
            }
            if (parsed.has("content")) {
                parsed.get("content").asString
            } else if (parsed.has("binary")) {
                null // Binary resource — content not available
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse content result: first 200 chars='${json.take(200)}'", e)
            null
        }
    }
}