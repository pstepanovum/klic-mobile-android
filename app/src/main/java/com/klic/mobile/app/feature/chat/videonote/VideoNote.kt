package com.klic.mobile.app.feature.chat.videonote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Rational
import android.view.Surface
import android.view.TextureView
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.klic.mobile.app.data.Attachment
import com.klic.mobile.app.feature.chat.media.rememberVideoThumbnail
import com.klic.mobile.app.feature.chat.messagelist.MediaTimePill
import com.klic.mobile.app.feature.chat.voice.durationText
import kotlinx.coroutines.delay
import java.io.File

// §16.2 round video notes: 60s hard cap; ~1 Mbps H.264 in a square mp4 (the encoder
// output is the SD tier cropped square via a 1:1 viewport — technique studied from
// the reference client, implemented on CameraX).
const val VIDEO_NOTE_MAX_MS = 60_000
private const val TARGET_BITRATE = 1_000_000

// MARK: - Recorder session

/**
 * One round-video recording session: front camera by default, square (1:1 viewport)
 * SD capture at ~1 Mbps, hard-capped at 60s by the caller. The composer's record
 * button drives [stop]/[cancel]; the overlay binds the camera when it appears.
 */
class VideoNoteSession(private val context: Context) {
    var isRecording by mutableStateOf(false)
        private set
    var elapsed by mutableFloatStateOf(0f)
        private set
    var lensFacing by mutableStateOf(CameraSelector.LENS_FACING_FRONT)
        private set

    val previewView: PreviewView = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }

    private var provider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var file: File? = null
    private var startMs = 0L
    private var canceled = false
    private var released = false
    private var onFinished: ((File?, Int) -> Unit)? = null
    private var elapsedTicker: Thread? = null

    /** Bind camera + start recording; called by the overlay once it's on screen. */
    fun bind(lifecycleOwner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                if (released || canceled) return@addListener
                val prov = future.get()
                provider = prov
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.SD,
                            FallbackStrategy.higherQualityOrLowerThan(Quality.SD),
                        )
                    )
                    .setTargetVideoEncodingBitRate(TARGET_BITRATE)
                    .build()
                val capture = VideoCapture.withOutput(recorder)
                videoCapture = capture
                val group = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(capture)
                    // Square output: both the preview circle and the encoded mp4
                    // crop to the same centered 1:1 viewport.
                    .setViewPort(ViewPort.Builder(Rational(1, 1), Surface.ROTATION_0).build())
                    .build()
                prov.unbindAll()
                prov.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                    group,
                )
                if (recording == null) startRecording()
            }.onFailure { finishNow(null) }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Flip front ↔ back mid-recording (persistent recording survives the rebind). */
    fun flip(lifecycleOwner: LifecycleOwner) {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        bind(lifecycleOwner)
    }

    @androidx.annotation.OptIn(androidx.camera.video.ExperimentalPersistentRecording::class)
    private fun startRecording() {
        val capture = videoCapture ?: return
        val f = File.createTempFile("klic_vnote_", ".mp4", context.cacheDir)
        file = f
        startMs = System.currentTimeMillis()
        isRecording = true
        elapsed = 0f
        val pending = capture.output
            .prepareRecording(context, FileOutputOptions.Builder(f).build())
            .apply {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    withAudioEnabled()
                }
            }
            // Keeps the recording alive across the flip-camera rebind.
            .asPersistentRecording()
        recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Status -> {
                    elapsed = (System.currentTimeMillis() - startMs) / 1000f
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    isRecording = false
                    val ok = !event.hasError() && !canceled && f.length() > 0L
                    if (!ok) f.delete()
                    finishNow(if (ok) f else null)
                }
                else -> Unit
            }
        }
    }

    /** Stop and hand the finished square mp4 (or null) to [onDone]. */
    fun stop(onDone: (File?, durationMs: Int) -> Unit) {
        onFinished = { result, ms -> onDone(result, ms) }
        val active = recording
        if (active == null) {
            // Released before the camera even bound — nothing recorded.
            canceled = true
            finishNow(null)
        } else {
            active.stop()
        }
    }

    /** Discard the take (slide-to-cancel / locked Cancel). */
    fun cancel() {
        canceled = true
        val active = recording
        if (active == null) finishNow(null) else active.stop()
    }

    /** Tear down the camera when the overlay leaves composition. */
    fun release() {
        released = true
        runCatching { recording?.stop() }
        runCatching { provider?.unbindAll() }
        provider = null
    }

    private fun finishNow(result: File?) {
        isRecording = false
        val cb = onFinished
        onFinished = null
        val durationMs = ((System.currentTimeMillis() - startMs).toInt())
            .coerceIn(0, VIDEO_NOTE_MAX_MS)
        cb?.invoke(result, durationMs)
        if (result == null) file?.delete()
        file = null
    }
}

// MARK: - Recording overlay

/**
 * §16.2: the live circular recording surface — camera preview clipped to a circle,
 * a progress RING for elapsed/60s, a timer + red dot, and a flip-camera button.
 * The slide-to-cancel / lock gestures live on the composer's record button.
 */
