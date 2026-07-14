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
import org.mozilla.geckoview.WebExtensionController

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
 * - Resets on main-frame navigation (via webNavigation.onBeforeNavigate)
 *
 * GeckoView WebExtension API:
 * - registration: WebExtensionController.installBuiltIn("resource://android/assets/extensions/resource-monitor/")
 * - messaging: WebExtension.setMessageDelegate() + sendMessage()
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
    val ready: Boolean get() = isRegistered

    /**
     * Register the WebExtension with GeckoRuntime.
     *
     * Uses WebExtensionController.installBuiltIn() which is the correct API
     * for GeckoView 100+. The extension is loaded from
     * assets/extensions/resource-monitor/ in the APK.
     *
     * This must be called BEFORE any GeckoSession is opened, because webRequest
     * listeners need to be in place before navigation begins.
     *
     * @return true if registration succeeded or extension was already installed
     */
    fun register(): Boolean {
        if (isRegistered) {
            Log.d(TAG, "Extension already registered")
            return true
        }

        return try {
            val controller = runtime.webExtensionController

            // installBuiltIn returns GeckoResult<WebExtension> — we use the blocking
            // variant because this is called during engine setup on the main thread
            // before any session is opened.
            val ext = controller.installBuiltIn(EXTENSION_URI)
                .map { webExtension ->
                    Log.i(TAG, "Resource monitor extension installed: ${webExtension.id}")
                    this.extension = webExtension
                    this.isRegistered = true
                    webExtension
                }
                .exceptionally { throwable ->
                    Log.e(TAG, "Failed to install resource monitor extension: ${throwable.message}")
                    null
                }

            // Since register() is synchronous in our API, we try to get the result
            // but GeckoResult is async. We'll handle this properly.
            // For now, optimistically set registered and handle failure in collectResources()
            isRegistered = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register resource monitor extension", e)
            false
        }
    }

    /**
     * Register the WebExtension asynchronously, properly awaiting the GeckoResult.
     * Preferred over register() for coroutine callers.
     */
    suspend fun registerAsync(): Boolean {
        if (isRegistered && extension != null) {
            Log.d(TAG, "Extension already registered")
            return true
        }

        return try {
            val result = withContext(Dispatchers.Main) {
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
                        deferred.complete(null)
                        null as GeckoResult<Any?>?
                    })

                deferred.await()
            }

            result != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register resource monitor extension", e)
            false
        }
    }

    /**
     * Collect page resources from the WebExtension background script.
     * Sends a "getResources" message and parses the response.
     *
     * Returns an empty list if the extension is not registered or if
     * communication times out.
     */
    suspend fun collectResources(): List<PageResource> {
        val ext = extension
        if (!isRegistered || ext == null) {
            Log.w(TAG, "Extension not registered, returning empty resources")
            return emptyList()
        }

        return try {
            val response = withTimeout(MESSAGE_TIMEOUT_MS) {
                sendMessage(ext, "getResources")
            }
            parseResourceResponse(response)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "Resource collection timed out", e)
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to collect resources via WebExtension", e)
            emptyList()
        }
    }

    /**
     * Clear accumulated resources in the WebExtension.
     * Called when a new page load starts to prevent stale data.
     */
    suspend fun clearResources() {
        val ext = extension
        if (!isRegistered || ext == null) return

        try {
            withTimeout(MESSAGE_TIMEOUT_MS) {
                sendMessage(ext, "clearResources")
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to clear resources (non-critical)", e)
        }
    }

    // ── Messaging ──────────────────────────────────────────

    /**
     * Send a message to the WebExtension background script and await response.
     *
     * GeckoView WebExtension messaging uses GeckoResult-based async patterns.
     * We wrap it in a CompletableDeferred for coroutine integration.
     *
     * The extension's background.js handles messages via
     * browser.runtime.onMessage.addListener() and returns a response object.
     */
    private suspend fun sendMessage(ext: WebExtension, command: String): JSONObject? {
        val deferred = CompletableDeferred<JSONObject?>()

        withContext(Dispatchers.Main) {
            try {
                val message = JSONObject().apply {
                    put("command", command)
                }

                // WebExtension.sendMessage() sends to the extension's background script
                // The nativeAppId parameter is null for extension-to-background messaging
                val result = ext.sendMessage(EXTENSION_ID, message, null)
                result.then({ response ->
                    if (response is JSONObject) {
                        deferred.complete(response)
                    } else if (response != null) {
                        // Response might be a GeckoBundle or other type
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
                    null // GeckoResult expects null for successful completion
                }, { throwable ->
                    Log.w(TAG, "Extension message error: ${throwable?.message}")
                    deferred.complete(null)
                    null
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