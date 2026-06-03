package com.devcompanion.ui

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.devcompanion.llm.LlmProvider
import com.devcompanion.llm.LlmRepositoryImpl
import com.devcompanion.llm.LlmSettings
import com.devcompanion.logging.EventType
import com.devcompanion.logging.SessionLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for Settings UI — single source of truth for all settings state.
 *
 * Responsibilities:
 * - Load/save provider configuration (API key, base URL, model, type)
 * - Provide reactive form state that UI observes
 * - Test provider connectivity
 * - Manage custom prompt and max iterations
 *
 * UI (SettingsSheet) only observes and calls methods — never touches LlmSettings directly.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SettingsViewModel"
    }

    // ── Provider form state ─────────────────────────────────────────

    private val _formState = MutableStateFlow(ProviderFormState())
    val formState: StateFlow<ProviderFormState> = _formState.asStateFlow()

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    private val _usingPlainStorage = MutableStateFlow(LlmSettings.isUsingPlainStorage)
    val usingPlainStorage: StateFlow<Boolean> = _usingPlainStorage.asStateFlow()

    private val _saveResult = MutableStateFlow<SaveResult?>(null)
    val saveResult: StateFlow<SaveResult?> = _saveResult.asStateFlow()

    // ── Custom prompt ────────────────────────────────────────────────

    private val _customPrompt = MutableStateFlow(LlmSettings.loadCustomPrompt() ?: "")
    val customPrompt: StateFlow<String> = _customPrompt.asStateFlow()

    // ── Max iterations ───────────────────────────────────────────────

    private val _maxIterations = MutableStateFlow(LlmSettings.maxIterations)
    val maxIterations: StateFlow<Int> = _maxIterations.asStateFlow()

    init {
        loadFromStorage()
    }

    /**
     * Load provider settings from persistent storage into form state.
     * Called on init and can be called to refresh.
     */
    fun loadFromStorage() {
        try {
            val provider = LlmSettings.loadProvider()
            _formState.value = if (provider != null) {
                ProviderFormState.fromProvider(provider)
            } else {
                ProviderFormState()
            }
            _usingPlainStorage.value = LlmSettings.isUsingPlainStorage
            SessionLog.log(EventType.SETTINGS_LOAD, mapOf(
                "source" to "SettingsViewModel",
                "provider" to (provider?.providerType ?: "null"),
                "apiKeyLen" to (_formState.value.apiKey.length).toString(),
                "apiKeyHead" to _formState.value.apiKey.take(4),
                "apiKeyTail" to _formState.value.apiKey.takeLast(4),
                "baseUrl" to _formState.value.baseUrl
            ))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load provider from storage", e)
            SessionLog.log(EventType.SETTINGS_LOAD, mapOf(
                "source" to "SettingsViewModel",
                "result" to "exception",
                "error" to (e.message?.take(80) ?: "unknown")
            ))
        }
    }

    // ── Form field updaters ──────────────────────────────────────────

    fun updateProviderType(type: String) {
        val current = _formState.value
        if (current.providerType == type) return // Same type — no reset

        _formState.value = current.copy(
            providerType = type,
            apiKey = "", // Clear key only when switching type
            baseUrl = defaultBaseUrlFor(type),
            model = defaultModelFor(type)
        )
    }

    fun updateApiKey(key: String) {
        _formState.value = _formState.value.copy(apiKey = key)
    }

    fun updateBaseUrl(url: String) {
        _formState.value = _formState.value.copy(baseUrl = url)
    }

    fun updateModel(model: String) {
        _formState.value = _formState.value.copy(model = model)
    }

    // ── Save ─────────────────────────────────────────────────────────

    /**
     * Save current form state to persistent storage.
     * Returns SaveResult for UI feedback.
     */
    fun save(): SaveResult {
        val state = _formState.value
        val provider = state.toProvider() ?: return SaveResult.Error("Invalid provider type: ${state.providerType}")

        return try {
            LlmSettings.saveProvider(provider)
            val saved = LlmSettings.loadProvider()
            val savedKey = when (saved) {
                is LlmProvider.Anthropic -> saved.apiKey
                is LlmProvider.OpenAi -> saved.apiKey
                is LlmProvider.Ollama -> saved.apiKey
                is LlmProvider.Gemini -> saved.apiKey
                null -> ""
            }
            if (savedKey != state.apiKey) {
                SessionLog.log(EventType.SETTINGS_SAVE, mapOf(
                    "provider" to provider.providerType,
                    "result" to "roundtrip_mismatch",
                    "savedLen" to state.apiKey.length.toString(),
                    "readBackLen" to savedKey.length.toString()
                ))
                _saveResult.value = SaveResult.Error("Saved key doesn't match — possible storage corruption")
                return _saveResult.value!!
            }
            SessionLog.log(EventType.SETTINGS_SAVE, mapOf(
                "provider" to provider.providerType,
                "result" to "ok",
                "apiKeyLen" to state.apiKey.length.toString(),
                "apiKeyHead" to state.apiKey.take(4),
                "apiKeyTail" to state.apiKey.takeLast(4),
                "storage" to if (LlmSettings.isUsingPlainStorage) "plain" else "encrypted"
            ))
            _saveResult.value = SaveResult.Success
            _saveResult.value!!
        } catch (e: Exception) {
            SessionLog.log(EventType.SETTINGS_SAVE, mapOf(
                "provider" to state.providerType,
                "result" to "error",
                "error" to (e.message?.take(80) ?: "unknown")
            ))
            _saveResult.value = SaveResult.Error(e.message?.take(50) ?: "Save failed")
            _saveResult.value!!
        }
    }

    // ── Test ─────────────────────────────────────────────────────────

    fun testConnection() {
        val state = _formState.value
        if (state.apiKey.isBlank()) {
            _testResult.value = "✗ API key required"
            return
        }
        val provider = state.toProvider() ?: return
        _testing.value = true
        _testResult.value = null

        viewModelScope.launch {
            try {
                val repo = LlmRepositoryImpl(provider)
                val response = repo.complete("Hello")
                _testResult.value = "✓ Connected: ${response.take(80)}"
            } catch (e: Exception) {
                _testResult.value = "✗ Error: ${e.message?.take(100) ?: "Unknown"}"
            } finally {
                _testing.value = false
            }
        }
    }

    // ── Custom prompt ────────────────────────────────────────────────

    fun updateCustomPrompt(prompt: String) {
        _customPrompt.value = prompt
    }

    fun saveCustomPrompt() {
        LlmSettings.saveCustomPrompt(_customPrompt.value.trim())
    }

    fun clearCustomPrompt() {
        _customPrompt.value = ""
        LlmSettings.saveCustomPrompt("")
    }

    // ── Max iterations ───────────────────────────────────────────────

    fun updateMaxIterations(value: Int) {
        val coerced = value.coerceIn(LlmSettings.MIN_MAX_ITERATIONS, LlmSettings.MAX_MAX_ITERATIONS)
        _maxIterations.value = coerced
        LlmSettings.maxIterations = coerced
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun defaultBaseUrlFor(type: String): String = when (type) {
        LlmProvider.Ollama.TYPE -> "https://ollama.com"
        LlmProvider.Gemini.TYPE -> "https://generativelanguage.googleapis.com/v1beta"
        else -> ""
    }

    private fun defaultModelFor(type: String): String = when (type) {
        LlmProvider.Anthropic.TYPE -> "claude-sonnet-4-20250514"
        LlmProvider.OpenAi.TYPE -> "gpt-4o"
        LlmProvider.Ollama.TYPE -> "glm-5.1"
        LlmProvider.Gemini.TYPE -> "gemini-2.5-flash"
        else -> ""
    }
}

