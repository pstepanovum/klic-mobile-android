package com.klic.mobile.app.feature.chat.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.klic.mobile.app.data.AttachmentInput
import com.klic.mobile.app.data.ImageUploads
import com.klic.mobile.app.data.SettingsStore
import com.klic.mobile.app.ui.theme.KlicIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** A photo or video staged in the composer, not yet uploaded. */
data class PendingMediaDraft(
    val id: String,
    val previewUri: Uri?,        // set for images (thumbnail is the image itself)
    val previewBitmap: Bitmap?,  // set for videos (extracted frame)
    val isVideo: Boolean,
    val attachment: AttachmentInput,
)

suspend fun loadImageDraft(context: Context, uri: Uri): PendingMediaDraft? = withContext(Dispatchers.IO) {
    // Upload quality (§8.3): Standard = the long-standing 2048px/q85 compression;
    // HD = a higher-res, lighter-touch encode. The pref lives in SettingsStore.
    val hd = SettingsStore.snapshot.value.uploadHd
    val encoded = ImageUploads.encodeImage(
        context, uri,
        maxDimension = if (hd) 4096 else 2048,
        quality = if (hd) 95 else 85,
    ) ?: return@withContext null
    PendingMediaDraft(
        id = UUID.randomUUID().toString(),
        previewUri = uri,
        previewBitmap = null,
        isVideo = false,
        attachment = AttachmentInput(
            key = "",
            kind = "IMAGE",
            contentType = encoded.contentType,
            byteSize = encoded.bytes.size,
            width = encoded.width,
            height = encoded.height,
            localBytes = encoded.bytes,
        ),
    )
}

suspend fun loadVideoDraft(context: Context, uri: Uri): PendingMediaDraft? = withContext(Dispatchers.IO) {
    // §13.15: NEVER read the video into memory — stage the Uri and stream at upload time.
    val byteSize = queryByteSize(context, uri) ?: return@withContext null
    val contentType = context.contentResolver.getType(uri) ?: "video/mp4"
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull()
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        // The preview is only ever drawn as a 76dp tile, so downscale to ~512px instead
        // of retaining a full-resolution frame (~8MB for 1080p, ~33MB for 4K) in composer state.
        val thumbnail = retriever.scaledPreviewFrame(width, height) ?: return@withContext null
        PendingMediaDraft(
            id = UUID.randomUUID().toString(),
            previewUri = null,
            previewBitmap = thumbnail,
            isVideo = true,
            attachment = AttachmentInput(
                key = "",
                kind = "VIDEO",
                contentType = contentType,
                byteSize = byteSize,
                width = width,
                height = height,
                durationMs = durationMs,
                fileName = queryDisplayName(context, uri),
                localUri = uri.toString(),
            ),
        )
    } catch (e: Exception) {
        null
    } finally {
        retriever.release()
    }
}

private const val VIDEO_PREVIEW_EDGE = 512

/** Extracts the video's representative frame already downscaled to ~512px so the
 *  composer preview never holds a full-resolution frame in memory. On API 27+ the
 *  decoder scales directly; older devices scale a decoded frame and recycle the source. */
private fun MediaMetadataRetriever.scaledPreviewFrame(width: Int?, height: Int?): Bitmap? {
    if (Build.VERSION.SDK_INT >= 27 && width != null && height != null && width > 0 && height > 0) {
        val edge = maxOf(width, height)
        val scale = if (edge > VIDEO_PREVIEW_EDGE) VIDEO_PREVIEW_EDGE.toFloat() / edge else 1f
        return getScaledFrameAtTime(
            -1,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
        )
    }
    val full = frameAtTime ?: return null
    val edge = maxOf(full.width, full.height)
    if (edge <= VIDEO_PREVIEW_EDGE) return full
    val scale = VIDEO_PREVIEW_EDGE.toFloat() / edge
    return Bitmap.createScaledBitmap(
        full,
        (full.width * scale).toInt().coerceAtLeast(1),
        (full.height * scale).toInt().coerceAtLeast(1),
        true,
    ).also { if (it !== full) full.recycle() }
}

/** Picks the right loader for a gallery/camera Uri based on its MIME type. */
suspend fun loadMediaDraft(context: Context, uri: Uri): PendingMediaDraft? {
    val type = context.contentResolver.getType(uri) ?: ""
    return if (type.startsWith("video/")) loadVideoDraft(context, uri) else loadImageDraft(context, uri)
}

/** Stages an arbitrary document Uri (from the file picker) as an uploadable attachment.
 *  §13.15: only the size/name are read here — the payload streams at upload time. */
suspend fun loadFileAttachment(context: Context, uri: Uri): AttachmentInput? = withContext(Dispatchers.IO) {
    val byteSize = queryByteSize(context, uri) ?: return@withContext null
    AttachmentInput(
        key = "",
        kind = "FILE",
        contentType = context.contentResolver.getType(uri) ?: "application/octet-stream",
        byteSize = byteSize,
        fileName = queryDisplayName(context, uri),
        localUri = uri.toString(),
    )
}

/** Resolves a content Uri's byte size without reading it (SIZE column, fd fallback). */
private fun queryByteSize(context: Context, uri: Uri): Int? {
    val fromColumn = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) cursor.getLong(idx) else null
        }
    }.getOrNull()
    val size = fromColumn ?: runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
    }.getOrNull()
    return size?.takeIf { it > 0 && it <= Int.MAX_VALUE }?.toInt()
}

private fun queryDisplayName(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()

@Composable
fun PendingMediaBar(
    items: List<PendingMediaDraft>,
    onRemove: (String) -> Unit,
    /** §10.9: opens the pre-send media editor for one staged item. */
    onEdit: (String) -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items.forEach { item ->
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(16.dp)),
            ) {
                if (item.isVideo && item.previewBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = item.previewBitmap.asImageBitmap(),
                        contentDescription = "Pending video",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    AsyncImage(
                        model = item.previewUri,
                        contentDescription = "Pending image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (item.isVideo) {
                    Icon(
                        imageVector = Icons.Filled.Videocam,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                            .size(16.dp),
                    )
                }
                // §10.9: pencil — pre-send edit (caption/draw/text/crop/quality).
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                        .clickable { onEdit(item.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Edit",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                        .clickable { onRemove(item.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(KlicIcons.close),
                        contentDescription = "Remove",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}
