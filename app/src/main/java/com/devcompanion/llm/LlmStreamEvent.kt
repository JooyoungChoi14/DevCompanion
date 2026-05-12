package com.devcompanion.llm

/**
 * Sealed class representing streaming events from an LLM API.
 *
 * Events are emitted in order: Start → Token* → Complete (or Error).
 * The [Token] events carry incremental text that should be concatenated
 * by the consumer to build the full response.
 */
sealed class LlmStreamEvent {

    /** Emitted when the stream begins, carrying the provider-assigned message ID. */
    data class Start(val messageId: String) : LlmStreamEvent()

    /** Emitted for each incremental text chunk from the model. */
    data class Token(val content: String) : LlmStreamEvent()

    /** Emitted when the model requests one or more tool calls. */
    data class ToolCalls(val calls: List<com.devcompanion.llm.agent.ToolCall>) : LlmStreamEvent()

    /** Emitted when an error occurs during streaming. */
    data class Error(val message: String, val code: Int? = null) : LlmStreamEvent()

    /** Emitted when the stream completes successfully. */
    data class Complete(val usage: TokenUsage?) : LlmStreamEvent()
}

/**
 * Token usage statistics reported by the provider upon completion.
 */
data class TokenUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0
)