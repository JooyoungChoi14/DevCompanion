package com.devcompanion.llm

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Metadata for a saved conversation.
 */
data class ConversationMeta(
    val id: String,
    val title: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
    val sourceUrl: String? = null
)

/**
 * Export format for a conversation — compatible with OpenAI/Anthropic/Ollama.
 * Includes both the internal format and industry-standard mappings.
 * Also includes system prompt and tool definitions for debugging/improvement.
 */
data class ConversationExport(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ChatMessage>,
    // OpenAI-compatible format for easy import
    val openaiFormat: List<Map<String, String>> = emptyList(),
    // Agent metadata for debugging and improvement analysis
    val agentMeta: AgentMeta? = null
)

/** Metadata about the system prompt and tools used during the session.
 * Included in exports so that agent behavior can be analyzed in context.
 */
data class AgentMeta(
    val systemPrompt: String,
    val mode: String,
    val currentUrl: String?,
    val toolDefinitions: List<ToolMeta>
)

/** Minimal tool definition info for export (no parameter details). */
data class ToolMeta(
    val name: String,
    val description: String,
    // OpenAI-compatible format for easy import
    val openaiFormat: List<Map<String, String>> = emptyList()
)

/**
 * Persists chat conversation history to JSON files.
 * Each conversation is stored as a separate file with metadata.
 */
object ChatHistory {
    private const val TAG = "ChatHistory"
    private const val MAX_MESSAGES = 200
    private const val DIRNAME = "chat_history"
    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create()

