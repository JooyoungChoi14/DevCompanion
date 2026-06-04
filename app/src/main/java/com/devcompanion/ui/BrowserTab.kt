package com.devcompanion.ui

import android.graphics.Bitmap
import android.webkit.WebView
import android.util.Log
import com.devcompanion.logging.SessionLog
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
import android.webkit.JavascriptInterface
import com.devcompanion.data.Bookmark
import com.devcompanion.data.BookmarksStore
import com.devcompanion.data.UrlHistoryStore
import com.devcompanion.debug.WebViewDebugger
import com.devcompanion.debug.WebViewDebuggerHolder
import com.devcompanion.bridge.BridgeServer
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
    onWebViewReady: ((() -> Boolean) -> Unit)? = null,
    onWebViewCreated: ((WebView) -> Unit)? = null,
    onAskAi: ((String) -> Unit)? = null,
    onNavigateHome: (() -> Unit)? = null,
    startPageVisible: Boolean = true,
    onStartPageVisibleChange: ((Boolean) -> Unit)? = null,
) {
    var urlTextValue by remember { mutableStateOf(TextFieldValue("about:blank")) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var pageTitle by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<BrowserAction?>(null) }
    var urlExpanded by remember { mutableStateOf(false) }
    var viewportScale by remember { mutableIntStateOf(100) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var urlHadFocus by remember { mutableStateOf(false) }
    var webViewCrashed by remember { mutableStateOf(false) }
    var webViewKey by remember { mutableIntStateOf(0) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val debugger = remember { WebViewDebugger() }
    val urlHistory by debugger.urlHistory.collectAsState(initial = emptyList())

    // Expose canGoBack to parent for back button handling
    LaunchedEffect(webViewRef, canGoBack) {
        onWebViewReady?.invoke {
            if (canGoBack && webViewRef != null) {
                webViewRef?.goBack()
                SessionLog.uiClick("webview_back")
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

    // ── Start page / Bookmarks state ──────────────────────────────────
    val context = LocalContext.current
    val bookmarksStore = remember { BookmarksStore(context) }
    val urlHistoryStore = remember { UrlHistoryStore(context) }
    var bookmarks by remember { mutableStateOf(bookmarksStore.getBookmarks()) }
    var showStartPage by remember { mutableStateOf(startPageVisible) }
    val startPageSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false
    )

    // Initialize urlHistory from persistent store
    LaunchedEffect(Unit) {
        val persisted = urlHistoryStore.getUrls()
        if (persisted.isNotEmpty()) {
            debugger.restoreUrlHistory(persisted)
        }
    }

    // Navigate away from start page
    val navigateFromStartPage: (String) -> Unit = { url ->
        showStartPage = false
        onStartPageVisibleChange?.invoke(false)
        urlTextValue = TextFieldValue(url, TextRange(url.length))
        pendingAction = BrowserAction.Navigate(url)
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
                            showStartPage = true
                            onStartPageVisibleChange?.invoke(true)
                            onNavigateHome?.invoke()
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
                                webViewRef?.settings?.textZoom = scale
                                webViewRef?.evaluateJavascript(
                                    "(function(){document.documentElement.style.zoom='${scale / 100.0}';})();",
                                    null
                                )
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

        // ── WebView + Crash overlay ──────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            key(webViewKey) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                Log.i(TAG, "Creating WebView with debugger (key=$webViewKey)")
                // Reset crash state on factory re-invocation
                webViewCrashed = false
                com.devcompanion.logging.SessionLog.log(
                    com.devcompanion.logging.EventType.WEBVIEW_RECOVER,
                    mapOf("webViewKey" to webViewKey.toString())
                )
                WebView(ctx.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.textZoom = viewportScale
                    setInitialScale(viewportScale)
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.6422.113 Mobile Safari/537.36"

                    importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_YES
                    settings.saveFormData = true
                    @Suppress("DEPRECATION")
                    settings.savePassword = true
                    isFocusable = true
                    isFocusableInTouchMode = true

                    webChromeClient = debugger.DebugChromeClient()
                    webViewClient = object : android.webkit.WebViewClient() {
                        override fun onPageStarted(view: WebView, pageUrl: String, favicon: Bitmap?) {
                            isLoading = true
                            urlTextValue = TextFieldValue(pageUrl, TextRange(pageUrl.length))
                            debugger.addUrlToHistory(pageUrl)
                            urlHistoryStore.addUrl(pageUrl)
                            debugger.markPageStart()
                            SessionLog.uiWebviewState(pageUrl, view.width, view.height, view.scrollX, view.scrollY, view.contentHeight)
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            isLoading = false
                            canGoBack = view.canGoBack()
                            canGoForward = view.canGoForward()
                            pageTitle = view.title ?: ""
                            SessionLog.uiWebviewState(url, view.width, view.height, view.scrollX, view.scrollY, view.contentHeight)
                            view.evaluateJavascript(
                                "(function(){document.documentElement.style.zoom='${viewportScale / 100.0}';})();",
                                null
                            )
                            view.evaluateJavascript(AUTOFILL_INJECTION, null)
                            // WebView rendering fixes
                            view.evaluateJavascript(VH_FIX_INJECTION, null)
                            // KEYBOARD_FIX & OVERFLOW_FIX removed: adjustResize handles
                            // viewport resizing natively. JS body locking caused
                            // scroll-lock residue on pages like 서경포탈.
                            view.evaluateJavascript(TEXT_SIZE_FIX_INJECTION, null)
                            if (debugger.inspectorEnabled) {
                                view.evaluateJavascript(INSPECTOR_IFRAME_INJECTION, null)
                            }
                            debugger.DebugWebViewClient().onPageFinished(view, url)
                        }

                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: android.webkit.WebResourceRequest
                        ): android.webkit.WebResourceResponse? {
                            return debugger.DebugWebViewClient().shouldInterceptRequest(view, request)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            debugger.DebugWebViewClient().onReceivedError(view, request, error)
                        }

                        override fun onReceivedHttpError(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                            errorResponse: android.webkit.WebResourceResponse
                        ) {
                            // Track HTTP errors (4xx/5xx from server) in network log
                            val url = request.url.toString()
                            val statusCode = errorResponse.statusCode
                            val reasonPhrase = errorResponse.reasonPhrase ?: "HTTP error"
                            debugger.trackHttpError(url, statusCode, reasonPhrase)
                            com.devcompanion.logging.SessionLog.networkError(url, statusCode, reasonPhrase)
                        }

                        override fun onRenderProcessGone(
                            view: WebView,
                            detail: android.webkit.RenderProcessGoneDetail
                        ): Boolean {
                            Log.e(TAG, "WebView render process gone: didCrash=${detail.didCrash()}")
                            com.devcompanion.logging.SessionLog.log(
                                com.devcompanion.logging.EventType.WEBVIEW_CRASH,
                                mapOf("didCrash" to detail.didCrash().toString())
                            )
                            if (detail.didCrash()) {
                                // Render process crashed — mark crashed and trigger WebView recreation
                                // Do NOT call view.destroy() directly: Compose manages the View lifecycle.
                                // Incrementing webViewKey forces AndroidView factory to re-run.
                                webViewCrashed = true
                                webViewKey += 1
                            }
                            return true
                        }
                    }

                    debugger.attachWebView(this)
                    webViewRef = this
                    onWebViewCreated?.invoke(this)

                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun post(json: String) {
                            debugger.onInspectorResult(json)
                        }
                    }, "__devCompanionInspector")

                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun post(json: String) {
                            debugger.onPerformanceResult(json)
                        }
                    }, "__devCompanionPerf")

                    loadUrl("about:blank")
                }
            },
            update = { webView ->
                when (val action = pendingAction) {
                    is BrowserAction.Navigate -> {
                        webView.loadUrl(action.url)
                        pendingAction = null
                    }
                    BrowserAction.GoBack -> {
                        if (webView.canGoBack()) webView.goBack()
                        pendingAction = null
                    }
                    BrowserAction.GoForward -> {
                        if (webView.canGoForward()) webView.goForward()
                        pendingAction = null
                    }
                    BrowserAction.Reload -> {
                        webView.reload()
                        pendingAction = null
                    }
                    null -> { }
                }
            }
        )
        }

            // ── Crash overlay ───────────────────────────────────────
            if (webViewCrashed) {
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
                            contentDescription = "WebView crashed",
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(Spacing.md))
                        Text(
                            "WebView has stopped",
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
                                webViewCrashed = false
                                webViewKey += 1
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

    // ── Start page bottom sheet ──────────────────────────────────────
    if (showStartPage) {
        ModalBottomSheet(
            onDismissRequest = {
                showStartPage = false
                onStartPageVisibleChange?.invoke(false)
            },
            sheetState = startPageSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            StartPage(
                bookmarks = bookmarks,
                recentUrls = urlHistory.takeLast(5).reversed(),
                onBookmarkClick = { url: String? -> if (url != null) navigateFromStartPage(url) },
                onAddBookmark = {
                    val currentUrl = urlTextValue.text
                    val currentTitle = pageTitle
                    if (currentUrl.isNotBlank() && currentUrl != "about:blank") {
                        val bm = Bookmark(title = currentTitle.ifBlank { currentUrl }, url = currentUrl)
                        bookmarksStore.addBookmark(bm)
                        bookmarks = bookmarksStore.getBookmarks()
                    }
                },
                onRemoveBookmark = { id: String ->
                    bookmarksStore.removeBookmark(id)
                    bookmarks = bookmarksStore.getBookmarks()
                },
                onRecentClick = { url: String -> navigateFromStartPage(url) },
                onSearch = { query: String ->
                    when (val route = routeUrlInput(query)) {
                        is UrlRoute.AiQuestion -> onAskAi?.invoke(route.question)
                        is UrlRoute.Direct, is UrlRoute.Url, is UrlRoute.Search -> {
                            val url = when (route) {
                                is UrlRoute.Direct -> route.url
                                is UrlRoute.Url -> route.url
                                is UrlRoute.Search -> route.url
                                else -> query
                            }
                            urlTextValue = TextFieldValue(url, TextRange(url.length))
                            pendingAction = BrowserAction.Navigate(url)
                            showStartPage = false
                            onStartPageVisibleChange?.invoke(false)
                        }
                    }
                },
            )
        }
    }

    } // Box

    LaunchedEffect(Unit) {
        WebViewDebuggerHolder.current = debugger
    }

    // Connect WebView to BridgeServer for agent API access
    DisposableEffect(webViewRef) {
        val app = context.applicationContext as? DevCompanionApp
        app?.bridgeServer?.attachWebView(webViewRef)
        onDispose {
            app?.bridgeServer?.attachWebView(null)
        }
    }
}

