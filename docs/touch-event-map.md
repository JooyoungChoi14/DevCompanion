# Touch Event Map — DevCompanion

> SSOT for all touch/click interactions on chat messages.
> Last updated: 2026-05-21 (commit `4be19b4`)

## MessageBubble Touch Events

### Current Implementation

```
MessageBubble
├── Outer Row
│   ├── Background (if selected in select mode)
│   └── pointerInput(isSelectMode, isStreaming)
│       ├── [Select Mode]  awaitFirstDown → waitForUpOrCancellation → onToggleSelect
│       └── [Normal Mode]   awaitFirstDown → withTimeout(longPressTimeout) → waitForUpOrCancellation
│           ├── up == null (timeout) → onEnterSelectMode  [LONG-PRESS]
│           └── up != null (short tap) → do nothing       [SHORT TAP]
├── Checkbox (select mode only, onCheckedChange = null — display-only)
├── Message content Column
│   └── (no touch handlers — content is static text/rendered markdown)
└── TokenUsage badge (no touch)
```

### State Transitions

```
                    long-press
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

| Mode | Gesture | Threshold | Event Consumed | Action | Side Effects |
|------|---------|-----------|----------------|--------|-------------|
| Normal | Short tap (< longPressTimeoutMs) | `longPressTimeoutMs` | No | None | — |
| Normal | Long press (≥ longPressTimeoutMs) | `longPressTimeoutMs` | Up only | `onEnterSelectMode(id)` | `isInSelectMode = true`, `selectedMessageIds + id` |
| Select | Tap (any duration) | None | Up only | `onToggleSelect(id)` | `selectedMessageIds ± id` |
| Select | Scroll drag | — | No (down not consumed) | LazyColumn scroll | — |
| Normal | Scroll drag | — | No (down not consumed) | LazyColumn scroll | — |
| Any | Tap during streaming | — | Up only | No action (`!isStreaming` guard) | — |

### Exit Select Mode Triggers

| Trigger | `isInSelectMode` | `selectedMessageIds` |
|---------|------------------|---------------------|
| ✕ button | `false` | `emptySet()` |
| System Back | `false` | `emptySet()` |
| Export complete | `false` | `emptySet()` |
| Conversation change | `false` | `emptySet()` |

### Known Issues (current)

1. **`longPressTimeoutMs` default = 1500ms (configurable)** — users can adjust in Settings (0.3s–5s); 400ms Android default was too short, causing accidental mode entry
2. **No haptic/audio feedback on long-press** — user has no signal that the threshold was crossed until the UI state changes

---

## ConversationDrawer Touch Events

| Mode | Target | Gesture | Action |
|------|--------|---------|--------|
| Normal | Conversation card | Tap | `onSelect(conv.id)` → load conversation |
| Select | Conversation card | Tap | Toggle `selectedIds ± conv.id` |
| Any | Delete icon | Tap | Set `conversationToDelete = conv.id` |

---

## Other Touch Events in AiChatScreen

| Target | Gesture | Action |
|--------|---------|--------|
| Drawer menu button | Tap | Open drawer |
| Agent/Normal mode toggle | Tap | `viewModel.toggleAgentMode()` |
| Capture button | Tap | Show capture dialog |
| Send button | Tap | Send message |
| Cancel (streaming) | Tap | Cancel streaming / stop agent |
| Export selected | Tap | Export + exit select mode |
| Select all/Deselect all | Tap | Toggle all message IDs |
| Close select mode (✕) | Tap | Exit select mode |
| Auto-capture banner | Tap | Disable auto-capture |
| Confirmation buttons | Tap | Respond to agent confirmation |
| Provider dropdown | Tap | Expand provider selector |
| Settings gear | Tap | Open settings |

---

## Design Decisions

1. **`onSelect` split into `onEnterSelectMode` + `onToggleSelect`** — prevents short tap from accidentally entering select mode
2. **`down` not consumed in normal mode** — allows LazyColumn to detect scroll drag
3. **`Checkbox.onCheckedChange = null`** — display-only, prevents double-toggle with `pointerInput`
4. **`isInSelectMode` independent from `selectedMessageIds`** — Gmail pattern, mode persists at 0 selections
5. **No `combinedClickable`** — avoids Compose's built-in long-press handling which conflicts with custom `pointerInput` gesture detection
6. **Long-press timeout is user-configurable** — `UiPreferences.longPressTimeoutMs` (default 1.5s, range 0.3-5s) via SettingsSheet slider

---

## Change Log

| Date | Commit | Change |
|------|--------|--------|
| 2026-05-21 | `e45f416` | Fix checkbox flicker (key stabilization) + tap-to-toggle in select mode |
| 2026-05-21 | `f5eaa59` | Decouple select mode state (Gmail pattern) + BackHandler + Select All |
| 2026-05-21 | `5a13f0d` | Review fixes: "0 selected" text, Export disabled, selectableCount |
| 2026-05-21 | `875e7a3` | Export timestamp fix (conversation metadata, not selected range) |
| 2026-05-21 | `737fbe7` | Select-all set equality + remember(selectableIds) |
| 2026-05-21 | `4be19b4` | Split onSelect → onEnterSelectMode + onToggleSelect |
| 2026-05-21 | `04c16ca` | Configurable long-press timeout (default 1.5s) + Settings UI + SSOT doc |