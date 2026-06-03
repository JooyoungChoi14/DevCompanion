package com.devcompanion.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.devcompanion.ui.theme.Spacing
import com.devcompanion.debug.WebViewDebuggerHolder
import com.devcompanion.debug.NetworkEntry
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    .withZone(ZoneId.systemDefault())

private fun formatTime(ts: Long): String = timeFormatter.format(Instant.ofEpochMilli(ts))

/** Color for HTTP status code — matches browser DevTools convention. */
private fun statusColor(code: Int): Color = when {
    code < 300 -> Color(0xFF4CAF50)   // 2xx green
    code < 400 -> Color(0xFFFFA726)   // 3xx amber
    code < 500 -> Color(0xFFEF5350)   // 4xx red
    else -> Color(0xFFE53935)         // 5xx bright red
}

@Composable
private fun methodColor(method: String): Color = when (method.uppercase()) {
    "GET" -> Color(0xFF8BE9FD)      // cyan
    "POST" -> Color(0xFF50FA7B)     // green
    "PUT", "PATCH" -> Color(0xFFBD93F9) // purple
    "DELETE" -> Color(0xFFFF5555)   // red
    "OPTIONS", "HEAD" -> Color(0xFFFFB86C) // orange
    else -> MaterialTheme.colorScheme.onSurface
}

/** Table column widths (fractions of available width). */
private object Col {
    const val METHOD = 0.10f
    const val STATUS = 0.08f
    const val DURATION = 0.08f
    const val TIME = 0.10f
    const val URL = 0.64f  // remainder
}

