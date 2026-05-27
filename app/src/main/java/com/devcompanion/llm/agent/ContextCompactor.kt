package com.devcompanion.llm.agent

import android.util.Log
import com.devcompanion.llm.ChatMessage

/**
 * Compacts conversation history when it exceeds the token budget.
 *
 * Inspired by Codex's mid-turn context compaction pattern:
 * When the conversation grows too large, we ask the LLM to produce a
 * "handoff summary" that preserves the essential context, then replace
 * the old messages with the summary. This allows the agent loop to
 * continue operating within token limits without losing critical information.
 *
 * The compaction preserves:
 * - The original user message (first user message)
 * - The most recent N messages (configurable, default 4)
 * - Replaces everything in between with a generated summary
 *
 * @param tokenBudget Maximum estimated token count before compaction triggers
 * @param recentWindow Number of recent messages to preserve intact
 * @param llmCaller Callback to invoke LLM for generating the summary
 */
class ContextCompactor(
    private val tokenBudget: Int = 8000,
    private val recentWindow: Int = 4,
    private val llmCaller: suspend (String) -> String
) {
    companion object {
        private const val TAG = "ContextCompactor"
        /** Rough estimate: 1 token ≈ 4 characters for English/mixed content. */
        private const val CHARS_PER_TOKEN = 4
    }

    /**
     * Estimate token count from message list.
     * Uses character-based heuristic: content length / CHARS_PER_TOKEN.
     */
    fun estimateTokens(messages: List<ChatMessage>): Int {
        var total = 0
        for (msg in messages) {
            total += (msg.content.length / CHARS_PER_TOKEN)
            msg.toolCalls?.let { calls ->
                for (call in calls) {
                    total += (call.arguments.toString().length / CHARS_PER_TOKEN)
                }
            }
        }
        return total
    }

    /**
     * Compact messages if they exceed the token budget.
     *
     * Strategy:
     * 1. Keep the first user message (original task)
     * 2. Keep the last [recentWindow] messages (current context)
     * 3. Replace everything in between with a generated summary
     *
     * @param messages Mutable message list to compact in-place
     * @return true if compaction was performed, false if not needed
     */
    suspend fun compact(messages: MutableList<ChatMessage>): Boolean {
        val estimatedTokens = estimateTokens(messages)
        if (estimatedTokens <= tokenBudget) return false

        if (messages.size <= recentWindow + 1) {
            // Not enough messages to compact — just the user message + recent window
            Log.w(TAG, "Token budget exceeded ($estimatedTokens > $tokenBudget) but too few messages to compact (${messages.size})")
            return false
        }

        Log.i(TAG, "Compacting: $estimatedTokens estimated tokens > $tokenBudget budget. Messages: ${messages.size}")

        // Find first user message
        val firstUserIdx = messages.indexOfFirst { it.role == "user" }
        if (firstUserIdx < 0) return false // no user message — can't compact meaningfully

        // Identify messages to summarize (between first user and recent window)
        val recentStart = maxOf(firstUserIdx + 1, messages.size - recentWindow)

        if (recentStart <= firstUserIdx + 1) {
            // Nothing to compact between user message and recent window
            return false
        }

        // Build the summary prompt from the middle messages
        val middleMessages = messages.subList(firstUserIdx + 1, recentStart)
        val summaryPrompt = buildCompactionPrompt(middleMessages)

        // Generate summary via LLM
        val summary = try {
            llmCaller(summaryPrompt)
        } catch (e: Exception) {
            Log.e(TAG, "Compaction LLM call failed", e)
            // Fallback: simple truncation of middle messages
            buildFallbackSummary(middleMessages)
        }

        // Rebuild message list: [first user] + [summary] + [recent window]
        val firstUser = messages[firstUserIdx]
        val recent = messages.takeLast(messages.size - recentStart)

        messages.clear()
        messages.add(firstUser)
        messages.add(ChatMessage(
            role = "system",
            content = buildString {
                appendLine("[CONTEXT COMPACTION]")
                appendLine("Previous conversation summary (another model started to solve this problem and produced this summary):")
                appendLine(summary)
                appendLine("---")
                appendLine("Continue from where this summary leaves off.")
            }
        ))
        messages.addAll(recent)

        val newTokens = estimateTokens(messages)
        Log.i(TAG, "Compaction complete: $estimatedTokens → $newTokens estimated tokens. Messages: ${messages.size}")
        return true
    }

    /**
     * Build the prompt for the LLM to generate a handoff summary.
     */
    private fun buildCompactionPrompt(middleMessages: List<ChatMessage>): String {
        return buildString {
            appendLine("Summarize the following conversation history concisely.")
            appendLine("Focus on:")
            appendLine("1. What the user asked for (their original goal)")
            appendLine("2. What tools were called and their key results (success or failure)")
            appendLine("3. What the current state of the page/task is")
            appendLine("4. Any unresolved issues or next steps identified")
            appendLine()
            appendLine("Keep the summary under 500 words. Be specific about tool results and selectors that worked or failed.")
            appendLine()
            appendLine("--- CONVERSATION HISTORY ---")
            for (msg in middleMessages) {
                appendLine("[${msg.role}]")
                appendLine(msg.content.take(500)) // limit each message to avoid bloating the prompt
                appendLine()
            }
            appendLine("--- END HISTORY ---")
        }
    }

    /**
     * Fallback summary when LLM call fails — extracts key information
     * without an LLM round-trip.
     */
    private fun buildFallbackSummary(middleMessages: List<ChatMessage>): String {
        return buildString {
            appendLine("(Auto-compacted — LLM summary unavailable)")
            appendLine()
            // Extract tool calls and key results
            for (msg in middleMessages) {
                if (msg.role == "tool") {
                    appendLine("- Tool result: ${msg.content.take(200)}...")
                } else if (msg.role == "assistant" && msg.toolCalls?.isNotEmpty() == true) {
                    appendLine("- Called: ${msg.toolCalls!!.joinToString(", ") { it.name }}")
                } else if (msg.role == "system" && msg.content.startsWith("[SYSTEM:")) {
                    appendLine("- System: ${msg.content.take(200)}")
                }
            }
        }
    }
}