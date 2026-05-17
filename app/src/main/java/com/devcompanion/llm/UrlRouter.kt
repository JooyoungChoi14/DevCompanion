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
    // Normalize full-width question mark (Korean IME) to half-width
    val normalized = trimmed.replace('\uff1f', '?')
    return when {
        normalized.startsWith("?") -> UrlRoute.AiQuestion(normalized.removePrefix("?").trim())
        normalized.endsWith("?") && !normalized.startsWith("http") && !normalized.contains("/") ->
            UrlRoute.AiQuestion(normalized.removeSuffix("?").trim())
        normalized.startsWith("http") -> UrlRoute.Direct(normalized)
        normalized.contains(".") && !normalized.contains(" ") ->
            UrlRoute.Url("https://$normalized")
        else -> UrlRoute.Search(
            "https://duckduckgo.com/?q=${URLEncoder.encode(normalized, "UTF-8")}"
        )
    }
}