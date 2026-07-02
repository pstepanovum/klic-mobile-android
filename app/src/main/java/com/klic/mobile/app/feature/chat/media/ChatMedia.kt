package com.klic.mobile.app.feature.chat.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
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
    val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
        .getOrNull() ?: return@withContext null
    val contentType = context.contentResolver.getType(uri) ?: "video/mp4"
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull()
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        val thumbnail = retriever.frameAtTime ?: return@withContext null
        PendingMediaDraft(
            id = UUID.randomUUID().toString(),
            previewUri = null,
            previewBitmap = thumbnail,
            isVideo = true,
            attachment = AttachmentInput(
                key = "",
                kind = "VIDEO",
                contentType = contentType,
                byteSize = bytes.size,
                width = width,
                height = height,
                durationMs = durationMs,
                fileName = queryDisplayName(context, uri),
                localBytes = bytes,
            ),
        )
    } catch (e: Exception) {
        null
    } finally {
        retriever.release()
    }
}

/** Picks the right loader for a gallery/camera Uri based on its MIME type. */
suspend fun loadMediaDraft(context: Context, uri: Uri): PendingMediaDraft? {
    val type = context.contentResolver.getType(uri) ?: ""
    return if (type.startsWith("video/")) loadVideoDraft(context, uri) else loadImageDraft(context, uri)
}

/** Reads an arbitrary document Uri (from the file picker) into an uploadable attachment. */
suspend fun loadFileAttachment(context: Context, uri: Uri): AttachmentInput? = withContext(Dispatchers.IO) {
    val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
        .getOrNull() ?: return@withContext null
    AttachmentInput(
        key = "",
        kind = "FILE",
        contentType = context.contentResolver.getType(uri) ?: "application/octet-stream",
        byteSize = bytes.size,
        fileName = queryDisplayName(context, uri),
        localBytes = bytes,
    )
}

private fun queryDisplayName(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()

@Composable
fun PendingMediaBar(items: List<PendingMediaDraft>, onRemove: (String) -> Unit) {
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
