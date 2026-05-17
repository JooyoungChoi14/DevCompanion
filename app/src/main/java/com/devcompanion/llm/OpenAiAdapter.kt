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
 * OpenAI Chat Completions API adapter that streams responses via SSE.
 *
 * This adapter:
 * - Builds the request body with optional vision (image_url) content
 * - Opens an SSE connection and parses events using [SseParser]
 * - Maps OpenAI-specific streaming deltas to [LlmStreamEvent] instances
 * - Handles HTTP errors, network timeouts, and JSON parsing failures
 */
class OpenAiAdapter(
    private val provider: LlmProvider.OpenAi,
    private val client: OkHttpClient = LlmAdapter.SHARED_CLIENT
) : LlmAdapter {

    companion object {
        private const val TAG = "OpenAiAdapter"
        private const val MAX_TOKENS = 4096
        // Default model — will be user-configurable in Phase 3.
        private const val DEFAULT_MODEL = "gpt-4o"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val gson = Gson()

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

        val requestBuilder = Request.Builder()
            .url("${provider.baseUrl}/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${provider.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody(JSON_TYPE))

        if (!provider.organization.isNullOrBlank()) {
            requestBuilder.addHeader("OpenAI-Organization", provider.organization)
        }

        val request = requestBuilder.build()
        val call = client.newCall(request)

        return flow {
            val response = call.execute()

            // Accumulator for OpenAI tool_calls streaming (function name + arguments)
            val toolCallAccumulator = mutableMapOf<Int, Pair<String, StringBuilder>>()

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

                var streamCompleted = false

                SseParser.parseFlow(body.source()).collect { event ->
                    val result = mapEvent(event, toolCallAccumulator)
                    if (result != null) {
                        if (result is LlmStreamEvent.Complete) {
                            streamCompleted = true
                        }
                        emit(result)
                    }
                }

                // Only emit a fallback Complete if the stream didn't already send one
                if (!streamCompleted) {
                    emit(LlmStreamEvent.Complete(null))
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
        val apiMessages = mutableListOf<Map<String, Any>>()

        // OpenAI: system prompt is a system role message
        val systemText = systemPrompt ?: LlmAdapter.DEFAULT_SYSTEM_PROMPT
        apiMessages.add(mapOf("role" to "system", "content" to systemText))

        // Capture context in a non-null local for smart casting inside loop
        val ctx = context

        for (msg in messages) {
            val isContextMessage = ctx != null && msg.hasContext && msg.role == "user"

            // Agent-mode tool results stored as role="system" with isToolResult=true
            // Convert to proper tool role for LLM API consumption
            if (msg.isToolResult) {
                apiMessages.add(mapOf(
                    "role" to "tool",
                    "tool_call_id" to (msg.toolCallId ?: msg.id),
                    "content" to msg.content
                ))
                continue
            }

            // Tool result message
            if (msg.role == "tool") {
                apiMessages.add(mapOf(
                    "role" to "tool",
                    "tool_call_id" to (msg.toolCallId ?: msg.id),
                    "content" to msg.content
                ))
                continue
            }

            if (isContextMessage && ctx != null && ctx.screenshotBase64.isNotBlank()) {
                // Multi-modal: image_url + text content
                val contentArray = mutableListOf<Map<String, Any>>()

                contentArray.add(mapOf(
                    "type" to "image_url",
                    "image_url" to mapOf(
                        "url" to "data:${ctx.screenshotMimeType};base64,${ctx.screenshotBase64}",
                        "detail" to "auto"
                    )
                ))

                contentArray.add(mapOf(
                    "type" to "text",
                    "text" to buildPromptWithContext(msg.content, ctx)
                ))

                val msgMap = mutableMapOf<String, Any>(
                    "role" to msg.role,
                    "content" to contentArray
                )

                // Assistant messages with tool_calls
                if (msg.role == "assistant" && msg.toolCalls != null && msg.toolCalls.isNotEmpty()) {
                    msgMap["tool_calls"] = msg.toolCalls.map { tc ->
                        mapOf(
                            "id" to tc.id,
                            "type" to "function",
                            "function" to mapOf(
                                "name" to tc.name,
                                "arguments" to tc.arguments.toString()
                            )
                        )
                    }
                }

                apiMessages.add(msgMap)
            } else if (isContextMessage && ctx != null) {
                // Text-only context (no screenshot — e.g., non-vision model)
                apiMessages.add(mapOf(
                    "role" to msg.role,
                    "content" to buildPromptWithContext(msg.content, ctx)
                ))
            } else {
                // Text-only
                val msgMap = mutableMapOf<String, Any>(
                    "role" to msg.role,
                    "content" to msg.content
                )

                // Assistant messages with tool_calls
                if (msg.role == "assistant" && msg.toolCalls != null && msg.toolCalls.isNotEmpty()) {
                    msgMap["tool_calls"] = msg.toolCalls.map { tc ->
                        mapOf(
                            "id" to tc.id,
                            "type" to "function",
                            "function" to mapOf(
                                "name" to tc.name,
                                "arguments" to tc.arguments.toString()
                            )
                        )
                    }
                }

                apiMessages.add(msgMap)
            }
        }

        val root = JsonObject().apply {
            addProperty("model", provider.model.ifBlank { DEFAULT_MODEL })
            addProperty("stream", true)
            addProperty("max_tokens", MAX_TOKENS)
            add("messages", gson.toJsonTree(apiMessages))

            // Include tools if provided (for agent mode)
            if (tools.isNotEmpty()) {
                val toolsArray = JsonArray()
                for (tool in tools) {
                    val toolObj = JsonObject().apply {
                        addProperty("type", "function")
                        add("function", JsonObject().apply {
                            addProperty("name", tool.name)
                            addProperty("description", tool.description)
                            add("parameters", tool.parameters)
                        })
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

    private fun mapEvent(
        event: SseEvent,
        toolCallAccumulator: MutableMap<Int, Pair<String, StringBuilder>>
    ): LlmStreamEvent? {
        val data = event.data

        return try {
            val json = JsonParser.parseString(data).asJsonObject

            // Check for error response
            val errorObj = json.getAsJsonObject("error")
            if (errorObj != null) {
                val message = errorObj.get("message")?.asString ?: "Unknown API error"
                val code = errorObj.get("code")?.asInt
                return LlmStreamEvent.Error(message, code)
            }

            val choices = json.getAsJsonArray("choices")
            if (choices != null && choices.size() > 0) {
                val choice = choices[0].asJsonObject
                val delta = choice.getAsJsonObject("delta")
                val finishReason = choice.get("finish_reason")?.asString

                // Extract token content from delta
                val content = delta?.get("content")?.asString
                if (content != null) {
                    return LlmStreamEvent.Token(content)
                }

                // OpenAI tool_calls delta — accumulate across chunks
                val toolCallsArray = delta?.getAsJsonArray("tool_calls")
                if (toolCallsArray != null && toolCallsArray.size() > 0) {
                    for (i in 0 until toolCallsArray.size()) {
                        val tcDelta = toolCallsArray[i].asJsonObject
                        val index = tcDelta.get("index")?.asInt ?: i
                        val fn = tcDelta.getAsJsonObject("function")
                        val name = fn?.get("name")?.asString
                        val args = fn?.get("arguments")?.asString

                        val existing = toolCallAccumulator[index]
                        if (name != null) {
                            // New tool call or name update
                            toolCallAccumulator[index] = name to (existing?.second ?: StringBuilder())
                        }
                        if (args != null && toolCallAccumulator.containsKey(index)) {
                            toolCallAccumulator[index]!!.second.append(args)
                        }
                    }
                }

                // Role-only delta at stream start — extract message ID if present
                val role = delta?.get("role")?.asString
                if (role != null) {
                    val messageId = json.get("id")?.asString ?: ""
                    return LlmStreamEvent.Start(messageId)
                }

                // Stream complete — only emit if finish_reason is present
                if (finishReason != null) {
                    // If finish_reason is "tool_calls", emit accumulated tool calls
                    if (finishReason == "tool_calls") {
                        val calls = toolCallAccumulator.entries.sortedBy { it.key }.map { (index, acc) ->
                            val args = try {
                                JsonParser.parseString(acc.second.toString()).asJsonObject
                            } catch (_: Exception) {
                                JsonObject().apply { addProperty("_raw", acc.second.toString()) }
                            }
                            ToolCall(
                                id = "call_${index}",
                                name = acc.first,
                                arguments = args
                            )
                        }
                        if (calls.isNotEmpty()) {
                            return LlmStreamEvent.ToolCalls(calls)
                        }
                    }

                    val usageObj = json.getAsJsonObject("usage")
                    val usage = if (usageObj != null) {
                        TokenUsage(
                            inputTokens = usageObj.get("prompt_tokens")?.asInt ?: 0,
                            outputTokens = usageObj.get("completion_tokens")?.asInt ?: 0
                        )
                    } else null
                    return LlmStreamEvent.Complete(usage)
                }
            }

            null // Unrecognized event — skip
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse SSE event: $data", e)
            null
        }
    }

    // ── Error parsing ────────────────────────────────────────────────

    private fun parseHttpError(body: String, statusCode: Int): Pair<String, Int?> {
        return try {
            val json = JsonParser.parseString(body).asJsonObject
            val errorObj = json.getAsJsonObject("error")
            val message = errorObj?.get("message")?.asString ?: "HTTP $statusCode"
            val code = errorObj?.get("code")?.asInt ?: statusCode
            message to code
        } catch (_: Exception) {
            "HTTP $statusCode" to statusCode
        }
    }
}