package com.klic.mobile.app.feature.chat.messagelist

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp

// §15.3 swipe-to-reply gesture geometry (dp).
private val ICON_APPEAR_AT = 20.dp     // reply icon starts fading in past this pull
private val TRIGGER_AT = 50.dp         // haptic + release-to-reply threshold
private val MAX_OVERSHOOT = 30.dp      // rubber-band asymptote past the trigger
private val ICON_SIZE = 34.dp

/**
 * §15.3: swipe a bubble LEFT to reply. Wraps one message row; the row's content
 * tracks the finger via a transform-only translation (no re-layout per frame),
 * with sublinear rubber-band resistance past the trigger distance. A reply icon
 * fades/scales in behind the moving content; one crisp haptic fires exactly when
 * the trigger threshold is crossed (latched — no refire while held past it).
 * Release past the threshold invokes [onReply]; release before springs back.
 *
 * §19.2: the gesture axis-locks against the vertical list scroll. It watches the
 * first movement past touch slop and only claims the pointer when that movement is
 * horizontally dominant (|dx| > |dy|) AND leftward; a vertical or vertical-dominant
 * drag is left completely unconsumed, so the enclosing LazyColumn wins the pan and
 * scrolls — even when the finger started on a large image/PDF/file bubble. Taps and
 * long-presses on the bubble keep working (nothing is consumed until an actual
 * horizontal swipe is recognised). This replaces `detectHorizontalDragGestures`,
 * which engaged on horizontal slop alone and could swallow a scroll that began with
 * a slight sideways drift over a big media bubble.
 */
@Composable
internal fun SwipeToReplyContainer(
    enabled: Boolean,
    onReply: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    Box(
        modifier
            .fillMaxWidth()
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val triggerPx = TRIGGER_AT.toPx()
                val overshootPx = MAX_OVERSHOOT.toPx()
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var accumX = 0f
                    var accumY = 0f
                    var engaged = false
                    // Axis-lock phase: accumulate movement without consuming anything until
                    // slop is crossed, then decide. Horizontal-dominant + leftward → own the
                    // gesture; anything else → bail and let the list scroll (§19.2).
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed || change.isConsumed) break
                        val delta = change.positionChange()
                        accumX += delta.x
                        accumY += delta.y
                        if (abs(accumX) < touchSlop && abs(accumY) < touchSlop) continue
                        if (abs(accumX) <= abs(accumY) || accumX >= 0f) {
                            // Vertical-dominant or rightward → yield the pan to the scroll.
                            return@awaitEachGesture
                        }
                        engaged = true
                        change.consume()
                        break
                    }
                    if (!engaged) return@awaitEachGesture

                    // Drag phase: track the finger, apply the rubber-band, latch the haptic.
                    var rawDx = accumX
                    var pastTrigger = false
                    var hapticFired = false
                    fun apply() {
                        val pull = (-rawDx).coerceAtLeast(0f)
                        val shown = if (pull <= triggerPx) pull
                        else triggerPx + overshootPx * (1f - exp(-(pull - triggerPx) / overshootPx))
                        pastTrigger = pull >= triggerPx
                        if (pastTrigger && !hapticFired) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            hapticFired = true
                        } else if (!pastTrigger) {
                            hapticFired = false
                        }
                        scope.launch { offsetX.snapTo(-shown) }
                    }
                    apply()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        rawDx += change.positionChange().x
                        change.consume()
                        apply()
                    }
                    if (pastTrigger) onReply()
                    scope.launch {
                        offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                    }
                }
            },
    ) {
        if (enabled) {
            // Reply icon revealed BEHIND the sliding content, riding along with it:
            // its centre sits at the row's right edge plus the current translation.
            val iconBg = MaterialTheme.colorScheme.surfaceVariant
            val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .size(ICON_SIZE)
                    .graphicsLayer {
                        val pull = -offsetX.value
                        val reveal = ((pull - ICON_APPEAR_AT.toPx()) /
                            (TRIGGER_AT.toPx() - ICON_APPEAR_AT.toPx())).coerceIn(0f, 1f)
                        alpha = reveal
                        scaleX = 0.6f + 0.4f * reveal
                        scaleY = scaleX
                        translationX = offsetX.value + size.width / 2f
                    }
                    .background(iconBg, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Reply,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Box(Modifier.graphicsLayer { translationX = offsetX.value }) {
            content()
        }
    }
}
