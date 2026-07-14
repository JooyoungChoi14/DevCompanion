package com.devcompanion.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devcompanion.engine.BrowserEngine
import com.devcompanion.engine.PageResource
import com.devcompanion.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Resource type filter options. */
private enum class ResourceFilter(val label: String, val types: Set<String>) {
    ALL("All", emptySet()),
    DOCUMENT("Doc", setOf("document")),
    SCRIPT("JS", setOf("script")),
    STYLESHEET("CSS", setOf("stylesheet")),
    IMAGE("Img", setOf("image")),
    FONT("Font", setOf("font")),
    XHR("XHR", setOf("xhr")),
    OTHER("Other", setOf("other"))
}

/** Maps a resource type to an appropriate Material icon. */
private fun typeIcon(type: String) = when (type) {
    "document" -> Icons.Default.Description
    "script" -> Icons.Default.Code
    "stylesheet" -> Icons.Default.Palette
    "image" -> Icons.Default.Image
    "font" -> Icons.Default.TextFormat
    "xhr" -> Icons.Default.Sync
    else -> Icons.Default.InsertDriveFile
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesTab(
    engine: BrowserEngine? = null,
    currentUrl: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var resources by remember { mutableStateOf<List<PageResource>>(emptyList()) }
    var selectedFilter by remember { mutableStateOf(ResourceFilter.ALL) }
    var searchText by remember { mutableStateOf("") }
    var viewingResource by remember { mutableStateOf<PageResource?>(null) }
    var resourceContent by remember { mutableStateOf<String?>(null) }
    var contentLoading by remember { mutableStateOf(false) }
    var contentError by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    // Collect resources from the engine
    suspend fun collectResources() {
        isLoading = true
        try {
            val result = engine?.collectPageResources() ?: emptyList()
            android.util.Log.d("SourcesTab", "Collected ${result.size} resources (url=$currentUrl)")
            resources = result
        } catch (e: Exception) {
            android.util.Log.e("SourcesTab", "Failed to collect resources", e)
            resources = emptyList()
        } finally {
            isLoading = false
        }
    }

    // Reload resources when engine or page URL changes (skip about:blank)
    LaunchedEffect(engine, currentUrl) {
        if (currentUrl != null && currentUrl != "about:blank") {
            collectResources()
        }
    }

    // Filter and search resources
    val filteredResources = remember(resources, selectedFilter, searchText) {
        var filtered = if (selectedFilter == ResourceFilter.ALL) resources
        else resources.filter { selectedFilter.types.contains(it.type) }

        if (searchText.isNotBlank()) {
            filtered = filtered.filter { it.url.contains(searchText, ignoreCase = true) }
        }
        filtered
    }

    val hasEngine = engine != null

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Source viewer overlay ──────────────────────────────────────
        if (viewingResource != null) {
            SourceViewer(
                resource = viewingResource!!,
                content = resourceContent,
                isLoading = contentLoading,
                error = contentError,
                onCopy = { text ->
                    copyToClipboard(context, text)
                },
                onBack = {
                    viewingResource = null
                    resourceContent = null
                    contentError = null
                    contentLoading = false
                }
            )
            return@Column
        }

        // ── Search bar ───────────────────────────────────────────────
        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            singleLine = true,
            placeholder = { Text("Search resources…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                }
            },
            textStyle = MaterialTheme.typography.bodySmall
        )

        // ── Type filter chips ─────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            ResourceFilter.entries.forEach { filter ->
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { selectedFilter = filter },
                    label = { Text(filter.label, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(28.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.xs))

        // ── Resource count + refresh ──────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${filteredResources.size} resources",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = {
                scope.launch { collectResources() }
            }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(18.dp))
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.xxs))

        // ── Resource list ─────────────────────────────────────────────
        if (filteredResources.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (!hasEngine) "Sources not available\nNo browser engine connected"
                    else if (resources.isEmpty()) "No resources loaded\nNavigate to a page to see its resources"
                    else "No resources matching \"$searchText\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(items = filteredResources, key = { it.url }) { resource ->
                    ResourceRow(
                        resource = resource,
                        onClick = {
                            viewingResource = resource
                            contentLoading = true
                            contentError = null
                            resourceContent = null
                            // Fetch content asynchronously
                            scope.launch {
                                try {
                                    val content = engine?.fetchResourceContent(resource.url)
                                    if (isTextResource(resource)) {
                                        resourceContent = content
                                        contentLoading = false
                                    } else {
                                        contentLoading = false
                                    }
                                } catch (e: Exception) {
                                    contentError = "Failed to load: ${e.message}"
                                    contentLoading = false
                                }
                            }
                        }
                    )
                }
            }
        }

        // ── Bottom info bar ───────────────────────────────────────────
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Tap resource to view source",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

