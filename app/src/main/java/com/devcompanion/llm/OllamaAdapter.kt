package com.devcompanion.llm

import android.util.Log
import com.devcompanion.llm.agent.ToolCall
import com.devcompanion.llm.agent.ToolDefinition
import com.google.gson.Gson
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
 * Ollama Chat API adapter that streams responses via NDJSON.
 *
 * Ollama uses newline-delimited JSON (NDJSON) streaming rather than SSE.
 * Each line is a complete JSON object with incremental content in the
 * `message.content` field and a `done` flag for stream termination.
 *
 * This adapter:
 * - Builds the request body with optional vision (images) content
 * - Reads NDJSON lines from the response body
 * - Maps Ollama-specific fields to [LlmStreamEvent] instances
 * - Handles HTTP errors, network timeouts, and JSON parsing failures
 */
class OllamaAdapter(
    private val provider: LlmProvider.Ollama,
    private val client: OkHttpClient = LlmAdapter.SHARED_CLIENT
) : LlmAdapter {

    companion object {
        private const val TAG = "OllamaAdapter"
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
            .url("${provider.baseUrl}/api/chat")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody(JSON_TYPE))

        if (provider.apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer ${provider.apiKey}")
        }

        val request = requestBuilder.build()
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
                var streamStarted = false

                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue

                    val json = try {
                        JsonParser.parseString(line).asJsonObject
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse NDJSON line: $line", e)
                        continue
                    }

                    // Check for error response
                    val errorObj = json.getAsJsonObject("error")
                    if (errorObj != null) {
                        val message = errorObj.get("message")?.asString ?: "Unknown API error"
                        emit(LlmStreamEvent.Error(message, null))
                        return@flow
                    }

                    val done = json.get("done")?.asBoolean ?: false

                    if (done) {
                        // Stream complete — extract usage if present
                        val usage = extractUsage(json)
                        emit(LlmStreamEvent.Complete(usage))
                        break
                    }

                    // Extract token content
                    val messageObj = json.getAsJsonObject("message")
                    val content = messageObj?.get("content")?.asString

                    // Extract tool calls if present (Ollama tool calling format)
                    val toolCallsArray = messageObj?.getAsJsonArray("tool_calls")
                    if (toolCallsArray != null && toolCallsArray.size() > 0) {
                        val calls = mutableListOf<ToolCall>()
                        for (i in 0 until toolCallsArray.size()) {
                            val tcObj = toolCallsArray[i].asJsonObject
                            val fn = tcObj.getAsJsonObject("function")
                            val name = fn.get("name")?.asString ?: continue
                            val args = try {
                                fn.get("arguments")?.asJsonObject ?: JsonObject()
                            } catch (_: Exception) {
                                // arguments may be a string that needs parsing
                                val argsStr = fn.get("arguments")?.asString ?: "{}"
                                try { JsonParser.parseString(argsStr).asJsonObject } catch (_: Exception) { JsonObject() }
                            }
                            val id = tcObj.get("id")?.asString ?: "call_${System.currentTimeMillis()}_$i"
                            calls.add(ToolCall(id = id, name = name, arguments = args))
                        }
                        if (calls.isNotEmpty()) {
                            if (!streamStarted) {
                                emit(LlmStreamEvent.Start(""))
                                streamStarted = true
                            }
                            emit(LlmStreamEvent.ToolCalls(calls))
                        }
                    }

                    if (!content.isNullOrBlank()) {
                        if (!streamStarted) {
                            emit(LlmStreamEvent.Start(""))
                            streamStarted = true
                        }
                        emit(LlmStreamEvent.Token(content))
                    }
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
        val apiMessages = mutableListOf<JsonObject>()

        // Ollama: system prompt as a system role message (same format as OpenAI)
        val systemText = systemPrompt ?: LlmAdapter.DEFAULT_SYSTEM_PROMPT
        apiMessages.add(JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", systemText)
        })

        // Capture context in a non-null local for smart casting inside loop
        val ctx = context

        for (msg in messages) {
            val isContextMessage = ctx != null && msg.hasContext && msg.role == "user"

            if (msg.role == "tool") {
                // Tool result message — Ollama format
                apiMessages.add(JsonObject().apply {
                    addProperty("role", "tool")
                    addProperty("content", msg.content)
                    // Include tool_call_id if available for correlation
                    msg.toolCallId?.let { addProperty("tool_call_id", it) }
                })
                continue
            }

            val messageObj = JsonObject().apply {
                addProperty("role", msg.role)
                addProperty("content", if (isContextMessage && ctx != null) buildPromptWithContext(msg.content, ctx) else msg.content)
            }

            // Vision: add images array if context screenshot is attached to this message
            if (isContextMessage && ctx != null && ctx.screenshotBase64.isNotBlank()) {
                val imagesArray = com.google.gson.JsonArray()
                imagesArray.add(ctx.screenshotBase64) // Raw base64, no data: URI prefix
                messageObj.add("images", imagesArray)
            }

            // Assistant messages with tool_calls
            if (msg.role == "assistant" && msg.toolCalls != null && msg.toolCalls.isNotEmpty()) {
                val tcArray = com.google.gson.JsonArray()
                for (tc in msg.toolCalls) {
                    tcArray.add(JsonObject().apply {
                        val fn = JsonObject().apply {
                            addProperty("name", tc.name)
                            add("arguments", tc.arguments)
                        }
                        add("function", fn)
                        addProperty("id", tc.id)
                    })
                }
                messageObj.add("tool_calls", tcArray)
            }

            apiMessages.add(messageObj)
        }

        val root = JsonObject().apply {
            addProperty("model", provider.model)
            addProperty("stream", true)
            add("messages", gson.toJsonTree(apiMessages))
            // Include tool definitions if provided
            if (tools.isNotEmpty()) {
                add("tools", gson.toJsonTree(tools.map { tool ->
                    JsonObject().apply {
                        addProperty("type", "function")
                        add("function", JsonObject().apply {
                            addProperty("name", tool.name)
                            addProperty("description", tool.description)
                            add("parameters", tool.parameters)
                        })
                    }
                }))
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

    // ── Usage extraction ─────────────────────────────────────────────

    private fun extractUsage(json: JsonObject): TokenUsage? {
        // Ollama's final message may include eval_count, prompt_eval_count, etc.
        val promptTokens = json.get("prompt_eval_count")?.asInt ?: 0
        val outputTokens = json.get("eval_count")?.asInt ?: 0
        return if (promptTokens > 0 || outputTokens > 0) {
            TokenUsage(inputTokens = promptTokens, outputTokens = outputTokens)
        } else null
    }

    // ── Error parsing ────────────────────────────────────────────────

    private fun parseHttpError(body: String, statusCode: Int): Pair<String, Int?> {
        return try {
            val json = JsonParser.parseString(body).asJsonObject
            val message = json.get("error")?.asString ?: "HTTP $statusCode"
            message to statusCode
        } catch (_: Exception) {
            "HTTP $statusCode" to statusCode
        }
    }
}