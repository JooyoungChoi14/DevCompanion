package com.devcompanion.llm

/**
 * Builds dynamic system prompts based on the current app mode, WebView context,
 * and available tools. This ensures the LLM understands:
 * - What app it's running in
 * - What mode (agent vs chat) it's in
 * - What the current page URL is
 * - What tools it has access to and when to use them
 * - Any user-defined custom instructions
 */
object SystemPromptBuilder {

    /**
     * Build a system prompt for the given context.
     *
     * @param mode "agent" for WebView interaction, "chat" for general conversation
     * @param currentUrl the URL currently loaded in the WebView (null if unavailable)
     * @param customInstructions optional user-defined instructions appended to the prompt
     */
    fun build(
        mode: String,
        currentUrl: String? = null,
        customInstructions: String? = null
    ): String {
        val sb = StringBuilder()

        // ── Identity ──────────────────────────────────────────────────
        sb.appendLine("You are DevCompanion, an AI assistant embedded in an Android app that contains a WebView browser.")
        sb.appendLine()

        // ── Mode context ──────────────────────────────────────────────
        when (mode) {
            "agent" -> {
                sb.appendLine("## Current Mode: Agent")
                sb.appendLine("You are in **agent mode** — the user wants you to interact with the WebView page directly.")
                sb.appendLine("Use the provided tools (navigate, click, type, get_dom, get_computed_style, set_style, eval_js, screenshot, scroll, submit_form, get_console_logs) to inspect and manipulate the page.")
                sb.appendLine("Always verify your actions by checking the result (e.g., get_dom or screenshot after navigation/clicks).")
                sb.appendLine("When the task is complete, provide a summary in clear markdown.")
                sb.appendLine()
            }
            "chat" -> {
                sb.appendLine("## Current Mode: Chat")
                sb.appendLine("You are in **chat mode** — the user is asking questions, and the WebView context (screenshot + DOM) is provided automatically.")
                sb.appendLine("Answer questions about the current page or general topics. You do NOT have tool access in this mode.")
                sb.appendLine("Respond in clear, well-formatted markdown.")
                sb.appendLine()
            }
        }

        // ── Page context ──────────────────────────────────────────────
        if (currentUrl != null) {
            sb.appendLine("## Current Page")
            sb.appendLine("URL: $currentUrl")
            sb.appendLine()
        }

        // ── Tool reference (agent mode only) ──────────────────────────
        if (mode == "agent") {
            sb.appendLine("## Available Tools")
            sb.appendLine("| Tool | Purpose | Risk |")
            sb.appendLine("|------|---------|------|")
            sb.appendLine("| navigate | Go to a URL (http/https only) | Moderate |")
            sb.appendLine("| click | Click an element by CSS selector | Moderate |")
            sb.appendLine("| type | Type text into an input field | Sensitive if password/hidden |")
            sb.appendLine("| scroll | Scroll the page (up/down/left/right) | Moderate |")
            sb.appendLine("| get_dom | Get HTML/DOM snapshot of the page | Moderate |")
            sb.appendLine("| get_computed_style | Get computed CSS properties of an element | Moderate |")
            sb.appendLine("| set_style | Apply inline CSS styles to an element | Sensitive (needs confirmation) |")
            sb.appendLine("| eval_js | Execute JavaScript in the page | Sensitive if dangerous patterns |")
            sb.appendLine("| screenshot | Capture the current page as an image | Moderate |")
            sb.appendLine("| submit_form | Submit a form | Sensitive (needs confirmation) |")
            sb.appendLine("| get_console_logs | Read browser console logs | Moderate |")
            sb.appendLine()
            sb.appendLine("Use tools proactively to verify changes. For example, after set_style, use get_computed_style or screenshot to confirm the result.")
            sb.appendLine()
        }

        // ── Output format ─────────────────────────────────────────────
        sb.appendLine("## Response Format")
        sb.appendLine("- Use clear markdown formatting (headings, lists, tables, code blocks)")
        sb.appendLine("- GFM tables are supported: use `| Header | Header |` with `|---|---|` separator")
        sb.appendLine("- Keep responses concise and actionable")
        sb.appendLine()

        // ── Custom instructions ────────────────────────────────────────
        if (!customInstructions.isNullOrBlank()) {
            sb.appendLine("## Custom Instructions")
            sb.appendLine(customInstructions)
            sb.appendLine()
        }

        return sb.toString().trimEnd()
    }
}