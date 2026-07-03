package com.klic.mobile.app.feature.chat.media

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.klic.mobile.app.data.Attachment
import com.klic.mobile.app.feature.chat.voice.AudioPlaybackManager
import com.klic.mobile.app.feature.chat.voice.WaveformBarsView
import com.klic.mobile.app.ui.components.MessageTicks
import com.klic.mobile.app.ui.components.PillButton
import com.klic.mobile.app.ui.theme.KlicIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import androidx.compose.ui.res.stringResource
import com.klic.mobile.app.R

// ── Attachment download cache (§7.3) ────────────────────────────────────────
//
// FILE attachments are always fetched into the app cache and viewed locally —
// the presigned media URL is never handed to a browser or another app.

object AttachmentDownloads {
    /** attachment id → download progress 0..1; an id is present only while downloading. */
    val progress = mutableStateMapOf<String, Float>()

    // Data-usage interceptor so downloads are attributed by media kind (§8.3).
    private val client by lazy {
        OkHttpClient.Builder().addInterceptor(com.klic.mobile.app.data.DataUsage.interceptor).build()
    }
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * Local cache slot for an attachment — keyed by id, keeps the original name/extension.
     * When [conversationId] is known the file lives in a per-conversation subfolder, which
     * is what "Manage storage" (§8.4) measures and clears.
     */
    fun cachedFile(context: Context, att: Attachment, conversationId: String? = null): File {
        val root = File(context.cacheDir, "attachments")
        val dir = (conversationId?.let { File(root, it) } ?: root).apply { mkdirs() }
        val safeName = (att.fileName ?: "file").replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
        return File(dir, "${att.id}-$safeName")
    }

    /**
     * Returns the cached local copy, downloading it first if needed (presigned URL fetch).
     * Concurrent requests for the same attachment share one download. Null on failure.
     */
    suspend fun ensureLocal(
        context: Context,
        att: Attachment,
        conversationId: String? = null,
    ): File? = withContext(Dispatchers.IO) {
        val target = cachedFile(context, att, conversationId)
        if (target.length() > 0L) return@withContext target
        locks.getOrPut(att.id) { Mutex() }.withLock {
            if (target.length() > 0L) return@withLock target
            progress[att.id] = 0f
            try {
                client.newCall(Request.Builder().url(att.url).build()).execute().use { response ->
                    val body = response.body
                    if (!response.isSuccessful || body == null) return@withLock null
                    val total = body.contentLength().takeIf { it > 0 } ?: att.byteSize.toLong()
                    val tmp = File(target.parentFile, "${target.name}.part")
                    body.byteStream().use { input ->
                        tmp.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var copied = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                copied += read
                                if (total > 0) progress[att.id] = (copied.toFloat() / total).coerceIn(0f, 1f)
                            }
                        }
                    }
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    target
                }
            } catch (t: Throwable) {
                null
            } finally {
                progress.remove(att.id)
            }
        }
    }
}

// ── PDF bubble preview (§10.10) ──────────────────────────────────────────────

/**
 * First-page thumbnails for PDF bubbles, cached by attachment id: memory map +
 * PNG on disk. Any failure returns null and the bubble falls back to the doc pill.
 */
object PdfThumbnails {
    private val memory = ConcurrentHashMap<String, Bitmap>()
    private val renderLock = Mutex()

