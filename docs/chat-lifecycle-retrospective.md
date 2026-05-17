# LLM Chat Lifecycle Retrospective & Architecture Review

**Date**: 2026-05-17  
**Scope**: DevCompanion AI Chat — conversation lifecycle, prompt re-send bug, structural debt  
**Status**: Immediate bug fixed; structural improvements deferred to next sprint

---

## 1. Incident Summary

Agent mode would re-send the same prompt every time the AI chat sheet was reopened. Session logs showed `messageCount=1` on repeated `AGENT_START` events — meaning each re-entry created an empty conversation and resent the identical question.

### Affected Commits

| Commit | Description |
|--------|-------------|
| [`e919449`](https://github.com/JooyoungChoi14/DevCompanion/commit/e919449) | `fix: always start new conversation when opening AI chat` — introduced `startNewConversation` flag |
| [`a53ae84`](https://github.com/JooyoungChoi14/DevCompanion/commit/a53ae84) | `fix: prevent agent tool_results from polluting LLM context` |
| [`8dc07eb`](https://github.com/JooyoungChoi14/DevCompanion/commit/8dc07eb) | `fix: use rememberSaveable for initialPromptSent` — partial fix, insufficient |
| [`abf8c6b`](https://github.com/JooyoungChoi14/DevCompanion/commit/abf8c6b) | `chore: exclude r8-mapping from git` |
| [`36d7167`](https://github.com/JooyoungChoi14/DevCompanion/commit/36d7167) | `fix: prevent agent prompt re-send on BottomSheet dismiss/recreate` — **final fix** |

### Session Log Evidence

- Session `899af9ec`: "hello world 이펙트" sent **3 times** (`messageCount=1` each)
- Session `c4f6c7ba`: "고양이를 만들어주세요" sent **2 times** (`messageCount=1` each)

Each re-entry: `newConversation()` called → empty history → same `initialPrompt` resent → LLM executes identical task again.

---

## 2. Root Cause Analysis

Three independent defects combined to produce the observed behavior:

### Defect 1: `initialPromptSent` stored in Composable scope

```kotlin
// BEFORE (broken)
val initialPromptSent = remember { mutableStateOf(false) }
```

`remember` resets when the Composable is destroyed and recreated. `ModalBottomSheet` dismiss removes `AiChatScreen` from composition; re-showing it creates a fresh instance with `initialPromptSent = false`.

**Partial fix attempt** (`8dc07eb`): Changed to `rememberSaveable` — survives Activity recreation but **not** BottomSheet destroy/recreate, because `rememberSaveable` is tied to the saved state registry, which the BottomSheet content doesn't independently participate in after full removal.

```kotlin
// PARTIAL FIX (still broken for BottomSheet dismiss)
val initialPromptSent = rememberSaveable { mutableStateOf(false) }
```

**Final fix**: Moved state to `ViewModel`, which survives BottomSheet lifecycle:

```kotlin
// ViewModel
var initialPromptSent by mutableStateOf(false)
    private set

fun markInitialPromptSent() { initialPromptSent = true }
fun resetInitialPromptSent() { initialPromptSent = false }
```

### Defect 2: Swipe dismiss didn't clear `pendingAiQuestion`

```kotlin
// BEFORE (broken)
ModalBottomSheet(
    onDismissRequest = { showAiChat = false },  // ← missing pendingAiQuestion = null
    ...
) {
    AiChatScreen(
        onDismiss = { showAiChat = false; pendingAiQuestion = null },  // ← only X button
    )
}
```

`onDismiss` (X button) correctly cleared `pendingAiQuestion`, but `onDismissRequest` (swipe / tap outside) did not. After a swipe dismiss, `pendingAiQuestion` persisted → next AI icon tap had `startNewConversation = true` → `newConversation()` called → empty history + same prompt.

**Fix**: Added `pendingAiQuestion = null` to `onDismissRequest`:

```kotlin
onDismissRequest = { showAiChat = false; pendingAiQuestion = null },
```

### Defect 3: `newConversation()` didn't reset `initialPromptSent`

When `startNewConversation = true`, the flow is:
1. `LaunchedEffect(startNewConversation)` → `viewModel.newConversation()` → clears `_messages`
2. `LaunchedEffect(initialPrompt)` → checks `!viewModel.initialPromptSent` → sends prompt

Step 1 clears messages but Step 2's guard (`initialPromptSent`) was still `true` from a previous conversation. This was actually the **opposite** problem — if `initialPromptSent` wasn't reset, the prompt would never be sent for a genuinely new conversation. The fix explicitly resets it:

```kotlin
LaunchedEffect(startNewConversation) {
    if (startNewConversation) {
        viewModel.newConversation()
        viewModel.resetInitialPromptSent()
    }
}
```

### Combined Failure Scenario

```
User types "?고양이" → pendingAiQuestion = "고양이", showAiChat = true
  → startNewConversation = true
  → LaunchedEffect(startNewConversation): newConversation() + resetInitialPromptSent()
  → LaunchedEffect(initialPrompt): markInitialPromptSent(), sendMessageAgent("고양이")
  → Agent completes successfully

User swipes to dismiss → onDismissRequest: showAiChat = false  (pendingAiQuestion NOT cleared)
User taps AI icon again → showAiChat = true
  → startNewConversation = true  (pendingAiQuestion still "고양이")
  → AiChatScreen recreated → initialPromptSent = false (remember resets)
  → LaunchedEffect(startNewConversation): newConversation() (clears history!)
  → LaunchedEffect(initialPrompt): markInitialPromptSent(), sendMessageAgent("고양이") AGAIN
```

---

## 3. Comparative Analysis: LibreChat

**Repository**: [github.com/danny-avila/LibreChat](https://github.com/danny-avila/LibreChat)

### Key Architectural Differences

| Aspect | LibreChat | DevCompanion |
|--------|-----------|--------------|
| **Conversation ID SSOT** | URL param (`/c/{id}`) — React Router is the source of truth | ViewModel internal `currentConversationId` — UI doesn't observe it |
| **New conversation trigger** | Explicit `newConversation()` call → sets `conversationId = Constants.NEW_CONVO` → navigates to `/c/new` | Compose `LaunchedEffect(startNewConversation)` → implicit side effect |
| **Prompt sending** | `ask()` function with explicit args — single entry point | Two `LaunchedEffect`s with different keys — order not guaranteed |
| **State durability** | Recoil atoms + React Query cache — survives navigation | `remember`/`rememberSaveable` — destroyed on BottomSheet dismiss |
| **Message dedup** | `isSubmitting` guard + `useCallback` deps array | `initialPromptSent` boolean flag (now in ViewModel) |

### Relevant LibreChat Source References

| Concern | File | Key Pattern |
|---------|------|-------------|
| New conversation lifecycle | [`client/src/hooks/useNewConvo.ts`](https://github.com/danny-avila/LibreChat/blob/main/client/src/hooks/useNewConvo.ts) | `newConversation()` → clears files, sets `conversationId = NEW_CONVO`, calls `switchToConversation()` atomically |
| Message submission | [`client/src/hooks/Messages/useSubmitMessage.ts`](https://github.com/danny-avila/LibreChat/blob/main/client/src/hooks/Messages/useSubmitMessage.ts) | `ask()` with explicit text arg — no implicit recomposition trigger |
| Chat state management | [`client/src/hooks/Chat/useChatHelpers.ts`](https://github.com/danny-avila/LibreChat/blob/main/client/src/hooks/Chat/useChatHelpers.ts) | `isSubmitting` guard, `abortMutation`, `setQueryData` — all through React Query cache |
| Conversation data layer | [`packages/data-schemas/src/methods/conversation.ts`](https://github.com/danny-avila/LibreChat/blob/main/packages/data-schemas/src/methods/conversation.ts) | Mongoose-based CRUD — server-side SSOT |
| Chat functions core | [`client/src/hooks/Chat/useChatFunctions.ts`](https://github.com/danny-avila/LibreChat/blob/main/client/src/hooks/Chat/useChatFunctions.ts) | `ask()` handles `NEW_CONVO` → null ID + empty messages + navigation |

### LibreChat's Critical Pattern: URL as SSOT

```typescript
// useChatFunctions.ts — ask() method
if (conversationId == Constants.NEW_CONVO) {
    parentMessageId = Constants.NO_PARENT;
    currentMessages = [];
    conversationId = null;
    navigate('/c/new', { state: { focusChat: true } });
}
```

The conversation ID transition from `NEW_CONVO` → real server-assigned ID happens **once**, and the URL update prevents any re-trigger. DevCompanion lacks this — `currentConversationId` is a plain `internal var` in ViewModel with no observation contract.

---

## 4. Structural Risk Assessment

### 🔴 Must (Next Version)

#### M1: Conversation ID is not observable

`currentConversationId` is `internal var` in ViewModel. UI cannot observe or react to conversation changes.

**Risk**: Conversation switching (sidebar) can desync from displayed messages.

**Remediation**: Promote to `StateFlow<String>`:

```kotlin
private val _conversationId = MutableStateFlow(ChatHistory.newConversationId())
val conversationId: StateFlow<String> = _conversationId.asStateFlow()
```

#### M2: Message sending triggers are scattered across two `LaunchedEffect`s

```kotlin
// Current: two separate effects, order not guaranteed
LaunchedEffect(startNewConversation) { ... }  // Effect 1: reset
LaunchedEffect(initialPrompt) { ... }           // Effect 2: send
```

Compose does not guarantee execution order of multiple `LaunchedEffect`s with different keys in the same composition. If Effect 2 fires before Effect 1, `initialPromptSent` may not yet be reset.

**Remediation**: Single entry point in ViewModel:

```kotlin
// ViewModel
fun initializeWithPrompt(prompt: String?, webView: WebView?) {
    newConversation()
    resetInitialPromptSent()
    if (prompt != null && prompt.isNotBlank()) {
        markInitialPromptSent()
        sendMessageAgent(prompt, webView)
    }
}
```

```kotlin
// AiChatScreen — single effect
LaunchedEffect(Unit) {
    if (startNewConversation && initialPrompt != null) {
        viewModel.initializeWithPrompt(initialPrompt, webView)
    }
}
```

### 🟡 Need (Structural Improvement)

#### N1: `newConversation()` doesn't reset context

`_messages`, `_currentResponse`, `_isStreaming`, `_agentState` are cleared, but `_lastContext` is not. Previous conversation's captured WebView context can leak into the new conversation.

**Remediation**: Add to `newConversation()`:

```kotlin
_lastContext.value = null
```

#### N2: Dual save path race condition

`init` block has a `collect` that saves immediately on every `_messages` change, while `saveMessages()` uses 500ms debounce. Both write to the same file.

**Remediation**: Remove the `init` collect and rely solely on `saveMessages()`, or vice versa. Debounced save is preferable for performance.

```kotlin
// Remove this from init:
// viewModelScope.launch { _messages.collect { ... } }

// Keep only saveMessages() with debounce
```

#### N3: Auto-restore restores messages without state

`init` block restores the last conversation's messages, but `agentState`, `pendingConfirmation`, and streaming state are lost. Opening a restored agent conversation shows messages but no context about whether the agent was mid-loop.

**Remediation**: Force `AgentState.Idle` on restore and add a visual indicator:

```kotlin
// init — after restoring messages
_agentState.value = AgentState.Idle  // Force idle on restore
```

### 🟢 Want (Nice-to-Have)

#### W1: Conversation title auto-generation

Currently `deriveTitle()` takes the first few words of the first user message. LLM-based title generation would improve sidebar readability.

#### W2: Deep Link conversation switching

URL-based conversation IDs (`?conv=<id>`) would enable external links and proper back-stack handling.

#### W3: Tool result format unification

Current fix filters `isToolResult` only in chat mode. Agent mode can still accumulate stale tool results. A unified message model (sealed class) would prevent format confusion.

---

## 5. Current State Summary

| Item | Status |
|------|--------|
| Immediate re-send bug | ✅ Fixed (`36d7167`) |
| `initialPromptSent` in ViewModel | ✅ Done |
| Swipe dismiss clearing `pendingAiQuestion` | ✅ Done |
| `newConversation()` resetting `initialPromptSent` | ✅ Done |
| Dual `LaunchedEffect` ordering risk | ⚠️ Mitigated (ViewModel flag) but not architecturally resolved |
| Context leak on `newConversation()` | 🔲 Open |
| Save path race condition | 🔲 Open |
| Conversation ID as `StateFlow` | 🔲 Open |
| Single send entry point | 🔲 Open |

---

## 6. References

- **LibreChat Repository**: [https://github.com/danny-avila/LibreChat](https://github.com/danny-avila/LibreChat)
  - [`useNewConvo.ts`](https://github.com/danny-avila/LibreChat/blob/main/client/src/hooks/useNewConvo.ts) — New conversation lifecycle
  - [`useSubmitMessage.ts`](https://github.com/danny-avila/LibreChat/blob/main/client/src/hooks/Messages/useSubmitMessage.ts) — Single-entry message submission
  - [`useChatFunctions.ts`](https://github.com/danny-avila/LibreChat/blob/main/client/src/hooks/Chat/useChatFunctions.ts) — `ask()` core logic
  - [`useChatHelpers.ts`](https://github.com/danny-avila/LibreChat/blob/main/client/src/hooks/Chat/useChatHelpers.ts) — Chat state orchestration
  - [`conversation.ts`](https://github.com/danny-avila/LibreChat/blob/main/packages/data-schemas/src/methods/conversation.ts) — Server-side conversation CRUD
- **DevCompanion Repository**: [https://github.com/JooyoungChoi14/DevCompanion](https://github.com/JooyoungChoi14/DevCompanion)
  - [`AiChatViewModel.kt`](https://github.com/JooyoungChoi14/DevCompanion/blob/main/app/src/main/java/com/devcompanion/ui/AiChatViewModel.kt) — Chat state management
  - [`AiChatScreen.kt`](https://github.com/JooyoungChoi14/DevCompanion/blob/main/app/src/main/java/com/devcompanion/ui/AiChatScreen.kt) — Compose UI with LaunchedEffect triggers
  - [`MainActivity.kt`](https://github.com/JooyoungChoi14/DevCompanion/blob/main/app/src/main/java/com/devcompanion/ui/MainActivity.kt) — BottomSheet hosting + pendingAiQuestion
  - [`ChatHistory.kt`](https://github.com/JooyoungChoi14/DevCompanion/blob/main/app/src/main/java/com/devcompanion/llm/ChatHistory.kt) — Persistence layer
  - [`AgentLoop.kt`](https://github.com/JooyoungChoi14/DevCompanion/blob/main/app/src/main/java/com/devcompanion/llm/agent/AgentLoop.kt) — Agent state machine
- **Android Compose Docs**: [State and Jetpack Compose](https://developer.android.com/develop/ui/compose/state) — `remember` vs `rememberSaveable` vs `ViewModel` state survival scope