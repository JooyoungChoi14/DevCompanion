package com.devcompanion.llm

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.devcompanion.logging.SessionLog
import com.devcompanion.logging.EventType
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages persistent storage of LLM provider settings.
 *
 * Encryption strategy:
 * - Primary: AES-256-GCM via Android Keystore (API 23+)
 * - Fallback: standard SharedPreferences with different file name
 *
 * Encrypted data is stored in a file ([ENCRYPTED_FILE]), not in SharedPreferences.
 * Non-sensitive settings (theme, max iterations) go to SharedPreferences.
 * Fallback uses a separate [PLAIN_FILE] to avoid file-type collisions.
 *
 * Thread-safe: all reads/writes go through synchronized methods.
 */
object LlmSettings {

    private const val TAG = "LlmSettings"

    // Separate file names for encrypted vs plain storage
    private const val PREFS_FILE = "devcompanion_prefs"       // non-sensitive settings
    private const val ENCRYPTED_FILE = "devcompanion_keys.enc" // encrypted API keys
    private const val PLAIN_FILE = "devcompanion_llm_plain"   // plaintext fallback (separate from encrypted)

    // Preference keys
    private const val KEY_PROVIDER_TYPE = "provider_type"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_MODEL = "model"
    private const val KEY_ORGANIZATION = "organization"
    private const val KEY_VERSION = "version"
    private const val KEY_CUSTOM_PROMPT = "custom_system_prompt"
    private const val KEY_MAX_ITERATIONS = "max_iterations"
    private const val KEY_AGENT_MODE_DEFAULT = "agent_mode_default"

    // Encryption constants
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "devcompanion_aes_key"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    const val DEFAULT_MAX_ITERATIONS = 10
    const val MIN_MAX_ITERATIONS = 3
    const val MAX_MAX_ITERATIONS = 30

    @Volatile
    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null

    /** Whether we're using plaintext storage (no encryption available). */
    var isUsingPlainStorage: Boolean = false
        private set

    /** In-memory cache of decrypted API key to avoid repeated decryption. */
    @Volatile
    private var cachedApiKey: String? = null
    @Volatile
    private var cachedApiKeyProvider: String? = null

    @Synchronized
    fun initialize(context: Context) {
        if (prefs != null) return
        appContext = context.applicationContext

        prefs = context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

        // Try to get or create AES key from Android Keystore
        val keyAvailable = try {
            getOrCreateAesKey()
            isUsingPlainStorage = false
            true
        } catch (ex: Exception) {
            Log.w(TAG, "Android Keystore unavailable, using plaintext fallback", ex)
            isUsingPlainStorage = true
            false
        }

        SessionLog.log(EventType.SETTINGS_INIT, mapOf(
            "storage" to if (keyAvailable) "encrypted" else "plaintext_fallback",
            "result" to if (keyAvailable) "success" else "fallback"
        ))

        // Migrate: if encrypted file exists but we're in plaintext mode now,
        // we can't read it. Log a warning.
        if (isUsingPlainStorage) {
            val encFile = File(context.filesDir, ENCRYPTED_FILE)
            if (encFile.exists()) {
                Log.w(TAG, "Encrypted key file exists but Keystore unavailable — API keys may be inaccessible")
                SessionLog.log(EventType.SETTINGS_INIT, mapOf(
                    "storage" to "plaintext_fallback",
                    "result" to "existing_encrypted_unreadable",
                    "error" to "Keystore unavailable but encrypted file exists"
                ))
            }
        }

        // Migrate: if old EncryptedSharedPreferences data exists, try to read it
        migrateFromEncryptedSharedPreferences(context)
    }

    private fun requirePrefs(): SharedPreferences {
        return prefs ?: error("LlmSettings not initialized — call initialize(context) first")
    }

    // ── AES-256-GCM via Android Keystore ───────────────────────────

