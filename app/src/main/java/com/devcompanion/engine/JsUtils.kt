package com.devcompanion.engine

/**
 * Shared JavaScript utilities for safe string escaping.
 *
 * SSOT for escapeJsString — used by ToolExecutor, PermissionGate, WebContextBuilder.
 */
object JsUtils {
    /**
     * Escape a string for safe embedding in a JavaScript string literal.
     * Handles: backslash, quotes, newlines, tabs, null char, and `</style` breakout.
     */
    fun escapeJsString(s: String): String {
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

    /**
     * Escape a CSS string to prevent `</style>` breakout XSS.
     * Use when injecting user-provided CSS into a `<style>` element.
     */
    fun escapeCssForStyleTag(css: String): String {
        return css.replace("</style", "<\\/style")
            .replace("</STYLE", "<\\/STYLE")
            .replace("<", "\\x3c")
    }
}