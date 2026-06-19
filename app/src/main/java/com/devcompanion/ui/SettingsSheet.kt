package com.devcompanion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.devcompanion.github.ui.GitHubPatSection
import com.devcompanion.llm.LlmProvider
import com.devcompanion.logging.SessionLog
import com.devcompanion.ui.theme.*
import kotlinx.coroutines.launch

private val RECOMMENDED_MODELS = mapOf(
    LlmProvider.Anthropic.TYPE to listOf(
        "claude-sonnet-4-20250514",
        "claude-opus-4-20250514",
        "claude-3.5-haiku-20241022"
    ),
    LlmProvider.OpenAi.TYPE to listOf(
        "gpt-4o",
        "gpt-4o-mini",
        "gpt-4.5-preview",
        "o3",
        "o4-mini"
    ),
    LlmProvider.Ollama.TYPE to listOf(
        "glm-5.1",
        "deepseek-v4-pro",
        "deepseek-v4-flash",
        "kimi-k2.5",
        "kimi-k2.6",
        "minimax-m2.7",
        "qwen3.5:397b",
        "qwen3-coder:480b",
        "devstral-2:123b",
        "llava"
    ),
    LlmProvider.Gemini.TYPE to listOf(
        "gemini-2.5-flash",
        "gemini-2.5-pro",
        "gemini-2.0-flash"
    )
)

// ── Tab indices ──────────────────────────────────────────────
const val SETTINGS_TAB_APPEARANCE = 0
const val SETTINGS_TAB_AI = 1
const val SETTINGS_TAB_INTEGRATIONS = 2

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(
    onDismiss: () -> Unit,
    initialTab: Int = SETTINGS_TAB_APPEARANCE,
    settingsViewModel: SettingsViewModel = viewModel(),
    // AiChatViewModel kept for backward compat — only used to sync runtime provider
    viewModel: AiChatViewModel? = null,
) {
    var selectedTab by remember { mutableStateOf(initialTab.coerceIn(0, 2)) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.md)
    ) {
        Text("Settings", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)

        Spacer(modifier = Modifier.height(Spacing.sm))

        // ── Tab row ──────────────────────────────────────────────
        SecondaryTabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Tab(
                selected = selectedTab == SETTINGS_TAB_APPEARANCE,
                onClick = { selectedTab = SETTINGS_TAB_APPEARANCE; SessionLog.uiTab("settings", "appearance") },
                text = { Text("Appearance") },
                icon = { Icon(Icons.Default.Palette, contentDescription = null, modifier = Modifier.size(18.dp)) },
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Tab(
                selected = selectedTab == SETTINGS_TAB_AI,
                onClick = { selectedTab = SETTINGS_TAB_AI; SessionLog.uiTab("settings", "ai") },
                text = { Text("AI") },
                icon = { Icon(Icons.Default.SmartToy, contentDescription = null, modifier = Modifier.size(18.dp)) },
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Tab(
                selected = selectedTab == SETTINGS_TAB_INTEGRATIONS,
                onClick = { selectedTab = SETTINGS_TAB_INTEGRATIONS; SessionLog.uiTab("settings", "integrations") },
                text = { Text("Integrations") },
                icon = { Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(18.dp)) },
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(Spacing.md))

        // ── Tab content ──────────────────────────────────────────
        when (selectedTab) {
            SETTINGS_TAB_APPEARANCE -> AppearanceTab()
            SETTINGS_TAB_AI -> AiTab(
                settingsViewModel = settingsViewModel,
                chatViewModel = viewModel,
            )
            SETTINGS_TAB_INTEGRATIONS -> IntegrationsTab()
        }
    }
    } // ModalBottomSheet
}

