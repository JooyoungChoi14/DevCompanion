package com.devcompanion.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/**
 * Concrete implementation of [LlmRepository].
 *
 * Supports Anthropic, OpenAI, Ollama, and Gemini providers via
 * their respective [LlmAdapter] implementations.
 */
class LlmRepositoryImpl(override val provider: LlmProvider) : LlmRepository {

    companion object {
        private const val TAG = "LlmRepositoryImpl"
    }

    /**
     * Token usage from the last completed stream.
     * Null if the provider did not report usage data.
     */
    var lastUsage: TokenUsage? = null
        private set

    /**
     * Send a prompt and collect the full response as a single string.
     *
     * Internally delegates to [stream] and concatenates all [LlmStreamEvent.Token]
     * events. If an [LlmStreamEvent.Error] is received, its message is thrown.
     */
    override suspend fun complete(prompt: String, context: WebContextPacket?): String {
        val messages = listOf(ChatMessage(role = "user", content = prompt))
        val adapter = resolveAdapter()
        val sb = StringBuilder()

        adapter.stream(messages, context).collect { event ->
            when (event) {
                is LlmStreamEvent.Token -> sb.append(event.content)
                is LlmStreamEvent.Error -> throw LlmException(event.message, event.code)
                is LlmStreamEvent.Start -> Unit
                is LlmStreamEvent.Complete -> { lastUsage = event.usage }
                is LlmStreamEvent.ToolCalls -> Unit // Tool calls not used in complete mode
            }
        }

        return sb.toString()
    }

    /**
     * Stream a single-turn prompt, emitting incremental text chunks.
     *
     * Convenience wrapper that converts a single prompt into a one-element
     * message list and delegates to the multi-turn [stream] overload.
     */
    override suspend fun stream(prompt: String, context: WebContextPacket?): Flow<String> {
        val messages = listOf(ChatMessage(role = "user", content = prompt))
        return stream(messages, context)
    }

    /**
     * Stream a multi-turn conversation with history, emitting incremental text chunks.
     *
     * Truncates history to [LlmAdapter.MAX_HISTORY_MESSAGES] and delegates
     * to the provider adapter. Translates internal [LlmStreamEvent]s into
     * raw text strings for the public API surface.
     */
    override suspend fun stream(
        messages: List<ChatMessage>,
        context: WebContextPacket?,
        systemPrompt: String?
    ): Flow<String> {
        val adapter = resolveAdapter()
        val effectivePrompt = systemPrompt ?: LlmAdapter.DEFAULT_SYSTEM_PROMPT

        // Trim to max history to avoid exceeding context limits
        val trimmedMessages = if (messages.size > LlmAdapter.MAX_HISTORY_MESSAGES) {
            messages.takeLast(LlmAdapter.MAX_HISTORY_MESSAGES)
        } else {
            messages
        }

        return flow {
            adapter.stream(trimmedMessages, context, effectivePrompt).collect { event ->
                when (event) {
                    is LlmStreamEvent.Token -> emit(event.content)
                    is LlmStreamEvent.Error -> throw LlmException(event.message, event.code)
                    is LlmStreamEvent.Start -> { /* swallow for public Flow<String> API */ }
                    is LlmStreamEvent.Complete -> { lastUsage = event.usage }
                    is LlmStreamEvent.ToolCalls -> { /* tool calls not exposed in simple stream API */ }
                }
            }
        }
    }

    /**
     * Stream with tool definitions for agent loop.
     *
     * Returns raw [LlmStreamEvent]s including [LlmStreamEvent.ToolCalls]
     * for function calling support.
     */
    suspend fun streamWithTools(
        messages: List<ChatMessage>,
        context: WebContextPacket?,
        systemPrompt: String?,
        tools: List<com.devcompanion.llm.agent.ToolDefinition>
    ): Flow<LlmStreamEvent> {
        val adapter = resolveAdapter()
        val effectivePrompt = systemPrompt ?: LlmAdapter.DEFAULT_SYSTEM_PROMPT

        val trimmedMessages = if (messages.size > LlmAdapter.MAX_HISTORY_MESSAGES) {
            messages.takeLast(LlmAdapter.MAX_HISTORY_MESSAGES)
        } else {
            messages
        }

        return adapter.streamWithTools(trimmedMessages, context, effectivePrompt, tools)
    }

    // ── Adapter resolution ───────────────────────────────────────────

    private fun resolveAdapter(): LlmAdapter {
        return when (provider) {
            is LlmProvider.Anthropic -> AnthropicAdapter(provider)
            is LlmProvider.OpenAi -> OpenAiAdapter(provider)
            is LlmProvider.Ollama -> OllamaAdapter(provider)
            is LlmProvider.Gemini -> GeminiAdapter(provider)
        }
    }
}

/**
 * Exception thrown when an LLM API call fails.
 */
class LlmException(
    message: String,
    val code: Int? = null
) : Exception(message)