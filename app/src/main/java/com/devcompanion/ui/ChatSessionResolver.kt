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
 * Note: resolve() performs I/O (filesystem reads via ChatHistory) and logging.
 * It is NOT a pure function. For testability, consider extracting the decision logic
 * into a pure function that takes pre-resolved match results.
 *
 * See: docs/session-lifecycle.md Section 3 for the decision tree.
 */
object ChatSessionResolver {

    private const val TAG = "ChatSessionResolver"

    /**
     * Result of a session resolution. All fields are simple data outputs
     * based on the resolution logic. The Resolution itself has no side effects,
     * but the resolve() function that produces it performs I/O and logging.
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
     * Performs I/O: calls ChatHistory.findConversationByUrl() which reads the filesystem.
     * Performs logging: emits UI_CLICK events via SessionLog.
     *
     * @param url               Current browser URL (may be null if engine not ready)
     * @param context            Android context (for ChatHistory filesystem access)
     * @param currentConvId      Currently active conversation ID in ViewModel
     * @param hasActiveChat      Whether ViewModel has messages (non-empty)
     * @param hasQuestion        Whether this invocation comes with a pre-filled question (onAskAi)
     * @param currentSourceUrl   The sourceUrl of the current conversation (null if about:blank/no conv)
     * @return Resolution determining what to show and which session to use
     */
    fun resolve(
        url: String?,
        context: Context,
        currentConvId: String,
        hasActiveChat: Boolean,
        hasQuestion: Boolean = false,
        currentSourceUrl: String? = null,
    ): Resolution {
        // Guard: empty string URL is not a real page
        val effectiveUrl = if (url.isNullOrBlank()) null else url
        val normalizedUrl = effectiveUrl?.let { ChatHistory.normalizeUrlForMatch(it) }
        val matchedConv = effectiveUrl?.let { ChatHistory.findConversationByUrl(context, it) }

        // Determine if the current conversation's URL matches the browser URL.
        // If hasActiveChat but sourceUrl doesn't match, we should check URL matching
        // instead of blindly reopening the current conversation.
        val currentSourceMatchesBrowser = if (hasActiveChat && currentSourceUrl != null) {
            ChatHistory.normalizeUrlForMatch(currentSourceUrl) == normalizedUrl
        } else {
            // No active chat, or sourceUrl is null (about:blank conversation)
            false
        }

        // Log the resolution context
        SessionLog.log(EventType.UI_CLICK, mapOf(
            "target" to if (hasQuestion) "on_ask_ai" else "ai_chat_btn",
            "detail" to if (effectiveUrl != null) "has_url" else "no_url",
            "url" to (effectiveUrl?.take(100) ?: "null"),
            "normalizedUrl" to (normalizedUrl ?: "null"),
            "hasActiveChat" to hasActiveChat.toString(),
            "currentConvId" to currentConvId.take(8),
            "matchedConvId" to (matchedConv?.id?.take(8) ?: "null"),
            "matchedConvSourceUrl" to (matchedConv?.sourceUrl?.take(100) ?: "null"),
            "currentSourceUrl" to (currentSourceUrl?.take(100) ?: "null"),
            "currentSourceMatchesBrowser" to currentSourceMatchesBrowser.toString(),
            "decision" to ""  // filled below
        ))

        val decision: String
        val result: Resolution

        when {
            // Path 1: Active conversation with matching URL — reopen directly
            // This is the quick-resume path when the user is on the same page.
            // If hasActiveChat but sourceUrl doesn't match, we fall through to URL matching.
            // If sourceUrl is null (about:blank conversation), we also fall through.
            hasActiveChat && !hasQuestion && currentSourceMatchesBrowser -> {
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
            "url" to (effectiveUrl?.take(100) ?: "null"),
            "normalizedUrl" to (normalizedUrl ?: "null"),
            "hasActiveChat" to hasActiveChat.toString(),
            "currentConvId" to currentConvId.take(8),
            "matchedConvId" to (matchedConv?.id?.take(8) ?: "null"),
        ))

        Log.d(TAG, "resolve: decision=${result.decision}, url=${effectiveUrl?.take(50)}, " +
                "hasActiveChat=$hasActiveChat, hasQuestion=$hasQuestion, " +
                "currentSourceMatchesBrowser=$currentSourceMatchesBrowser, " +
                "matchedConvId=${result.matchedConversationId?.take(8)}")

        return result
    }
}