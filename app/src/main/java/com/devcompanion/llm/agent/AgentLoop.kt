package com.devcompanion.llm.agent

import android.util.Log
import com.devcompanion.engine.BrowserEngine
import com.devcompanion.llm.ChatMessage
import com.devcompanion.llm.CaptureMode
import com.devcompanion.llm.WebContextBuilder
import com.devcompanion.llm.WebContextPacket
import com.devcompanion.logging.EventType
import com.devcompanion.logging.SessionLog
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
 * Agent loop that orchestrates LLM tool calling with browser engine execution.
 *
 * The loop works as follows:
 * 1. Capture browser engine context (screenshot + DOM)
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
    private val undoStack: UndoStack = UndoStack(),
    private val contextCompactor: ContextCompactor? = null
) {
    companion object {
        private const val TAG = "AgentLoop"
        private const val MAX_CONSECUTIVE_ERRORS = 3
        private const val MAX_SAME_ACTION_REPEAT = 2
        /** Maximum length of tool result content sent to LLM context. Results exceeding this are truncated with a recall hint. */
        private const val CONTEXT_TOOL_RESULT_BUDGET = 4000
        /** Fraction of tool result budget allocated to the tail (end) when middle-truncating. */
        private const val MIDDLE_TRUNCATE_TAIL_RATIO = 0.3f
        /** Minimum tool result budget (chars) for adaptive truncation. */
        private const val MIN_TOOL_RESULT_BUDGET = 2000
        /** Maximum tool result budget (chars) for adaptive truncation. */
        private const val MAX_TOOL_RESULT_BUDGET = 6000
        /** Estimated context token budget (triggers compaction when exceeded). */
        private const val ESTIMATED_CONTEXT_TOKEN_BUDGET = 8000
        /** Reserve ratio: fraction of context to keep free for new content (LibreChat pattern). */
        private const val CONTEXT_RESERVE_RATIO = 0.15f
        const val MAX_SEMANTIC_ERRORS = 3
    }

    private val _state = MutableStateFlow<AgentState>(AgentState.Idle)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private var currentJob: Job? = null

    /** Scratchpad for storing full tool results during this agent session. */
    private var scratchpad: SessionScratchpad = SessionScratchpad()

    // ── Loop detection state (M1 + M4) ───────────────────────────────

    /** Tracks repeated recall of the same scratchpad index. */
    private var lastRecallIndex: Int? = null
    private var recallRepeatCount = 0

    /** Tracks (toolName, keyParam) signature for same-action detection. */
    private var lastActionSignature: ActionSignature? = null
    private var sameActionCount = 0

    // ── Semantic failure tracking (semanticError from ToolResult) ──────
    /** Tracks consecutive semantic errors per tool category. Key = tool name. */
    private val semanticErrorCounts = mutableMapOf<String, Int>()

    /** Whether the current provider supports vision (image input). Set by ViewModel. */
    var supportsVision: Boolean = true

    /** Current provider name for logging. Set by ViewModel. */
    var providerName: String = "Unknown"

    /** Current model name for logging. Set by ViewModel. */
    var modelName: String = "Unknown"

    // ── Tool result context framing ──────────────────────────────────

    /**
     * Build a context-framed tool result message for the LLM.
     *
     * Instead of just passing raw tool output, we frame it with:
     * - Which tool produced this result
     * - What the user's original intent was
     * - Whether the result was truncated (and how to retrieve full content)
     * - Whether there was an error (and what type)
     *
     * This prevents the LLM from losing track of user intent when interpreting tool results.
     */
    private fun buildFramedToolResult(
        callName: String,
        rawOutput: String,
        isError: Boolean,
        isTruncated: Boolean,
        entryIndex: Int,
        userIntent: String,
        errorType: String?,
        budget: Int = CONTEXT_TOOL_RESULT_BUDGET
    ): String {
        return buildString {
            // Context frame header
            appendLine("[Tool: $callName | Intent: \"$userIntent\"]")

            // Error framing
            if (isError) {
                appendLine("⚠️ ERROR: This tool call failed.")
            }

            // Error type guidance
            if (errorType != null) {
                when (errorType) {
                    "csp" -> appendLine("⚠️ CSP (Content Security Policy) blocked JavaScript execution. Do NOT retry eval_js on this site — switch to get_dom, extract_text, or click instead.")
                    "not_found" -> appendLine("⚠️ Element not found. Try a different selector or use extract_text for the full page.")
                    "timeout" -> appendLine("⚠️ Operation timed out. The page may be slow or the selector too broad.")
                    "download" -> appendLine("⚠️ JS returned a download/save/export trigger, but this does NOT mean a file was actually saved. WebView cannot download files via JS. Use extract_text to collect data instead.")
                    else -> appendLine("⚠️ Error type: $errorType")
                }
            }

            // Truncation warning with recall hint — M3: middle truncation (N4: adaptive budget)
            if (isTruncated) {
                val truncated = middleTruncate(rawOutput, budget)
                appendLine("⚠️ RESULT TRUNCATED: ${rawOutput.length} chars total, showing ${budget} chars (head + tail). Use recall(index=$entryIndex) to retrieve the full content.")
                appendLine("---")
                appendLine(truncated)
                appendLine("---")
                appendLine("[End of truncated result. Full content available via recall(index=$entryIndex)]")
            } else {
                appendLine("---")
                append(rawOutput)
            }
        }
    }

    /**
     * Detect the type of error from tool output for targeted guidance.
     */
    private fun detectErrorType(output: String): String? {
        val lower = output.lowercase()
        return when {
            lower.contains("content security policy") || lower.contains("csp") ||
                lower.contains("unsafe-eval") || lower.contains("script-src") -> "csp"
            lower.contains("not found") || lower.contains("no element") ||
                (lower.contains("selector") && lower.contains("match")) -> "not_found"
            lower.contains("timeout") || lower.contains("timed out") -> "timeout"
            (lower.contains("download") || lower.contains("save")) &&
                (lower.contains("trigger") || lower.contains("initiated") || lower.contains("started")) -> "download"
            else -> null
        }
    }

    /**
     * Middle-truncate a string: keep head + tail with an omission marker in between.
     * This preserves both the beginning and end of long tool results,
     * which is far more useful than head-only truncation.
     *
     * Inspired by Codex TruncationPolicy (middle truncation pattern).
     *
     * @param raw The full string to truncate.
     * @param budget Maximum character budget.
     * @return Truncated string with "[N chars omitted]" marker.
     */
    private fun middleTruncate(raw: String, budget: Int): String {
        if (raw.length <= budget) return raw
        val tailSize = (budget * MIDDLE_TRUNCATE_TAIL_RATIO).toInt()
        val headSize = budget - tailSize - 30 // 30 chars for the omission marker
        val omitted = raw.length - headSize - tailSize
        return raw.take(headSize) +
            "\n\n... [$omitted chars omitted] ...\n\n" +
            raw.takeLast(tailSize)
    }

    /**
     * Compute adaptive tool result budget based on current context usage and remaining iterations.
     *
     * When context is already large or iterations are running low, we reduce the per-result
     * budget to avoid overwhelming the context window. When there's plenty of room,
     * we allow more content per result.
     *
     * Inspired by LibreChat's per-agent `maxToolResultChars` with dynamic allocation.
     *
     * @param messages Current conversation messages
     * @param remainingIterations How many iterations are left (including this one)
     * @return Character budget for the current tool result
     */
    private fun computeToolResultBudget(messages: List<ChatMessage>, remainingIterations: Int): Int {
        // Rough estimate of current context size in chars
        val contextChars = messages.sumOf { it.content.length }
        // Each remaining iteration might add ~budget chars of tool result
        // Use diminishing budget as context grows
        val contextBudgetChars = ESTIMATED_CONTEXT_TOKEN_BUDGET * 4 // chars
        val available = (contextBudgetChars - contextChars).coerceAtLeast(0)
        val perIteration = available / maxOf(remainingIterations, 1)
        return perIteration.coerceIn(MIN_TOOL_RESULT_BUDGET, MAX_TOOL_RESULT_BUDGET)
    }

    // ── Action signature for loop detection ─────────────────────────

    /**
     * Identifies a tool call by its name and key parameter (selector or url).
     * Used to detect repeated identical actions across iterations.
     */
    private data class ActionSignature(val tool: String, val keyParam: String?)

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
     * @param engine The browser engine to operate on.
     * @param history Prior conversation messages for multi-turn context.
     * @return A flow of [AgentEvent]s for the UI to consume.
     */
    fun start(
        userMessage: String,
        engine: BrowserEngine,
        history: List<ChatMessage> = emptyList()
    ): Flow<AgentEvent> = callbackFlow {
        currentJob?.cancel()

        currentJob = CoroutineScope(Dispatchers.Default).launch {
            try {
                runAgentLoop(userMessage, engine, history) { event ->
                    trySend(event)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Agent loop cancelled")
                SessionLog.log(EventType.AGENT_END, mapOf("reason" to "cancelled"))
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
        engine: BrowserEngine,
        history: List<ChatMessage>,
        emit: (AgentEvent) -> Unit
    ) {
        _state.value = AgentState.Thinking(0)

        // Reset loop detection state for new session
        lastRecallIndex = null
        recallRepeatCount = 0
        lastActionSignature = null
        sameActionCount = 0
        semanticErrorCounts.clear()

        // Reset scratchpad for new session and set user intent
        scratchpad = SessionScratchpad()
        scratchpad.setUserIntent(userMessage)
        toolExecutor.updateScratchpad(scratchpad)

        SessionLog.log(EventType.AGENT_START, mapOf(
            "userMessage" to userMessage.take(200),
            "provider" to providerName,
            "model" to modelName,
            "supportsVision" to supportsVision.toString()
        ))

        val messages = mutableListOf<ChatMessage>()
        // Include prior conversation context for multi-turn
        messages.addAll(history)
        messages.add(ChatMessage(role = "user", content = userMessage, hasContext = true))

        val completed = runAgentLoopBody(messages, engine, maxIterations, 0, 0, null, 0, emit)

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
                runAgentLoopBody(messages, engine, maxIterations, 0, maxIterations, null, 0, emit)
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
        engine: BrowserEngine,
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

            // ── N2: Pre-turn token check + compaction ────────────────────
            contextCompactor?.let { compactor ->
                val estimatedTokens = compactor.estimateTokens(messages)
                val budgetLimit = (ESTIMATED_CONTEXT_TOKEN_BUDGET * (1 - CONTEXT_RESERVE_RATIO)).toInt()
                if (estimatedTokens > budgetLimit) {
                    Log.i(TAG, "Pre-turn compaction: $estimatedTokens tokens > $budgetLimit budget (iteration $iteration)")
                    SessionLog.log(EventType.AGENT_START, mapOf(
                        "what" to "context_compaction",
                        "estimatedTokens" to estimatedTokens.toString(),
                        "budget" to budgetLimit.toString(),
                        "iteration" to iteration.toString()
                    ))
                    val compacted = compactor.compact(messages)
                    if (compacted) {
                        emit(AgentEvent.TextResponse("[Context was compacted to stay within token budget. Continuing...]"))
                    }
                }
            }

            val effectiveTools = if (remainingIterations <= 1) emptyList() else WebViewTools.ALL

            // ── M2: Token budget awareness ──────────────────────────────
            // Inject iteration + token budget info into every iteration
            val budgetInfo = buildString {
                append("[BUDGET: Iteration ${iteration + 1}/$maxIterations")
                append(", remaining: $remainingIterations iterations")
                append("]")
            }
            val budgetWarning = when {
                remainingIterations <= 1 -> "\n[SYSTEM: No more tool calls allowed. Summarize your findings and respond in text only.]"
                remainingIterations == 2 -> "\n[SYSTEM: You have 1 tool call remaining. After this, summarize your findings and respond in text only.]"
                iteration + 1 >= maxIterations - 2 -> "\n$budgetInfo\n[SYSTEM: You are near the iteration limit. Prioritize the most important remaining action, then summarize.]"
                else -> "\n$budgetInfo"
            }

            // Capture URL before any tool execution for URL_CHANGE tracking
            val urlBefore = withContext(Dispatchers.Main) { engine.getUrl() ?: "" }

            // Capture context — screenshot every iteration (Quick mode after 1st for token efficiency)
            // WebView methods MUST run on the main thread
            var context = try {
                val mode = if (iteration == 0) CaptureMode.Standard else CaptureMode.Quick
                val ctx = withContext(Dispatchers.Main) {
                    WebContextBuilder.buildContext(engine, mode)
                }
                // Strip screenshot for non-vision models to avoid API errors
                val stripped = if (!supportsVision && ctx.screenshotBase64.isNotEmpty()) {
                    SessionLog.capture(mode.name, ctx.url, true, screenshotStripped = true)
                    ctx.copy(screenshotBase64 = "", screenshotMimeType = "")
                } else {
                    SessionLog.capture(mode.name, ctx.url, ctx.screenshotBase64.isNotEmpty())
                    ctx
                }
                stripped
            } catch (e: Exception) {
                Log.w(TAG, "Context capture failed, continuing without context", e)
                SessionLog.log(EventType.ERROR, mapOf("what" to "capture_failed", "error" to (e.message ?: "unknown")))
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
            val requestStartMs = System.currentTimeMillis()
            val response = try {
                llmCaller(effectiveMessages, context, effectiveTools)
            } catch (e: Exception) {
                val latencyMs = System.currentTimeMillis() - requestStartMs
                SessionLog.llmError(providerName, modelName, e.message ?: "Unknown LLM error", iteration = iteration)
                emit(AgentEvent.Error("LLM call failed: ${e.message}"))
                _state.value = AgentState.Error(e.message ?: "LLM error")
                return false
            }

            if (response == null) {
                SessionLog.llmError(providerName, modelName, "No response from LLM", iteration = iteration)
                emit(AgentEvent.Error("No response from LLM"))
                _state.value = AgentState.Error("No response")
                return false
            }

            // Process response
            if (response.toolCalls.isNotEmpty()) {
                SessionLog.llmResponse(providerName, hasToolCalls = true,
                    inputTokens = response.inputTokens, outputTokens = response.outputTokens,
                    latencyMs = System.currentTimeMillis() - requestStartMs,
                    model = modelName, iteration = iteration)

                // Add assistant message with tool_calls to history
                messages.add(ChatMessage(
                    role = "assistant",
                    content = response.content.ifBlank { "" },
                    toolCalls = response.toolCalls
                ))

                // Process each tool call
                for (call in response.toolCalls) {
                    // ── M4: Same-action loop detection (ActionSignature) ──
                    val signature = ActionSignature(
                        call.name,
                        call.arguments.getAsJsonPrimitive("selector")?.asString
                            ?: call.arguments.getAsJsonPrimitive("url")?.asString
                    )
                    if (signature == lastActionSignature) {
                        sameActionCount++
                        if (sameActionCount >= MAX_SAME_ACTION_REPEAT) {
                            messages.add(ChatMessage(
                                role = "system",
                                content = "[SYSTEM: You have called ${signature.tool}(${signature.keyParam}) $sameActionCount times with the same result. STOP repeating this action. Try a different approach or report the limitation to the user.]"
                            ))
                            sameActionCount = 0 // reset to allow continuation with different action
                        }
                    } else {
                        lastActionSignature = signature
                        sameActionCount = 0
                    }

                    // Legacy loop detection (kept for full-argument-string match)
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
                    SessionLog.toolCall(call.name, call.arguments.toString(), call.id)
                    val risk = permissionGate.classify(call, engine)

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
                            SessionLog.confirmation(call.id, call.name, details.action, details.riskLevel.name, false)
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
                    SessionLog.stateChange("CheckingPermission", "ExecutingTool", iteration + 1)

                    // Push current URL to undo stack before navigation/submission
                    if (call.name == "navigate" || call.name == "submit_form") {
                        withContext(Dispatchers.Main) {
                            undoStack.push(engine.getUrl() ?: "")
                        }
                    }

                    val result = try {
                        toolExecutor.execute(call, engine)
                    } catch (e: Exception) {
                        ToolResult(call.id, "Error: ${e.message}", isError = true)
                    }

                    // Track URL changes after navigation/submission/eval
                    if (call.name == "navigate" || call.name == "submit_form" || call.name == "eval_js") {
                        val urlAfter = withContext(Dispatchers.Main) { engine.getUrl() ?: "" }
                        if (urlAfter != urlBefore) {
                            SessionLog.urlChange(urlBefore, urlAfter, call.name)
                        }
                    }

                    // Error tracking
                    if (result.isError) {
                        consecutiveErrors++
                        SessionLog.toolResult(call.id, result.output, true)
                        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                            emit(AgentEvent.Error("Too many consecutive errors ($consecutiveErrors)"))
                            _state.value = AgentState.Error("Max errors")
                            return false
                        }
                    } else {
                        consecutiveErrors = 0
                        SessionLog.toolResult(call.id, result.output, false)
                    }

                    // Semantic error tracking: tool executed (isError=false) but result is unusable
                    if (result.semanticError != null) {
                        val count = (semanticErrorCounts[call.name] ?: 0) + 1
                        semanticErrorCounts[call.name] = count
                        if (count >= MAX_SEMANTIC_ERRORS) {
                            messages.add(ChatMessage(
                                role = "system",
                                content = "[SYSTEM: Tool '${call.name}' has produced $count unusable results (semantic errors: ${result.semanticError}). STOP using this tool for this task. Switch to an alternative approach or inform the user of the limitation.]"
                            ))
                            // Reset count to allow one more attempt if user insists
                            semanticErrorCounts[call.name] = 0
                        }
                    } else {
                        // Successful non-semantic result resets the counter for this tool
                        semanticErrorCounts.remove(call.name)
                    }

                    emit(AgentEvent.ToolExecuted(call, result))

                    // Store full result in scratchpad (untruncated)
                    val selector = call.arguments.getAsJsonPrimitive("selector")?.asString
                    // ── N4: Adaptive tool result budget ──────────────────────
                    // Compute budget based on remaining context capacity and iterations
                    val adaptiveBudget = computeToolResultBudget(messages, remainingIterations)
                    val isTruncated = result.output.length > adaptiveBudget
                    val errorType = result.semanticError ?: detectErrorType(result.output) ?: if (result.isError) "error" else null
                    val entry = scratchpad.store(
                        toolName = call.name,
                        selector = selector,
                        rawOutput = result.output,
                        truncated = isTruncated,
                        errorType = errorType
                    )

                    // ── M1: Recall repeat detection ────────────────────────
                    if (call.name == "recall") {
                        val idx = call.arguments.getAsJsonPrimitive("index")?.asInt
                        if (idx == lastRecallIndex) {
                            recallRepeatCount++
                            if (recallRepeatCount >= 2) {
                                messages.add(ChatMessage(
                                    role = "system",
                                    content = "[SYSTEM: You have recalled the same entry (index=$idx) twice. Stop recalling and proceed with your current information, or tell the user what is missing.]"
                                ))
                                // Don't abort — just warn and let the loop continue
                            }
                        } else {
                            lastRecallIndex = idx
                            recallRepeatCount = 0
                        }
                    }

                    // Build context-framed tool result message
                    val framedContent = buildFramedToolResult(
                        callName = call.name,
                        rawOutput = result.output,
                        isError = result.isError,
                        isTruncated = isTruncated,
                        entryIndex = entry.index,
                        userIntent = scratchpad.userIntent,
                        errorType = errorType,
                        budget = adaptiveBudget
                    )

                    // Include tool_call_id for API protocol compliance
                    messages.add(ChatMessage(
                        role = "tool",
                        content = framedContent,
                        hasContext = false,
                        toolCallId = call.id,
                        toolName = call.name
                    ))
                    _state.value = AgentState.Thinking(iteration + 1)
                }
                // Continue loop for next LLM decision
            } else {
                // Text-only response → done
                val latencyMs = System.currentTimeMillis() - requestStartMs
                SessionLog.llmResponse(providerName, hasToolCalls = false,
                    inputTokens = response.inputTokens, outputTokens = response.outputTokens,
                    latencyMs = latencyMs, model = modelName, iteration = iteration)

                if (response.content.isNotBlank()) {
                    messages.add(ChatMessage(
                        role = "assistant",
                        content = response.content
                    ))
                }
                emit(AgentEvent.TextResponse(response.content))
                SessionLog.log(EventType.AGENT_END, mapOf("reason" to "text_response", "iterations" to iteration.toString()))
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
    val hasToolCalls: Boolean = toolCalls.isNotEmpty(),
    val inputTokens: Int? = null,
    val outputTokens: Int? = null
)