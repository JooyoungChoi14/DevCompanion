# Session & Lifecycle Integration Map

> **SSOT for browser session, chat history, URL, and user interaction lifecycle.**
> Refer to this document when modifying any code that crosses these boundaries.
> When code and document conflict, update the document FIRST, then align the code.

## 1. Component Overview

| Component | Lifecycle Scope | Persisted? | Key State | Clears on Dismiss? |
|---|---|---|---|---|
| BrowserEngine | Activity | No (in-memory) | URL, scroll position, DOM | N/A (independent) |
| AiChatViewModel | Activity (ViewModel) | Partial | conversationId, messages, sourceUrl | No — persists across show/hide |
| ChatHistory | Disk (JSON files) | Yes | conversation files with sourceUrl | N/A (persistent store) |
| ModalBottomSheet | Composition | No | showAiChat, showSessionChoice | Yes — all state reset |
| SessionLog | Process + Disk | Yes | Event log (JSONL) | N/A (append-only) |

**Key insight**: ViewModel state (conversationId, messages) persists across bottom sheet show/hide cycles.
Composition state (showAiChat, forceNewSession, etc.) resets on dismiss.
This asymmetry is the root of several bugs.

## 2. Key State Variables

### MainActivity (composition state — resets on sheet dismiss)

```
showAiChat: Boolean         — whether chat bottom sheet is visible
showSessionChoice: Boolean  — whether session resume dialog is showing
matchedConversationId: String?  — ID of URL-matched conversation (for dialog)
currentUrlForChat: String?  — URL at time of chat button click
forceNewSession: Boolean    — skip URL matching, start fresh
pendingAiQuestion: String?  — pre-filled question from onAskAi
```

#### State Reset on Dismiss (TWO paths, DIFFERENT behavior)

| Path | showAiChat | pendingAiQuestion | matchedConversationId | forceNewSession |
|---|---|---|---|---|
| **onDismissRequest** (swipe/back) | false | null | null | **false** (reset) |
| **onDismiss** (X button inside AiChatScreen) | false | null | null | **false** (reset) |

**Both paths now reset all composition state consistently.** Previously, swipe dismiss did not reset
`forceNewSession`, causing URL matching to be skipped on next open. Fixed in commit 959d157.

**Note**: `currentUrlForChat` is NOT reset by either dismiss path. This is acceptable because it's
re-set on every chat button click. However, stale URL state persists if the sheet is reopened
via `onAskAi` without a button click.

### AiChatViewModel (ViewModel state — persists across sheet show/hide)

```
conversationId: StateFlow<String>  — active conversation ID (observable)
messages: StateFlow<List<ChatMessage>>  — messages in active conversation (observable)
private var _sourceUrl: String?    — URL associated with current conversation (NOT observable)
initialPromptSent: Boolean         — prevents re-sending prompt on recomposition
```

**`_sourceUrl` is NOT a StateFlow** — it's a private `var`. Changes are not observed by composables.
`setSourceUrl()` exists but only called from `AiChatScreen.LaunchedEffect(sourceUrl)`.
This means UI cannot reactively respond to sourceUrl changes.

## 3. Chat Entry Decision Trees

### 3A. Single Entry Point: ChatSessionResolver.resolve()

Both the AI chat button click and `onAskAi` callback use the same
`ChatSessionResolver.resolve()` function. The `hasQuestion` parameter
differentiates the two entry points.

```
ChatSessionResolver.resolve(url, context, currentConvId, hasActiveChat, hasQuestion)
│
├─ Path 1: hasActiveChat && !hasQuestion
│   → open_existing: showAiChat=true (reopen current conversation, skip URL matching)
│
├─ Path 2: matchedConv != null && matchedConv.id != currentConvId
│   → hasQuestion? show_session_choice : resume_matched
│     (onAskAi shows dialog, button click resumes directly)
│
├─ Path 3: matchedConv != null && matchedConv.id == currentConvId
│   → open_current: showAiChat=true (already in the right conversation)
│
├─ Path 4: normalizedUrl != null && matchedConv == null
│   → hasQuestion? new_session : show_session_choice
│     (onAskAi opens directly, button click shows dialog)
│
└─ Path 5: else (about:blank, chrome://, null URL)
    → new_session: forceNewSession=true, showAiChat=true
```

