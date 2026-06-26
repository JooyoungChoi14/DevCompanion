package com.devcompanion.ui

import android.content.Context
import android.util.Log
import com.devcompanion.llm.ChatHistory
import com.devcompanion.logging.EventType
import com.devcompanion.logging.SessionLog

/**
 * Single entry point for resolving which conversation to open when the AI chat is invoked.
 *
 * Eliminates the duplicated decision logic between the AI chat button click handler
 * and the onAskAi callback. All session resolution now flows through [resolve].
 *
 * Lifecycle: created per-invocation (not persisted). State is returned to the caller
 * which then sets composition state accordingly.
 *
 * See: docs/session-lifecycle.md Section 3 for the decision tree.
 */
object ChatSessionResolver {

    private const val TAG = "ChatSessionResolver"

    /**
     * Result of a session resolution. All fields are deterministic outputs
     * based on the inputs — no side effects, no state mutation.
     */
    data class Resolution(
        /** Whether to show the AI chat sheet. */
        val showAiChat: Boolean,
        /** Whether to show the session choice dialog. */
        val showSessionChoice: Boolean = false,
        /** ID of a URL-matched conversation to resume, or null. */
        val matchedConversationId: String? = null,
        /** Whether to force creating a new conversation (skip URL matching). */
        val forceNewSession: Boolean = false,
        /** The URL captured at the time of invocation. */
        val url: String?,
        /** The normalized URL used for matching, or null if URL is not matchable. */
        val normalizedUrl: String?,
        /** Decision path taken, for logging. */
        val decision: String,
    )

    /**
     * Resolve which conversation/session to open for the AI chat.
     *
     * @param url          Current browser URL (may be null if engine not ready)
     * @param context       Android context (for ChatHistory access)
     * @param currentConvId Currently active conversation ID in ViewModel
     * @param hasActiveChat Whether ViewModel has messages (non-empty)
     * @param hasQuestion   Whether this invocation comes with a pre-filled question (onAskAi)
     * @return Resolution determining what to show and which session to use
     */
    fun resolve(
        url: String?,
        context: Context,
        currentConvId: String,
        hasActiveChat: Boolean,
        hasQuestion: Boolean = false,
    ): Resolution {
        val normalizedUrl = url?.let { ChatHistory.normalizeUrlForMatch(it) }
        val matchedConv = url?.let { ChatHistory.findConversationByUrl(context, it) }

        // Log the resolution context
        SessionLog.log(EventType.UI_CLICK, mapOf(
            "target" to if (hasQuestion) "on_ask_ai" else "ai_chat_btn",
            "detail" to if (url != null) "has_url" else "no_url",
            "url" to (url?.take(100) ?: "null"),
            "normalizedUrl" to (normalizedUrl ?: "null"),
            "hasActiveChat" to hasActiveChat.toString(),
            "currentConvId" to currentConvId.take(8),
            "matchedConvId" to (matchedConv?.id?.take(8) ?: "null"),
            "matchedConvSourceUrl" to (matchedConv?.sourceUrl?.take(100) ?: "null"),
            "decision" to ""  // filled below
        ))

        val decision: String
        val result: Resolution

        when {
            // Path 1: Active conversation exists — reopen it directly
            // This takes priority over URL matching for quick resume.
            // Note: this means URL mismatch is invisible to the user.
            hasActiveChat && !hasQuestion -> {
                decision = "open_existing"
                result = Resolution(
                    showAiChat = true,
                    matchedConversationId = null,
                    forceNewSession = false,
                    url = url,
                    normalizedUrl = normalizedUrl,
                    decision = decision,
                )
            }

            // Path 2: URL matches an existing conversation, and it's not the current one
            // For onAskAi: show session choice so user can decide
            // For button click: resume directly (no dialog — user explicitly tapped)
            matchedConv != null && matchedConv.id != currentConvId -> {
                decision = "resume_matched"
                result = if (hasQuestion) {
                    // onAskAi: show session choice dialog
                    Resolution(
                        showAiChat = false,
                        showSessionChoice = true,
                        matchedConversationId = matchedConv.id,
                        forceNewSession = false,
                        url = url,
                        normalizedUrl = normalizedUrl,
                        decision = "show_session_choice",
                    )
                } else {
                    // Button click: resume directly
                    Resolution(
                        showAiChat = true,
                        matchedConversationId = matchedConv.id,
                        forceNewSession = false,
                        url = url,
                        normalizedUrl = normalizedUrl,
                        decision = decision,
                    )
                }
            }

            // Path 3: URL matches current conversation — just open it
            matchedConv != null -> {
                decision = "open_current"
                result = Resolution(
                    showAiChat = true,
                    matchedConversationId = null,  // already current, no need to specify
                    forceNewSession = false,
                    url = url,
                    normalizedUrl = normalizedUrl,
                    decision = decision,
                )
            }

            // Path 4: Real URL but no matching conversation
            normalizedUrl != null -> {
                decision = "show_session_choice"
                result = if (hasQuestion) {
                    // onAskAi with no match: open new session directly with prompt
                    Resolution(
                        showAiChat = true,
                        showSessionChoice = false,
                        matchedConversationId = null,
                        forceNewSession = true,
                        url = url,
                        normalizedUrl = normalizedUrl,
                        decision = "new_session",
                    )
                } else {
                    // Button click: show session choice dialog
                    // (user might want to associate this URL with a new conversation)
                    Resolution(
                        showAiChat = false,
                        showSessionChoice = true,
                        matchedConversationId = null,
                        forceNewSession = false,
                        url = url,
                        normalizedUrl = normalizedUrl,
                        decision = decision,
                    )
                }
            }

            // Path 5: about:blank, chrome://, null URL, etc. — always new session
            else -> {
                decision = "new_session"
                result = Resolution(
                    showAiChat = true,
                    matchedConversationId = null,
                    forceNewSession = true,
                    url = url,
                    normalizedUrl = normalizedUrl,
                    decision = decision,
                )
            }
        }

        // Update the log entry with the actual decision
        SessionLog.log(EventType.UI_CLICK, mapOf(
            "target" to if (hasQuestion) "on_ask_ai_resolve" else "ai_chat_btn_resolve",
            "decision" to result.decision,
            "url" to (url?.take(100) ?: "null"),
            "normalizedUrl" to (normalizedUrl ?: "null"),
            "hasActiveChat" to hasActiveChat.toString(),
            "currentConvId" to currentConvId.take(8),
            "matchedConvId" to (matchedConv?.id?.take(8) ?: "null"),
        ))

        Log.d(TAG, "resolve: decision=${result.decision}, url=${url?.take(50)}, " +
                "hasActiveChat=$hasActiveChat, hasQuestion=$hasQuestion, " +
                "matchedConvId=${result.matchedConversationId?.take(8)}")

        return result
    }
}