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

    fun stream(
        messages: List<ChatMessage>,
        context: WebContextPacket? = null,
        systemPrompt: String? = null
    ): Flow<LlmStreamEvent>

    fun streamWithTools(
        messages: List<ChatMessage>,
        context: WebContextPacket? = null,
        systemPrompt: String? = null,
        tools: List<com.devcompanion.llm.agent.ToolDefinition> = emptyList()
    ): Flow<LlmStreamEvent> {
        return stream(messages, context, systemPrompt)
    }

    companion object {
        const val DEFAULT_SYSTEM_PROMPT = "You are a helpful web development assistant. Analyze the provided web context and answer questions about the webpage. You have tools to inspect and interact with the page: get_dom (inspect HTML), get_computed_style (check CSS), set_style (apply CSS fixes), eval_js (run JavaScript), screenshot (capture the page), and more. Use them to verify your work and debug issues."

        const val MAX_HISTORY_MESSAGES = 20

        /**
         * Shared OkHttpClient instance used by all adapters.
         *
         * Timeout strategy:
         * - connectTimeout: 30s — connection establishment
         * - readTimeout: 60s — time between any two data chunks in a streaming response.
         *   Reduced from 120s because SSE streaming sends tokens incrementally;
         *   if no chunk arrives within 60s the provider is effectively dead.
         *   Agent loop also wraps LLM calls with coroutine withTimeout(60s) as a
         *   safety net.
         */
        internal val SHARED_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}