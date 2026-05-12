package com.devcompanion.llm.agent

import android.util.Log
import android.webkit.WebView
import com.devcompanion.llm.ChatMessage
import com.devcompanion.llm.CaptureMode
import com.devcompanion.llm.WebContextBuilder
import com.devcompanion.llm.WebContextPacket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch

/**
 * Agent loop that orchestrates LLM tool calling with WebView execution.
 *
 * The loop works as follows:
 * 1. Capture WebView context (screenshot + DOM)
 * 2. Send to LLM with tool definitions
 * 3. If LLM responds with tool_calls → execute via ToolExecutor (with permission checks)
 * 4. Feed tool results back to LLM with correct message pairing
 * 5. Repeat until LLM gives a text-only response or max iterations reached
 * 6. When max iterations is reached, ask user whether to continue
 *
 * Supports multi-turn: prior conversation history is passed as context.
 *
 * State machine: Idle → Thinking → ExecutingTool → (WaitingConfirmation) → Thinking → ...
 * Cancellable at any point via [stop()].
 */
class AgentLoop(
    private val toolExecutor: ToolExecutor,
    private val permissionGate: PermissionGate,
    private val maxIterations: Int = 10,
    private val undoStack: UndoStack = UndoStack()
) {
    companion object {
        private const val TAG = "AgentLoop"
        private const val MAX_CONSECUTIVE_ERRORS = 3
        private const val MAX_SAME_ACTION_REPEAT = 3
    }

    private val _state = MutableStateFlow<AgentState>(AgentState.Idle)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private var currentJob: Job? = null

    /** Callback for requesting user confirmation of sensitive actions. */
    var confirmationHandler: suspend (ToolCall, ToolConfirmationDetails) -> Boolean = { _, _ -> false }

    /** Callback for asking user whether to continue when max iterations is reached. */
    var continueHandler: suspend (Int) -> Boolean = { _ -> false }

    /** Callback for calling the LLM with tools. Provided by the ViewModel. */
    var llmCaller: suspend (messages: List<ChatMessage>, context: WebContextPacket?, tools: List<ToolDefinition>) -> LlmResponse? = { _, _, _ -> null }

    /**
     * Start the agent loop.
     *
     * @param userMessage The initial user message.
     * @param webView The WebView to operate on.
     * @param history Prior conversation messages for multi-turn context.
     * @return A flow of [AgentEvent]s for the UI to consume.
     */
    fun start(
        userMessage: String,
        webView: WebView,
        history: List<ChatMessage> = emptyList()
    ): Flow<AgentEvent> = callbackFlow {
        currentJob?.cancel()

        currentJob = CoroutineScope(Dispatchers.Default).launch {
            try {
                runAgentLoop(userMessage, webView, history) { event ->
                    trySend(event)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Agent loop cancelled")
                _state.value = AgentState.Idle
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Agent loop error", e)
                _state.value = AgentState.Error(e.message ?: "Unknown error")
            } finally {
                _state.value = AgentState.Idle
            }
        }

        awaitClose { currentJob?.cancel() }
    }

    /** Cancel the running agent loop. */
    fun stop() {
        Log.d(TAG, "Agent loop stopped by user")
        currentJob?.cancel()
        _state.value = AgentState.Idle
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private suspend fun runAgentLoop(
        userMessage: String,
        webView: WebView,
        history: List<ChatMessage>,
        emit: (AgentEvent) -> Unit
    ) {
        _state.value = AgentState.Thinking(0)

        val messages = mutableListOf<ChatMessage>()
        // Include prior conversation context for multi-turn
        messages.addAll(history)
        messages.add(ChatMessage(role = "user", content = userMessage, hasContext = true))

        val completed = runAgentLoopBody(messages, webView, maxIterations, 0, 0, null, 0, emit)

        if (!completed) return // aborted due to error

        // If we exhausted iterations without completing, ask user whether to continue
        if (_state.value != AgentState.Idle) {
            val shouldContinue = try {
                continueHandler(maxIterations)
            } catch (_: Exception) {
                false
            }
            if (shouldContinue) {
                messages.add(ChatMessage(role = "system", content = "[User approved continuing beyond $maxIterations iterations. Proceed carefully.]"))
                runAgentLoopBody(messages, webView, maxIterations, 0, maxIterations, null, 0, emit)
            } else {
                emit(AgentEvent.Error("Max iterations reached ($maxIterations)"))
                _state.value = AgentState.Error("Max iterations")
            }
        }
    }

    /**
     * Reusable inner loop body. Called from [runAgentLoop] and from continuation.
     * Returns true if the loop completed naturally or exhausted iterations,
     * false if it was aborted due to an error.
     */
    private suspend fun runAgentLoopBody(
        messages: MutableList<ChatMessage>,
        webView: WebView,
        iterations: Int,
        startConsecutiveErrors: Int = 0,
        startIterationOffset: Int = 0,
        lastAction: Pair<String, String>? = null,
        startRepeatCount: Int = 0,
        emit: (AgentEvent) -> Unit
    ): Boolean {
        var consecutiveErrors = startConsecutiveErrors
        var prevAction = lastAction
        var repeatCount = startRepeatCount

        for (i in 0 until iterations) {
            val iteration = i + startIterationOffset
            val remainingIterations = iterations - i
            val effectiveTools = if (remainingIterations <= 1) emptyList() else WebViewTools.ALL
            val budgetWarning = when {
                remainingIterations == 2 -> "\n[SYSTEM: You have 1 tool call remaining. After this, summarize your findings and respond in text only.]"
                remainingIterations == 1 -> "\n[SYSTEM: No more tool calls allowed. Summarize your findings and respond in text only.]"
                else -> null
            }

            // Capture context (full screenshot only on first iteration of each round)
            val context = try {
                if (iteration == 0) {
                    WebContextBuilder.buildContext(webView, CaptureMode.Standard)
                } else {
                    val full = WebContextBuilder.buildContext(webView, CaptureMode.Standard)
                    full.copy(screenshotBase64 = "", screenshotMimeType = "")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Context capture failed, continuing without context", e)
                null
            }

            _state.value = AgentState.Thinking(iteration + 1)

            // Build messages with budget warning if needed
            val effectiveMessages = if (budgetWarning != null) {
                messages + ChatMessage(role = "system", content = budgetWarning)
            } else {
                messages
            }

            // Call LLM
            val response = try {
                llmCaller(effectiveMessages, context, effectiveTools)
            } catch (e: Exception) {
                emit(AgentEvent.Error("LLM call failed: ${e.message}"))
                _state.value = AgentState.Error(e.message ?: "LLM error")
                return false
            }

            if (response == null) {
                emit(AgentEvent.Error("No response from LLM"))
                _state.value = AgentState.Error("No response")
                return false
            }

            // Process response
            if (response.toolCalls.isNotEmpty()) {
                // Add assistant message with tool_calls to history
                messages.add(ChatMessage(
                    role = "assistant",
                    content = response.content.ifBlank { "" },
                    toolCalls = response.toolCalls
                ))

                // Process each tool call
                for (call in response.toolCalls) {
                    // Loop detection
                    val actionKey = call.name to call.arguments.toString()
                    if (actionKey == prevAction) {
                        repeatCount++
                        if (repeatCount >= MAX_SAME_ACTION_REPEAT) {
                            emit(AgentEvent.Error("Stuck in loop: same action repeated $repeatCount times"))
                            _state.value = AgentState.Error("Loop detected")
                            return false
                        }
                    } else {
                        repeatCount = 0
                        prevAction = actionKey
                    }

                    // Permission check
                    _state.value = AgentState.CheckingPermission(call, iteration + 1)
                    val risk = permissionGate.classify(call, webView)

                    if (risk == ActionRisk.SENSITIVE) {
                        val details = permissionGate.getConfirmationDetails(call)
                        emit(AgentEvent.ConfirmationRequired(call, details))
                        _state.value = AgentState.WaitingConfirmation(call)

                        val approved = try {
                            confirmationHandler(call, details)
                        } catch (e: Exception) {
                            Log.w(TAG, "Confirmation handler error", e)
                            false
                        }

                        if (!approved) {
                            emit(AgentEvent.Rejected(call))
                            messages.add(ChatMessage(
                                role = "tool",
                                content = "User rejected action: ${call.name}",
                                hasContext = false,
                                toolCallId = call.id,
                                toolName = call.name
                            ))
                            _state.value = AgentState.Thinking(iteration + 1)
                            continue
                        }
                    }

                    // Execute tool
                    _state.value = AgentState.ExecutingTool(call.name, iteration + 1)

                    // Push current URL to undo stack before navigation/submission
                    if (call.name == "navigate" || call.name == "submit_form") {
                        withContext(Dispatchers.Main) {
                            undoStack.push(webView.url ?: "")
                        }
                    }

                    val result = try {
                        toolExecutor.execute(call, webView)
                    } catch (e: Exception) {
                        ToolResult(call.id, "Error: ${e.message}", isError = true)
                    }

                    // Error tracking
                    if (result.isError) {
                        consecutiveErrors++
                        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                            emit(AgentEvent.Error("Too many consecutive errors ($consecutiveErrors)"))
                            _state.value = AgentState.Error("Max errors")
                            return false
                        }
                    } else {
                        consecutiveErrors = 0
                    }

                    emit(AgentEvent.ToolExecuted(call, result))
                    // Include tool_call_id for API protocol compliance
                    messages.add(ChatMessage(
                        role = "tool",
                        content = if (result.isError) "Error: ${result.output}" else result.output,
                        hasContext = false,
                        toolCallId = call.id,
                        toolName = call.name
                    ))
                    _state.value = AgentState.Thinking(iteration + 1)
                }
                // Continue loop for next LLM decision
            } else {
                // Text-only response → done
                if (response.content.isNotBlank()) {
                    messages.add(ChatMessage(
                        role = "assistant",
                        content = response.content
                    ))
                }
                emit(AgentEvent.TextResponse(response.content))
                _state.value = AgentState.Idle
                return true // completed
            }
        }
        return true // exhausted iterations but didn't abort
    }
}

/**
 * Response from the LLM that may contain tool calls or text content.
 */
data class LlmResponse(
    val content: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val hasToolCalls: Boolean = toolCalls.isNotEmpty()
)