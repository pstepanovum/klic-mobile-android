package com.klic.mobile.app.feature.call

import android.app.Activity
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.StopScreenShare
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.calling.LiveKitVideo
import com.klic.mobile.app.data.CallSession
import com.klic.mobile.app.data.Network
import com.klic.mobile.app.feature.KlicViewModel
import com.klic.mobile.app.ui.components.AvatarView
import com.klic.mobile.app.ui.components.CircleControl
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.klic.mobile.app.R

@Composable
fun CallScreen(
    vm: KlicViewModel,
    call: CallSession,
    peerName: String,
    onMinimize: () -> Unit,
    onEnd: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val manager = vm.callManager
    val callStatus by vm.callStatus.collectAsState()
    val micEnabled by manager.micEnabled.collectAsState()
    val cameraEnabled by manager.cameraEnabled.collectAsState()
    val screenShareEnabled by manager.screenShareEnabled.collectAsState()
    val speakerOn by manager.speakerOn.collectAsState()
    val remoteVideo by manager.remoteVideoTrack.collectAsState()
    val screenShare by manager.screenShareTrack.collectAsState()
    val localVideo by manager.localVideoTrack.collectAsState()
    val participants by manager.participants.collectAsState()
    val localSpeaking by manager.localSpeaking.collectAsState()
    val frontCamera by manager.frontCamera.collectAsState()
    val me by vm.currentUser.collectAsState()
    val peerId by vm.callPeerId.collectAsState()
    val isGroupCall by vm.callIsGroup.collectAsState()
    val connectedAt by vm.callConnectedAt.collectAsState()
    val isVideo = call.kind == "VIDEO"
    // Someone sharing their screen takes over as the big tile — camera feeds become secondary,
    // so the equal grid steps aside in favour of the fullscreen screen-share presentation.
    val screenSharePrimary = screenShare != null
    // 2+ remotes → tile grid; 0–1 remotes → today's fullscreen 1:1 layout.
    val gridMode = !screenSharePrimary && participants.size >= 2
    val shouldShowVideo = cameraEnabled || localVideo != null || remoteVideo != null || screenSharePrimary

    // Screen sharing: the system MediaProjection consent dialog must be shown before capture.
    // On approval, hand the result Intent to the SDK, which spins up its own capture foreground
    // service and publishes the track into the call.
    val context = LocalContext.current
    val mpm = remember(context) { context.getSystemService(MediaProjectionManager::class.java) }
    val screenShareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            scope.launch { manager.startScreenShare(data) }
        }
    }

    // Live call duration: once connected, tick every second and render mm:ss (h:mm:ss past
    // an hour) — same pattern as the minimized overlay. Before connect (or on hold) the
    // status text stays.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }
    // §9.7: alone in a live group room → say so, never fake an ongoing peer.
    val displayStatus = when {
        isGroupCall && participants.isEmpty() && callStatus == "Connected" ->
            stringResource(R.string.call_waiting_for_others)
        callStatus != "On Hold" && connectedAt != null ->
            formatCallDuration((nowMs - connectedAt!!) / 1000)
        else -> localizedCallStatus(callStatus)
    }

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

    // System back leaves the call RUNNING and drops to the floating overlay — identical to the
    // Minimize chevron. Without this, back popped the call route while callMinimized stayed false,
    // so the overlay never showed and an active call was stranded with no in-app way back to it.
    // Disabled in PiP, where the system window owns its own dismissal.
    BackHandler(enabled = !pip.isInPipMode) { onMinimize() }

    var localFullscreen by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    // Pick which feed is full-screen and which rides in the draggable card (WhatsApp-style swap).
    // §17.1: in grid mode there is NO floating card — my feed becomes a regular grid tile.
    val localTrack = if (cameraEnabled) localVideo else null
    // A remote screen share always wins the big tile; otherwise the WhatsApp-style local/remote
    // swap decides. Camera feeds ride in the secondary strip/card during a screen share.
    val primaryIsLocal = !screenSharePrimary && !gridMode && localFullscreen && localTrack != null
    val primaryTrack = when {
        screenSharePrimary -> screenShare
        primaryIsLocal -> localTrack
        else -> remoteVideo
    }
    val secondaryTrack = if (gridMode || screenSharePrimary) null else if (primaryIsLocal) remoteVideo else localTrack
    val hasPrimaryVideo = (!gridMode || screenSharePrimary) && shouldShowVideo && primaryTrack != null
    // §7.6: the "video-call look" keys on the REMOTE feed being fullscreen — never on the
    // local camera. No remote fullscreen → themed header with name + status pill.
    val remoteFullscreen = hasPrimaryVideo && !primaryIsLocal

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (pip.isInPipMode) {
            // Compacted into the system PiP window: ONLY the remote video, full-bleed, no
            // chrome. Fall back to a centred avatar while no remote feed is live.
            val pipTrack = screenShare ?: remoteVideo ?: participants.firstNotNullOfOrNull { it.videoTrack }
            if (pipTrack != null) {
                LiveKitVideo(manager.room, pipTrack, Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AvatarView(url = peerId?.let { Network.avatarUrl(it) }, name = peerName, size = 64.dp)
                }
            }
        } else {
            if (hasPrimaryVideo) {
                LiveKitVideo(manager.room, primaryTrack, Modifier.fillMaxSize())
            }
            // Minimize back into the app (floating overlay) + optional system-PiP compact.
            Row(
                Modifier.align(Alignment.TopStart).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircleControl(
                    painter = rememberVectorPainter(Icons.Filled.KeyboardArrowDown),
                    contentDescription = "Minimize call",
                    diameter = 44,
                ) { onMinimize() }
                if (pip.supported) {
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
                // §7.6: remote video fullscreen → no header at all (controls remain). Otherwise
                // (voice call, peer's camera off, or my own preview expanded) → themed name +
                // status pill, never white-on-nothing.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!hasPrimaryVideo && !gridMode) {
                        AvatarView(
                            url = peerId?.let { Network.avatarUrl(it) },
                            name = peerName,
                            size = 120.dp,
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    when {
                        !remoteFullscreen -> {
                            Text(
                                peerName,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                displayStatus,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(horizontal = 12.dp, vertical = 5.dp),
                            )
                        }
                        callStatus == "On Hold" -> {
                            // Header is hidden over remote video, but a hold must stay
                            // visible (§7.5) — dark translucent capsule over the feed.
                            Text(
                                localizedCallStatus(callStatus),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .padding(horizontal = 12.dp, vertical = 5.dp),
                            )
                        }
                    }
                }

                if (screenSharePrimary) {
                    // A remote is sharing their screen (rendered full-bleed as the primary tile):
                    // push the camera feeds into a small horizontal strip above the controls so the
                    // shared desktop stays the prominent tile, no matter the participant count.
                    Spacer(Modifier.weight(1f))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        participants.forEach { participant ->
                            key(participant.userId) {
                                Box(Modifier.size(96.dp, 128.dp)) {
                                    CallGridTile(
                                        room = manager.room,
                                        videoTrack = participant.videoTrack,
                                        displayName = participant.name.ifBlank {
                                            vm.displayNameFor(participant.userId)
                                                ?: stringResource(R.string.call_member)
                                        },
                                        avatarUrl = Network.avatarUrl(participant.userId),
                                        micMuted = participant.micMuted,
                                        isSpeaking = participant.isSpeaking,
                                        reconnecting = participant.reconnecting,
                                    )
                                }
                            }
                        }
                        Box(Modifier.size(96.dp, 128.dp)) {
                            CallGridTile(
                                room = manager.room,
                                videoTrack = localTrack,
                                displayName = stringResource(R.string.common_you),
                                avatarUrl = me?.id?.let { Network.avatarUrl(it) },
                                micMuted = !micEnabled,
                                isSpeaking = localSpeaking,
                                avatarName = me?.displayName ?: stringResource(R.string.common_you),
                                mirrorVideo = frontCamera,
                            )
                        }
                    }
                } else if (gridMode) {
                    // §17.1: Zoom-style non-scrolling grid — remotes keep their order, MY
                    // tile goes LAST as a regular grid citizen (mirrored selfie feed when
                    // the camera is on, the same avatar/mute chrome as everyone when off).
                    NonScrollingCallGrid(
                        tileCount = participants.size + 1,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 16.dp),
                    ) { index ->
                        if (index < participants.size) {
                            val participant = participants[index]
                            key(participant.userId) {
                                CallGridTile(
                                    room = manager.room,
                                    videoTrack = participant.videoTrack,
                                    displayName = participant.name.ifBlank {
                                        vm.displayNameFor(participant.userId)
                                            ?: stringResource(R.string.call_member)
                                    },
                                    avatarUrl = Network.avatarUrl(participant.userId),
                                    micMuted = participant.micMuted,
                                    isSpeaking = participant.isSpeaking,
                                    reconnecting = participant.reconnecting,
                                )
                            }
                        } else {
                            CallGridTile(
                                room = manager.room,
                                videoTrack = localTrack,
                                displayName = stringResource(R.string.common_you),
                                avatarUrl = me?.id?.let { Network.avatarUrl(it) },
                                micMuted = !micEnabled,
                                isSpeaking = localSpeaking,
                                avatarName = me?.displayName ?: stringResource(R.string.common_you),
                                mirrorVideo = frontCamera,
                            )
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

                    // Speaker / earpiece toggle — on every call; auto-routing still flips it
                    // when the video state changes (see updateAudioRouteForVideo).
                    CircleControl(
                        painter = rememberVectorPainter(if (speakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.Filled.PhoneInTalk),
                        contentDescription = "Toggle speaker",
                        fill = if (speakerOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        tint = if (speakerOn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    ) { manager.toggleSpeaker() }

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

                    // Share my phone screen into the call. Toggles: stop if already sharing,
                    // otherwise request MediaProjection consent and let the callback publish.
                    CircleControl(
                        painter = rememberVectorPainter(
                            if (screenShareEnabled) Icons.Filled.StopScreenShare else Icons.Filled.ScreenShare
                        ),
                        contentDescription = "Share screen",
                        fill = if (screenShareEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        tint = if (screenShareEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    ) {
                        if (screenShareEnabled) {
                            scope.launch { manager.stopScreenShare() }
                        } else {
                            mpm?.let { screenShareLauncher.launch(it.createScreenCaptureIntent()) }
                        }
                    }

                    if (cameraEnabled) {
                        CircleControl(
                            painter = rememberVectorPainter(Icons.Filled.Cameraswitch),
                            contentDescription = "Switch camera",
                        ) { manager.switchCamera() }
                    }
                }
            }

        }

        // Draggable, tap-to-swap picture-in-picture card for the secondary feed — 1:1 layout
        // only (in grid mode secondaryTrack is null; my feed lives in the grid, §17.1). §7.7:
        // the drag tracks the finger 1:1 (accumulated into Animatables, rendered via
        // graphicsLayer) and the release springs to the nearest horizontal edge carrying the
        // fling velocity.
        if (!pip.isInPipMode && shouldShowVideo && secondaryTrack != null) {
            val leftLimitPx = with(density) { (maxWidth - 150.dp).toPx() }.coerceAtLeast(0f)
            val downLimitPx = with(density) { (maxHeight - 320.dp).toPx() }.coerceAtLeast(0f)
            val cardX = remember { Animatable(0f) }
            val cardY = remember { Animatable(0f) }
            LaunchedEffect(leftLimitPx, downLimitPx) {
                cardX.updateBounds(-leftLimitPx, 0f)
                cardY.updateBounds(0f, downLimitPx)
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .graphicsLayer {
                        translationX = cardX.value
                        translationY = cardY.value
                    }
                    .padding(20.dp)
                    .size(110.dp, 160.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .pointerInput(leftLimitPx, downLimitPx) {
                        val tracker = VelocityTracker()
                        var dragX = 0f
                        var dragY = 0f
                        detectDragGestures(
                            onDragStart = {
                                dragX = cardX.value
                                dragY = cardY.value
                                tracker.resetTracking()
                            },
                            onDrag = { change, drag ->
                                change.consume()
                                tracker.addPointerInputChange(change)
                                dragX = (dragX + drag.x).coerceIn(-leftLimitPx, 0f)
                                dragY = (dragY + drag.y).coerceIn(0f, downLimitPx)
                                val x = dragX
                                val y = dragY
                                scope.launch {
                                    cardX.snapTo(x)
                                    cardY.snapTo(y)
                                }
                            },
                            onDragEnd = {
                                val velocity = tracker.calculateVelocity()
                                val settle = spring<Float>(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                )
                                // Project the fling a beat forward to pick the edge it's headed to.
                                val projectedX = dragX + velocity.x * 0.12f
                                val targetX = if (projectedX < -leftLimitPx / 2f) -leftLimitPx else 0f
                                scope.launch { cardX.animateTo(targetX, settle, initialVelocity = velocity.x) }
                                scope.launch { cardY.animateTo(dragY, settle, initialVelocity = velocity.y) }
                            },
                        )
                    }
                    .clickable { localFullscreen = !localFullscreen },
            ) {
                LiveKitVideo(manager.room, secondaryTrack, Modifier.fillMaxSize())
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


/** Elapsed call time since connect: mm:ss, growing to h:mm:ss once past the first hour. */
internal fun formatCallDuration(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    val seconds = s % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

/** §10.5: user-visible mapping of the internal call-status sentinels. */
@Composable
internal fun localizedCallStatus(status: String): String = when (status) {
    "Calling..." -> stringResource(R.string.call_status_calling)
    "Connecting..." -> stringResource(R.string.call_status_connecting)
    "Connected" -> stringResource(R.string.call_status_connected)
    "Reconnecting…" -> stringResource(R.string.call_status_reconnecting)
    "On Hold" -> stringResource(R.string.call_status_on_hold)
    "Busy" -> stringResource(R.string.call_status_busy)
    "No answer" -> stringResource(R.string.call_status_no_answer)
    "Call failed" -> stringResource(R.string.call_status_failed)
    "Ended" -> stringResource(R.string.call_status_ended)
    else -> status
}
