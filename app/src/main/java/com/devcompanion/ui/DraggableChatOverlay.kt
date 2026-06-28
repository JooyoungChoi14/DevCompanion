package com.devcompanion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

private val DragHandleHeight = 28.dp
private val HandleIndicatorWidth = 40.dp
private val HandleIndicatorHeight = 4.dp
private val HandleTopPadding = 8.dp
private const val MinFraction = 0.3f
private const val MaxFraction = 0.95f
private const val DismissFraction = 0.15f

/**
 * Draggable chat overlay that sits on top of the browser content.
 *
 * Key behaviors:
 * - Browser remains visible behind the overlay (height fraction < 1.0)
 * - User can drag the top handle to resize the chat panel
 * - Input bar (inside AiChatScreen) is always pinned at bottom
 * - Last position is remembered across sessions via [UiPreferences]
 * - Swipe down fast / below threshold → dismiss
 * - IME-aware: overlay bottom aligns to top of soft keyboard
 *
 * Layout:
 * ```
 * ┌─────────────────────────────┐  ← Rounded top corners
 * │     ═══ (drag handle)       │  ← 28dp drag zone
 * ├─────────────────────────────┤
 * │     AiChatScreen content    │
 * │   (TopAppBar + messages +   │
 * │    input bar)                │
 * └─────────────────────────────┘  ← Bottom aligns to keyboard top
 * ```
 */
@Composable
fun DraggableChatOverlay(
    fraction: Float,
    onFractionChange: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current

    // Read IME height so we can account for it in overlay sizing.
    // When the keyboard is open, the overlay should fill from the top of the screen
    // down to the top of the keyboard, not extend behind it.
    val imeHeightDp = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    val imeHeightPx = with(density) { imeHeightDp.toPx() }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenHeightPx = with(density) { maxHeight.toPx() }
        // Available height = screen height - keyboard height (0 if keyboard closed)
        val availableHeightPx = screenHeightPx - imeHeightPx

        // Drag offset: positive = finger moving down = decrease overlay height
        var dragOffsetPx by remember { mutableFloatStateOf(0f) }

        // Compute overlay height as fraction of AVAILABLE space (not full screen).
        // This way the overlay bottom edge sits exactly at the keyboard top.
        val baseOverlayHeightPx = availableHeightPx * fraction
        val effectiveOverlayHeightPx = (baseOverlayHeightPx - dragOffsetPx)
            .coerceIn(availableHeightPx * MinFraction, availableHeightPx * MaxFraction)

        // Convert back to a fraction of FULL screen height for fillMaxHeight.
        // This is needed because fillMaxHeight works relative to the parent (full screen).
        val effectiveFraction = if (screenHeightPx > 0f) {
            (effectiveOverlayHeightPx / screenHeightPx).coerceIn(0.1f, 1.0f)
        } else {
            fraction
        }

        // Bottom padding = IME height, so the overlay sits above the keyboard
        val imeBottomPadding = imeHeightDp

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(fraction = effectiveFraction)
                .align(Alignment.BottomCenter)
                .padding(bottom = imeBottomPadding)
                .shadow(elevation = 16.dp, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(MaterialTheme.colorScheme.surface)
        ) {
            // Drag handle zone at the top
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(DragHandleHeight)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { dragOffsetPx = 0f },
                            onDragEnd = {
                                // Recompute fraction relative to available height
                                val newFraction = if (availableHeightPx > 0f) {
                                    (effectiveOverlayHeightPx / availableHeightPx).coerceIn(MinFraction, MaxFraction)
                                } else {
                                    fraction
                                }
                                if (newFraction < DismissFraction) {
                                    onDismiss()
                                } else {
                                    onFractionChange(newFraction)
                                }
                                dragOffsetPx = 0f
                            },
                            onDragCancel = {
                                dragOffsetPx = 0f
                            },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetPx += dragAmount
                            }
                        )
                    },
                contentAlignment = Alignment.TopCenter
            ) {
                // Visual drag indicator pill
                Box(
                    modifier = Modifier
                        .padding(top = HandleTopPadding)
                        .width(HandleIndicatorWidth)
                        .height(HandleIndicatorHeight)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                )
            }

            // Chat content below the drag handle
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = DragHandleHeight)
            ) {
                content()
            }
        }
    }
}