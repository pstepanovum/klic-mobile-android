package com.klic.mobile.app.feature.chat.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.data.Attachment
import com.klic.mobile.app.ui.components.MessageTicks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ── Waveform codec ───────────────────────────────────────────────────────────

fun unpackWaveform(data: ByteArray): List<Float> {
    if (data.isEmpty()) return emptyList()
    val totalBits = data.size * 8
    val sampleCount = totalBits / 5
    if (sampleCount == 0) return emptyList()
    return List(sampleCount) { i ->
        val bitStart = i * 5
        val byteIndex = bitStart / 8
        val bitOffset = bitStart % 8
        var raw = (data[byteIndex].toInt() and 0xFF).toLong()
        if (byteIndex + 1 < data.size) raw = raw or ((data[byteIndex + 1].toInt() and 0xFF).toLong() shl 8)
        if (byteIndex + 2 < data.size) raw = raw or ((data[byteIndex + 2].toInt() and 0xFF).toLong() shl 16)
        ((raw shr bitOffset) and 0x1F) / 31f
    }
}

fun packWaveform(amplitudes: List<Float>): ByteArray {
    var bitBuffer = 0L
    var bitCount = 0
    val bytes = mutableListOf<Byte>()
    for (amp in amplitudes) {
        val value = (amp.coerceIn(0f, 1f) * 31 + 0.5f).toLong().coerceIn(0, 31)
        bitBuffer = bitBuffer or (value shl bitCount)
        bitCount += 5
        while (bitCount >= 8) {
            bytes.add((bitBuffer and 0xFF).toByte())
            bitBuffer = bitBuffer shr 8
            bitCount -= 8
        }
    }
    if (bitCount > 0) bytes.add((bitBuffer and 0xFF).toByte())
    return bytes.toByteArray()
}

// ── WaveformBarsView ─────────────────────────────────────────────────────────

