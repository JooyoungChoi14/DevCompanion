package com.devcompanion.debug

import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebView-native debugging bridge — no CDP required.
 * Captures console logs, network requests/responses, and performance metrics
 * directly from WebView callbacks.
 *
 * Implements [BrowserDebugger] for use via [BrowserDebuggerHolder].
 * Only exists in the `free` flavor source set (requires android.webkit.*).
 *
 * StateFlow-based: all event streams retain latest state for new subscribers.
 */
class WebViewDebugger : BrowserDebugger {
    companion object {
        private const val TAG = "WebViewDebugger"
        private const val MAX_CONSOLE_ITEMS = 500
        private const val MAX_NETWORK_ENTRIES = 500
        private const val INSPECTOR_JS = """(function(){
            if(document.getElementById('__devOverlay')){
                var ov=document.getElementById('__devOverlay');
                ov.style.display='block';
                return;
            }
            var overlay=document.createElement('div');
            overlay.id='__devOverlay';
            overlay.style.cssText='position:fixed;pointer-events:none;z-index:999999;border:2px solid #4CAF50;background:rgba(76,175,80,0.1);border-radius:2px;transition:all 0.15s ease;display:none;';
            document.body.appendChild(overlay);
            function getXPath(el){
                if(el.id) return '//*[@id="'+el.id+'"]';
                var parts=[];
                while(el&&el.nodeType===1){
                    var idx=1,sib=el.previousSibling;
                    while(sib){if(sib.nodeType===1&&sib.tagName===el.tagName)idx++;sib=sib.previousSibling;}
                    var tag=el.tagName.toLowerCase();
                    parts.unshift(tag+'['+idx+']');
                    el=el.parentNode;
                }
                return '/'+parts.join('/');
            }
            function getCssSelector(el){
                if(el.id) return '#'+el.id;
                var sel=el.tagName.toLowerCase();
                if(el.className&&typeof el.className==='string'){
                    var cls=el.className.trim().split(/\s+/).filter(function(c){return c;}).join('.');
                    if(cls) sel+='.'+cls;
                }
                return sel;
            }
            function handler(e){
                var target=e.target;
                if(target===overlay) return;
                // Prevent click/link navigation while inspecting
                e.preventDefault();
                e.stopPropagation();
                var rect=target.getBoundingClientRect();
                overlay.style.left=rect.left+'px';
                overlay.style.top=rect.top+'px';
                overlay.style.width=rect.width+'px';
                overlay.style.height=rect.height+'px';
                overlay.style.display='block';
                var attrs={};
                for(var i=0;i<target.attributes.length;i++){
                    var a=target.attributes[i];
                    attrs[a.name]=a.value;
                }
                var data={
                    tagName:target.tagName,
                    id:target.id||null,
                    className:target.className&&typeof target.className==='string'?target.className:null,
                    xpath:getXPath(target),
                    cssSelector:getCssSelector(target),
                    textContent:target.textContent?target.textContent.substring(0,200):null,
                    attributes:attrs,
                    boundingRect:{left:rect.left,top:rect.top,width:rect.width,height:rect.height}
                };
                if(window.__devCompanionInspector){
                    window.__devCompanionInspector.post(JSON.stringify(data));
                }
            }
            window.__devInspectorHandler=handler;
            document.addEventListener('touchstart',handler,{capture:true,passive:false});
            document.addEventListener('click',handler,true);
        })();"""
    }

    private val requestIdCounter = AtomicInteger(0)

    // WebView reference for JS evaluation
    internal var webView: WebView? = null
        private set

    // ── URL History ────────────────────────────────────────────────────
    private val _urlHistory = MutableStateFlow<List<String>>(emptyList())
    override val urlHistory: StateFlow<List<String>> = _urlHistory.asStateFlow()

    override fun addUrlToHistory(url: String) {
        _urlHistory.update { history ->
            val filtered = history.filter { it != url }
            (listOf(url) + filtered).take(50)
        }
    }

    /** Restore URL history from persistent storage (called on startup). */
    override fun restoreUrlHistory(urls: List<String>) {
        _urlHistory.update { urls }
    }

    // ── Console (unified timeline) ──────────────────────────────────────
    private val _consoleItems = MutableStateFlow<List<ConsoleItem>>(emptyList())
    override val consoleItems: StateFlow<List<ConsoleItem>> = _consoleItems.asStateFlow()

    /** Add a console log item from WebChromeClient */
    override fun addConsoleLog(level: ConsoleLevel, text: String, source: String?, line: Int?) {
        val item = ConsoleItem.Log(
            uid = nextConsoleItemId(),
            timestamp = System.currentTimeMillis(),
            level = level,
            text = text,
            source = source,
            line = line,
        )
        _consoleItems.update { (it + item).takeLast(MAX_CONSOLE_ITEMS) }
    }

