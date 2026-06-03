package com.devcompanion.github

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.devcompanion.github.PatType.*

/**
 * Manages persistent storage of GitHub PAT credentials using
 * [EncryptedSharedPreferences] — same pattern as [LlmSettings].
 *
 * PAT is NEVER exposed to the LLM. The app proxies all GitHub API calls.
 */
object GitHubSettings {

    private const val TAG = "GitHubSettings"
    private const val FILE_NAME = "devcompanion_github_settings"
    private const val KEY_PAT_TOKEN = "github_pat_token"
    private const val KEY_PAT_TYPE = "github_pat_type"       // "classic" or "fine_grained"
    private const val KEY_PAT_VALIDATED = "pat_validated"
    private const val KEY_PAT_VALIDATED_AT = "pat_validated_at"
    private const val KEY_PAT_EXPIRES_AT = "pat_expires_at"
    private const val KEY_PAT_SCOPES = "pat_scopes"           // comma-separated ClassicScope names
    private const val KEY_PAT_FG_REPOS = "pat_fg_repos"      // comma-separated "owner/repo"
    private const val KEY_PAT_FG_PERMS = "pat_fg_perms"       // comma-separated FineGrainedPermission names
    private const val KEY_DEFAULT_REPO = "default_repo"        // "owner/repo"
    private const val KEY_READ_ONLY_MODE = "read_only_mode"

    @Volatile
    private var prefs: SharedPreferences? = null

    /** Whether we're using plaintext fallback (no secure enclave). */
    var isUsingPlainStorage: Boolean = false
        private set

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
            Log.i(TAG, "GitHub settings initialized (encrypted)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize encrypted preferences — falling back to standard", e)
            prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            isUsingPlainStorage = true
        }
    }

    private fun requirePrefs(): SharedPreferences {
        return prefs ?: error("GitHubSettings not initialized — call initialize(context) first")
    }

    // ── PAT CRUD ──────────────────────────────────────────────────

    /** Save a GitHub PAT credential. */
    fun savePat(credential: GitHubPatCredential) {
        val p = requirePrefs()
        p.edit().apply {
            putString(KEY_PAT_TOKEN, credential.token)
            putString(KEY_PAT_TYPE, when (credential.patType) {
                is PatType.Classic -> "classic"
                is PatType.FineGrained -> "fine_grained"
            })
            putBoolean(KEY_PAT_VALIDATED, credential.validatedAt != null)
            putLong(KEY_PAT_VALIDATED_AT, credential.validatedAt ?: 0L)
            putLong(KEY_PAT_EXPIRES_AT, credential.expiresAt ?: 0L)

            when (credential.patType) {
                is PatType.Classic -> {
                    putString(KEY_PAT_SCOPES, credential.patType.scopes.joinToString(",") { it.name })
                    remove(KEY_PAT_FG_REPOS)
                    remove(KEY_PAT_FG_PERMS)
                }
                is PatType.FineGrained -> {
                    putString(KEY_PAT_FG_REPOS, credential.patType.repositories.joinToString(","))
                    putString(KEY_PAT_FG_PERMS, credential.patType.permissions.joinToString(",") { it.name })
                    remove(KEY_PAT_SCOPES)
                }
            }
            apply()
        }
        Log.d(TAG, "PAT saved (type=${when (credential.patType) {
            is PatType.Classic -> "classic"
            is PatType.FineGrained -> "fine_grained"
        }})")
    }

    /** Load the saved PAT credential, or null if none configured. */
    fun loadPat(): GitHubPatCredential? {
        val p = requirePrefs()
        val token = p.getString(KEY_PAT_TOKEN, null) ?: return null
        if (token.isBlank()) return null

        val typeStr = p.getString(KEY_PAT_TYPE, "classic") ?: "classic"
        val validatedAt = p.getLong(KEY_PAT_VALIDATED_AT, 0L).takeIf { it > 0 }
        val expiresAt = p.getLong(KEY_PAT_EXPIRES_AT, 0L).takeIf { it > 0 }

        val patType = when (typeStr) {
            "fine_grained" -> {
                val repos = p.getString(KEY_PAT_FG_REPOS, "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                val perms = p.getString(KEY_PAT_FG_PERMS, "")?.split(",")
                    ?.mapNotNull { runCatching { FineGrainedPermission.valueOf(it) }.getOrNull() }
                    ?.toSet() ?: emptySet()
                PatType.FineGrained(repositories = repos, permissions = perms)
            }
            else -> {
                val scopes = p.getString(KEY_PAT_SCOPES, "")?.split(",")
                    ?.mapNotNull { runCatching { ClassicScope.valueOf(it) }.getOrNull() }
                    ?.toSet() ?: emptySet()
                PatType.Classic(scopes = scopes)
            }
        }

        return GitHubPatCredential(
            token = token,
            patType = patType,
            validatedAt = validatedAt,
            expiresAt = expiresAt
        )
    }

    /** Clear the saved PAT. */
    fun clearPat() {
        requirePrefs().edit().apply {
            remove(KEY_PAT_TOKEN)
            remove(KEY_PAT_TYPE)
            remove(KEY_PAT_VALIDATED)
            remove(KEY_PAT_VALIDATED_AT)
            remove(KEY_PAT_EXPIRES_AT)
            remove(KEY_PAT_SCOPES)
            remove(KEY_PAT_FG_REPOS)
            remove(KEY_PAT_FG_PERMS)
            apply()
        }
        Log.d(TAG, "PAT cleared")
    }

    /** Whether a PAT is currently configured. */
    fun hasPat(): Boolean = requirePrefs().getString(KEY_PAT_TOKEN, null)?.isNotBlank() == true

    // ── Default repo ───────────────────────────────────────────────

    var defaultRepo: String
        get() = requirePrefs().getString(KEY_DEFAULT_REPO, "") ?: ""
        set(value) { requirePrefs().edit().putString(KEY_DEFAULT_REPO, value).commit() }

    // ── Read-only mode ─────────────────────────────────────────────

    /** When true, all write actions are blocked regardless of PAT scopes. */
    var readOnlyMode: Boolean
        get() = requirePrefs().getBoolean(KEY_READ_ONLY_MODE, true)  // Default: read-only
        set(value) { requirePrefs().edit().putBoolean(KEY_READ_ONLY_MODE, value).commit() }

    // ── Validation status ──────────────────────────────────────────

    /** Whether the saved PAT has been validated against the GitHub API. */
    val isPatValidated: Boolean
        get() = requirePrefs().getBoolean(KEY_PAT_VALIDATED, false)

    /** Mark the PAT as validated (or invalidated). */
    fun setPatValidated(valid: Boolean) {
        requirePrefs().edit()
            .putBoolean(KEY_PAT_VALIDATED, valid)
            .putLong(KEY_PAT_VALIDATED_AT, if (valid) System.currentTimeMillis() else 0L)
            .commit()
    }
}