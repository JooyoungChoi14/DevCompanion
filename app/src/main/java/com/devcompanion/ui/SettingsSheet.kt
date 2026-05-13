package com.devcompanion.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.devcompanion.cdp.CdpClient
import com.devcompanion.github.ui.GitHubPatSection
import com.devcompanion.llm.LlmSettings
import com.devcompanion.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(
    cdpClient: CdpClient,
    onDismiss: () -> Unit,
) {
    val connected by cdpClient.connected.collectAsState()
    val scope = rememberCoroutineScope()
    val themePrefs = LocalThemePreferences.current

    var hostInput by remember { mutableStateOf(cdpClient.discoveryHost) }
    var portInput by remember { mutableStateOf(cdpClient.discoveryPort) }
    var connecting by remember { mutableStateOf(false) }

    val currentPreset by themePrefs.preset.collectAsState(initial = ColorPreset.DRACULA)
    val currentDarkMode by themePrefs.darkMode.collectAsState(initial = "system")

    // M3: Auto-override dark mode when Solarized Light is selected
    val effectiveDarkMode = when {
        currentPreset == ColorPreset.SOLARIZED_LIGHT && currentDarkMode == "dark" -> "light"
        else -> currentDarkMode
    }
    // Sync if we overrode
    LaunchedEffect(effectiveDarkMode) {
        if (effectiveDarkMode != currentDarkMode) {
            themePrefs.setDarkMode(effectiveDarkMode)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.md)
    ) {
        Text("Settings", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)

        Spacer(modifier = Modifier.height(Spacing.md))

        // ── Theme section ──────────────────────────────────────────
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

                // Appearance toggle — above palette (W2: cognitive flow)
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

                // Color palette — FlowRow for proper wrapping (N1)
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

                // Solarized Light notice
                if (currentPreset == ColorPreset.SOLARIZED_LIGHT && currentDarkMode == "dark") {
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text("⚠ Solarized Light forces Light appearance",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.md))

        // ── Custom System Prompt section ──────────────────────────────
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

                var customPrompt by remember {
                    mutableStateOf(LlmSettings.loadCustomPrompt() ?: "")
                }

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
                    }) {
                        Text("Clear")
                    }
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Button(onClick = {
                        LlmSettings.saveCustomPrompt(customPrompt.trim())
                    }) {
                        Text("Save")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.md))

        // ── Agent Max Iterations ──────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(Spacing.md)) {
                var maxIter by remember {
                    mutableStateOf(LlmSettings.maxIterations)
                }

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

        // ── GitHub PAT section ────────────────────────────────────
        GitHubPatSection()

        Spacer(modifier = Modifier.height(Spacing.md))

        // ── CDP Connection section ──────────────────────────────────
        Text("CDP Connection", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            "Connect to Chrome DevTools Protocol on your development machine.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.height(Spacing.md))

        // Connection status
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (connected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (connected) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (connected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    if (connected) "Connected to ${cdpClient.discoveryHost}:${cdpClient.discoveryPort}"
                    else "Disconnected",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.md))

        // Host input
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

        // Port input
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

        Spacer(modifier = Modifier.height(Spacing.md))

        // Action buttons
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

        Spacer(modifier = Modifier.height(Spacing.md))
    }
}