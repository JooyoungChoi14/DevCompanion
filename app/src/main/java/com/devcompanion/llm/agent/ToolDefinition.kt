package com.devcompanion.llm.agent

import com.google.gson.JsonObject

/**
 * Tool definitions for the browser agent loop.
 *
 * Each tool describes an action the LLM can take on the page,
 * including its name, description, and JSON Schema parameters.
 * These are converted to provider-specific function calling formats
 * by each adapter.
 */

// ── Data classes ──────────────────────────────────────────────────────

/**
 * A tool definition provided to the LLM for function calling.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

/**
 * A tool call from the LLM requesting an action.
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject
)

/**
 * Result of executing a tool call.
 *
 * [semanticError] indicates a logical failure where the tool executed successfully
 * (isError=false) but the result is unusable for the agent's purpose.
 * Examples: CSP blocking eval_js, get_dom truncation, download triggers in the browser.
 * AgentLoop uses this to accumulate per-tool-category semantic failures
 * and disable the tool after repeated issues.
 */
data class ToolResult(
    val id: String,
    val output: String,
    val isError: Boolean = false,
    val semanticError: String? = null
)

/**
 * Confirmation details for a sensitive action requiring user approval.
 */
data class ToolConfirmationDetails(
    val action: String,
    val target: String,
    val preview: String,
    val riskLevel: ActionRisk
)

// ── Enums ──────────────────────────────────────────────────────────────

/**
 * Risk classification for tool actions.
 * - [SAFE]: Read-only, no side effects
 * - [MODERATE]: Has side effects but low risk
 * - [SENSITIVE]: Requires user confirmation
 */
enum class ActionRisk { SAFE, MODERATE, SENSITIVE }

/**
 * State of the agent loop.
 * Used by the UI to show progress and enable cancellation.
 */
sealed class AgentState {
    object Idle : AgentState()
    data class Thinking(val iteration: Int) : AgentState()
    data class CheckingPermission(val call: ToolCall, val iteration: Int) : AgentState()
    data class WaitingConfirmation(val call: ToolCall) : AgentState()
    data class ExecutingTool(val tool: String, val iteration: Int) : AgentState()
    data class Error(val message: String) : AgentState()
}

/**
 * Events emitted by the agent loop for the UI to consume.
 */
sealed class AgentEvent {
    data class ToolExecuted(val call: ToolCall, val result: ToolResult) : AgentEvent()
    data class TextResponse(val content: String) : AgentEvent()
    data class ConfirmationRequired(val call: ToolCall, val details: ToolConfirmationDetails) : AgentEvent()
    data class Rejected(val call: ToolCall) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
    object BudgetExceeded : AgentEvent()
}

// ── Tool definitions ──────────────────────────────────────────────────

/**
 * Predefined tool definitions for browser interaction.
 *
 * Each tool has a name, description, and JSON Schema parameters.
 * These are provided to the LLM via the `tools` parameter in chat requests.
 */
object WebViewTools {

