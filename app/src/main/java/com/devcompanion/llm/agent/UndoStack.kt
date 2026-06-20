package com.devcompanion.llm.agent

import android.util.Log
import com.devcompanion.engine.BrowserEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thread-safe URL-based undo/rollback for the browser engine agent.
 *
 * Before each navigation or form submission, the current URL is pushed
 * onto a stack. The user or agent can roll back to the previous URL
 * via [undo] or [goBack].
 *
 * This is a lightweight mechanism — it does not restore DOM state,
 * form inputs, or JavaScript runtime state. Only the URL history is
 * preserved.
 *
 * Thread safety: [push], [undo], [clear], [peek], and [depth] are all
 * synchronized on the internal deque. [AgentLoop] runs on
 * [Dispatchers.Default] while [AiChatViewModel] calls [undo] from
 * [Dispatchers.Main], so concurrent access is possible.
 */
class UndoStack {

    companion object {
        private const val TAG = "UndoStack"
        private const val MAX_STACK_SIZE = 20
    }

    private val deque = ArrayDeque<String>(MAX_STACK_SIZE)

    /** Current stack depth (number of entries). Thread-safe read. */
    val depth: Int get() = synchronized(deque) { deque.size }

    /**
     * Push the current URL onto the stack before a navigation.
     * Call this *before* executing any tool that changes the page URL.
     */
    fun push(url: String) = synchronized(deque) {
        if (deque.size >= MAX_STACK_SIZE) {
            deque.removeFirst()
        }
        deque.addLast(url)
        Log.d(TAG, "Pushed URL: $url (stack depth: ${deque.size})")
    }

    /**
     * Pop the last URL from the stack and navigate the browser engine back.
     *
     * @return The URL that was on top of the stack, or null if the stack was empty.
     */
    suspend fun undo(engine: BrowserEngine): String? {
        val url: String?
        synchronized(deque) {
            if (deque.isEmpty()) {
                Log.w(TAG, "Undo stack is empty")
                return null
            }
            url = deque.removeLast()
            Log.d(TAG, "Undo to URL: $url (stack depth: ${deque.size})")
        }
        // Use engine.goBack() which respects the browser's own history
        // and is more reliable than loadUrl for proper back navigation
        withContext(Dispatchers.Main) {
            engine.goBack()
        }
        return url
    }

    /**
     * Navigate back using the browser engine's own history.
     * This is simpler and more reliable for most cases.
     */
    suspend fun goBack(engine: BrowserEngine): Boolean {
        return withContext(Dispatchers.Main) {
            if (engine.canGoBack()) {
                engine.goBack()
                Log.d(TAG, "Browser engine goBack()")
                true
            } else {
                Log.w(TAG, "Browser engine cannot go back")
                false
            }
        }
    }

    /** Clear the entire undo stack. */
    fun clear() = synchronized(deque) {
        deque.clear()
        Log.d(TAG, "Undo stack cleared")
    }

    /** Peek at the top of the stack without removing it. */
    fun peek(): String? = synchronized(deque) { deque.lastOrNull() }
}