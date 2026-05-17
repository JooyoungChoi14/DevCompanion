package com.devcompanion.llm

import android.util.Log
import com.devcompanion.llm.agent.ToolCall
import com.devcompanion.llm.agent.ToolDefinition
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

/**
 * Anthropic Messages API adapter that streams responses via SSE.
 *
 * This adapter:
 * - Builds the request body with optional vision (image) content blocks
 * - Opens an SSE connection and parses events using [SseParser]
 * - Maps Anthropic-specific events to [LlmStreamEvent] instances
 * - Handles HTTP errors, network timeouts, and JSON parsing failures
 */
class AnthropicAdapter(
    private val provider: LlmProvider.Anthropic,
    private val client: OkHttpClient = LlmAdapter.SHARED_CLIENT
) : LlmAdapter {

    companion object {
        private const val TAG = "AnthropicAdapter"
        private const val MAX_TOKENS = 4096
        // Default model used when no provider.model is specified.
        // Anthropic does not expose model selection in the current UI;
        // this constant will be replaced by a user-configurable field in Phase 3.
        private const val DEFAULT_MODEL = "claude-sonnet-4-20250514"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val gson = Gson()

    /**
     * Stream an Anthropic Messages API response, emitting [LlmStreamEvent]s.
     *
     * If [context] is provided, the screenshot will be attached as an image
     * content block to the last user message that has [ChatMessage.hasContext].
     */
    override fun stream(
        messages: List<ChatMessage>,
        context: WebContextPacket?,
        systemPrompt: String?
    ): Flow<LlmStreamEvent> = streamWithTools(messages, context, systemPrompt, emptyList())

    override fun streamWithTools(
        messages: List<ChatMessage>,
        context: WebContextPacket?,
        systemPrompt: String?,
        tools: List<ToolDefinition>
    ): Flow<LlmStreamEvent> {
        val requestBody = buildRequestBody(messages, context, systemPrompt, tools)

        val request = Request.Builder()
            .url("${provider.baseUrl}/v1/messages")
            .addHeader("x-api-key", provider.apiKey)
            .addHeader("anthropic-version", provider.version)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(requestBody.toRequestBody(JSON_TYPE))
            .build()

        val call = client.newCall(request)

        return flow {
            val response = call.execute()

            try {
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    val (message, code) = parseHttpError(errorBody, response.code)
                    emit(LlmStreamEvent.Error(message, code))
                    return@flow
                }

                val body = response.body ?: run {
                    emit(LlmStreamEvent.Error("Empty response body", null))
                    return@flow
                }

                val source = body.source()
                var pendingUsage: TokenUsage? = null

                // Accumulator for Anthropic tool_use streaming
                val toolCallAccumulator = mutableMapOf<Int, Triple<String, String, StringBuilder>>()

                SseParser.parseFlow(source).collect { event ->
                    val result = mapEvent(event, pendingUsage, toolCallAccumulator)
                    when (result) {
                        is EventResult.Emit -> {
                            emit(result.event)
                            if (result.event is LlmStreamEvent.Complete) pendingUsage = null
                        }
                        is EventResult.UpdateUsage -> pendingUsage = result.usage
                        is EventResult.EmitComplete -> {
                            emit(result.event)
                            pendingUsage = null
                        }
                        is EventResult.Ignore -> { /* no-op */ }
                    }
                }

                // If stream ended without an explicit Complete, emit one
                // with any pending usage data

                // Emit accumulated tool calls if any
                if (toolCallAccumulator.isNotEmpty()) {
                    val calls = toolCallAccumulator.entries.sortedBy { it.key }.map { (_, triple) ->
                        val args = try {
                            JsonParser.parseString(triple.third.toString()).asJsonObject
                        } catch (_: Exception) {
                            JsonObject().apply { addProperty("_raw", triple.third.toString()) }
                        }
                        ToolCall(
                            id = triple.first,
                            name = triple.second,
                            arguments = args
                        )
                    }
                    emit(LlmStreamEvent.ToolCalls(calls))
                }

                if (pendingUsage != null) {
                    emit(LlmStreamEvent.Complete(pendingUsage))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Stream error", e)
                emit(LlmStreamEvent.Error(e.message ?: "Unknown error", null))
            } finally {
                response.close()
            }
        }.flowOn(Dispatchers.IO)
    }

    // ── Request building ─────────────────────────────────────────────

    private fun buildRequestBody(
        messages: List<ChatMessage>,
        context: WebContextPacket?,
        systemPrompt: String?,
        tools: List<ToolDefinition> = emptyList()
    ): String {
        // Anthropic: system prompt goes in the top-level "system" field,
        // not as a system role message inside the messages array.
        val systemText = systemPrompt ?: LlmAdapter.DEFAULT_SYSTEM_PROMPT
        // Capture context in a non-null local for smart casting inside lambda
        val ctx = context

        val apiMessages = messages.map { msg ->
            // Find the user message that carries web context
            val isContextMessage = ctx != null && msg.hasContext && msg.role == "user"

            // Agent-mode tool results stored as role="system" with isToolResult=true
            // Convert to proper tool role for LLM API consumption
            if (msg.isToolResult) {
                val toolResultBlock = JsonObject().apply {
                    addProperty("type", "tool_result")
                    addProperty("tool_use_id", msg.toolCallId ?: msg.id)
                    addProperty("content", msg.content)
                }
                mapOf("role" to "user", "content" to listOf(toolResultBlock))
            } else if (msg.role == "tool") {
                // Tool result message — Anthropic uses role=user with tool_result content block
                val toolResultBlock = JsonObject().apply {
                    addProperty("type", "tool_result")
                    addProperty("tool_use_id", msg.toolCallId ?: msg.id)
                    addProperty("content", msg.content)
                }
                mapOf("role" to "user", "content" to listOf(toolResultBlock))
            } else if (isContextMessage && ctx != null && ctx.screenshotBase64.isNotBlank()) {
                // Multi-modal: image + text content blocks
                val imageBlock = JsonObject().apply {
                    addProperty("type", "image")
                    add("source", JsonObject().apply {
                        addProperty("type", "base64")
                        addProperty("media_type", ctx.screenshotMimeType)
                        addProperty("data", ctx.screenshotBase64)
                    })
                }
                val textBlock = JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", buildPromptWithContext(msg.content, ctx))
                }
                mapOf("role" to msg.role, "content" to listOf(imageBlock, textBlock))
            } else if (isContextMessage && ctx != null) {
                // Text-only context (no screenshot — e.g., non-vision model)
                mapOf("role" to msg.role, "content" to buildPromptWithContext(msg.content, ctx))
            } else if (msg.role == "assistant" && msg.toolCalls != null && msg.toolCalls.isNotEmpty()) {
                // Assistant message with tool_use content blocks (Anthropic format)
                val contentBlocks = mutableListOf<Any>()
                // Text content if present
                if (msg.content.isNotBlank()) {
                    contentBlocks.add(mapOf("type" to "text", "text" to msg.content))
                }
                // tool_use blocks
                for (tc in msg.toolCalls) {
                    contentBlocks.add(mapOf(
                        "type" to "tool_use",
                        "id" to tc.id,
                        "name" to tc.name,
                        "input" to tc.arguments.toString()
                    ))
                }
                mapOf("role" to "assistant", "content" to contentBlocks)
            } else {
                // Text-only content
                mapOf("role" to msg.role, "content" to msg.content)
            }
        }

        val root = JsonObject().apply {
            addProperty("model", provider.model.ifBlank { DEFAULT_MODEL })
            addProperty("max_tokens", MAX_TOKENS)
            addProperty("stream", true)
            addProperty("system", systemText)
            add("messages", gson.toJsonTree(apiMessages))

            // Include tools if provided (for agent mode)
            if (tools.isNotEmpty()) {
                val toolsArray = JsonArray()
                for (tool in tools) {
                    val toolObj = JsonObject().apply {
                        addProperty("name", tool.name)
                        addProperty("description", tool.description)
                        add("input_schema", tool.parameters)
                    }
                    toolsArray.add(toolObj)
                }
                add("tools", toolsArray)
            }
        }

        return gson.toJson(root)
    }

    /**
     * Prefix the prompt with contextual metadata from the web page.
     * Includes DOM snapshot and computed styles when available.
     */
    private fun buildPromptWithContext(prompt: String, context: WebContextPacket): String {
        return buildString {
            appendLine("[Web Context]")
            appendLine("URL: ${context.url}")
            appendLine("Title: ${context.title}")
            if (context.domSnapshot.isNotBlank()) {
                appendLine("DOM:")
                appendLine(context.domSnapshot)
            }
            if (context.computedStyles.isNotBlank()) {
                appendLine("Styles:")
                appendLine(context.computedStyles)
            }
            appendLine()
            appendLine(prompt)
        }
    }

    // ── Event mapping ────────────────────────────────────────────────

    private sealed class EventResult {
        data class Emit(val event: LlmStreamEvent) : EventResult()
        data class UpdateUsage(val usage: TokenUsage) : EventResult()
        data class EmitComplete(val event: LlmStreamEvent.Complete) : EventResult()
        data object Ignore : EventResult()
    }

    private fun mapEvent(
        event: SseEvent,
        pendingUsage: TokenUsage?,
        toolCallAccumulator: MutableMap<Int, Triple<String, String, StringBuilder>>
    ): EventResult {
        val eventName = event.name
        val data = event.data

        return try {
            val json = JsonParser.parseString(data).asJsonObject

            when (eventName) {
                "message_start" -> {
                    val messageObj = json.getAsJsonObject("message")
                    val messageId = messageObj?.get("id")?.asString ?: ""
                    EventResult.Emit(LlmStreamEvent.Start(messageId))
                }

                "content_block_start" -> {
                    val block = json.getAsJsonObject("content_block")
                    val blockType = block?.get("type")?.asString
                    if (blockType == "tool_use") {
                        val index = json.get("index")?.asInt ?: 0
                        val id = block?.get("id")?.asString ?: ""
                        val name = block?.get("name")?.asString ?: ""
                        toolCallAccumulator[index] = Triple(id, name, StringBuilder())
                    }
                    EventResult.Ignore
                }

                "content_block_delta" -> {
                    val delta = json.getAsJsonObject("delta")
                    val deltaType = delta?.get("type")?.asString
                    if (deltaType == "text_delta") {
                        val text = delta.get("text")?.asString ?: ""
                        EventResult.Emit(LlmStreamEvent.Token(text))
                    } else if (deltaType == "input_json_delta") {
                        // Tool call input streaming — accumulate partial JSON
                        val index = json.get("index")?.asInt ?: 0
                        val partialJson = delta.get("partial_json")?.asString ?: ""
                        val existing = toolCallAccumulator[index]
                        if (existing != null) {
                            toolCallAccumulator[index] = Triple(
                                existing.first, existing.second,
                                existing.third.append(partialJson)
                            )
                        }
                        EventResult.Ignore
                    } else {
                        EventResult.Ignore
                    }
                }

                "message_delta" -> {
                    // Extract usage info; will be attached to the Complete event
                    val usageObj = json.getAsJsonObject("usage")
                    val outputTokens = usageObj?.get("output_tokens")?.asInt ?: 0
                    // input_tokens may have been set in message_start; prefer the
                    // accumulated usage
                    val inputTokens = usageObj?.get("input_tokens")?.asInt
                        ?: pendingUsage?.inputTokens ?: 0
                    EventResult.UpdateUsage(TokenUsage(inputTokens, outputTokens))
                }

                "message_stop" -> {
                    EventResult.EmitComplete(LlmStreamEvent.Complete(pendingUsage))
                }

                "error" -> {
                    val errorObj = json.getAsJsonObject("error")
                    val message = errorObj?.get("message")?.asString ?: "Unknown API error"
                    val code = errorObj?.get("status")?.asInt
                    EventResult.Emit(LlmStreamEvent.Error(message, code))
                }

                "ping" -> EventResult.Ignore

                else -> EventResult.Ignore
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse SSE event: $data", e)
            EventResult.Ignore
        }
    }

    // ── Error parsing ────────────────────────────────────────────────

    private fun parseHttpError(body: String, statusCode: Int): Pair<String, Int?> {
        return try {
            val json = JsonParser.parseString(body).asJsonObject
            val errorObj = json.getAsJsonObject("error")
            val message = errorObj?.get("message")?.asString ?: "HTTP $statusCode"
            val code = errorObj?.get("status")?.asInt ?: statusCode
            message to code
        } catch (_: Exception) {
            "HTTP $statusCode" to statusCode
        }
    }
}