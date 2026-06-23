# Chat Entry Fix Plan

## Problem Statement

Two UX bugs in LLM chat entry:

### Bug 1: Chat history invisible on entry
When entering chat with existing conversation restored, messages are loaded but not scrolled into view. Input field gets focus (IME rises), pushing messages above viewport. No scroll correction occurs because `LaunchedEffect(messages.size, currentResponse)` only fires on **change**, not on initial composition with pre-loaded messages.

### Bug 2: Session choice dialog timing-dependent
`onAskAi` uses `hasActiveChat = chatViewModel.messages.value.isNotEmpty()` as first branch. Since ViewModel init auto-restores last conversation, this is **always true** after app start. The URL-matching dialog (Route B) is effectively unreachable. Users never see "Resume / New session" even when they should.

Additionally, even with URL matching, a user switching between browser and chat on the **same page** would get the dialog every time — annoying when they're already in that conversation.

## Solution

### Fix 1: Scroll to bottom on conversation change + IME settle

In `AiChatScreen.kt`, change:
```kotlin
LaunchedEffect(messages.size, currentResponse) {
    if (messages.isNotEmpty() || currentResponse.isNotBlank()) {
        listState.animateScrollToItem(...)
    }
}
```
To:
```kotlin
// Scroll on conversation change (covers initial load and resume)
LaunchedEffect(conversationId) {
    if (messages.isNotEmpty()) {
        listState.scrollToItem(messages.size - 1)
    }
}

// Scroll on new messages / streaming (after initial load)
LaunchedEffect(messages.size, currentResponse) {
    if (messages.isNotEmpty() || currentResponse.isNotBlank()) {
        listState.animateScrollToItem(
            (messages.size + if (currentResponse.isNotBlank()) 1 else 0).coerceAtLeast(0)
        )
    }
}

// Scroll after IME appears (settles layout)
LaunchedEffect(imeBottom) {
    if (imeBottom > 0 && messages.isNotEmpty()) {
        listState.scrollToItem(messages.size - 1)
    }
}
```

Key change: `conversationId` key ensures scroll fires on conversation restore/switch. `imeBottom` ensures scroll correction after keyboard appears. Using `scrollToItem` (instant) instead of `animateScrollToItem` for initial positioning — animation is distracting on entry.

### Fix 2: Replace hasActiveChat with URL-first matching + conversationId check

In `MainActivity.kt`, change `onAskAi` from:
```kotlin
onAskAi = { question ->
    pendingAiQuestion = question
    val url = engineRef?.getUrl()
    currentUrlForChat = url
    val hasActiveChat = chatViewModel.messages.value.isNotEmpty()
    if (hasActiveChat) {
        showAiChat = true
    } else if (url != null && ChatHistory.normalizeUrlForMatch(url) != null) {
        showSessionChoice = true
    } else {
        forceNewSession = true
        showAiChat = true
    }
}
```
To:
```kotlin
onAskAi = { question ->
    pendingAiQuestion = question
    val url = engineRef?.getUrl()
    currentUrlForChat = url
    val currentConvId = chatViewModel.conversationId.value
    val matched = url?.let { ChatHistory.findConversationByUrl(context, it) }
    if (matched != null && matched.id != currentConvId) {
        // URL has a matching conversation that isn't currently active → ask
        matchedConversationId = matched.id
        showSessionChoice = true
    } else {
        // No match, or already in the matching conversation → open directly
        forceNewSession = matched == null
        showAiChat = true
    }
}
```

Logic:
- **Same conversation already active** (`matched.id == currentConvId`) → open directly, no dialog. Solves the "ask every time on same page" annoyance.
- **Different conversation matches this URL** → show dialog (Resume / New session).
- **No matching conversation** → new session, open directly.

Also update the session choice dialog's "no match found" branch — remove the `LaunchedEffect(Unit)` that auto-opens with `forceNewSession`, since we now pre-check URL matching in `onAskAi` and never reach `showSessionChoice = true` without a match.

## Files Changed

1. **`AiChatScreen.kt`** — scroll behavior (3 LaunchedEffects)
2. **`MainActivity.kt`** — `onAskAi` branch logic + session choice dialog cleanup

## Safety Checklist

1. **Single concern**: Each fix addresses one UX bug (scroll / dialog). No cross-concern changes.
2. **Side effect isolation**: Scroll changes only affect scroll timing. Dialog changes only affect entry routing. No shared state mutations.
3. **SSOT preserved**: `conversationId` was already the source of truth. `ChatHistory.findConversationByUrl` already existed. No new sources introduced.
4. **Rollback**: Both changes are additive (new LaunchedEffect, restructured if-else). Revert is clean.
5. **Cross-validation**: 
   - Scroll: verify on empty conversation, restored conversation, new message arrival, streaming.
   - Dialog: verify on same-page revisit, different-page revisit, no-history state, app restart with restore.