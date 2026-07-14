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
 * - Communicates via GeckoView port-based messaging (no URL length limit)
 * - Accumulates resources in real-time as the page loads
 * - Resets on main-frame navigation (via webNavigation.onBeforeNavigate in background.js)
 *
 * GeckoView WebExtension API:
 * - registration: runtime.webExtensionController.installBuiltIn(uri)
 * - messaging: Port-based via WebExtension.MessageDelegate
 *   background.js calls browser.runtime.connect() → we get a Port
 *   We send commands via port.postMessage() → background.js responds via port.postMessage()
 */
class ResourceCollector(
    private val runtime: GeckoRuntime
) {
    companion object {
        private const val TAG = "ResourceCollector"
        private const val EXTENSION_URI = "resource://android/assets/extensions/resource-monitor/"
        private const val NATIVE_APP_ID = "devcompanion"
        private const val MESSAGE_TIMEOUT_MS = 3000L
    }

    @Volatile
    private var extension: WebExtension? = null

    @Volatile
    private var isRegistered = false

    /** Active port for communicating with the extension's background script */
    @Volatile
    private var port: WebExtension.Port? = null

    /** Pending response completable for the current request-response cycle */
    private var pendingResponse: CompletableDeferred<JSONObject?>? = null

    /** Whether the WebExtension has been successfully registered */
    val ready: Boolean get() = isRegistered && extension != null

    /**
     * Register the WebExtension with GeckoRuntime asynchronously.
     *
     * Uses WebExtensionController.installBuiltIn() which returns GeckoResult.
     * Must be called BEFORE any GeckoSession is opened, because webRequest
     * listeners need to be in place before navigation begins.
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
                    .then({ webExtension: WebExtension? ->
                        if (webExtension != null) {
                            Log.i(TAG, "Resource monitor extension installed: ${webExtension.id}")
                            extension = webExtension
                            isRegistered = true
                            // Set up message delegate for port-based communication
                            webExtension.setMessageDelegate(
                                ResourceMessageDelegate(),
                                NATIVE_APP_ID
                            )
                        }
                        deferred.complete(webExtension)
                        null as GeckoResult<Any?>?
                    }, { throwable: Throwable ->
                        Log.e(TAG, "Failed to install resource monitor extension: ${throwable.message}")
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
     *
     * Returns an empty list if the extension is not registered or if
     * communication times out, so the caller can fall back to Performance API.
     */
    suspend fun collectResources(): List<PageResource> {
        if (!isRegistered || extension == null) {
            Log.w(TAG, "Extension not registered, returning empty resources")
            return emptyList()
        }

        val currentPort = port
        if (currentPort == null) {
            Log.w(TAG, "No active port, returning empty resources")
            return emptyList()
        }

        return try {
            val response = withTimeout(MESSAGE_TIMEOUT_MS) {
                sendPortMessage(currentPort, "getResources")
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

    // ── Port-based Messaging ───────────────────────────────

    /**
     * Send a command to the extension's background script via port and await response.
     *
     * The port was established by the extension calling browser.runtime.connect().
     * We send a command via port.postMessage() and wait for the response in
     * ResourceMessageDelegate.onPortMessage().
     */
    private suspend fun sendPortMessage(port: WebExtension.Port, command: String): JSONObject? {
        val responseDeferred = CompletableDeferred<JSONObject?>()
        pendingResponse = responseDeferred

        withContext(Dispatchers.Main) {
            try {
                val message = JSONObject().apply {
                    put("command", command)
                }
                port.postMessage(message)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send port message", e)
                responseDeferred.complete(null)
            }
        }

        return responseDeferred.await()
    }

    /**
     * Message delegate for receiving port-based messages from the WebExtension.
     *
     * The extension connects via browser.runtime.connect(NATIVE_APP_ID)
     * and we receive the port in onConnect().
     * We set a PortDelegate on the port to receive messages.
     */
    private inner class ResourceMessageDelegate : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            Log.d(TAG, "Extension connected: port=${port.name}")
            this@ResourceCollector.port = port
            // Set port delegate to receive messages from the extension
            port.setDelegate(ResourcePortDelegate())
        }

        override fun onMessage(
            nativeAppId: String,
            message: Any,
            sender: WebExtension.MessageSender
        ): GeckoResult<Any>? {
            // Handle runtime messages (from browser.runtime.onMessage/sendResponse)
            Log.d(TAG, "Received runtime message from extension")
            try {
                val json = when (message) {
                    is JSONObject -> message
                    is String -> JSONObject(message)
                    else -> {
                        Log.d(TAG, "Unexpected message type: ${message.javaClass.simpleName}")
                        null
                    }
                }
                json?.let { pendingResponse?.complete(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse extension message", e)
                pendingResponse?.complete(null)
            }
            return null
        }
    }

    /**
     * Port delegate for receiving messages sent through the port.
     */
    private inner class ResourcePortDelegate : WebExtension.PortDelegate {
        override fun onPortMessage(message: Any, port: WebExtension.Port) {
            Log.d(TAG, "Received port message from extension")
            try {
                val json = when (message) {
                    is JSONObject -> message
                    is String -> JSONObject(message)
                    else -> {
                        Log.d(TAG, "Unexpected port message type: ${message.javaClass.simpleName}")
                        null
                    }
                }
                json?.let { pendingResponse?.complete(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse port message", e)
                pendingResponse?.complete(null)
            }
        }
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