private fun isTextResource(resource: PageResource): Boolean {
    val mimeType = resource.mimeType
    val url = resource.url.lowercase()
    // Check MIME type
    if (mimeType != null) {
        val mt = mimeType.lowercase()
        if (mt.startsWith("text/") || mt.contains("javascript") || mt.contains("json") ||
            mt.contains("xml") || mt.contains("html") || mt.contains("css")) {
            return true
        }
        if (mt.startsWith("image/") || mt.contains("font") || mt.contains("audio") ||
            mt.contains("video") || mt.contains("pdf") || mt.contains("zip")) {
            return false
        }
    }
    // Fallback: check URL extension
    val path = url.split("?")[0].split("#")[0]
    return when (path.substringAfterLast('.', "")) {
        "js", "mjs", "css", "html", "htm", "json", "xml", "svg", "txt", "md",
        "yaml", "yml", "toml", "cfg", "ini", "sh", "py", "rb", "ts", "tsx", "jsx",
        "vue", "svelte" -> true
        "png", "jpg", "jpeg", "gif", "webp", "ico", "avif", "bmp", "tiff", "woff",
        "woff2", "ttf", "otf", "eot", "mp3", "mp4", "webm", "pdf", "zip", "gz" -> false
        else -> resource.type in listOf("document", "script", "stylesheet", "xhr")
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResourceRow(
    resource: PageResource,
    onClick: () -> Unit,
) {
    val path = try {
        val uri = java.net.URI(resource.url)
        val pathPart = uri.path ?: resource.url
        if (pathPart.length > 2 && pathPart.startsWith("/")) pathPart.substring(1) else pathPart
    } catch (_: Exception) {
        resource.url
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            typeIcon(resource.type),
            contentDescription = resource.type,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                path,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                resource.type,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Spacer(modifier = Modifier.width(Spacing.sm))
        if (resource.size > 0) {
            Text(
                formatFileSize(resource.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                fontFamily = FontFamily.Monospace
            )
        }
        if (resource.statusCode > 0) {
            Spacer(modifier = Modifier.width(Spacing.xs))
            Text(
                "${resource.statusCode}",
                style = MaterialTheme.typography.labelSmall,
                color = if (resource.statusCode < 400) MaterialTheme.colorScheme.outline
                else MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun SourceViewer(
    resource: PageResource,
    content: String?,
    isLoading: Boolean,
    error: String?,
    onCopy: (String) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // ── Header ───────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    resource.url.substringAfterLast("/"),
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        append(resource.type)
                        if (resource.mimeType != null) append(" • ${resource.mimeType}")
                        if (resource.size > 0) append(" • ${formatFileSize(resource.size)}")
                        if (resource.statusCode > 0) append(" • HTTP ${resource.statusCode}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            if (content != null) {
                IconButton(onClick = { onCopy(content) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy all")
                }
            }
        }

        HorizontalDivider()

        // ── Content ───────────────────────────────────────────────────
        if (isLoading) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        } else if (error != null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(Spacing.md),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else if (content != null) {
            val displayContent = remember(content) {
                if (content.length > 50_000) {
                    content.take(50_000) + "\n\n--- TRUNCATED (showing first 50KB) ---"
                } else {
                    content
                }
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    SelectionContainer {
                        Text(
                            displayContent,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(Spacing.md)
                        )
                    }
                }
            }
        } else if (!isTextResource(resource)) {
            // Binary resource — show metadata only
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(Spacing.md),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        typeIcon(resource.type),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(Spacing.md))
                    Text(
                        "Binary resource",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text("Type: ${resource.type}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    if (resource.mimeType != null) {
                        Text("MIME: ${resource.mimeType}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    if (resource.size > 0) {
                        Text("Size: ${formatFileSize(resource.size)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    if (resource.statusCode > 0) {
                        Text("Status: HTTP ${resource.statusCode}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        } else {
            // Text resource with no content (fetch failed)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(Spacing.md),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Could not load resource content",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

// ── SelectionContainer (text selection support) ─────────────────────────────

@Composable
private fun SelectionContainer(content: @Composable () -> Unit) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        content()
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────

private fun formatFileSize(bytes: Long): String = when {
    bytes < 0L -> "—"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("source", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}