package com.devcompanion.llm.agent

import android.util.Log
import android.webkit.WebView
import com.devcompanion.llm.evalJs
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Security gate that classifies tool actions by risk level
 * and performs runtime DOM inspection for sensitive fields.
 *
 * All tool actions are classified into three risk levels:
 * - [ActionRisk.SAFE]: Read-only, no side effects (auto-approved)
 * - [ActionRisk.MODERATE]: Has side effects but low risk (auto-approved)
 * - [ActionRisk.SENSITIVE]: Requires user confirmation
 */
class PermissionGate {

    companion object {
        private const val TAG = "PermissionGate"

        /** Dangerous JavaScript patterns that require confirmation. */
        private val DANGEROUS_JS_PATTERNS = listOf(
            Regex("""document\.cookie""", RegexOption.IGNORE_CASE),
            Regex("""document\.domain""", RegexOption.IGNORE_CASE),
            Regex("""localStorage""", RegexOption.IGNORE_CASE),
            Regex("""sessionStorage""", RegexOption.IGNORE_CASE),
            Regex("""indexedDB""", RegexOption.IGNORE_CASE),
            Regex("""\bfetch\s*\(""", RegexOption.IGNORE_CASE),
            Regex("""XMLHttpRequest""", RegexOption.IGNORE_CASE),
            Regex("""WebSocket\s*\(""", RegexOption.IGNORE_CASE),
            Regex("""EventSource\s*\(""", RegexOption.IGNORE_CASE),
            Regex("""navigator\.sendBeacon""", RegexOption.IGNORE_CASE),
            Regex("""performance\.getEntries""", RegexOption.IGNORE_CASE),
            Regex("""\bFunction\s*\(""", RegexOption.IGNORE_CASE),
            Regex("""\beval\s*\(""", RegexOption.IGNORE_CASE),
            Regex("""setTimeout\s*\[""", RegexOption.IGNORE_CASE)
        )

        /** CSS selector patterns for sensitive input fields. */
        private val SENSITIVE_SELECTORS = listOf(
            "input[type='password']",
            "input[type='hidden']",
            "[data-sensitive]",
            "input[name*='card']",
            "input[name*='ssn']",
            "input[name*='pw']",
            "input[name*='pass']",
            "input[id*='password']",
            "input[id*='pass']",
            "input[class*='password']",
            "input[autocomplete*='cc-']",
            "input[autocomplete*='current-password']"
        )
    }

    /**
     * Classify a tool call's risk level.
     *
     * For [type] actions, this performs runtime DOM inspection via WebView
     * to check the target element's type, name, and autocomplete attributes.
     */
    suspend fun classify(call: ToolCall, webView: WebView): ActionRisk = when (call.name) {
        "navigate" -> checkNavigateRisk(call)
        "type" -> checkTypeRisk(call, webView)
        "eval_js" -> checkEvalRisk(call)
        "submit_form" -> ActionRisk.SENSITIVE
        "click", "scroll", "get_dom", "extract_text", "get_computed_style", "screenshot", "get_console_logs" -> ActionRisk.MODERATE
        "set_style" -> ActionRisk.SENSITIVE
        "submit_form" -> ActionRisk.SENSITIVE
        else -> ActionRisk.MODERATE
    }

    /**
     * Get confirmation details for a sensitive action.
     */
    fun getConfirmationDetails(call: ToolCall): ToolConfirmationDetails = when (call.name) {
        "type" -> ToolConfirmationDetails(
            action = "Type into field",
            target = call.arguments.getAsJsonPrimitive("selector")?.asString ?: "unknown",
            preview = (call.arguments.getAsJsonPrimitive("text")?.asString ?: "").take(50),
            riskLevel = ActionRisk.SENSITIVE
        )
        "eval_js" -> ToolConfirmationDetails(
            action = "Execute JavaScript",
            target = "WebView",
            preview = (call.arguments.getAsJsonPrimitive("expression")?.asString ?: "").take(200),
            riskLevel = ActionRisk.SENSITIVE
        )
        "submit_form" -> ToolConfirmationDetails(
            action = "Submit form",
            target = call.arguments.getAsJsonPrimitive("selector")?.asString ?: "unknown",
            preview = "Form data will be sent to the server",
            riskLevel = ActionRisk.SENSITIVE
        )
        "set_style" -> ToolConfirmationDetails(
            action = "Apply CSS styles",
            target = call.arguments.getAsJsonPrimitive("selector")?.asString ?: "unknown",
            preview = call.arguments.getAsJsonPrimitive("styles")?.asString?.take(100) ?: "",
            riskLevel = ActionRisk.SENSITIVE
        )
        else -> ToolConfirmationDetails(
            action = call.name,
            target = call.arguments.toString().take(100),
            preview = "",
            riskLevel = ActionRisk.MODERATE
        )
    }

