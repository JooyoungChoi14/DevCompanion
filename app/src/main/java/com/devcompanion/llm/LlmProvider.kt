package com.devcompanion.llm

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Sealed class representing a supported LLM provider configuration.
 *
 * Each subclass holds the credentials and endpoint details needed
 * to make API calls to the respective provider.
 */
sealed class LlmProvider {

    /** Unique discriminator used for (de)serialization. */
    abstract val providerType: String

    /** Human-readable label for display in UI. */
    abstract val displayName: String

    /** Whether this provider has a usable API key configured.
     *  For Ollama, the key is optional so this is always true.
     *  For others, the key must be non-blank. */
    abstract val hasApiKey: Boolean

    /** Whether this provider/model supports image (vision) input.
     *  Ollama models vary — only known vision models (llava, etc.) return true.
     *  Other providers' default models all support vision. */
    abstract val supportsVision: Boolean

    // ── Anthropic ────────────────────────────────────────────────────

    data class Anthropic(
        val apiKey: String,
        val baseUrl: String = "https://api.anthropic.com",
        val version: String = "2023-06-01",
        val model: String = "claude-sonnet-4-20250514"
    ) : LlmProvider() {
        override val providerType = TYPE
        override val displayName = "Anthropic"
        override val hasApiKey get() = apiKey.isNotBlank()
        override val supportsVision = true

        override fun toString(): String =
            "Anthropic(apiKey=••••${apiKey.takeLast(4)}, baseUrl=$baseUrl)"

        companion object {
            const val TYPE = "anthropic"
        }
    }

    // ── OpenAI ───────────────────────────────────────────────────────

    data class OpenAi(
        val apiKey: String,
        val baseUrl: String = "https://api.openai.com",
        val organization: String? = null,
        val model: String = "gpt-4o"
    ) : LlmProvider() {
        override val providerType = TYPE
        override val displayName = "OpenAI"
        override val hasApiKey get() = apiKey.isNotBlank()
        override val supportsVision = true

        override fun toString(): String =
            "OpenAi(apiKey=••••${apiKey.takeLast(4)}, baseUrl=$baseUrl)"

        companion object {
            const val TYPE = "openai"
        }
    }

    // ── Ollama ───────────────────────────────────────────────────────

    data class Ollama(
        val apiKey: String = "",
        val baseUrl: String = "https://ollama.com",
        val model: String = "glm-5.1"
    ) : LlmProvider() {
        override val providerType = TYPE
        override val displayName = "Ollama"
        override val hasApiKey get() = apiKey.isNotBlank()
        override val supportsVision = false // glm-5.1 doesn't support vision; set true for llava etc.

        override fun toString(): String =
            "Ollama(baseUrl=$baseUrl, model=$model)"

        companion object {
            const val TYPE = "ollama"
        }
    }

    // ── Gemini ───────────────────────────────────────────────────────

    data class Gemini(
        val apiKey: String,
        val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
        val model: String = "gemini-2.5-flash"
    ) : LlmProvider() {
        override val providerType = TYPE
        override val displayName = "Gemini"
        override val hasApiKey get() = apiKey.isNotBlank()
        override val supportsVision = true

        override fun toString(): String =
            "Gemini(apiKey=••••${apiKey.takeLast(4)}, baseUrl=$baseUrl, model=$model)"

        companion object {
            const val TYPE = "gemini"
        }
    }

    // ── Serialization helpers ────────────────────────────────────────

    companion object {
        private const val TAG = "LlmProvider"
        private val gson = Gson()

        /**
         * Serialize an [LlmProvider] to a JSON string.
         * The [providerType] field is included for polymorphic deserialization.
         */
        fun toJson(provider: LlmProvider): String {
            val json = gson.toJson(provider)
            // Gson serializes the sealed class including all fields of the
            // concrete subclass, but we must ensure `providerType` is present.
            val obj = JsonParser.parseString(json).asJsonObject
            obj.addProperty("providerType", provider.providerType)
            return gson.toJson(obj)
        }

        /**
         * Deserialize a JSON string back to an [LlmProvider].
         * Returns null if the type discriminator is missing or unknown.
         */
        fun fromJson(json: String): LlmProvider? {
            return try {
                val obj = JsonParser.parseString(json).asJsonObject
                val type = obj.get("providerType")?.asString ?: return null
                when (type) {
                    Anthropic.TYPE -> gson.fromJson(obj, Anthropic::class.java)
                    OpenAi.TYPE -> gson.fromJson(obj, OpenAi::class.java)
                    Ollama.TYPE -> gson.fromJson(obj, Ollama::class.java)
                    Gemini.TYPE -> gson.fromJson(obj, Gemini::class.java)
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}