**Key difference between hasQuestion=true and hasQuestion=false:**
- `hasQuestion=false` (button click): Path 1 short-circuits (hasActiveChat wins)
- `hasQuestion=true` (onAskAi): Path 1 is skipped (always goes through URL matching)
- Path 2 and 4: `hasQuestion=true` avoids dialogs when possible (direct open)
- Path 4: `hasQuestion=true` opens new session directly without dialog

## 4. URL Normalization Rules (`normalizeUrlForMatch`)

| Input | Output | Reason |
|---|---|---|
| `about:blank` | `null` | Not a real page — no session matching |
| `chrome://*` | `null` | Internal browser page |
| `data:*` | `null` | Inline data |
| `https://example.com/path?query=1#frag` | `https://example.com/path` | Strip query & fragment |
| `https://example.com/` | `https://example.com/` | Root path preserved |
| `null` / unparsable | `null` | Safety fallback |

**Important**: `findConversationByUrl()` calls `normalizeUrlForMatch()` on both the input URL
and each saved `sourceUrl`. Two URLs match if their normalized forms are identical.

## 5. ViewModel Init: Auto-Restore

On creation, `AiChatViewModel.init` restores the most recent conversation:

```
conversations = listConversations()  // sorted by updatedAt descending
lastConv = conversations.firstOrNull()
if (lastConv != null) {
    conversationId = lastConv.id
    messages = load(lastConv.id)
    _sourceUrl = lastConv.sourceUrl   // Note: private var, not observable
}
```

**Implication**: After app restart, `messages` is non-empty → `hasActiveChat = true`.
This means the chat button will always reopen the last conversation,
regardless of current URL.

**about:blank conversation trap**: If a conversation was started on about:blank,
`sourceUrl` is null. After navigating to a real URL, `hasActiveChat` is still true,
so the button click reopens the about:blank conversation. URL matching is never reached.

## 6. SourceUrl Lifecycle

```
sourceUrl is set when:
  - newConversation(sourceUrl) called with current browser URL
  - loadConversation() restores sourceUrl from saved metadata
  - setSourceUrl(url) called from AiChatScreen LaunchedEffect(sourceUrl)

sourceUrl is NOT updated when:
  - User navigates to a new URL in the browser while chat is open
  - User types a message in an existing conversation

sourceUrl type: private var String? (NOT StateFlow)
  - Changes are NOT observed by composables
  - This means UI cannot reactively respond to sourceUrl changes
```

**Known issue**: If chat is open for URL A, user navigates to URL B in browser,
sourceUrl remains A while auto-capture provides context from B.
The saved conversation metadata (sourceUrl) will say A, but the content relates to B.
This affects URL-based session matching reliability.

## 7. Known Issues & Edge Cases

| # | Issue | Severity | Root Cause | Current Behavior | Desired Behavior |
|---|---|---|---|---|---|
| 1 | Wrong conversation after URL change | Medium | `hasActiveChat` priority over URL matching | Reopens last conversation regardless of current URL | Consider: check URL match even with active chat, or show conversation title |
| 2 | ~~`forceNewSession` not reset on swipe dismiss~~ | ~~**High**~~ | ~~onDismissRequest didn't reset forceNewSession~~ | ~~Next open skips URL matching~~ | Both dismiss paths now reset forceNewSession | **FIXED (959d157)** |
| 3 | about:blank conversation blocks URL matching | Medium | `hasActiveChat=true` from auto-restore with sourceUrl=null | Button always reopens about:blank conversation | Consider: skip auto-restore when sourceUrl=null and current URL is real |
| 4 | sourceUrl becomes stale during navigation | Low | sourceUrl not updated on URL change | Saved metadata says URL A, content relates to URL B | Consider: update sourceUrl on URL change, or make it immutable |
| 5 | ~~Two different dismiss paths with different state cleanup~~ | ~~**High**~~ | ~~onDismissRequest vs onDismiss inconsistency~~ | ~~Swipe dismiss leaves dangling state~~ | Both paths now consistent | **FIXED (959d157)** |
| 6 | ~~onAskAi and button click use different decision trees~~ | ~~Medium~~ | ~~Code duplication without shared logic~~ | ~~Different URL matching and state management~~ | Now use ChatSessionResolver | **FIXED (ef6f10a)** |
| 7 | `currentUrlForChat` not reset on dismiss | Low | Only re-set on button click, not on onAskAi | Stale URL if sheet reopened via onAskAi | Reset in both dismiss paths or move to ViewModel |
| 8 | HalfExpanded sheet state trap | Medium | `skipPartiallyExpanded=false` allows half-open state | User stuck in liminal sheet state, no re-expand affordance | Consider `skipPartiallyExpanded=true` or add expand gesture |
| 9 | Button click doesn't check `pendingAiQuestion` | Low | No mutual exclusion between button click and onAskAi | onAskAi-set prompt could be discarded by button tap | Guard: skip button logic if pendingAiQuestion is set |