// Inject inspector to all iframes recursively
private val INSPECTOR_IFRAME_INJECTION = """(function(){
    function injectToIframe(iframe){
        try{
            var doc=iframe.contentDocument||iframe.contentWindow.document;
            if(!doc||doc.getElementById('__devOverlay')) return;
            var script=doc.createElement('script');
            script.textContent=`(function(){
                if(document.getElementById('__devOverlay')) return;
                var overlay=document.createElement('div');
                overlay.id='__devOverlay';
                overlay.style.cssText='position:fixed;pointer-events:none;z-index:999999;border:2px solid #4CAF50;background:rgba(76,175,80,0.1);border-radius:2px;transition:all 0.15s ease;display:none;';
                document.body.appendChild(overlay);
                function getXPath(el){
                    if(el.id) return '//*[@id=\"'+el.id+'\"]';
                    var parts=[];
                    while(el&&el.nodeType===1){
                        var idx=1,sib=el.previousSibling;
                        while(sib){if(sib.nodeType===1&&sib.tagName===el.tagName)idx++;sib=sib.previousSibling;}
                        var tag=el.tagName.toLowerCase();
                        parts.unshift(tag+'['+idx+']');
                        el=el.parentNode;
                    }
                    return '/'+parts.join('/');
                }
                function getCssSelector(el){
                    if(el.id) return '#'+el.id;
                    var sel=el.tagName.toLowerCase();
                    if(el.className&&typeof el.className==='string'){
                        var cls=el.className.trim().split(/\\s+/).filter(function(c){return c;}).join('.');
                        if(cls) sel+='.'+cls;
                    }
                    return sel;
                }
                function handler(e){
                    var target=e.target;
                    if(target===overlay) return;
                    e.preventDefault();
                    e.stopPropagation();
                    var rect=target.getBoundingClientRect();
                    overlay.style.left=rect.left+'px';
                    overlay.style.top=rect.top+'px';
                    overlay.style.width=rect.width+'px';
                    overlay.style.height=rect.height+'px';
                    overlay.style.display='block';
                    var attrs={};
                    for(var i=0;i<target.attributes.length;i++){
                        var a=target.attributes[i];
                        attrs[a.name]=a.value;
                    }
                    var data={
                        tagName:target.tagName,
                        id:target.id||null,
                        className:target.className&&typeof target.className==='string'?target.className:null,
                        xpath:getXPath(target),
                        cssSelector:getCssSelector(target),
                        textContent:target.textContent?target.textContent.substring(0,200):null,
                        attributes:attrs,
                        boundingRect:{left:rect.left,top:rect.top,width:rect.width,height:rect.height}
                    };
                    if(window.parent&&window.parent.__devCompanionInspector){
                        window.parent.__devCompanionInspector.post(JSON.stringify(data));
                    } else if(window.__devCompanionInspector){
                        window.__devCompanionInspector.post(JSON.stringify(data));
                    }
                }
                document.addEventListener('touchstart',handler,{capture:true,passive:false});
                document.addEventListener('click',handler,true);
            })();`;
            doc.head.appendChild(script);
        }catch(e){}
    }
    var iframes=document.querySelectorAll('iframe');
    for(var i=0;i<iframes.length;i++){
        injectToIframe(iframes[i]);
    }
})();"""

