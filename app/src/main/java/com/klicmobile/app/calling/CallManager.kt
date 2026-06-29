package com.klicmobile.app.calling

import android.content.Context
import android.media.AudioManager
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.AudioOptions
import io.livekit.android.AudioType
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
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

/**
 * Wraps a LiveKit room for a 1:1 call and exposes in-call controls + video tracks.
 * Media is routed by the LiveKit SFU; this only manages local track state and rendering.
 */
class CallManager(
    private val appContext: Context,
    private val diagnosticSink: suspend (event: String, callId: String?, detail: String?) -> Unit = { _, _, _ -> },
) {

    val isConnected = MutableStateFlow(false)
    val micEnabled = MutableStateFlow(true)
    val cameraEnabled = MutableStateFlow(false)
    /** Whether call audio is on the loudspeaker (vs. earpiece / a connected headset). */
    val speakerOn = MutableStateFlow(false)
    val localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteParticipantDisconnected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** True while LiveKit is re-establishing media after a network change (WiFi↔cellular). */
    val isReconnecting = MutableStateFlow(false)
    /** Emitted when the connection is lost terminally (not a user-initiated leave). */
    val networkDisconnected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    var room: Room? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var eventsJob: Job? = null
    private var joinJob: Job? = null
    private var leaving = false
    private var audioHandler: AudioSwitchHandler? = null
    private var currentCallId: String? = null

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
                joinInternal(callId, url, token, video)
                onJoined(callId)
            } catch (c: CancellationException) {
                throw c // a real teardown (leave()/hang-up), not a join failure
            } catch (t: Throwable) {
                onFailed(callId)
            }
        }
    }

    private suspend fun joinInternal(callId: String, url: String, token: String, video: Boolean) {
        diagnostic("livekit.join.configure", callId, if (video) "video" else "audio")

        val handler = createAudioHandler(callId, video).also { audioHandler = it }
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
                    is RoomEvent.ParticipantDisconnected -> {
                        diagnostic("livekit.remote.disconnect", callId)
                        remoteParticipantDisconnected.tryEmit(Unit)
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
                        isReconnecting.value = false
                        diagnostic("livekit.connectionState", callId, "disconnected reason=${event.reason}")
                        if (!leaving) networkDisconnected.tryEmit(Unit)
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
            if (video) preferVideoSpeaker(handler, callId, "after-connect")

            diagnostic("livekit.join.mic.start", callId)
            room.localParticipant.setMicrophoneEnabled(true)
            room.setMicrophoneMute(false)
            diagnostic("livekit.join.mic.ok", callId)

            if (video) {
                diagnostic("livekit.join.camera.start", callId)
                room.localParticipant.setCameraEnabled(true)
                diagnostic("livekit.join.camera.ok", callId)
                scope.launch {
                    delay(350)
                    if (currentCallId == callId) preferVideoSpeaker(handler, callId, "settled")
                }
            }
            room.setSpeakerMute(false)
            isConnected.value = true
            micEnabled.value = true
            cameraEnabled.value = video
            refreshTracks()
        } catch (c: CancellationException) {
            throw c // hang-up/teardown cancelled the in-flight join — not a real failure
        } catch (t: Throwable) {
            diagnostic("livekit.join.failed", callId, t.message ?: t::class.java.simpleName)
            throw t
        }
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
        if (hadRoom) diagnostic("livekit.leave.start", callId)
        joinJob?.cancel()
        joinJob = null
        eventsJob?.cancel()
        eventsJob = null
        room?.disconnect()
        room = null
        audioHandler = null
        isConnected.value = false
        micEnabled.value = true
        cameraEnabled.value = false
        localVideoTrack.value = null
        remoteVideoTrack.value = null
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
        diagnostic(
            "livekit.tracks.refresh",
            detail = "localVideo=${localVideoTrack.value != null} remoteVideo=${remoteVideoTrack.value != null} remoteAudio=${hasRemoteAudioTrack(r)}",
        )
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

    private fun preferVideoSpeaker(handler: AudioSwitchHandler, callId: String, reason: String) {
        val devices = handler.availableAudioDevices
        val hasExternalRoute = devices.any { it is AudioDevice.BluetoothHeadset || it is AudioDevice.WiredHeadset }
        val speaker = devices.firstOrNull { it is AudioDevice.Speakerphone }
        if (!hasExternalRoute && speaker != null) {
            handler.selectDevice(speaker)
            diagnostic("livekit.audio.route.selectSpeaker", callId, "reason=$reason ${routeDetail(devices, speaker)}")
        } else {
            diagnostic("livekit.audio.route.keep", callId, "reason=$reason ${routeDetail(devices, handler.selectedAudioDevice)}")
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
}
