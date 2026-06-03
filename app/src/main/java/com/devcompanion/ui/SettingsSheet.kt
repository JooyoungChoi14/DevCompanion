package com.devcompanion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.ui.platform.LocalContext
import com.devcompanion.cdp.CdpClient
import com.devcompanion.ui.AiChatViewModel
import com.devcompanion.github.ui.GitHubPatSection
import com.devcompanion.llm.LlmProvider
import com.devcompanion.llm.LlmRepositoryImpl
import com.devcompanion.llm.LlmSettings
import com.devcompanion.logging.SessionLog
import com.devcompanion.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
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

private fun providerApiKey(provider: LlmProvider?): String = when (provider) {
    is LlmProvider.Anthropic -> provider.apiKey
    is LlmProvider.OpenAi -> provider.apiKey
    is LlmProvider.Ollama -> provider.apiKey
    is LlmProvider.Gemini -> provider.apiKey
    null -> ""
}

private fun providerBaseUrl(provider: LlmProvider?): String = when (provider) {
    is LlmProvider.Anthropic -> provider.baseUrl
    is LlmProvider.OpenAi -> provider.baseUrl
    is LlmProvider.Ollama -> provider.baseUrl
    is LlmProvider.Gemini -> provider.baseUrl
    null -> ""
}

private fun providerModel(provider: LlmProvider?): String = when (provider) {
    is LlmProvider.Anthropic -> provider.model
    is LlmProvider.OpenAi -> provider.model
    is LlmProvider.Ollama -> provider.model
    is LlmProvider.Gemini -> provider.model
    null -> ""
}