    val NAVIGATE = ToolDefinition(
        name = "navigate",
        description = "Navigate the browser to a URL. Only http:// and https:// URLs are allowed.",
        parameters = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                add("url", JsonObject().apply {
                    addProperty("type", "string")
                    addProperty("description", "The URL to navigate to (http:// or https:// only)")
                })
            })
            add("required", com.google.gson.JsonArray().apply { add("url") })
        }
    )

    val CLICK = ToolDefinition(
        name = "click",
        description = "Click an element on the page identified by a CSS selector.",
        parameters = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                add("selector", JsonObject().apply {
                    addProperty("type", "string")
                    addProperty("description", "CSS selector of the element to click")
                })
            })
            add("required", com.google.gson.JsonArray().apply { add("selector") })
        }
    )

    val TYPE = ToolDefinition(
        name = "type",
        description = "Type text into an input field. The field's sensitivity is checked at runtime: password, hidden, or credit-card fields require user confirmation.",
        parameters = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                add("selector", JsonObject().apply {
                    addProperty("type", "string")
                    addProperty("description", "CSS selector of the input element")
                })
                add("text", JsonObject().apply {
                    addProperty("type", "string")
                    addProperty("description", "Text to type into the field")
                })
                add("clear", JsonObject().apply {
                    addProperty("type", "boolean")
                    addProperty("description", "Whether to clear the field before typing (default: true)")
                })
            })
            add("required", com.google.gson.JsonArray().apply { add("selector"); add("text") })
        }
    )

    val SCROLL = ToolDefinition(
        name = "scroll",
        description = "Scroll the page in a direction by a number of pixels.",
        parameters = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                add("direction", JsonObject().apply {
                    addProperty("type", "string")
                    addProperty("description", "Scroll direction: up, down, left, right")
                    add("enum", com.google.gson.JsonArray().apply {
                        add("up"); add("down"); add("left"); add("right")
                    })
                })
                add("amount", JsonObject().apply {
                    addProperty("type", "integer")
                    addProperty("description", "Number of pixels to scroll (default: 300)")
                })
            })
            add("required", com.google.gson.JsonArray().apply { add("direction") })
        }
    )

    val EVAL_JS = ToolDefinition(
        name = "eval_js",
        description = "Execute a JavaScript expression in the page context. WARNING: (1) Many sites (chatgpt.com, google.com, etc.) block eval via Content Security Policy (CSP). If you receive a CSP error, do NOT retry eval_js — switch to get_dom, extract_text, or click instead. (2) This runs inside the browser engine — you CANNOT trigger file downloads, access the filesystem, or save files via JS. A return value like 'download triggered' only means JS executed, NOT that a file was saved. (3) Dangerous patterns (document.cookie, localStorage, fetch, etc.) require user confirmation.",
        parameters = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                add("expression", JsonObject().apply {
                    addProperty("type", "string")
                    addProperty("description", "JavaScript expression to evaluate. Cannot trigger downloads or access the filesystem in the browser engine.")
                })
            })
            add("required", com.google.gson.JsonArray().apply { add("expression") })
        }
    )

    val GET_DOM = ToolDefinition(
        name = "get_dom",
        description = "Get a snapshot of the page DOM. Returns up to ~10,000 characters of outerHTML — long pages WILL be truncated. For text-only extraction without HTML markup, use extract_text instead. Sensitive fields (passwords, hidden inputs) are masked.",
        parameters = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                add("selector", JsonObject().apply {
                    addProperty("type", "string")
                    addProperty("description", "CSS selector to scope the DOM snapshot (default: 'body'). Use specific selectors like '#content' or '[data-testid]' to get focused, untruncated content.")
                })
            })
        }
    )

    val EXTRACT_TEXT = ToolDefinition(
        name = "extract_text",
        description = "Extract visible text content from the page or a specific element. Unlike get_dom which returns HTML markup (which can be very large and truncated), this tool returns only the human-readable text. Use this when you need the actual content of a page — articles, conversations, lists — rather than its HTML structure. Returns up to ~8,000 characters. IMPORTANT: If the user asks to 'download' or 'save' data, use this tool to collect the data, then include it in your response text so the user can copy it. Do NOT try to trigger file downloads via eval_js — they do not work in the browser engine.",
        parameters = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                add("selector", JsonObject().apply {
                    addProperty("type", "string")
                    addProperty("description", "CSS selector to scope text extraction (default: 'body'). Use specific selectors like 'article', '[role=main]', or '.post-content' to get focused content.")
                })
                add("includeHeadings", JsonObject().apply {
                    addProperty("type", "boolean")
                    addProperty("description", "Whether to include heading text (default: true)")
                })
            })
        }
    )

    val SCREENSHOT = ToolDefinition(
        name = "screenshot",
        description = "Take a screenshot of the current page. Password fields are blurred for privacy.",
        parameters = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                add("selector", JsonObject().apply {
                    addProperty("type", "string")
                    addProperty("description", "Optional CSS selector to capture a specific element instead of the full page")
                })
            })
        }
    )

    val SUBMIT_FORM = ToolDefinition(
        name = "submit_form",
        description = "Submit a form on the page. Requires user confirmation because this sends data.",
        parameters = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                add("selector", JsonObject().apply {
                    addProperty("type", "string")
                    addProperty("description", "CSS selector of the form to submit")
                })
            })
            add("required", com.google.gson.JsonArray().apply { add("selector") })
        }
    )

    val GET_CONSOLE_LOGS = ToolDefinition(
        name = "get_console_logs",
        description = "Retrieve recent console log messages from the browser. Returns an array of log entries with level, message, and timestamp. Useful for debugging JavaScript errors or inspecting runtime behavior.",
        parameters = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                add("limit", JsonObject().apply {
                    addProperty("type", "integer")
                    addProperty("description", "Maximum number of log entries to return (default: 50)")
                })
                add("level", JsonObject().apply {
                    addProperty("type", "string")
                    addProperty("description", "Filter by log level: 'all', 'error', 'warn', 'info' (default: 'all')")
                })
            })
        }
    )

    val GET_COMPUTED_STYLE = ToolDefinition(
        name = "get_computed_style",
        description = "Get the computed CSS style of an element. Returns property values like color, font, display, margin, padding, etc. Useful for debugging layout and styling issues.",
        parameters = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                add("selector", JsonObject().apply {
                    addProperty("type", "string")
                    addProperty("description", "CSS selector of the element to inspect")
                })
                add("properties", JsonObject().apply {
                    addProperty("type", "string")
                    addProperty("description", "Comma-separated CSS property names to retrieve (e.g. 'color,font-size,display'). If omitted, returns common layout properties.")
                })
            })
            add("required", com.google.gson.JsonArray().apply { add("selector") })
        }
    )

    val SET_STYLE = ToolDefinition(
        name = "set_style",
        description = "Apply inline CSS styles to an element. Useful for quick visual fixes or testing style changes before making them permanent. Requires user confirmation.",
        parameters = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                add("selector", JsonObject().apply {
                    addProperty("type", "string")
                    addProperty("description", "CSS selector of the element to style")
                })
                add("styles", JsonObject().apply {
                    addProperty("type", "string")
                    addProperty("description", "CSS declarations to apply, e.g. 'background: red; padding: 10px;'")
                })
            })
            add("required", com.google.gson.JsonArray().apply { add("selector"); add("styles") })
        }
    )

    val SWITCH_MODE = ToolDefinition(
        name = "switch_mode",
        description = "Switch between Chat and Act (agent) mode. Use this when the current mode doesn't match the user's intent — e.g., user asks to interact with the page but you're in Chat mode, or user asks a general question but you're in Act mode.",
        parameters = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                add("mode", JsonObject().apply {
                    addProperty("type", "string")
                    addProperty("description", "Target mode: 'chat' for general Q\u0026A, 'agent' for browser interaction")
                    add("enum", com.google.gson.JsonArray().apply { add("chat"); add("agent") })
                })
            })
            add("required", com.google.gson.JsonArray().apply { add("mode") })
        }
    )

    val GET_CURRENT_MODE = ToolDefinition(
        name = "get_current_mode",
        description = "Get the current interaction mode (Chat or Act/Agent). Use this when you're unsure which mode you're in.",
        parameters = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {})
        }
    )

    val RECALL = ToolDefinition(
        name = "recall",
        description = "Retrieve a previously stored tool result from the session's working memory. Use when: (1) a previous tool result was truncated and you need the full content, (2) you need to re-examine earlier data instead of re-running the same tool, (3) you want to cross-reference results from different tools. This is more efficient than re-running tools.",
        parameters = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                add("tool_name", JsonObject().apply {
                    addProperty("type", "string")
                    addProperty("description", "Filter by tool name (e.g. 'extract_text', 'get_dom'). Omit to list all entries.")
                })
                add("index", JsonObject().apply {
                    addProperty("type", "integer")
                    addProperty("description", "Specific entry index to retrieve. Use -1 or omit to list entries instead.")
                })
                add("query", JsonObject().apply {
                    addProperty("type", "string")
                    addProperty("description", "Text search within stored results. Returns entries whose output contains this text.")
                })
            })
        }
    )

    /** All available browser tools. */
    val ALL = listOf(
        NAVIGATE, CLICK, TYPE, SCROLL, EVAL_JS, GET_DOM, EXTRACT_TEXT, GET_COMPUTED_STYLE, SET_STYLE, SCREENSHOT, SUBMIT_FORM, GET_CONSOLE_LOGS,
        RECALL,
        SWITCH_MODE, GET_CURRENT_MODE
    )
}