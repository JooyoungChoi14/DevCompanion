package com.devcompanion.ui

import android.util.Log
import com.devcompanion.logging.SessionLog
import com.devcompanion.engine.BrowserEngine
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
// Removed: detectTapGestures, pointerInput — select mode is now explicit toggle, not gesture
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.devcompanion.llm.ChatMessage
import com.devcompanion.llm.ConversationMeta
import com.devcompanion.llm.LlmRepositoryImpl
// Removed: EventType, SessionLog imports — no longer used after gesture code removal
import com.devcompanion.llm.LlmSettings
import com.devcompanion.llm.WebContextBuilder
import com.devcompanion.llm.agent.ActionRisk
import com.devcompanion.llm.agent.AgentState
import com.devcompanion.llm.agent.ToolCall
import com.devcompanion.llm.agent.ToolConfirmationDetails
import com.devcompanion.ui.theme.Spacing
import kotlinx.coroutines.launch
import java.io.File
import android.content.Intent



/**
 * Full-screen AI chat UI.
 *
 * Displays a conversation with an LLM provider, supports streaming
 * responses, web context capture, and provider configuration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatScreen(
    viewModel: AiChatViewModel = viewModel(),
    engine: BrowserEngine?,
    initialPrompt: String? = null,
    startNewConversation: Boolean = false,
    resumeConversationId: String? = null,
    sourceUrl: String? = null,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val messages by viewModel.messages.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val currentResponse by viewModel.currentResponse.collectAsState()
    val provider by viewModel.provider.collectAsState()
    val lastContext by viewModel.lastContext.collectAsState()
    val autoCapture by viewModel.autoCaptureEnabled.collectAsState()
    val error by viewModel.error.collectAsState()
    val totalInputTokens by viewModel.totalInputTokens.collectAsState()
    val totalOutputTokens by viewModel.totalOutputTokens.collectAsState()
    val agentMode by viewModel.agentMode.collectAsState()
    val agentState by viewModel.agentState.collectAsState()
    val pendingConfirmation by viewModel.pendingConfirmation.collectAsState()
    val conversationId by viewModel.conversationId.collectAsState()

    // Message selection mode — independent from selection state (Gmail pattern)
    var isInSelectMode by remember { mutableStateOf(false) }
    var selectedMessageIds by remember { mutableStateOf(emptySet<String>()) }
    // Freeze selectable IDs at mode entry — avoids "Select all" breaking during streaming
    var frozenSelectableIds by remember { mutableStateOf<Set<String>?>(null) }

    // System back exits select mode
    BackHandler(enabled = isInSelectMode) {
        isInSelectMode = false
        selectedMessageIds = emptySet()
        frozenSelectableIds = null
    }

    // Reset select mode when conversation changes
    LaunchedEffect(conversationId) {
        isInSelectMode = false
        selectedMessageIds = emptySet()
        frozenSelectableIds = null
    }

    var inputText by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var settingsInitialTab by remember { mutableIntStateOf(SETTINGS_TAB_APPEARANCE) }
    var showCaptureDialog by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    // Auto-focus input field when chat screen enters
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(300) // small delay for composition to settle
        inputFocusRequester.requestFocus()
    }

    // Resume existing conversation if requested
    LaunchedEffect(resumeConversationId) {
        if (resumeConversationId != null) {
            viewModel.loadConversation(resumeConversationId)
        }
    }

    // Set sourceUrl for new conversations
    LaunchedEffect(sourceUrl) {
        if (sourceUrl != null && resumeConversationId == null) {
            viewModel.setSourceUrl(sourceUrl)
        }
    }

    // Initialize new conversation with prompt via single ViewModel entry point (M2).
    // Replaces dual LaunchedEffect pattern to guarantee ordering:
    // newConversation → resetInitialPromptSent → send prompt.
    LaunchedEffect(startNewConversation, initialPrompt) {
        if (startNewConversation && initialPrompt != null && !viewModel.initialPromptSent) {
            viewModel.initializeWithPrompt(initialPrompt, engine, agentMode)
        } else if (startNewConversation && initialPrompt == null) {
            viewModel.newConversation(sourceUrl = sourceUrl)
        }
    }

    // Track CSS styles injected from chat responses (styleId -> css)
    val injectedStyles = remember { mutableStateMapOf<String, String>() }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Log UI data snapshot on chat entry and conversation change
    LaunchedEffect(conversationId) {
        val prov = provider
        SessionLog.uiDataSnapshot(
            messages = messages.size,
            conversations = viewModel.listConversationMetas().size,
            isStreaming = isStreaming,
            providerType = prov?.displayName ?: "none",
            model = prov?.currentModel ?: "none"
        )
    }

    // Scroll to bottom on conversation change (initial load, resume, switch)
    // Uses instant scroll to avoid distracting animation on entry.
    LaunchedEffect(conversationId) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem((messages.size - 1).coerceAtLeast(0))
        }
    }

    // Auto-scroll to bottom when new messages arrive or streaming updates
    LaunchedEffect(messages.size, currentResponse) {
        if (messages.isNotEmpty() || currentResponse.isNotBlank()) {
            listState.animateScrollToItem(
                (messages.size + if (currentResponse.isNotBlank()) 1 else 0).coerceAtLeast(0)
            )
        }
    }

    // Show error snackbar
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Drawer state for conversation history
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var conversations by remember { mutableStateOf(emptyList<ConversationMeta>()) }
    val exportContext = LocalContext.current

    // Refresh conversation list when drawer opens
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            conversations = viewModel.listConversationMetas()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        modifier = Modifier.fillMaxSize(),
        drawerContent = {
            ConversationDrawer(
                conversations = conversations,
                currentConversationId = conversationId,
                onSelect = { id ->
                    viewModel.loadConversation(id)
                    scope.launch { drawerState.close() }
                },
                onNew = {
                    viewModel.newConversation()
                    scope.launch { drawerState.close() }
                },
                onDelete = { id ->
                    viewModel.deleteConversation(id)
                    conversations = viewModel.listConversationMetas()
                },
                onDeleteMultiple = { ids ->
                    viewModel.deleteMultipleConversations(ids)
                    conversations = viewModel.listConversationMetas()
                },
                onExport = {
                    val json = viewModel.exportCurrentConversation()
                    if (json != null) {
                        val exportCtx = exportContext
                        val file = File(exportCtx.cacheDir, "conversation_${System.currentTimeMillis()}.json")
                        file.writeText(json)
                        file.deleteOnExit()
                        val uri = FileProvider.getUriForFile(
                            exportCtx,
                            "${exportCtx.packageName}.fileprovider",
                            file
                        )
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        exportCtx.startActivity(Intent.createChooser(shareIntent, "Export conversation"))
                    }
                },
                onExportMultiple = { ids ->
                    val json = viewModel.exportMultipleConversations(ids)
                    val exportCtx = exportContext
                    val file = File(exportCtx.cacheDir, "conversations_${System.currentTimeMillis()}.json")
                    file.writeText(json)
                    file.deleteOnExit()
                    val uri = FileProvider.getUriForFile(
                        exportCtx,
                        "${exportCtx.packageName}.fileprovider",
                        file
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    exportCtx.startActivity(Intent.createChooser(shareIntent, "Export ${ids.size} conversations"))
                },
                modifier = Modifier
                    .fillMaxHeight()
                    .width(280.dp)
            )
        }
    ) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    // Drawer toggle — primary navigation, always visible and tappable
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Default.Menu, contentDescription = "Conversations")
                    }
                },
                actions = {
                    // View/Act mode switch
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.height(28.dp)
                    ) {
                        SegmentedButton(
                            selected = !agentMode,
                            onClick = { if (agentMode) viewModel.toggleAgentMode(); SessionLog.uiClick("mode_switch", "view") },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) {
                            Text("View", style = MaterialTheme.typography.labelSmall)
                        }
                        SegmentedButton(
                            selected = agentMode,
                            onClick = { if (!agentMode) viewModel.toggleAgentMode(); SessionLog.uiClick("mode_switch", "agent") },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) {
                            Text("Act", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    // Select mode toggle — explicit entry, consistent with ConversationDrawer
                    IconButton(
                        onClick = {
                            if (isInSelectMode) {
                                isInSelectMode = false
                                selectedMessageIds = emptySet()
                                frozenSelectableIds = null
                                SessionLog.uiClick("select_mode", "exit")
                            } else {
                                isInSelectMode = true
                                frozenSelectableIds = messages.map { it.id }.toSet()
                                SessionLog.uiClick("select_mode", "enter")
                            }
                        }
                    ) {
                        Icon(
                            if (isInSelectMode) Icons.Default.Close else Icons.Default.SelectAll,
                            contentDescription = if (isInSelectMode) "Exit select mode" else "Select messages"
                        )
                    }
                    // Settings
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    // Close chat
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Status bar: provider + connection + token usage + context
                Surface(
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.sm, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (provider != null) {
                            Row(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable {
                                        settingsInitialTab = SETTINGS_TAB_AI
                                        showSettings = true
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(5.dp)
                                        .background(
                                            color = if (provider!!.hasApiKey) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.error,
                                            shape = CircleShape
                                        )
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    provider!!.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1
                                )
                            }
                        } else {
                            Text(
                                "No provider ⚠️",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable {
                                        settingsInitialTab = SETTINGS_TAB_AI
                                        showSettings = true
                                    }
                            )
                        }
                    }
                        if (totalInputTokens > 0 || totalOutputTokens > 0) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "IN ${formatTokenCount(totalInputTokens)} · OUT ${formatTokenCount(totalOutputTokens)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                maxLines = 1
                            )
                        }
                        if (lastContext != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                Icons.Default.AttachFile,
                                contentDescription = "Context",
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                // Input bar
                Surface(
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f).focusRequester(inputFocusRequester),
                        placeholder = { Text(
                            if (agentMode) "Agent command… (/do prefix optional)"
                            else "Message…",
                            style = MaterialTheme.typography.bodyMedium
                        ) },
                        textStyle = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                        enabled = !isStreaming && provider != null && !isInSelectMode,
                        colors = if (agentMode) OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.08f),
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        ) else OutlinedTextFieldDefaults.colors()
                    )
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    if (isStreaming) {
                        // Cancel button during streaming
                        IconButton(
                            onClick = {
                                if (agentMode) viewModel.stopAgentLoop()
                                else viewModel.cancelStreaming()
                            }
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "Cancel",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        // Send button — agent mode or normal
                        IconButton(
                            onClick = {
                                if (inputText.isNotBlank()) {
                                    SessionLog.uiClick("send_msg", if (agentMode) "agent" else "chat")
                                    if (agentMode) {
                                        viewModel.sendMessageAgent(inputText.trim(), engine)
                                    } else {
                                        viewModel.sendMessage(inputText.trim(), engine)
                                    }
                                    inputText = ""
                                }
                            },
                            enabled = inputText.isNotBlank() && provider != null
                        ) {
                            Icon(
                                if (agentMode) Icons.Default.SmartToy else Icons.AutoMirrored.Filled.Send,
                                contentDescription = if (agentMode) "Send agent command" else "Send",
                                tint = if (inputText.isNotBlank() && provider != null)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Message selection action bar — independent mode lifecycle
            AnimatedVisibility(visible = isInSelectMode) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Use frozen selectable IDs (set at mode entry) so streaming doesn't break "Select all"
                        val selectableIds = frozenSelectableIds ?: emptySet()
                        Text(
                            if (selectedMessageIds.isEmpty()) "Select messages to export"
                            else "${selectedMessageIds.size} selected",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        // Select all / Deselect all
                        TextButton(onClick = {
                            selectedMessageIds = if (selectedMessageIds == selectableIds) emptySet() else selectableIds
                        }) {
                            Text(
                                if (selectedMessageIds == selectableIds) "Deselect all" else "Select all",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        // Export (disabled when nothing selected)
                        TextButton(
                            onClick = {
                                val json = viewModel.exportSelectedMessages(selectedMessageIds)
                                if (json != null) {
                                    val file = File(exportContext.cacheDir, "messages_${System.currentTimeMillis()}.json")
                                    file.writeText(json)
                                    file.deleteOnExit()
                                    val uri = FileProvider.getUriForFile(
                                        exportContext,
                                        "${exportContext.packageName}.fileprovider",
                                        file
                                    )
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/json"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    exportContext.startActivity(Intent.createChooser(shareIntent, "Export selected messages"))
                                }
                                isInSelectMode = false
                                selectedMessageIds = emptySet()
                                frozenSelectableIds = null
                            },
                            enabled = selectedMessageIds.isNotEmpty()
                        ) {
                            Icon(Icons.Default.IosShare, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(Spacing.xs))
                            Text("Export", style = MaterialTheme.typography.labelMedium)
                        }
                        // Exit select mode (redundant with TopAppBar ✕, kept for discoverability)
                        IconButton(onClick = {
                            isInSelectMode = false
                            selectedMessageIds = emptySet()
                            frozenSelectableIds = null
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Exit select mode", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            // Context badge
            AnimatedVisibility(visible = lastContext != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("\uD83D\uDCC1", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        Text(
                            "Page context captured",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.clearContext() }) {
                            Text("Clear", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            // Auto-capture indicator — also serves as manual capture trigger (camera button removed from TopAppBar)
            AnimatedVisibility(visible = lastContext == null && autoCapture && engine != null) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
                            .clickable { viewModel.setAutoCaptureEnabled(false) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("\u2699\uFE0F", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        Text(
                            "Auto-capture ON",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            if (engine != null) showCaptureDialog = true
                        }) {
                            Text("Capture", style = MaterialTheme.typography.labelSmall)
                        }
                        Text(
                            "\u00B7",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.5f)
                        )
                        Text(
                            "Disable",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                            modifier = Modifier.clickable { viewModel.setAutoCaptureEnabled(false) }
                        )
                    }
                }
            }

            // Manual capture trigger — shown when auto-capture is off and no context captured
            // (camera button removed from TopAppBar; this is the alternative entry point)
            AnimatedVisibility(visible = lastContext == null && !autoCapture && engine != null && !isInSelectMode) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
                            .clickable { if (engine != null) showCaptureDialog = true },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("\uD83D\uDCF7", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        Text(
                            "Capture page context",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Agent mode state indicator
            AnimatedVisibility(visible = agentMode) {
                Surface(
                    color = when (agentState) {
                        is AgentState.Idle -> MaterialTheme.colorScheme.tertiaryContainer
                        is AgentState.Thinking -> MaterialTheme.colorScheme.primaryContainer
                        is AgentState.CheckingPermission -> MaterialTheme.colorScheme.secondaryContainer
                        is AgentState.WaitingConfirmation -> MaterialTheme.colorScheme.errorContainer
                        is AgentState.ExecutingTool -> MaterialTheme.colorScheme.primaryContainer
                        is AgentState.Error -> MaterialTheme.colorScheme.errorContainer
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            when (agentState) {
                                is AgentState.Idle -> "🤖 Agent ready"
                                is AgentState.Thinking -> "🤔 Thinking… (iteration ${(agentState as AgentState.Thinking).iteration})"
                                is AgentState.CheckingPermission -> "🔒 Checking permission…"
                                is AgentState.WaitingConfirmation -> "⚠️ Awaiting confirmation"
                                is AgentState.ExecutingTool -> "🔧 Executing ${(agentState as AgentState.ExecutingTool).tool}"
                                is AgentState.Error -> "❌ ${(agentState as AgentState.Error).message}"
                            },
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.stopAgentLoop() }) {
                            Text("Stop", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // Confirmation card for sensitive actions
            pendingConfirmation?.let { (_, details) ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(Spacing.md)) {
                        Text(
                            "⚠️ ${details.action}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        Text(
                            "Target: ${details.target}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        if (details.preview.isNotBlank()) {
                            Text(
                                details.preview,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                                maxLines = 3
                            )
                        }
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            Button(
                                onClick = { viewModel.respondConfirmation(true) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("Allow Once")
                            }
                            OutlinedButton(onClick = { viewModel.respondConfirmation(false) }) {
                                Text("Reject")
                            }
                        }
                    }
                }
            }

            // Messages list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                contentPadding = PaddingValues(vertical = Spacing.sm)
            ) {
                // No provider configured
                if (provider == null) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(Spacing.lg),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.SmartToy,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.outline
                                )
                                Spacer(modifier = Modifier.height(Spacing.sm))
                                Text(
                                    "No LLM provider configured",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Spacer(modifier = Modifier.height(Spacing.xs))
                                Text(
                                    "Tap ⚙ to configure a provider",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                Spacer(modifier = Modifier.height(Spacing.md))
                                Button(onClick = { showSettings = true }) {
                                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(Spacing.xs))
                                    Text("Configure")
                                }
                            }
                        }
                    }
                }

                // Message items — key stabilizes LazyColumn recycling (prevents checkbox flicker)
                items(messages, key = { message -> message.id }) { message ->
                    MessageBubble(
                            message = message,
                            engine = engine,
                            injectedStyles = injectedStyles,
                            isSelected = message.id in selectedMessageIds,
                            isSelectMode = isInSelectMode,
                            onToggleSelect = { id ->
                                selectedMessageIds = if (id in selectedMessageIds)
                                    selectedMessageIds - id
                                else
                                    selectedMessageIds + id
                            }
                        )
                }

                // Streaming response in progress
                if (isStreaming && currentResponse.isNotBlank()) {
                    item {
                        MessageBubble(
                            message = ChatMessage(
                                role = "assistant",
                                content = currentResponse
                            ),
                            isStreaming = true,
                            engine = engine,
                            injectedStyles = injectedStyles,
                            // Streaming messages cannot be selected
                            isSelected = false,
                            isSelectMode = false
                        )
                    }
                }

                // Streaming indicator (no tokens yet)
                if (isStreaming && currentResponse.isBlank()) {
                    item {
                        StreamingIndicator()
                    }
                }
            }
        }
    }

    // ── Capture confirmation dialog ───────────────────────────────────

    if (showCaptureDialog) {
        AlertDialog(
            onDismissRequest = { showCaptureDialog = false },
            title = { Text("Capture Page Context") },
            text = {
                Text("Capture a screenshot of the current page to include as context for the AI? The page URL and title will also be shared.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCaptureDialog = false
                        if (engine != null) {
                            scope.launch {
                                viewModel.captureContext(engine!!)
                            }
                        }
                    }
                ) {
                    Text("Capture")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCaptureDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // ── Settings sheet (unified: Appearance / AI / Integrations) ──
    if (showSettings) {
        SettingsSheet(
            onDismiss = { showSettings = false },
            initialTab = settingsInitialTab,
            viewModel = viewModel
        )
    }
    } // ModalNavigationDrawer
}

/**
 * Single message bubble in the chat list.
 *
 * CSS code blocks in assistant messages include Inject/Revert buttons
 * that inject styles into the browser engine for live preview.
 */