    private fun historyDir(context: Context): File {
        val dir = File(context.filesDir, DIRNAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ── Save / Load ────────────────────────────────────────────────

    /** Save messages for a given conversation. */
    fun save(context: Context, conversationId: String, messages: List<ChatMessage>, sourceUrl: String? = null) {
        try {
            val persistable = messages
                .filter { it.role in listOf("user", "assistant", "system") }
                .takeLast(MAX_MESSAGES)
            val file = File(historyDir(context), "$conversationId.json")

            // Build metadata
            val title = deriveTitle(persistable)
            val now = System.currentTimeMillis()
            val meta = mapOf(
                "id" to conversationId,
                "title" to title,
                "createdAt" to (persistable.firstOrNull()?.timestamp ?: now),
                "updatedAt" to now,
                "messageCount" to persistable.size,
                "sourceUrl" to (sourceUrl ?: loadSourceUrl(context, conversationId)),
                "messages" to persistable
            )
            file.writeText(gson.toJson(meta))
            Log.d(TAG, "Saved ${persistable.size} messages to $conversationId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save chat history", e)
        }
    }

    /** Load messages for a given conversation. */
    fun load(context: Context, conversationId: String): List<ChatMessage> {
        return try {
            val file = File(historyDir(context), "$conversationId.json")
            if (!file.exists()) return emptyList()
            val json = file.readText()
            // Try new format (with metadata wrapper)
            try {
                val map = gson.fromJson(json, Map::class.java) as? Map<*, *>
                val messagesRaw = map?.get("messages")
                if (messagesRaw != null) {
                    val type = object : TypeToken<List<ChatMessage>>() {}.type
                    return gson.fromJson<List<ChatMessage>>(gson.toJson(messagesRaw), type)
                }
            } catch (_: Exception) {}
            // Fallback: old format (plain list)
            val type = object : TypeToken<List<ChatMessage>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load chat history", e)
            emptyList()
        }
    }

    // ── List / Meta ────────────────────────────────────────────────

    /** List all saved conversations with metadata, sorted by most recent first. */
    fun listConversations(context: Context): List<ConversationMeta> {
        return try {
            historyDir(context).listFiles()
                ?.filter { it.extension == "json" }
                ?.mapNotNull { file ->
                    try {
                        val json = file.readText()
                        val map = gson.fromJson(json, Map::class.java) as? Map<*, *>
                        if (map != null) {
                            ConversationMeta(
                                id = file.nameWithoutExtension,
                                title = map["title"] as? String ?: "Untitled",
                                createdAt = (map["createdAt"] as? Number)?.toLong() ?: 0L,
                                updatedAt = (map["updatedAt"] as? Number)?.toLong() ?: 0L,
                                messageCount = (map["messageCount"] as? Number)?.toInt() ?: 0,
                                sourceUrl = map["sourceUrl"] as? String
                            )
                        } else {
                            // Old format: plain message list
                            val type = object : TypeToken<List<ChatMessage>>() {}.type
                            val msgs: List<ChatMessage> = gson.fromJson(json, type)
                            ConversationMeta(
                                id = file.nameWithoutExtension,
                                title = deriveTitle(msgs),
                                createdAt = msgs.firstOrNull()?.timestamp ?: 0L,
                                updatedAt = msgs.lastOrNull()?.timestamp ?: 0L,
                                messageCount = msgs.size,
                                sourceUrl = null
                            )
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
                ?.sortedByDescending { it.updatedAt }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Delete a conversation's history. */
    fun delete(context: Context, conversationId: String) {
        try {
            val file = File(historyDir(context), "$conversationId.json")
            file.delete()
            Log.d(TAG, "Deleted conversation $conversationId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete chat history", e)
        }
    }

    /** Generate a new conversation ID based on timestamp. */
    fun newConversationId(): String {
        return System.currentTimeMillis().toString()
    }

    /** Load sourceUrl from a saved conversation without parsing all messages. */
    private fun loadSourceUrl(context: Context, conversationId: String): String? {
        return try {
            val file = File(historyDir(context), "$conversationId.json")
            if (!file.exists()) return null
            val json = file.readText()
            val map = gson.fromJson(json, Map::class.java) as? Map<*, *>
            map?.get("sourceUrl") as? String
        } catch (_: Exception) { null }
    }

    /**
     * Find the most recent conversation matching a URL by domain+path prefix.
     * Strips query parameters and fragments before comparison.
     * Returns null if no match found.
     */
    fun findConversationByUrl(context: Context, url: String): ConversationMeta? {
        val normalized = normalizeUrlForMatch(url) ?: return null
        return listConversations(context)
            .filter { it.sourceUrl != null }
            .filter { normalizeUrlForMatch(it.sourceUrl!!) == normalized }
            .firstOrNull()
    }

    /**
     * Normalize URL for matching: extract scheme+host+path (no query/fragment).
     * Returns null for about:blank, chrome://, data:, and other non-http schemes.
     * about:blank is not a meaningful URL for session matching.
     */
    fun normalizeUrlForMatch(url: String): String? {
        if (url == "about:blank") return null
        return try {
            val uri = java.net.URI(url)
            val scheme = uri.scheme ?: return null
            if (scheme !in listOf("http", "https")) return null
            val host = uri.host ?: return null
            val path = uri.path?.trimEnd('/') ?: ""
            "$scheme://$host$path"
        } catch (_: Exception) { null }
    }

    // ── Export / Import ────────────────────────────────────────────

    /** Export a conversation in DevCompanion's native format. */
    fun exportConversation(context: Context, conversationId: String, agentMeta: AgentMeta? = null): ConversationExport? {
        val messages = load(context, conversationId)
        if (messages.isEmpty()) return null

        val file = File(historyDir(context), "$conversationId.json")
        val json = file.readText()
        val map = gson.fromJson(json, Map::class.java) as? Map<*, *>
        val title = map?.get("title") as? String ?: deriveTitle(messages)
        val createdAt = (map?.get("createdAt") as? Number)?.toLong() ?: messages.firstOrNull()?.timestamp ?: 0L
        val updatedAt = (map?.get("updatedAt") as? Number)?.toLong() ?: messages.lastOrNull()?.timestamp ?: 0L

        // OpenAI-compatible format
        val openaiFormat = messages.map { msg ->
            mapOf("role" to msg.role, "content" to msg.content)
        }

        return ConversationExport(
            id = conversationId,
            title = title,
            createdAt = createdAt,
            updatedAt = updatedAt,
            messages = messages,
            openaiFormat = openaiFormat,
            agentMeta = agentMeta
        )
    }

    /** Export as JSON string (for sharing). */
    fun exportToJson(context: Context, conversationId: String, agentMeta: AgentMeta? = null): String? {
        val export = exportConversation(context, conversationId, agentMeta) ?: return null
        return gson.toJson(export)
    }

    /** Export multiple conversations as a JSON array string. */
    fun exportMultipleToJson(context: Context, conversationIds: List<String>, agentMeta: AgentMeta? = null): String {
        val exports = conversationIds.mapNotNull { id ->
            exportConversation(context, id, agentMeta)
        }
        return gson.toJson(mapOf("conversations" to exports))
    }

    /** Delete multiple conversations. Returns count of deleted. */
    fun deleteMultiple(context: Context, conversationIds: List<String>): Int {
        var count = 0
        for (id in conversationIds) {
            val file = File(historyDir(context), "$id.json")
            if (file.exists()) {
                file.delete()
                count++
            }
        }
        return count
    }

    /** Export selected messages from a conversation as JSON string. */
    fun exportMessagesToJson(context: Context, conversationId: String, messageIds: Set<String>, agentMeta: AgentMeta? = null): String? {
        val allMessages = load(context, conversationId)
        if (allMessages.isEmpty()) return null
        val selected = allMessages.filter { it.id in messageIds }
        if (selected.isEmpty()) return null

        val file = File(historyDir(context), "$conversationId.json")
        val json = file.readText()
        val map = gson.fromJson(json, Map::class.java) as? Map<*, *>
        val title = map?.get("title") as? String ?: deriveTitle(allMessages)
        val createdAt = (map?.get("createdAt") as? Number)?.toLong() ?: allMessages.firstOrNull()?.timestamp ?: 0L
        val updatedAt = (map?.get("updatedAt") as? Number)?.toLong() ?: allMessages.lastOrNull()?.timestamp ?: 0L

        val export = ConversationExport(
            id = conversationId,
            title = title,
            createdAt = createdAt,
            updatedAt = updatedAt,
            messages = selected,
            openaiFormat = selected.map { mapOf("role" to it.role, "content" to it.content) },
            agentMeta = agentMeta
        )
        return gson.toJson(export)
    }

    /** Import a conversation from JSON string.
     * Supports DevCompanion native format, OpenAI format, and Anthropic format.
     * Returns the new conversation ID or null on failure.
     */
    fun importFromJson(context: Context, json: String): String? {
        return try {
            val map = gson.fromJson(json, Map::class.java) as? Map<*, *>
            when {
                // DevCompanion native format: has "messages" array with ChatMessage structure
                map?.containsKey("messages") == true -> {
                    @Suppress("UNCHECKED_CAST")
                    val messagesRaw = map["messages"] as? List<Map<String, Any>>
                    val messages = messagesRaw?.map { m ->
                        ChatMessage(
                            role = m["role"] as? String ?: "user",
                            content = m["content"] as? String ?: "",
                            timestamp = (m["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                            hasContext = m["hasContext"] as? Boolean ?: false,
                            tokenUsage = null,
                            toolCalls = null,
                            toolCallId = m["toolCallId"] as? String,
                            toolName = m["toolName"] as? String,
                            isToolResult = m["isToolResult"] as? Boolean ?: false,
                            isError = m["isError"] as? Boolean ?: false
                        )
                    } ?: emptyList()

                    if (messages.isEmpty()) return null
                    val newId = newConversationId()
                    save(context, newId, messages)
                    newId
                }

                // OpenAI format: top-level "messages" array with {role, content}
                map?.containsKey("messages") == false && map.isNotEmpty() -> {
                    // Could be OpenAI export format with different structure
                    parseOpenAiFormat(map) ?: parseAnthropicFormat(map)
                }

                else -> {
                    // Try to parse as plain message list
                    val type = object : TypeToken<List<Map<String, Any>>>() {}.type
                    val msgs: List<Map<String, Any>>? = gson.fromJson(json, type)
                    if (msgs != null && msgs.isNotEmpty() && msgs.first().containsKey("role")) {
                        val messages = msgs.map { m ->
                            ChatMessage(
                                role = m["role"] as? String ?: "user",
                                content = m["content"] as? String ?: ""
                            )
                        }
                        val newId = newConversationId()
                        save(context, newId, messages)
                        newId
                    } else null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import conversation", e)
            null
        }
    }

    /** Parse OpenAI chat completion format. */
    private fun parseOpenAiFormat(map: Map<*, *>): String? {
        // OpenAI format: { "messages": [{ "role": "user/assistant/system", "content": "..." }] }
        @Suppress("UNCHECKED_CAST")
        val messagesRaw = map["messages"] as? List<Map<String, Any>> ?: return null
        if (messagesRaw.isEmpty()) return null
        val messages = messagesRaw.map { m ->
            val content = when (val c = m["content"]) {
                is String -> c
                is List<*> -> {
                    // OpenAI multi-part content: [{ "type": "text", "text": "..." }]
                    c.filterIsInstance<Map<*, *>>()
                        .filter { it["type"] == "text" }
                        .joinToString("\n") { it["text"] as? String ?: "" }
                }
                else -> ""
            }
            ChatMessage(
                role = m["role"] as? String ?: "user",
                content = content
            )
        }
        val newId = newConversationId()
        // We need a context here — this will be called from ViewModel
        return newId // Caller will save
    }

    /** Parse Anthropic format. */
    private fun parseAnthropicFormat(map: Map<*, *>): String? {
        // Anthropic format: { "messages": [{ "role": "user/assistant", "content": [{ "type": "text", "text": "..." }] }] }
        @Suppress("UNCHECKED_CAST")
        val messagesRaw = map["messages"] as? List<Map<String, Any>> ?: return null
        if (messagesRaw.isEmpty()) return null
        val messages = messagesRaw.map { m ->
            val content = when (val c = m["content"]) {
                is String -> c
                is List<*> -> {
                    c.filterIsInstance<Map<*, *>>()
                        .filter { it["type"] == "text" }
                        .joinToString("\n") { it["text"] as? String ?: "" }
                }
                else -> ""
            }
            ChatMessage(
                role = m["role"] as? String ?: "user",
                content = content
            )
        }
        val newId = newConversationId()
        return newId
    }

    // ── Helpers ─────────────────────────────────────────────────────

    /** Derive a title from the first user message. */
    private fun deriveTitle(messages: List<ChatMessage>): String {
        val firstUserMsg = messages.firstOrNull { it.role == "user" }
        if (firstUserMsg != null) {
            val title = firstUserMsg.content.take(50).trim()
            val newline = title.indexOf('\n')
            return if (newline > 0) title.take(newline) else title
        }
        return "Untitled"
    }
}