    suspend fun thumbnail(context: Context, att: Attachment, conversationId: String?): Bitmap? =
        withContext(Dispatchers.IO) {
            memory[att.id]?.let { return@withContext it }
            val cacheFile = File(File(context.cacheDir, "pdf_thumbs").apply { mkdirs() }, "${att.id}.png")
            if (cacheFile.length() > 0) {
                android.graphics.BitmapFactory.decodeFile(cacheFile.absolutePath)?.let {
                    memory[att.id] = it
                    return@withContext it
                }
            }
            val pdf = AttachmentDownloads.ensureLocal(context, att, conversationId) ?: return@withContext null
            val bitmap = renderLock.withLock {
                runCatching {
                    PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                        if (renderer.pageCount == 0) return@runCatching null
                        renderer.openPage(0).use { page ->
                            val width = 480
                            val height = (width.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
                            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bmp ->
                                bmp.eraseColor(android.graphics.Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }.getOrNull()
            } ?: return@withContext null
            memory[att.id] = bitmap
            runCatching { cacheFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) } }
            bitmap
        }
}

/**
 * §10.10: a PDF renders its FIRST PAGE as the bubble preview — image-style pill
 * with a doc badge and a filename/size footer. Falls back to [FileAttachmentView]
 * while loading fails or before the thumbnail lands.
 */
@Composable
fun PdfFileBubbleView(
    att: Attachment,
    isMine: Boolean,
    time: String = "",
    status: String? = null,
    conversationId: String? = null,
    starred: Boolean = false,
) {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(initialValue = null, att.id) {
        value = PdfThumbnails.thumbnail(context, att, conversationId)
    }
    val bmp = thumb
    if (bmp == null) {
        FileAttachmentView(att, isMine, time, status, conversationId, starred)
        return
    }
    val containerColor = if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val nameColor = if (isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val metaColor = if (isMine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f)
                    else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = containerColor, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.width(240.dp).padding(4.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f / 1.15f)) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = att.fileName ?: "PDF",
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White, RoundedCornerShape(15.dp)),
                )
                // Doc badge — top-left.
                Box(
                    Modifier
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text("PDF", style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(KlicIcons.document),
                    contentDescription = null,
                    tint = nameColor,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        att.fileName ?: "PDF",
                        style = MaterialTheme.typography.bodyMedium,
                        color = nameColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        formatByteSize(att.byteSize),
                        style = MaterialTheme.typography.labelSmall,
                        color = metaColor,
                    )
                }
                if (time.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (starred) {
                            Icon(
                                painter = painterResource(KlicIcons.starBold),
                                contentDescription = "Starred",
                                tint = metaColor,
                                modifier = Modifier.size(10.dp),
                            )
                            Spacer(Modifier.width(3.dp))
                        }
                        Text(time, style = MaterialTheme.typography.labelSmall, color = metaColor)
                        if (isMine && status != null) {
                            Spacer(Modifier.width(3.dp))
                            MessageTicks(status = status, onPrimary = isMine)
                        }
                    }
                }
            }
        }
    }
}

fun isPdfAttachment(att: Attachment): Boolean =
    att.contentType.equals("application/pdf", ignoreCase = true) ||
        (att.fileName ?: "").endsWith(".pdf", ignoreCase = true)

fun isAudioAttachment(att: Attachment): Boolean =
    att.contentType.startsWith("audio/", ignoreCase = true)

fun formatByteSize(bytes: Int): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576f)
    bytes >= 1 shl 10 -> "%.0f KB".format(bytes / 1024f)
    else -> "$bytes B"
}

// ── File bubble ──────────────────────────────────────────────────────────────

/**
 * Chat bubble for a FILE attachment: leading icon (or download progress), name + size,
 * trailing time + ticks (same treatment as the voice bubble). Audio files embed the
 * voice-note player instead of opening a separate screen.
 */
@Composable
fun FileAttachmentView(
    att: Attachment,
    isMine: Boolean,
    time: String = "",
    status: String? = null,
    conversationId: String? = null,
    starred: Boolean = false,
) {
    val containerColor = if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val nameColor = if (isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val metaColor = if (isMine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f)
                    else MaterialTheme.colorScheme.onSurfaceVariant
    val downloadProgress = AttachmentDownloads.progress[att.id]
    val isAudio = isAudioAttachment(att)

    Surface(color = containerColor, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.widthIn(max = 260.dp).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isAudio) AudioFilePlayButton(att, isMine, conversationId) else FileLeadingIcon(isMine, downloadProgress)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        att.fileName ?: "File",
                        style = MaterialTheme.typography.bodyMedium,
                        color = nameColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isAudio && AudioPlaybackManager.playingId == att.id) {
                        Spacer(Modifier.height(3.dp))
                        WaveformBarsView(
                            amplitudes = emptyList(),
                            progress = AudioPlaybackManager.progress,
                            isOutgoing = isMine,
                            modifier = Modifier.width(130.dp),
                        )
                    } else {
                        Text(
                            formatByteSize(att.byteSize),
                            style = MaterialTheme.typography.labelSmall,
                            color = metaColor,
                        )
                    }
                }
            }
            // Time + delivery ticks — trailing-aligned, mirroring the voice bubble (§7.2).
            if (time.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (starred) {
                        Icon(
                            painter = painterResource(KlicIcons.starBold),
                            contentDescription = "Starred",
                            tint = metaColor,
                            modifier = Modifier.size(10.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                    }
                    Text(time, style = MaterialTheme.typography.labelSmall, color = metaColor)
                    if (isMine && status != null) {
                        Spacer(Modifier.width(3.dp))
                        MessageTicks(status = status, onPrimary = isMine)
                    }
                }
            }
        }
    }
}