// ── Tab indices ──────────────────────────────────────────────
const val SETTINGS_TAB_APPEARANCE = 0
const val SETTINGS_TAB_AI = 1
const val SETTINGS_TAB_INTEGRATIONS = 2

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(
    cdpClient: CdpClient,
    onDismiss: () -> Unit,
    initialTab: Int = SETTINGS_TAB_APPEARANCE,
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
                onClick = { selectedTab = SETTINGS_TAB_APPEARANCE },
                text = { Text("Appearance") },
                icon = { Icon(Icons.Default.Palette, contentDescription = null, modifier = Modifier.size(18.dp)) },
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Tab(
                selected = selectedTab == SETTINGS_TAB_AI,
                onClick = { selectedTab = SETTINGS_TAB_AI },
                text = { Text("AI") },
                icon = { Icon(Icons.Default.SmartToy, contentDescription = null, modifier = Modifier.size(18.dp)) },
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Tab(
                selected = selectedTab == SETTINGS_TAB_INTEGRATIONS,
                onClick = { selectedTab = SETTINGS_TAB_INTEGRATIONS },
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
            SETTINGS_TAB_AI -> AiTab(viewModel = viewModel)
            SETTINGS_TAB_INTEGRATIONS -> IntegrationsTab(cdpClient = cdpClient)
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
        // ── Theme Card ────────────────────────────────────────────
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
// Tab 2: AI (LLM Provider + Instructions + Max Iterations)
// ═══════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiTab(
    viewModel: AiChatViewModel?,
) {
    val currentProvider by (viewModel?.provider ?: MutableStateFlow(null)).collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // ── Form state ────────────────────────────────────────────────
    var selectedType by remember {
        mutableStateOf(
            when (currentProvider) {
                is LlmProvider.Anthropic -> LlmProvider.Anthropic.TYPE
                is LlmProvider.OpenAi -> LlmProvider.OpenAi.TYPE
                is LlmProvider.Ollama -> LlmProvider.Ollama.TYPE
                is LlmProvider.Gemini -> LlmProvider.Gemini.TYPE
                null -> LlmProvider.Anthropic.TYPE
            }
        )
    }
    var apiKey by remember { mutableStateOf(providerApiKey(currentProvider)) }
    var baseUrl by remember { mutableStateOf(providerBaseUrl(currentProvider)) }
    var model by remember { mutableStateOf(providerModel(currentProvider)) }
    var showApiKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    val usingPlainStorage = LlmSettings.isUsingPlainStorage

    // ── Custom Instructions & Max Iterations ──────────────────────
    var customPrompt by remember { mutableStateOf(LlmSettings.loadCustomPrompt() ?: "") }
    var maxIter by remember { mutableStateOf(LlmSettings.maxIterations) }

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
                            selected = selectedType == type,
                            onClick = {
                                if (selectedType != type) {
                                    selectedType = type
                                    // Only clear apiKey when actually switching provider type
                                    // Same type = user re-entered settings, preserve existing key
                                    apiKey = ""
                                    baseUrl = when (type) {
                                        LlmProvider.Ollama.TYPE -> "https://ollama.com"
                                        LlmProvider.Gemini.TYPE -> "https://generativelanguage.googleapis.com/v1beta"
                                        else -> ""
                                    }
                                    model = when (type) {
                                        LlmProvider.Anthropic.TYPE -> "claude-sonnet-4-20250514"
                                        LlmProvider.OpenAi.TYPE -> "gpt-4o"
                                        LlmProvider.Ollama.TYPE -> "glm-5.1"
                                        LlmProvider.Gemini.TYPE -> "gemini-2.5-flash"
                                        else -> ""
                                    }
                                }
                            },
                            label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.md))

                // API Key
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Key") },
                    placeholder = { Text(
                        when (selectedType) {
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
                if (selectedType == LlmProvider.Ollama.TYPE || selectedType == LlmProvider.Gemini.TYPE) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Base URL") },
                        placeholder = { Text(
                            when (selectedType) {
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
                        value = model,
                        onValueChange = { model = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        label = { Text("Model") },
                        placeholder = { Text(
                            when (selectedType) {
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
                        RECOMMENDED_MODELS[selectedType]?.forEach { modelName ->
                            DropdownMenuItem(
                                text = { Text(modelName, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) },
                                onClick = {
                                    model = modelName
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
                            val newProvider = when (selectedType) {
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
                                    baseUrl = baseUrl.ifEmpty { "https://ollama.com" },
                                    model = model.ifEmpty { "glm-5.1" }
                                )
                                LlmProvider.Gemini.TYPE -> LlmProvider.Gemini(
                                    apiKey = apiKey,
                                    baseUrl = baseUrl.ifEmpty { "https://generativelanguage.googleapis.com/v1beta" },
                                    model = model.ifEmpty { "gemini-2.5-flash" }
                                )
                                else -> return@Button
                            }
                            val vm = viewModel
                            // Always save to persistent storage (ViewModel or not)
                            try {
                                LlmSettings.saveProvider(newProvider)
                                SessionLog.log(com.devcompanion.logging.EventType.SETTINGS_SAVE, mapOf(
                                    "provider" to newProvider.providerType,
                                    "result" to "ok",
                                    "apiKeyLen" to providerApiKey(newProvider).length.toString()
                                ))
                                android.widget.Toast.makeText(
                                    context,
                                    "✓ Saved",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            } catch (e: Exception) {
                                SessionLog.log(com.devcompanion.logging.EventType.SETTINGS_SAVE, mapOf(
                                    "provider" to newProvider.providerType,
                                    "result" to "error",
                                    "error" to (e.message?.take(80) ?: "unknown")
                                ))
                                android.widget.Toast.makeText(
                                    context,
                                    "✗ Save failed: ${e.message?.take(50)}",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                            // Also update runtime provider if ViewModel available
                            vm?.setProvider(newProvider, persist = false)
                            testResult = null
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        Text("Save")
                    }

                    OutlinedButton(
                        onClick = {
                            val testProvider = when (selectedType) {
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
                                    baseUrl = baseUrl.ifEmpty { "https://ollama.com" },
                                    model = model.ifEmpty { "glm-5.1" }
                                )
                                LlmProvider.Gemini.TYPE -> LlmProvider.Gemini(
                                    apiKey = apiKey,
                                    baseUrl = baseUrl.ifEmpty { "https://generativelanguage.googleapis.com/v1beta" },
                                    model = model.ifEmpty { "gemini-2.5-flash" }
                                )
                                else -> return@OutlinedButton
                            }
                            testing = true
                            testResult = null
                            scope.launch {
                                try {
                                    val repo = LlmRepositoryImpl(testProvider)
                                    val response = repo.complete("Hello")
                                    testResult = "✓ Connected: ${response.take(80)}"
                                } catch (e: Exception) {
                                    testResult = "✗ Error: ${e.message?.take(100) ?: "Unknown"}"
                                } finally {
                                    testing = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = apiKey.isNotBlank() && !testing
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
                    onValueChange = { customPrompt = it },
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
                        customPrompt = ""
                        LlmSettings.saveCustomPrompt("")
                        android.widget.Toast.makeText(
                            context, "✓ Custom instructions cleared", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }) {
                        Text("Clear")
                    }
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Button(onClick = {
                        LlmSettings.saveCustomPrompt(customPrompt.trim())
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
                        onValueChange = {
                            maxIter = it.toInt()
                            LlmSettings.maxIterations = maxIter
                        },
                        valueRange = LlmSettings.MIN_MAX_ITERATIONS.toFloat()..LlmSettings.MAX_MAX_ITERATIONS.toFloat(),
                        steps = LlmSettings.MAX_MAX_ITERATIONS - LlmSettings.MIN_MAX_ITERATIONS - 1,
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
// Tab 3: Integrations (CDP + GitHub + Session Log)
// ═══════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntegrationsTab(
    cdpClient: CdpClient,
) {
    val connected by cdpClient.connected.collectAsState()
    val scope = rememberCoroutineScope()
    val logContext = LocalContext.current

    var hostInput by remember { mutableStateOf(cdpClient.discoveryHost) }
    var portInput by remember { mutableStateOf(cdpClient.discoveryPort) }
    var connecting by remember { mutableStateOf(false) }
    var showClearLogDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        // ── CDP Connection ────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(Spacing.md)) {
                Text("CDP Connection", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    "Connect to Chrome DevTools Protocol on your development machine.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(Spacing.sm))

                // Connection status
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (connected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (connected) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (connected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        Text(
                            if (connected) "Connected to ${cdpClient.discoveryHost}:${cdpClient.discoveryPort}"
                            else "Disconnected",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.sm))

                OutlinedTextField(
                    value = hostInput,
                    onValueChange = { hostInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Host") },
                    placeholder = { Text("e.g. 192.168.1.100") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    enabled = !connected && !connecting
                )

                Spacer(modifier = Modifier.height(Spacing.sm))

                OutlinedTextField(
                    value = portInput,
                    onValueChange = { portInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Port") },
                    placeholder = { Text("9222") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (!connected && !connecting) {
                                connecting = true
                                cdpClient.discoveryHost = hostInput
                                cdpClient.discoveryPort = portInput
                                scope.launch {
                                    cdpClient.connect()
                                    connecting = false
                                }
                            }
                        }
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    enabled = !connected && !connecting
                )

                Spacer(modifier = Modifier.height(Spacing.sm))

                // Setup instructions
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(Spacing.md)) {
                        Text("Setup", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        Text(
                            "1. Launch Chrome on PC with:\n" +
                            "   chrome --remote-debugging-port=9222\n" +
                            "2. Find your PC's LAN IP\n" +
                            "   (ipconfig / ifconfig)\n" +
                            "3. Enter IP above and tap Connect",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.sm))

                // Connect/Disconnect button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    if (connected) {
                        OutlinedButton(
                            onClick = { cdpClient.disconnect() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.LinkOff, contentDescription = null)
                            Spacer(modifier = Modifier.width(Spacing.xs))
                            Text("Disconnect")
                        }
                    } else {
                        Button(
                            onClick = {
                                connecting = true
                                cdpClient.discoveryHost = hostInput
                                cdpClient.discoveryPort = portInput
                                scope.launch {
                                    cdpClient.connect()
                                    connecting = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !connecting
                        ) {
                            if (connecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(Icons.Default.Link, contentDescription = null)
                            }
                            Spacer(modifier = Modifier.width(Spacing.xs))
                            Text(if (connecting) "Connecting…" else "Connect")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.md))

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