    // ── Private risk checks ─────────────────────────────────────────────

    /**
     * Check navigate risk: only http and https URLs are allowed.
     */
    private fun checkNavigateRisk(call: ToolCall): ActionRisk {
        val url = call.arguments.getAsJsonPrimitive("url")?.asString ?: return ActionRisk.SENSITIVE
        return if (url.startsWith("http://") || url.startsWith("https://")) {
            ActionRisk.MODERATE
        } else {
            ActionRisk.SENSITIVE
        }
    }

    /**
     * Check type risk: inspect the target element's DOM properties.
     *
     * Queries the WebView for the element's type, name, autocomplete
     * attributes to detect sensitive fields like passwords and credit cards.
     */
    private suspend fun checkTypeRisk(call: ToolCall, webView: WebView): ActionRisk {
        val selector = call.arguments.getAsJsonPrimitive("selector")?.asString
            ?: return ActionRisk.SENSITIVE // No selector = can't verify = treat as sensitive

        // Quick check: if selector matches known sensitive patterns, skip DOM query
        val selectorLower = selector.lowercase()
        if (SENSITIVE_SELECTORS.any { selectorLower.contains(it.substringAfter("'").substringBefore("'").lowercase()) }) {
            return ActionRisk.SENSITIVE
        }

        // Runtime DOM inspection
        val js = """
            (function(){
                var el = document.querySelector(${escapeJsString(selector)});
                if (!el) return JSON.stringify({error:'not found'});
                return JSON.stringify({
                    tagName: el.tagName,
                    type: el.type || null,
                    name: el.name || null,
                    autocomplete: el.autocomplete || null,
                    inputMode: el.inputMode || null
                });
            })()
        """.trimIndent()

        return try {
            val result = webView.evalJs(js)
            val props = parseJsResult(result)

            val isSensitive = when {
                props["type"] == "password" -> true
                props["type"] == "hidden" -> true
                props["name"]?.contains(Regex("(?i)(password|pw|pass|secret|token|key|ssn|card|cvv)")) == true -> true
                props["autocomplete"]?.contains("cc-") == true -> true
                props["autocomplete"]?.contains("current-password") == true -> true
                props["autocomplete"]?.contains("new-password") == true -> true
                else -> false
            }

            if (isSensitive) ActionRisk.SENSITIVE else ActionRisk.MODERATE
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inspect element for type risk, treating as sensitive", e)
            ActionRisk.SENSITIVE // Fail safe: if we can't verify, treat as sensitive
        }
    }

    /**
     * Check eval_js risk: scan for dangerous patterns.
     *
     * Detects access to cookies, storage, network requests, and
     * dynamic code execution patterns.
     */
    private fun checkEvalRisk(call: ToolCall): ActionRisk {
        val code = call.arguments.getAsJsonPrimitive("expression")?.asString
            ?: return ActionRisk.SENSITIVE

        val hasDangerous = DANGEROUS_JS_PATTERNS.any { it.containsMatchIn(code) }

        return if (hasDangerous) ActionRisk.SENSITIVE else ActionRisk.MODERATE
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun parseJsResult(json: String): Map<String, String?> {
        return try {
            val obj = JsonParser.parseString(json).asJsonObject
            obj.entrySet().associate { (key, value) ->
                key to if (value.isJsonNull) null else value.asString
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse JS result: $json", e)
            emptyMap()
        }
    }

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