@Composable
fun VideoNoteRecordingOverlay(session: VideoNoteSession, modifier: Modifier = Modifier) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    val diameter = (minOf(configuration.screenWidthDp, configuration.screenHeightDp) * 0.66f).dp

    DisposableEffect(Unit) {
        session.bind(lifecycleOwner)
        onDispose { session.release() }
    }

    Column(
        modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(diameter + 16.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(diameter)
                    .clip(CircleShape)
                    .background(Color(0xFF101010)),
            ) {
                AndroidView({ session.previewView }, Modifier.matchParentSize())
            }
            // Progress ring: elapsed/60s, sweeping from 12 o'clock.
            val ringColor = MaterialTheme.colorScheme.primary
            Canvas(Modifier.matchParentSize()) {
                val stroke = 4.dp.toPx()
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * (session.elapsed * 1000f / VIDEO_NOTE_MAX_MS).coerceIn(0f, 1f),
                    useCenter = false,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(Color.Red, CircleShape))
            Spacer(Modifier.size(8.dp))
            val s = session.elapsed.toInt()
            Text(
                "%d:%02d".format(s / 60, s % 60),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Spacer(Modifier.size(20.dp))
            IconButton(
                onClick = { session.flip(lifecycleOwner) },
                modifier = Modifier.size(44.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.White.copy(alpha = 0.18f),
                    contentColor = Color.White,
                ),
            ) {
                Icon(Icons.Filled.FlipCameraAndroid, contentDescription = "Flip camera", modifier = Modifier.size(22.dp))
            }
        }
    }
}

// MARK: - Playback bubble

/**
 * §16.2: circular video-note playback — no bubble chrome (sticker-like), a thin
 * progress ring while playing, tap to play/pause inline WITH sound, duration pill
 * bottom-left and the usual time + ticks (+ "edited") pill bottom-right.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoNoteBubble(
    att: Attachment,
    conversationId: String,
    time: String,
    status: String?,
    starred: Boolean,
    edited: Boolean,
    onLongPress: () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    // ~60% of the shorter screen side.
    val diameter = (minOf(configuration.screenWidthDp, configuration.screenHeightDp) * 0.6f).dp

    var player by remember(att.id) { mutableStateOf<ExoPlayer?>(null) }
    var playing by remember(att.id) { mutableStateOf(false) }
    var progress by remember(att.id) { mutableFloatStateOf(0f) }
    var positionMs by remember(att.id) { mutableStateOf(0L) }

    DisposableEffect(att.id) {
        onDispose {
            player?.release()
            player = null
        }
    }
    LaunchedEffect(playing) {
        while (playing) {
            player?.let { p ->
                val dur = p.duration.takeIf { it > 0 } ?: att.durationMs?.toLong() ?: 0L
                positionMs = p.currentPosition
                progress = if (dur > 0) (p.currentPosition.toFloat() / dur).coerceIn(0f, 1f) else 0f
            }
            delay(66)
        }
    }

    fun togglePlay() {
        val existing = player
        if (existing == null) {
            player = ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(att.url))
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        playing = isPlaying
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            seekTo(0)
                            pause()
                            progress = 0f
                            positionMs = 0L
                        }
                    }
                })
                prepare()
                playWhenReady = true
            }
        } else if (existing.isPlaying) {
            existing.pause()
        } else {
            existing.play()
        }
    }

    Box(Modifier.size(diameter)) {
        Box(
            Modifier
                .matchParentSize()
                .padding(3.dp)
                .clip(CircleShape)
                .background(Color(0xFF1A1A1A))
                .combinedClickable(onClick = { togglePlay() }, onLongClick = onLongPress),
        ) {
            // First-frame thumbnail behind (and before) the player surface.
            val thumb by rememberVideoThumbnail(att, conversationId)
            thumb?.let {
                androidx.compose.foundation.Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
            if (player != null) {
                // TextureView (not SurfaceView) so the circular clip applies.
                AndroidView(
                    factory = { ctx -> TextureView(ctx) },
                    update = { view -> player?.setVideoTextureView(view) },
                    modifier = Modifier.matchParentSize(),
                )
            }
            if (!playing) {
                Icon(
                    imageVector = if (positionMs > 0L) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "Play video message",
                    tint = Color.White.copy(alpha = 0.95f),
                    modifier = Modifier
                        .size(46.dp)
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                        .padding(8.dp),
                )
            }
        }
        // Thin progress ring around the circle while playing.
        if (progress > 0f) {
            val ringColor = MaterialTheme.colorScheme.primary
            Canvas(Modifier.matchParentSize()) {
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
        // Duration (or current position while playing) — bottom-left.
        MediaTimePill(
            text = durationText(if (playing || positionMs > 0L) positionMs.toInt() else (att.durationMs ?: 0)),
            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
        )
        // Time + ticks (+ edited) — bottom-right.
        MediaTimePill(
            time = time,
            status = status,
            starred = starred,
            edited = edited,
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
        )
    }
}
