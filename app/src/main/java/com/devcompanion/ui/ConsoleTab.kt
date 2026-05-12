package com.devcompanion.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.devcompanion.debug.*
import com.devcompanion.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SS")
    .withZone(ZoneId.systemDefault())

private fun formatTime(ts: Long): String = timeFormatter.format(Instant.ofEpochMilli(ts))

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConsoleTab(
    vm: ConsoleViewModel = viewModel(),
) {
    val debugger = WebViewDebuggerHolder.current
    val context = LocalContext.current
    val items by vm.filteredItems.collectAsState()
    val searchQuery by vm.searchQuery.collectAsState()
    var searchField by remember { mutableStateOf(TextFieldValue("")) }
    var searchHadFocus by remember { mutableStateOf(false) }
    LaunchedEffect(searchQuery) {
        if (searchQuery != searchField.text) {
            searchField = searchField.copy(text = searchQuery, selection = TextRange(searchQuery.length))
        }
    }
    val filterLevel by vm.filterLevel.collectAsState()
    val jsInput by vm.jsInput.collectAsState()
    var jsField by remember { mutableStateOf(TextFieldValue("")) }
    var jsHadFocus by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val expandedItems by vm.expandedItems.collectAsState()
    val isSelectMode by vm.isSelectMode.collectAsState()
    val selectedItems by vm.selectedItems.collectAsState()
    val isInspectorMode by vm.isInspectorMode.collectAsState()
    val inspectorTarget by vm.inspectorTarget.collectAsState()
    // Bridge debugger inspector target to VM
    LaunchedEffect(Unit) {
        debugger?.inspectorTarget?.collect { target ->
            vm.setInspectorTarget(target)
        }
    }
    val listState = rememberLazyListState()

    // All items from debugger (for clipboard pair resolution)
    val allItems by (debugger?.consoleItems
        ?: MutableStateFlow<List<ConsoleItem>>(emptyList()))
        .collectAsState(initial = emptyList())

    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("console", text))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }

    // Auto-scroll on new items
    LaunchedEffect(items.size) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.size - 1)
    }

    // Autocomplete
    val suggestions = remember(jsInput) {
        if (jsInput.isBlank()) emptyList()
        else JsAutocomplete.getSuggestions(jsInput).take(6)
    }
    LaunchedEffect(jsInput) {
        if (jsInput != jsField.text) {
            jsField = jsField.copy(text = jsInput, selection = TextRange(jsInput.length))
        }
    }

    Column(modifier = Modifier.imePadding()) {
        // Search
        OutlinedTextField(
            value = searchField,
            onValueChange = {
                searchField = it
                vm.setSearchQuery(it.text)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                .onFocusChanged { focusState ->
                    if (!searchHadFocus && focusState.isFocused) {
                        val newSel = TextRange(0, searchField.text.length)
                        searchField = searchField.copy(selection = newSel)
                    }
                    searchHadFocus = focusState.isFocused
                },
            singleLine = true,
            placeholder = { Text("Filter logs…") },
            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            textStyle = MaterialTheme.typography.bodySmall
        )

        // Filters + actions
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            FilterChip(selected = filterLevel == null, onClick = { vm.setFilterLevel(null) }, label = { Text("All") },
                border = FilterChipDefaults.filterChipBorder(enabled = true, selected = filterLevel == null,
                    borderColor = MaterialTheme.colorScheme.outline, selectedBorderColor = MaterialTheme.colorScheme.primary),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary))
            FilterChip(selected = filterLevel == ConsoleLevel.Warn,
                onClick = { vm.setFilterLevel(ConsoleLevel.Warn) }, label = { Text("Warn") },
                border = FilterChipDefaults.filterChipBorder(enabled = true, selected = filterLevel == ConsoleLevel.Warn,
                    borderColor = MaterialTheme.colorScheme.outline, selectedBorderColor = MaterialTheme.colorScheme.primary),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary))
            FilterChip(selected = filterLevel == ConsoleLevel.Error,
                onClick = { vm.setFilterLevel(ConsoleLevel.Error) }, label = { Text("Error") },
                border = FilterChipDefaults.filterChipBorder(enabled = true, selected = filterLevel == ConsoleLevel.Error,
                    borderColor = MaterialTheme.colorScheme.outline, selectedBorderColor = MaterialTheme.colorScheme.primary),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary))
            Spacer(modifier = Modifier.weight(1f))
            // Inspector mode toggle
            IconButton(onClick = {
                val entering = !isInspectorMode
                vm.toggleInspectorMode()
                if (entering) vm.enableInspector() else vm.disableInspector()
            }) {
                Icon(
                    if (isInspectorMode) Icons.Default.Insights else Icons.Default.Highlight,
                    contentDescription = if (isInspectorMode) "Exit inspector" else "Inspector",
                    tint = if (isInspectorMode) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Select mode toggle
            IconButton(onClick = { vm.toggleSelectMode() }) {
                Icon(
                    if (isSelectMode) Icons.Default.CheckCircle else Icons.Default.SelectAll,
                    contentDescription = if (isSelectMode) "Exit select" else "Select",
                    tint = if (isSelectMode) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Selection action bar (shown when in select mode)
        if (isSelectMode) {
            Row(modifier = Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${selectedItems.size} selected",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.weight(1f))
                if (selectedItems.isNotEmpty()) {
                    TextButton(onClick = {
                        copyToClipboard(vm.getSelectedClipboardText(allItems))
                        vm.clearSelection()
                    }) {
                        Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(Spacing.xxs))
                        Text("Pairs", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = {
                        val text = items.filter { it.uid in selectedItems }
                            .joinToString("\n") { itemText(it) }
                        copyToClipboard(text)
                        vm.clearSelection()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(Spacing.xxs))
                        Text("Raw", style = MaterialTheme.typography.labelSmall)
                    }
                }
                TextButton(onClick = { vm.selectAll(items.map { it.uid }) }) { Text("All", style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = { vm.deselectAll() }) { Text("None", style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = { vm.clearConsole() }) { Text("Clear", style = MaterialTheme.typography.labelSmall) }
            }
        } else {
            // Normal mode: just clear button
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
                horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { vm.clearConsole() }) {
                    Text("Clear", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        HorizontalDivider()

        // Inspector mode panel
        if (isInspectorMode) {
            InspectorPanel(
                target = inspectorTarget,
                onCopy = { copyToClipboard(it) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (items.isEmpty()) {
            Box(modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (debugger == null) "Open browser tab to start debugging"
                    else "No console messages",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                items(items = items, key = { it.uid }) { item ->
                    val isSelected = item.uid in selectedItems
                    when (item) {
                        is ConsoleItem.Input -> ConsoleInputRow(
                            item = item,
                            isSelected = isSelected,
                            isSelectMode = isSelectMode,
                            onClick = {
                                if (isSelectMode) vm.toggleSelected(item.uid)
                            },
                            onCopy = { copyToClipboard("▶ ${item.expression}") },
                        )
                        is ConsoleItem.Log -> ConsoleLogRow(
                            item = item,
                            isSelected = isSelected,
                            isSelectMode = isSelectMode,
                            onClick = {
                                if (isSelectMode) vm.toggleSelected(item.uid)
                            },
                            onCopy = { copyToClipboard(item.text) },
                        )
                        is ConsoleItem.Result -> ConsoleResultRow(
                            item = item,
                            isSelected = isSelected,
                            isSelectMode = isSelectMode,
                            onSelect = { vm.toggleSelected(item.uid) },
                            expanded = item.uid in expandedItems,
                            onToggle = { vm.toggleExpanded(item.uid) },
                            onCopy = { copyToClipboard(item.evalResult.value) },
                        )
                    }
                }
            }
        }

        HorizontalDivider()

        // Autocomplete
        if (suggestions.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                .padding(horizontal = Spacing.md, vertical = Spacing.xxs)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    suggestions.forEach { s ->
                        Text(text = s, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                val dot = jsInput.lastIndexOf('.')
                                vm.jsInput.value = if (dot >= 0) jsInput.substring(0, dot + 1) + s else s
                            }.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                MaterialTheme.shapes.extraSmall).padding(horizontal = Spacing.xs, vertical = Spacing.xxs))
                    }
                }
            }
        }

        // JS input
        Row(modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically) {
            Text(">", fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = Spacing.xs))
            OutlinedTextField(
                value = jsField,
                onValueChange = {
                    jsField = it
                    vm.jsInput.value = it.text
                },
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focusState ->
                        android.util.Log.d("SelectAllDebug", "[JS] onFocusChanged: hadFocus=$jsHadFocus, isFocused=${focusState.isFocused}, textLen=${jsField.text.length}, selBefore=${jsField.selection}")
                        if (!jsHadFocus && focusState.isFocused) {
                            val newSel = TextRange(0, jsField.text.length)
                            jsField = jsField.copy(selection = newSel)
                            android.util.Log.d("SelectAllDebug", "[JS] Applied select-all: newSel=$newSel, actualSel=${jsField.selection}")
                        }
                        jsHadFocus = focusState.isFocused
                        if (focusState.isFocused) {
                            keyboardController?.show()
                        }
                    },
                singleLine = true,
                placeholder = { Text("Evaluate JS…", fontFamily = FontFamily.Monospace) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(
                    onGo = {
                        if (jsField.text.isNotBlank()) {
                            vm.evaluateJs(jsField.text)
                            jsField = TextFieldValue("")
                            vm.jsInput.value = ""
                        }
                    }
                ),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

private fun itemText(item: ConsoleItem): String = when (item) {
    is ConsoleItem.Input -> "▶ ${item.expression}"
    is ConsoleItem.Log -> item.text
    is ConsoleItem.Result -> "← ${item.evalResult.value}"
}

// ── Row composables ─────────────────────────────────────────────────────

@Composable
private fun ConsoleInputRow(
    item: ConsoleItem.Input,
    isSelected: Boolean,
    isSelectMode: Boolean,
    onClick: () -> Unit,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (isSelected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) else Modifier)
            .clickable(enabled = isSelectMode, onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelectMode) {
            Checkbox(checked = isSelected, onCheckedChange = { onClick() },
                modifier = Modifier.size(20.dp),
                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary))
            Spacer(modifier = Modifier.width(Spacing.xs))
        }
        Text("▶", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.width(Spacing.xs))
        Text(formatTime(item.timestamp), style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.width(Spacing.xs))
        Text(item.expression, style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (!isSelectMode) {
            Text("📋", style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.clickable(onClick = onCopy).padding(start = Spacing.xs))
        }
    }
}

@Composable
private fun ConsoleLogRow(
    item: ConsoleItem.Log,
    isSelected: Boolean,
    isSelectMode: Boolean,
    onClick: () -> Unit,
    onCopy: () -> Unit,
) {
    val (icon, iconColor) = when (item.level) {
        ConsoleLevel.Warn -> Icons.Default.Warning to Color(0xFFFFA726)
        ConsoleLevel.Error -> Icons.Default.Error to Color(0xFFEF5350)
        else -> Icons.Default.Info to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (isSelected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) else Modifier)
            .clickable(enabled = isSelectMode, onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelectMode) {
            Checkbox(checked = isSelected, onCheckedChange = { onClick() },
                modifier = Modifier.size(20.dp),
                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary))
            Spacer(modifier = Modifier.width(Spacing.xs))
        }
        Icon(icon, contentDescription = item.level.name, tint = iconColor, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(Spacing.xs))
        Text(formatTime(item.timestamp), style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.width(Spacing.xs))
        Text(item.text, style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace, maxLines = 5, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ConsoleResultRow(
    item: ConsoleItem.Result,
    isSelected: Boolean,
    isSelectMode: Boolean,
    onSelect: () -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
) {
    val e = item.evalResult
    val canExpand = e.isExpandable

    Row(
        modifier = Modifier.fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else if (e.success) MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
                else Color(0xFFEF5350).copy(alpha = 0.08f))
            .then(if (isSelectMode) Modifier.clickable(onClick = onSelect) else Modifier)
            .then(if (!isSelectMode && canExpand) Modifier.clickable(onClick = onToggle) else Modifier)
            .then(if (!isSelectMode && !canExpand) Modifier.clickable(onClick = onCopy) else Modifier)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelectMode) {
            Checkbox(checked = isSelected, onCheckedChange = { onSelect() },
                modifier = Modifier.size(20.dp),
                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary))
            Spacer(modifier = Modifier.width(Spacing.xs))
        }
        if (canExpand && !isSelectMode) {
            Icon(Icons.Default.ChevronRight, contentDescription = "Expand",
                tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(Spacing.xs))
        }
        Text("←", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
            color = if (e.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(end = Spacing.xs))
        Column(modifier = Modifier.weight(1f)) {
            Surface(color = typeColor(e.type).copy(alpha = 0.15f),
                shape = MaterialTheme.shapes.extraSmall) {
                Text(e.type, style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace, color = typeColor(e.type),
                    modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xxs))
            }
            Spacer(modifier = Modifier.height(Spacing.xxs))
            Text(
                when { expanded -> e.value; canExpand -> summariseObject(e.value) + " ▶"; else -> e.value },
                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                color = if (e.success) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
            )
        }
    }
}

private fun summariseObject(value: String): String {
    val t = value.trim()
    return when {
        t.startsWith("{") -> {
            val keys = t.removePrefix("{").removeSuffix("}").split(",").filter { it.contains(":") }
                .map { it.substringBefore(":").trim().removeSurrounding("\"") }
            if (keys.size <= 3) "{${keys.joinToString(", ")}}" else "{${keys.take(3).joinToString(", ")}, …} (${keys.size} keys)"
        }
        t.startsWith("[") -> {
            val items = t.removePrefix("[").removeSuffix("]").split(",")
            if (items.size <= 3) t.take(60) else "[${items.size} items]"
        }
        else -> t.take(60)
    }
}

@Composable
private fun typeColor(type: String): Color = when (type) {
    "object" -> Color(0xFF42A5F5); "function" -> Color(0xFFAB47BC)
    "string" -> Color(0xFF66BB6A); "number" -> Color(0xFFFFA726)
    "boolean" -> Color(0xFF26A69A); "error" -> Color(0xFFEF5350)
    "undefined", "null" -> MaterialTheme.colorScheme.outline
    else -> MaterialTheme.colorScheme.onSurface
}

// ── Inspector panel ───────────────────────────────────────────────────

@Composable
private fun InspectorPanel(
    target: InspectorTarget?,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        if (target == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.TouchApp,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(Spacing.xs))
                Text(
                    "Tap element in WebView",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // Tag name header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = Color(0xFF4CAF50).copy(alpha = 0.15f),
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        target.tagName,
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF4CAF50),
                        modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xxs)
                    )
                }
                Spacer(modifier = Modifier.width(Spacing.sm))
                target.id?.let {
                    Surface(
                        color = Color(0xFF42A5F5).copy(alpha = 0.15f),
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            "#$it",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF42A5F5),
                            modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xxs)
                        )
                    }
                }
                target.className?.let {
                    Text(
                        ".${it.split(" ").first()}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.xxs))

            // XPath
            InspectorRow(label = "XPath", value = target.xpath, onCopy = onCopy)
            // CSS Selector
            InspectorRow(label = "Selector", value = target.cssSelector, onCopy = onCopy)
            // Text content preview
            target.textContent?.let { text ->
                InspectorRow(
                    label = "Text",
                    value = text.take(100) + if (text.length > 100) "…" else "",
                    onCopy = onCopy
                )
            }
            // Attributes
            if (target.attributes.isNotEmpty()) {
                val attrText = target.attributes.entries
                    .filter { it.key != "id" && it.key != "class" }
                    .take(5)
                    .joinToString(" ") { "${it.key}=\"${it.value.take(30)}\"" }
                if (attrText.isNotBlank()) {
                    InspectorRow(label = "Attrs", value = attrText, onCopy = onCopy)
                }
            }
        }
    }
}

@Composable
private fun InspectorRow(
    label: String,
    value: String,
    onCopy: (String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(56.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = { onCopy(value) },
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = "Copy $label",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
