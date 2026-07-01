package com.devcompanion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.devcompanion.logging.SessionLog

private val DragHandleHeight = 28.dp
private val HandleIndicatorWidth = 40.dp
private val HandleIndicatorHeight = 4.dp
private val HandleTopPadding = 8.dp
private const val MinFraction = 0.3f
private const val MaxFraction = 0.95f
private const val DismissFraction = 0.15f

/** Debug overlay flag — remove after fixing IME gap */
private const val DEBUG_OVERLAY = true

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
    // When keyboard is closed, this returns 0 (or navigation bar height on some devices).
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val imeHeightDp = with(density) { imeBottomPx.toDp() }

    // Also read navigation bar insets for comparison
    val navBarBottomPx = WindowInsets.navigationBars.getBottom(density)

    // Track the committed fraction to avoid "snap-back" when the parent
    // hasn't yet propagated the new fraction value.
    // After onDragEnd we set pendingFraction; once the incoming fraction
    // catches up we clear the offset.
    // NOTE: We intentionally mutate state in the composable body (not SideEffect)
    // because clearing must happen *before* this frame renders — SideEffect runs
    // after, which would cause a one-frame flicker.
    var pendingFraction by remember { mutableFloatStateOf(Float.NaN) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }

    // When the parent fraction updates to match our pending value, clear the offset.
    // Use approximate comparison to guard against float rounding in parent pipelines.
    if (pendingFraction.isNaN().not() && kotlin.math.abs(fraction - pendingFraction) < 0.001f) {
        dragOffsetPx = 0f
        pendingFraction = Float.NaN
    }

    // If a new fraction arrives from outside (e.g. IME change), snap offset to 0.
    if (pendingFraction.isNaN() && dragOffsetPx != 0f) {
        dragOffsetPx = 0f
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val totalHeightDp = maxHeight
        val totalHeightPx = with(density) { totalHeightDp.toPx() }
        val imeHeightPxCalc = with(density) { imeHeightDp.toPx() }

        // Available height = screen minus keyboard only (not navigation bar).
        // When keyboard is closed, imeHeightPxCalc should be 0 and overlay uses full height.
        val availableHeightPx = (totalHeightPx - imeHeightPxCalc).coerceAtLeast(0f)

        // During drag: position = fraction*avail - dragOffset
        // After drag ends but before parent updates: position = pendingFraction*avail
        // Once parent catches up: position = fraction*avail (dragOffset = 0)
        val displayFraction = if (pendingFraction.isNaN().not()) pendingFraction else fraction
        val baseOverlayHeightPx = availableHeightPx * displayFraction
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
                            onDragStart = {
                                dragOffsetPx = 0f
                                SessionLog.uiDrag("chat_overlay", fraction, fraction, "drag_start")
                            },
                            onDragEnd = {
                                val newFraction = if (availableHeightPx > 0f) {
                                    (effectiveOverlayHeightPx / availableHeightPx).coerceIn(MinFraction, MaxFraction)
                                } else {
                                    fraction
                                }
                                if (newFraction < DismissFraction) {
                                    SessionLog.uiDrag("chat_overlay", fraction, newFraction, "dismiss")
                                    onDismiss()
                                    dragOffsetPx = 0f
                                    pendingFraction = Float.NaN
                                } else {
                                    SessionLog.uiDrag("chat_overlay", fraction, newFraction, "drag_end")
                                    pendingFraction = newFraction
                                    onFractionChange(newFraction)
                                }
                            },
                            onDragCancel = {
                                SessionLog.uiDrag("chat_overlay", fraction, fraction, "drag_cancel")
                                dragOffsetPx = 0f
                                pendingFraction = Float.NaN
                            },
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

        // Debug overlay — shows inset values directly on screen
        if (DEBUG_OVERLAY) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(top = 50.dp)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.8f))
            ) {
                Text(
                    text = "DEBUG: total=${totalHeightPx.toInt()}px ime=${imeBottomPx}px nav=${navBarBottomPx}px avail=${availableHeightPx.toInt()}px frac=$fraction overlay=${effectiveOverlayHeightPx.toInt()}px y=${yOffsetPx.toInt()}px",
                    color = MaterialTheme.colorScheme.onError,
                    fontSize = 11.sp
                )
            }
        }
    }
}