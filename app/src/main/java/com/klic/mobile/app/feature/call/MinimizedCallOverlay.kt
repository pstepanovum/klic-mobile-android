package com.klic.mobile.app.feature.call

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.calling.LiveKitVideo
import com.klic.mobile.app.feature.KlicViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val CallAccentGreen = Color(0xFF10B981)

/**
 * Floating widget shown at the app root while the in-call screen is minimized: a small
 * remote-video tile on video calls, a compact name + live-timer pill on voice calls.
 * Tap restores the full call screen; drag moves it, snapping to the nearest horizontal edge.
 * Renders nothing once the call ends (activeCall goes null), so no auto-restore is needed.
 */
@Composable
fun MinimizedCallOverlay(vm: KlicViewModel, onRestore: () -> Unit) {
    val call by vm.activeCall.collectAsState()
    val minimized by vm.callMinimized.collectAsState()
    if (call == null || !minimized) return

    val manager = vm.callManager
    val remoteVideo by manager.remoteVideoTrack.collectAsState()
    val peerName by vm.callPeerName.collectAsState()
    val connectedAt by vm.callConnectedAt.collectAsState()
    val callStatus by vm.callStatus.collectAsState()

    // Live mm:ss since connect; before that (or while reconnecting) show the status text.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val subtitle = connectedAt?.let {
        val s = ((nowMs - it) / 1000).coerceAtLeast(0)
        "%d:%02d".format(s / 60, s % 60)
    } ?: callStatus

    BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()
        val marginPx = with(density) { 12.dp.toPx() }
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val maxHeightPx = with(density) { maxHeight.toPx() }
        var itemSize by remember { mutableStateOf(IntSize.Zero) }
        val offsetX = remember { Animatable(0f) }
        val offsetY = remember { Animatable(0f) }
        var placed by remember { mutableStateOf(false) }

        fun xBounds(width: Int) = marginPx..(maxWidthPx - width - marginPx).coerceAtLeast(marginPx)
        fun yBounds(height: Int) = marginPx..(maxHeightPx - height - marginPx).coerceAtLeast(marginPx)

        val hasVideo = remoteVideo != null
        Box(
            Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt()) }
                // Hidden for the first frame, until the initial corner position is applied.
                .alpha(if (placed) 1f else 0f)
                .onSizeChanged { size ->
                    if (size.width == 0) return@onSizeChanged
                    val first = !placed
                    itemSize = size
                    scope.launch {
                        if (first) {
                            // Start snapped under the top-right corner.
                            offsetX.snapTo(xBounds(size.width).endInclusive)
                            offsetY.snapTo((marginPx + with(density) { 48.dp.toPx() }).coerceIn(yBounds(size.height)))
                            placed = true
                        } else {
                            // Re-clamp when the widget changes shape (video tile ↔ pill).
                            offsetX.snapTo(offsetX.value.coerceIn(xBounds(size.width)))
                            offsetY.snapTo(offsetY.value.coerceIn(yBounds(size.height)))
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, drag ->
                            change.consume()
                            scope.launch {
                                offsetX.snapTo((offsetX.value + drag.x).coerceIn(xBounds(itemSize.width)))
                                offsetY.snapTo((offsetY.value + drag.y).coerceIn(yBounds(itemSize.height)))
                            }
                        },
                        onDragEnd = {
                            val bounds = xBounds(itemSize.width)
                            val target = if (offsetX.value + itemSize.width / 2f < maxWidthPx / 2f) {
                                bounds.start
                            } else {
                                bounds.endInclusive
                            }
                            scope.launch { offsetX.animateTo(target) }
                        },
                    )
                }
                .clip(if (hasVideo) RoundedCornerShape(18.dp) else CircleShape)
                .clickable { onRestore() },
        ) {
            val video = remoteVideo
            if (video != null) {
                Box(Modifier.size(120.dp, 160.dp).background(Color.Black)) {
                    LiveKitVideo(manager.room, video, Modifier.fillMaxSize())
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            } else {
                Row(
                    Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Filled.PhoneInTalk,
                        contentDescription = "Return to call",
                        tint = CallAccentGreen,
                        modifier = Modifier.size(18.dp),
                    )
                    Column {
                        Text(
                            peerName,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 160.dp),
                        )
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = CallAccentGreen,
                        )
                    }
                }
            }
        }
    }
}