    /** Add a JS input item and return its uid for linking with the result */
    fun addJsInput(expression: String): Long {
        val uid = nextConsoleItemId()
        val item = ConsoleItem.Input(
            uid = uid,
            timestamp = System.currentTimeMillis(),
            expression = expression,
        )
        _consoleItems.update { (it + item).takeLast(MAX_CONSOLE_ITEMS) }
        return uid
    }

    /** Add a JS result item linked to the input */
    fun addJsResult(inputUid: Long, expression: String, result: JsEvalResult) {
        val item = ConsoleItem.Result(
            uid = nextConsoleItemId(),
            timestamp = System.currentTimeMillis(),
            inputUid = inputUid,
            expression = expression,
            evalResult = result,
        )
        _consoleItems.update { (it + item).takeLast(MAX_CONSOLE_ITEMS) }
    }

    /** Clear all console items */
    override fun clearConsole() {
        _consoleItems.value = emptyList()
    }

    // ── JS Evaluation ───────────────────────────────────────────────────

    /**
     * Evaluate JavaScript in the WebView.
     * Uses JSON-based transport to avoid delimiter collision issues.
     * Automatically adds Input and Result items to the console timeline.
     */
    override fun evaluateJs(expression: String) {
        val wv = webView
        if (wv == null) {
            val inputUid = addJsInput(expression)
            addJsResult(inputUid, expression, JsEvalResult(false, "WebView not available", "error"))
            return
        }

        // Add Input item to timeline immediately
        val inputUid = addJsInput(expression)

        // Escape expression for safe embedding in a JS string literal
        val safeExpr = expression
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\u0000", "\\0")

        // JS eval: returns JSON object {t: type, v: value} — no delimiter collision
        val evalJs = """
            (function(){
                try {
                    var r = eval("$safeExpr");
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

        wv.post {
            try {
                wv.evaluateJavascript(evalJs) { raw ->
                    val result = parseEvalResult(raw)
                    addJsResult(inputUid, expression, result)
                }
            } catch (e: Exception) {
                addJsResult(inputUid, expression, JsEvalResult(false, e.message ?: "Error", "error"))
            }
        }
    }

    /**
     * Parse the evaluateJavascript callback result.
     * The callback wraps string results in JSON quotes; we strip those first,
     * then parse the JSON object {t, v}.
     */
    private fun parseEvalResult(raw: String?): JsEvalResult {
        if (raw == null || raw == "null") {
            return JsEvalResult(success = true, value = "undefined", type = "undefined")
        }

        return try {
            // Strip evaluateJavascript's JSON quoting of the string result
            val json = if (raw.startsWith("\"") && raw.endsWith("\"")) {
                raw.removeSurrounding("\"")
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\t", "\t")
            } else {
                raw
            }

            val type = object : TypeToken<Map<String, String>>() {}.type
            val map: Map<String, String> = Gson().fromJson(json, type)
            val t = map["t"] ?: "unknown"
            val v = map["v"] ?: ""

            when (t) {
                "undefined" -> JsEvalResult(success = true, value = "undefined", type = "undefined")
                "null" -> JsEvalResult(success = true, value = "null", type = "null")
                "error" -> JsEvalResult(success = false, value = v, type = "error")
                else -> JsEvalResult(success = true, value = v, type = t, rawJson = if (t == "object") v else null)
            }
        } catch (e: Exception) {
            JsEvalResult(success = true, value = raw, type = "raw")
        }
    }

    internal fun attachWebView(wv: WebView) {
        webView = wv
    }

    internal fun detachWebView() {
        webView = null
    }

    // ── Network (StateFlow-based) ──────────────────────────────────────
    private val _networkEntries = MutableStateFlow<Map<String, NetworkEntry>>(emptyMap())
    override val networkEntries: StateFlow<Map<String, NetworkEntry>> = _networkEntries.asStateFlow()

    // Pending requests for duration tracking
    private val pendingRequests = mutableMapOf<String, Long>()

    /** Clear all network entries */
    override fun clearNetwork() {
        _networkEntries.value = emptyMap()
        pendingRequests.clear()
    }

    // ── Performance (StateFlow-based) ──────────────────────────────────
    private val _performanceMetrics = MutableStateFlow<List<PerformanceMetric>>(emptyList())
    override val performanceMetrics: StateFlow<List<PerformanceMetric>> = _performanceMetrics.asStateFlow()

    override fun emitMetric(metric: PerformanceMetric) {
        _performanceMetrics.update { (it + metric).takeLast(100) }
    }

    /**
     * Inject JS to collect performance data (heap, DOM, FPS) from the WebView.
     * Called after page load and on demand from PerformanceTab.
     */
    override fun collectPerformanceData() {
        val wv = webView ?: return
        val js = """
            (function(){
                var data = {
                    jsHeapUsed: 0,
                    jsHeapTotal: 0,
                    domNodes: document.querySelectorAll('*').length
                };
                if (window.performance && window.performance.memory) {
                    data.jsHeapUsed = window.performance.memory.usedJSHeapSize / 1048576;
                    data.jsHeapTotal = window.performance.memory.totalJSHeapSize / 1048576;
                }
                if (window.__devCompanionPerf) {
                    window.__devCompanionPerf.post(JSON.stringify(data));
                }
            })()
        """.trimIndent()
        wv.post { wv.evaluateJavascript(js, null) }
    }

    /**
     * Called from JavascriptInterface when performance JS sends data.
     */
    fun onPerformanceResult(json: String) {
        try {
            val obj = JSONObject(json)
            val existing = _performanceMetrics.value.lastOrNull()
            // Update last metric with heap/DOM data if it was a load metric
            val updated = existing?.copy(
                jsHeapUsed = obj.optDouble("jsHeapUsed", 0.0).toFloat(),
                jsHeapTotal = obj.optDouble("jsHeapTotal", 0.0).toFloat(),
                domNodes = obj.optInt("domNodes", 0),
            )
            if (updated != null && updated != existing) {
                _performanceMetrics.update { list ->
                    list.dropLast(1) + updated
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to parse performance result: $e")
        }
    }

    // ── WebChromeClient for console ────────────────────────────────────

    inner class DebugChromeClient : WebChromeClient() {
        override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
            message?.let {
                val level = when (it.messageLevel()) {
                    ConsoleMessage.MessageLevel.WARNING -> ConsoleLevel.Warn
                    ConsoleMessage.MessageLevel.ERROR -> ConsoleLevel.Error
                    ConsoleMessage.MessageLevel.DEBUG -> ConsoleLevel.Debug
                    ConsoleMessage.MessageLevel.LOG -> ConsoleLevel.Log
                    else -> ConsoleLevel.Info
                }
                addConsoleLog(
                    level = level,
                    text = "${it.message()} [${it.sourceId()}:${it.lineNumber()}]",
                    source = it.sourceId(),
                    line = it.lineNumber(),
                )
            }
            return true
        }
    }

    // ── WebViewClient for network ──────────────────────────────────────

    inner class DebugWebViewClient : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val requestId = requestIdCounter.incrementAndGet().toString()
            val url = request.url.toString()
            val method = request.method

            pendingRequests[requestId] = System.currentTimeMillis()

            _networkEntries.update { map ->
                map + (requestId to NetworkEntry(
                    request = NetworkRequest(
                        requestId = requestId,
                        url = url,
                        method = method,
                        headers = request.requestHeaders ?: emptyMap(),
                        timestamp = System.currentTimeMillis()
                    )
                ))
            }

            // Don't actually intercept, just observe
            return null
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            // Emit basic load metric
            val pageStart = pendingRequests.remove("__page_start__")
            if (pageStart != null) {
                emitMetric(PerformanceMetric(
                    loadTimeMs = System.currentTimeMillis() - pageStart,
                ))
            }
            // Inject performance data collection after page loads
            collectPerformanceData()
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            request?.let {
                val requestId = requestIdCounter.incrementAndGet().toString()
                val failure = NetworkLoadFailure(
                    requestId = requestId,
                    url = it.url.toString(),
                    errorCode = error?.errorCode ?: -1,
                    description = error?.description?.toString() ?: "Unknown error",
                    timestamp = System.currentTimeMillis()
                )

                // Match to existing request by URL
                _networkEntries.update { map ->
                    val existing = map.values.find { entry ->
                        entry.request.url == it.url.toString() && entry.failure == null
                    }
                    if (existing != null) {
                        map + (existing.request.requestId to existing.copy(
                            failure = failure,
                            completedAt = failure.timestamp
                        ))
                    } else {
                        map + (requestId to NetworkEntry(request = NetworkRequest(
                            requestId = requestId,
                            url = it.url.toString(),
                            timestamp = System.currentTimeMillis()
                        ), failure = failure, completedAt = failure.timestamp))
                    }
                }
            }
        }
    }

    /** Track a network response — called when we can match by requestId */
    fun trackResponse(requestId: String, statusCode: Int, headers: Map<String, String> = emptyMap()) {
        val startTime = pendingRequests.remove(requestId) ?: System.currentTimeMillis()
        val duration = System.currentTimeMillis() - startTime

        val response = NetworkResponse(
            requestId = requestId,
            url = "", // Will be filled from existing entry
            statusCode = statusCode,
            headers = headers,
            timestamp = System.currentTimeMillis(),
            durationMs = duration
        )

        _networkEntries.update { map ->
            val existing = map[requestId]
            if (existing != null) {
                map + (requestId to existing.copy(
                    response = response.copy(url = existing.request.url),
                    completedAt = response.timestamp
                ))
            } else map
        }
    }

    /** Track HTTP error (4xx/5xx from server) — matched by URL since WebView doesn't give requestId for HTTP errors */
    override fun trackHttpError(url: String, statusCode: Int, reasonPhrase: String) {
        val now = System.currentTimeMillis()
        _networkEntries.update { map ->
            // Find existing pending entry for this URL that doesn't have a response yet
            val existing = map.values.find { it.request.url == url && it.response == null && it.failure == null }
            if (existing != null) {
                val response = NetworkResponse(
                    requestId = existing.request.requestId,
                    url = url,
                    statusCode = statusCode,
                    headers = emptyMap(),
                    timestamp = now,
                    durationMs = now - existing.request.timestamp
                )
                map + (existing.request.requestId to existing.copy(
                    response = response,
                    completedAt = now
                ))
            } else {
                // No matching request — create a standalone entry
                val requestId = requestIdCounter.incrementAndGet().toString()
                val request = NetworkRequest(
                    requestId = requestId,
                    url = url,
                    timestamp = now
                )
                val response = NetworkResponse(
                    requestId = requestId,
                    url = url,
                    statusCode = statusCode,
                    headers = emptyMap(),
                    timestamp = now,
                    durationMs = 0
                )
                map + (requestId to NetworkEntry(request = request, response = response, completedAt = now))
            }
        }
    }

    /** Mark page load start for performance tracking */
    override fun markPageStart() {
        pendingRequests["__page_start__"] = System.currentTimeMillis()
    }

    // ── Inspector mode ──────────────────────────────────────────────────

    private val _inspectorTarget = MutableStateFlow<InspectorTarget?>(null)
    override val inspectorTarget: StateFlow<InspectorTarget?> = _inspectorTarget.asStateFlow()

    private var _inspectorEnabled = false
    override val inspectorEnabled: Boolean
        get() = _inspectorEnabled

    /**
     * Enable Inspector mode: injects JS to highlight tapped elements
     * and send element info back via __devCompanionInspector bridge.
     */
    override fun enableInspector() {
        _inspectorEnabled = true
        val wv = webView ?: return
        wv.post {
            wv.evaluateJavascript(INSPECTOR_JS, null)
        }
    }

    /**
     * Disable Inspector mode: removes overlay and event listener from WebView.
     */
    override fun disableInspector() {
        _inspectorEnabled = false
        _inspectorTarget.value = null
        val wv = webView ?: return
        wv.post {
            wv.evaluateJavascript("""
                (function(){
                    var ov = document.getElementById('__devOverlay');
                    if(ov) ov.remove();
                    document.removeEventListener('touchstart', window.__devInspectorHandler, true);
                    document.removeEventListener('click', window.__devInspectorHandler, true);
                    window.__devInspectorHandler = null;
                    // Remove from all iframes
                    var iframes = document.querySelectorAll('iframe');
                    for(var i=0;i<iframes.length;i++){
                        try{
                            var doc=iframes[i].contentDocument||iframes[i].contentWindow.document;
                            if(!doc) continue;
                            var iov=doc.getElementById('__devOverlay');
                            if(iov) iov.remove();
                            doc.removeEventListener('touchstart', window.__devInspectorHandler, true);
                            doc.removeEventListener('click', window.__devInspectorHandler, true);
                        }catch(e){}
                    }
                })();
            """.trimIndent(), null)
        }
    }

    /**
     * Called from WebView JavascriptInterface when inspector JS sends element data.
     */
    fun onInspectorResult(json: String) {
        try {
            val obj = JSONObject(json)
            val attrsObj = obj.optJSONObject("attributes")
            val attributes = if (attrsObj != null) {
                val keys = attrsObj.keys()
                val map = mutableMapOf<String, String>()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = attrsObj.getString(key)
                }
                map
            } else emptyMap()

            val rectObj = obj.optJSONObject("boundingRect")
            val boundingRect = if (rectObj != null) {
                BoundingRect(
                    left = rectObj.optDouble("left", 0.0).toFloat(),
                    top = rectObj.optDouble("top", 0.0).toFloat(),
                    width = rectObj.optDouble("width", 0.0).toFloat(),
                    height = rectObj.optDouble("height", 0.0).toFloat(),
                )
            } else null

            _inspectorTarget.value = InspectorTarget(
                tagName = obj.getString("tagName"),
                id = obj.optString("id").takeIf { it != "" && it != "null" },
                className = obj.optString("className").takeIf { it != "" && it != "null" },
                xpath = obj.getString("xpath"),
                cssSelector = obj.getString("cssSelector"),
                textContent = obj.optString("textContent").takeIf { it != "" && it != "null" },
                attributes = attributes,
                boundingRect = boundingRect,
            )
        } catch (e: Exception) {
            // Malformed JSON — ignore
        }
    }
}