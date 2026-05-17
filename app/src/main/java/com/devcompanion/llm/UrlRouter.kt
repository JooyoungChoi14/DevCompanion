package com.devcompanion.llm

import java.net.URLEncoder

/**
 * Routes user input from the address bar or NTP search bar.
 * Unified logic for smart URL/AI/search routing.
 *
 * 1. ?question          → AI (explicit prefix)
 * 2. Sentence ending with ? → AI (natural question)
 * 3. http(s)://...      → direct URL
 * 4. domain-like (has ., no spaces) → URL with https://
 * 5. Everything else    → DDG search
 */
sealed class UrlRoute {
    data class AiQuestion(val question: String) : UrlRoute()
    data class Direct(val url: String) : UrlRoute()
    data class Url(val url: String) : UrlRoute()
    data class Search(val url: String) : UrlRoute()
}

fun routeUrlInput(input: String): UrlRoute {
    val trimmed = input.trim()
    return when {
        trimmed.startsWith("?") -> UrlRoute.AiQuestion(trimmed.removePrefix("?").trim())
        trimmed.endsWith("?") && !trimmed.startsWith("http") && !trimmed.contains(".") ->
            UrlRoute.AiQuestion(trimmed.removeSuffix("?").trim())
        trimmed.startsWith("http") -> UrlRoute.Direct(trimmed)
        trimmed.contains(".") && !trimmed.contains(" ") ->
            UrlRoute.Url("https://$trimmed")
        else -> UrlRoute.Search(
            "https://duckduckgo.com/?q=${URLEncoder.encode(trimmed, "UTF-8")}"
        )
    }
}