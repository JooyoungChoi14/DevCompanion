package com.devcompanion.llm

import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Common interface for LLM provider adapters.
 *
 * Each adapter wraps a specific provider API (Anthropic, OpenAI, Ollama, Gemini)
 * and exposes a unified [stream] method that emits [LlmStreamEvent]s.
 */
interface LlmAdapter {

    /**
     * Stream a response from the LLM provider with multi-turn conversation history.
     *
     * @param messages    Ordered list of conversation messages (user + assistant turns).
     *                    Adapters convert these into provider-specific message formats.
     * @param context     Optional web context with screenshot for vision-enabled models.
     *                    When present, the screenshot is attached to the last user message.
     * @param systemPrompt Optional system prompt. Anthropic places it in the top-level
     *                    `system` field; OpenAI/Ollama use a `system` role message;
     *                    Gemini uses the `systemInstruction` field.
     * @return A cold [Flow] of [LlmStreamEvent] instances.
     */
    fun stream(
        messages: List<ChatMessage>,
        context: WebContextPacket? = null,
        systemPrompt: String? = null
    ): Flow<LlmStreamEvent>

    /**
     * Stream a response with tool definitions for function calling.
     *
     * When tools are provided, the LLM may respond with tool_call requests
     * instead of (or in addition to) text content. The caller is responsible
     * for executing tool calls and feeding results back.
     *
     * @param messages    Conversation history including tool results (role="tool").
     * @param context     Optional web context.
     * @param systemPrompt Optional system prompt.
     * @param tools       Tool definitions for the LLM to call.
     * @return A cold [Flow] of [LlmStreamEvent] instances, including [LlmStreamEvent.ToolCalls].
     */
    fun streamWithTools(
        messages: List<ChatMessage>,
        context: WebContextPacket? = null,
        systemPrompt: String? = null,
        tools: List<com.devcompanion.llm.agent.ToolDefinition> = emptyList()
    ): Flow<LlmStreamEvent> {
        // Default: delegate to stream() without tools (no tool calling support)
        return stream(messages, context, systemPrompt)
    }

    companion object {
        /** Default system prompt for web development assistance. */
        const val DEFAULT_SYSTEM_PROMPT = "You are a helpful web development assistant. Analyze the provided web context and answer questions about the webpage. You have tools to inspect and interact with the page: get_dom (inspect HTML), get_computed_style (check CSS), set_style (apply CSS fixes), eval_js (run JavaScript), screenshot (capture the page), and more. Use them to verify your work and debug issues."

        /**
         * Maximum number of historical messages to include in a request.
         * Limits context window usage while preserving recent conversation flow.
         */
        const val MAX_HISTORY_MESSAGES = 20

        /**
         * shared OkHttpClient instance used by all adapters.
         * Configured with generous timeouts for streaming LLM responses.
         */
        internal val SHARED_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
        }
    }
}