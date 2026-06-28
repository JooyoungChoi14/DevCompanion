package com.devcompanion.ui

import android.util.Log
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

private const val TAG = "DraggableChatOverlay"

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
 * - IME-aware: overlay shrinks to fit above keyboard, no gap
 *
 * IME strategy: Read WindowInsets.ime directly. Compute overlay height as
 * fraction of (screen - keyboard). Position with height+offset so bottom
 * aligns exactly to keyboard top.
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

    // WindowInsets.ime.getBottom(density) returns pixels (Int).
    // Convert: px → Dp (divide by density) → then use in calculations.
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val imeHeightDp = with(density) { imeBottomPx.toDp() }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val totalHeightDp = maxHeight
        val totalHeightPx = with(density) { totalHeightDp.toPx() }
        val imeHeightPxCalc = with(density) { imeHeightDp.toPx() }

        // Available height = screen minus keyboard. 0 when keyboard closed.
        val availableHeightPx = (totalHeightPx - imeHeightPxCalc).coerceAtLeast(0f)

        // Log values for debugging
        LaunchedEffect(totalHeightPx, imeHeightPxCalc) {
            Log.d(TAG, "totalHeight=${totalHeightPx}px(${totalHeightDp}), " +
                    "imeHeight=${imeHeightPxCalc}px(${imeHeightDp}), " +
                    "available=${availableHeightPx}px, " +
                    "fraction=$fraction, " +
                    "overlayH=${availableHeightPx * fraction}px")
        }

        var dragOffsetPx by remember { mutableFloatStateOf(0f) }

        val baseOverlayHeightPx = availableHeightPx * fraction
        val effectiveOverlayHeightPx = (baseOverlayHeightPx - dragOffsetPx)
            .coerceIn(availableHeightPx * MinFraction, availableHeightPx * MaxFraction)

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
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(DragHandleHeight)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { dragOffsetPx = 0f },
                            onDragEnd = {
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
                            onDragCancel = { dragOffsetPx = 0f },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetPx += dragAmount
                            }
                        )
                    },
                contentAlignment = Alignment.TopCenter
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = HandleTopPadding)
                        .width(HandleIndicatorWidth)
                        .height(HandleIndicatorHeight)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                )
            }

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