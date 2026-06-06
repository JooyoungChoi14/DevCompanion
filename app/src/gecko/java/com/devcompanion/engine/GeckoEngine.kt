package com.devcompanion.engine

import android.graphics.Bitmap
import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * BrowserEngine implementation wrapping GeckoView + GeckoSession.
 *
 * Used by the `gecko` flavor. GeckoView handles rendering natively,
 * eliminating the need for JS injections (vh fix, autofill, heartbeat, etc.).
 *
 * JS evaluation uses GeckoSession's evaluateJavaScript API.
 * Navigation callbacks are wired via NavigationDelegate, ProgressDelegate,
 * and ContentDelegate.
 */
class GeckoEngine(
    private val geckoView: GeckoView,
    private val session: GeckoSession
) : BrowserEngine {

    override val view: View get() = geckoView

    /** Direct access to the underlying GeckoSession for delegate setup. */
    val underlyingSession: GeckoSession get() = session

    /** Direct access to the underlying GeckoView for view-level operations. */
    val underlyingGeckoView: GeckoView get() = geckoView

    // ── Navigation state tracked via delegates ──────────────────────

    private var _canGoBack = false
    private var _canGoForward = false
    private var _title: String? = null
    private var _url: String? = null
    private var _isLoading = false

    /** Whether the current page is still loading. */
    val isLoading: Boolean get() = _isLoading

    /**
     * Set up navigation tracking delegates.
     * Called by BrowserTab after creating the engine.
     */
    fun setupDelegates() {
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>?,
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
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                _isLoading = true
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                _isLoading = false
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                _title = title
            }
        }
    }

    // ── BrowserEngine contract ──────────────────────────────────────

    override fun loadUrl(url: String) {
        session.loadUri(url)
    }

    override fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?) {
        // GeckoView uses GeckoSession.evaluateJavaScript for JS evaluation
        val result: GeckoResult<String> = session.evaluateJavaScript(script)
        result.then({ value ->
            callback?.invoke(value)
            GeckoResult<Void>()
        }, { throwable ->
            callback?.invoke(null)
            GeckoResult<Void>()
        })
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

    override fun scrollX(): Int = geckoView.scrollX

    override fun scrollY(): Int = geckoView.scrollY

    override fun contentHeight(): Int {
        // GeckoView doesn't expose contentHeight directly like WebView
        // Return view height as approximation
        return geckoView.height
    }

    override fun viewportWidth(): Int = geckoView.width

    override fun viewportHeight(): Int = geckoView.height

    override fun setTextZoom(percent: Int) {
        session.settings.textZoom = percent
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
            null
        }
    }

    override fun destroy() {
        // Don't close the session — Compose manages the view lifecycle
        // Session cleanup happens when the view is detached
    }
}