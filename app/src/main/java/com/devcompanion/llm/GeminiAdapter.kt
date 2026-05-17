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
 * Google Gemini API adapter that streams responses via SSE.
 *
 * Gemini supports two authentication methods:
 * - `x-goog-api-key` header (recommended — key not exposed in URL/logs)
 * - `?key=` URL parameter (legacy — avoid due to log/Referer leakage)
 *
 * This adapter uses the header method for security.
 * Streaming is enabled via the `?alt=sse` query parameter.
 * Response events carry incremental text in
 * `candidates[0].content.parts[0].text`.
 *
 * This adapter:
 * - Builds the request body with optional vision (inline_data) parts
 * - Opens an SSE connection and parses events using [SseParser]
 * - Maps Gemini-specific fields to [LlmStreamEvent] instances
 * - Handles HTTP errors, network timeouts, and JSON parsing failures
 */
class GeminiAdapter(
    private val provider: LlmProvider.Gemini,
    private val client: OkHttpClient = LlmAdapter.SHARED_CLIENT
) : LlmAdapter {

    companion object {
        private const val TAG = "GeminiAdapter"
        private const val MAX_OUTPUT_TOKENS = 4096
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

        // Use header-based auth to avoid exposing the API key in URLs,
        // server logs, and Referer headers.
        val url = "${provider.baseUrl}/models/${provider.model}:streamGenerateContent?alt=sse"

        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", provider.apiKey)
            .addHeader("Content-Type", "application/json")
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

                var streamCompleted = false

                SseParser.parseFlow(body.source()).collect { event ->
                    val result = mapEvent(event)
                    if (result != null) {
                        if (result is LlmStreamEvent.Complete) {
                            streamCompleted = true
                        }
                        emit(result)
                    }
                }

                // If stream ended without an explicit Complete, emit one
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
        // Gemini: uses "contents" array with "role": "user" | "model" (not "assistant")
        // and "parts" arrays. System prompt goes in "systemInstruction".
        val apiContents = mutableListOf<Map<String, Any>>()

        // Capture context in a non-null local for smart casting inside loop
        val ctx = context

        for (msg in messages) {
            // Gemini uses "model" instead of "assistant"
            val geminiRole = when (msg.role) {
                "assistant" -> "model"
                "tool" -> "function" // Gemini uses "function" role for tool results
                else -> msg.role
            }

            val isContextMessage = ctx != null && msg.hasContext && msg.role == "user"

            // Agent-mode tool results stored as role="system" with isToolResult=true
            // Convert to proper function response for Gemini API
            if (msg.isToolResult) {
                apiContents.add(mapOf(
                    "role" to "function",
                    "parts" to listOf(mapOf(
                        "functionResponse" to mapOf(
                            "name" to (msg.toolName ?: msg.toolCallId ?: msg.id),
                            "response" to mapOf("result" to msg.content)
                        )
                    ))
                ))
                continue
            }

            // Tool result message — Gemini function response
            if (msg.role == "tool") {
                apiContents.add(mapOf(
                    "role" to "function",
                    "parts" to listOf(mapOf(
                        "functionResponse" to mapOf(
                            "name" to (msg.toolName ?: msg.toolCallId ?: msg.id),
                            "response" to mapOf("result" to msg.content)
                        )
                    ))
                ))
                continue
            }

            // Assistant message with tool calls
            if (msg.role == "assistant" && msg.toolCalls != null && msg.toolCalls.isNotEmpty()) {
                val parts = mutableListOf<Map<String, Any>>()
                if (msg.content.isNotBlank()) {
                    parts.add(mapOf("text" to msg.content))
                }
                for (tc in msg.toolCalls) {
                    parts.add(mapOf(
                        "functionCall" to mapOf(
                            "name" to tc.name,
                            "args" to tc.arguments.toString()
                        )
                    ))
                }
                apiContents.add(mapOf(
                    "role" to "model",
                    "parts" to parts
                ))
                continue
            }

            if (isContextMessage && ctx != null && ctx.screenshotBase64.isNotBlank()) {
                // Multi-modal: inline_data + text parts
                val parts = mutableListOf<Map<String, Any>>()

                parts.add(mapOf(
                    "inline_data" to mapOf(
                        "mime_type" to ctx.screenshotMimeType,
                        "data" to ctx.screenshotBase64
                    )
                ))

                parts.add(mapOf(
                    "text" to buildPromptWithContext(msg.content, ctx)
                ))

                apiContents.add(mapOf(
                    "role" to geminiRole,
                    "parts" to parts
                ))
            } else if (isContextMessage && ctx != null) {
                // Text-only context (no screenshot)
                apiContents.add(mapOf(
                    "role" to geminiRole,
                    "parts" to listOf(mapOf("text" to buildPromptWithContext(msg.content, ctx)))
                ))
            } else {
                // Text-only
                apiContents.add(mapOf(
                    "role" to geminiRole,
                    "parts" to listOf(mapOf("text" to msg.content))
                ))
            }
        }

        val root = JsonObject().apply {
            add("contents", gson.toJsonTree(apiContents))
            // System instruction as a separate top-level field
            val systemText = systemPrompt ?: LlmAdapter.DEFAULT_SYSTEM_PROMPT
            add("systemInstruction", JsonObject().apply {
                add("parts", gson.toJsonTree(listOf(mapOf("text" to systemText))))
            })
            add("generationConfig", JsonObject().apply {
                addProperty("maxOutputTokens", MAX_OUTPUT_TOKENS)
            })

            // Include tools if provided (for agent mode)
            if (tools.isNotEmpty()) {
                val toolsArray = JsonArray()
                for (tool in tools) {
                    val toolObj = JsonObject().apply {
                        add("functionDeclarations", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("name", tool.name)
                                addProperty("description", tool.description)
                                add("parameters", tool.parameters)
                            })
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

    private fun mapEvent(event: SseEvent): LlmStreamEvent? {
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

            val candidates = json.getAsJsonArray("candidates")
            if (candidates != null && candidates.size() > 0) {
                val candidate = candidates[0].asJsonObject
                val content = candidate.getAsJsonObject("content")
                val parts = content?.getAsJsonArray("parts")

                // Check for function call parts (Gemini tool use)
                if (parts != null && parts.size() > 0) {
                    val toolCalls = mutableListOf<ToolCall>()
                    var hasText = false

                    for (i in 0 until parts.size()) {
                        val part = parts[i].asJsonObject

                        // Text part
                        val text = part.get("text")?.asString
                        if (text != null && text.isNotEmpty()) {
                            hasText = true
                            // Will emit as Token below if no function calls
                        }

                        // Function call part
                        val funcCall = part.getAsJsonObject("functionCall")
                        if (funcCall != null) {
                            val name = funcCall.get("name")?.asString ?: ""
                            val args = funcCall.getAsJsonObject("args")
                                ?: funcCall.get("args")?.asJsonObject
                                ?: JsonObject()
                            toolCalls.add(ToolCall(
                                id = "call_$i",
                                name = name,
                                arguments = args
                            ))
                        }
                    }

                    // If we have tool calls, emit them
                    if (toolCalls.isNotEmpty()) {
                        return LlmStreamEvent.ToolCalls(toolCalls)
                    }

                    // Otherwise emit text token
                    if (hasText) {
                        val text = parts[0].asJsonObject.get("text")?.asString ?: ""
                        if (text.isNotEmpty()) {
                            return LlmStreamEvent.Token(text)
                        }
                    }
                }

                // Check finish reason
                val finishReason = candidate.get("finishReason")?.asString
                if (finishReason == "STOP") {
                    // Extract usage metadata if present
                    val usageMetadata = json.getAsJsonObject("usageMetadata")
                    val usage = if (usageMetadata != null) {
                        TokenUsage(
                            inputTokens = usageMetadata.get("promptTokenCount")?.asInt ?: 0,
                            outputTokens = usageMetadata.get("candidatesTokenCount")?.asInt ?: 0
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