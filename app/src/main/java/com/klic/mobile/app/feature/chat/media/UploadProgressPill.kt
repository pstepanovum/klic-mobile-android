package com.klic.mobile.app.feature.chat.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.feature.UploadTask
import com.klic.mobile.app.ui.theme.KlicIcons
import androidx.compose.ui.res.stringResource
import com.klic.mobile.app.R

/**
 * Optimistic outgoing-message pill for an in-flight upload (§9.1): local media preview
 * where possible, a byte-driven progress bar, and retry/discard affordances on failure.
 */
@Composable
fun UploadProgressPill(
    task: UploadTask,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
) {
    val first = task.attachments.firstOrNull()
    val preview = rememberUploadPreview(task)
    val label = uploadLabel(task)

    Column(
        Modifier
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                if (preview != null) {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = label,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp),
                    )
                } else if (first?.kind == "VIDEO") {
                    Icon(
                        imageVector = Icons.Filled.Videocam,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Icon(
                        painter = painterResource(KlicIcons.document),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // §13.15: failed pills say WHY (size cap vs network) when known.
                    if (task.failed) task.errorMessage ?: stringResource(R.string.upload_failed)
                    else stringResource(R.string.upload_progress, (task.progress * 100).toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f),
                )
            }
        }
        task.body?.let { caption ->
            Spacer(Modifier.height(6.dp))
            Text(
                caption,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (task.failed) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillAction(stringResource(R.string.common_retry), onRetry, Modifier.weight(1f))
                PillAction(stringResource(R.string.upload_discard), onDiscard, Modifier.weight(1f))
            }
        } else {
            LinearProgressIndicator(
                progress = { task.progress },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                color = MaterialTheme.colorScheme.onPrimary,
                trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.25f),
            )
        }
    }
}

/** Small capsule action on the failed pill — matches the app's rounded button language. */
@Composable
private fun PillAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/** Downsampled thumbnail from the first image attachment's staged bytes, if any. */
@Composable
private fun rememberUploadPreview(task: UploadTask): Bitmap? = remember(task.id) {
    val image = task.attachments.firstOrNull { it.kind == "IMAGE" && it.localBytes != null }
        ?: return@remember null
    val bytes = image.localBytes ?: return@remember null
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = (maxOf(bounds.outWidth, bounds.outHeight) / 256).coerceAtLeast(1)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.getOrNull()
}

@Composable
private fun uploadLabel(task: UploadTask): String {
    val atts = task.attachments
    if (atts.size > 1) return stringResource(R.string.upload_items_count, atts.size)
    val first = atts.firstOrNull() ?: return stringResource(R.string.upload_attachment)
    return when (first.kind) {
        "IMAGE" -> stringResource(R.string.preview_photo)
        "VIDEO" -> stringResource(R.string.preview_video)
        else -> first.fileName ?: stringResource(R.string.preview_file)
    }
}