// ── Data classes ──────────────────────────────────────────────────

/**
 * Immutable form state — single object for all provider fields.
 * UI observes this via StateFlow. All mutations go through SettingsViewModel methods.
 */
data class ProviderFormState(
    val providerType: String = LlmProvider.Ollama.TYPE,
    val apiKey: String = "",
    val baseUrl: String = "https://ollama.com",
    val model: String = "glm-5.1"
) {
    /** Convert form state to LlmProvider. Returns null for unknown type. */
    fun toProvider(): LlmProvider? = when (providerType) {
        LlmProvider.Anthropic.TYPE -> LlmProvider.Anthropic(
            apiKey = apiKey,
            baseUrl = baseUrl.ifEmpty { "https://api.anthropic.com" },
            model = model.ifEmpty { "claude-sonnet-4-20250514" }
        )
        LlmProvider.OpenAi.TYPE -> LlmProvider.OpenAi(
            apiKey = apiKey,
            baseUrl = baseUrl.ifEmpty { "https://api.openai.com" },
            model = model.ifEmpty { "gpt-4o" }
        )
        LlmProvider.Ollama.TYPE -> LlmProvider.Ollama(
            apiKey = apiKey,
            baseUrl = baseUrl.ifEmpty { LlmProvider.Ollama.DEFAULT_BASE_URL },
            model = model.ifEmpty { "glm-5.1" }
        )
        LlmProvider.Gemini.TYPE -> LlmProvider.Gemini(
            apiKey = apiKey,
            baseUrl = baseUrl.ifEmpty { "https://generativelanguage.googleapis.com/v1beta" },
            model = model.ifEmpty { "gemini-2.5-flash" }
        )
        else -> null
    }

    companion object {
        fun fromProvider(provider: LlmProvider): ProviderFormState = when (provider) {
            is LlmProvider.Anthropic -> ProviderFormState(
                providerType = LlmProvider.Anthropic.TYPE,
                apiKey = provider.apiKey,
                baseUrl = provider.baseUrl,
                model = provider.model
            )
            is LlmProvider.OpenAi -> ProviderFormState(
                providerType = LlmProvider.OpenAi.TYPE,
                apiKey = provider.apiKey,
                baseUrl = provider.baseUrl,
                model = provider.model
            )
            is LlmProvider.Ollama -> ProviderFormState(
                providerType = LlmProvider.Ollama.TYPE,
                apiKey = provider.apiKey,
                baseUrl = provider.baseUrl,
                model = provider.model
            )
            is LlmProvider.Gemini -> ProviderFormState(
                providerType = LlmProvider.Gemini.TYPE,
                apiKey = provider.apiKey,
                baseUrl = provider.baseUrl,
                model = provider.model
            )
        }
    }
}

sealed class SaveResult {
    data object Success : SaveResult()
    data class Error(val message: String) : SaveResult()
}

private object Log {
    fun w(tag: String, msg: String, e: Exception) = android.util.Log.w(tag, msg, e)
}