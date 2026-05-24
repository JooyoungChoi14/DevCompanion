# Touch Event Map — DevCompanion

> SSOT for all touch/click interactions on chat messages.
> Last updated: 2026-05-24 (commit `9ea2edf`)

## MessageBubble Touch Events

### Current Implementation

```
MessageBubble
├── Outer Row
│   ├── Background (if selected in select mode)
│   └── Modifier.clickable (select mode only — tap toggles selection)
│       ├── [Select Mode]  clickable → onToggleSelect(message.id)
│       └── [Normal Mode]  no touch handler — scroll works naturally
├── Checkbox (select mode only, onCheckedChange = null — display-only)
├── Message content Column
│   └── (no touch handlers — content is static text/rendered markdown)
└── TokenUsage badge (no touch)
```

### Select Mode Entry/Exit

**Entry**: TopAppBar `SelectAll` icon → `isInSelectMode = true`, `frozenSelectableIds = messages.map { it.id }.toSet()`
**Exit**:
- TopAppBar `Close` icon (replaces SelectAll in select mode)
- Bottom action bar `Close` button (redundant but discoverable)
- System Back button
- Export complete
- Conversation change

> **Design decision**: Select mode is entered via explicit toggle, NOT long-press gesture.
> Compose's pointer event pass architecture (Initial → Main → Final) makes reliable
> long-press/short-tap disambiguation inside LazyColumn fundamentally broken.
> 4 build/test cycles confirmed this is a platform constraint, not a bug.

### State Transitions

```
                    ☑ icon tap
  ┌──────────┐ ──────────────► ┌──────────────┐
  │ Normal   │                 │ Select Mode   │
  │ Mode     │ ◄────────────── │               │
  └──────────┘  ✕ / Back /     └──────────────┘
                  Export done /      ▲  │
                  Conv change        │  │ tap (toggle)
                               ┌────┘  │
                               │  ┌─────┘
                               │  │
                          onToggleSelect(id)
                          → selectedMessageIds ± id
```

### Touch Behavior Matrix

| Mode | Gesture | Action | Side Effects |
|------|---------|--------|-------------|
| Normal | Tap | None | — |
| Normal | Scroll | LazyColumn scroll | — |
| Select | Tap | `onToggleSelect(id)` | `selectedMessageIds ± id` |
| Select | Scroll | LazyColumn scroll | — |
| Any | TopAppBar ☑ icon | Toggle select mode | `isInSelectMode`, `frozenSelectableIds` |

### Frozen Selectable IDs

When select mode is entered, `frozenSelectableIds` captures the current message IDs.
This prevents "Select all" from breaking when new streaming messages arrive.
New messages during streaming will NOT have checkboxes until the user exits and re-enters select mode.
This matches Gmail's behavior (new emails don't auto-appear in selection).

### Exit Select Mode Triggers

| Trigger | `isInSelectMode` | `selectedMessageIds` | `frozenSelectableIds` |
|---------|------------------|---------------------|----------------------|
| TopAppBar ✕ | `false` | `emptySet()` | `null` |
| Bottom bar ✕ | `false` | `emptySet()` | `null` |
| System Back | `false` | `emptySet()` | `null` |
| Export complete | `false` | `emptySet()` | `null` |
| Conversation change | `false` | `emptySet()` | `null` |

---

## ConversationDrawer Touch Events

| Mode | Target | Gesture | Action |
|------|--------|---------|--------|
| Normal | Conversation card | Tap | `onSelect(conv.id)` → load conversation |
| Normal | SelectAll icon | Tap | Enter select mode |
| Select | Conversation card | Tap | Toggle `selectedIds ± conv.id` |
| Select | Checkbox | Tap | Toggle `selectedIds ± conv.id` |
| Any | Delete icon | Tap | Set `conversationToDelete = conv.id` |

> **Consistency**: Both ConversationDrawer and MessageBubble now use explicit
> `SelectAll` icon for entering select mode. No gesture-based entry anywhere.

---

## Other Touch Events in AiChatScreen

| Target | Gesture | Action |
|--------|---------|--------|
| Drawer menu button | Tap | Open drawer |
| Select mode toggle (☑/✕) | Tap | Toggle `isInSelectMode` |
| Agent/Normal mode toggle | Tap | `viewModel.toggleAgentMode()` |
| Capture button (hidden in select mode) | Tap | Show capture dialog |
| Send button | Tap | Send message |
| Cancel (streaming) | Tap | Cancel streaming / stop agent |
| Export selected | Tap | Export + exit select mode |
| Select all/Deselect all | Tap | Toggle all message IDs (frozen set) |
| Close select mode (✕) | Tap | Exit select mode |
| Auto-capture banner | Tap | Disable auto-capture |
| Confirmation buttons | Tap | Respond to agent confirmation |
| Provider dropdown | Tap | Expand provider selector |
| Settings gear | Tap | Open settings |

---

## Design Decisions

1. **Explicit toggle over gesture**: Long-press/short-tap disambiguation in LazyColumn is broken on Compose. 4 attempts (combinedClickable, manual pointerInput + down.consume, Initial pass intercept, detectTapGestures) all failed. Explicit toggle is the correct UX.
2. **Consistent with ConversationDrawer**: Both use `SelectAll` icon for entry, `Close` for exit.
3. **`frozenSelectableIds`**: Prevents "Select all" from breaking during streaming. Gmail pattern.
4. **Camera hidden during select mode**: Select and capture are mutually exclusive actions.
5. **`Checkbox.onCheckedChange = null`**: Display-only, prevents double-toggle with clickable modifier.
6. **No touch handler in normal mode**: Scroll works naturally without gesture conflicts.
7. **`clickable` in select mode only**: Simple tap toggle, no gesture disambiguation needed.

---

## Change Log

| Date | Commit | Change |
|------|--------|--------|
| 2026-05-24 | `9ea2edf` | Replace gesture-based select mode with explicit SelectAll toggle |
| 2026-05-24 | `9ea2edf` | Remove longPressTimeoutMs setting, EventType.GESTURE |
| 2026-05-24 | `9ea2edf` | Freeze selectable IDs at mode entry |
| 2026-05-21 | `4be19b4` | Split onSelect → onEnterSelectMode + onToggleSelect |
| 2026-05-21 | `04c16ca` | Configurable long-press timeout (now removed) |