@Composable
fun WaveformBarsView(
    amplitudes: List<Float>,
    progress: Float,
    isOutgoing: Boolean,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val foregroundTint = if (isOutgoing) Color.White else primary
    val dimTint = if (isOutgoing) Color.White.copy(alpha = 0.35f) else primary.copy(alpha = 0.3f)

    androidx.compose.foundation.Canvas(modifier = modifier.height(18.dp)) {
        if (amplitudes.isEmpty()) {
            drawProgressLine(foregroundTint, dimTint, progress)
            return@Canvas
        }
        val barCount = 32
        val barSpacing = 1.dp.toPx()
        val totalSpacing = barSpacing * (barCount - 1)
        val barWidth = ((size.width - totalSpacing) / barCount).coerceAtLeast(1f)
        val midY = size.height / 2
        val playedBars = (barCount * progress).toInt()
        val downsampled = downsample(amplitudes, barCount)
        for (i in 0 until barCount) {
            val amp = downsampled[i].coerceAtLeast(0.1f)
            val h = (amp * size.height).coerceAtLeast(2f)
            val x = i * (barWidth + barSpacing)
            drawRoundRect(
                color = if (i < playedBars) foregroundTint else dimTint,
                topLeft = Offset(x, midY - h / 2),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
    }
}

private fun DrawScope.drawProgressLine(foreground: Color, dim: Color, progress: Float) {
    val h = 2.dp.toPx()
    val r = CornerRadius(h / 2)
    drawRoundRect(dim, size = Size(size.width, h), topLeft = Offset(0f, size.height / 2 - h / 2), cornerRadius = r)
    if (progress > 0f) {
        drawRoundRect(foreground, size = Size(size.width * progress, h), topLeft = Offset(0f, size.height / 2 - h / 2), cornerRadius = r)
    }
}

private fun downsample(src: List<Float>, n: Int): List<Float> {
    if (src.size == n) return src
    if (src.size < n) return src + List(n - src.size) { 0f }
    val bucketSize = src.size.toDouble() / n
    return List(n) { i ->
        val start = (i * bucketSize).toInt()
        val end = ((i + 1) * bucketSize).toInt().coerceAtMost(src.size).coerceAtLeast(start + 1)
        src.subList(start, end).max()
    }
}

// ── VoiceAttachmentView ──────────────────────────────────────────────────────

@Composable
fun VoiceAttachmentView(
    att: Attachment,
    isMine: Boolean,
    time: String = "",
    status: String? = null,
    starred: Boolean = false,
    /** §14.5: reactions render INSIDE the bubble, at its bottom edge. */
    reactions: List<com.klic.mobile.app.data.Reaction> = emptyList(),
    onReactionTap: (String) -> Unit = {},
) {
    val player = AudioPlaybackManager
    val playing = player.playingId == att.id
    val progress = if (playing) player.progress else 0f
    val amplitudes = att.waveform
        ?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }.getOrNull() }
        ?.let { unpackWaveform(it) }
        ?: emptyList()

    val containerColor = if (isMine) MaterialTheme.colorScheme.primary
                         else MaterialTheme.colorScheme.surfaceVariant
    val buttonTint = if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimary
    val buttonContainer = if (isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    val timeColor = if (isMine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                    else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(color = containerColor, shape = RoundedCornerShape(18.dp)) {
        // Hug the content: without the intrinsic-width cap the fillMaxWidth time row
        // below stretches the bubble across the whole screen.
        Column(
            modifier = Modifier
                .width(androidx.compose.foundation.layout.IntrinsicSize.Max)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { player.toggle(att.id, att.url) },
                    modifier = Modifier.size(34.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = buttonContainer,
                        contentColor   = buttonTint,
                    ),
                ) {
                    Icon(
                        imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                WaveformBarsView(
                    amplitudes = amplitudes,
                    progress = progress,
                    isOutgoing = isMine,
                    modifier = Modifier.width(110.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = durationText(att.durationMs ?: 0),
                    style = MaterialTheme.typography.labelSmall,
                    color = timeColor,
                )
            }
            // Time + delivery ticks — trailing-aligned below the waveform row.
            if (time.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (starred) {
                        com.klic.mobile.app.feature.chat.messagelist.StarIndicator(timeColor)
                        Spacer(Modifier.width(3.dp))
                    }
                    Text(
                        text = time,
                        style = MaterialTheme.typography.labelSmall,
                        color = timeColor,
                    )
                    if (isMine && status != null) {
                        Spacer(Modifier.width(3.dp))
                        MessageTicks(status = status, onPrimary = isMine)
                    }
                }
            }
            // §14.5: reaction chips inside the voice bubble.
            com.klic.mobile.app.feature.chat.actions.ReactionChipsInline(
                reactions = reactions,
                onTap = onReactionTap,
                onPrimary = isMine,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

internal fun durationText(ms: Int): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

// ── AudioPlaybackManager ─────────────────────────────────────────────────────

object AudioPlaybackManager {
    var playingId by mutableStateOf<String?>(null)
        private set
    var progress by mutableFloatStateOf(0f)
        private set

    private var player: MediaPlayer? = null
    private var progressJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun toggle(id: String, url: String) {
        if (playingId == id) { stop(); return }
        scope.launch { play(id, url) }
    }

    private suspend fun play(id: String, url: String) {
        stop()
        val p = withContext(Dispatchers.IO) {
            runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    setDataSource(url)
                    prepare()
                }
            }.getOrNull()
        } ?: return
        playingId = id
        player = p
        p.setOnCompletionListener { stop() }
        p.start()
        progressJob = scope.launch {
            while (isActive) {
                delay(50)
                val mp = player ?: break
                if (!mp.isPlaying) break
                val dur = mp.duration.takeIf { it > 0 } ?: continue
                progress = mp.currentPosition.toFloat() / dur
            }
        }
    }

    fun stop() {
        progressJob?.cancel(); progressJob = null
        runCatching { player?.stop() }
        player?.release(); player = null
        playingId = null; progress = 0f
    }
}

// ── VoiceRecorder ────────────────────────────────────────────────────────────

class VoiceRecorder(private val context: Context) {
    var isRecording by mutableStateOf(false)
        private set
    var elapsed by mutableFloatStateOf(0f)
        private set

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var timerJob: Job? = null
    private val samples = mutableListOf<Float>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun start(): Boolean {
        if (isRecording) return true
        val f = File.createTempFile("voice_", ".m4a", context.cacheDir)
        file = f
        samples.clear()

        @Suppress("DEPRECATION")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        val started = runCatching {
            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44_100)
                setAudioEncodingBitRate(128_000)
                setOutputFile(f.absolutePath)
                prepare()
                start()
            }
        }.isSuccess
        if (!started) {
            runCatching { rec.release() }
            f.delete()
            file = null
            isRecording = false
            elapsed = 0f
            return false
        }
        recorder = rec
        isRecording = true
        elapsed = 0f

        val startMs = System.currentTimeMillis()
        timerJob = scope.launch {
            while (isActive) {
                delay(100)
                elapsed = (System.currentTimeMillis() - startMs) / 1000f
                val amp = runCatching { rec.maxAmplitude }.getOrDefault(0)
                samples.add((amp / 32767f).coerceIn(0f, 1f))
            }
        }
        return true
    }

    /** Returns (audioBytes, durationMs, waveformBytes), or null if too short / failed. */
    fun stop(): Triple<ByteArray, Int, ByteArray>? {
        timerJob?.cancel(); timerJob = null
        val rec = recorder ?: run { isRecording = false; return null }
        val f = file ?: run { isRecording = false; return null }
        val durationMs = (elapsed * 1000).toInt()
        val stopped = runCatching { rec.stop() }.isSuccess
        rec.release()
        recorder = null
        file = null
        isRecording = false
        if (!stopped || elapsed < 0.4f) {
            f.delete()
            samples.clear()
            return null
        }
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: run { f.delete(); return null }
        f.delete()
        val waveform = packWaveform(samples.toList())
        samples.clear()
        return Triple(bytes, durationMs, waveform)
    }

    fun cancel() {
        timerJob?.cancel(); timerJob = null
        runCatching { recorder?.stop(); recorder?.release() }
        recorder = null
        file?.delete(); file = null
        samples.clear()
        isRecording = false
    }
}
