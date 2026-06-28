package com.klicmobile.app.calling

import android.content.Context
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Wraps a LiveKit room for a 1:1 call and exposes in-call controls + video tracks.
 * Media is routed by the LiveKit SFU; this only manages local track state and rendering.
 */
class CallManager(private val appContext: Context) {

    val isConnected = MutableStateFlow(false)
    val micEnabled = MutableStateFlow(true)
    val cameraEnabled = MutableStateFlow(false)
    val localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteParticipantDisconnected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    var room: Room? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var eventsJob: Job? = null

    suspend fun join(url: String, token: String, video: Boolean) {
        leave()
        val room = LiveKit.create(appContext).also { this.room = it }
        eventsJob = scope.launch {
            room.events.collect { event ->
                refreshTracks()
                if (event is RoomEvent.ParticipantDisconnected) {
                    remoteParticipantDisconnected.tryEmit(Unit)
                }
            }
        }
        room.connect(url, token)
        room.localParticipant.setMicrophoneEnabled(true)
        if (video) room.localParticipant.setCameraEnabled(true)
        isConnected.value = true
        micEnabled.value = true
        cameraEnabled.value = video
        refreshTracks()
    }

    suspend fun toggleMic() {
        val next = !micEnabled.value
        room?.localParticipant?.setMicrophoneEnabled(next)
        micEnabled.value = next
    }

    suspend fun toggleCamera() {
        val next = !cameraEnabled.value
        room?.localParticipant?.setCameraEnabled(next)
        cameraEnabled.value = next
        if (!next) localVideoTrack.value = null
        refreshTracks()
    }

    fun leave() {
        eventsJob?.cancel()
        eventsJob = null
        room?.disconnect()
        room = null
        isConnected.value = false
        localVideoTrack.value = null
        remoteVideoTrack.value = null
    }

    private fun refreshTracks() {
        val r = room ?: return
        localVideoTrack.value = r.localParticipant.videoTrackPublications
            .firstOrNull()?.second as? VideoTrack
        remoteVideoTrack.value = r.remoteParticipants.values
            .flatMap { it.videoTrackPublications }
            .firstOrNull()?.second as? VideoTrack
    }
}
