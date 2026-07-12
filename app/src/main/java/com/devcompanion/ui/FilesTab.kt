package com.devcompanion.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devcompanion.ui.theme.Spacing
import java.io.File
import java.text.DecimalFormat

private val fileSizeFormat = DecimalFormat("#,###")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesTab() {
    val context = LocalContext.current
    val filesDir = remember { context.filesDir }
    var currentDir by remember { mutableStateOf(filesDir) }
    var searchText by remember { mutableStateOf("") }
    var viewingFile by remember { mutableStateOf<File?>(null) }
    var fileContent by remember { mutableStateOf<String?>(null) }
    var fileLoadError by remember { mutableStateOf<String?>(null) }

    // Refresh directory listing
    val dirFiles = remember(currentDir, searchText) {
        val files = currentDir.listFiles()?.toList()?.sortedWith(
            compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }
        ) ?: emptyList()
        if (searchText.isBlank()) files
        else files.filter { it.name.contains(searchText, ignoreCase = true) }
    }

    // Breadcrumb path relative to filesDir
    val relativePath = remember(currentDir) {
        currentDir.absolutePath.removePrefix(filesDir.absolutePath).removePrefix("/")
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── File viewer overlay ──────────────────────────────────────
        if (viewingFile != null && fileContent != null) {
            FileViewer(
                file = viewingFile!!,
                content = fileContent!!,
                error = fileLoadError,
                onCopy = { text ->
                    copyToClipboard(context, text)
                },
                onBack = {
                    viewingFile = null
                    fileContent = null
                    fileLoadError = null
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
            placeholder = { Text("Search files…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            textStyle = MaterialTheme.typography.bodySmall
        )

        // ── Breadcrumb + refresh ─────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (relativePath.isBlank()) "Internal Storage" else relativePath,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(Spacing.sm))
            Text(
                "${dirFiles.size} items",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            IconButton(onClick = {
                // Force recompose by flipping currentDir
                currentDir = File(currentDir.absolutePath)
            }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(18.dp))
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.xxs))

        // ── Parent directory entry ────────────────────────────────────
        if (currentDir.absolutePath != filesDir.absolutePath) {
            FileRow(
                name = "..",
                isDir = true,
                size = null,
                onClick = {
                    currentDir = currentDir.parentFile ?: filesDir
                },
                onLongClick = {}
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.xxs))
        }

        // ── File list ─────────────────────────────────────────────────
        if (dirFiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (searchText.isNotBlank()) "No files matching \"$searchText\""
                    else "No files in this directory",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(items = dirFiles, key = { it.absolutePath }) { file ->
                    FileRow(
                        name = file.name,
                        isDir = file.isDirectory,
                        size = if (file.isFile) file.length() else null,
                        onClick = {
                            if (file.isDirectory) {
                                currentDir = file
                            } else {
                                // Read file content
                                if (file.length() > 50 * 1024) {
                                    fileLoadError = "File too large (${fileSizeFormat.format(file.length())} bytes). Max 50KB."
                                    fileContent = null
                                } else {
                                    fileLoadError = null
                                    fileContent = try {
                                        file.readText()
                                    } catch (e: Exception) {
                                        fileLoadError = "Failed to read: ${e.message}"
                                        null
                                    }
                                }
                                viewingFile = file
                            }
                        },
                        onLongClick = {
                            copyToClipboard(context, file.absolutePath)
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
                "Tap file to view • Long-press to copy path",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    name: String,
    isDir: Boolean,
    size: Long?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (isDir) Icons.Default.Folder else Icons.Default.Description,
            contentDescription = if (isDir) "Directory" else "File",
            tint = if (isDir) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (size != null) {
            Text(
                formatFileSize(size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun FileViewer(
    file: File,
    content: String?,
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
                    file.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatFileSize(file.length()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            IconButton(onClick = { if (content != null) onCopy(content) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy all")
            }
        }

        HorizontalDivider()

        // ── Content ───────────────────────────────────────────────────
        if (error != null) {
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
            // Truncate display to ~20KB for performance
            val displayContent = remember(content) {
                if (content.length > 20_000) {
                    content.take(20_000) + "\n\n--- TRUNCATED (showing first 20KB of ${formatFileSize(file.length())}) ---"
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
        }
    }
}

// ── SelectionContainer (simplified — always allow text selection) ─────

@Composable
private fun SelectionContainer(content: @Composable () -> Unit) {
    // Use basic text selection — Compose Foundation's SelectionContainer
    androidx.compose.foundation.text.selection.SelectionContainer {
        content()
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("file", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}