## 8. State Transitions (Dismiss → Reopen)

When the bottom sheet is dismissed and reopened, these states persist (ViewModel):
- `conversationId`, `messages`, `_sourceUrl`, `initialPromptSent`

These states reset on dismiss (composition):
- `showAiChat`, `showSessionChoice`, `matchedConversationId`, `pendingAiQuestion`, `forceNewSession`

**Note**: `currentUrlForChat` does NOT reset on dismiss — it's re-set on every button click or onAskAi call. Stale values persist between dismiss/reopen cycles.

**Both dismiss paths now reset all composition state consistently** (fixed in 959d157).
`currentUrlForChat` is NOT reset by either path — it's re-set on every button click.

### State at Reopen

```
After any dismiss (swipe, back, or X button):
  showAiChat = false ✅
  forceNewSession = false ✅ (reset by both paths since 959d157)
  matchedConversationId = null ✅
  pendingAiQuestion = null ✅
  currentUrlForChat = (stale — re-set on next button click) ⚠️
  ViewModel state: unchanged
```

## 9. Logging Contract

### AI Chat Button Click → `UI_CLICK` event

```
target: "ai_chat_btn"
detail: "has_url" | "no_url"
url: current browser URL (truncated to 100 chars)
normalizedUrl: result of normalizeUrlForMatch (or "null")
hasActiveChat: messages.isNotEmpty()
messagesCount: messages.size
currentConvId: first 8 chars of conversationId
matchedConvId: first 8 chars of matched conversation ID (or "null")
matchedConvSourceUrl: URL of matched conversation (or "null")
decision: "open_existing" | "resume_matched" | "show_session_choice" | "new_session"
```

### onAskAi Call → `UI_CLICK` event

```
target: "on_ask_ai_resolve"
decision: "show_session_choice" | "open_current" | "new_session"
url: current browser URL (truncated to 100 chars, or "null")
normalizedUrl: result of normalizeUrlForMatch (or "null")
hasActiveChat: messages.isNotEmpty()
currentConvId: first 8 chars of conversationId
matchedConvId: first 8 chars of matched conversation ID (or "null")
```

(Logged inside ChatSessionResolver.resolve() along with the button click resolution)

### Chat Screen Entry → `UI_DATA_SNAPSHOT` event

```
messages: count of messages in current conversation
conversations: total saved conversations
isStreaming: whether streaming is active
provider: display name of current provider
model: current model name
```

### Session Choice Fallback → `UI_CLICK` event

```
target: "session_choice_fallback"
detail: "no_match_open_new"
```

## 10. Test Scenarios

| # | Scenario | Expected | Priority |
|---|---|---|---|
| 1 | Fresh app, about:blank, tap AI Chat | New session, no dialog | P0 |
| 2 | Fresh app, navigate to google.com, tap AI Chat | New session (no prior conv) | P0 |
| 3 | Existing conv for google.com, navigate to google.com, tap AI Chat | Resume matched conversation | P0 |
| 4 | Active conv (any URL), tap AI Chat | Reopen current conversation | P0 |
| 5 | Active conv for google.com, navigate to naver.com, tap AI Chat | Reopen current conv (URL mismatch) — user can start new from drawer | P1 |
| 6 | App restart, tap AI Chat | Restore last conversation | P0 |
| 7 | Swipe dismiss chat, reopen chat | Should NOT skip URL matching (forceNewSession must be false) | P0 (bug) |
| 8 | X button dismiss chat, reopen chat | Normal flow, forceNewSession reset | P0 |
| 9 | onAskAi with matching conversation (different from current) | Show session choice dialog | P1 |
| 10 | onAskAi with no matching conversation | Open new session directly with prompt | P1 |
| 11 | about:blank conversation exists, navigate to real URL, tap AI Chat | Currently reopens about:blank conv (bug) | P1 |
| 12 | Chat open on URL A, navigate to URL B in browser, send message | Message saved with sourceUrl=A but context from B | P2 |