// Inject autocomplete/autofill attributes into form fields
private val AUTOFILL_INJECTION = """(function(){
    var autofillMap = {
        'username': ['text','email'],
        'email': ['email'],
        'current-password': ['password'],
        'new-password': ['password'],
        'tel': ['tel'],
        'street-address': ['text'],
        'address-line1': ['text'],
        'address-line2': ['text'],
        'address-level1': ['text'],
        'address-level2': ['text'],
        'postal-code': ['text'],
        'country': ['text'],
        'country-name': ['text'],
        'cc-number': ['text'],
        'cc-exp': ['text'],
        'cc-exp-month': ['text'],
        'cc-exp-year': ['text'],
        'cc-csc': ['text'],
        'cc-given-name': ['text'],
        'cc-family-name': ['text'],
        'cc-name': ['text'],
        'cc-type': ['text']
    };
    function inferAutocomplete(input) {
        if (input.autocomplete && input.autocomplete !== 'off') return;
        var type = (input.type || 'text').toLowerCase();
        var name = (input.name || '').toLowerCase();
        var id = (input.id || '').toLowerCase();
        var hint = '';
        if (type === 'email' || name.includes('email') || id.includes('email')) hint = 'email';
        else if (type === 'password' || name.includes('pass') || id.includes('pass')) {
            hint = name.includes('new') || id.includes('new') ? 'new-password' : 'current-password';
        }
        else if (type === 'tel' || name.includes('phone') || id.includes('phone')) hint = 'tel';
        else if (name.includes('user') || id.includes('user') || name.includes('login') || id.includes('login')) hint = 'username';
        else if (name.includes('search') || id.includes('search')) hint = 'off';
        if (hint) input.setAttribute('autocomplete', hint);
    }
    var inputs = document.querySelectorAll('input');
    for (var i = 0; i < inputs.length; i++) {
        inferAutocomplete(inputs[i]);
    }
})();"""
/**
 * Fixes Android WebView vh/dvh unit miscalculation.
 *
 * Known issues in Android WebView:
 * - 100vh / 100dvh may compute to 0px
 * - Vuetify sets inline height:0px on navigation drawers
 * - Elements with CSS calc() using vh/dvh also break
 * - Vuetify Vue bindings re-set inline styles on re-render
 *
 * Strategy:
 * 1. Set --webview-vh CSS custom property on :root
 * 2. Fix inline 100vh/100dvh -> innerHeight px
 * 3. Fix computed height:0px on Vuetify/navigation elements
 * 4. Fix CSS calc() using vh/dvh (e.g., calc(-75px + 100dvh))
 * 5. MutationObserver watches for framework re-renders
 * 6. Resize listener for viewport changes
 */
