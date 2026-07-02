package com.klic.mobile.app.calling

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.SystemClock
import com.klic.mobile.app.R
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.AudioOptions
import io.livekit.android.AudioType
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** One remote participant in the call, as rendered by the call UI (identity == userId). */
data class RemoteCallParticipant(
    val userId: String,
    val name: String,
    val videoTrack: VideoTrack? = null,
    val micMuted: Boolean = false,
    val isSpeaking: Boolean = false,
    /** True while the participant dropped from the SFU and their 60s grace timer is running. */
    val reconnecting: Boolean = false,
)

/**
 * Wraps a LiveKit room for a call (1:1 or group) and exposes in-call controls + video tracks.
 * Media is routed by the LiveKit SFU; this only manages local track state and rendering.
 *
 * Robustness (CALLS.md §2.2):
 * - A remote participant dropping NEVER ends the call here — they get a 60s grace timer and a
 *   `reconnecting` tile; [peerGraceExpired] fires only when the grace runs out (the ViewModel
 *   decides what that means for a 1:1 vs a group call).
 * - A terminal local disconnect starts a rejoin loop (fresh token via [rejoinTokenProvider],
 *   1s/2s/4s/8s backoff, ~60s budget) instead of tearing the call down.
 */
class CallManager(
    private val appContext: Context,
    private val diagnosticSink: suspend (event: String, callId: String?, detail: String?) -> Unit = { _, _, _ -> },
    private val rejoinTokenProvider: suspend (callId: String) -> RejoinTokenResult = { RejoinTokenResult.CallOver },
) {

    /** Result of a mid-call token re-fetch for the rejoin loop (wired from the repository). */
    sealed interface RejoinTokenResult {
        data class Token(val url: String, val token: String) : RejoinTokenResult
        /** 404/409/410 — the call is over server-side; finish quietly. */
        object CallOver : RejoinTokenResult
        /** Network/5xx — keep retrying within the budget. */
        object Transient : RejoinTokenResult
    }

    enum class RejoinOutcome { CALL_OVER, GAVE_UP }

    val isConnected = MutableStateFlow(false)
    val micEnabled = MutableStateFlow(true)
    val cameraEnabled = MutableStateFlow(false)
    /** Whether call audio is on the loudspeaker (vs. earpiece / a connected headset). */
    val speakerOn = MutableStateFlow(false)
    val localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    /** First remote video track — drives the 1:1 fullscreen layout and audio routing. */
    val remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    /** All remote participants (connected + in-grace), for the group grid. */
    val participants = MutableStateFlow<List<RemoteCallParticipant>>(emptyList())
    /** A remote participant's 60s grace expired without them coming back (value = userId). */
    val peerGraceExpired = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** True while media is being re-established — LiveKit's own resume OR our rejoin loop. */
    val isReconnecting = MutableStateFlow(false)
    /** A rejoin succeeded; the ViewModel re-POSTs media-joined for this callId. */
    val rejoined = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** The rejoin loop ended without a connection (call over server-side, or budget spent). */
    val rejoinFailed = MutableSharedFlow<RejoinOutcome>(extraBufferCapacity = 1)

    var room: Room? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var eventsJob: Job? = null
    private var joinJob: Job? = null
    private var rejoinJob: Job? = null
    private var leaving = false
    private var audioHandler: AudioSwitchHandler? = null
    private var ringbackPlayer: MediaPlayer? = null
    /** Last audio route we applied based on whether any video is on screen — drives automatic
     *  speaker (video) ↔ earpiece (audio-only) switching as cameras turn on/off mid-call. */
    private var videoRouteActive = false
    private var currentCallId: String? = null
    // Participants that dropped from the SFU and are inside their grace window:
    // userId → (timer, last-known snapshot rendered as a dimmed "reconnecting" tile).
    private val graceJobs = mutableMapOf<String, Job>()
    private val graceSnapshots = mutableMapOf<String, RemoteCallParticipant>()

    /**
     * Connect + publish on the manager's own [scope] (not the caller's coroutine) and report the
     * outcome via callbacks. The connect/publish work must survive the CallScreen composable being
     * disposed/recomposed — hosting it in CallScreen's `LaunchedEffect` cancelled every in-flight
     * join the instant the screen left the tree, failing with "The coroutine scope left the
     * composition" (100% of calls on the Android emulator; a race on real devices).
     */
    fun join(
        callId: String,
        url: String,
        token: String,
        video: Boolean,
        onJoined: (String) -> Unit,
        onFailed: (String) -> Unit,
    ) {
        // Already joining or connected to this exact call (e.g. a harmless recomposition re-fired
        // the trigger) — don't tear it down and restart.
        if (currentCallId == callId && (joinJob?.isActive == true || isConnected.value)) return
        leave()
        currentCallId = callId
        leaving = false
        isReconnecting.value = false
        joinJob = scope.launch {
            try {
                joinInternal(callId, url, token, micOn = true, cameraOn = video)
                onJoined(callId)
            } catch (c: CancellationException) {
                throw c // a real teardown (leave()/hang-up), not a join failure
            } catch (t: Throwable) {
                onFailed(callId)
            }
        }
    }

    private suspend fun joinInternal(callId: String, url: String, token: String, micOn: Boolean, cameraOn: Boolean) {
        diagnostic("livekit.join.configure", callId, if (cameraOn) "video" else "audio")

        val handler = createAudioHandler(callId, cameraOn).also { audioHandler = it }
        val room = LiveKit.create(
            appContext,
            overrides = LiveKitOverrides(
                audioOptions = AudioOptions(
                    audioOutputType = AudioType.CallAudioType(),
                    audioHandler = handler,
                ),
            ),
        ).also { this.room = it }
        eventsJob = scope.launch {
            room.events.collect { event ->
                refreshTracks()
                when (event) {
                    is RoomEvent.Connected -> diagnostic("livekit.connectionState", callId, "connected")
                    is RoomEvent.ParticipantConnected -> {
                        diagnostic("livekit.remote.connect", callId, event.participant.identity?.value)
                        cancelGrace(event.participant.identity?.value)
                        refreshTracks()
                    }
                    is RoomEvent.ParticipantDisconnected -> {
                        diagnostic("livekit.remote.disconnect", callId, event.participant.identity?.value)
                        startGrace(callId, event.participant)
                        refreshTracks()
                    }
                    is RoomEvent.Reconnecting -> {
                        isReconnecting.value = true
                        diagnostic("livekit.connectionState", callId, "reconnecting")
                    }
                    is RoomEvent.Reconnected -> {
                        isReconnecting.value = false
                        diagnostic("livekit.connectionState", callId, "reconnected")
                    }
                    is RoomEvent.Disconnected -> {
                        diagnostic("livekit.connectionState", callId, "disconnected reason=${event.reason}")
                        // Terminal disconnect mid-call (LiveKit's resume gave up — typical on a
                        // WiFi→LTE switch with an IP change): rejoin with a fresh token instead
                        // of ending the call. isConnected guards against firing on a failed
                        // *initial* join, which the join() failure path already handles.
                        if (!leaving && isConnected.value) startRejoin(callId)
                    }
                    is RoomEvent.TrackSubscribed -> diagnostic(
                        "livekit.remote.subscribe",
                        callId,
                        "kind=${event.track.kind.value} source=${event.publication.source} muted=${event.publication.muted}",
                    )
                    is RoomEvent.TrackUnsubscribed -> diagnostic(
                        "livekit.remote.unsubscribe",
                        callId,
                        "kind=${event.track.kind.value}",
                    )
                    is RoomEvent.TrackMuted -> diagnostic(
                        "livekit.track.muted",
                        callId,
                        "kind=${event.publication.kind.value} source=${event.publication.source}",
                    )
                    is RoomEvent.TrackUnmuted -> diagnostic(
                        "livekit.track.unmuted",
                        callId,
                        "kind=${event.publication.kind.value} source=${event.publication.source}",
                    )
                    else -> Unit
                }
            }
        }
        try {
            diagnostic("livekit.join.connect.start", callId)
            room.connect(url, token)
            diagnostic("livekit.join.connect.ok", callId)

            diagnostic("livekit.join.mic.start", callId)
            room.localParticipant.setMicrophoneEnabled(micOn)
            room.setMicrophoneMute(!micOn)
            diagnostic("livekit.join.mic.ok", callId)

            if (cameraOn) {
                diagnostic("livekit.join.camera.start", callId)
                room.localParticipant.setCameraEnabled(true)
                diagnostic("livekit.join.camera.ok", callId)
                scope.launch {
                    delay(350)
                    // Re-apply once the speaker device has settled into the available list.
                    if (currentCallId == callId) updateAudioRouteForVideo(force = true)
                }
            }
            room.setSpeakerMute(false)
            isConnected.value = true
            micEnabled.value = micOn
            cameraEnabled.value = cameraOn
            refreshTracks()
        } catch (c: CancellationException) {
            throw c // hang-up/teardown cancelled the in-flight join — not a real failure
        } catch (t: Throwable) {
            diagnostic("livekit.join.failed", callId, t.message ?: t::class.java.simpleName)
            throw t
        }
    }

    /**
     * Rejoin loop after a terminal disconnect: fetch a fresh token, reconnect, republish with
     * the user's current mic/camera toggles, and let the ViewModel re-POST media-joined. Backoff
     * 1s/2s/4s then 8s steps, ~60s budget. Cancelled by [leave] (user hang-up / socket end).
     * isConnected deliberately stays true so OngoingCallService keeps its foreground priority.
     */
    private fun startRejoin(callId: String) {
        if (rejoinJob?.isActive == true) return
        isReconnecting.value = true
        diagnostic("livekit.rejoin.start", callId)
        rejoinJob = scope.launch {
            // Drop the dead room (and its event collector) before building a fresh one.
            eventsJob?.cancel()
            eventsJob = null
            runCatching { room?.disconnect() }
            room = null
            val deadline = SystemClock.elapsedRealtime() + REJOIN_BUDGET_MS
            var attempt = 0
            while (true) {
                delay(REJOIN_BACKOFF_MS.getOrElse(attempt) { REJOIN_BACKOFF_MS.last() })
                attempt++
                if (leaving || currentCallId != callId) return@launch
                when (val result = runCatching { rejoinTokenProvider(callId) }
                    .getOrDefault(RejoinTokenResult.Transient)) {
                    RejoinTokenResult.CallOver -> {
                        diagnostic("livekit.rejoin.callOver", callId)
                        isReconnecting.value = false
                        rejoinFailed.tryEmit(RejoinOutcome.CALL_OVER)
                        return@launch
                    }
                    RejoinTokenResult.Transient -> Unit
                    is RejoinTokenResult.Token -> {
                        val joined = try {
                            joinInternal(
                                callId, result.url, result.token,
                                micOn = micEnabled.value, cameraOn = cameraEnabled.value,
                            )
                            true
                        } catch (c: CancellationException) {
                            throw c
                        } catch (t: Throwable) {
                            // Tear down the half-built room before the next attempt.
                            eventsJob?.cancel()
                            eventsJob = null
                            runCatching { room?.disconnect() }
                            room = null
                            false
                        }
                        if (joined) {
                            isReconnecting.value = false
                            diagnostic("livekit.rejoin.ok", callId, "attempt=$attempt")
                            rejoined.tryEmit(callId)
                            return@launch
                        }
                    }
                }
                if (SystemClock.elapsedRealtime() >= deadline) {
                    diagnostic("livekit.rejoin.gaveUp", callId, "attempt=$attempt")
                    isReconnecting.value = false
                    rejoinFailed.tryEmit(RejoinOutcome.GAVE_UP)
                    return@launch
                }
            }
        }
    }

    /** 60s grace before a dropped remote counts as gone — their network switch shouldn't kill
     *  the call (D1). A [RoomEvent.ParticipantConnected] with the same identity cancels it. */
    private fun startGrace(callId: String, participant: RemoteParticipant) {
        val userId = participant.identity?.value ?: return
        graceSnapshots[userId] = participants.value.firstOrNull { it.userId == userId }
            ?.copy(videoTrack = null, isSpeaking = false, reconnecting = true)
            ?: RemoteCallParticipant(userId = userId, name = participant.name ?: "", reconnecting = true)
        graceJobs.remove(userId)?.cancel()
        graceJobs[userId] = scope.launch {
            delay(GRACE_MS)
            graceJobs.remove(userId)
            graceSnapshots.remove(userId)
            refreshTracks()
            diagnostic("livekit.remote.graceExpired", callId, userId)
            peerGraceExpired.tryEmit(userId)
        }
    }

    private fun cancelGrace(userId: String?) {
        if (userId == null) return
        graceJobs.remove(userId)?.cancel()
        graceSnapshots.remove(userId)
    }

    private fun clearGrace() {
        graceJobs.values.forEach { it.cancel() }
        graceJobs.clear()
        graceSnapshots.clear()
    }

    suspend fun toggleMic() {
        val next = !micEnabled.value
        room?.localParticipant?.setMicrophoneEnabled(next)
        room?.setMicrophoneMute(!next)
        micEnabled.value = next
        diagnostic("livekit.mic.toggle.ok", detail = "enabled=$next")
    }

    suspend fun toggleCamera() {
        val next = !cameraEnabled.value
        room?.localParticipant?.setCameraEnabled(next)
        cameraEnabled.value = next
        if (!next) localVideoTrack.value = null
        refreshTracks()
        diagnostic("livekit.camera.toggle.ok", detail = "enabled=$next")
    }

    /** Flip between the front and back camera mid-call. */
    fun switchCamera() {
        val track = room?.localParticipant?.videoTrackPublications
            ?.firstOrNull()?.second as? LocalVideoTrack
        track?.switchCamera()
        diagnostic("livekit.camera.switch.ok")
    }

    fun leave() {
        val callId = currentCallId
        val hadRoom = room != null
        leaving = true
        isReconnecting.value = false
        videoRouteActive = false
        stopRingback()
        if (hadRoom) diagnostic("livekit.leave.start", callId)
        joinJob?.cancel()
        joinJob = null
        rejoinJob?.cancel()
        rejoinJob = null
        eventsJob?.cancel()
        eventsJob = null
        clearGrace()
        room?.disconnect()
        room = null
        audioHandler = null
        isConnected.value = false
        micEnabled.value = true
        cameraEnabled.value = false
        localVideoTrack.value = null
        remoteVideoTrack.value = null
        participants.value = emptyList()
        if (hadRoom) diagnostic("livekit.leave.ok", callId)
        currentCallId = null
    }

    private fun refreshTracks() {
        val r = room ?: return
        localVideoTrack.value = r.localParticipant.videoTrackPublications
            .firstOrNull()?.second as? VideoTrack
        remoteVideoTrack.value = r.remoteParticipants.values
            .flatMap { it.videoTrackPublications }
            .firstOrNull()?.second as? VideoTrack
        refreshParticipants(r)
        diagnostic(
            "livekit.tracks.refresh",
            detail = "localVideo=${localVideoTrack.value != null} remoteVideo=${remoteVideoTrack.value != null} remoteAudio=${hasRemoteAudioTrack(r)}",
        )
        updateAudioRouteForVideo()
    }

    /** Rebuild the participant list from the live room + any in-grace snapshots. */
    private fun refreshParticipants(r: Room) {
        val live = r.remoteParticipants.values.mapNotNull { p ->
            val userId = p.identity?.value ?: return@mapNotNull null
            RemoteCallParticipant(
                userId = userId,
                name = p.name ?: "",
                videoTrack = p.videoTrackPublications.firstOrNull()?.second as? VideoTrack,
                micMuted = p.audioTrackPublications.firstOrNull()?.first?.muted ?: false,
                isSpeaking = p.isSpeaking,
            )
        }
        val liveIds = live.map { it.userId }.toSet()
        participants.value = live + graceSnapshots.values.filter { it.userId !in liveIds }
    }

    private fun createAudioHandler(callId: String, video: Boolean): AudioSwitchHandler =
        AudioSwitchHandler(appContext).apply {
            preferredDeviceList = if (video) {
                listOf(
                    AudioDevice.BluetoothHeadset::class.java,
                    AudioDevice.WiredHeadset::class.java,
                    AudioDevice.Speakerphone::class.java,
                    AudioDevice.Earpiece::class.java,
                )
            } else {
                listOf(
                    AudioDevice.BluetoothHeadset::class.java,
                    AudioDevice.WiredHeadset::class.java,
                    AudioDevice.Earpiece::class.java,
                    AudioDevice.Speakerphone::class.java,
                )
            }
            onAudioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
                diagnostic("livekit.audio.focus", callId, "change=$change")
            }
            audioDeviceChangeListener = { devices: List<AudioDevice>, selected: AudioDevice? ->
                speakerOn.value = selected is AudioDevice.Speakerphone
                diagnostic("livekit.audio.route", callId, routeDetail(devices, selected))
            }
        }

    /** Toggle call audio between the loudspeaker and the earpiece/headset. Leaving the speaker
     *  prefers a connected Bluetooth/wired headset, falling back to the earpiece. */
    fun toggleSpeaker() {
        val handler = audioHandler ?: return
        val devices = handler.availableAudioDevices
        val target = if (handler.selectedAudioDevice is AudioDevice.Speakerphone) {
            devices.firstOrNull { it is AudioDevice.BluetoothHeadset }
                ?: devices.firstOrNull { it is AudioDevice.WiredHeadset }
                ?: devices.firstOrNull { it is AudioDevice.Earpiece }
        } else {
            devices.firstOrNull { it is AudioDevice.Speakerphone }
        }
        target?.let {
            handler.selectDevice(it)
            speakerOn.value = it is AudioDevice.Speakerphone
            diagnostic("livekit.audio.speaker", currentCallId, if (it is AudioDevice.Speakerphone) "on" else "off")
        }
    }

    /** Outgoing ringback — the looping tone the caller hears while "Calling…" and waiting for the
     *  callee to answer. Plays as a call-signalling stream; stopped the moment the call connects. */
    fun startRingback() {
        if (ringbackPlayer != null) return
        runCatching {
            ringbackPlayer = MediaPlayer().apply {
                setDataSource(appContext, Uri.parse("android.resource://${appContext.packageName}/${R.raw.ring}"))
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
            diagnostic("livekit.ringback.start", currentCallId)
        }.onFailure { diagnostic("livekit.ringback.failed", currentCallId, it.message) }
    }

    fun stopRingback() {
        runCatching { ringbackPlayer?.stop(); ringbackPlayer?.release() }
        ringbackPlayer = null
    }

    /** Keep the loudspeaker on whenever any video is on screen, and fall back to the earpiece
     *  ("regular phone" mode) the moment both sides have their camera off — without a manual
     *  toggle. Only acts on a change (unless [force]) so a user's manual speaker choice during an
     *  audio-only stretch isn't overridden until the video state actually flips. A connected
     *  Bluetooth/wired headset always wins and is never overridden. */
    private fun updateAudioRouteForVideo(force: Boolean = false) {
        val videoActive = cameraEnabled.value || localVideoTrack.value != null || remoteVideoTrack.value != null
        if (!force && videoActive == videoRouteActive) return
        videoRouteActive = videoActive
        val handler = audioHandler ?: return
        val devices = handler.availableAudioDevices
        if (devices.any { it is AudioDevice.BluetoothHeadset || it is AudioDevice.WiredHeadset }) return
        val target = if (videoActive) devices.firstOrNull { it is AudioDevice.Speakerphone }
                     else devices.firstOrNull { it is AudioDevice.Earpiece }
        target?.let {
            handler.selectDevice(it)
            speakerOn.value = it is AudioDevice.Speakerphone
            diagnostic("livekit.audio.route.video", currentCallId, "videoActive=$videoActive ${routeDetail(devices, it)}")
        }
    }

    private fun routeDetail(devices: List<AudioDevice>, selected: AudioDevice?): String =
        "selected=${selected.audioName()} available=${devices.joinToString("|") { it.audioName() }}"

    private fun AudioDevice?.audioName(): String = this?.javaClass?.simpleName ?: "none"

    private fun hasRemoteAudioTrack(room: Room): Boolean =
        room.remoteParticipants.values
            .flatMap { it.audioTrackPublications }
            .any { (_, track) -> track?.kind == Track.Kind.AUDIO }

    private fun diagnostic(event: String, callId: String? = currentCallId, detail: String? = null) {
        scope.launch {
            runCatching { diagnosticSink(event, callId, detail) }
        }
    }

    companion object {
        private const val GRACE_MS = 60_000L
        private const val REJOIN_BUDGET_MS = 60_000L
        private val REJOIN_BACKOFF_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L)
    }
}
