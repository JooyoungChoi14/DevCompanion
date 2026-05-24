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
    private val onSwitchMode: ((String) -> Unit)? = null,
    private val getCurrentMode: (() -> String)? = null,
    private var scratchpad: SessionScratchpad? = null,
) {

    companion object {
        private const val TAG = "ToolExecutor"
    }

    /** Update the scratchpad reference (called by AgentLoop when starting a new session). */
    fun updateScratchpad(pad: SessionScratchpad) {
        scratchpad = pad
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
                "extract_text" -> executeExtractText(call, webView)
                "get_computed_style" -> executeGetComputedStyle(call, webView)
                "set_style" -> executeSetStyle(call, webView)
                "screenshot" -> executeScreenshot(call, webView)
                "submit_form" -> executeSubmit(call, webView)
                "get_console_logs" -> executeGetConsoleLogs(call, webView)
                "switch_mode" -> executeSwitchMode(call)
                "get_current_mode" -> executeGetCurrentMode(call)
                "recall" -> executeRecall(call)
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

        // Append WebView environment warning for specific result patterns
        val enhancedResult = if (result.contains(""""t":"error"""")) {
            // Check for CSP error specifically
            if (result.contains("Content Security Policy", ignoreCase = true) ||
                result.contains("unsafe-eval", ignoreCase = true)) {
                "$result\n\n[WebView: CSP blocks eval on this site. Do NOT retry eval_js. Use get_dom, extract_text, click, or navigate to backend APIs instead.]"
            } else {
                "$result\n\n[WebView: eval_js returned an error. Consider alternative tools.]"
            }
        } else if (result.contains("download", ignoreCase = true) ||
                   result.contains("saved", ignoreCase = true) ||
                   result.contains("exported", ignoreCase = true)) {
            // Flag potential false positives — JS return values don't mean actual file I/O
            "$result\n\n[WebView WARNING: eval_js cannot trigger real file downloads or save files. This string only means JS code executed, NOT that a file was saved to the device. To provide data to the user, include it in your response text.]"
        } else {
            result
        }

        return ToolResult(call.id, enhancedResult)
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
                
                var totalLen = html.length;
                var maxLen = 10000;
                var truncated = totalLen > maxLen;
                var capturedHtml = html.substring(0, maxLen);
                
                return JSON.stringify({
                    tagName: el.tagName,
                    outerHTML: capturedHtml,
                    textContent: el.textContent ? el.textContent.substring(0, 2000) : null,
                    childCount: el.children.length,
                    _meta: { truncated: truncated, totalLength: totalLen, capturedLength: capturedHtml.length }
                });
            })()
        """.trimIndent()

        val result = webView.evalJs(js)
        return ToolResult(call.id, result)
    }

    private suspend fun executeExtractText(call: ToolCall, webView: WebView): ToolResult {
        val selector = call.arguments.getAsJsonPrimitive("selector")?.asString ?: "body"
        val includeHeadings = call.arguments.getAsJsonPrimitive("includeHeadings")?.asBoolean ?: true

        val js = """
            (function(){
                var el = document.querySelector(${escapeJsString(selector)});
                if (!el) return JSON.stringify({error:'not found', selector:${escapeJsString(selector)}});
                
                // Extract text content with structure preservation
                var maxLen = 8000;
                var texts = [];
                var totalLen = 0;
                
                function walk(node, depth) {
                    if (totalLen >= maxLen) return;
                    if (node.nodeType === Node.TEXT_NODE) {
                        var t = node.textContent.trim();
                        if (t.length > 0) {
                            texts.push(t);
                            totalLen += t.length + 1;
                        }
                        return;
                    }
                    if (node.nodeType !== Node.ELEMENT_NODE) return;
                    
                    var tag = node.tagName.toLowerCase();
                    
                    // Skip hidden elements
                    var style = window.getComputedStyle(node);
                    if (style.display === 'none' || style.visibility === 'hidden') return;
                    
                    // Skip script/style
                    if (tag === 'script' || tag === 'style' || tag === 'noscript') return;
                    
                    // Add heading markers
                    if (${includeHeadings} && /^h[1-6]$/.test(tag)) {
                        var prefix = '#'.repeat(parseInt(tag[1]));
                        texts.push('\n' + prefix + ' ' + (node.textContent || '').trim());
                        totalLen += texts[texts.length-1].length + 1;
                        return;
                    }
                    
                    // Add paragraph breaks
                    if (tag === 'p' || tag === 'div' || tag === 'li' || tag === 'tr' || tag === 'br' || tag === 'hr') {
                        if (texts.length > 0 && texts[texts.length-1] !== '\n') {
                            texts.push('\n');
                            totalLen += 1;
                        }
                    }
                    
                    for (var i = 0; i < node.childNodes.length; i++) {
                        walk(node.childNodes[i], depth + 1);
                    }
                }
                
                walk(el, 0);
                
                var result = texts.join(' ').replace(/ +\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim();
                var truncated = result.length >= maxLen;
                
                return JSON.stringify({
                    text: result.substring(0, maxLen),
                    selector: ${escapeJsString(selector)},
                    _meta: { truncated: truncated, totalLength: totalLen, capturedLength: Math.min(result.length, maxLen) }
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

    private suspend fun executeSwitchMode(call: ToolCall): ToolResult {
        val mode = call.arguments.getAsJsonPrimitive("mode")?.asString
            ?: return ToolResult(call.id, "Missing 'mode' parameter", isError = true)

        if (mode != "chat" && mode != "agent") {
            return ToolResult(call.id, "Invalid mode: '$mode'. Must be 'chat' or 'agent'.", isError = true)
        }

        return try {
            onSwitchMode?.invoke(mode)
            ToolResult(call.id, "Switched to ${if (mode == "agent") "Act" else "Chat"} mode.")
        } catch (e: Exception) {
            ToolResult(call.id, "Failed to switch mode: ${e.message}", isError = true)
        }
    }

    private suspend fun executeGetCurrentMode(call: ToolCall): ToolResult {
        val current = getCurrentMode?.invoke() ?: "unknown"
        val displayName = if (current == "agent") "Act" else "Chat"
        return ToolResult(call.id, "Current mode: $displayName ($current)")
    }

    private fun executeRecall(call: ToolCall): ToolResult {
        val pad = scratchpad
        if (pad == null) {
            return ToolResult(call.id, "Session memory not available in this mode.")
        }

        val toolName = call.arguments.getAsJsonPrimitive("tool_name")?.asString
        val index = call.arguments.getAsJsonPrimitive("index")?.asInt
        val query = call.arguments.getAsJsonPrimitive("query")?.asString

        /** Cap recall output to avoid flooding LLM context. */
        val recallBudget = 4000

        // Specific index requested — return full entry but cap output
        if (index != null && index >= 0) {
            val entry = pad.getByIndex(index)
            return if (entry != null) {
                val fullOutput = entry.rawOutput
                val capped = fullOutput.length > recallBudget
                ToolResult(call.id, buildString {
                    appendLine("[Recall: #${entry.index} ${entry.toolName}]")
                    appendLine("User intent: \"${entry.userIntent}\"")
                    if (entry.errorType != null) appendLine("⚠️ Error type: ${entry.errorType}")
                    if (capped) appendLine("⚠️ Output too large (${fullOutput.length} chars), showing first $recallBudget chars.")
                    appendLine("---")
                    append(fullOutput.take(recallBudget))
                    if (capped) appendLine("\n--- [Use recall with query=\"...\" to search within this entry]")
                })
            } else {
                ToolResult(call.id, "No entry found at index $index. Valid indices: ${pad.entries.map { it.index }}")
            }
        }

        // Search by query
        if (query != null && query.isNotBlank()) {
            val results = pad.search(query)
            return if (results.isEmpty()) {
                ToolResult(call.id, "No entries found matching \"$query\".\n${pad.summary()}")
            } else {
                ToolResult(call.id, buildString {
                    appendLine("Found ${results.size} entries matching \"$query\":")
                    results.forEach { entry ->
                        appendLine("\n--- #${entry.index} ${entry.toolName}${if (entry.truncated) " [TRUNCATED]" else ""} ---")
                        appendLine(entry.rawOutput.take(2000))
                    }
                })
            }
        }

        // Filter by tool name or show all (summary only)
        val filtered = if (toolName != null) pad.getByTool(toolName) else pad.entries
        return if (filtered.isEmpty()) {
            ToolResult(call.id, "No entries found${if (toolName != null) " for tool '$toolName'" else ""}.\n${pad.summary()}")
        } else {
            ToolResult(call.id, buildString {
                appendLine("${filtered.size} entries${if (toolName != null) " for '$toolName'" else ""}:")
                filtered.forEach { entry ->
                    val preview = entry.rawOutput.take(300).replace("\n", " ")
                    appendLine("#${entry.index} ${entry.toolName}" +
                        (if (entry.selector != null) " selector=${entry.selector}" else "") +
                        (if (entry.truncated) " [TRUNCATED]" else "") +
                        (if (entry.errorType != null) " [ERROR: ${entry.errorType}]" else "") +
                        " → ${preview}")
                }
                appendLine("\nUse recall with index=N to get full content of a specific entry.")
            })
        }
    }

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