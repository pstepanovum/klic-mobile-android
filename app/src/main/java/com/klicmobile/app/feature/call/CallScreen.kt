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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.klicmobile.app.calling.LiveKitVideo
import com.klicmobile.app.data.CallSession
import com.klicmobile.app.data.Network
import com.klicmobile.app.feature.KlicViewModel
import com.klicmobile.app.ui.components.AvatarView
import com.klicmobile.app.ui.components.CircleControl
import com.klicmobile.app.ui.theme.KlicIcons
import kotlinx.coroutines.launch

@Composable
fun CallScreen(vm: KlicViewModel, call: CallSession, peerName: String, onEnd: () -> Unit) {
    val scope = rememberCoroutineScope()
    val manager = vm.callManager
    val callStatus by vm.callStatus.collectAsState()
    val micEnabled by manager.micEnabled.collectAsState()
    val cameraEnabled by manager.cameraEnabled.collectAsState()
    val remoteVideo by manager.remoteVideoTrack.collectAsState()
    val localVideo by manager.localVideoTrack.collectAsState()
    val peerId by vm.callPeerId.collectAsState()
    val isVideo = call.kind == "VIDEO"
    val shouldShowVideo = cameraEnabled || localVideo != null || remoteVideo != null

    LaunchedEffect(call.callId) {
        runCatching {
            manager.join(call.livekitUrl, call.token, video = isVideo)
        }.onSuccess {
            vm.onCallMediaJoined(call.callId)
        }.onFailure {
            vm.onCallJoinFailed(call.callId)
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (shouldShowVideo && remoteVideo != null) {
            LiveKitVideo(manager.room, remoteVideo, Modifier.fillMaxSize())
        }

        Column(
            Modifier.fillMaxSize().padding(vertical = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (!(shouldShowVideo && remoteVideo != null)) {
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
                    painter = painterResource(if (micEnabled) KlicIcons.mic else KlicIcons.micOff),
                    contentDescription = "Toggle microphone",
                ) { scope.launch { manager.toggleMic() } }

                CircleControl(
                    painter = painterResource(KlicIcons.callEnd),
                    contentDescription = "End call",
                    fill = MaterialTheme.colorScheme.error,
                    tint = MaterialTheme.colorScheme.onError,
                    diameter = 72,
                ) { vm.endCall(); onEnd() }

                CircleControl(
                    painter = painterResource(if (cameraEnabled) KlicIcons.camera else KlicIcons.cameraOff),
                    contentDescription = "Toggle camera",
                ) { scope.launch { manager.toggleCamera() } }
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
