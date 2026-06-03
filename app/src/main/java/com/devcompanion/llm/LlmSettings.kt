package com.devcompanion.llm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.devcompanion.logging.SessionLog
import com.devcompanion.logging.EventType
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Manages persistent storage of LLM provider settings using
 * [EncryptedSharedPreferences] so API keys are never stored in plaintext.
 *
 * Thread-safe: all reads/writes go through SharedPreferences commit/apply
 * which provides atomic guarantees on the Android side.
 */
object LlmSettings {

    private const val TAG = "LlmSettings"
    private const val FILE_NAME = "devcompanion_llm_settings"
    private const val KEY_PROVIDER_TYPE = "provider_type"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_MODEL = "model"
    private const val KEY_ORGANIZATION = "organization"
    private const val KEY_VERSION = "version"
    private const val KEY_CUSTOM_PROMPT = "custom_system_prompt"
    private const val KEY_MAX_ITERATIONS = "max_iterations"
    private const val KEY_AGENT_MODE_DEFAULT = "agent_mode_default"
    const val DEFAULT_MAX_ITERATIONS = 10
    const val MIN_MAX_ITERATIONS = 3
    const val MAX_MAX_ITERATIONS = 30

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * Whether the current storage backend is using standard (unencrypted)
     * SharedPreferences instead of EncryptedSharedPreferences.
     *
     * When true, the UI should warn the user that API keys are stored
     * in plaintext. This can happen on devices without a secure enclave
     * (e.g. some emulators).
     */
    var isUsingPlainStorage: Boolean = false
        private set

