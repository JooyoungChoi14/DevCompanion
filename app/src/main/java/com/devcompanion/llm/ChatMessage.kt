package com.devcompanion.llm

import java.util.UUID

/**
 * Represents a single message in the AI chat conversation.
 *
 * Used both for UI display and for constructing multi-turn LLM requests.
 * Each adapter converts [ChatMessage] instances into provider-specific
 * message formats (e.g., Gemini's `contents` with `role: "model"`).
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String, // "user", "assistant", "system"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val hasContext: Boolean = false, // true if this message included web context
    val tokenUsage: TokenUsage? = null, // token usage for assistant responses
    val toolCalls: List<com.devcompanion.llm.agent.ToolCall>? = null, // tool calls from assistant
    val toolCallId: String? = null, // required for tool role messages (OpenAI/Anthropic)
    val toolName: String? = null, // function name for tool role messages (Gemini uses name, not id)
    val isToolResult: Boolean = false, // true for system messages that are tool execution results
    val isError: Boolean = false // true if tool execution resulted in error
)