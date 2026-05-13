package com.devcompanion.llm.budget

import com.devcompanion.llm.ChatMessage
import com.devcompanion.llm.LlmProvider
import com.devcompanion.llm.WebContextPacket

/**
 * Client-side token estimation before making API calls.
 *
 * Estimation is conservative (~120% of actual) to prevent underestimation.
 * Actual token counts are reported via [TokenUsage] after each API call
 * and used to correct the estimates.
 */
object TokenEstimator {

    /** Language detection for per-language estimation heuristics. */
    enum class Language {
        KOREAN, ENGLISH, JAPANESE, CHINESE, MIXED, UNKNOWN
    }

    /** Conservative overestimation factor. Actual * 1.2 ≈ estimated. */
    private const val OVERESTIMATE_FACTOR = 1.2

    /**
     * Estimate token count for a text string based on language heuristics.
     *
     * Korean: ~0.5 tokens/char (high due to morphological analysis)
     * English: ~1.3 tokens/word
     * Japanese: ~0.6 tokens/char
     * Chinese: ~0.5 tokens/char
     */
    fun estimate(
        text: String,
        language: Language = detectLanguage(text)
    ): Int {
        if (text.isBlank()) return 0

        val rawEstimate = when (language) {
            Language.KOREAN -> (text.length * 0.5).toInt()
            Language.ENGLISH -> (text.split("\\s+".toRegex()).size * 1.3).toInt()
            Language.JAPANESE -> (text.length * 0.6).toInt()
            Language.CHINESE -> (text.length * 0.5).toInt()
            Language.MIXED -> {
                // Heuristic: count CJK chars separately
                val cjkCount = text.count { isCJK(it) }
                val latinCount = text.length - cjkCount
                ((cjkCount * 0.5) + (latinCount * 0.25)).toInt()
            }
            Language.UNKNOWN -> (text.length * 0.4).toInt() // conservative average
        }

        return (rawEstimate * OVERESTIMATE_FACTOR).toInt()
    }

    /**
     * Estimate total tokens for a list of messages plus optional web context.
     */
    fun estimate(
        messages: List<com.devcompanion.llm.ChatMessage>,
        context: WebContextPacket?,
        provider: LlmProvider
    ): Int {
        val textTokens = messages.sumOf { estimate(it.content) }
        val imageTokens = context?.let {
            estimateImageTokens(it.screenshotBase64, provider)
        } ?: 0
        val domTokens = context?.let {
            estimate(it.domSnapshot)
        } ?: 0

        return textTokens + imageTokens + domTokens
    }

    /**
     * Provider-specific image token estimation.
     *
     * OpenAI: detail level affects token count (low=85, high=85+170*MP, auto≈200)
     * Anthropic: ~1600 tokens per image at recommended resolution
     * Gemini: images counted as input tokens; ~258 tokens per megapixel
     * Ollama: varies by model; conservative 1000
     *
     * @param base64Image Base64-encoded screenshot data
     * @param provider Current LLM provider
     * @param detail OpenAI detail level: "low", "high", "auto"
     */
    fun estimateImageTokens(
        base64Image: String?,
        provider: LlmProvider,
        detail: String = "auto"
    ): Int {
        if (base64Image.isNullOrBlank()) return 0

        // Base64 → approximate byte size → megapixels
        val bytes = (base64Image.length * 3) / 4
        val megapixels = (bytes / 3) / 1_000_000.0  // RGB assumption

        return when (provider) {
            is LlmProvider.OpenAi -> {
                when (detail) {
                    "low" -> 85
                    "high" -> (85 + megapixels * 170).toInt().coerceIn(85, 1105)
                    else -> 200  // auto: conservative default
                }
            }
            is LlmProvider.Anthropic -> {
                // Claude: ~1600 tokens per image at recommended resolution
                (megapixels * 1600).toInt().coerceIn(400, 1600)
            }
            is LlmProvider.Gemini -> {
                // Gemini: images as input tokens, ~258 per megapixel
                (megapixels * 258).toInt().coerceIn(100, 1000)
            }
            is LlmProvider.Ollama -> {
                1000  // conservative estimate
            }
        }
    }

    /**
     * Detect the dominant language of a text string.
     */
    fun detectLanguage(text: String): Language {
        if (text.isBlank()) return Language.UNKNOWN

        val koreanCount = text.count { it in '\uAC00'..'\uD7AF' || it in '\u1100'..'\u11FF' }
        val cjkCount = text.count { isCJK(it) && it !in '\uAC00'..'\uD7AF' }
        val latinCount = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        val total = text.length.coerceAtLeast(1)

        val koreanRatio = koreanCount.toDouble() / total
        val cjkRatio = cjkCount.toDouble() / total
        val latinRatio = latinCount.toDouble() / total

        return when {
            koreanRatio > 0.3 -> Language.KOREAN
            cjkRatio > 0.3 && latinRatio > 0.2 -> Language.MIXED
            cjkRatio > 0.3 -> {
                // Distinguish Japanese vs Chinese by hiragana/katakana
                val hiraCount = text.count { it in '\u3040'..'\u309F' }
                val kataCount = text.count { it in '\u30A0'..'\u30FF' }
                if (hiraCount + kataCount > 0) Language.JAPANESE else Language.CHINESE
            }
            latinRatio > 0.5 -> Language.ENGLISH
            else -> Language.UNKNOWN
        }
    }

    private fun isCJK(char: Char): Boolean {
        return char in '\u2E80'..'\u9FFF' ||
                char in '\uF900'..'\uFAFF' ||
                char in '\uAC00'..'\uD7AF' ||
                char in '\u3040'..'\u30FF' ||
                char in '\uFF00'..'\uFFEF'
    }
}