package com.devcompanion.llm

/**
 * Abstraction for LLM API interactions.
 *
 * Phase 1A defines the interface; implementation lives in Phase 1B.
 * The repository is responsible for:
 * - Sending messages (with optional image attachments) to the configured provider
 * - Streaming response tokens back to the caller
 * - Handling provider-specific request/response formatting
 */
interface LlmRepository {

    /** The currently active provider configuration. */
    val provider: LlmProvider

    /**
     * Send a text prompt and receive a complete response.
     *
     * Convenience method that wraps [stream] with a single user message.
     *
     * @param prompt  The user message / system prompt text.
     * @param context Optional web context packet providing page state (screenshot, DOM, etc.).
     * @return The full response text from the LLM.
     */
    suspend fun complete(prompt: String, context: WebContextPacket? = null): String

    /**
     * Send a text prompt and stream response chunks as they arrive.
     *
     * Convenience method for single-turn queries without history.
     *
     * @param prompt  The user message / system prompt text.
     * @param context Optional web context packet.
     * @return A cold [kotlinx.coroutines.flow.Flow] of incremental text chunks.
     */
    suspend fun stream(
        prompt: String,
        context: WebContextPacket? = null
    ): kotlinx.coroutines.flow.Flow<String>

    /**
     * Stream a multi-turn conversation with history and optional system prompt.
     *
     * @param messages      Ordered list of conversation messages (user + assistant turns).
     *                      The last message should be the current user query.
     *                      Only the most recent [LlmAdapter.MAX_HISTORY_MESSAGES] are sent.
     * @param context       Optional web context packet. When provided, the screenshot
     *                      is attached to the last user message that has [ChatMessage.hasContext].
     * @param systemPrompt  Optional system prompt. Defaults to
     *                      [LlmAdapter.DEFAULT_SYSTEM_PROMPT] when null.
     * @return A cold [kotlinx.coroutines.flow.Flow] of incremental text chunks.
     */
    suspend fun stream(
        messages: List<ChatMessage>,
        context: WebContextPacket? = null,
        systemPrompt: String? = null
    ): kotlinx.coroutines.flow.Flow<String>
}