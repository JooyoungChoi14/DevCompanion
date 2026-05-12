package com.devcompanion.llm.agent

import android.util.Log
import android.webkit.WebView
import com.devcompanion.llm.CaptureMode
import com.devcompanion.llm.WebContextBuilder
import com.devcompanion.llm.evalJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Executes tool calls on the WebView.
 *
 * All WebView operations are dispatched to the Main thread via
 * [withContext(Dispatchers.Main)][withContext] to avoid
 * [CalledFromWrongThreadException].
 *
 * Uses [WebView.evalJs] for async JavaScript execution with coroutine support.
 */
class ToolExecutor(
) {

    companion object {
        private const val TAG = "ToolExecutor"
    }

    /**
     * Execute a tool call on the given WebView.
     *
     * @param call The tool call to execute.
     * @param webView The WebView to operate on.
     * @return A [ToolResult] with the output or error.
     */
    suspend fun execute(call: ToolCall, webView: WebView): ToolResult {
        return try {
            when (call.name) {
                "navigate" -> executeNavigate(call, webView)
                "click" -> executeClick(call, webView)
                "type" -> executeType(call, webView)
                "scroll" -> executeScroll(call, webView)
                "eval_js" -> executeEval(call, webView)
                "get_dom" -> executeGetDom(call, webView)
                "get_computed_style" -> executeGetComputedStyle(call, webView)
                "set_style" -> executeSetStyle(call, webView)
                "screenshot" -> executeScreenshot(call, webView)
                "submit_form" -> executeSubmit(call, webView)
                "get_console_logs" -> executeGetConsoleLogs(call, webView)
                else -> ToolResult(call.id, "Unknown tool: ${call.name}", isError = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tool execution failed: ${call.name}", e)
            ToolResult(call.id, "Error: ${e.message}", isError = true)
        }
    }

    // ── Tool implementations ────────────────────────────────────────────

    private suspend fun executeNavigate(call: ToolCall, webView: WebView): ToolResult {
        val url = call.arguments.getAsJsonPrimitive("url")?.asString
            ?: return ToolResult(call.id, "Missing 'url' parameter", isError = true)

        // URL whitelist: only http and https
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult(
                call.id,
                "Blocked: Only http:// and https:// URLs are allowed. Received: $url",
                isError = true
            )
        }

        return withContext(Dispatchers.Main) {
            webView.loadUrl(url)
            ToolResult(call.id, "Navigated to $url")
        }
    }

    private suspend fun executeClick(call: ToolCall, webView: WebView): ToolResult {
        val selector = call.arguments.getAsJsonPrimitive("selector")?.asString
            ?: return ToolResult(call.id, "Missing 'selector' parameter", isError = true)

        val js = """
            (function(){
                var el = document.querySelector(${escapeJsString(selector)});
                if (!el) return JSON.stringify({success:false, error:'Element not found', selector:${escapeJsString(selector)}});
                el.click();
                return JSON.stringify({success:true, tagName:el.tagName, text:el.textContent?el.textContent.substring(0,50):''});
            })()
        """.trimIndent()

        val result = webView.evalJs(js)
        return ToolResult(call.id, result)
    }

    private suspend fun executeType(call: ToolCall, webView: WebView): ToolResult {
        val selector = call.arguments.getAsJsonPrimitive("selector")?.asString
            ?: return ToolResult(call.id, "Missing 'selector' parameter", isError = true)
        val text = call.arguments.getAsJsonPrimitive("text")?.asString
            ?: return ToolResult(call.id, "Missing 'text' parameter", isError = true)
        val clear = call.arguments.getAsJsonPrimitive("clear")?.asBoolean ?: true

        val js = """
            (function(){
                var el = document.querySelector(${escapeJsString(selector)});
                if (!el) return JSON.stringify({success:false, error:'Element not found'});
                if ($clear) el.value = '';
                el.value = ${escapeJsString(text)};
                el.dispatchEvent(new Event('input', {bubbles:true}));
                el.dispatchEvent(new Event('change', {bubbles:true}));
                return JSON.stringify({success:true, value:el.value?el.value.substring(0,50):''});
            })()
        """.trimIndent()

        val result = webView.evalJs(js)
        return ToolResult(call.id, result)
    }

    private suspend fun executeScroll(call: ToolCall, webView: WebView): ToolResult {
        val direction = call.arguments.getAsJsonPrimitive("direction")?.asString ?: "down"
        val amount = call.arguments.getAsJsonPrimitive("amount")?.asInt ?: 300

        val (x, y) = when (direction.lowercase()) {
            "up" -> 0 to -amount
            "down" -> 0 to amount
            "left" -> -amount to 0
            "right" -> amount to 0
            else -> 0 to amount
        }

        val js = "window.scrollBy($x, $y); JSON.stringify({success:true,scrolled:{x:$x,y:$y}})"

        val result = webView.evalJs(js)
        return ToolResult(call.id, result)
    }

    private suspend fun executeEval(call: ToolCall, webView: WebView): ToolResult {
        val expression = call.arguments.getAsJsonPrimitive("expression")?.asString
            ?: return ToolResult(call.id, "Missing 'expression' parameter", isError = true)

        // PermissionGate has already validated this expression
        val js = """
            (function(){
                try {
                    var r = eval(${escapeJsString(expression)});
                    if (r === undefined) return JSON.stringify({t:"undefined",v:null});
                    if (r === null) return JSON.stringify({t:"null",v:null});
                    var t = typeof r;
                    var v;
                    if (t === 'object') {
                        try { v = JSON.stringify(r, null, 2); }
                        catch(e) { v = String(r); }
                    } else {
                        v = String(r);
                    }
                    return JSON.stringify({t:t,v:v.substring(0,5000)});
                } catch(e) {
                    return JSON.stringify({t:"error",v:e.message||String(e)});
                }
            })()
        """.trimIndent()

        val result = webView.evalJs(js)
        return ToolResult(call.id, result)
    }

    private suspend fun executeGetDom(call: ToolCall, webView: WebView): ToolResult {
        val selector = call.arguments.getAsJsonPrimitive("selector")?.asString ?: "body"

        val js = """
            (function(){
                var el = document.querySelector(${escapeJsString(selector)});
                if (!el) return JSON.stringify({error:'not found', selector:${escapeJsString(selector)}});
                
                // Mask sensitive fields
                var html = el.outerHTML;
                html = html.replace(/(<input[^>]*type=["']password["'][^>]*?value=)["'][^"']*["']/gi, '$1"***"');
                html = html.replace(/(<input[^>]*?value=)["'][^"']*["']([^>]*?type=["']hidden["'])/gi, '$1"***"$2');
                
                return JSON.stringify({
                    tagName: el.tagName,
                    outerHTML: html.substring(0, 10000),
                    textContent: el.textContent ? el.textContent.substring(0, 2000) : null,
                    childCount: el.children.length
                });
            })()
        """.trimIndent()

        val result = webView.evalJs(js)
        return ToolResult(call.id, result)
    }

    private suspend fun executeScreenshot(call: ToolCall, webView: WebView): ToolResult {
        return withContext(Dispatchers.Main) {
            try {
                val context = WebContextBuilder.buildContext(webView, CaptureMode.Quick)
                // Return metadata only (base64 is too large for tool result)
                ToolResult(call.id, "Screenshot captured: ${context.url}, ${context.screenshotMimeType}, available in context")
            } catch (e: Exception) {
                ToolResult(call.id, "Screenshot failed: ${e.message}", isError = true)
            }
        }
    }

    private suspend fun executeSubmit(call: ToolCall, webView: WebView): ToolResult {
        val selector = call.arguments.getAsJsonPrimitive("selector")?.asString
            ?: return ToolResult(call.id, "Missing 'selector' parameter", isError = true)

        val js = """
            (function(){
                var form = document.querySelector(${escapeJsString(selector)});
                if (!form) return JSON.stringify({success:false, error:'Form not found'});
                if (form.tagName !== 'FORM') return JSON.stringify({success:false, error:'Element is not a form'});
                form.submit();
                return JSON.stringify({success:true, action:form.action, method:form.method});
            })()
        """.trimIndent()

        val result = webView.evalJs(js)
        return ToolResult(call.id, result)
    }

    private suspend fun executeGetConsoleLogs(call: ToolCall, webView: WebView): ToolResult {
        val limit = call.arguments.getAsJsonPrimitive("limit")?.asInt ?: 50
        val level = call.arguments.getAsJsonPrimitive("level")?.asString ?: "all"

        // Intercept console methods and collect logs
        val js = """
            (function(){
                if (!window.__devCompanionLogs) {
                    window.__devCompanionLogs = [];
                    var origConsole = {
                        log: console.log.bind(console),
                        warn: console.warn.bind(console),
                        error: console.error.bind(console),
                        info: console.info.bind(console)
                    };
                    ['log','warn','error','info'].forEach(function(lvl){
                        console[lvl] = function(){
                            origConsole[lvl].apply(console, arguments);
                            try {
                                window.__devCompanionLogs.push({
                                    level: lvl,
                                    message: Array.prototype.slice.call(arguments).map(function(a){
                                        return typeof a === 'object' ? JSON.stringify(a) : String(a);
                                    }).join(' '),
                                    ts: Date.now()
                                });
                                if (window.__devCompanionLogs.length > 500) window.__devCompanionLogs.shift();
                            } catch(e) {}
                        };
                    });
                }
                var logs = window.__devCompanionLogs;
                var filtered = logs;
                if ('$level' !== 'all') filtered = logs.filter(function(e){ return e.level === '$level'; });
                return JSON.stringify(filtered.slice(-$limit));
            })()
        """.trimIndent()

        val result = webView.evalJs(js)
        return ToolResult(call.id, result)
    }

    private suspend fun executeGetComputedStyle(call: ToolCall, webView: WebView): ToolResult {
        val selector = call.arguments.getAsJsonPrimitive("selector")?.asString
            ?: return ToolResult(call.id, "Missing 'selector' parameter", isError = true)
        val properties = call.arguments.getAsJsonPrimitive("properties")?.asString ?: ""

        val propsList = if (properties.isBlank()) {
            "color,backgroundColor,fontSize,fontWeight,fontFamily,display,visibility,position,margin,padding,width,height,overflow,opacity,border,boxSizing"
        } else {
            properties
        }

        val js = """
            (function(){
                var el = document.querySelector(${escapeJsString(selector)});
                if (!el) return JSON.stringify({error:'Element not found',selector:${escapeJsString(selector)}});
                var cs = window.getComputedStyle(el);
                var props = ${escapeJsString(propsList)}.split(',');
                var result = {};
                props.forEach(function(p){
                    try { result[p.trim()] = cs.getPropertyValue(p.trim()); } catch(e){}
                });
                result._box = {offsetWidth:el.offsetWidth, offsetHeight:el.offsetHeight, clientWidth:el.clientWidth, clientHeight:el.clientHeight};
                return JSON.stringify(result);
            })()
        """.trimIndent()

        val result = webView.evalJs(js)
        return ToolResult(call.id, result)
    }

    private suspend fun executeSetStyle(call: ToolCall, webView: WebView): ToolResult {
        val selector = call.arguments.getAsJsonPrimitive("selector")?.asString
            ?: return ToolResult(call.id, "Missing 'selector' parameter", isError = true)
        val styles = call.arguments.getAsJsonPrimitive("styles")?.asString
            ?: return ToolResult(call.id, "Missing 'styles' parameter", isError = true)

        val js = """
            (function(){
                var el = document.querySelector(${escapeJsString(selector)});
                if (!el) return JSON.stringify({success:false, error:'Element not found', selector:${escapeJsString(selector)}});
                var decls = ${escapeJsString(styles)}.split(';').filter(function(s){return s.trim().length > 0});
                decls.forEach(function(d){
                    var parts = d.split(':');
                    if (parts.length === 2) {
                        try { el.style[parts[0].trim().replace(/-([a-z])/g, function(m,c){return c.toUpperCase()})] = parts[1].trim(); } catch(e){}
                    }
                });
                return JSON.stringify({success:true, applied:decls.length, selector:${escapeJsString(selector)}});
            })()
        """.trimIndent()

        val result = webView.evalJs(js)
        return ToolResult(call.id, result)
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * Escape a string for safe embedding in a JavaScript string literal.
     */
    private fun escapeJsString(s: String): String {
        return buildString {
            append('"')
            for (ch in s) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\u0000' -> append("\\0")
                    else -> append(ch)
                }
            }
            append('"')
        }
    }
}