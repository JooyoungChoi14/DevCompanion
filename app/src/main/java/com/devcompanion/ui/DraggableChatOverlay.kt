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

import com.devcompanion.logging.SessionLog

private val DragHandleHeight = 28.dp
private val HandleIndicatorWidth = 40.dp
private val HandleIndicatorHeight = 4.dp
private val HandleTopPadding = 8.dp
private const val MinFraction = 0.3f
private const val MaxFraction = 0.95f
private const val DismissFraction = 0.15f
private const val SnapThresholdPx = 8f



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
 * fraction of (screen - keyboard). When IME toggles, we adjust the fraction
 * so that the visual pixel height stays constant — no snap-back.
 *
 * Stale closure fix: fraction is tracked via rememberUpdatedState so the
 * gesture handler always reads the latest value without restarting.
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

    // Use rememberUpdatedState so the gesture handler (pointerInput) always
    // reads the latest fraction without needing to restart the coroutine.
    val currentFraction by rememberUpdatedState(fraction)

    // Track the previous available height to detect IME toggles.
    // When IME opens/closes, we adjust fraction so the overlay's pixel height
    // stays the same — preventing snap-back.
    var prevAvailableHeightPx by remember { mutableFloatStateOf(Float.NaN) }
    var adjustedFraction by remember { mutableFloatStateOf(fraction) }

    // Sync adjustedFraction when the parent fraction changes (e.g. after drag commit).
    // But NOT during IME transitions — those are handled by the height-preservation logic.
    var lastSyncedFraction by remember { mutableFloatStateOf(fraction) }
    if (kotlin.math.abs(fraction - lastSyncedFraction) > 0.001f) {
        adjustedFraction = fraction
        lastSyncedFraction = fraction
    }

    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val totalHeightDp = maxHeight
        val totalHeightPx = with(density) { totalHeightDp.toPx() }
        val imeHeightPxCalc = with(density) { imeHeightDp.toPx() }

        // Available height = screen minus keyboard only (not navigation bar).
        // When keyboard is closed, imeHeightPxCalc should be 0 and overlay uses full height.
        val availableHeightPx = (totalHeightPx - imeHeightPxCalc).coerceAtLeast(0f)

        // Detect IME toggle: preserve pixel height across available height changes.
        // When IME opens, available height shrinks — we increase fraction so
        // the overlay stays the same visual height. When IME closes, reverse.
        if (!isDragging && !prevAvailableHeightPx.isNaN() && prevAvailableHeightPx > 0f &&
            kotlin.math.abs(availableHeightPx - prevAvailableHeightPx) > 1f) {
            val prevPixelHeight = prevAvailableHeightPx * adjustedFraction
            val newFraction = if (availableHeightPx > 0f) {
                (prevPixelHeight / availableHeightPx).coerceIn(MinFraction, MaxFraction)
            } else {
                adjustedFraction
            }
            adjustedFraction = newFraction
            lastSyncedFraction = newFraction
            onFractionChange(newFraction)
        }
        prevAvailableHeightPx = availableHeightPx

        // During drag: position = fraction*avail - dragOffset
        // Use adjustedFraction to account for IME transitions.
        val displayFraction = adjustedFraction
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
                                isDragging = true
                                dragOffsetPx = 0f
                                // Use currentFraction (rememberUpdatedState) to get latest value
                                val f = currentFraction
                                SessionLog.uiDrag("chat_overlay", f, f, "drag_start")
                            },
                            onDragEnd = {
                                isDragging = false
                                // Use currentFraction (rememberUpdatedState) to get latest value
                                val fractionNow = currentFraction
                                // Calculate new fraction from current drag offset
                                val currentOffset = dragOffsetPx
                                val currentHeight = (availableHeightPx * fractionNow - currentOffset)
                                    .coerceIn(availableHeightPx * MinFraction, availableHeightPx * MaxFraction)
                                val newFraction = if (availableHeightPx > 0f) {
                                    (currentHeight / availableHeightPx).coerceIn(MinFraction, MaxFraction)
                                } else {
                                    fractionNow
                                }
                                if (newFraction < DismissFraction) {
                                    SessionLog.uiDrag("chat_overlay", fractionNow, newFraction, "dismiss")
                                    onDismiss()
                                    dragOffsetPx = 0f
                                } else {
                                    SessionLog.uiDrag("chat_overlay", fractionNow, newFraction, "drag_end_offset=${currentOffset.toInt()}px")
                                    // Update adjustedFraction immediately
                                    adjustedFraction = newFraction
                                    lastSyncedFraction = newFraction
                                    dragOffsetPx = 0f
                                    onFractionChange(newFraction)
                                }
                            },
                            onDragCancel = {
                                isDragging = false
                                val f = currentFraction
                                SessionLog.uiDrag("chat_overlay", f, f, "drag_cancel")
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