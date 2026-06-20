package com.devcompanion.ui

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import com.devcompanion.engine.BrowserEngine
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.devcompanion.AgentService
import com.devcompanion.data.UrlHistoryStore
import com.devcompanion.llm.*
import com.devcompanion.llm.agent.ActionRisk
import com.devcompanion.llm.agent.AgentEvent
import com.devcompanion.llm.agent.AgentLoop
import com.devcompanion.llm.agent.AgentState
import com.devcompanion.llm.agent.ContextCompactor
import com.devcompanion.llm.agent.LlmResponse
import com.devcompanion.llm.agent.PermissionGate
import com.devcompanion.llm.agent.ToolCall
import com.devcompanion.llm.agent.ToolConfirmationDetails
import com.devcompanion.llm.agent.ToolExecutor
import com.devcompanion.llm.agent.WebViewTools
import com.devcompanion.logging.AppHealthMonitor
import com.devcompanion.logging.EventType
import com.devcompanion.logging.SessionLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * ViewModel managing the AI chat screen state.
 *
 * Handles provider selection, message history, streaming responses,
 * and web context capture. Confirmation prompts for context capture
 * are delegated to the UI layer.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
class AiChatViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "AiChatViewModel"
    }

    /** Build system prompt with browser engine URL context and URL history. */
    private fun buildSystemPrompt(mode: String, engine: BrowserEngine? = null): String {
        val url = try { engine?.getUrl() } catch (_: Exception) { null }
        val customPrompt = try {
            LlmSettings.loadCustomPrompt()
        } catch (_: Exception) { null }
        val recentUrls = try {
            val store = UrlHistoryStore(getApplication<Application>())
            store.getUrls().take(10)
        } catch (_: Exception) { emptyList() }
        return SystemPromptBuilder.build(
            mode = mode,
            currentUrl = url,
            recentUrls = recentUrls,
            customInstructions = customPrompt
        )
    }

    // ── LLM provider ──────────────────────────────────────────────────

    private val _provider = MutableStateFlow<LlmProvider?>(null)
    val provider: StateFlow<LlmProvider?> = _provider.asStateFlow()

    // ── Chat messages ─────────────────────────────────────────────────

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // ── Conversation persistence ────────────────────────────────────────

    // M1: Promoted from internal var to StateFlow for UI observability
    private val _conversationId = MutableStateFlow(ChatHistory.newConversationId())
    val conversationId: StateFlow<String> = _conversationId.asStateFlow()
    private var _sourceUrl: String? = null

    fun loadConversation(conversationId: String) {
        // If loading a different conversation while agent loop is running, stop it first
        if (agentLoop != null && _conversationId.value != conversationId) {
            stopAgentLoop()
        }
        _conversationId.value = conversationId
        _messages.value = ChatHistory.load(getApplication<Application>(), conversationId)
        // Restore sourceUrl from saved metadata
        _sourceUrl = ChatHistory.listConversations(getApplication<Application>())
            .find { it.id == conversationId }?.sourceUrl
        // Reset streaming state for the loaded conversation
        _isStreaming.value = false
        _agentState.value = AgentState.Idle
    }

    /** Whether the initial prompt from address bar has been sent (prevents re-send on recomposition). */
    var initialPromptSent by mutableStateOf(false)
        private set

    /** Mark initial prompt as sent. */
    fun markInitialPromptSent() {
        initialPromptSent = true
    }

    /** Reset initial prompt state when starting a new conversation via address bar. */
    fun resetInitialPromptSent() {
        initialPromptSent = false
    }

    /** Start a new conversation. */
    fun newConversation(sourceUrl: String? = null) {
        _conversationId.value = ChatHistory.newConversationId()
        _messages.value = emptyList()
        _currentResponse.value = ""
        _isStreaming.value = false
        _agentState.value = AgentState.Idle
        _lastContext.value = null  // N1: prevent context leak between conversations
        _sourceUrl = sourceUrl
        initialPromptSent = false  // Reset so future initial prompts can fire
    }

    /**
     * Single entry point for initializing a new conversation with a prompt.
     * Replaces the dual LaunchedEffect pattern that had no ordering guarantee.
     * M2: merges newConversation + prompt sending into one atomic ViewModel operation.
     * Internal: only called from AiChatScreen's LaunchedEffect.
     */
    internal fun initializeWithPrompt(prompt: String, engine: BrowserEngine?, isAgentMode: Boolean) {
        newConversation(sourceUrl = _sourceUrl)  // already resets initialPromptSent
        if (prompt.isNotBlank()) {
            markInitialPromptSent()
            if (isAgentMode) {
                sendMessageAgent(prompt, engine)
            } else {
                sendMessage(prompt, engine)
            }
        }
    }

    /** List available conversation IDs. */
    fun listConversations(): List<ConversationMeta> {
        return ChatHistory.listConversations(getApplication<Application>())
    }

    /** List conversations with metadata. */
    fun listConversationMetas(): List<ConversationMeta> {
        return ChatHistory.listConversations(getApplication<Application>())
    }

    /** Delete a conversation. */
    fun deleteConversation(conversationId: String) {
        ChatHistory.delete(getApplication<Application>(), conversationId)
        // If deleting current conversation, start fresh
        if (conversationId == _conversationId.value) {
            newConversation()
        }
    }

    /** Delete multiple conversations. Returns count of deleted. */
    fun deleteMultipleConversations(conversationIds: List<String>): Int {
        val count = ChatHistory.deleteMultiple(getApplication<Application>(), conversationIds)
        if (_conversationId.value in conversationIds) {
            newConversation()
        }
        return count
    }

    /** Build agent metadata for export debugging. */
    private fun buildAgentMeta(): AgentMeta? {
        val mode = if (_agentMode.value) "agent" else "chat"
        val prompt = try { buildSystemPrompt(mode) } catch (_: Exception) { null } ?: return null
        val tools = if (mode == "agent") WebViewTools.ALL.map { ToolMeta(it.name, it.description) }
                    else listOf(ToolMeta("switch_mode", WebViewTools.SWITCH_MODE.description),
                                 ToolMeta("get_current_mode", WebViewTools.GET_CURRENT_MODE.description))
        return AgentMeta(systemPrompt = prompt, mode = mode, currentUrl = null, toolDefinitions = tools)
    }

    /** Export current conversation as JSON string. */
    fun exportCurrentConversation(): String? {
        val meta = buildAgentMeta()
        return ChatHistory.exportToJson(getApplication<Application>(), _conversationId.value, meta)
    }

    /** Export multiple conversations as a JSON array string. */
    fun exportMultipleConversations(conversationIds: List<String>): String {
        val meta = buildAgentMeta()
        return ChatHistory.exportMultipleToJson(getApplication<Application>(), conversationIds, meta)
    }

    /** Export selected messages from the current conversation as JSON string. */
    fun exportSelectedMessages(messageIds: Set<String>): String? {
        val meta = buildAgentMeta()
        return ChatHistory.exportMessagesToJson(
            getApplication<Application>(),
            _conversationId.value,
            messageIds,
            meta
        )
    }

    /** Import a conversation from JSON string. Returns new conversation ID or null. */
    fun importConversation(json: String): String? {
        return ChatHistory.importFromJson(getApplication<Application>(), json)
    }

    init {
        // Auto-save messages whenever they change (debounced)
        // N2: Replaces immediate collect with 500ms debounce to prevent
        // race with saveMessages() and reduce disk I/O on rapid updates.
        viewModelScope.launch {
            _messages
                .debounce(500)
                .collect {
                    if (it.isNotEmpty()) {
                        ChatHistory.save(getApplication<Application>(), _conversationId.value, it, _sourceUrl)
                    }
                }
        }
    }

    // ── Streaming state ───────────────────────────────────────────────

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    // Current streaming response being built token-by-token
    private val _currentResponse = MutableStateFlow("")
    val currentResponse: StateFlow<String> = _currentResponse.asStateFlow()

    // ── Web context ────────────────────────────────────────────────────

    private val _lastContext = MutableStateFlow<WebContextPacket?>(null)
    val lastContext: StateFlow<WebContextPacket?> = _lastContext.asStateFlow()

    // ── Error state ────────────────────────────────────────────────────

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // ── Cumulative token usage ─────────────────────────────────────────

    private val _totalInputTokens = MutableStateFlow(0)
    val totalInputTokens: StateFlow<Int> = _totalInputTokens.asStateFlow()

    private val _totalOutputTokens = MutableStateFlow(0)
    val totalOutputTokens: StateFlow<Int> = _totalOutputTokens.asStateFlow()

    // ── Public API ──────────────────────────────────────────────────────

    init {
        // Load previously saved provider
        try {
            LlmSettings.initialize(application)
            _provider.value = LlmSettings.loadProvider()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load saved provider", e)
            SessionLog.log(com.devcompanion.logging.EventType.SETTINGS_LOAD, mapOf(
                "result" to "exception_in_init",
                "error" to (e.message?.take(80) ?: "unknown")
            ))
        }

        // Auto-restore last conversation on startup
        try {
            val conversations = ChatHistory.listConversations(application)
            val lastConv = conversations.firstOrNull()
            if (lastConv != null) {
                _conversationId.value = lastConv.id
                _messages.value = ChatHistory.load(application, lastConv.id)
                _sourceUrl = lastConv.sourceUrl
                Log.d(TAG, "Restored last conversation: ${lastConv.id} (${lastConv.messageCount} msgs)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore last conversation", e)
        }

    }

    /**
     * Set the active LLM provider and persist it.
     */
    fun setSourceUrl(url: String) {
        _sourceUrl = url
    }

    fun setProvider(provider: LlmProvider, persist: Boolean = true) {
        val oldName = _provider.value?.displayName ?: "None"
        _provider.value = provider
        if (persist) {
            try {
                LlmSettings.saveProvider(provider)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save provider", e)
            }
        }
        if (oldName != provider.displayName) {
            SessionLog.providerChange(oldName, provider.displayName, provider.model)
        }
    }

    /**
     * Capture the current browser engine state as context for the next message.
     * The UI layer should confirm before calling this.
     * @param mode Capture depth (defaults to Standard for rich context).
     */
    suspend fun captureContext(engine: BrowserEngine, mode: CaptureMode = CaptureMode.Standard) {
        try {
            val context = WebContextBuilder.buildContext(engine, mode)
            _lastContext.value = context
            Log.d(TAG, "Context captured: url=${context.url}, mode=${context.captureMode}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture context", e)
            _error.value = "Context capture failed: ${e.message}"
        }
    }

    /**
     * Clear the captured context.
     */
    fun clearContext() {
        _lastContext.value = null
    }

    /**
     * Send a user message and stream the LLM response.
     *
     * Adds the user message immediately, then streams the response
     * token-by-token into [_currentResponse]. On completion or error,
     * the response is finalized into [_messages].
     */
    /**
     * Send a user message and stream the LLM response.
     *
     * If [engine] is provided and the user hasn't manually captured context,
     * automatically captures a Standard-mode snapshot before sending.
     * Manually captured context (via 📷 button) takes priority.
     */
    private var streamingJob: kotlinx.coroutines.Job? = null

    private var _autoCaptureEnabled = MutableStateFlow(true)
    val autoCaptureEnabled: StateFlow<Boolean> = _autoCaptureEnabled.asStateFlow()

    fun setAutoCaptureEnabled(enabled: Boolean) {
        _autoCaptureEnabled.value = enabled
    }

    fun sendMessage(text: String, engine: BrowserEngine? = null) {
        val currentProvider = _provider.value ?: run {
            _error.value = "No LLM provider configured"
            return
        }

        SessionLog.llmRequest(currentProvider.displayName, currentProvider.model, _messages.value.size,
            hasTools = false, systemPromptLength = buildSystemPrompt("chat", engine).length)

        // If no manually captured context and auto-capture is on, capture from browser engine
        val context = _lastContext.value ?: run {
            if (_autoCaptureEnabled.value && engine != null) {
                // Use runBlocking alternatives — we'll launch a coroutine
                // and capture context inline before sending
                null // Will be captured in the streaming coroutine
            } else null
        }

        // Add user message
        val hasContext = context != null || (_autoCaptureEnabled.value && engine != null)
        val userMessage = ChatMessage(
            role = "user",
            content = text,
            hasContext = hasContext
        )
        _messages.value = _messages.value + userMessage

        // Clear error state
        _error.value = null
        _isStreaming.value = true
        SessionLog.uiDataSnapshot(
            messages = _messages.value.size,
            conversations = ChatHistory.listConversations(getApplication<Application>()).size,
            isStreaming = true,
            providerType = currentProvider.providerType,
            model = currentProvider.model
        )
        _currentResponse.value = ""

        // Clear manually captured context after use (one-shot)
        _lastContext.value = null

        val responseBuilder = StringBuilder()

        streamingJob = viewModelScope.launch {
            try {
                // Auto-capture context if needed
                var capturedContext = context ?: if (_autoCaptureEnabled.value && engine != null) {
                    try {
                        WebContextBuilder.buildContext(engine, CaptureMode.Standard)
                    } catch (e: Exception) {
                        Log.w(TAG, "Auto-context capture failed, sending without context", e)
                        null
                    }
                } else null

                // Strip screenshot for non-vision models to avoid API errors
                if (capturedContext != null && !currentProvider.supportsVision) {
                    capturedContext = capturedContext.copy(
                        screenshotBase64 = "",
                        screenshotMimeType = ""
                    )
                }

                val repository = LlmRepositoryImpl(currentProvider)
                // Pass conversation history for multi-turn context.
                // The repository trims to MAX_HISTORY_MESSAGES internally.
                // Chat mode also gets mode-aware tools (switch_mode, get_current_mode)
                val modeTools = listOf(
                    com.devcompanion.llm.agent.WebViewTools.SWITCH_MODE,
                    com.devcompanion.llm.agent.WebViewTools.GET_CURRENT_MODE
                )
                val modeExecutor = com.devcompanion.llm.agent.ToolExecutor(
                    onSwitchMode = { mode ->
                        val isAgent = mode == "agent"
                        if (_agentMode.value != isAgent) {
                            _agentMode.value = isAgent
                            LlmSettings.agentModeDefault = isAgent
                        }
                    },
                    getCurrentMode = { if (_agentMode.value) "agent" else "chat" }
                )
                val systemPrompt = buildSystemPrompt("chat", engine)
                var toolCallsToProcess = listOf<com.devcompanion.llm.agent.ToolCall>()
                withTimeout(60_000L) {
                repository.streamWithTools(_messages.value, capturedContext, systemPrompt, modeTools).collect { event ->
                    when (event) {
                        is LlmStreamEvent.Token -> {
                            responseBuilder.append(event.content)
                            _currentResponse.value = responseBuilder.toString()
                        }
                        is LlmStreamEvent.ToolCalls -> {
                            toolCallsToProcess = event.calls
                        }
                        is LlmStreamEvent.Complete -> { /* handled below */ }
                        is LlmStreamEvent.Start -> { /* no-op */ }
                        is LlmStreamEvent.Error -> {
                            responseBuilder.append(event.message)
                            _currentResponse.value = responseBuilder.toString()
                        }
                    }
                }
                }

                // Process any mode-switch tool calls
                if (toolCallsToProcess.isNotEmpty()) {
                    val wv = engine ?: return@launch
                    val toolResults = mutableListOf<String>()
                    for (call in toolCallsToProcess) {
                        val result = modeExecutor.execute(call, wv)
                        toolResults.add("${call.name}: ${result.output}")
                    }
                    // Append tool results as system context
                    val toolResultMsg = ChatMessage(
                        role = "system",
                        content = toolResults.joinToString("\n"),
                        hasContext = false,
                        isToolResult = true
                    )
                    _messages.value = _messages.value + toolResultMsg

                    // Re-stream with tool results so the LLM can produce a text response
                    responseBuilder.clear()
                    _currentResponse.value = ""
                    try {
                        withTimeout(60_000L) {
                        repository.streamWithTools(
                            _messages.value,
                            capturedContext,
                            systemPrompt,
                            modeTools
                        ).collect { event ->
                            when (event) {
                                is LlmStreamEvent.Token -> {
                                    responseBuilder.append(event.content)
                                    _currentResponse.value = responseBuilder.toString()
                                }
                                is LlmStreamEvent.Complete -> { /* handled below */ }
                                is LlmStreamEvent.Start -> { /* no-op */ }
                                is LlmStreamEvent.Error -> {
                                    responseBuilder.append(event.message)
                                    _currentResponse.value = responseBuilder.toString()
                                }
                                is LlmStreamEvent.ToolCalls -> {
                                    // Ignore subsequent tool calls in re-stream
                                }
                            }
                        }
                        }
                    } catch (_: Exception) { /* best effort re-stream */ }
                }

                // Stream completed — finalize the assistant message
                val response = responseBuilder.toString()
                val usage = repository.lastUsage
                if (response.isNotBlank()) {
                    // Accumulate token usage
                    usage?.let {
                        _totalInputTokens.value += it.inputTokens
                        _totalOutputTokens.value += it.outputTokens
                    }
                    SessionLog.llmResponse(currentProvider.displayName, hasToolCalls = false,
                        inputTokens = usage?.inputTokens, outputTokens = usage?.outputTokens,
                        latencyMs = null, model = currentProvider.model)
                    val assistantMessage = ChatMessage(
                        role = "assistant",
                        content = response,
                        hasContext = false,
                        tokenUsage = usage
                    )
                    _messages.value = _messages.value + assistantMessage
                } else if (toolCallsToProcess.isNotEmpty()) {
                    // LLM returned only tool calls without text — show tool result summary
                    val lastMsg = _messages.value.lastOrNull()
                    if (lastMsg?.isToolResult == true) {
                        // Already have a tool result message, no need to add another
                    } else {
                        _messages.value = _messages.value + ChatMessage(
                            role = "assistant",
                            content = "⚙ Mode updated.",
                            hasContext = false
                        )
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "LLM response timed out (60s)")
                SessionLog.llmError(currentProvider.displayName, currentProvider.model, "LLM response timed out (60s)")
                _error.value = "LLM response timed out. The provider may be overloaded — try again."
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelled by user — partial response already handled in cancelStreaming()
                Log.d(TAG, "Streaming cancelled by user")
            } catch (e: LlmException) {
                Log.e(TAG, "LLM error: ${e.message}", e)
                SessionLog.llmError(currentProvider.displayName, currentProvider.model, e.message ?: "Unknown LLM error", code = e.code)
                _error.value = e.message ?: "Unknown LLM error"
            } catch (e: UnsupportedOperationException) {
                Log.w(TAG, "Provider not supported: ${e.message}")
                SessionLog.llmError(currentProvider.displayName, currentProvider.model, e.message ?: "Provider not supported")
                _error.value = e.message ?: "Provider not yet supported"
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during streaming", e)
                SessionLog.llmError(currentProvider.displayName, currentProvider.model, e.message ?: "Unexpected error")
                _error.value = "Error: ${e.message}"
            } finally {
                _isStreaming.value = false
                SessionLog.uiDataSnapshot(
                    messages = _messages.value.size,
                    conversations = ChatHistory.listConversations(getApplication<Application>()).size,
                    isStreaming = false,
                    providerType = _provider.value?.providerType ?: "none",
                    model = _provider.value?.model ?: "none"
                )
                _currentResponse.value = ""
                streamingJob = null
            }
        }
    }

    /**
     * Cancel the current streaming operation.
     *
     * Cancels the coroutine Job, which also cancels the underlying
     * OkHttp call via the callbackFlow's awaitClose handler.
     */
    fun cancelStreaming() {
        val job = streamingJob
        streamingJob = null

        // Save partial response before cancelling
        val partial = _currentResponse.value
        _isStreaming.value = false
        _currentResponse.value = ""

        // Cancel the coroutine — this triggers awaitClose → call.cancel()
        job?.cancel()

        if (partial.isNotBlank()) {
            val cancelledMessage = ChatMessage(
                role = "assistant",
                content = partial + "\n\n_[Cancelled]_",
                hasContext = false
            )
            _messages.value = _messages.value + cancelledMessage
        }
    }

    /**
     * Clear all chat messages.
     */
    fun clearMessages() {
        _messages.value = emptyList()
        _currentResponse.value = ""
        _error.value = null
        _totalInputTokens.value = 0
        _totalOutputTokens.value = 0
        // Start a new conversation ID for the fresh chat
        _conversationId.value = ChatHistory.newConversationId()
    }

    /**
     * Clear the current error message.
     */
    fun clearError() {
        _error.value = null
    }

    // ── Agent mode ──────────────────────────────────────────────────────

    private val _agentMode = MutableStateFlow(LlmSettings.agentModeDefault)
    val agentMode: StateFlow<Boolean> = _agentMode.asStateFlow()

    private val _agentState = MutableStateFlow<AgentState>(AgentState.Idle)
    val agentState: StateFlow<AgentState> = _agentState.asStateFlow()

    /** Pending confirmation request from the agent loop (if any). */
    private val _pendingConfirmation = MutableStateFlow<Pair<ToolCall, ToolConfirmationDetails>?>(null)
    val pendingConfirmation: StateFlow<Pair<ToolCall, ToolConfirmationDetails>?> = _pendingConfirmation.asStateFlow()

    private val _confirmationResult = MutableStateFlow<Boolean?>(null)

    private var agentLoop: AgentLoop? = null
    private var agentJob: kotlinx.coroutines.Job? = null

    /** Toggle agent mode on/off. Persists the preference. */
    fun setAgentMode(enabled: Boolean) {
        _agentMode.value = enabled
        LlmSettings.agentModeDefault = enabled
        if (!enabled) {
            stopAgentLoop()
        }
    }

    /** Toggle agent mode. */
    fun toggleAgentMode() {
        setAgentMode(!_agentMode.value)
    }

    /**
     * Send a user message in agent mode.
     *
     * If the text starts with `/do`, it's stripped and processed as an agent command
     * regardless of agentMode state (implicit activation).
     */
    fun sendMessageAgent(text: String, engine: BrowserEngine?) {
        val currentProvider = _provider.value ?: run {
            _error.value = "No LLM provider configured"
            return
        }
        val wv = engine ?: run {
            _error.value = "Browser engine not available for agent mode"
            return
        }

        // Strip /do prefix
        val commandText = if (text.startsWith("/do ")) text.removePrefix("/do ").trim() else text

        // Auto-enable agent mode if using /do
        if (!_agentMode.value) {
            _agentMode.value = true
        }

        // Add user message
        _messages.value = _messages.value + ChatMessage(role = "user", content = commandText, hasContext = true)
        _error.value = null

        // Setup agent loop components
        val executor = ToolExecutor(
            onSwitchMode = { mode ->
                val isAgent = mode == "agent"
                if (_agentMode.value != isAgent) {
                    _agentMode.value = isAgent
                    LlmSettings.agentModeDefault = isAgent
                }
            },
            getCurrentMode = { if (_agentMode.value) "agent" else "chat" }
        )
        val gate = PermissionGate()

        // N1: ContextCompactor — compacts conversation when it exceeds token budget
        // Uses LlmRepositoryImpl.complete for a simple non-streaming summary generation
        val compactor = ContextCompactor { summaryPrompt ->
            try {
                val repository = LlmRepositoryImpl(currentProvider)
                repository.complete(summaryPrompt, null)
            } catch (e: Exception) {
                "(compaction failed: ${e.message})"
            }
        }

        val loop = AgentLoop(executor, gate, maxIterations = LlmSettings.maxIterations, undoStack = undoStack, contextCompactor = compactor)
        loop.supportsVision = currentProvider.supportsVision
        loop.providerName = currentProvider.displayName
        loop.modelName = currentProvider.model
        agentLoop = loop

        // Wire continuation handler — ask user when max iterations is reached
        loop.continueHandler = { maxIterations ->
            // Post continuation request to UI
            _pendingConfirmation.value = ToolCall(
                id = "continue",
                name = "continue_agent",
                arguments = com.google.gson.JsonObject().apply {
                    addProperty("iterations", maxIterations)
                }
            ) to ToolConfirmationDetails(
                action = "continue_agent",
                target = "Agent loop",
                preview = "Agent reached $maxIterations iterations. Continue for $maxIterations more?",
                riskLevel = ActionRisk.SENSITIVE
            )
            _agentState.value = AgentState.WaitingConfirmation(
                ToolCall(id = "continue", name = "continue_agent", arguments = com.google.gson.JsonObject())
            )
            _confirmationResult.value = null
            val deadline = System.currentTimeMillis() + 120_000
            while (_confirmationResult.value == null && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(500)
            }
            val result = _confirmationResult.value ?: false
            _confirmationResult.value = null
            _pendingConfirmation.value = null
            result
        }

        // Wire confirmation handler
        loop.confirmationHandler = { call, details ->
            // Post confirmation request to UI
            _pendingConfirmation.value = call to details
            _agentState.value = AgentState.WaitingConfirmation(call)
            // Suspend until user responds
            _confirmationResult.value = null
            // Wait for result with timeout
            val deadline = System.currentTimeMillis() + 60_000 // 60s timeout
            while (_confirmationResult.value == null && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(500)
            }
            val result = _confirmationResult.value ?: false
            _confirmationResult.value = null
            _pendingConfirmation.value = null
            result
        }

        // Wire LLM caller — streams tokens to UI in real-time
        loop.llmCaller = { messages, context, tools ->
            val repository = LlmRepositoryImpl(currentProvider)
            val systemPrompt = buildSystemPrompt("agent", wv)
            SessionLog.llmRequest(currentProvider.displayName, currentProvider.model, messages.size,
                hasTools = tools.isNotEmpty(),
                systemPromptLength = systemPrompt.length,
                toolNames = tools.joinToString(",") { it.name })
            val sb = StringBuilder()
            var toolCallsList: List<ToolCall>? = null
            var streamError: String? = null
            var streamErrorCode: Int? = null
            var lastUsage: TokenUsage? = null

            // Clear previous streaming content for this iteration
            _currentResponse.value = ""

            try {
                withTimeout(60_000L) {
                    repository.streamWithTools(messages, context, systemPrompt, tools).collect { event ->
                        when (event) {
                            is LlmStreamEvent.Token -> {
                                sb.append(event.content)
                                _currentResponse.value = sb.toString()
                            }
                            is LlmStreamEvent.ToolCalls -> toolCallsList = event.calls
                            is LlmStreamEvent.Start -> Unit
                            is LlmStreamEvent.Complete -> {
                                event.usage?.let {
                                    lastUsage = it
                                    _totalInputTokens.value += it.inputTokens
                                    _totalOutputTokens.value += it.outputTokens
                                }
                            }
                            is LlmStreamEvent.Error -> {
                                streamError = event.message
                                streamErrorCode = event.code
                            }
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                streamError = "LLM response timed out (60s)"
                SessionLog.log(
                    com.devcompanion.logging.EventType.ERROR,
                    mapOf("what" to "llm_timeout", "provider" to currentProvider.displayName, "model" to currentProvider.model)
                )
            } catch (e: Exception) {
                streamError = e.message
            }

            // Clear streaming buffer — content is now finalized
            _currentResponse.value = ""

            val capturedError = streamError
            if (capturedError != null) {
                SessionLog.llmError(currentProvider.displayName, currentProvider.model,
                    capturedError, code = streamErrorCode)
                LlmResponse(content = "Error: $capturedError", toolCalls = emptyList(),
                    inputTokens = lastUsage?.inputTokens, outputTokens = lastUsage?.outputTokens)
            } else {
                LlmResponse(content = sb.toString(), toolCalls = toolCallsList ?: emptyList(),
                    inputTokens = lastUsage?.inputTokens, outputTokens = lastUsage?.outputTokens)
            }
        }

        // Observe agent state
        viewModelScope.launch {
            loop.state.collect { state ->
                _agentState.value = state
                // Only expose significant state changes as system messages.
                // Thinking/Permission states are shown via the status bar,
                // so we don't pollute chat with transient system messages.
                when (state) {
                    is AgentState.Thinking -> Unit // Shown in status bar
                    is AgentState.ExecutingTool -> Unit // Shown in status bar
                    is AgentState.CheckingPermission -> Unit // Shown in status bar
                    is AgentState.WaitingConfirmation -> Unit // Shown in status bar
                    is AgentState.Error -> {
                        _messages.value = _messages.value + ChatMessage(
                            role = "system",
                            content = "❌ ${state.message}",
                            hasContext = false
                        )
                    }
                    else -> { /* Idle - no message */ }
                }
            }
        }

        // Start foreground service to keep agent running in background
        val app = getApplication<Application>()
        val serviceIntent = Intent(app, AgentService::class.java).apply {
            action = AgentService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(serviceIntent)
        } else {
            app.startService(serviceIntent)
        }

        // Start agent loop
        agentJob = viewModelScope.launch {
            _isStreaming.value = true
            try {
                loop.start(commandText, wv, history = _messages.value.dropLast(1)).collect { event ->
                    when (event) {
                        is AgentEvent.TextResponse -> {
                            _messages.value = _messages.value + ChatMessage(
                                role = "assistant",
                                content = event.content,
                                hasContext = false
                            )
                            _isStreaming.value = false
                        }
                        is AgentEvent.ToolExecuted -> {
                            // Show tool result in chat — collapsible with color coding
                            val toolLabel = if (event.result.isError) "⚠️" else "🔧"
                            // Pretty-print JSON output and unescape unicode escapes
                            val displayOutput = try {
                                val json = com.google.gson.JsonParser.parseString(event.result.output)
                                val gson = com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                                gson.toJson(json)
                            } catch (_: Exception) {
                                // Not valid JSON — return as-is with basic unescape
                                event.result.output
                                    .replace("\\u003C", "<")
                                    .replace("\\u003E", ">")
                                    .replace("\\u0026", "&")
                            }
                            val resultText = if (event.result.isError) {
                                "$toolLabel ${event.call.name}: ${displayOutput.take(1000)}"
                            } else {
                                "$toolLabel ${event.call.name}: ${displayOutput.take(800)}"
                            }
                            _messages.value = _messages.value + ChatMessage(
                                role = "system",
                                content = resultText,
                                hasContext = false,
                                isToolResult = true,
                                isError = event.result.isError
                            )
                        }
                        is AgentEvent.ConfirmationRequired -> {
                            // Already handled via confirmationHandler
                        }
                        is AgentEvent.Rejected -> {
                            _messages.value = _messages.value + ChatMessage(
                                role = "assistant",
                                content = "❌ Action rejected: ${event.call.name}",
                                hasContext = false
                            )
                        }
                        is AgentEvent.Error -> {
                            _error.value = event.message
                            _messages.value = _messages.value + ChatMessage(
                                role = "assistant",
                                content = "⚠️ Error: ${event.message}",
                                hasContext = false
                            )
                            _isStreaming.value = false
                        }
                        is AgentEvent.BudgetExceeded -> {
                            _messages.value = _messages.value + ChatMessage(
                                role = "assistant",
                                content = "⚠️ Token budget exceeded",
                                hasContext = false
                            )
                            _isStreaming.value = false
                        }
                    }
                }
            } finally {
                _isStreaming.value = false
                _agentState.value = AgentState.Idle
                agentLoop = null
                agentJob = null
                SessionLog.log(EventType.AGENT_END, mapOf("reason" to "loop_finished"))
                // Flush immediately so AGENT_END is persisted even if the app
                // goes to background right after the coroutine cancels.
                val app = getApplication<Application>()
                viewModelScope.launch(Dispatchers.IO) { SessionLog.flush(app) }
                // Stop foreground service when agent finishes
                app.startService(Intent(app, AgentService::class.java).apply {
                    action = AgentService.ACTION_STOP
                })
            }
        }
    }

    /** User responds to a pending confirmation — approve or reject. */
    fun respondConfirmation(approved: Boolean) {
        _confirmationResult.value = approved
    }

    /** Stop the agent loop. */
    fun stopAgentLoop() {
        SessionLog.log(EventType.AGENT_END, mapOf("reason" to "user_stop"))
        agentLoop?.stop()
        agentJob?.cancel()
        agentJob = null
        agentLoop = null
        _agentState.value = AgentState.Idle
        // Stop foreground service
        val app = getApplication<Application>()
        app.startService(Intent(app, AgentService::class.java).apply {
            action = AgentService.ACTION_STOP
        })
        _isStreaming.value = false
        _currentResponse.value = ""
        _pendingConfirmation.value = null
        _confirmationResult.value = null
    }

    // ── Undo / Rollback ────────────────────────────────────────────────

    private val undoStack = com.devcompanion.llm.agent.UndoStack()

    /**
     * Navigate back to the previous page in the undo stack.
     * Falls back to engine.goBack() if the stack is empty.
     */
    suspend fun undoNavigation(engine: BrowserEngine): Boolean {
        return undoStack.undo(engine!!) != null || undoStack.goBack(engine!!)
    }

    /** Clear the undo stack. */
    fun clearUndoStack() {
        undoStack.clear()
    }

    /**
     * Analyze the last assistant message's markdown rendering.
     * Returns a JSON string with parse results for the agent to self-diagnose.
     */
    fun analyzeLastResponse(): String {
        return try {
            val lastAssistantMsg = _messages.value.lastOrNull { it.role == "assistant" }
                ?: return "{\"error\":\"no_assistant_message\"}"
            val analysis = analyzeMarkdown(lastAssistantMsg.content)
            val sb = StringBuilder()
            sb.append("{\"rawLength\":${analysis.rawLength},\"blocks\":[")
            analysis.blocks.forEachIndexed { i, b ->
                if (i > 0) sb.append(",")
                sb.append("{\"type\":\"${b.type.jsonEscape()}\",\"details\":\"${b.details.jsonEscape()}\",\"preview\":\"${b.contentPreview.jsonEscape()}\",\"spans\":${b.spanCount},\"renderLen\":${b.renderedLength}}")
            }
            sb.append("]}")
            sb.toString()
        } catch (e: Exception) {
            "{\"error\":\"${e.message?.jsonEscape()}\"}"
        }
    }

    private fun String.jsonEscape(): String =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    override fun onCleared() {
        super.onCleared()
        // Flush any debounced messages that haven't been persisted yet.
        // ChatHistory.save() is synchronous (file I/O), so safe to call here.
        val msgs = _messages.value
        if (msgs.isNotEmpty()) {
            ChatHistory.save(getApplication<Application>(), _conversationId.value, msgs, _sourceUrl)
        }
        // Release agent loop scope to prevent coroutine leaks
        agentLoop?.destroy()
        agentLoop = null
    }
}
