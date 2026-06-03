package com.devcompanion.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkTab() {
    val debugger = WebViewDebuggerHolder.current
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedEntry by remember { mutableStateOf<NetworkEntry?>(null) }
    var showDetail by remember { mutableStateOf(false) }

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

    if (showDetail && selectedEntry != null) {
        NetworkDetail(
            entry = selectedEntry!!,
            onBack = { showDetail = false },
            onCopy = { copyToClipboard(it) }
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        // Search
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
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
        } else {
            // Table header
            NetworkTableHeader()
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 0.dp)
            ) {
                items(items = filtered, key = { it.request.requestId }) { entry ->
                    NetworkTableRow(
                        entry = entry,
                        onClick = {
                            selectedEntry = entry
                            showDetail = true
                        }
                    )
                }
            }
        }
    }
}

/** Table header row — Method | Status | Duration | Time | URL */
@Composable
private fun NetworkTableHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Method", modifier = Modifier.weight(Col.METHOD), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.outline)
        Text("Status", modifier = Modifier.weight(Col.STATUS), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.outline)
        Text("Time", modifier = Modifier.weight(Col.TIME), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.outline, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        Text("ms", modifier = Modifier.weight(Col.DURATION), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.outline, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        Text("URL", modifier = Modifier.weight(Col.URL), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.outline)
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
            modifier = Modifier.weight(Col.METHOD),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = mColor,
            fontWeight = FontWeight.Bold,
        )

        // Status code
        Text(
            entry.statusDisplay,
            modifier = Modifier.weight(Col.STATUS),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = sColor,
            fontWeight = FontWeight.Bold,
        )

        // Timestamp
        Text(
            formatTime(entry.request.timestamp),
            modifier = Modifier.weight(Col.TIME),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )

        // Duration
        val dur = entry.durationMs
        Text(
            dur?.let { "${it}ms" } ?: "–",
            modifier = Modifier.weight(Col.DURATION),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = if (dur != null && dur > 3000) Color(0xFFFFA726) else MaterialTheme.colorScheme.outline,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )

        // URL (truncated)
        Text(
            entry.request.url,
            modifier = Modifier.weight(Col.URL),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkDetail(
    entry: NetworkEntry,
    onBack: () -> Unit,
    onCopy: (String) -> Unit = {},
) {
    val context = LocalContext.current

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