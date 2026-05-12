package com.devcompanion.llm.agent

import android.util.Log
import android.webkit.WebView

/**
 * URL-based undo/rollback for the WebView agent.
 *
 * Before each navigation or form submission, the current URL is pushed
 * onto a stack. The user or agent can roll back to the previous URL
 * via [undo] or [goBack].
 *
 * This is a lightweight mechanism — it does not restore DOM state,
 * form inputs, or JavaScript runtime state. Only the URL history is
 * preserved.
 */
class UndoStack {

    companion object {
        private const val TAG = "UndoStack"
        private const val MAX_STACK_SIZE = 20
    }

    private val stack = mutableListOf<String>()

    /** Current stack depth (number of entries). */
    val depth: Int get() = stack.size

    /**
     * Push the current URL onto the stack before a navigation.
     * Call this *before* executing any tool that changes the page URL.
     */
    fun push(url: String) {
        if (stack.size >= MAX_STACK_SIZE) {
            stack.removeAt(0)
        }
        stack.add(url)
        Log.d(TAG, "Pushed URL: $url (stack depth: ${stack.size})")
    }

    /**
     * Pop the last URL from the stack and navigate the WebView to it.
     *
     * @return The URL navigated to, or null if the stack is empty.
     */
    suspend fun undo(webView: WebView): String? {
        if (stack.isEmpty()) {
            Log.w(TAG, "Undo stack is empty")
            return null
        }
        val url = stack.removeLast()
        Log.d(TAG, "Undo to URL: $url (stack depth: ${stack.size})")
        // Use WebView.goBack() which respects the browser's own history
        // and is more reliable than loadUrl for proper back navigation
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            webView.goBack()
        }
        return url
    }

    /**
     * Navigate back using WebView's own history.
     * This is simpler and more reliable for most cases.
     */
    suspend fun goBack(webView: WebView): Boolean {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            if (webView.canGoBack()) {
                webView.goBack()
                Log.d(TAG, "WebView goBack()")
                true
            } else {
                Log.w(TAG, "WebView cannot go back")
                false
            }
        }
    }

    /** Clear the entire undo stack. */
    fun clear() {
        stack.clear()
        Log.d(TAG, "Undo stack cleared")
    }

    /** Peek at the top of the stack without removing it. */
    fun peek(): String? = stack.lastOrNull()
}