/** Minimum column widths for horizontal scroll mode (dp). */
private object ColMin {
    const val METHOD = 56
    const val STATUS = 48
    const val TIME = 72
    const val DURATION = 48
    const val URL = 240
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkTab() {
    val debugger = WebViewDebuggerHolder.current
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedEntry by remember { mutableStateOf<NetworkEntry?>(null) }
    var showDetail by remember { mutableStateOf(false) }
    var expandedEntryId by remember { mutableStateOf<String?>(null) }
    var wideTableMode by remember { mutableStateOf(false) }

    val networkMap by (debugger?.networkEntries
        ?: MutableStateFlow<Map<String, NetworkEntry>>(emptyMap()))
        .collectAsState(initial = emptyMap())

    val entries = remember(networkMap) { networkMap.values.sortedByDescending { it.request.timestamp } }
    val filtered = remember(entries, searchQuery) {
        if (searchQuery.isBlank()) entries
        else entries.filter { entry ->
            entry.request.url.contains(searchQuery, ignoreCase = true) ||
            entry.request.method.contains(searchQuery, ignoreCase = true)
        }
    }

    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("network", text))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }

    // Detail view (full-screen)
    if (showDetail && selectedEntry != null) {
        NetworkDetail(
            entry = selectedEntry!!,
            onBack = { showDetail = false },
            onCopy = { copyToClipboard(it) }
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        // Search + toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Filter URLs…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                textStyle = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.width(Spacing.xs))
            // Wide table toggle
            IconButton(onClick = { wideTableMode = !wideTableMode }) {
                Icon(
                    if (wideTableMode) Icons.Default.List else Icons.Default.TableChart,
                    contentDescription = if (wideTableMode) "Compact view" else "Wide table",
                    tint = if (wideTableMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Summary
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "${filtered.size} requests",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            TextButton(onClick = { debugger?.clearNetwork() }) {
                Text("Clear", style = MaterialTheme.typography.labelSmall)
            }
        }

        HorizontalDivider()

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (debugger == null) "Open browser tab to capture network"
                    else "No network requests",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else if (wideTableMode) {
            // ── Wide table mode (horizontal scroll) ──────────────────
            WideNetworkTable(
                entries = filtered,
                expandedEntryId = expandedEntryId,
                onExpand = { id -> expandedEntryId = if (expandedEntryId == id) null else id },
                onFullDetail = { entry ->
                    selectedEntry = entry
                    showDetail = true
                },
                onCopy = { copyToClipboard(it) }
            )
        } else {
            // ── Compact list mode (expand pattern) ────────────────────
            CompactNetworkList(
                entries = filtered,
                expandedEntryId = expandedEntryId,
                onExpand = { id -> expandedEntryId = if (expandedEntryId == id) null else id },
                onFullDetail = { entry ->
                    selectedEntry = entry
                    showDetail = true
                },
                onCopy = { copyToClipboard(it) }
            )
        }
    }
}

// ── Compact list mode ────────────────────────────────────────────────

@Composable
private fun CompactNetworkList(
    entries: List<NetworkEntry>,
    expandedEntryId: String?,
    onExpand: (String) -> Unit,
    onFullDetail: (NetworkEntry) -> Unit,
    onCopy: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 0.dp)
    ) {
        // Sticky header
        stickyHeader(key = "header") {
            NetworkTableHeader()
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }

        items(items = entries, key = { it.request.requestId }) { entry ->
            val isExpanded = expandedEntryId == entry.request.requestId
            Column {
                NetworkTableRow(
                    entry = entry,
                    onClick = { onExpand(entry.request.requestId) }
                )
                // Expand content — immediate switch (no animation to avoid scroll jumps)
                if (isExpanded) {
                    ExpandContent(
                        entry = entry,
                        onFullDetail = { onFullDetail(entry) },
                        onCopy = onCopy
                    )
                }
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

// ── Wide table mode (horizontal scroll) ──────────────────────────────

@Composable
private fun WideNetworkTable(
    entries: List<NetworkEntry>,
    expandedEntryId: String?,
    onExpand: (String) -> Unit,
    onFullDetail: (NetworkEntry) -> Unit,
    onCopy: (String) -> Unit,
) {
    val horizontalScrollState = rememberScrollState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Shared header (synced scroll state)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(horizontalScrollState)
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Method", modifier = Modifier.width(ColMin.METHOD.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.outline)
            Text("Status", modifier = Modifier.width(ColMin.STATUS.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.outline)
            Text("Time", modifier = Modifier.width(ColMin.TIME.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.outline, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            Text("ms", modifier = Modifier.width(ColMin.DURATION.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.outline, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            Text("URL", modifier = Modifier.width(ColMin.URL.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.outline)
        }

        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 0.dp)
        ) {
            items(items = entries, key = { it.request.requestId }) { entry ->
                val isExpanded = expandedEntryId == entry.request.requestId
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(horizontalScrollState)
                            .clickable { onExpand(entry.request.requestId) }
                            .padding(horizontal = Spacing.md, vertical = Spacing.xxs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val mColor = methodColor(entry.request.method)
                        val sColor = when {
                            entry.failure != null -> MaterialTheme.colorScheme.error
                            entry.response != null -> statusColor(entry.response.statusCode)
                            else -> MaterialTheme.colorScheme.outline
                        }
                        val dur = entry.durationMs

                        Text(entry.request.method, modifier = Modifier.width(ColMin.METHOD.dp), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = mColor, fontWeight = FontWeight.Bold)
                        Text(entry.statusDisplay, modifier = Modifier.width(ColMin.STATUS.dp), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = sColor, fontWeight = FontWeight.Bold)
                        Text(formatTime(entry.request.timestamp), modifier = Modifier.width(ColMin.TIME.dp), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.outline, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        Text(dur?.let { "${it}ms" } ?: "–", modifier = Modifier.width(ColMin.DURATION.dp), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = if (dur != null && dur > 3000) Color(0xFFFFA726) else MaterialTheme.colorScheme.outline, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        Text(entry.request.url, modifier = Modifier.width(ColMin.URL.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (isExpanded) {
                        ExpandContent(
                            entry = entry,
                            onFullDetail = { onFullDetail(entry) },
                            onCopy = onCopy
                        )
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

// ── Shared components ─────────────────────────────────────────────────

/** Table header row — Method | Status | Time | ms | URL */
@Composable
private fun NetworkTableHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Method", modifier = Modifier.weight(Col.METHOD).widthIn(min = ColMin.METHOD.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.outline)
        Text("Status", modifier = Modifier.weight(Col.STATUS).widthIn(min = ColMin.STATUS.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.outline)
        Text("Time", modifier = Modifier.weight(Col.TIME).widthIn(min = ColMin.TIME.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.outline, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        Text("ms", modifier = Modifier.weight(Col.DURATION).widthIn(min = ColMin.DURATION.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.outline, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        Text("URL", modifier = Modifier.weight(Col.URL).widthIn(min = ColMin.URL.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.outline)
    }
}

/** Table data row — aligned columns matching the header. */
@Composable
private fun NetworkTableRow(
    entry: NetworkEntry,
    onClick: () -> Unit,
) {
    val mColor = methodColor(entry.request.method)
    val sColor = when {
        entry.failure != null -> MaterialTheme.colorScheme.error
        entry.response != null -> statusColor(entry.response.statusCode)
        else -> MaterialTheme.colorScheme.outline
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Method
        Text(
            entry.request.method,
            modifier = Modifier.weight(Col.METHOD).widthIn(min = ColMin.METHOD.dp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = mColor,
            fontWeight = FontWeight.Bold,
        )

        // Status code
        Text(
            entry.statusDisplay,
            modifier = Modifier.weight(Col.STATUS).widthIn(min = ColMin.STATUS.dp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = sColor,
            fontWeight = FontWeight.Bold,
        )

        // Timestamp
        Text(
            formatTime(entry.request.timestamp),
            modifier = Modifier.weight(Col.TIME).widthIn(min = ColMin.TIME.dp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )

        // Duration
        val dur = entry.durationMs
        Text(
            dur?.let { "${it}ms" } ?: "–",
            modifier = Modifier.weight(Col.DURATION).widthIn(min = ColMin.DURATION.dp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = if (dur != null && dur > 3000) Color(0xFFFFA726) else MaterialTheme.colorScheme.outline,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )

        // URL (truncated)
        Text(
            entry.request.url,
            modifier = Modifier.weight(Col.URL).widthIn(min = ColMin.URL.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Expand content — shows summary + "View full details" button. */
@Composable
private fun ExpandContent(
    entry: NetworkEntry,
    onFullDetail: () -> Unit,
    onCopy: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
            .padding(start = 32.dp)  // indent to show hierarchy
    ) {
        // General info
        Text("General", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(Spacing.xxs))
        DetailRow("URL", entry.request.url, onCopy)
        DetailRow("Method", entry.request.method, onCopy)
        entry.response?.let { resp ->
            DetailRow("Status", "${resp.statusCode}", onCopy)
        }
        entry.durationMs?.let { DetailRow("Duration", "${it}ms", onCopy) }
        entry.failure?.let { fail ->
            DetailRow("Error", fail.description, onCopy)
        }

        // Headers summary (first 3)
        if (entry.request.headers.isNotEmpty()) {
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text("Request Headers (${entry.request.headers.size})", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            entry.request.headers.entries.take(3).forEach { (k, v) ->
                DetailRow(k, v, onCopy)
            }
            if (entry.request.headers.size > 3) {
                Text("… +${entry.request.headers.size - 3} more", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        entry.response?.let { resp ->
            if (resp.headers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text("Response Headers (${resp.headers.size})", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                resp.headers.entries.take(3).forEach { (k, v) ->
                    DetailRow(k, v, onCopy)
                }
                if (resp.headers.size > 3) {
                    Text("… +${resp.headers.size - 3} more", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.xs))
        // Full detail button
        TextButton(onClick = onFullDetail) {
            Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(Spacing.xxs))
            Text("View full details", style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ── Full detail view ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkDetail(
    entry: NetworkEntry,
    onBack: () -> Unit,
    onCopy: (String) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    entry.request.method + " " + entry.request.url.substringAfterLast("/"),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = {
                    val text = buildString {
                        appendLine("${entry.request.method} ${entry.request.url}")
                        entry.response?.let { appendLine("Status: ${it.statusCode}") }
                        entry.durationMs?.let { appendLine("Duration: ${it}ms") }
                        entry.failure?.let { appendLine("Error: ${it.description}") }
                        if (entry.request.headers.isNotEmpty()) {
                            appendLine()
                            appendLine("Request Headers:")
                            entry.request.headers.forEach { (k, v) -> appendLine("  $k: $v") }
                        }
                        entry.response?.let { resp ->
                            if (resp.headers.isNotEmpty()) {
                                appendLine()
                                appendLine("Response Headers:")
                                resp.headers.forEach { (k, v) -> appendLine("  $k: $v") }
                            }
                        }
                    }
                    onCopy(text)
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                }
            }
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.md)
        ) {
            item {
                Text("General", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(Spacing.xs))
                DetailRow("Request URL", entry.request.url, onCopy)
                DetailRow("Method", entry.request.method, onCopy)
                entry.response?.let { resp ->
                    DetailRow("Status Code", resp.statusCode.toString(), onCopy)
                    entry.durationMs?.let { DetailRow("Duration", "${it}ms", onCopy) }
                }
                entry.failure?.let { fail ->
                    DetailRow("Error", fail.description, onCopy)
                }
                Spacer(modifier = Modifier.height(Spacing.md))
            }

            if (entry.request.headers.isNotEmpty()) {
                item {
                    Text("Request Headers", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(Spacing.xs))
                }
                items(items = entry.request.headers.entries.toList(), key = { it.key }) { (k, v) ->
                    DetailRow(k, v, onCopy)
                }
                item { Spacer(modifier = Modifier.height(Spacing.md)) }
            }

            // Response headers
            entry.response?.let { resp ->
                if (resp.headers.isNotEmpty()) {
                    item {
                        Text("Response Headers", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.height(Spacing.xs))
                    }
                    items(items = resp.headers.entries.toList(), key = { "resp-${it.key}" }) { (k, v) ->
                        DetailRow(k, v, onCopy)
                    }
                    item { Spacer(modifier = Modifier.height(Spacing.md)) }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(key: String, value: String, onCopy: (String) -> Unit = {}) {
    Row(
        modifier = Modifier
            .padding(vertical = Spacing.xxs)
            .clickable { onCopy("$key: $value") },
    ) {
        Text(
            key,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(0.35f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(0.65f)
        )
    }
}