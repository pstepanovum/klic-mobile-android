package com.klicmobile.app.feature.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.klicmobile.app.calling.LiveKitVideo
import com.klicmobile.app.data.CallSession
import com.klicmobile.app.data.Network
import com.klicmobile.app.feature.KlicViewModel
import com.klicmobile.app.ui.components.AvatarView
import com.klicmobile.app.ui.components.CircleControl
import kotlinx.coroutines.launch

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
    val peerId by vm.callPeerId.collectAsState()
    val isVideo = call.kind == "VIDEO"
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

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val hasRemoteVideo = shouldShowVideo && remoteVideo != null
        if (hasRemoteVideo) {
            LiveKitVideo(manager.room, remoteVideo, Modifier.fillMaxSize())
        }

        if (pip.isInPipMode) {
            // Compacted into a PiP window: video only, no chrome. Fall back to a centred avatar
            // while the remote isn't sending video.
            if (!hasRemoteVideo) {
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
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!hasRemoteVideo) {
                        AvatarView(
                            url = peerId?.let { Network.avatarUrl(it) },
                            name = peerName,
                            size = 120.dp,
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    Text(peerName, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        callStatus,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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

            if (shouldShowVideo && cameraEnabled && localVideo != null) {
                LiveKitVideo(
                    manager.room, localVideo,
                    Modifier.padding(20.dp).size(110.dp, 160.dp).clip(RoundedCornerShape(18.dp))
                        .align(Alignment.TopEnd),
                )
            }
        }
    }
}
