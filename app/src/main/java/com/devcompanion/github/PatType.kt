package com.devcompanion.github

/**
 * GitHub PAT type discriminator.
 *
 * Classic PATs use scope-based permissions (ghp_ prefix).
 * Fine-grained PATs use repository+permission mapping (github_pat_ prefix).
 */
sealed class PatType {

    /** Classic personal access token with scope-based permissions. */
    data class Classic(
        val scopes: Set<ClassicScope>
    ) : PatType()

    /** Fine-grained personal access token with per-repository permissions. */
    data class FineGrained(
        val repositories: List<String>,  // "owner/repo" format; empty = all repos
        val permissions: Set<FineGrainedPermission>
    ) : PatType()

    /** Whether this PAT has write-level permissions. */
    fun canWrite(): Boolean = when (this) {
        is Classic -> scopes.contains(ClassicScope.REPO_WRITE) || scopes.contains(ClassicScope.REPO_ADMIN)
        is FineGrained -> permissions.contains(FineGrainedPermission.ISSUES_WRITE) ||
                permissions.contains(FineGrainedPermission.PULL_REQUESTS_WRITE)
    }

    /** Whether this PAT can read repository contents. */
    fun canRead(): Boolean = when (this) {
        is Classic -> scopes.contains(ClassicScope.PUBLIC_REPO) ||
                scopes.contains(ClassicScope.REPO) ||
                scopes.contains(ClassicScope.REPO_WRITE) ||
                scopes.contains(ClassicScope.REPO_ADMIN)
        is FineGrained -> permissions.contains(FineGrainedPermission.CONTENTS_READ) ||
                permissions.contains(FineGrainedPermission.METADATA_READ)
    }

    /** Human-readable summary of permissions for UI display. */
    fun summary(): String = when (this) {
        is Classic -> scopes.joinToString(", ") { it.label }
        is FineGrained -> {
            val repoLabel = if (repositories.isEmpty()) "All repos" else repositories.take(3).joinToString()
            "$repoLabel — ${permissions.map { it.label }.joinToString(", ")}"
        }
    }

    enum class ClassicScope(val label: String) {
        PUBLIC_REPO("Public repos"),
        REPO("All repos (read)"),
        REPO_WRITE("All repos (write)"),
        REPO_ADMIN("All repos (admin)")
    }

    enum class FineGrainedPermission(val label: String) {
        CONTENTS_READ("Contents: read"),
        ISSUES_READ("Issues: read"),
        ISSUES_WRITE("Issues: write"),
        PULL_REQUESTS_READ("PRs: read"),
        PULL_REQUESTS_WRITE("PRs: write"),
        METADATA_READ("Metadata: read")
    }
}

/**
 * GitHub PAT credential with validation state.
 */
data class GitHubPatCredential(
    val token: String,
    val patType: PatType,
    val validatedAt: Long? = null,
    val expiresAt: Long? = null
) {
    companion object {
        /** Detect PAT type from token prefix and create appropriate credential. */
        fun fromToken(token: String, scopes: Set<PatType.ClassicScope> = emptySet()): GitHubPatCredential {
            val patType = if (token.startsWith("github_pat_")) {
                // Fine-grained PAT — actual permissions fetched from API later
                PatType.FineGrained(
                    repositories = emptyList(),
                    permissions = setOf(PatType.FineGrainedPermission.METADATA_READ)
                )
            } else {
                PatType.Classic(scopes = scopes)
            }
            return GitHubPatCredential(token = token, patType = patType)
        }

        /** Mask token for safe display: show first 4 and last 4 chars. */
        fun masked(credential: GitHubPatCredential): String {
            val t = credential.token
            if (t.length <= 8) return "••••••••"
            return "${t.take(4)}${"•".repeat(t.length - 8)}${t.takeLast(4)}"
        }
    }
}