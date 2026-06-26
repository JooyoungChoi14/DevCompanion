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
| **onDismissRequest** (swipe/back) | false | null | null | ⚠️ **NOT RESET** |
| **onDismiss** (X button inside AiChatScreen) | false | null | null | **false** (reset) |

**Bug**: Swipe dismiss leaves `forceNewSession=true` dangling. Next open skips URL matching incorrectly.
**Fix needed**: Both paths must reset forceNewSession to false.

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

### 3A. AI Chat Button Click (direct user tap)

```
AI Chat Button Click
│
├─ url = engine.getUrl()
├─ normalizedUrl = normalizeUrlForMatch(url)
├─ matchedConv = findConversationByUrl(url)
├─ hasActiveChat = messages.isNotEmpty()
│
├─ hasActiveChat? ─── YES ──→ showAiChat = true
│                              (reopen current conversation, IGNORE current URL)
│                              ⚠️ This means URL mismatch is invisible to user
│
├─ matchedConv != null? ─── YES ──→ matchedConversationId = matchedConv.id
│                                     showAiChat = true
│                                     (resume URL-matched conversation)
│
├─ normalizedUrl != null? ─── YES ──→ showSessionChoice = true
│                                       (real URL, no match — ask user)
│
└─ else ──→ forceNewSession = true
             showAiChat = true
             (about:blank, chrome://, etc. — new session)
```

**Design note**: `hasActiveChat` takes priority over URL matching. This is intentional for quick resume,
but means navigating to a new URL while an old conversation is active will reopen the old conversation.
See Section 7 for known issues.

### 3B. onAskAi (browser-to-chat bridge, e.g. BridgeServer)

```
onAskAi(question)
│
├─ url = engineRef?.getUrl()
├─ currentConvId = chatViewModel.conversationId.value
├─ matched = findConversationByUrl(url)
│
├─ matched != null && matched.id != currentConvId? ── YES ──→ matchedConversationId = matched.id
│                                                            showSessionChoice = true
│                                                            pendingAiQuestion = question
│
└─ else ──→ forceNewSession = (matched == null)
             showAiChat = true
             pendingAiQuestion = question
```

**Key difference from button click**: onAskAi checks `matched.id != currentConvId` (avoids re-opening
same conversation), and does NOT check `hasActiveChat`. It also sets `pendingAiQuestion` which the
button click does not.

### 3C. Session Choice Dialog (triggered by 3A or 3B)

```
showSessionChoice == true
│
├─ matched != null? ─── YES ──→ AlertDialog with:
│   "Resume"  → showSessionChoice = false, showAiChat = true
│   "New"     → showSessionChoice = false, matchedConversationId = null,
│               forceNewSession = true, showAiChat = true
│
└─ matched == null ──→ forceNewSession = true, matchedConversationId = null,
                        showSessionChoice = false, showAiChat = true
                        (immediate — no LaunchedEffect delay)
```

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
| 2 | `forceNewSession` not reset on swipe dismiss | **High** | onDismissRequest doesn't reset forceNewSession | Next open skips URL matching | Both dismiss paths must reset forceNewSession |
| 3 | about:blank conversation blocks URL matching | Medium | `hasActiveChat=true` from auto-restore with sourceUrl=null | Button always reopens about:blank conversation | Consider: skip auto-restore when sourceUrl=null and current URL is real |
| 4 | sourceUrl becomes stale during navigation | Low | sourceUrl not updated on URL change | Saved metadata says URL A, content relates to URL B | Consider: update sourceUrl on URL change, or make it immutable |
| 5 | Two different dismiss paths with different state cleanup | **High** | onDismissRequest vs onDismiss inconsistency | Swipe dismiss leaves dangling state | Unify: both paths must reset all composition state |
| 6 | onAskAi and button click use different decision trees | Medium | Code duplication without shared logic | Different URL matching and state management | Consider: extract shared logic into a single entry point |

## 8. State Transitions (Dismiss → Reopen)

When the bottom sheet is dismissed and reopened, these states persist (ViewModel):
- `conversationId`, `messages`, `_sourceUrl`, `initialPromptSent`

These states reset (composition):
- `showAiChat`, `showSessionChoice`, `matchedConversationId`, `currentUrlForChat`, `pendingAiQuestion`

**Bug**: `forceNewSession` is ONLY reset by `onDismiss` (X button), NOT by `onDismissRequest` (swipe/back).

### State at Reopen

```
After swipe dismiss:
  showAiChat = false ✅
  forceNewSession = (whatever it was before) ⚠️ BUG — should be false
  matchedConversationId = null ✅
  pendingAiQuestion = null ✅
  ViewModel state: unchanged

After X button dismiss:
  showAiChat = false ✅
  forceNewSession = false ✅
  matchedConversationId = null ✅
  pendingAiQuestion = null ✅
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

### onAskAi Call → (currently not logged — TODO)

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