package com.devcompanion.ui

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import android.webkit.WebView
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
import com.devcompanion.llm.agent.LlmResponse
import com.devcompanion.llm.agent.PermissionGate
import com.devcompanion.llm.agent.ToolCall
import com.devcompanion.llm.agent.ToolConfirmationDetails
import com.devcompanion.llm.agent.ToolExecutor
import com.devcompanion.llm.agent.WebViewTools
import com.devcompanion.logging.EventType
import com.devcompanion.logging.SessionLog
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * ViewModel managing the AI chat screen state.
 *
 * Handles provider selection, message history, streaming responses,
 * and web context capture. Confirmation prompts for context capture
 * are delegated to the UI layer.
 */
class AiChatViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "AiChatViewModel"
    }

    /** Build system prompt with WebView URL context and URL history. */
    private fun buildSystemPrompt(mode: String, webView: WebView? = null): String {
        val url = try { webView?.url } catch (_: Exception) { null }
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

    internal var currentConversationId: String = ChatHistory.newConversationId()
    private var saveJob: kotlinx.coroutines.Job? = null

    /** Save messages to disk (debounced). Called after each message mutation. */
    private fun saveMessages() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            kotlinx.coroutines.delay(500) // debounce 500ms
            ChatHistory.save(getApplication<Application>(), currentConversationId, _messages.value)
        }
    }

    /** Load a previous conversation. */
    fun loadConversation(conversationId: String) {
        currentConversationId = conversationId
        _messages.value = ChatHistory.load(getApplication<Application>(), conversationId)
    }

    /** Start a new conversation. */
    fun newConversation() {
        currentConversationId = ChatHistory.newConversationId()
        _messages.value = emptyList()
        _currentResponse.value = ""
        _isStreaming.value = false
        _agentState.value = AgentState.Idle
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
        if (conversationId == currentConversationId) {
            newConversation()
        }
    }

    /** Export current conversation as JSON string. */
    fun exportCurrentConversation(): String? {
        return ChatHistory.exportToJson(getApplication<Application>(), currentConversationId)
    }

    /** Import a conversation from JSON string. Returns new conversation ID or null. */
    fun importConversation(json: String): String? {
        return ChatHistory.importFromJson(getApplication<Application>(), json)
    }

    init {
        // Auto-save messages whenever they change
        viewModelScope.launch {
            _messages
                .collect {
                    if (it.isNotEmpty()) {
                        ChatHistory.save(getApplication<Application>(), currentConversationId, it)
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
        }

        // Auto-restore last conversation on startup
        try {
            val conversations = ChatHistory.listConversations(application)
            val lastConv = conversations.firstOrNull()
            if (lastConv != null) {
                currentConversationId = lastConv.id
                _messages.value = ChatHistory.load(application, lastConv.id)
                Log.d(TAG, "Restored last conversation: ${lastConv.id} (${lastConv.messageCount} msgs)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore last conversation", e)
        }
    }

    /**
     * Set the active LLM provider and persist it.
     */
    fun setProvider(provider: LlmProvider) {
        _provider.value = provider
        try {
            LlmSettings.saveProvider(provider)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save provider", e)
        }
    }

    /**
     * Capture the current WebView state as context for the next message.
     * The UI layer should confirm before calling this.
     * @param mode Capture depth (defaults to Standard for rich context).
     */
    suspend fun captureContext(webView: WebView, mode: CaptureMode = CaptureMode.Standard) {
        try {
            val context = WebContextBuilder.buildContext(webView, mode)
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
     * If [webView] is provided and the user hasn't manually captured context,
     * automatically captures a Standard-mode snapshot before sending.
     * Manually captured context (via 📷 button) takes priority.
     */
    private var streamingJob: kotlinx.coroutines.Job? = null

    private var _autoCaptureEnabled = MutableStateFlow(true)
    val autoCaptureEnabled: StateFlow<Boolean> = _autoCaptureEnabled.asStateFlow()

    fun setAutoCaptureEnabled(enabled: Boolean) {
        _autoCaptureEnabled.value = enabled
    }

    fun sendMessage(text: String, webView: WebView? = null) {
        val currentProvider = _provider.value ?: run {
            _error.value = "No LLM provider configured"
            return
        }

        SessionLog.llmRequest(currentProvider.displayName, currentProvider.model, _messages.value.size, hasTools = false)

        // If no manually captured context and auto-capture is on, capture from WebView
        val context = _lastContext.value ?: run {
            if (_autoCaptureEnabled.value && webView != null) {
                // Use runBlocking alternatives — we'll launch a coroutine
                // and capture context inline before sending
                null // Will be captured in the streaming coroutine
            } else null
        }

        // Add user message
        val hasContext = context != null || (_autoCaptureEnabled.value && webView != null)
        val userMessage = ChatMessage(
            role = "user",
            content = text,
            hasContext = hasContext
        )
        _messages.value = _messages.value + userMessage

        // Clear error state
        _error.value = null
        _isStreaming.value = true
        _currentResponse.value = ""

        // Clear manually captured context after use (one-shot)
        _lastContext.value = null

        val responseBuilder = StringBuilder()

        streamingJob = viewModelScope.launch {
            try {
                // Auto-capture context if needed
                var capturedContext = context ?: if (_autoCaptureEnabled.value && webView != null) {
                    try {
                        WebContextBuilder.buildContext(webView, CaptureMode.Standard)
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
                val systemPrompt = buildSystemPrompt("chat", webView)
                var toolCallsToProcess = listOf<com.devcompanion.llm.agent.ToolCall>()
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

                // Process any mode-switch tool calls
                if (toolCallsToProcess.isNotEmpty()) {
                    val toolResults = mutableListOf<String>()
                    for (call in toolCallsToProcess) {
                        val result = modeExecutor.execute(call, webView ?: return@collect)
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
                    SessionLog.llmResponse(currentProvider.displayName, hasToolCalls = false, usage?.inputTokens, usage?.outputTokens, null)
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelled by user — partial response already handled in cancelStreaming()
                Log.d(TAG, "Streaming cancelled by user")
            } catch (e: LlmException) {
                Log.e(TAG, "LLM error: ${e.message}", e)
                SessionLog.llmError(currentProvider.displayName, e.message ?: "Unknown LLM error", e.code)
                _error.value = e.message ?: "Unknown LLM error"
            } catch (e: UnsupportedOperationException) {
                Log.w(TAG, "Provider not supported: ${e.message}")
                SessionLog.llmError(currentProvider.displayName, e.message ?: "Provider not supported")
                _error.value = e.message ?: "Provider not yet supported"
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during streaming", e)
                SessionLog.llmError(currentProvider.displayName, e.message ?: "Unexpected error")
                _error.value = "Error: ${e.message}"
            } finally {
                _isStreaming.value = false
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
        currentConversationId = ChatHistory.newConversationId()
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
    fun sendMessageAgent(text: String, webView: WebView?) {
        val currentProvider = _provider.value ?: run {
            _error.value = "No LLM provider configured"
            return
        }
        val wv = webView ?: run {
            _error.value = "WebView not available for agent mode"
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
        val loop = AgentLoop(executor, gate, maxIterations = LlmSettings.maxIterations)
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
            SessionLog.llmRequest(currentProvider.displayName, currentProvider.model, messages.size, hasTools = tools.isNotEmpty())
            val sb = StringBuilder()
            var toolCallsList: List<ToolCall>? = null
            var streamError: String? = null

            // Clear previous streaming content for this iteration
            _currentResponse.value = ""

            try {
                repository.streamWithTools(messages, context, buildSystemPrompt("agent", wv), tools).collect { event ->
                    when (event) {
                        is LlmStreamEvent.Token -> {
                            sb.append(event.content)
                            _currentResponse.value = sb.toString()
                        }
                        is LlmStreamEvent.ToolCalls -> toolCallsList = event.calls
                        is LlmStreamEvent.Start -> Unit
                        is LlmStreamEvent.Complete -> {
                            event.usage?.let {
                                _totalInputTokens.value += it.inputTokens
                                _totalOutputTokens.value += it.outputTokens
                            }
                        }
                        is LlmStreamEvent.Error -> streamError = event.message
                    }
                }
            } catch (e: Exception) {
                streamError = e.message
            }

            // Clear streaming buffer — content is now finalized
            _currentResponse.value = ""

            if (streamError != null) {
                LlmResponse(content = "Error: $streamError", toolCalls = emptyList())
            } else {
                LlmResponse(content = sb.toString(), toolCalls = toolCallsList ?: emptyList())
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
            SessionLog.log(EventType.AGENT_START, mapOf("command" to commandText.take(100), "mode" to "agent"))
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
                // Stop foreground service when agent finishes
                getApplication<Application>().stopService(
                    Intent(getApplication<Application>(), AgentService::class.java)
                )
            }
        }
    }

    /** User responds to a pending confirmation — approve or reject. */
    fun respondConfirmation(approved: Boolean) {
        _confirmationResult.value = approved
    }

    /** Stop the agent loop. */
    fun stopAgentLoop() {
        agentLoop?.stop()
        agentJob?.cancel()
        agentJob = null
        agentLoop = null
        _agentState.value = AgentState.Idle
        // Stop foreground service
        val app = getApplication<Application>()
        app.stopService(Intent(app, AgentService::class.java))
        _isStreaming.value = false
        _currentResponse.value = ""
        _pendingConfirmation.value = null
        _confirmationResult.value = null
    }

    // ── Undo / Rollback ────────────────────────────────────────────────

    private val undoStack = com.devcompanion.llm.agent.UndoStack()

    /**
     * Navigate back to the previous page in the undo stack.
     * Falls back to WebView.goBack() if the stack is empty.
     */
    suspend fun undoNavigation(webView: WebView): Boolean {
        return undoStack.undo(webView) != null || undoStack.goBack(webView)
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
}
