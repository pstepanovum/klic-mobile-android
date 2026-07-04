package com.klic.mobile.app.calling

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack

/** Renders a LiveKit [VideoTrack] inside Compose via a TextureViewRenderer.
 *  [mirror] flips the frame horizontally — selfie-style preview for the front camera (§17.1). */
@Composable
fun LiveKitVideo(room: Room?, track: VideoTrack?, modifier: Modifier = Modifier, mirror: Boolean = false) {
    if (room == null || track == null) return
    key(track.sid) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                TextureViewRenderer(ctx).apply {
                    room.initVideoRenderer(this)
                    setMirror(mirror)
                    track.addRenderer(this)
                }
            },
            update = { view -> view.setMirror(mirror) },
            onRelease = { view ->
                track.removeRenderer(view)
                view.release()
            },
        )
    }
}
