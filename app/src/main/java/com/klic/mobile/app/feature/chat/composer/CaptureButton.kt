package com.klic.mobile.app.feature.chat.composer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider

/**
 * §16.2: the two-mode capture button. TAP toggles audio ↔ video-note mode (with a
 * small haptic); HOLD records in the current mode, tracking the finger so slides
 * left (cancel) and up (lock) work — the button itself rides along with the drag.
 */
@Composable
internal fun CaptureActionButton(
    mode: CaptureMode,
    held: Boolean,
    dragX: Float,
    dragY: Float,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldDrag: (Offset) -> RecordDragResult,
    onHoldEnd: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val scale by animateFloatAsState(if (held) 1.7f else 1f, label = "captureScale")

    Box(
        modifier = Modifier
            .size(44.dp)
            .graphicsLayer {
                translationX = if (held) dragX else 0f
                translationY = if (held) dragY else 0f
                scaleX = scale
                scaleY = scale
            }
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var releasedQuickly = false
                    var cancelledEarly = false
                    val beforeTimeout = withTimeoutOrNull(180L) {
                        val up = waitForUpOrCancellation()
                        if (up != null) releasedQuickly = true else cancelledEarly = true
                        true
                    }
                    when {
                        cancelledEarly -> return@awaitEachGesture
                        beforeTimeout != null && releasedQuickly -> {
                            // Quick tap → toggle mic ↔ camera with a small haptic.
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onTap()
                            return@awaitEachGesture
                        }
                    }
                    // Still pressed after 180ms → hold-to-record; track the drag.
                    onHoldStart()
                    var total = Offset.Zero
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || change.changedToUpIgnoreConsumed()) {
                            change?.consume()
                            onHoldEnd()
                            break
                        }
                        val delta = change.positionChange()
                        if (delta != Offset.Zero) {
                            change.consume()
                            total += delta
                            when (onHoldDrag(total)) {
                                RecordDragResult.LOCKED -> {
                                    // One crisp haptic exactly at the lock threshold.
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    break
                                }
                                RecordDragResult.CANCELED -> break
                                RecordDragResult.CONTINUE -> Unit
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (mode == CaptureMode.AUDIO) Icons.Filled.Mic else Icons.Filled.Videocam,
            contentDescription = if (mode == CaptureMode.AUDIO) "Hold to record audio" else "Hold to record video",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * §16.2: the floating padlock above the record button — open and tilted while
 * unlocked, closing/straightening as [lockProgress] → 1; [snap] > 0 renders the
 * just-locked pop (closed padlock scaling back down over ~250ms).
 */
@Composable
internal fun RecordPadlock(lockProgress: Float, snap: Float, modifier: Modifier = Modifier) {
    val closed = snap > 0f || lockProgress > 0.8f
    Box(
        modifier
            .size(40.dp)
            .graphicsLayer {
                val pop = 1f + 0.25f * snap
                scaleX = pop
                scaleY = pop
            }
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (closed) Icons.Filled.Lock else Icons.Filled.LockOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer { rotationZ = -18f * (1f - lockProgress.coerceIn(0f, 1f)) },
        )
    }
}

/** §16.2: helper tooltip anchored above the capture button. */
@Composable
internal fun CaptureModeTooltip(text: String) {
    Popup(
        popupPositionProvider = object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset = IntOffset(
                x = (anchorBounds.right - popupContentSize.width).coerceAtLeast(8),
                y = anchorBounds.top - popupContentSize.height - 12,
            )
        },
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            shadowElevation = 4.dp,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
