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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.devcompanion.ui.theme.Spacing
import com.devcompanion.ui.theme.Sizing
import com.devcompanion.debug.WebViewDebuggerHolder
import com.devcompanion.debug.NetworkEntry
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    .withZone(ZoneId.systemDefault())

private fun formatTime(ts: Long): String = timeFormatter.format(Instant.ofEpochMilli(ts))

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
            onBack = { showDetail = false }
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(items = filtered, key = { it.request.requestId }) { entry ->
                    NetworkEntryRow(
                        entry = entry,
                        onClick = {
                            selectedEntry = entry
                            showDetail = true
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = Spacing.md),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }
}

@Composable
private fun NetworkEntryRow(
    entry: NetworkEntry,
    onClick: () -> Unit,
) {
    val methodColor = when (entry.request.method.uppercase()) {
        "GET" -> Color(0xFF8BE9FD)      // cyan
        "POST" -> Color(0xFF50FA7B)     // green
        "PUT", "PATCH" -> Color(0xFFBD93F9) // purple
        "DELETE" -> Color(0xFFFF5555)   // red
        "OPTIONS", "HEAD" -> Color(0xFFFFB86C) // orange
        else -> MaterialTheme.colorScheme.onSurface
    }
    val statusColor = when {
        entry.failure != null -> MaterialTheme.colorScheme.error
        entry.response != null && entry.response.statusCode >= 400 -> Color(0xFFEF5350)
        entry.response != null && entry.response.statusCode >= 300 -> Color(0xFFFFA726)
        entry.response != null -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = methodColor.copy(alpha = 0.15f),
            shape = MaterialTheme.shapes.extraSmall,
            modifier = Modifier.size(width = Sizing.tagWidth, height = Sizing.tagHeight)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    entry.request.method,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = methodColor,
                )
            }
        }

        Spacer(modifier = Modifier.width(Spacing.sm))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.request.url,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    entry.statusDisplay,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = statusColor,
                )
                entry.durationMs?.let { ms ->
                    Text(
                        "${ms}ms",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(Spacing.xs))
        Text(
            formatTime(entry.request.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkDetail(
    entry: NetworkEntry,
    onBack: () -> Unit,
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
                HeaderRow("Request URL", entry.request.url)
                HeaderRow("Method", entry.request.method)
                entry.response?.let { resp ->
                    HeaderRow("Status Code", resp.statusCode.toString())
                    entry.durationMs?.let { HeaderRow("Duration", "${it}ms") }
                }
                entry.failure?.let { fail ->
                    HeaderRow("Error", fail.description)
                }
                Spacer(modifier = Modifier.height(Spacing.md))
            }

            if (entry.request.headers.isNotEmpty()) {
                item {
                    Text("Request Headers", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(Spacing.xs))
                }
                items(items = entry.request.headers.entries.toList(), key = { it.key }) { (k, v) ->
                    HeaderRow(k, v)
                }
                item { Spacer(modifier = Modifier.height(Spacing.md)) }
            }
        }
    }
}

@Composable
private fun HeaderRow(key: String, value: String) {
    Row(modifier = Modifier.padding(vertical = Spacing.xxs)) {
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