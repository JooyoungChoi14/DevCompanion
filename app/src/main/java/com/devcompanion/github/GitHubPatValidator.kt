package com.devcompanion.github

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Validates GitHub PAT by calling the GitHub API.
 * Uses the token to fetch the authenticated user and token metadata.
 *
 * PAT is NEVER sent to the LLM — only used here for validation
 * and later for proxied GitHub API calls.
 */
object GitHubPatValidator {

    private const val TAG = "GitHubPatValidator"
    private const val GITHUB_API = "https://api.github.com"

    /** Result of a PAT validation attempt. */
    sealed class ValidationResult {
        data class Valid(
            val username: String,
            val scopes: Set<PatType.ClassicScope>,
            val patType: PatType,
            val expiresAt: Long?
        ) : ValidationResult()

        data class Invalid(val reason: String) : ValidationResult()
        data class Error(val message: String) : ValidationResult()
    }

    /**
     * Validate a PAT by calling the GitHub API.
     * Must be called from a coroutine scope.
     */
    suspend fun validate(token: String): ValidationResult = withContext(Dispatchers.IO) {
        try {
            // Step 1: Fetch authenticated user
            val conn = URL("$GITHUB_API/user").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "token $token")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.w(TAG, "PAT validation failed: $responseCode — $errorBody")
                return@withContext when (responseCode) {
                    401 -> ValidationResult.Invalid("Invalid token")
                    403 -> ValidationResult.Invalid("Token lacks required scopes")
                    else -> ValidationResult.Error("HTTP $responseCode: $errorBody")
                }
            }

            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val username = json.optString("login", "unknown")

            // Step 2: Determine PAT type and scopes
            val scopes = parseScopes(conn.getHeaderField("X-OAuth-Scopes"))
            val isFineGrained = token.startsWith("github_pat_")

            val patType = if (isFineGrained) {
                // Fine-grained PAT: fetch permissions from /user/installations or infer from token
                // For now, we set minimal default and will enrich via API later
                PatType.FineGrained(
                    repositories = emptyList(),
                    permissions = setOf(PatType.FineGrainedPermission.METADATA_READ)
                )
            } else {
                PatType.Classic(scopes = scopes)
            }

            // Step 3: Check rate limit headers for expiry hints
            val expiresAt = conn.getHeaderField("github-authentication-token-expiration")?.toLongOrNull()

            conn.disconnect()

            Log.i(TAG, "PAT validated: user=$username, type=${if (isFineGrained) "fine_grained" else "classic"}, scopes=${scopes.map { it.name }}")
            ValidationResult.Valid(
                username = username,
                scopes = scopes,
                patType = patType,
                expiresAt = expiresAt
            )
        } catch (e: Exception) {
            Log.e(TAG, "PAT validation error", e)
            ValidationResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Parse X-OAuth-Scopes header into ClassicScope set.
     * Header format: "repo,public_repo,user" (comma-separated)
     */
    private fun parseScopes(scopesHeader: String?): Set<PatType.ClassicScope> {
        if (scopesHeader.isNullOrBlank()) return emptySet()

        val rawScopes = scopesHeader.split(",").map { it.trim() }.toSet()
        val mappedScopes = mutableSetOf<PatType.ClassicScope>()

        // Map GitHub API scopes to our enum
        // repo scope implies all repo sub-scopes
        if (rawScopes.contains("repo")) {
            mappedScopes.add(PatType.ClassicScope.REPO_ADMIN)
        } else {
            if (rawScopes.contains("public_repo")) mappedScopes.add(PatType.ClassicScope.PUBLIC_REPO)
            if (rawScopes.contains("repo:status") || rawScopes.contains("repo_deployment")) {
                mappedScopes.add(PatType.ClassicScope.REPO)
            }
        }

        return mappedScopes
    }
}