// ═══════════════════════════════════════════════════════════════
// Tab 1: Appearance
// ═══════════════════════════════════════════════════════════════
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceTab() {
    val themePrefs = LocalThemePreferences.current
    val scope = rememberCoroutineScope()

    val currentPreset by themePrefs.preset.collectAsState(initial = ColorPreset.DRACULA)
    val currentDarkMode by themePrefs.darkMode.collectAsState(initial = "system")

    // M3: Auto-override dark mode when Solarized Light is selected
    val effectiveDarkMode = when {
        currentPreset == ColorPreset.SOLARIZED_LIGHT && currentDarkMode == "dark" -> "light"
        else -> currentDarkMode
    }
    LaunchedEffect(effectiveDarkMode) {
        if (effectiveDarkMode != currentDarkMode) {
            themePrefs.setDarkMode(effectiveDarkMode)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(Spacing.md)) {
                Text("Theme", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(Spacing.sm))

                Text("Appearance", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(Spacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    listOf("system" to "System", "dark" to "Dark", "light" to "Light").forEach { (mode, label) ->
                        val selected = effectiveDarkMode == mode
                        FilterChip(
                            selected = selected,
                            onClick = { scope.launch { themePrefs.setDarkMode(mode) } },
                            label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selected,
                                borderColor = MaterialTheme.colorScheme.outline,
                                selectedBorderColor = MaterialTheme.colorScheme.primary,
                                borderWidth = 1.dp,
                                selectedBorderWidth = 1.dp,
                            ),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                                containerColor = MaterialTheme.colorScheme.surface,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.md))

                Text("Color Palette", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(Spacing.xs))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    ColorPreset.entries.forEach { preset ->
                        val selected = currentPreset == preset
                        FilterChip(
                            selected = selected,
                            onClick = { scope.launch { themePrefs.setPreset(preset) } },
                            label = { Text(preset.label, style = MaterialTheme.typography.labelMedium) },
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selected,
                                borderColor = MaterialTheme.colorScheme.outline,
                                selectedBorderColor = MaterialTheme.colorScheme.primary,
                                borderWidth = 1.dp,
                                selectedBorderWidth = 1.dp,
                            ),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                                containerColor = MaterialTheme.colorScheme.surface,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        )
                    }
                }

                if (currentPreset == ColorPreset.SOLARIZED_LIGHT && currentDarkMode == "dark") {
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text("⚠ Solarized Light forces Light appearance",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Tab 2: AI — now observes SettingsViewModel, never touches LlmSettings directly
// ═══════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiTab(
    settingsViewModel: SettingsViewModel,
    chatViewModel: AiChatViewModel?,
) {
    // ── All form state from ViewModel ─────────────────────────────
    val formState by settingsViewModel.formState.collectAsState()
    val testing by settingsViewModel.testing.collectAsState()
    val testResult by settingsViewModel.testResult.collectAsState()
    val usingPlainStorage by settingsViewModel.usingPlainStorage.collectAsState()
    val customPrompt by settingsViewModel.customPrompt.collectAsState()
    val maxIter by settingsViewModel.maxIterations.collectAsState()

    // ── Local UI-only state ───────────────────────────────────────
    var showApiKey by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        // ── Provider config Card ──────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(Spacing.md)) {
                Text("Provider", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface)

                // Security warning for plaintext storage
                if (usingPlainStorage) {
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                "Encrypted storage unavailable — API keys stored in plaintext",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.sm))

                // Provider type selector
                Text("Type", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(Spacing.xs))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    listOf(
                        LlmProvider.Anthropic.TYPE to "Anthropic",
                        LlmProvider.OpenAi.TYPE to "OpenAI",
                        LlmProvider.Ollama.TYPE to "Ollama",
                        LlmProvider.Gemini.TYPE to "Gemini"
                    ).forEach { (type, label) ->
                        FilterChip(
                            selected = formState.providerType == type,
                            onClick = { settingsViewModel.updateProviderType(type); SessionLog.uiClick("provider_type", type) },
                            label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.md))

                // API Key
                OutlinedTextField(
                    value = formState.apiKey,
                    onValueChange = { settingsViewModel.updateApiKey(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Key") },
                    placeholder = { Text(
                        when (formState.providerType) {
                            LlmProvider.Ollama.TYPE -> "Enter your Ollama Cloud API key"
                            else -> "Enter your API key"
                        }
                    )},
                    visualTransformation = if (showApiKey) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showApiKey) "Hide" else "Show"
                            )
                        }
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                )

                Spacer(modifier = Modifier.height(Spacing.sm))

                // Base URL (Ollama and Gemini only)
                if (formState.providerType == LlmProvider.Ollama.TYPE || formState.providerType == LlmProvider.Gemini.TYPE) {
                    OutlinedTextField(
                        value = formState.baseUrl,
                        onValueChange = { settingsViewModel.updateBaseUrl(it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Base URL") },
                        placeholder = { Text(
                            when (formState.providerType) {
                                LlmProvider.Ollama.TYPE -> "https://ollama.com"
                                LlmProvider.Gemini.TYPE -> "https://generativelanguage.googleapis.com/v1beta"
                                else -> ""
                            }
                        )},
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                }

                // Model dropdown
                ExposedDropdownMenuBox(
                    expanded = modelMenuExpanded,
                    onExpandedChange = { modelMenuExpanded = !modelMenuExpanded }
                ) {
                    OutlinedTextField(
                        value = formState.model,
                        onValueChange = { settingsViewModel.updateModel(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        label = { Text("Model") },
                        placeholder = { Text(
                            when (formState.providerType) {
                                LlmProvider.Anthropic.TYPE -> "claude-sonnet-4-20250514"
                                LlmProvider.OpenAi.TYPE -> "gpt-4o"
                                LlmProvider.Ollama.TYPE -> "glm-5.1"
                                LlmProvider.Gemini.TYPE -> "gemini-2.5-flash"
                                else -> ""
                            }
                        )},
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded) }
                    )
                    ExposedDropdownMenu(
                        expanded = modelMenuExpanded,
                        onDismissRequest = { modelMenuExpanded = false }
                    ) {
                        RECOMMENDED_MODELS[formState.providerType]?.forEach { modelName ->
                            DropdownMenuItem(
                                text = { Text(modelName, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) },
                                onClick = {
                                    settingsViewModel.updateModel(modelName)
                                    modelMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.sm))

                // Save & Test row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Button(
                        onClick = {
                            val result = settingsViewModel.save()
                            SessionLog.uiClick("settings_save", when (result) { is SaveResult.Success -> "ok"; is SaveResult.Error -> result.message.take(50) })
                            val toastMsg = when (result) {
                                is SaveResult.Success -> "✓ Saved"
                                is SaveResult.Error -> "✗ ${result.message}"
                            }
                            android.widget.Toast.makeText(context, toastMsg, android.widget.Toast.LENGTH_SHORT).show()
                            // Sync runtime provider if AiChatViewModel available
                            val provider = formState.toProvider()
                            if (provider != null) {
                                chatViewModel?.setProvider(provider, persist = false)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        Text("Save")
                    }

                    OutlinedButton(
                        onClick = { settingsViewModel.testConnection(); SessionLog.uiClick("test_connection") },
                        modifier = Modifier.weight(1f),
                        enabled = formState.apiKey.isNotBlank() && !testing
                    ) {
                        if (testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        } else {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        Text("Test")
                    }
                }

                // Test result
                testResult?.let { result ->
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (result.startsWith("✓"))
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            result,
                            modifier = Modifier.padding(Spacing.sm),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.md))

        // ── Custom Instructions Card ──────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(Spacing.md)) {
                Text("Custom Instructions", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    "Appended to the system prompt. Define role, tone, or constraints.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(Spacing.sm))

                OutlinedTextField(
                    value = customPrompt,
                    onValueChange = { settingsViewModel.updateCustomPrompt(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    placeholder = { Text("e.g., Always respond in Korean. Focus on accessibility.") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 8
                )

                Spacer(modifier = Modifier.height(Spacing.xs))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        settingsViewModel.clearCustomPrompt()
                        android.widget.Toast.makeText(
                            context, "✓ Custom instructions cleared", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }) {
                        Text("Clear")
                    }
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Button(onClick = {
                        settingsViewModel.saveCustomPrompt()
                        android.widget.Toast.makeText(
                            context, "✓ Custom instructions saved", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }) {
                        Text("Save")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.md))

        // ── Agent Max Iterations Card ─────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(Spacing.md)) {
                Text("Agent Max Iterations", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    "Max tool-call rounds before the agent stops. Higher values allow complex tasks but use more tokens.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(Spacing.sm))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Slider(
                        value = maxIter.toFloat(),
                        onValueChange = { settingsViewModel.updateMaxIterations(it.toInt()) },
                        valueRange = com.devcompanion.llm.LlmSettings.MIN_MAX_ITERATIONS.toFloat()..com.devcompanion.llm.LlmSettings.MAX_MAX_ITERATIONS.toFloat(),
                        steps = com.devcompanion.llm.LlmSettings.MAX_MAX_ITERATIONS - com.devcompanion.llm.LlmSettings.MIN_MAX_ITERATIONS - 1,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(
                        "$maxIter",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.md))
    }
}

// ═══════════════════════════════════════════════════════════════
// Tab 3: Integrations (GitHub + Session Log)
// ═══════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntegrationsTab() {
    val scope = rememberCoroutineScope()
    val logContext = LocalContext.current

    var showClearLogDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        // ── GitHub PAT ────────────────────────────────────────────
        GitHubPatSection()

        Spacer(modifier = Modifier.height(Spacing.md))

        // ── Session Log ───────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(Spacing.md)) {
                Text("Session Log", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    "${SessionLog.bufferSize()} events in buffer, ${SessionLog.diskLogFileCount(logContext)} log files on disk.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                Button(
                    onClick = {
                        scope.launch {
                            SessionLog.flush(logContext)
                            val logText = SessionLog.exportFullHistory(logContext)
                            if (logText.isBlank()) {
                                android.widget.Toast.makeText(logContext, "No logs to export", android.widget.Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                                .format(java.util.Date())
                            val file = java.io.File(logContext.cacheDir, "devcompanion-log-$dateStr.jsonl")
                            file.writeText(logText)
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                logContext,
                                "${logContext.packageName}.fileprovider",
                                file
                            )
                            val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "application/jsonl"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            logContext.startActivity(
                                android.content.Intent.createChooser(sendIntent, "Export Session Log")
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Text("Export Log")
                }
                Spacer(modifier = Modifier.height(Spacing.xs))
                TextButton(
                    onClick = { showClearLogDialog = true }
                ) {
                    Text("Clear all logs", color = MaterialTheme.colorScheme.error)
                }
                if (showClearLogDialog) {
                    AlertDialog(
                        onDismissRequest = { showClearLogDialog = false },
                        title = { Text("Clear all logs?") },
                        text = { Text("This permanently deletes all session log history. This cannot be undone.") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    val count = SessionLog.clearLogs(logContext)
                                    SessionLog.startSession()
                                    showClearLogDialog = false
                                    android.widget.Toast.makeText(
                                        logContext, "Cleared $count log files", android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            ) { Text("Clear", color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearLogDialog = false }) { Text("Cancel") }
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.md))
    }
}