    private fun getOrCreateAesKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
            return entry.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return keyGenerator.generateKey()
    }

    private fun encrypt(plaintext: String): String {
        val key = getOrCreateAesKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // Format: Base64(iv + ciphertext)
        val combined = iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(ciphertext: String): String? {
        return try {
            val key = getOrCreateAesKey()
            val combined = Base64.decode(ciphertext, Base64.NO_WRAP)
            if (combined.size < GCM_IV_LENGTH) return null
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val encrypted = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed", e)
            null
        }
    }

    // ── File I/O for encrypted keys ────────────────────────────────

    private fun writeEncryptedFile(data: Map<String, String>) {
        val ctx = appContext ?: return
        val content = data.entries.joinToString("\n") { (k, v) -> "$k=$v" }
        val encrypted = encrypt(content)
        val file = File(ctx.filesDir, ENCRYPTED_FILE)
        FileOutputStream(file).use { fos ->
            fos.write(encrypted.toByteArray(Charsets.UTF_8))
        }
    }

    private fun readEncryptedFile(): Map<String, String> {
        val ctx = appContext ?: return emptyMap()
        val file = File(ctx.filesDir, ENCRYPTED_FILE)
        if (!file.exists()) return emptyMap()
        val encrypted = FileInputStream(file).bufferedReader().use { it.readText() }
        val decrypted = decrypt(encrypted) ?: return emptyMap()
        return decrypted.lines()
            .filter { it.contains("=") }
            .associate {
                val (k, v) = it.split("=", limit = 2)
                k.trim() to v.trim()
            }
    }

    // ── Plaintext fallback file ────────────────────────────────────

    private fun writePlainFile(data: Map<String, String>) {
        val ctx = appContext ?: return
        val file = File(ctx.filesDir, PLAIN_FILE)
        val content = data.entries.joinToString("\n") { (k, v) -> "$k=$v" }
        FileOutputStream(file).use { fos ->
            fos.write(content.toByteArray(Charsets.UTF_8))
        }
    }

    private fun readPlainFile(): Map<String, String> {
        val ctx = appContext ?: return emptyMap()
        val file = File(ctx.filesDir, PLAIN_FILE)
        if (!file.exists()) return emptyMap()
        return file.readText(Charsets.UTF_8).lines()
            .filter { it.contains("=") }
            .associate {
                val (k, v) = it.split("=", limit = 2)
                k.trim() to v.trim()
            }
    }

    // ── Public API ─────────────────────────────────────────────────

    /**
     * Save the currently active [LlmProvider] configuration.
     * API key goes to encrypted/plaintext file (never SharedPreferences).
     * Other settings go to SharedPreferences.
     */
    fun saveProvider(provider: LlmProvider) {
        val p = requirePrefs()
        val apiKeyValue = provider.apiKey
        val baseUrlValue = provider.baseUrl

        // Save non-sensitive settings to SharedPreferences (synchronous commit)
        val committed = p.edit().apply {
            putString(KEY_PROVIDER_TYPE, provider.providerType)
            putString(KEY_BASE_URL, baseUrlValue)
            putString(KEY_MODEL, provider.model)
            putString(KEY_ORGANIZATION, when (provider) {
                is LlmProvider.OpenAi -> provider.organization
                else -> null
            })
            putString(KEY_VERSION, when (provider) {
                is LlmProvider.Anthropic -> provider.version
                else -> null
            })
        }.commit() // synchronous — guarantees disk write
        if (!committed) {
            Log.e(TAG, "SharedPreferences.commit() returned false")
        }

        // Save API key to encrypted/plaintext file
        try {
            if (isUsingPlainStorage) {
                writePlainFile(mapOf<String, String>(KEY_API_KEY to apiKeyValue))
            } else {
                writeEncryptedFile(mapOf<String, String>(KEY_API_KEY to apiKeyValue))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write API key file", e)
        }

        // Update in-memory cache
        cachedApiKey = apiKeyValue
        cachedApiKeyProvider = provider.providerType

        // Verify round-trip
        val savedKey = readApiKeyFromStorage()
        if (savedKey != apiKeyValue) {
            Log.e(TAG, "API KEY CORRUPTION: saved=${apiKeyValue.length}chars, read back=${savedKey?.length ?: 0}chars")
            SessionLog.log(EventType.SETTINGS_SAVE, mapOf(
                "provider" to provider.providerType,
                "result" to "corruption",
                "savedLen" to apiKeyValue.length.toString(),
                "readBackLen" to (savedKey?.length ?: 0).toString(),
                "baseUrl" to baseUrlValue,
                "storage" to if (isUsingPlainStorage) "plain" else "encrypted"
            ))
        } else {
            Log.d(TAG, "Provider saved: ${provider.providerType}, apiKey=${apiKeyValue.take(4)}...${apiKeyValue.takeLast(4)} (${apiKeyValue.length}chars)")
            SessionLog.log(EventType.SETTINGS_SAVE, mapOf(
                "provider" to provider.providerType,
                "result" to "ok",
                "apiKeyLen" to apiKeyValue.length.toString(),
                "apiKeyHead" to apiKeyValue.take(4),
                "apiKeyTail" to apiKeyValue.takeLast(4),
                "baseUrl" to baseUrlValue,
                "storage" to if (isUsingPlainStorage) "plain" else "encrypted"
            ))
        }
    }

    /**
     * Load the previously saved [LlmProvider], or null if none configured yet.
     */
    fun loadProvider(): LlmProvider? {
        val p = requirePrefs()
        val type = p.getString(KEY_PROVIDER_TYPE, null) ?: return null

        val baseUrl = p.getString(KEY_BASE_URL, "") ?: ""
        val model = p.getString(KEY_MODEL, "") ?: ""
        val organization = p.getString(KEY_ORGANIZATION, null)
        val version = p.getString(KEY_VERSION, "2023-06-01") ?: "2023-06-01"

        // Read API key from encrypted/plaintext file (not SharedPreferences)
        val apiKey = readApiKeyFromStorage() ?: ""

        Log.d(TAG, "Loaded provider: type=$type, apiKey=${apiKey.take(4)}...${apiKey.takeLast(4)} (${apiKey.length}chars), baseUrl=$baseUrl")
        SessionLog.log(EventType.SETTINGS_LOAD, mapOf(
            "provider" to type,
            "result" to "ok",
            "apiKeyLen" to apiKey.length.toString(),
            "apiKeyHead" to apiKey.take(4),
            "apiKeyTail" to apiKey.takeLast(4),
            "baseUrl" to baseUrl,
            "storage" to if (isUsingPlainStorage) "plain" else "encrypted"
        ))

        // Update cache
        cachedApiKey = apiKey
        cachedApiKeyProvider = type

        return when (type) {
            LlmProvider.Anthropic.TYPE -> LlmProvider.Anthropic(
                apiKey = apiKey,
                baseUrl = baseUrl.ifEmpty { "https://api.anthropic.com" },
                version = version,
                model = model.ifEmpty { "claude-sonnet-4-20250514" }
            )
            LlmProvider.OpenAi.TYPE -> LlmProvider.OpenAi(
                apiKey = apiKey,
                baseUrl = baseUrl.ifEmpty { "https://api.openai.com" },
                organization = organization,
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
            else -> {
                Log.w(TAG, "Unknown provider type: $type")
                null
            }
        }
    }

    /**
     * Read API key from the appropriate storage (encrypted or plaintext file).
     */
    private fun readApiKeyFromStorage(): String? {
        // Check cache first — verify provider type matches
        if (cachedApiKey != null && cachedApiKeyProvider != null) {
            val currentType = requirePrefs().getString(KEY_PROVIDER_TYPE, null)
            if (currentType == cachedApiKeyProvider) {
                return cachedApiKey
            }
            // Cache is stale — clear and re-read
            cachedApiKey = null
            cachedApiKeyProvider = null
        }

        return try {
            if (isUsingPlainStorage) {
                readPlainFile()[KEY_API_KEY]
            } else {
                readEncryptedFile()[KEY_API_KEY]
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read API key from storage", e)
            null
        }
    }

    /**
     * Clear all stored provider settings.
     */
    fun clear() {
        val wasAgentModeDefault = requirePrefs().getBoolean(KEY_AGENT_MODE_DEFAULT, true)
        requirePrefs().edit().clear().commit()
        // Preserve agent mode default preference after clear
        requirePrefs().edit().putBoolean(KEY_AGENT_MODE_DEFAULT, wasAgentModeDefault).commit()

        // Delete key files
        appContext?.let { ctx ->
            File(ctx.filesDir, ENCRYPTED_FILE).delete()
            File(ctx.filesDir, PLAIN_FILE).delete()
        }

        cachedApiKey = null
        cachedApiKeyProvider = null

        Log.d(TAG, "Provider settings cleared")
    }

    /**
     * Save custom system prompt instructions.
     */
    fun saveCustomPrompt(prompt: String) {
        requirePrefs().edit().putString(KEY_CUSTOM_PROMPT, prompt).commit()
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

    /** Default agent mode (true = Act, false = View). */
    var agentModeDefault: Boolean
        get() = requirePrefs().getBoolean(KEY_AGENT_MODE_DEFAULT, true)
        set(value) { requirePrefs().edit().putBoolean(KEY_AGENT_MODE_DEFAULT, value).commit() }

    /** Maximum agent loop iterations (3-30, default 10). */
    var maxIterations: Int
        get() = requirePrefs().getInt(KEY_MAX_ITERATIONS, DEFAULT_MAX_ITERATIONS)
        set(value) { requirePrefs().edit().putInt(
            KEY_MAX_ITERATIONS,
            value.coerceIn(MIN_MAX_ITERATIONS, MAX_MAX_ITERATIONS)
        ).commit() }

    // ── Migration from old EncryptedSharedPreferences ──────────────

    /**
     * Attempt to migrate data from the old EncryptedSharedPreferences-based storage.
     * Old storage used the same FILE_NAME for both encrypted and plain modes,
     * which caused data loss when modes switched.
     */
    private fun migrateFromEncryptedSharedPreferences(context: Context) {
        // Check if new storage already has data
        if (readApiKeyFromStorage()?.isNotBlank() == true) return

        // Try old EncryptedSharedPreferences
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val oldPrefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                context,
                "devcompanion_llm_settings", // old file name
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            val oldApiKey = oldPrefs.getString(KEY_API_KEY, "") ?: ""
            val oldType = oldPrefs.getString(KEY_PROVIDER_TYPE, null)

            if (oldType != null && oldApiKey.isNotBlank()) {
                Log.i(TAG, "Migrating API key from old EncryptedSharedPreferences")
                // Save to new storage via normal path
                val oldBaseUrl = oldPrefs.getString(KEY_BASE_URL, "") ?: ""
                val oldModel = oldPrefs.getString(KEY_MODEL, "") ?: ""

                // Create provider and save through new path
                val provider = when (oldType) {
                    LlmProvider.Ollama.TYPE -> LlmProvider.Ollama(
                        apiKey = oldApiKey,
                        baseUrl = oldBaseUrl.ifEmpty { LlmProvider.Ollama.DEFAULT_BASE_URL },
                        model = oldModel.ifEmpty { "glm-5.1" }
                    )
                    LlmProvider.Anthropic.TYPE -> LlmProvider.Anthropic(
                        apiKey = oldApiKey,
                        baseUrl = oldBaseUrl.ifEmpty { "https://api.anthropic.com" }
                    )
                    LlmProvider.OpenAi.TYPE -> LlmProvider.OpenAi(
                        apiKey = oldApiKey,
                        baseUrl = oldBaseUrl.ifEmpty { "https://api.openai.com" }
                    )
                    LlmProvider.Gemini.TYPE -> LlmProvider.Gemini(
                        apiKey = oldApiKey,
                        baseUrl = oldBaseUrl.ifEmpty { "https://generativelanguage.googleapis.com/v1beta" },
                        model = oldModel.ifEmpty { "gemini-2.5-flash" }
                    )
                    else -> return
                }

                saveProvider(provider)
                SessionLog.log(EventType.SETTINGS_INIT, mapOf(
                    "storage" to "migrated_from_encrypted_sp",
                    "result" to "ok",
                    "provider" to oldType,
                    "apiKeyLen" to oldApiKey.length.toString()
                ))
            }
        } catch (e: Exception) {
            // Old storage unreadable — this is expected if master key changed
            Log.w(TAG, "Could not read old EncryptedSharedPreferences (expected if key rotated)", e)
            SessionLog.log(EventType.SETTINGS_INIT, mapOf(
                "storage" to "migration_failed",
                "result" to "old_unreadable",
                "error" to (e.message?.take(80) ?: "unknown")
            ))
        }

        // Also try old plaintext SharedPreferences (from fallback mode)
        try {
            val oldPlainPrefs = context.getSharedPreferences("devcompanion_llm_settings", Context.MODE_PRIVATE)
            val oldApiKey = oldPlainPrefs.getString(KEY_API_KEY, "") ?: ""
            val oldType = oldPlainPrefs.getString(KEY_PROVIDER_TYPE, null)

            if (oldType != null && oldApiKey.isNotBlank() && readApiKeyFromStorage().isNullOrBlank()) {
                Log.i(TAG, "Migrating API key from old plaintext SharedPreferences")
                val provider = when (oldType) {
                    LlmProvider.Ollama.TYPE -> LlmProvider.Ollama(apiKey = oldApiKey, baseUrl = oldPlainPrefs.getString(KEY_BASE_URL, "")?.ifEmpty { LlmProvider.Ollama.DEFAULT_BASE_URL } ?: LlmProvider.Ollama.DEFAULT_BASE_URL, model = oldPlainPrefs.getString(KEY_MODEL, "glm-5.1") ?: "glm-5.1")
                    LlmProvider.Anthropic.TYPE -> LlmProvider.Anthropic(apiKey = oldApiKey, baseUrl = oldPlainPrefs.getString(KEY_BASE_URL, "")?.ifEmpty { "https://api.anthropic.com" } ?: "https://api.anthropic.com")
                    LlmProvider.OpenAi.TYPE -> LlmProvider.OpenAi(apiKey = oldApiKey, baseUrl = oldPlainPrefs.getString(KEY_BASE_URL, "")?.ifEmpty { "https://api.openai.com" } ?: "https://api.openai.com")
                    LlmProvider.Gemini.TYPE -> LlmProvider.Gemini(apiKey = oldApiKey, baseUrl = oldPlainPrefs.getString(KEY_BASE_URL, "")?.ifEmpty { "https://generativelanguage.googleapis.com/v1beta" } ?: "https://generativelanguage.googleapis.com/v1beta", model = oldPlainPrefs.getString(KEY_MODEL, "gemini-2.5-flash") ?: "gemini-2.5-flash")
                    else -> return
                }
                saveProvider(provider)
                SessionLog.log(EventType.SETTINGS_INIT, mapOf(
                    "storage" to "migrated_from_plain_sp",
                    "result" to "ok",
                    "provider" to oldType
                ))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read old plaintext SharedPreferences", e)
        }
    }
}