package com.klic.mobile.app.feature.chat.messagelist

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs

/**
 * §19.2: tap + long-press detection for the large media/file bubbles that never fights
 * the enclosing LazyColumn's vertical scroll. Unlike [combinedClickable] it does NOT
 * consume the press, and it abandons the gesture the moment the pointer travels past
 * touch slop — leaving that drag entirely to the list, so a scroll that STARTS on an
 * image / video / file bubble pans the conversation instead of being swallowed. Only an
 * in-place release fires [onTap]; an in-place hold fires [onLongPress].
 */
@Composable
internal fun Modifier.mediaTapGestures(
    onTap: () -> Unit,
    onLongPress: () -> Unit,
): Modifier {
    val currentTap by rememberUpdatedState(onTap)
    val currentLong by rememberUpdatedState(onLongPress)
    return this.pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        val longPressTimeout = viewConfiguration.longPressTimeoutMillis
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var travelX = 0f
            var travelY = 0f
            var draggedAway = false
            // Returns true on a clean in-place release; null on the long-press timeout;
            // sets draggedAway (and returns false) if the pointer leaves as a scroll/drag.
            val tapped = withTimeoutOrNull(longPressTimeout) {
                var released = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    // A null/consumed change means the list (or another node) claimed the
                    // pointer for a pan — never treat that as a tap.
                    if (change == null || change.isConsumed) { draggedAway = true; break }
                    if (!change.pressed) { released = true; break }
                    travelX += change.positionChange().x
                    travelY += change.positionChange().y
                    if (abs(travelX) > slop || abs(travelY) > slop) { draggedAway = true; break }
                }
                released
            }
            when {
                draggedAway -> Unit          // a scroll/drag — the LazyColumn owns it
                tapped == true -> currentTap()
                tapped == null -> currentLong()
                else -> Unit
            }
        }
    }
}
