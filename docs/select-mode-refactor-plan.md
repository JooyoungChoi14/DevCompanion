# Select Mode UX Refactor Plan

## Problem
Long-press/short-tap gesture differentiation in LazyColumn is fundamentally broken on Compose. 4 build/test cycles proved that Compose's pointer event pass architecture (Initial → Main → Final) makes reliable gesture disambiguation inside scrollable containers impossible. This is a platform constraint, not a bug.

## Current State: Two Inconsistent Select Modes

| | ConversationDrawer (sidebar) | MessageBubble (chat) |
|---|---|---|
| Entry | ✅ Explicit `SelectAll` icon in TopAppBar | ❌ Hidden long-press gesture (broken) |
| Exit | ✕ button / Back | ✕ button / Back |
| Toggle | Card tap | Checkbox tap |
| Select all | No (individual only) | Select all / Deselect all |

## Proposed Changes

### 1. Remove gesture-based select mode entry from MessageBubble
- Delete all `pointerInput` / `detectTapGestures` / `combinedClickable` from MessageBubble
- Delete `CompositionLocalProvider(LocalViewConfiguration)` override
- Delete `onEnterSelectMode` parameter — no longer needed
- MessageBubble normal mode: no touch handlers at all (scroll works naturally)
- MessageBubble select mode: `clickable` toggle only (already works)

### 2. Add explicit select mode toggle to TopAppBar
- Replace camera (📷) icon with `Icons.Default.Checklist` (or `SelectAll`)
- Tap → `isInSelectMode = !isInSelectMode`
- When in select mode: icon changes to `Icons.Default.Close` (exit)
- Camera capture moves to:
  - Conditionally visible only in Agent (Act) mode
  - Or moved to overflow menu / long-press on status bar

### 3. Align ConversationDrawer select mode UX
- ConversationDrawer already uses explicit `SelectAll` icon → consistent
- Add "Deselect all" option when items are selected (parity with messages)

### 4. Simplify MessageBubble signature
**Before:**
```kotlin
fun MessageBubble(
    message: ChatMessage,
    isStreaming: Boolean,
    webView: WebView?,
    injectedStyles: SnapshotStateMap<String, String>,
    isSelected: Boolean,
    isSelectMode: Boolean,
    longPressTimeoutMs: Long,
    onEnterSelectMode: (String) -> Unit,
    onToggleSelect: (String) -> Unit
)
```

**After:**
```kotlin
fun MessageBubble(
    message: ChatMessage,
    isStreaming: Boolean,
    webView: WebView?,
    injectedStyles: SnapshotStateMap<String, String>,
    isSelected: Boolean,
    isSelectMode: Boolean,
    onToggleSelect: (String) -> Unit  // only needed in select mode
)
```

### 5. Delete obsolete code
- `longPressTimeoutMs` parameter and `UiPreferences.longPressTimeoutMs` settings UI
- `touch-event-map.md` — rewrite for new explicit UX
- SessionLog EventType.GESTURE entries for long-press/short-tap

## Files to Change
1. `AiChatScreen.kt` — TopAppBar, MessageBubble, select mode state
2. `UiPreferences.kt` — remove `longPressTimeoutMs` setting
3. `SettingsSheet.kt` — remove timeout slider
4. `touch-event-map.md` — rewrite for explicit UX
5. `SessionLog.kt` — keep EventType.GESTURE but remove long-press/short-tap entries

## UX Flow After Changes

### Normal Mode
- TopAppBar: `[X] [≡] [View|Act] [☑] [⚙]`
- Tap ☑ → enter select mode, first message auto-selected? No, just enter mode with empty selection
- Tap message → nothing (same as before)

### Select Mode
- TopAppBar: `[✕] [≡] [View|Act] [_selected] [⚙]` (✕ replaces ☑)
- Action bar below: "n selected" | Select all/Deselect all | Export | ✕
- Tap message → toggle selection
- Back button → exit select mode