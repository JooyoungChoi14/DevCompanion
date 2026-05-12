package com.devcompanion.debug

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for ConsoleTab — survives bottom sheet dismiss / tab switches.
 * Owns the filtered view of WebViewDebugger's unified console timeline.
 */
class ConsoleViewModel(application: Application) : AndroidViewModel(application) {

    private val debugger get() = WebViewDebuggerHolder.current

    // ── UI state ────────────────────────────────────────────────────────
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _filterLevel = MutableStateFlow<ConsoleLevel?>(null) // null = show all
    val filterLevel: StateFlow<ConsoleLevel?> = _filterLevel.asStateFlow()

    val jsInput = MutableStateFlow("")

    private val _expandedItems = MutableStateFlow<Set<Long>>(emptySet())
    val expandedItems: StateFlow<Set<Long>> = _expandedItems.asStateFlow()

    private val _isSelectMode = MutableStateFlow(false)
    val isSelectMode: StateFlow<Boolean> = _isSelectMode.asStateFlow()

    private val _selectedItems = MutableStateFlow<Set<Long>>(emptySet())
    val selectedItems: StateFlow<Set<Long>> = _selectedItems.asStateFlow()

    // ── Inspector mode ─────────────────────────────────────────────────
    private val _isInspectorMode = MutableStateFlow(false)
    val isInspectorMode: StateFlow<Boolean> = _isInspectorMode.asStateFlow()

    private val _inspectorTarget = MutableStateFlow<InspectorTarget?>(null)
    val inspectorTarget: StateFlow<InspectorTarget?> = _inspectorTarget.asStateFlow()

    fun toggleInspectorMode() {
        _isInspectorMode.update { !it }
        if (!_isInspectorMode.value) _inspectorTarget.value = null
    }

    fun setInspectorTarget(target: InspectorTarget?) {
        _inspectorTarget.value = target
    }

    fun enableInspector() {
        debugger?.enableInspector()
    }

    fun disableInspector() {
        debugger?.disableInspector()
    }

    fun toggleSelectMode() {
        _isSelectMode.update { !it }
        if (!_isSelectMode.value) _selectedItems.value = emptySet()
    }

    fun selectAll(itemIds: List<Long>) {
        _selectedItems.value = itemIds.toSet()
    }

    fun deselectAll() {
        _selectedItems.value = emptySet()
    }

    // ── Derived filtered items ──────────────────────────────────────────
    val filteredItems: StateFlow<List<ConsoleItem>> = combine(
        debugger?.consoleItems ?: MutableStateFlow(emptyList()),
        _filterLevel,
        _searchQuery,
    ) { items, level, query ->
        items.filter { item ->
            (level == null || (item is ConsoleItem.Log && item.level == level)) &&
            (query.isBlank() || when (item) {
                is ConsoleItem.Input -> item.expression.contains(query, ignoreCase = true)
                is ConsoleItem.Log -> item.text.contains(query, ignoreCase = true)
                is ConsoleItem.Result -> item.expression.contains(query, ignoreCase = true) ||
                    item.evalResult.value.contains(query, ignoreCase = true)
            })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Actions ─────────────────────────────────────────────────────────

    fun setSearchQuery(query: String) { _searchQuery.value = query }

    fun setFilterLevel(level: ConsoleLevel?) { _filterLevel.value = level }

    fun evaluateJs(expression: String) {
        if (expression.isBlank()) return
        debugger?.evaluateJs(expression)
    }

    fun toggleExpanded(uid: Long) {
        _expandedItems.update { set ->
            if (uid in set) set - uid else set + uid
        }
    }

    fun toggleSelected(uid: Long) {
        _selectedItems.update { set ->
            if (uid in set) set - uid else set + uid
        }
    }

    fun clearSelection() {
        _selectedItems.value = emptySet()
        _isSelectMode.value = false
    }

    fun clearConsole() {
        debugger?.clearConsole()
        clearSelection()
    }

    /**
     * Build clipboard text from selected items.
     * For Input+Result pairs, format as: "▶ expression\n← result"
     * For standalone items, format individually.
     */
    fun getSelectedClipboardText(items: List<ConsoleItem>): String {
        val selected = _selectedItems.value
        if (selected.isEmpty()) return ""

        val selectedItems = items.filter { it.uid in selected }
        val resultByInputUid = selectedItems
            .filterIsInstance<ConsoleItem.Result>()
            .associateBy { it.inputUid }

        val lines = mutableListOf<String>()
        val processedInputUids = mutableSetOf<Long>()

        for (item in selectedItems.sortedBy { it.timestamp }) {
            when (item) {
                is ConsoleItem.Input -> {
                    val result = resultByInputUid[item.uid]
                    if (result != null) {
                        lines.add("▶ ${item.expression}")
                        lines.add("← ${result.evalResult.value}")
                        processedInputUids.add(item.uid)
                    } else {
                        lines.add("▶ ${item.expression}")
                    }
                }
                is ConsoleItem.Result -> {
                    // Skip if we already printed this with its input
                    if (item.inputUid !in processedInputUids) {
                        lines.add("▶ ${item.expression}")
                        lines.add("← ${item.evalResult.value}")
                    }
                }
                is ConsoleItem.Log -> {
                    lines.add(item.text)
                }
            }
        }
        return lines.joinToString("\n")
    }

    override fun onCleared() {
        super.onCleared()
        // Don't clear debugger state — it persists beyond the ViewModel
    }
}