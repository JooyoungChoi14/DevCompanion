package com.devcompanion.llm.budget

import com.devcompanion.llm.LlmProvider

/**
 * Hard-coded default pricing for offline fallback.
 *
 * These values are used when no remote pricing table is available.
 * The self-calculating pricing pipeline (self-calculating-pricing.md)
 * can update these from OpenRouter API or official provider pages.
 *
 * Pricing source: official provider docs, 2026-05 baseline.
 * All prices in USD per 1M tokens.
 */
object DefaultPricing {

    data class PricingEntry(
        val modelId: String,
        val inputPricePerMTok: Double,
        val outputPricePerMTok: Double,
        val contextWindow: Int,
        val supportsVision: Boolean
    )

    // Base reference for weight calculation
    private const val BASE_INPUT = 3.00   // Claude 4 Sonnet input
    private const val BASE_OUTPUT = 15.00  // Claude 4 Sonnet output

    val entries: Map<String, PricingEntry> = mapOf(
        // Anthropic
        "claude-sonnet-4-20250514" to PricingEntry(
            modelId = "claude-sonnet-4-20250514",
            inputPricePerMTok = 3.00,
            outputPricePerMTok = 15.00,
            contextWindow = 200_000,
            supportsVision = true
        ),
        // OpenAI
        "gpt-4o" to PricingEntry(
            modelId = "gpt-4o",
            inputPricePerMTok = 2.50,
            outputPricePerMTok = 10.00,
            contextWindow = 128_000,
            supportsVision = true
        ),
        "gpt-4.1" to PricingEntry(
            modelId = "gpt-4.1",
            inputPricePerMTok = 2.00,
            outputPricePerMTok = 8.00,
            contextWindow = 1_047_576,
            supportsVision = true
        ),
        // Google
        "gemini-2.5-pro" to PricingEntry(
            modelId = "gemini-2.5-pro",
            inputPricePerMTok = 1.25,
            outputPricePerMTok = 10.00,
            contextWindow = 1_000_000,
            supportsVision = true
        ),
        "gemini-2.5-flash" to PricingEntry(
            modelId = "gemini-2.5-flash",
            inputPricePerMTok = 0.15,
            outputPricePerMTok = 3.50,
            contextWindow = 1_000_000,
            supportsVision = true
        ),
        // Ollama Cloud
        "glm-5.1" to PricingEntry(
            modelId = "glm-5.1",
            inputPricePerMTok = 0.05,
            outputPricePerMTok = 0.25,
            contextWindow = 128_000,
            supportsVision = false
        )
    )

    /**
     * Look up a pricing entry by model ID.
     * Falls back to partial match (e.g. "claude-sonnet-4" matches "claude-sonnet-4-20250514").
     */
    fun find(modelId: String): PricingEntry? {
        // Exact match first
        entries[modelId]?.let { return it }

        // Partial prefix match (longest match wins)
        return entries.values
            .filter { modelId.startsWith(it.modelId) || it.modelId.startsWith(modelId) }
            .maxByOrNull { it.modelId.length }
    }

    /**
     * Get the [ModelWeight] for a given model ID.
     * Returns [ModelWeight.FREE] for local/Ollama models, [ModelWeight.UNKNOWN] if not found.
     */
    fun weightFor(provider: LlmProvider): ModelWeight {
        // Local Ollama = free
        if (provider is LlmProvider.Ollama && provider.baseUrl.contains("localhost")) {
            return ModelWeight.FREE
        }

        val modelId = when (provider) {
            is LlmProvider.Anthropic -> provider.model.ifBlank { "claude-sonnet-4-20250514" }
            is LlmProvider.OpenAi -> provider.model.ifBlank { "gpt-4o" }
            is LlmProvider.Ollama -> provider.model
            is LlmProvider.Gemini -> provider.model.ifBlank { "gemini-2.5-flash" }
        }

        val entry = find(modelId) ?: return ModelWeight.UNKNOWN

        return ModelWeight(
            inputWeight = BASE_INPUT / entry.inputPricePerMTok,
            outputWeight = BASE_OUTPUT / entry.outputPricePerMTok
        )
    }

    /**
     * Calculate estimated dollar cost for a token usage event.
     */
    fun estimateCost(
        provider: LlmProvider,
        inputTokens: Int,
        outputTokens: Int
    ): Double {
        val modelId = when (provider) {
            is LlmProvider.Anthropic -> provider.model.ifBlank { "claude-sonnet-4-20250514" }
            is LlmProvider.OpenAi -> provider.model.ifBlank { "gpt-4o" }
            is LlmProvider.Ollama -> provider.model
            is LlmProvider.Gemini -> provider.model.ifBlank { "gemini-2.5-flash" }
        }

        val entry = find(modelId) ?: return 0.0
        val inputCost = (inputTokens / 1_000_000.0) * entry.inputPricePerMTok
        val outputCost = (outputTokens / 1_000_000.0) * entry.outputPricePerMTok
        return inputCost + outputCost
    }
}