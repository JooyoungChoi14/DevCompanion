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
 * - IME-aware: overlay shrinks when keyboard opens
 *
 * Layout:
 * ```
 * ┌─────────────────────────────┐  ← Rounded top corners
 * │     ═══ (drag handle)       │  ← 28dp drag zone
 * ├─────────────────────────────┤
 * │     AiChatScreen content    │
 * │   (TopAppBar + messages +   │
 * │    input bar)                │
 * └─────────────────────────────┘  ← Bottom of available space (above keyboard)
 * │         (keyboard)          │  ← Not part of overlay
 * ```
 *
 * IME handling: When the keyboard is open, the overlay height is calculated
 * relative to the available space (screen - keyboard), and the overlay is
 * positioned at the bottom of the available space using offset(y).
 * This avoids the double-subtraction bug that caused the overlay to shrink
 * to a thin strip.
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

    // Read IME height to position overlay above keyboard
    val imeHeightPx = with(density) {
        WindowInsets.ime.getBottom(density).toFloat().toDp().toPx()
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenHeightPx = with(density) { maxHeight.toPx() }

        // When keyboard is open, available space is screen minus keyboard.
        // When closed, available space equals full screen.
        val availableHeightPx = (screenHeightPx - imeHeightPx).coerceAtLeast(0f)

        // Drag offset: positive = finger moving down = decrease overlay height
        var dragOffsetPx by remember { mutableFloatStateOf(0f) }

        // Compute overlay height as fraction of available space
        val baseOverlayHeightPx = availableHeightPx * fraction
        val effectiveOverlayHeightPx = (baseOverlayHeightPx - dragOffsetPx)
            .coerceIn(availableHeightPx * MinFraction, availableHeightPx * MaxFraction)

        // Position overlay at the bottom of available space (above keyboard)
        // by using offset from top of parent.
        val yOffsetPx = availableHeightPx - effectiveOverlayHeightPx
        val overlayHeightDp = with(density) { effectiveOverlayHeightPx.toDp() }
        val yOffsetDp = with(density) { yOffsetPx.toDp() }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(overlayHeightDp)
                .offset(y = yOffsetDp)
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