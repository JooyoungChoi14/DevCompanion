package com.devcompanion.github.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.devcompanion.github.GitHubPatCredential
import com.devcompanion.github.GitHubPatValidator
import com.devcompanion.github.GitHubSettings
import com.devcompanion.github.PatType
import com.devcompanion.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * GitHub PAT configuration section for the Settings sheet.
 *
 * Security requirements:
 * - PAT input is always password-type (hidden by default)
 * - PAT is stored in EncryptedSharedPreferences, never in plaintext
 * - PAT is NEVER exposed to the LLM prompt
 * - Default mode is read-only
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubPatSection() {
    val scope = rememberCoroutineScope()

    var patInput by remember { mutableStateOf("") }
    var patVisible by remember { mutableStateOf(false) }
    var isValidating by remember { mutableStateOf(false) }
    var validationResult by remember { mutableStateOf<GitHubPatValidator.ValidationResult?>(null) }
    var savedCredential by remember { mutableStateOf<GitHubPatCredential?>(null) }

    // Load saved credential on first composition
    LaunchedEffect(Unit) {
        savedCredential = GitHubSettings.loadPat()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Text("GitHub Integration", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                "Connect a Personal Access Token for repo context injection. " +
                "PAT is never shared with the LLM — the app proxies all API calls.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(Spacing.sm))

            // ── Current PAT status ──────────────────────────────────
            savedCredential?.let { cred ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "PAT configured",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "${GitHubPatCredential.masked(cred)} · ${cred.patType.summary()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    IconButton(onClick = {
                        GitHubSettings.clearPat()
                        savedCredential = null
                        validationResult = null
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove PAT",
                            tint = MaterialTheme.colorScheme.error)
                    }
                }

                // Validation status
                if (GitHubSettings.isPatValidated) {
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Verified, contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        Text("Verified", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.sm))
            }

            // ── PAT input ──────────────────────────────────────────
            if (savedCredential == null) {
                OutlinedTextField(
                    value = patInput,
                    onValueChange = { patInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Personal Access Token") },
                    placeholder = { Text("ghp_... or github_pat_...") },
                    visualTransformation = if (patVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (patInput.isNotBlank() && !isValidating) {
                                isValidating = true
                                scope.launch {
                                    val result = GitHubPatValidator.validate(patInput)
                                    validationResult = result
                                    isValidating = false
                                    if (result is GitHubPatValidator.ValidationResult.Valid) {
                                        val credential = GitHubPatCredential(
                                            token = patInput,
                                            patType = result.patType,
                                            validatedAt = System.currentTimeMillis(),
                                            expiresAt = result.expiresAt
                                        )
                                        GitHubSettings.savePat(credential)
                                        savedCredential = credential
                                        patInput = ""
                                    }
                                }
                            }
                        }
                    ),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    trailingIcon = {
                        IconButton(onClick = { patVisible = !patVisible }) {
                            Icon(
                                if (patVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (patVisible) "Hide token" else "Show token"
                            )
                        }
                    }
                )

                Spacer(modifier = Modifier.height(Spacing.xs))

                // Validation result feedback
                AnimatedVisibility(
                    visible = validationResult != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    when (val result = validationResult) {
                        is GitHubPatValidator.ValidationResult.Valid -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(Spacing.xs))
                                Text("Authenticated as @${result.username}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        is GitHubPatValidator.ValidationResult.Invalid -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Error, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(Spacing.xs))
                                Text(result.reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error)
                            }
                        }
                        is GitHubPatValidator.ValidationResult.Error -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(Spacing.xs))
                                Text(result.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline)
                            }
                        }
                        null -> {}
                    }
                }

                // Validate button
                if (patInput.isNotBlank() && savedCredential == null) {
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Button(
                        onClick = {
                            isValidating = true
                            scope.launch {
                                val result = GitHubPatValidator.validate(patInput)
                                validationResult = result
                                isValidating = false
                                if (result is GitHubPatValidator.ValidationResult.Valid) {
                                    val credential = GitHubPatCredential(
                                        token = patInput,
                                        patType = result.patType,
                                        validatedAt = System.currentTimeMillis(),
                                        expiresAt = result.expiresAt
                                    )
                                    GitHubSettings.savePat(credential)
                                    savedCredential = credential
                                    patInput = ""
                                }
                            }
                        },
                        enabled = !isValidating,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isValidating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(Spacing.xs))
                            Text("Validating…")
                        } else {
                            Icon(Icons.Default.VerifiedUser, contentDescription = null)
                            Spacer(modifier = Modifier.width(Spacing.xs))
                            Text("Validate & Save")
                        }
                    }
                }
            }

            // ── Default repo ────────────────────────────────────────
            Spacer(modifier = Modifier.height(Spacing.sm))

            var defaultRepo by remember { mutableStateOf(GitHubSettings.defaultRepo) }
            OutlinedTextField(
                value = defaultRepo,
                onValueChange = {
                    defaultRepo = it
                    GitHubSettings.defaultRepo = it
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Default Repository") },
                placeholder = { Text("owner/repo") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                enabled = savedCredential != null
            )

            // ── Read-only mode toggle ────────────────────────────────
            Spacer(modifier = Modifier.height(Spacing.sm))

            var readOnly by remember { mutableStateOf(GitHubSettings.readOnlyMode) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Read-only mode", style = MaterialTheme.typography.bodyMedium)
                    Text("Block all write actions (issues, PRs, pushes)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = readOnly,
                    onCheckedChange = {
                        readOnly = it
                        GitHubSettings.readOnlyMode = it
                    }
                )
            }

            // ── Security notice ─────────────────────────────────────
            if (GitHubSettings.isUsingPlainStorage) {
                Spacer(modifier = Modifier.height(Spacing.sm))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        Text(
                            "Device doesn't support encrypted storage. " +
                            "PAT is stored in plaintext. Use with caution.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
    }
}