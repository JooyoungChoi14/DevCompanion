package com.devcompanion.engine

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension

/**
 * Collects page resources via a GeckoView WebExtension that uses the webRequest API.
 *
 * This replaces the previous Performance API polling approach which had two critical flaws:
 * 1. CORS: performance.getEntriesByType('resource') returns empty size/statusCode
 *    for cross-origin resources
 * 2. URL bridge limit: devcompanion://eval-result?data=... has ~8KB limit,
 *    causing eval_timeout for resource-heavy pages
 *
 * The WebExtension approach:
 * - Uses browser.webRequest.onHeadersReceived to capture MIME type, status code,
 *   and Content-Length for every request (no CORS restriction)
 * - Communicates via GeckoRuntime messaging (no URL length limit)
 * - Accumulates resources in real-time as the page loads
 * - Resets on main-frame navigation (via webNavigation.onBeforeNavigate in background.js)
 *
 * GeckoView WebExtension API:
 * - registration: runtime.webExtensionController.installBuiltIn(uri)
 * - messaging: runtime.webExtensionController.sendMessage(id, message)
 *   (NOT ext.sendMessage — that's for native messaging ports, not runtime messages)
 */
class ResourceCollector(
    private val runtime: GeckoRuntime
) {
    companion object {
        private const val TAG = "ResourceCollector"
        private const val EXTENSION_URI = "resource://android/assets/extensions/resource-monitor/"
        private const val EXTENSION_ID = "resource-monitor@devcompanion"
        private const val MESSAGE_TIMEOUT_MS = 3000L
    }

    @Volatile
    private var extension: WebExtension? = null

    @Volatile
    private var isRegistered = false

    /** Whether the WebExtension has been successfully registered */
    val ready: Boolean get() = isRegistered && extension != null

    /**
     * Register the WebExtension with GeckoRuntime asynchronously.
     *
     * Uses WebExtensionController.installBuiltIn() which returns GeckoResult.
     * Must be called BEFORE any GeckoSession is opened, because webRequest
     * listeners need to be in place before navigation begins.
     *
     * The caller should await the result before proceeding. If registration
     * fails, collectPageResources() will fall back to the Performance API.
     *
     * @return true if registration succeeded, false otherwise
     */
    suspend fun registerAsync(): Boolean {
        if (isRegistered && extension != null) {
            Log.d(TAG, "Extension already registered")
            return true
        }

        return try {
            withContext(Dispatchers.Main) {
                val controller = runtime.webExtensionController
                val deferred = CompletableDeferred<WebExtension?>()

                controller.installBuiltIn(EXTENSION_URI)
                    .then({ webExtension ->
                        Log.i(TAG, "Resource monitor extension installed: ${webExtension.id}")
                        extension = webExtension
                        isRegistered = true
                        deferred.complete(webExtension)
                        null as GeckoResult<Any?>?
                    }, { throwable ->
                        Log.e(TAG, "Failed to install resource monitor extension: ${throwable?.message}")
                        isRegistered = false
                        deferred.complete(null)
                        null as GeckoResult<Any?>?
                    })

                val result = deferred.await()
                result != null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register resource monitor extension", e)
            isRegistered = false
            false
        }
    }

    /**
     * Collect page resources from the WebExtension background script.
     * Sends a "getResources" message via WebExtensionController and parses the response.
     *
     * Returns an empty list if the extension is not registered or if
     * communication times out, so the caller can fall back to Performance API.
     */
    suspend fun collectResources(): List<PageResource> {
        val ext = extension
        if (!isRegistered || ext == null) {
            Log.w(TAG, "Extension not registered, returning empty resources")
            return emptyList()
        }

        return try {
            val response = withTimeout(MESSAGE_TIMEOUT_MS) {
                sendMessage("getResources")
            }
            parseResourceResponse(response)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "Resource collection timed out")
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to collect resources via WebExtension", e)
            emptyList()
        }
    }

    // ── Messaging ──────────────────────────────────────────

    /**
     * Send a message to the WebExtension background script and await response.
     *
     * CRITICAL: Uses runtime.webExtensionController.sendMessage() — the
     * controller-level API that sends runtime messages to the extension's
     * background script, where browser.runtime.onMessage receives them.
     *
     * This is NOT ext.sendMessage() which sends to a native messaging port.
     */
    private suspend fun sendMessage(command: String): JSONObject? {
        val deferred = CompletableDeferred<JSONObject?>()

        withContext(Dispatchers.Main) {
            try {
                val message = JSONObject().apply {
                    put("command", command)
                }

                // Controller-level messaging: sends to the extension's background script
                // via browser.runtime.onMessage, NOT to a native messaging port.
                val result = runtime.webExtensionController.sendMessage(
                    EXTENSION_ID, message, null
                )
                result.then({ response ->
                    if (response is JSONObject) {
                        deferred.complete(response)
                    } else if (response != null) {
                        try {
                            val jsonStr = response.toString()
                            deferred.complete(JSONObject(jsonStr))
                        } catch (e: Exception) {
                            Log.d(TAG, "Non-JSONObject response: ${response.javaClass.simpleName}")
                            deferred.complete(null)
                        }
                    } else {
                        deferred.complete(null)
                    }
                    null as GeckoResult<Any?>?
                }, { throwable ->
                    Log.w(TAG, "Extension message error: ${throwable?.message}")
                    deferred.complete(null)
                    null as GeckoResult<Any?>?
                })
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send extension message", e)
                deferred.complete(null)
            }
        }

        return deferred.await()
    }

    // ── Parsing ───────────────────────────────────────────

    private fun parseResourceResponse(response: JSONObject?): List<PageResource> {
        if (response == null) {
            Log.w(TAG, "Resource response is null")
            return emptyList()
        }

        val resourcesArray = response.optJSONArray("resources") ?: run {
            Log.w(TAG, "No 'resources' array in response: keys=${response.keys().asSequence().toList()}")
            return emptyList()
        }

        return (0 until resourcesArray.length()).mapNotNull { i ->
            try {
                val r = resourcesArray.getJSONObject(i)
                val url = r.optString("url", null) ?: return@mapNotNull null
                PageResource(
                    url = url,
                    type = r.optString("type", "other"),
                    mimeType = r.optString("mimeType", null)?.takeIf { it.isNotEmpty() && it != "null" },
                    size = r.optLong("size", -1L),
                    statusCode = r.optInt("statusCode", -1)
                )
            } catch (e: Exception) {
                Log.d(TAG, "Failed to parse resource entry", e)
                null
            }
        }
    }
}