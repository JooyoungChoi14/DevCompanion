package com.devcompanion.ui

import android.util.Log
import android.os.Bundle
import androidx.compose.ui.text.style.TextOverflow
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
// Removed: detectTapGestures — unused import
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
// Removed: pointerInput — unused import
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import com.devcompanion.llm.ChatHistory
import com.devcompanion.ui.AiChatViewModel
import com.devcompanion.DevCompanionApp
import com.devcompanion.debug.BrowserDebuggerHolder
import com.devcompanion.ui.theme.DevCompanionTheme
import com.devcompanion.ui.theme.ThemePreferences
import com.devcompanion.ui.theme.ColorPreset
import com.devcompanion.ui.theme.LocalThemePreferences
import com.devcompanion.ui.theme.Spacing
import com.devcompanion.engine.BrowserEngine
import com.devcompanion.engine.InjectionConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState

import com.devcompanion.logging.SessionLog
import com.devcompanion.logging.EventType

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private var engineCanGoBack: (() -> Boolean)? = null

    override fun onPause() {
        super.onPause()
        lifecycleScope.launch { SessionLog.flush(this@MainActivity) }
    }

    override fun onResume() {
        super.onResume()
        SessionLog.startAutoFlush(lifecycleScope)
    }

    override fun onStop() {
        super.onStop()
        SessionLog.log(EventType.SESSION_END, mapOf("reason" to "onStop"))
        SessionLog.stopAutoFlush()
        lifecycleScope.launch { SessionLog.flush(this@MainActivity) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate: starting")
        SessionLog.init(this)
        SessionLog.startSession()

        try {
            try {
                enableEdgeToEdge()
                Log.i(TAG, "onCreate: enableEdgeToEdge ok")
            } catch (e: Exception) {
                Log.w(TAG, "onCreate: enableEdgeToEdge failed, continuing without it", e)
            }

            // WebView debugging — only relevant for free flavor (WebView engine)
            if (InjectionConfig.needsInjections) {
                try { android.webkit.WebView.setWebContentsDebuggingEnabled(true) } catch (_: Exception) {}
                Log.i(TAG, "onCreate: WebView debugging enabled (free flavor)")
            }

            // Handle back button: browser engine history first, then finish
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val canGoBack = engineCanGoBack?.invoke() ?: false
                    if (!canGoBack) {
                        finish()
                    }
                }
            })

            val app = applicationContext as DevCompanionApp
            val bridgeAuthToken = app.bridgeAuthToken
            val bridgePort = app.bridgeServer.port
            val tunnelUrlFlow = app.tunnelUrl
            val tunnelErrorFlow = app.tunnelError
            val themePrefs = ThemePreferences(this)

            setContent {
                val tunnelUrl by tunnelUrlFlow.collectAsState()
                val tunnelError by tunnelErrorFlow.collectAsState()
                val currentPreset by themePrefs.preset.collectAsState(initial = ColorPreset.DRACULA)
                val currentDarkMode by themePrefs.darkMode.collectAsState(initial = "system")

                CompositionLocalProvider(LocalThemePreferences provides themePrefs) {
                    DevCompanionTheme(preset = currentPreset, darkModeOverride = currentDarkMode) {
                    MainApp(
                        onEngineReady = { canGoBackCallback ->
                            engineCanGoBack = canGoBackCallback
                        },
                        bridgeAuthToken = bridgeAuthToken,
                        bridgePort = bridgePort,
                        tunnelUrl = tunnelUrl,
                        tunnelError = tunnelError,
                        app = app,
                    )
                    }
                }
            }
            Log.i(TAG, "onCreate: content set")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate FAILED", e)
            throw e
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(
    onEngineReady: ((() -> Boolean) -> Unit)? = null,
    bridgeAuthToken: String = "",
    bridgePort: Int = 8765,
    tunnelUrl: String? = null,
    tunnelError: String? = null,
    app: DevCompanionApp? = null,
) {
    var showDevTools by remember { mutableStateOf(false) }
    var devToolsTab by remember { mutableStateOf(0) }
    var headerVisible by remember { mutableStateOf(true) }
    var showBridgeInfo by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showAiChat by remember { mutableStateOf(false) }
    var pendingAiQuestion by remember { mutableStateOf<String?>(null) }
    var engineRef by remember { mutableStateOf<BrowserEngine?>(null) }
    var showSessionChoice by remember { mutableStateOf(false) }
    var matchedConversationId by remember { mutableStateOf<String?>(null) }
    var forceNewSession by remember { mutableStateOf(false) }
    val chatViewModel: AiChatViewModel = viewModel()
    var currentUrlForChat by remember { mutableStateOf<String?>(null) }

    // ── UI navigation tracking ──
    LaunchedEffect(showAiChat) { SessionLog.uiNav("ai_chat", if (showAiChat) "open" else "close") }
    LaunchedEffect(showDevTools) { SessionLog.uiNav("devtools", if (showDevTools) "open" else "close") }
    LaunchedEffect(showSettings) { SessionLog.uiNav("settings", if (showSettings) "open" else "close") }
    LaunchedEffect(showBridgeInfo) { SessionLog.uiNav("bridge_info", if (showBridgeInfo) "open" else "close") }
    LaunchedEffect(showSessionChoice) { SessionLog.uiNav("session_choice", if (showSessionChoice) "open" else "close") }
    LaunchedEffect(devToolsTab) { SessionLog.uiTab("devtools", when(devToolsTab) { 0 -> "console"; 1 -> "network"; 2 -> "perf"; else -> "unknown" }) }
    val debugger = BrowserDebuggerHolder.current
    val isActive = debugger != null

    Scaffold(
        topBar = {
            AnimatedVisibility(
                visible = headerVisible && !showAiChat,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                TopAppBar(
                    title = { Text("DevCompanion", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    actions = {
                        Text(
                            if (isActive) "● Live" else "○ Idle",
                            color = if (isActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(end = Spacing.xs)
                        )
                        IconButton(onClick = {
                            val url = engineRef?.getUrl()
                            currentUrlForChat = url
                            SessionLog.uiClick("ai_chat_btn", if (url != null) "has_url" else "no_url")
                            // If there's an active conversation, reopen it directly (no dialog)
                            val hasActiveChat = chatViewModel.messages.value.isNotEmpty()
                            if (hasActiveChat) {
                                showAiChat = true
                            } else if (url != null && ChatHistory.normalizeUrlForMatch(url) != null) {
                                // Real URL but no active chat — show session choice dialog
                                showSessionChoice = true
                            } else {
                                // about:blank, chrome://, etc. — always new session
                                forceNewSession = true
                                showAiChat = true
                            }
                        }) {
                            Icon(
                                Icons.Default.SmartToy,
                                contentDescription = "AI Chat",
                                tint = if (showAiChat) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { showBridgeInfo = !showBridgeInfo; SessionLog.uiClick("bridge_info_btn") }) {
                            Icon(
                                Icons.Default.SettingsEthernet,
                                contentDescription = "Bridge API",
                                tint = if (showBridgeInfo) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { showDevTools = !showDevTools; SessionLog.uiClick("devtools_btn") }) {
                            Icon(
                                Icons.Default.BugReport,
                                contentDescription = "DevTools",
                                tint = if (showDevTools) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { showSettings = true; SessionLog.uiClick("settings_btn") }) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = if (showSettings) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            // Browser is always in composition — preserves browser engine state
            BrowserTab(
                clearAddressFocus = showDevTools || showBridgeInfo || showAiChat,
                headerExpanded = headerVisible && !showAiChat,
                headerVisible = !showAiChat,
                onHeaderVisibilityToggle = { headerVisible = !headerVisible },
                onEngineReady = onEngineReady,
                onEngineCreated = { engine -> engineRef = engine },
                onAskAi = { question ->
                    pendingAiQuestion = question
                    val url = engineRef?.getUrl()
                    currentUrlForChat = url
                    // If there's an active conversation, reopen directly
                    val hasActiveChat = chatViewModel.messages.value.isNotEmpty()
                    if (hasActiveChat) {
                        showAiChat = true
                    } else if (url != null && ChatHistory.normalizeUrlForMatch(url) != null) {
                        showSessionChoice = true
                    } else {
                        forceNewSession = true
                        showAiChat = true
                    }
                }
            )

            // Session choice dialog — shown when a matching URL conversation exists
            if (showSessionChoice) {
                val context = LocalContext.current
                val matched = currentUrlForChat?.let { ChatHistory.findConversationByUrl(context, it) }

                if (matched != null) {
                    // Found existing conversation for this URL
                    AlertDialog(
                        onDismissRequest = { showSessionChoice = false; SessionLog.uiNav("session_choice", "close") },
                        title = { Text("Existing session found") },
                        text = {
                            Column {
                                Text("A conversation for this page already exists:")
                                Spacer(modifier = Modifier.height(8.dp))
                                Surface(
                                    tonalElevation = 1.dp,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        matched.title ?: "Untitled",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Resume that session or start a new one?", style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showSessionChoice = false
                                matchedConversationId = matched.id
                                showAiChat = true
                            }) { Text("Resume") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showSessionChoice = false
                                matchedConversationId = null  // new session
                                forceNewSession = true
                                showAiChat = true
                            }) { Text("New session") }
                        }
                    )
                } else {
                    // No existing conversation — open chat directly with new session
                    LaunchedEffect(Unit) {
                        showSessionChoice = false
                        matchedConversationId = null
                        forceNewSession = true
                        showAiChat = true
                    }
                }
            }

            // AI Chat as bottom sheet — browser engine stays visible behind
            if (showAiChat) {
                ModalBottomSheet(
                    onDismissRequest = { showAiChat = false; pendingAiQuestion = null; matchedConversationId = null; SessionLog.uiNav("ai_chat", "close") },
                    containerColor = MaterialTheme.colorScheme.surface,
                    sheetState = rememberModalBottomSheetState(
                        skipPartiallyExpanded = false
                    ),
                ) {
                    AiChatScreen(
                        engine = engineRef,
                        cdpClient = app!!.cdpClient,
                        initialPrompt = pendingAiQuestion,
                        startNewConversation = forceNewSession || (matchedConversationId == null && pendingAiQuestion != null),
                        resumeConversationId = matchedConversationId,
                        sourceUrl = if (matchedConversationId == null) currentUrlForChat else null,
                        onDismiss = { showAiChat = false; pendingAiQuestion = null; matchedConversationId = null; forceNewSession = false },
                        modifier = Modifier.imePadding()
                    )
                }
            }

            // DevTools as bottom sheet overlay
            if (showDevTools) {
                ModalBottomSheet(
                    onDismissRequest = { showDevTools = false; SessionLog.uiNav("devtools", "close") },
                    containerColor = MaterialTheme.colorScheme.surface,
                    sheetState = rememberModalBottomSheetState(
                        skipPartiallyExpanded = false
                    ),
                ) {
                    DevToolsPanel(
                        selectedTab = devToolsTab,
                        onTabChange = { devToolsTab = it },
                        modifier = Modifier.imePadding()
                    )
                }
            }

            // Bridge API info sheet
            if (showBridgeInfo) {
                ModalBottomSheet(
                    onDismissRequest = { showBridgeInfo = false; SessionLog.uiNav("bridge_info", "close") },
                    containerColor = MaterialTheme.colorScheme.surface,
                    sheetState = rememberModalBottomSheetState(
                        skipPartiallyExpanded = false
                    ),
                ) {
                    BridgeInfoPanel(
                        authToken = bridgeAuthToken,
                        port = bridgePort,
                        tunnelUrl = tunnelUrl,
                        tunnelError = tunnelError,
                        modifier = Modifier.imePadding()
                    )
                }
            }

            // Settings sheet
            if (showSettings) {
                ModalBottomSheet(
                    onDismissRequest = { showSettings = false; SessionLog.uiNav("settings", "close") },
                    containerColor = MaterialTheme.colorScheme.surface,
                    sheetState = rememberModalBottomSheetState(
                        skipPartiallyExpanded = false
                    ),
                ) {
                    SettingsSheet(
                        cdpClient = app!!.cdpClient,
                        onDismiss = { showSettings = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun DevToolsPanel(
    selectedTab: Int,
    onTabChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 0.dp) {
            Tab(selected = selectedTab == 0, onClick = { onTabChange(0) }, text = { Text("Console", style = MaterialTheme.typography.titleSmall) })
            Tab(selected = selectedTab == 1, onClick = { onTabChange(1) }, text = { Text("Network", style = MaterialTheme.typography.titleSmall) })
            Tab(selected = selectedTab == 2, onClick = { onTabChange(2) }, text = { Text("Perf", style = MaterialTheme.typography.titleSmall) })
        }

        when (selectedTab) {
            0 -> ConsoleTab()
            1 -> NetworkTab()
            2 -> PerformanceTab()
        }
    }
}

@Composable
private fun BridgeInfoPanel(
    authToken: String,
    port: Int,
    tunnelUrl: String? = null,
    tunnelError: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.md)
    ) {
        Text(
            "Bridge API",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            "AI agents can control the browser via HTTP API",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(Spacing.md))

        // Server info
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(Spacing.md)) {
                Text("Server", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(Spacing.xs))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("Host:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(0.3f))
                    Text("0.0.0.0:$port", style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Token:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(0.3f))
                    Text(
                        maskToken(authToken),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.weight(0.55f)
                    )
                    TextButton(onClick = {
                        val clip = android.content.ClipData.newPlainText("token", authToken)
                        (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
                        android.widget.Toast.makeText(context, "Token copied", android.widget.Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy token", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text("Copy", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.md))

        // Tunnel status
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (tunnelUrl != null) MaterialTheme.colorScheme.primaryContainer
                else if (tunnelError != null) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(Spacing.md)) {
                when {
                    tunnelUrl != null -> {
                        Text("External Access", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text("URL:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.15f))
                            Text(tunnelUrl, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.height(Spacing.xxs))
                        Text(
                            "curl -H \"Authorization: Bearer $authToken\" http://$tunnelUrl/info",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    tunnelError != null -> {
                        Text("External Access — Error", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        Text(tunnelError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(modifier = Modifier.height(Spacing.xxs))
                        Text("Auto-reconnecting...", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f))
                    }
                    else -> {
                        Text("External Access", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        Text("Connecting to bore.pub...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(Spacing.xxs))
                        Text("Auto-reconnects on failure", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.md))

        // Endpoints
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(Spacing.md)) {
                Text("Endpoints", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(Spacing.xs))
                val endpoints = listOf(
                    "GET /info" to "Server info",
                    "POST /eval" to "Evaluate JS",
                    "GET /console" to "Console logs",
                    "GET /network" to "Network events",
                    "GET /dom" to "DOM snapshot",
                    "POST /navigate" to "Navigate URL",
                    "GET /screenshot" to "Browser engine screenshot",
                    "GET /perf" to "Performance metrics",
                    "POST /inspector" to "Toggle inspector",
                )
                endpoints.forEach { (method, desc) ->
                    val httpMethod = method.substringBefore(' ')
                    val methodColor = when (httpMethod) {
                        "GET" -> MaterialTheme.colorScheme.tertiary
                        "POST" -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xxs)) {
                        Text(method, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = methodColor, modifier = Modifier.weight(0.45f))
                        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.55f))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.md))

        Text(
            "Usage: curl -H \"Authorization: Bearer $authToken\" http://<ip>:$port/info",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline
        )

        Spacer(modifier = Modifier.height(Spacing.lg))
    }
}

private fun maskToken(token: String): String {
    if (token.length <= 8) return "••••••••"
    return token.take(4) + "••••" + token.takeLast(4)
}