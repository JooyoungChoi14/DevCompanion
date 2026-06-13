package com.devcompanion.engine

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import com.devcompanion.logging.SessionLog
import com.devcompanion.logging.EventType

/**
 * BrowserEngine implementation wrapping GeckoView + GeckoSession.
 *
 * Used by the `gecko` flavor. GeckoView handles rendering natively,
 * eliminating the need for JS injections (vh fix, autofill, heartbeat, etc.).
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
                hasUserGesture: Boolean?
            ) {
                _url = url
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                _canGoBack = canGoBack
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                _canGoForward = canGoForward
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

        Log.i(TAG, "GeckoEngine delegates installed")
    }

    // ── BrowserEngine contract ──────────────────────────────────────

    override fun loadUrl(url: String) {
        session.loadUri(url)
    }

    override fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?) {
        // GeckoView 150: evaluateJs removed from GeckoSession API.
        // Use loadUri("javascript:...") as a fallback, or implement via WebExtension.
        // For now, log and invoke callback with null — JS eval not available.
        Log.w(TAG, "evaluateJavascript: GeckoView 150 does not support evaluateJs; falling back to no-op")
        callback?.invoke(null)
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

    override suspend fun evalJs(js: String, timeoutMs: Long): String {
        // GeckoView 150: evaluateJs removed from GeckoSession API.
        // JS evaluation via evaluateJavascript (which also falls back to no-op for now).
        // TODO: Implement via WebExtension messaging or data: URI injection.
        Log.w(TAG, "evalJs: GeckoView 150 does not support evaluateJs; returning error")
        return """{"t":"error","v":"GeckoView 150 does not support evaluateJs. JS evaluation not available."}"""
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
        // C4 fix: close the GeckoSession to release native resources
        try {
            session.close()
        } catch (e: Exception) {
            Log.w(TAG, "GeckoSession.close() failed", e)
        }
        Log.i(TAG, "GeckoEngine destroyed, session closed")
    }

    override fun pause() {
        // GeckoView: no explicit pause needed — setActive(false) reduces resource usage
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

    companion object {
        private const val TAG = "GeckoEngine"
    }
}