package com.devcompanion.ui

import android.util.Log
import com.devcompanion.engine.BrowserEngine
import com.devcompanion.engine.EngineFactory
import com.devcompanion.logging.SessionLog
import com.devcompanion.logging.EventType
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

import com.devcompanion.data.UrlHistoryStore
import com.devcompanion.debug.BrowserDebugger
import com.devcompanion.debug.BrowserDebuggerHolder
import com.devcompanion.DevCompanionApp
import com.devcompanion.llm.routeUrlInput
import com.devcompanion.llm.UrlRoute
import com.devcompanion.ui.theme.Spacing

private const val TAG = "BrowserTab"

sealed class BrowserAction {
    data class Navigate(val url: String) : BrowserAction()
    object GoBack : BrowserAction()
    object GoForward : BrowserAction()
    object Reload : BrowserAction()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserTab(
    modifier: Modifier = Modifier,
    clearAddressFocus: Boolean = false,
    headerExpanded: Boolean = true,
    headerVisible: Boolean = true,
    onHeaderVisibilityToggle: (() -> Unit)? = null,
    onEngineReady: ((() -> Boolean) -> Unit)? = null,
    onEngineCreated: ((BrowserEngine) -> Unit)? = null,
    onAskAi: ((String) -> Unit)? = null,
    homeUrl: String = "about:blank",
) {
    var urlTextValue by remember { mutableStateOf(TextFieldValue("about:blank")) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var pageTitle by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<BrowserAction?>(null) }
    var urlExpanded by remember { mutableStateOf(false) }
    var viewportScale by remember { mutableIntStateOf(100) }
    var engineRef by remember { mutableStateOf<BrowserEngine?>(null) }
    var urlHadFocus by remember { mutableStateOf(false) }
    var engineCrashed by remember { mutableStateOf(false) }
    var engineKey by remember { mutableIntStateOf(0) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val context = LocalContext.current
    val urlHistoryStore = remember { UrlHistoryStore(context) }

    // Debugger: GeckoView uses NoOpDebugger (DevTools not yet supported)
    val debugger: BrowserDebugger = remember { EngineFactory.createDebugger() }
    // URL history comes from UrlHistoryStore (persistent) — not coupled to debugger
    val urlHistory by urlHistoryStore.urlsFlow.collectAsState(initial = emptyList())

    // Expose canGoBack to parent for back button handling
    LaunchedEffect(engineRef, canGoBack) {
        onEngineReady?.invoke {
            if (canGoBack && engineRef != null) {
                engineRef?.goBack()
                SessionLog.uiClick("browser_back")
                true
            } else {
                false
            }
        }
    }

    // Clear address focus when DevTools opens
    LaunchedEffect(clearAddressFocus) {
        if (clearAddressFocus) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }

    // Initialize debugger's URL history from persistent store
    LaunchedEffect(Unit) {
        val persisted = urlHistoryStore.getUrls()
        debugger.restoreUrlHistory(persisted)
    }

    Box(modifier = modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        // ── Expanded header (URL bar + nav + zoom) ────────────────
        AnimatedVisibility(
            visible = headerVisible && headerExpanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column {
                // URL bar row with collapse button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = urlTextValue,
                        onValueChange = { newValue ->
                            urlTextValue = newValue
                            urlExpanded = newValue.text.length >= 3
                        },
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { focusState ->
                                android.util.Log.d("SelectAllDebug", "[URL] onFocusChanged: hadFocus=$urlHadFocus, isFocused=${focusState.isFocused}, textLen=${urlTextValue.text.length}, selBefore=${urlTextValue.selection}")
                                if (!urlHadFocus && focusState.isFocused) {
                                    val newSel = TextRange(0, urlTextValue.text.length)
                                    urlTextValue = urlTextValue.copy(selection = newSel)
                                    android.util.Log.d("SelectAllDebug", "[URL] Applied select-all: newSel=$newSel, actualSel=${urlTextValue.selection}")
                                }
                                urlHadFocus = focusState.isFocused
                            },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                val input = urlTextValue.text.trim()
                                SessionLog.uiClick("url_bar_go", input.take(50))
                                when (val route = routeUrlInput(input)) {
                                    is UrlRoute.AiQuestion -> {
                                        onAskAi?.invoke(route.question)
                                        focusManager.clearFocus()
                                    }
                                    is UrlRoute.Direct -> {
                                        pendingAction = BrowserAction.Navigate(route.url)
                                    }
                                    is UrlRoute.Url -> {
                                        urlTextValue = TextFieldValue(route.url, TextRange(route.url.length))
                                        pendingAction = BrowserAction.Navigate(route.url)
                                    }
                                    is UrlRoute.Search -> {
                                        urlTextValue = TextFieldValue(route.url, TextRange(route.url.length))
                                        pendingAction = BrowserAction.Navigate(route.url)
                                    }
                                }
                                urlExpanded = false
                                focusManager.clearFocus()
                            }
                        ),
                        trailingIcon = {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        },
                        label = { Text("URL", style = MaterialTheme.typography.labelSmall) }
                    )

                    // Single collapse button
                    IconButton(
                        onClick = { onHeaderVisibilityToggle?.invoke() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.ExpandLess,
                            contentDescription = "Collapse",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // URL autocomplete dropdown
                val filtered = urlHistory.filter {
                    it.contains(urlTextValue.text, ignoreCase = true) && it != urlTextValue.text
                }
                if (filtered.isNotEmpty() && urlExpanded) {
                    Popup(
                        alignment = Alignment.TopStart,
                        offset = IntOffset(8, 0)
                    ) {
                        Surface(
                            modifier = Modifier.width(300.dp),
                            shape = MaterialTheme.shapes.small,
                            tonalElevation = 4.dp,
                            shadowElevation = 8.dp
                        ) {
                            Column {
                                filtered.take(5).forEach { match ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                urlTextValue = TextFieldValue(match, TextRange(match.length))
                                                urlExpanded = false
                                                pendingAction = BrowserAction.Navigate(match)
                                            }
                                            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                                    ) {
                                        Text(
                                            match,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Nav + viewport scale (always visible when expanded)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { pendingAction = BrowserAction.GoBack },
                        enabled = canGoBack,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = { pendingAction = BrowserAction.GoForward },
                        enabled = canGoForward,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.ArrowForward, contentDescription = "Forward", modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = { pendingAction = BrowserAction.Reload },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = {
                            pendingAction = BrowserAction.Navigate(homeUrl)
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Home, contentDescription = "Home", modifier = Modifier.size(18.dp))
                    }
                    Text(
                        text = pageTitle,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = Spacing.xs),
                        maxLines = 1
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Zoom", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    listOf(100, 120, 150, 200).forEach { scale ->
                        FilterChip(
                            selected = viewportScale == scale,
                            onClick = {
                                viewportScale = scale
                                SessionLog.uiClick("viewport_scale", "${scale}%")
                                engineRef?.setTextZoom(scale)
                            },
                            label = { Text("${scale}%", style = MaterialTheme.typography.labelMedium) },
                            modifier = Modifier.height(28.dp),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true, selected = viewportScale == scale,
                                borderColor = MaterialTheme.colorScheme.outline,
                                selectedBorderColor = MaterialTheme.colorScheme.primary
                            ),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                                containerColor = MaterialTheme.colorScheme.surface,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }

                HorizontalDivider()
            }
        }

        // ── Collapsed mini bar ──────────────────────────────────────
        AnimatedVisibility(
            visible = headerVisible && !headerExpanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onHeaderVisibilityToggle?.invoke() }
                    .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = "Expand",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(Spacing.xs))
                Text(
                    urlTextValue.text,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp
                    )
                } else {
                    // Emergency reload: visible even when collapsed
                    IconButton(
                        onClick = { pendingAction = BrowserAction.Reload },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Reload",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        HorizontalDivider()

        // ── Engine view + Crash overlay ──────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            key(engineKey) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                Log.i(TAG, "Creating browser engine (key=$engineKey)")
                // Reset crash state on factory re-invocation
                engineCrashed = false
                SessionLog.log(
                    EventType.UI_NAV,
                    mapOf("target" to "browser_engine", "action" to "create", "detail" to "key=$engineKey")
                )
                val engine = EngineFactory.create(ctx, debugger)
                engineRef = engine
                onEngineCreated?.invoke(engine)

                // Engine setup: each flavor handles its own client/delegate installation
                engine.setup(viewportScale, urlHistoryStore)
                engine.setCallbacks(object : BrowserEngine.Callbacks {
                    override fun onPageStarted(url: String) {
                        isLoading = true
                        urlTextValue = TextFieldValue(url, TextRange(url.length))
                    }
                    override fun onPageFinished(url: String, title: String?, canBack: Boolean, canFwd: Boolean) {
                        isLoading = false
                        canGoBack = canBack
                        canGoForward = canFwd
                        pageTitle = title ?: ""
                    }
                    override fun onRenderProcessGone() {
                        engineCrashed = true
                        engineKey += 1
                    }
                })
                engine.loadUrl("about:blank")

                engine.view
            },
            update = { _ ->
                when (val action = pendingAction) {
                    is BrowserAction.Navigate -> {
                        engineRef?.loadUrl(action.url)
                        pendingAction = null
                    }
                    BrowserAction.GoBack -> {
                        if (engineRef?.canGoBack() == true) engineRef?.goBack()
                        pendingAction = null
                    }
                    BrowserAction.GoForward -> {
                        if (engineRef?.canGoForward() == true) engineRef?.goForward()
                        pendingAction = null
                    }
                    BrowserAction.Reload -> {
                        engineRef?.reload()
                        pendingAction = null
                    }
                    null -> { }
                }
            }
        )
        }

            // ── Crash overlay ───────────────────────────────────────
            if (engineCrashed) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(Spacing.lg)
                    ) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = "Browser crashed",
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(Spacing.md))
                        Text(
                            "Browser has stopped",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        Text(
                            "The render process crashed. Tap to retry.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(Spacing.md))
                        Button(
                            onClick = {
                                engineCrashed = false
                                engineKey += 1
                            }
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(Spacing.xs))
                            Text("Retry")
                        }
                    }
                }
            }
        }
    }

    } // Box

    LaunchedEffect(Unit) {
        BrowserDebuggerHolder.current = debugger
    }

    // Connect browser engine to BridgeServer for agent API access
    DisposableEffect(engineRef) {
        val app = context.applicationContext as? DevCompanionApp
        app?.bridgeServer?.attachEngine(engineRef)
        onDispose {
            app?.bridgeServer?.attachEngine(null)
        }
    }
}

