package com.klic.mobile.app.feature.call

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.calling.LiveKitVideo
import com.klic.mobile.app.calling.RemoteCallParticipant
import com.klic.mobile.app.data.CallSession
import com.klic.mobile.app.data.Network
import com.klic.mobile.app.feature.KlicViewModel
import com.klic.mobile.app.ui.components.AvatarView
import com.klic.mobile.app.ui.components.CircleControl
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun CallScreen(vm: KlicViewModel, call: CallSession, peerName: String, onEnd: () -> Unit) {
    val scope = rememberCoroutineScope()
    val manager = vm.callManager
    val callStatus by vm.callStatus.collectAsState()
    val micEnabled by manager.micEnabled.collectAsState()
    val cameraEnabled by manager.cameraEnabled.collectAsState()
    val speakerOn by manager.speakerOn.collectAsState()
    val remoteVideo by manager.remoteVideoTrack.collectAsState()
    val localVideo by manager.localVideoTrack.collectAsState()
    val participants by manager.participants.collectAsState()
    val peerId by vm.callPeerId.collectAsState()
    val isVideo = call.kind == "VIDEO"
    // 2+ remotes → tile grid; 0–1 remotes → today's fullscreen 1:1 layout.
    val gridMode = participants.size >= 2
    val shouldShowVideo = cameraEnabled || localVideo != null || remoteVideo != null

    // Trigger the join; the actual connect runs on CallManager's own scope, so it survives this
    // screen leaving the composition (which used to cancel it mid-connect on the emulator).
    LaunchedEffect(call.callId) {
        manager.join(
            call.callId, call.livekitUrl, call.token, video = isVideo,
            onJoined = { vm.onCallMediaJoined(it) },
            onFailed = { vm.onCallJoinFailed(it) },
        )
    }

    // Don't let the screen dim/lock while the call UI is up.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val pip = LocalPipController.current
    var localFullscreen by remember { mutableStateOf(false) }
    var cardOffset by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current

    // Pick which feed is full-screen and which rides in the draggable card (WhatsApp-style swap).
    // In grid mode the remotes live in tiles, so the card always carries the local preview.
    val localTrack = if (cameraEnabled) localVideo else null
    val primaryIsLocal = !gridMode && localFullscreen && localTrack != null
    val primaryTrack = if (primaryIsLocal) localTrack else remoteVideo
    val secondaryTrack = if (gridMode) localTrack else if (primaryIsLocal) remoteVideo else localTrack
    val hasPrimaryVideo = !gridMode && shouldShowVideo && primaryTrack != null

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (hasPrimaryVideo) {
            LiveKitVideo(manager.room, primaryTrack, Modifier.fillMaxSize())
        }

        if (pip.isInPipMode) {
            // Compacted into a PiP window: video only, no chrome. Fall back to a centred avatar
            // while there's no full-screen video (grid mode shows the first live remote feed).
            val pipTrack = if (gridMode) participants.firstNotNullOfOrNull { it.videoTrack } else primaryTrack
            if (gridMode && pipTrack != null) {
                LiveKitVideo(manager.room, pipTrack, Modifier.fillMaxSize())
            } else if (!hasPrimaryVideo && pipTrack == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AvatarView(url = peerId?.let { Network.avatarUrl(it) }, name = peerName, size = 64.dp)
                }
            }
        } else {
            // "Compact" the call into a PiP window so the rest of Klic stays usable.
            if (pip.supported) {
                Box(Modifier.align(Alignment.TopStart).padding(16.dp)) {
                    CircleControl(
                        painter = rememberVectorPainter(Icons.Filled.PictureInPictureAlt),
                        contentDescription = "Compact call",
                        diameter = 44,
                    ) { pip.enter() }
                }
            }

            Column(
                Modifier.fillMaxSize().padding(vertical = 56.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Over video: the name is plain white text and only the status ("Connected") gets a
                // small white pill. On a voice/avatar call, use theme colors with no pill.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!hasPrimaryVideo && !gridMode) {
                        AvatarView(
                            url = peerId?.let { Network.avatarUrl(it) },
                            name = peerName,
                            size = 120.dp,
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    Text(
                        peerName,
                        style = MaterialTheme.typography.titleLarge,
                        color = if (hasPrimaryVideo) Color.White else MaterialTheme.colorScheme.onBackground,
                    )
                    if (hasPrimaryVideo) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            callStatus,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.Black.copy(alpha = 0.8f),
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.White)
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    } else {
                        Text(callStatus, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (gridMode) {
                    // 2-column grid of remote tiles (video, or avatar + name); the local
                    // preview keeps riding in the floating card below.
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(participants, key = { it.userId }) { participant ->
                            ParticipantTile(vm, participant)
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircleControl(
                        painter = rememberVectorPainter(if (micEnabled) Icons.Filled.Mic else Icons.Filled.MicOff),
                        contentDescription = "Toggle microphone",
                    ) { scope.launch { manager.toggleMic() } }

                    // Speaker / earpiece toggle — shown on a voice call (video defaults to speaker).
                    if (!shouldShowVideo) {
                        CircleControl(
                            painter = rememberVectorPainter(if (speakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.Filled.PhoneInTalk),
                            contentDescription = "Toggle speaker",
                            fill = if (speakerOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            tint = if (speakerOn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        ) { manager.toggleSpeaker() }
                    }

                    CircleControl(
                        painter = rememberVectorPainter(Icons.Filled.CallEnd),
                        contentDescription = "End call",
                        fill = MaterialTheme.colorScheme.error,
                        tint = MaterialTheme.colorScheme.onError,
                        diameter = 72,
                    ) { vm.endCall(); onEnd() }

                    CircleControl(
                        painter = rememberVectorPainter(if (cameraEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff),
                        contentDescription = "Toggle camera",
                    ) { scope.launch { manager.toggleCamera() } }

                    if (cameraEnabled) {
                        CircleControl(
                            painter = rememberVectorPainter(Icons.Filled.Cameraswitch),
                            contentDescription = "Switch camera",
                        ) { manager.switchCamera() }
                    }
                }
            }

        }

        // Draggable, tap-to-swap picture-in-picture card for the secondary feed.
        if (!pip.isInPipMode && shouldShowVideo && secondaryTrack != null) {
            val leftLimitPx = with(density) { (maxWidth - 150.dp).toPx() }.coerceAtLeast(0f)
            val downLimitPx = with(density) { (maxHeight - 320.dp).toPx() }.coerceAtLeast(0f)
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(cardOffset.x.roundToInt(), cardOffset.y.roundToInt()) }
                    .padding(20.dp)
                    .size(110.dp, 160.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            cardOffset = Offset(
                                (cardOffset.x + drag.x).coerceIn(-leftLimitPx, 0f),
                                (cardOffset.y + drag.y).coerceIn(0f, downLimitPx),
                            )
                        }
                    }
                    .clickable { if (!gridMode) localFullscreen = !localFullscreen },
            ) {
                LiveKitVideo(manager.room, secondaryTrack, Modifier.fillMaxSize())
                if (!gridMode) {
                    Icon(
                        imageVector = Icons.Filled.OpenInFull,
                        contentDescription = "Expand",
                        tint = Color.White,
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(16.dp),
                    )
                }
            }
        }
    }
}

/** One remote in the group grid: their video (or avatar), name, mute badge — dimmed while
 *  they sit in their reconnect grace window. */
@Composable
private fun ParticipantTile(vm: KlicViewModel, participant: RemoteCallParticipant) {
    val manager = vm.callManager
    val displayName = participant.name.ifBlank { "Unknown" }
    Box(
        Modifier
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (participant.isSpeaking && !participant.reconnecting) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp))
                } else Modifier
            ),
    ) {
        if (participant.videoTrack != null) {
            LiveKitVideo(manager.room, participant.videoTrack, Modifier.fillMaxSize())
        } else {
            Box(
                Modifier.fillMaxSize().alpha(if (participant.reconnecting) 0.4f else 1f),
                contentAlignment = Alignment.Center,
            ) {
                AvatarView(
                    url = Network.avatarUrl(participant.userId),
                    name = displayName,
                    size = 64.dp,
                )
            }
        }
        if (participant.reconnecting) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("Reconnecting…", style = MaterialTheme.typography.labelMedium, color = Color.White)
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
            if (participant.micMuted) {
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
