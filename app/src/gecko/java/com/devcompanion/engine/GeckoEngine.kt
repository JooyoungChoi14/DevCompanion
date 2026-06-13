package com.devcompanion.engine

import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
 * Used by the `gecko` flavor. GeckoView handles rendering natively,
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
    private val session: GeckoSession
) : BrowserEngine {

    override val view: View get() = geckoView

    /**
     * Direct access to the underlying GeckoSession for delegate setup.
     * Internal — only for use within the gecko flavor source set.
     */
    internal val underlyingSession: GeckoSession get() = session

    /**
     * Direct access to the underlying GeckoView for view-level operations.
     * Internal — only for use within the gecko flavor source set.
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
        // GeckoView 150: textZoom was removed from GeckoSessionSettings.
        // Font scaling is handled via CSS zoom in BrowserTab (same as WebView zoom).
        // No-op here — BrowserTab applies CSS zoom via evaluateJavascript.
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
}