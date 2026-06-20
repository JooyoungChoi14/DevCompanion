package com.devcompanion.bridge

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.devcompanion.engine.BrowserEngine
import com.devcompanion.engine.JsUtils
import com.devcompanion.debug.ConsoleLevel
import com.devcompanion.debug.BrowserDebuggerHolder
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream

/**
 * Lightweight HTTP bridge server for AI agent control.
 *
 * Provides REST API endpoints for:
 * - JS evaluation (/eval)
 * - Console log access (/console)
 * - Network event access (/network)
 * - DOM snapshot (/dom)
 * - Navigation (/navigate)
 * - Screenshot capture (/screenshot)
 * - Server info (/info)
 *
 * Security: Bearer token authentication, local network binding.
 */
class BridgeServer(
    val port: Int = DEFAULT_PORT,
    private val authToken: String
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "BridgeServer"
        const val DEFAULT_PORT = 8765
        private val gson = Gson()
    }

    private var engine: BrowserEngine? = null

    /**
     * Attach a BrowserEngine for JS evaluation, screenshots, and navigation.
     * Works with both WebViewEngine and GeckoEngine.
     */
    fun attachEngine(eng: BrowserEngine?) {
        engine = eng
    }

    override fun serve(session: IHTTPSession): Response {
        // ── Auth check ──────────────────────────────────────────────
        val authHeader = session.headers["authorization"] ?: ""
        val token = if (authHeader.startsWith("Bearer ", ignoreCase = true)) {
            authHeader.removePrefix("Bearer ").removePrefix("bearer ").trim()
        } else {
            session.parms["token"] ?: ""
        }

        if (token != authToken) {
            return jsonResponse(401, mapOf("error" to "Unauthorized", "hint" to "Provide Bearer token or ?token= parameter"))
        }

        // ── Route ───────────────────────────────────────────────────
        val uri = session.uri.trimEnd('/')
        val method = session.method

        return try {
            when {
                // ── Server info ───────────────────────────────────
                uri == "/info" && method == Method.GET -> handleInfo()

                // ── Evaluate JS ──────────────────────────────────
                uri == "/eval" && method == Method.POST -> handleEval(session)

                // ── Console logs ─────────────────────────────────
                uri == "/console" && method == Method.GET -> handleConsole(session)

                // ── Network events ──────────────────────────────
                uri == "/network" && method == Method.GET -> handleNetwork(session)

                // ── DOM snapshot ────────────────────────────────
                uri == "/dom" && method == Method.GET -> handleDom(session)

                // ── Navigate ────────────────────────────────────
                uri == "/navigate" && method == Method.POST -> handleNavigate(session)

                // ── Screenshot ──────────────────────────────────
                uri == "/screenshot" && method == Method.GET -> handleScreenshot()

                // ── Performance metrics ─────────────────────────
                uri == "/perf" && method == Method.GET -> handlePerf()

                // ── Inspector ───────────────────────────────────
                uri == "/inspector" && method == Method.POST -> handleInspector(session)

                // ── 404 ──────────────────────────────────────────
                else -> jsonResponse(404, mapOf("error" to "Not found", "path" to uri))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling request: ${session.uri}", e)
            jsonResponse(500, mapOf("error" to (e.message ?: "Internal error")))
        }
    }

    // ── Handlers ────────────────────────────────────────────────────

    private fun handleInfo(): Response {
        val debugger = BrowserDebuggerHolder.current
        return jsonResponse(200, mapOf(
            "name" to "DevCompanion Bridge",
            "version" to "1.0.0",
            "debugger" to (debugger != null),
            "port" to port,
            "endpoints" to listOf(
                "GET  /info — Server info",
                "POST /eval — Evaluate JS expression (body: {expression: string})",
                "GET  /console — Console logs (?level=Error|Warn&limit=50)",
                "GET  /network — Network events (?limit=50)",
                "GET  /dom — DOM snapshot (?selector=body)",
                "POST /navigate — Navigate to URL (body: {url: string})",
                "GET  /screenshot — Browser engine screenshot (Base64 PNG)",
                "GET  /perf — Performance metrics",
                "POST /inspector — Toggle inspector (body: {enable: boolean})"
            )
        ))
    }

    private fun handleEval(session: IHTTPSession): Response {
        val body = readBody(session) ?: return jsonResponse(400, mapOf("error" to "Missing body"))
        val map: Map<String, String> = gson.fromJson(body, object : TypeToken<Map<String, String>>() {}.type)
        val expression = map["expression"] ?: return jsonResponse(400, mapOf("error" to "Missing 'expression' field"))

        val debugger = BrowserDebuggerHolder.current
            ?: return jsonResponse(503, mapOf("error" to "No debugger active — open a browser tab first"))

        val eng = engine
            ?: return jsonResponse(503, mapOf("error" to "No browser engine attached"))

        // Evaluate JS on UI thread, wait for result via CompletableDeferred
        val deferred = CompletableDeferred<String>()

        eng.view.post {
            try {
                // Use JsUtils.escapeJsString for consistent, safe JS string escaping
                // (includes quotes, so we embed directly: eval("expression") becomes eval(escaped))
                val evalJs = """
                    (function(){
                        try {
                            var r = eval(${JsUtils.escapeJsString(expression)});
                            if (r === undefined) return JSON.stringify({t:"undefined",v:null});
                            if (r === null) return JSON.stringify({t:"null",v:null});
                            var t = typeof r;
                            var v;
                            if (t === 'object') {
                                try { v = JSON.stringify(r, null, 2); }
                                catch(e) { v = String(r); }
                            } else if (t === 'function') {
                                v = String(r).substring(0, 1000);
                            } else {
                                v = String(r);
                            }
                            return JSON.stringify({t:t,v:v});
                        } catch(e) {
                            return JSON.stringify({t:"error",v:e.message||String(e)});
                        }
                    })()
                """.trimIndent()

                eng.evaluateJavascript(evalJs) { raw ->
                    deferred.complete(raw)
                }
            } catch (e: Exception) {
                deferred.completeExceptionally(e)
            }
        }

        // Wait for result (with 10s timeout)
        val result = runBlocking {
            withTimeoutOrNull(10_000L) { deferred.await() }
        }

        if (result == null) {
            // Check if it was an exception
            val exc = runCatching { deferred.getCompleted() }.getOrNull()
            if (deferred.isCancelled) {
                return jsonResponse(500, mapOf("error" to "Evaluation cancelled"))
            }
            return jsonResponse(504, mapOf("error" to "Evaluation timed out"))
        }

        return newFixedLengthResponse(Response.Status.OK, "application/json", result)
    }

    private fun handleConsole(session: IHTTPSession): Response {
        val debugger = BrowserDebuggerHolder.current
            ?: return jsonResponse(503, mapOf("error" to "No debugger active"))

        val params = session.parms
        val levelFilter = params["level"]
        val limit = (params["limit"] ?: "100").toIntOrNull()?.coerceIn(1, 500) ?: 100

        val items = debugger.consoleItems.value
        val filtered = if (levelFilter != null) {
            val level = try { ConsoleLevel.valueOf(levelFilter) } catch (_: Exception) { null }
            items.filter {
                it is com.devcompanion.debug.ConsoleItem.Log && (level == null || it.level == level)
            }
        } else {
            items
        }.takeLast(limit)

        return jsonResponse(200, mapOf(
            "count" to filtered.size,
            "total" to items.size,
            "items" to filtered.map { item ->
                when (item) {
                    is com.devcompanion.debug.ConsoleItem.Input -> mapOf(
                        "type" to "input",
                        "uid" to item.uid,
                        "timestamp" to item.timestamp,
                        "expression" to item.expression
                    )
                    is com.devcompanion.debug.ConsoleItem.Log -> mapOf(
                        "type" to "log",
                        "uid" to item.uid,
                        "timestamp" to item.timestamp,
                        "level" to item.level.name,
                        "text" to item.text,
                        "source" to item.source,
                        "line" to item.line
                    )
                    is com.devcompanion.debug.ConsoleItem.Result -> mapOf(
                        "type" to "result",
                        "uid" to item.uid,
                        "timestamp" to item.timestamp,
                        "inputUid" to item.inputUid,
                        "expression" to item.expression,
                        "success" to item.evalResult.success,
                        "value" to item.evalResult.value,
                        "resultType" to item.evalResult.type
                    )
                }
            }
        ))
    }

    private fun handleNetwork(session: IHTTPSession): Response {
        val debugger = BrowserDebuggerHolder.current
            ?: return jsonResponse(503, mapOf("error" to "No debugger active"))

        val params = session.parms
        val limit = (params["limit"] ?: "100").toIntOrNull()?.coerceIn(1, 500) ?: 100

        val entries = debugger.networkEntries.value.values
            .sortedByDescending { it.request.timestamp }
            .take(limit)

        return jsonResponse(200, mapOf(
            "count" to entries.size,
            "total" to debugger.networkEntries.value.size,
            "entries" to entries.map { entry ->
                mapOf(
                    "requestId" to entry.request.requestId,
                    "url" to entry.request.url,
                    "method" to entry.request.method,
                    "timestamp" to entry.request.timestamp,
                    "response" to (entry.response?.let { resp ->
                        mapOf(
                            "statusCode" to resp.statusCode,
                            "durationMs" to entry.durationMs,
                            "headers" to resp.headers
                        )
                    }),
                    "failure" to (entry.failure?.let { fail ->
                        mapOf(
                            "errorCode" to fail.errorCode,
                            "description" to fail.description
                        )
                    }),
                    "statusDisplay" to entry.statusDisplay
                )
            }
        ))
    }

    private fun handleDom(session: IHTTPSession): Response {
        val eng = engine
            ?: return jsonResponse(503, mapOf("error" to "No browser engine attached"))

        val params = session.parms
        val selector = params["selector"] ?: "body"

        // DOM snapshot via CompletableDeferred — per-endpoint, no shared monitor
        val deferred = CompletableDeferred<String>()

        eng.view.post {
            try {
                val escapedSelector = JsUtils.escapeJsString(selector)
                val js = """
                    (function(){
                        try {
                            var el = document.querySelector($escapedSelector);
                            if (!el) return JSON.stringify({error:'Element not found', selector: $escapedSelector});
                            return JSON.stringify({
                                selector: $escapedSelector,
                                outerHTML: el.outerHTML.substring(0, 50000),
                                textContent: el.textContent ? el.textContent.substring(0, 5000) : null,
                                tagName: el.tagName,
                                id: el.id || null,
                                className: el.className || null,
                                childCount: el.children.length
                            });
                        } catch(e) {
                            return JSON.stringify({error: e.message});
                        }
                    })()
                """.trimIndent()

                eng.evaluateJavascript(js) { raw ->
                    deferred.complete(raw)
                }
            } catch (e: Exception) {
                deferred.completeExceptionally(e)
            }
        }

        val result = runBlocking {
            withTimeoutOrNull(5_000L) { deferred.await() }
        }

        if (result == null) {
            if (deferred.isCancelled) {
                return jsonResponse(500, mapOf("error" to "DOM snapshot cancelled"))
            }
            return jsonResponse(504, mapOf("error" to "DOM snapshot timed out"))
        }

        return newFixedLengthResponse(Response.Status.OK, "application/json", result)
    }

    private fun handleNavigate(session: IHTTPSession): Response {
        val body = readBody(session) ?: return jsonResponse(400, mapOf("error" to "Missing body"))
        val map: Map<String, String> = gson.fromJson(body, object : TypeToken<Map<String, String>>() {}.type)
        val url = map["url"] ?: return jsonResponse(400, mapOf("error" to "Missing 'url' field"))

        val eng = engine
            ?: return jsonResponse(503, mapOf("error" to "No browser engine attached"))

        eng.view.post {
            eng.loadUrl(url)
        }

        return jsonResponse(200, mapOf("status" to "navigating", "url" to url))
    }

    private fun handleScreenshot(): Response {
        val eng = engine
            ?: return jsonResponse(503, mapOf("error" to "No browser engine attached"))

        val deferred = CompletableDeferred<String>()

        eng.view.post {
            try {
                val w = eng.viewportWidth().coerceAtLeast(1)
                val h = eng.viewportHeight().coerceAtLeast(1)
                val bitmap = android.graphics.Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                eng.view.draw(canvas)

                val stream = java.io.ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
                val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

                bitmap.recycle()

                val json = """{"width":${w},"height":${h},"format":"png","base64":"${base64.replace("\\", "\\\\")}"}"""
                deferred.complete(json)
            } catch (e: Exception) {
                deferred.completeExceptionally(e)
            }
        }

        val result = runBlocking {
            withTimeoutOrNull(5_000L) { deferred.await() }
        }

        if (result == null) {
            if (deferred.isCancelled) {
                return jsonResponse(500, mapOf("error" to "Screenshot cancelled"))
            }
            return jsonResponse(504, mapOf("error" to "Screenshot timed out"))
        }

        return newFixedLengthResponse(Response.Status.OK, "application/json", result)
    }

    private fun handlePerf(): Response {
        val debugger = BrowserDebuggerHolder.current
            ?: return jsonResponse(503, mapOf("error" to "No debugger active"))

        val metrics = debugger.performanceMetrics.value

        return jsonResponse(200, mapOf(
            "count" to metrics.size,
            "latest" to (metrics.lastOrNull()?.let { m ->
                mapOf(
                    "timestamp" to m.timestamp,
                    "fps" to m.fps,
                    "jsHeapUsed" to m.jsHeapUsed,
                    "jsHeapTotal" to m.jsHeapTotal,
                    "domNodes" to m.domNodes,
                    "loadTimeMs" to m.loadTimeMs
                )
            }),
            "history" to metrics.takeLast(20).map { m ->
                mapOf(
                    "timestamp" to m.timestamp,
                    "fps" to m.fps,
                    "jsHeapUsed" to m.jsHeapUsed,
                    "jsHeapTotal" to m.jsHeapTotal,
                    "domNodes" to m.domNodes,
                    "loadTimeMs" to m.loadTimeMs
                )
            }
        ))
    }

    private fun handleInspector(session: IHTTPSession): Response {
        val body = readBody(session) ?: return jsonResponse(400, mapOf("error" to "Missing body"))
        val map: Map<String, String> = gson.fromJson(body, object : TypeToken<Map<String, String>>() {}.type)
        val enable = map["enable"]?.toBoolean() ?: (map["action"] == "enable")

        val debugger = BrowserDebuggerHolder.current
            ?: return jsonResponse(503, mapOf("error" to "No debugger active"))

        if (enable) debugger.enableInspector() else debugger.disableInspector()

        return jsonResponse(200, mapOf(
            "inspector" to if (enable) "enabled" else "disabled",
            "active" to debugger.inspectorEnabled
        ))
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun readBody(session: IHTTPSession): String? {
        return try {
            val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
            if (contentLength > 1_000_000L) return null // 1MB limit

            val body = session.inputStream.bufferedReader().readText()
            // NanoHTTPD needs parms to be parsed for POST
            val files = HashMap<String, String>()
            session.parseBody(files)
            files["postData"] ?: body
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read body", e)
            null
        }
    }

    private fun jsonResponse(status: Int, data: Any): Response {
        val json = gson.toJson(data)
        val statusEnum = when (status) {
            200 -> Response.Status.OK
            400 -> Response.Status.BAD_REQUEST
            401 -> Response.Status.UNAUTHORIZED
            404 -> Response.Status.NOT_FOUND
            503 -> Response.Status.SERVICE_UNAVAILABLE
            else -> if (status < 400) Response.Status.OK else Response.Status.INTERNAL_ERROR
        }
        return newFixedLengthResponse(statusEnum, "application/json", json)
    }

    /**
     * Start the server and return the auth token for display.
     * Binds to all interfaces (0.0.0.0) for network access.
     */
    fun startServer(): String {
        start()
        Log.i(TAG, "Bridge server started on port $port")
        return authToken
    }

    fun stopServer() {
        stop()
        Log.i(TAG, "Bridge server stopped")
    }
}