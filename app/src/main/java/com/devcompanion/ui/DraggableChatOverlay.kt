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
private val MinFraction = 0.3f
private val MaxFraction = 0.95f
private val DismissFraction = 0.15f

/**
 * Draggable chat overlay that sits on top of the browser content.
 *
 * Key behaviors:
 * - Browser remains visible behind the overlay (height fraction < 1.0)
 * - User can drag the top handle to resize the chat panel
 * - Input bar (inside AiChatScreen) is always pinned at bottom
 * - Last position is remembered across sessions via [UiPreferences]
 * - Swipe down fast / below threshold → dismiss
 *
 * Layout:
 * ```
 * ┌─────────────────────────────┐  ← Rounded top corners
 * │     ═══ (drag handle)       │  ← 28dp drag zone
 * ├─────────────────────────────┤
 * │     AiChatScreen content    │
 * │   (TopAppBar + messages +   │
 * │    input bar)                │
 * └─────────────────────────────┘  ← Bottom of screen
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

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val totalHeightPx = with(density) { maxHeight.toPx() }

        // Drag offset: positive = finger moving down = decrease overlay height
        var dragOffsetPx by remember { mutableFloatStateOf(0f) }

        // Compute effective fraction from base + drag offset
        val baseHeightPx = totalHeightPx * fraction
        val effectiveHeightPx = (baseHeightPx - dragOffsetPx)
            .coerceIn(totalHeightPx * MinFraction, totalHeightPx * MaxFraction)
        val effectiveFraction = effectiveHeightPx / totalHeightPx

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(fraction = effectiveFraction)
                .align(Alignment.BottomCenter)
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
                                val newFraction = effectiveFraction
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