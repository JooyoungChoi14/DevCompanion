# W1-W3 Design Notes — Industry Research & DevCompanion Recommendations

**Date**: 2026-05-17  
**Based on**: LibreChat, LobeChat, Open WebUI, NextChat source analysis

---

## W1: Conversation Title Auto-Generation

### Industry Patterns

| Project | Approach | Trigger | Mechanism |
|---------|----------|---------|-----------|
| **LobeChat** | LLM-generated title | On first assistant response complete | `summaryTopicTitle()` → streaming LLM call → update topic title in store |
| **Open WebUI** | LLM-generated title (configurable) | After first user message gets response | POST `/title/completions` → separate task model (configurable) → template-based prompt → title |
| **LibreChat** | LLM-generated title | After first response | `genTitle` endpoint → separate model call (cheaper model) → update conversation |
| **NextChat** | LLM-generated title | After first response | Client-side LLM call with title prompt → auto-summary |

**Common pattern**: All use LLM-based generation, not rule-based extraction. Triggered after first exchange completes. Most use a dedicated "task model" (cheaper/faster than main chat model).

### DevCompanion Current State
- `deriveTitle()` takes first 50 chars of first user message → `"Untitled"` fallback
- Stored in `ConversationMeta.title` at save time
- No async title generation

### Recommendation
```kotlin
// AiChatViewModel.kt
private var titleJob: Job? = null

fun generateTitle(webView: WebView? = null) {
    if (_conversationId.value.isBlank()) return
    val messages = _messages.value
    if (messages.size < 2) return  // need at least 1 exchange
    val existingTitle = ChatHistory.listConversations(getApplication<Application>())
        .find { it.id == _conversationId.value }?.title
    if (existingTitle != null && existingTitle != "Untitled") return  // already has title

    titleJob?.cancel()
    titleJob = viewModelScope.launch {
        val prompt = buildSystemPrompt("title", webView) +
            "\n\nGenerate a short title (max 40 chars, no quotes) for this conversation based on the first messages."
        val provider = _provider.value ?: return@launch
        // Use current provider — no separate task model for mobile
        try {
            val title = withTimeout(10_000) {
                // Simplified: use first 200 chars of exchange as context
                val context = messages.take(4).joinToString("\n") { 
                    "${it.role}: ${it.content.take(200)}" 
                }
                // Call LLM with title generation prompt
                // ... actual implementation depends on LLM adapter API
                "Untitled" // placeholder until adapter supports title gen
            }
            if (title.isNotBlank() && title != "Untitled") {
                ChatHistory.updateTitle(getApplication<Application>(), _conversationId.value, title)
            }
        } catch (_: Exception) { /* silent fail — keep derived title */ }
    }
}
```

**Implementation priority**: LOW. Current `deriveTitle()` works for mobile — adds complexity without strong mobile UX benefit. Consider for v2 when sidebar shows many conversations.

---

## W2: Deep Link / URL-Based Conversation Switching

### Industry Patterns

| Project | Approach | Format | Back-Stack |
|---------|----------|--------|------------|
| **LibreChat** | URL SSOT | `/c/{conversationId}` | React Router handles browser back |
| **LobeChat** | URL + store sync | `/chat/{agentId}/{topicId}` | React Router, deep links work |
| **Open WebUI** | URL routing | `/c/{id}` | Svelte routing, shareable links |
| **NextChat** | URL hash routing | `#/chat/{id}` | Hash-based SPA routing |

**Common pattern**: URL path contains conversation ID. Navigation = state change. Back button works naturally.

### DevCompanion Current State
- `currentConversationId` is now `StateFlow<String>` (M1 completed)
- No deep link support
- BottomSheet-based UI — no URL bar navigation
- Android: no intent filters for conversation IDs

### Recommendation
For Android with BottomSheet, URL-based routing doesn't apply directly. Instead:

```kotlin
// AndroidManifest.xml — deep link intent filter
// <intent-filter>
//     <action android:name="android.intent.action.VIEW" />
//     <category android:name="android.intent.category.DEFAULT" />
//     <category android:name="android.intent.category.BROWSABLE" />
//     <data android:scheme="devcompanion" android:host="chat" />
// </intent-filter>

// AiChatViewModel.kt
fun handleDeepLink(conversationId: String) {
    val conversations = ChatHistory.listConversations(getApplication<Application>())
    if (conversations.any { it.id == conversationId }) {
        loadConversation(conversationId)
    }
}
```

**Implementation priority**: LOW. Deep links are nice-to-have for mobile — the BottomSheet pattern makes URL routing less relevant. The `conversationId` StateFlow (M1) is the prerequisite and is already done.

---

## W3: Tool Result Format Unification

### Industry Patterns

| Project | Approach | Model |
|---------|----------|--------|
| **LibreChat** | `ContentTypes` enum: TEXT, CODE, TOOL_CALL, TOOL_RESULT | Each message part has explicit type |
| **LobeChat** | `UIChatMessage` with `tools` array separate from content | Tool calls/results are sub-objects on the message |
| **Open WebUI** | Separate `tool_calls` and `function` fields on message model | OpenAI-compatible message structure |

**Common pattern**: Discriminated union / sealed type for message content parts. Tool results are never mixed with user/assistant text content.

### DevCompanion Current State
```kotlin
data class ChatMessage(
    val role: String,              // "user", "assistant", "system" — flat string
    val isToolResult: Boolean,     // ad-hoc discriminator
    val toolCalls: List<ToolCall>?, // embedded in message
    val toolCallId: String?,       // for tool role mapping
    val toolName: String?,         // for Gemini
    val isError: Boolean           // error flag
)
```
Problems:
- `role = "system"` is overloaded (system prompt AND tool results)
- `isToolResult` is only checked in chat mode, not agent mode
- Flat booleans instead of type-safe discrimination
- Adding new message types requires adding more booleans

### Recommendation
```kotlin
sealed class ChatMessageContent {
    data class Text(val text: String) : ChatMessageContent()
    data class ToolCall(val calls: List<com.devcompanion.llm.agent.ToolCall>) : ChatMessageContent()
    data class ToolResult(val callId: String, val name: String, val result: String, val isError: Boolean) : ChatMessageContent()
    data class SystemPrompt(val text: String) : ChatMessageContent()
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,  // "user", "assistant" — only these two
    val parts: List<ChatMessageContent>,
    val timestamp: Long = System.currentTimeMillis(),
    val hasContext: Boolean = false,
    val tokenUsage: TokenUsage? = null
)
```

This is a **breaking schema change** — requires migration logic for existing saved conversations.

**Implementation priority**: MEDIUM. The current `isToolResult` flag causes real bugs in agent mode (stale tool results can be sent to LLM). But migration complexity is high. Plan for v2.

---

## Summary

| Item | Priority | Rationale |
|------|----------|-----------|
| W1 (Title gen) | LOW | Mobile UX doesn't strongly benefit; deriveTitle works OK for now |
| W2 (Deep links) | LOW | M1 prerequisite done; BottomSheet makes URL routing less relevant |
| W3 (Sealed message) | MEDIUM | Real bug risk in agent mode; plan for v2 with migration logic |
| onCleared safety net | DONE | Committed in `2be58fa` |