/** In-bubble play/pause for audio files — downloads to cache first, then plays the local copy. */
@Composable
private fun AudioFilePlayButton(att: Attachment, isMine: Boolean, conversationId: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playing = AudioPlaybackManager.playingId == att.id
    val buttonTint = if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimary
    val buttonContainer = if (isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    val downloading = AttachmentDownloads.progress.containsKey(att.id)

    IconButton(
        onClick = {
            if (playing) {
                AudioPlaybackManager.stop()
            } else {
                scope.launch {
                    AttachmentDownloads.ensureLocal(context, att, conversationId)?.let {
                        AudioPlaybackManager.toggle(att.id, it.absolutePath)
                    }
                }
            }
        },
        modifier = Modifier.size(34.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = buttonContainer,
            contentColor   = buttonTint,
        ),
    ) {
        if (downloading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = buttonTint)
        } else {
            Icon(
                imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun FileLeadingIcon(isMine: Boolean, progress: Float?) {
    val iconTint = if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimary
    val container = if (isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    Box(Modifier.size(34.dp).background(container, CircleShape), contentAlignment = Alignment.Center) {
        if (progress != null) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = iconTint,
                trackColor = Color.Transparent,
            )
        } else {
            Icon(
                painter = painterResource(KlicIcons.document),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ── In-app PDF viewer ────────────────────────────────────────────────────────

/** Fullscreen in-app PDF viewer over a downloaded local file (PdfRenderer, lazy pages). */
@Composable
fun PdfViewerOverlay(file: File, onDismiss: () -> Unit) {
    val renderer = remember(file) {
        runCatching { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull()
    }
    DisposableEffect(renderer) {
        onDispose { runCatching { renderer?.close() } }
    }
    // PdfRenderer allows only one open page at a time — serialize page rendering.
    val renderLock = remember { Mutex() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (renderer == null) {
            Text(
                stringResource(R.string.file_pdf_open_failed),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 64.dp, bottom = 24.dp),
            ) {
                items(renderer.pageCount) { index -> PdfPageView(renderer, renderLock, index) }
            }
        }
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Close",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun PdfPageView(renderer: PdfRenderer, renderLock: Mutex, index: Int) {
    val config = LocalConfiguration.current
    val widthPx = with(LocalDensity.current) { config.screenWidthDp.dp.toPx() }.toInt().coerceIn(320, 1600)
    val bitmap by produceState<Bitmap?>(initialValue = null, renderer, index) {
        value = withContext(Dispatchers.IO) {
            renderLock.withLock {
                runCatching {
                    renderer.openPage(index).use { page ->
                        val height = (widthPx.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
                        Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).also { bmp ->
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }.getOrNull()
            }
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Page ${index + 1}",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        Box(Modifier.fillMaxWidth().aspectRatio(1f / 1.414f), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

// ── Detail sheet for other/unknown files ─────────────────────────────────────

/**
 * In-app detail sheet for non-PDF, non-audio files: icon, name, size, and an
 * "Open with…" chooser fed the downloaded LOCAL file via FileProvider —
 * the remote URL never leaves the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileDetailSheet(att: Attachment, file: File, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(64.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(KlicIcons.document),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                att.fileName ?: "File",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${formatByteSize(att.byteSize)} · ${att.contentType}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            PillButton(text = stringResource(R.string.file_open_with)) {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, att.contentType.ifBlank { "*/*" })
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { context.startActivity(Intent.createChooser(intent, att.fileName ?: "Open with")) }
            }
        }
    }
}
