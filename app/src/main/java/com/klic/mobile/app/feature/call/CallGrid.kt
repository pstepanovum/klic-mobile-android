package com.klic.mobile.app.feature.call

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.calling.LiveKitVideo
import com.klic.mobile.app.ui.components.AvatarView
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import com.klic.mobile.app.R

/** §17.1: Zoom-style group-call grid that NEVER scrolls — rows/columns derive from
 *  [tileCount] (2 → 1×2 stacked, 3-4 → 2×2, 5-6 → 2×3, 7-9 → 3×3, …) and the tiles shrink
 *  so every one always fits the available area. A short last row keeps the regular tile
 *  size and centers, matching the column-centered idiom of the rest of the call screen. */
@Composable
internal fun NonScrollingCallGrid(
    tileCount: Int,
    modifier: Modifier = Modifier,
    tile: @Composable (index: Int) -> Unit,
) {
    if (tileCount <= 0) return
    BoxWithConstraints(modifier) {
        val spacing = 10.dp
        val columns = when {
            tileCount <= 2 -> 1
            tileCount <= 6 -> 2
            tileCount <= 12 -> 3
            else -> 4
        }
        val rows = (tileCount + columns - 1) / columns
        val tileWidth = (maxWidth - spacing * (columns - 1)) / columns
        val tileHeight = (maxHeight - spacing * (rows - 1)) / rows
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(spacing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for (row in 0 until rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    for (column in 0 until columns) {
                        val index = row * columns + column
                        if (index < tileCount) {
                            Box(Modifier.width(tileWidth).height(tileHeight)) { tile(index) }
                        }
                    }
                }
            }
        }
    }
}

/** One tile in the group grid — a remote participant or my own feed (§17.1): video (or the
 *  camera-off avatar), name pill with mute badge, dimmed + veiled while reconnecting, and an
 *  animated accent glow while its participant is the active speaker (short linger so brief
 *  pauses don't flicker). §9.7: every tile gets a name, never a blank pill — the caller
 *  resolves LiveKit metadata → cached member list → generic fallback. */
@Composable
internal fun CallGridTile(
    room: Room?,
    videoTrack: VideoTrack?,
    displayName: String,
    avatarUrl: String?,
    micMuted: Boolean,
    isSpeaking: Boolean,
    modifier: Modifier = Modifier,
    avatarName: String = displayName,
    mirrorVideo: Boolean = false,
    reconnecting: Boolean = false,
) {
    var speakingLingers by remember { mutableStateOf(false) }
    LaunchedEffect(isSpeaking, reconnecting) {
        if (isSpeaking && !reconnecting) {
            speakingLingers = true
        } else {
            delay(400)
            speakingLingers = false
        }
    }
    val glow by animateFloatAsState(
        targetValue = if (speakingLingers) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "speakingGlow",
    )
    Box(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (glow > 0.01f) {
                    Modifier.border(
                        2.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = glow),
                        RoundedCornerShape(18.dp),
                    )
                } else Modifier
            ),
    ) {
        if (videoTrack != null) {
            LiveKitVideo(room, videoTrack, Modifier.fillMaxSize(), mirror = mirrorVideo)
        } else {
            Box(
                Modifier.fillMaxSize().alpha(if (reconnecting) 0.4f else 1f),
                contentAlignment = Alignment.Center,
            ) {
                AvatarView(url = avatarUrl, name = avatarName, size = 64.dp)
            }
        }
        if (reconnecting) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.call_status_reconnecting), style = MaterialTheme.typography.labelMedium, color = Color.White)
            }
        }
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                displayName,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (micMuted) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Filled.MicOff,
                    contentDescription = "Muted",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