@Composable
private fun MessageBubble(
    message: ChatMessage,
    isStreaming: Boolean = false,
    engine: BrowserEngine?,
    injectedStyles: androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>,
    isSelected: Boolean = false,
    isSelectMode: Boolean = false,
    onToggleSelect: (String) -> Unit = {}
) {
    val isUser = message.role == "user"
    val isSystem = message.role == "system"
    val isToolResult = message.isToolResult
    val isError = message.isError

    // System messages: tool results vs regular system messages
    if (isSystem) {
        if (isToolResult) {
            // Tool result: collapsible with color-coded header
            var expanded by remember { mutableStateOf(false) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isError)
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    else
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                )
            ) {
                Column {
                    // Header row: icon + tool name + expand toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = !expanded }
                            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            message.content.takeWhile { it != ':' }.let { if (it.length < message.content.length) it + ":" else it },
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (isError)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(
                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "Collapse" else "Expand",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
                    // Expandable detail
                    AnimatedVisibility(visible = expanded) {
                        SelectionContainer {
                            Text(
                                message.content.substringAfter(':').trim(),
                                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        } else {
            // Regular system message: compact
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
                )
            ) {
                Text(
                    message.content,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelectMode && isSelected) Modifier.background(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    MaterialTheme.shapes.medium
                ) else Modifier
            )
            .then(
                // Select mode: tap to toggle selection
                // Normal mode: no touch handler — scroll works naturally
                if (isSelectMode) {
                    Modifier.clickable {
                        if (!isStreaming) {
                            onToggleSelect(message.id)
                        }
                    }
                } else Modifier
            ),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        // Selection indicator — display-only; toggling handled by clickable modifier on Row
        if (isSelectMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = null,
                modifier = Modifier.size(32.dp)
            )
        }
        Card(
            modifier = Modifier.widthIn(max = 320.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser)
                    MaterialTheme.colorScheme.primaryContainer
                else if (isSelected)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(Spacing.sm)) {
                // Role label
                Text(
                    if (isUser) "You" else "AI",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(Spacing.xxs))

                // Content
                if (isUser) {
                    Text(
                        message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else {
                    // Build CSS inject/revert callbacks scoped to this message
                    val messageId = message.id
                    val onCssInject: (String, String) -> Unit = { styleId, css ->
                        val globalStyleId = "${messageId}-$styleId"
                        if (engine != null) {
                            val success = WebContextBuilder.injectCss(engine!!, css, globalStyleId)
                            if (success) {
                                injectedStyles[globalStyleId] = css
                            }
                        } else {
                            Log.w("AiChatScreen", "No browser engine available for CSS inject")
                        }
                    }
                    val onCssRevert: (String) -> Unit = { styleId ->
                        val globalStyleId = "${messageId}-$styleId"
                        if (engine != null) {
                            WebContextBuilder.revertCss(engine!!, globalStyleId)
                            injectedStyles.remove(globalStyleId)
                        }
                    }
                    val isCssInjected: (String) -> Boolean = { styleId ->
                        val globalStyleId = "${messageId}-$styleId"
                        injectedStyles.containsKey(globalStyleId)
                    }

                    MarkdownText(
                        text = message.content,
                        onCssInject = onCssInject,
                        onCssRevert = onCssRevert,
                        isCssInjected = isCssInjected
                    )
                }

                // Context badge
                if (message.hasContext) {
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        "📎 Page context attached",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                // Streaming indicator
                if (isStreaming) {
                    Spacer(modifier = Modifier.height(Spacing.xxs))
                    Text(
                        "●●●",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Token usage (assistant messages only)
                if (!isUser) {
                    message.tokenUsage?.let { usage ->
                        Spacer(modifier = Modifier.height(Spacing.xxs))
                        Text(
                            "tokens: ${usage.inputTokens} in / ${usage.outputTokens} out",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Animated streaming indicator shown while waiting for first token.
 */
@Composable
private fun StreamingIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier.padding(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Thinking…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Format token counts into human-readable strings.
 * < 1000: as-is (e.g. "420")
 * >= 1000: "1.2K" format (1 decimal)
 * >= 1,000,000: "1.2M" format (1 decimal)
 */
private fun formatTokenCount(count: Int): String = when {
    count >= 1_000_000 -> "${(count / 100_000) / 10.0}M"
    count >= 1_000 -> "${(count / 100) / 10.0}K"
    else -> count.toString()
}

/**
 * Drawer showing conversation history list with multi-select for batch delete/export.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationDrawer(
    conversations: List<ConversationMeta>,
    currentConversationId: String,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onDelete: (String) -> Unit,
    onDeleteMultiple: (List<String>) -> Unit,
    onExport: () -> Unit,
    onExportMultiple: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormat = remember { java.text.SimpleDateFormat("MMM dd", java.util.Locale.getDefault()) }
    var conversationToDelete by remember { mutableStateOf<String?>(null) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var isSelectMode by remember { mutableStateOf(false) }

    ModalDrawerSheet(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header with select mode toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSelectMode) {
                    // Select mode header
                    TextButton(onClick = {
                        selectedIds = if (selectedIds.size == conversations.size) emptySet() else conversations.map { it.id }.toSet()
                    }) {
                        Text(
                            if (selectedIds.size == conversations.size) "Deselect all" else "Select all",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        "${selectedIds.size}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    IconButton(onClick = {
                        isSelectMode = false
                        selectedIds = emptySet()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Exit select mode")
                    }
                } else {
                    Text(
                        "Conversations",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        if (conversations.isNotEmpty()) {
                            isSelectMode = true
                        }
                    }) {
                        Icon(Icons.Default.SelectAll, contentDescription = "Select")
                    }
                    IconButton(onClick = onNew) {
                        Icon(Icons.Default.Add, contentDescription = "New chat")
                    }
                }
            }
            HorizontalDivider()

            // Conversation list
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                items(conversations) { conv ->
                    val isSelected = conv.id == currentConversationId
                    val isChecked = conv.id in selectedIds

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.sm, vertical = Spacing.xxs)
                            .then(
                                if (isSelectMode) Modifier.clickable {
                                    selectedIds = if (conv.id in selectedIds)
                                        selectedIds - conv.id
                                    else
                                        selectedIds + conv.id
                                }
                                else Modifier.clickable { onSelect(conv.id) }
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                isChecked -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                isSelected -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Checkbox in select mode
                            if (isSelectMode) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = {
                                        selectedIds = if (it) selectedIds + conv.id else selectedIds - conv.id
                                    },
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.width(Spacing.xs))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    conv.title ?: "Untitled",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 2.dp)
                                ) {
                                    Text(
                                        dateFormat.format(conv.updatedAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    Spacer(modifier = Modifier.width(Spacing.xs))
                                    Text(
                                        "${conv.messageCount} msgs",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                            // Delete button (only in non-select mode)
                            if (!isSelectMode) {
                                IconButton(
                                    onClick = { conversationToDelete = conv.id },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = "Delete",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }
                }

                if (conversations.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.lg),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "No conversations yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // Footer: contextual actions
            if (isSelectMode && selectedIds.isNotEmpty()) {
                // Batch action bar in select mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    OutlinedButton(
                        onClick = {
                            onExportMultiple(selectedIds.toList())
                            selectedIds = emptySet()
                            isSelectMode = false
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.IosShare, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        Text("Export ${selectedIds.size}", style = MaterialTheme.typography.labelMedium)
                    }
                    Button(
                        onClick = {
                            // Confirm before batch delete
                            conversationToDelete = "__batch__${selectedIds.size}"
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        Text("Delete ${selectedIds.size}", style = MaterialTheme.typography.labelMedium)
                    }
                }
            } else {
                // Normal mode footer
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.sm),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    OutlinedButton(
                        onClick = onExport,
                        modifier = Modifier.weight(1f).padding(end = Spacing.xs)
                    ) {
                        Icon(Icons.Default.IosShare, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        Text("Export", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    conversationToDelete?.let { id ->
        if (id.startsWith("__batch__")) {
            // Batch delete confirmation
            val count = id.removePrefix("__batch__").toIntOrNull() ?: return@let
            AlertDialog(
                onDismissRequest = { conversationToDelete = null },
                title = { Text("Delete $count conversations?") },
                text = { Text("This cannot be undone. All selected conversations will be permanently deleted.") },
                confirmButton = {
                    TextButton(onClick = {
                        onDeleteMultiple(selectedIds.toList())
                        selectedIds = emptySet()
                        isSelectMode = false
                        conversationToDelete = null
                    }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { conversationToDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        } else {
            // Single delete confirmation
            AlertDialog(
                onDismissRequest = { conversationToDelete = null },
                title = { Text("Delete conversation?") },
                text = { Text("This cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        onDelete(id)
                        conversationToDelete = null
                    }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { conversationToDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}