private val VH_FIX_INJECTION = """(function(){
    var h = window.innerHeight;

    // 1. Set CSS custom property
    document.documentElement.style.setProperty('--webview-vh', (h * 0.01) + 'px');

    // 2-4. Fix all vh/dvh related height issues
    function fixVhUnits() {
        h = window.innerHeight;
        document.documentElement.style.setProperty('--webview-vh', (h * 0.01) + 'px');

        // Fix inline 100vh/100dvh styles
        var inlineSelectors = '.v-navigation-drawer, .navigation-container, .v-navigation-drawer__content, [style*="100vh"], [style*="100dvh"]';
        document.querySelectorAll(inlineSelectors).forEach(function(el) {
            var style = el.getAttribute('style') || '';
            if (style.includes('100vh') || style.includes('100dvh')) {
                el.style.setProperty('height', h + 'px', 'important');
            }
        });

        // Fix Vuetify aside elements with computed height:0px
        // Vuetify sets position:fixed + top:0 + bottom:Npx + height:0px
        // CSS rule uses calc(-Npx + 100dvh) which evaluates to 0 in WebView
        var asideSelectors = '.v-navigation-drawer aside, .navigation-container aside, .system-menu, .system-depth-menu';
        document.querySelectorAll(asideSelectors).forEach(function(el) {
            var computedHeight = window.getComputedStyle(el).height;
            if (computedHeight === '0px' && el.children.length > 0) {
                var marginTop = parseInt(window.getComputedStyle(el).marginTop) || 0;
                el.style.setProperty('height', (h - marginTop) + 'px', 'important');
                el.style.removeProperty('bottom');
            }
        });

        // Fix parent container collapse
        document.querySelectorAll('.v-navigation-drawer__content').forEach(function(el) {
            var computedHeight = window.getComputedStyle(el).height;
            if (computedHeight === '0px') {
                el.style.setProperty('height', '100%', 'important');
            }
        });
    }
    fixVhUnits();

    // 5. MutationObserver: Vuetify/Vue re-renders reset inline styles
    var observer = new MutationObserver(function(mutations) {
        var needsFix = false;
        mutations.forEach(function(m) {
            if (m.type === 'attributes' && m.attributeName === 'style') {
                var target = m.target;
                var style = target.getAttribute('style') || '';
                if (style.includes('100vh') || style.includes('100dvh') || style.includes('height: 0px') || style.includes('height:0px')) {
                    target.style.setProperty('height', window.innerHeight + 'px', 'important');
                    needsFix = true;
                }
                // Catch Vuetify removing our fix on navigation elements
                if (target.classList && (
                    target.classList.contains('v-navigation-drawer') ||
                    target.classList.contains('system-menu') ||
                    target.classList.contains('system-depth-menu') ||
                    target.classList.contains('navigation-container')
                )) {
                    needsFix = true;
                }
            }
        });
        if (needsFix) fixVhUnits();
    });
    observer.observe(document.documentElement, {
        attributes: true,
        subtree: true,
        attributeFilter: ['style']
    });

    // 6. Viewport changes (rotation, split screen, keyboard)
    window.addEventListener('resize', fixVhUnits);
})();"""

/**
 * Fixes position:fixed elements when virtual keyboard appears.
 * Android WebView resizes the viewport when the keyboard opens, causing
 * fixed elements (headers, bottom navs, modals) to shift or disappear.
 * This script adjusts fixed elements to use absolute positioning during
 * keyboard visibility, matching iOS WKWebView behavior.
 */
private val TEXT_SIZE_FIX_INJECTION = """(function(){
    var style = document.createElement('style');
    style.textContent = [
        'html {',
        '  -webkit-text-size-adjust: 100% !important;',
        '  -ms-text-size-adjust: 100% !important;',
        '  text-size-adjust: 100% !important;',
        '}',
        'body {',
        '  -webkit-text-size-adjust: 100% !important;',
        '  text-size-adjust: 100% !important;',
        '}'
    ].join('\\n');
    document.head.appendChild(style);
})();"""