    /**
     * Must be called once during Application.onCreate or similar
     * early initialization. Creates or opens the encrypted prefs file.
     */
    @Synchronized
    fun initialize(context: Context) {
        if (prefs != null) return

        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            prefs = EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            Log.i(TAG, "Encrypted settings initialized")
            SessionLog.log(EventType.SETTINGS_INIT, mapOf(
                "storage" to "encrypted",
                "result" to "success"
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize encrypted preferences — falling back to standard prefs", e)
            prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            isUsingPlainStorage = true
            SessionLog.log(EventType.SETTINGS_INIT, mapOf(
                "storage" to "plaintext_fallback",
                "result" to "fallback",
                "error" to (e.message?.take(80) ?: "unknown")
            ))
        }
    }

    private fun requirePrefs(): SharedPreferences {
        return prefs ?: error("LlmSettings not initialized — call initialize(context) first")
    }

    /**
     * Save the currently active [LlmProvider] configuration.
     * All fields are persisted atomically via [SharedPreferences.edit].
     */
    fun saveProvider(provider: LlmProvider) {
        val p = requirePrefs()
        val apiKeyValue = when (provider) {
            is LlmProvider.Anthropic -> provider.apiKey
            is LlmProvider.OpenAi -> provider.apiKey
            is LlmProvider.Ollama -> provider.apiKey
            is LlmProvider.Gemini -> provider.apiKey
        }
        p.edit().apply {
            putString(KEY_PROVIDER_TYPE, provider.providerType)
            putString(KEY_API_KEY, apiKeyValue)
            putString(KEY_BASE_URL, when (provider) {
                is LlmProvider.Anthropic -> provider.baseUrl
                is LlmProvider.OpenAi -> provider.baseUrl
                is LlmProvider.Ollama -> provider.baseUrl
                is LlmProvider.Gemini -> provider.baseUrl
            })
            putString(KEY_MODEL, when (provider) {
                is LlmProvider.Anthropic -> ""
                is LlmProvider.OpenAi -> ""
                is LlmProvider.Ollama -> provider.model
                is LlmProvider.Gemini -> provider.model
            })
            putString(KEY_ORGANIZATION, when (provider) {
                is LlmProvider.OpenAi -> provider.organization
                else -> null
            })
            putString(KEY_VERSION, when (provider) {
                is LlmProvider.Anthropic -> provider.version
                else -> null
            })
            apply()
        }
        // Verify round-trip immediately after save
        val savedKey = p.getString(KEY_API_KEY, "")
        if (savedKey != apiKeyValue) {
            Log.e(TAG, "API KEY CORRUPTION: saved=${apiKeyValue.length}chars, read back=${savedKey?.length ?: 0}chars")
            SessionLog.log(EventType.SETTINGS_SAVE, mapOf(
                "provider" to provider.providerType,
                "result" to "corruption",
                "savedLen" to apiKeyValue.length.toString(),
                "readBackLen" to (savedKey?.length ?: 0).toString(),
                "baseUrl" to (when (provider) {
                    is LlmProvider.Anthropic -> provider.baseUrl
                    is LlmProvider.OpenAi -> provider.baseUrl
                    is LlmProvider.Ollama -> provider.baseUrl
                    is LlmProvider.Gemini -> provider.baseUrl
                })
            ))
        } else {
            Log.d(TAG, "Provider saved: ${provider.providerType}, apiKey=${apiKeyValue.take(4)}...${apiKeyValue.takeLast(4)} (${apiKeyValue.length}chars)")
            SessionLog.log(EventType.SETTINGS_SAVE, mapOf(
                "provider" to provider.providerType,
                "result" to "ok",
                "apiKeyLen" to apiKeyValue.length.toString(),
                "apiKeyHead" to apiKeyValue.take(4),
                "apiKeyTail" to apiKeyValue.takeLast(4),
                "baseUrl" to (when (provider) {
                    is LlmProvider.Anthropic -> provider.baseUrl
                    is LlmProvider.OpenAi -> provider.baseUrl
                    is LlmProvider.Ollama -> provider.baseUrl
                    is LlmProvider.Gemini -> provider.baseUrl
                })
            ))
        }
    }

    /**
     * Load the previously saved [LlmProvider], or null if none configured yet.
     */
    fun loadProvider(): LlmProvider? {
        val p = requirePrefs()
        val type = p.getString(KEY_PROVIDER_TYPE, null) ?: return null

        val apiKey = p.getString(KEY_API_KEY, "") ?: ""
        val baseUrl = p.getString(KEY_BASE_URL, "") ?: ""
        val model = p.getString(KEY_MODEL, "llava") ?: "llava"
        val organization = p.getString(KEY_ORGANIZATION, null)
        val version = p.getString(KEY_VERSION, "2023-06-01") ?: "2023-06-01"

        Log.d(TAG, "Loaded provider: type=$type, apiKey=${apiKey.take(4)}...${apiKey.takeLast(4)} (${apiKey.length}chars), baseUrl=$baseUrl")
        SessionLog.log(EventType.SETTINGS_LOAD, mapOf(
            "provider" to (type ?: "null"),
            "result" to if (type != null) "ok" else "not_found",
            "apiKeyLen" to apiKey.length.toString(),
            "apiKeyHead" to apiKey.take(4),
            "apiKeyTail" to apiKey.takeLast(4),
            "baseUrl" to baseUrl
        ))

        return when (type) {
            LlmProvider.Anthropic.TYPE -> LlmProvider.Anthropic(
                apiKey = apiKey,
                baseUrl = baseUrl.ifEmpty { "https://api.anthropic.com" },
                version = version
            )
            LlmProvider.OpenAi.TYPE -> LlmProvider.OpenAi(
                apiKey = apiKey,
                baseUrl = baseUrl.ifEmpty { "https://api.openai.com" },
                organization = organization
            )
            LlmProvider.Ollama.TYPE -> LlmProvider.Ollama(
                apiKey = apiKey,
                baseUrl = baseUrl.ifEmpty { LlmProvider.Ollama.DEFAULT_BASE_URL },
                model = model
            )
            LlmProvider.Gemini.TYPE -> LlmProvider.Gemini(
                apiKey = apiKey,
                baseUrl = baseUrl.ifEmpty { "https://generativelanguage.googleapis.com/v1beta" },
                model = model.ifEmpty { "gemini-2.0-flash" }
            )
            else -> {
                Log.w(TAG, "Unknown provider type: $type")
                null
            }
        }
    }

    /**
     * Clear all stored provider settings.
     */
    fun clear() {
        val wasAgentModeDefault = requirePrefs().getBoolean(KEY_AGENT_MODE_DEFAULT, true)
        requirePrefs().edit().clear().apply()
        // Preserve agent mode default preference after clear
        requirePrefs().edit().putBoolean(KEY_AGENT_MODE_DEFAULT, wasAgentModeDefault).apply()
        Log.d(TAG, "Provider settings cleared")
    }

    /**
     * Save custom system prompt instructions.
     * These are appended after the built-in prompt context.
     */
    fun saveCustomPrompt(prompt: String) {
        requirePrefs().edit().putString(KEY_CUSTOM_PROMPT, prompt).apply()
        Log.d(TAG, "Custom prompt saved (${prompt.length} chars)")
    }

    /**
     * Load custom system prompt instructions, or null if none set.
     */
    fun loadCustomPrompt(): String? {
        val p = requirePrefs()
        val prompt = p.getString(KEY_CUSTOM_PROMPT, null)
        return if (prompt.isNullOrBlank()) null else prompt
    }

    /** Default agent mode (true = Act, false = View). Persisted across sessions. */
    var agentModeDefault: Boolean
        get() = requirePrefs().getBoolean(KEY_AGENT_MODE_DEFAULT, true) // Act by default
        set(value) = requirePrefs().edit().putBoolean(KEY_AGENT_MODE_DEFAULT, value).apply()

    /** Maximum agent loop iterations (3-30, default 10). */
    var maxIterations: Int
        get() = requirePrefs().getInt(KEY_MAX_ITERATIONS, DEFAULT_MAX_ITERATIONS)
        set(value) = requirePrefs().edit().putInt(
            KEY_MAX_ITERATIONS,
            value.coerceIn(MIN_MAX_ITERATIONS, MAX_MAX_ITERATIONS)
        ).apply()
}