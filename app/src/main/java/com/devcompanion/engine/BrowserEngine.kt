package com.devcompanion.engine

/**
 * Abstract browser engine interface.
 * Implemented by [WebViewEngine] (free flavor) and [GeckoViewEngine] (gecko flavor).
 */
interface BrowserEngine {
    /** Load a URL in the browser engine. */
    fun loadUrl(url: String)

    /** Navigate back. Returns true if there was a history entry to go back to. */
    fun goBack(): Boolean

    /** Navigate forward. Returns true if there was a history entry to go forward to. */
    fun goForward(): Boolean

    /** Check if back navigation is possible. */
    fun canGoBack(): Boolean

    /** Check if forward navigation is possible. */
    fun canGoForward(): Boolean

    /** Reload the current page. */
    fun reload()

    /** Evaluate JavaScript in the page context. */
    fun evaluateJavascript(script: String, resultCallback: ((String?) -> Unit)? = null)

    /** Get the current URL. */
    fun getUrl(): String?

    /** Get the current page title. */
    fun getTitle(): String?

    /** Destroy the engine and release resources. */
    fun destroy()

    /** Set a listener for page lifecycle events. */
    fun setPageListener(listener: PageListener?)

    /** Set a listener for URL history updates. */
    fun setUrlHistoryListener(listener: UrlHistoryListener?)
}

interface PageListener {
    fun onPageStarted(url: String)
    fun onPageFinished(url: String)
    fun onPageTitleChanged(title: String)
    fun onReceivedError(url: String?, errorCode: Int, description: String?)
}

interface UrlHistoryListener {
    fun onUrlVisited(url: String)
}