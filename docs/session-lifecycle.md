# Session & Lifecycle Integration Map

> SSOT for browser session, chat history, URL, and user interaction lifecycle.
> Refer to this document when modifying any code that crosses these boundaries.

## 1. Component Overview

| Component | Lifecycle Scope | Persisted? | Key State |
|---|---|---|---|
| BrowserEngine | Activity | No (in-memory) | URL, scroll position, DOM |
| AiChatViewModel | Activity (ViewModel) | Partial | conversationId, messages, sourceUrl |
| ChatHistory | Disk (JSON files) | Yes | conversation files with sourceUrl |
| ModalBottomSheet | Composition | No | showAiChat, showSessionChoice |
| SessionLog | Process + Disk | Yes | Event log (JSONL) |

## 2. Key State Variables

### MainActivity (composition state)

```
showAiChat: Boolean         — whether chat bottom sheet is visible
showSessionChoice: Boolean  — whether session resume dialog is showing
matchedConversationId: String?  — ID of URL-matched conversation (for dialog)
currentUrlForChat: String?  — URL at time of chat button click
forceNewSession: Boolean    — skip URL matching, start fresh
pendingAiQuestion: String?  — pre-filled question from onAskAi
```

### AiChatViewModel

```
conversationId: StateFlow<String>  — active conversation ID
messages: StateFlow<List<ChatMessage>>  — messages in active conversation
sourceUrl: String?           — URL associated with current conversation
initialPromptSent: Boolean   — prevents re-sending prompt on recomposition
```

## 3. AI Chat Button Click Decision Tree

```
AI Chat Button Click
│
├─ url = engine.getUrl()
├─ normalizedUrl = normalizeUrlForMatch(url)
├─ matchedConv = findConversationByUrl(url)
├─ hasActiveChat = messages.isNotEmpty()
│
├─ hasActiveChat? ─── YES ──→ showAiChat = true
│                              (reopen current conversation)
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

### Session Choice Dialog Paths

```
showSessionChoice == true
│
├─ matched != null? ─── YES ──→ AlertDialog with:
│   "Resume"  → showSessionChoice = false, showAiChat = true
│   "New"     → showSessionChoice = false, forceNewSession = true, showAiChat = true
│
└─ matched == null ──→ forceNewSession = true, showAiChat = true, showSessionChoice = false
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

## 5. ViewModel Init: Auto-Restore

On creation, `AiChatViewModel.init` restores the most recent conversation:

```
conversations = listConversations()  // sorted by updatedAt descending
lastConv = conversations.firstOrNull()
if (lastConv != null) {
    conversationId = lastConv.id
    messages = load(lastConv.id)
    sourceUrl = lastConv.sourceUrl
}
```

**Implication**: After app restart, `messages` is non-empty → `hasActiveChat = true`.
This means the chat button will always reopen the last conversation,
regardless of current URL. This is intentional for quick resume,
but can show an unrelated conversation if the user navigated to a different site.

## 6. Conversation SourceUrl Assignment

```
sourceUrl is set when:
  - newConversation(sourceUrl) called with current browser URL
  - loadConversation() restores sourceUrl from saved metadata
  - setSourceUrl(url) called from AiChatScreen LaunchedEffect(sourceUrl)

sourceUrl is NOT updated when:
  - User navigates to a new URL in the browser while chat is open
  - User types a message in an existing conversation
```

## 7. Common Bugs & Their Lifecycle Roots

| Bug | Root Cause | Fix Strategy |
|---|---|---|
| Chat opens then immediately closes | `showAiChat` set true then false in same composition frame. LaunchedEffect delay or recomposition race. | Set state immediately, no deferred effects |
| Wrong conversation shown | `hasActiveChat = true` from auto-restore, URL ignored | Consider URL-aware matching even with active chat |
| Session choice dialog not shown | `matchedConversationId` null because `findConversationByUrl` not called before setting `showSessionChoice` | Call `findConversationByUrl` at click time |
| about:blank triggers session dialog | `normalizeUrlForMatch("about:blank")` returned non-null | Return null for about:blank |
| Input field not visible | IME padding, bottom sheet height, or composition race | Log UI_DATA_SNAPSHOT at entry to diagnose |

## 8. Logging Contract

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

## 9. Test Scenarios

| # | Scenario | Expected |
|---|---|---|
| 1 | Fresh app, about:blank, tap AI Chat | New session, no dialog |
| 2 | Fresh app, navigate to google.com, tap AI Chat | New session (no prior conv for google.com) |
| 3 | Existing conv for google.com, navigate to google.com, tap AI Chat | Resume matched conversation |
| 4 | Active conv (any URL), tap AI Chat | Reopen current conversation |
| 5 | Active conv for google.com, navigate to naver.com, tap AI Chat | Show current conv (URL mismatch is OK — user chose to continue) |
| 6 | App restart, tap AI Chat | Restore last conversation |