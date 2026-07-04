package com.klic.mobile.app.feature.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.klic.mobile.app.R
import com.klic.mobile.app.data.ReplyPreview
import com.klic.mobile.app.feature.chat.actions.replySnippetText
import com.klic.mobile.app.feature.chat.media.rememberVideoThumbnail
import com.klic.mobile.app.ui.components.rememberStableImageRequest
import com.klic.mobile.app.ui.theme.KlicIcons

/**
 * §16.3: pinned bar at the top of the chat (below the header) — "Pinned Message"
 * title + snippet of the displayed pin, a small thumbnail when the pin carries
 * visual media, and a vertical segmented indicator when multiple pins exist
 * (≤3 visible segments, reference-style). TAP steps back through pins (cycling);
 * × unpins (when allowed) or hides the bar locally.
 */
@Composable
fun PinnedMessagesBar(
    pins: List<ReplyPreview>,
    currentIndex: Int,
    onTap: () -> Unit,
    onClose: () -> Unit,
) {
    val pin = pins.getOrNull(currentIndex) ?: return
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onTap)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PinSegmentIndicator(
                total = pins.size,
                selected = currentIndex,
                modifier = Modifier.width(3.dp).height(34.dp),
            )
            val att = pin.attachment
            val showThumb = pin.deleted != true && att != null && (
                att.kind == "IMAGE" || att.kind == "VIDEO" || att.kind == "VIDEO_NOTE" ||
                    (att.kind == "FILE" && att.contentType.startsWith("image/"))
                )
            if (showThumb && att != null) {
                val shape = if (att.kind == "VIDEO_NOTE") CircleShape else RoundedCornerShape(4.dp)
                Box(
                    Modifier
                        .padding(start = 8.dp)
                        .size(34.dp)
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    if (att.kind == "VIDEO" || att.kind == "VIDEO_NOTE") {
                        val thumb by rememberVideoThumbnail(att.asAttachment())
                        thumb?.let {
                            androidx.compose.foundation.Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                    } else {
                        AsyncImage(
                            model = rememberStableImageRequest(att.url),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    stringResource(R.string.pinned_message_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    replySnippetText(pin),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(
                    painter = painterResource(KlicIcons.close),
                    contentDescription = "Close pinned bar",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * Vertical segmented pin indicator — one accent segment per pin (capped at 3
 * visible segments), the displayed pin's segment fully opaque.
 */
@Composable
private fun PinSegmentIndicator(total: Int, selected: Int, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val segments = minOf(total, 3)
        if (segments <= 1) {
            drawRoundRect(accent, cornerRadius = CornerRadius(size.width / 2))
            return@Canvas
        }
        val gap = 2.dp.toPx()
        val segH = (size.height - gap * (segments - 1)) / segments
        // Map the selected pin onto the visible window of ≤3 segments.
        val selectedSegment = if (total <= 3) selected else (selected * segments / total).coerceIn(0, segments - 1)
        for (i in 0 until segments) {
            drawRoundRect(
                color = if (i == selectedSegment) accent else accent.copy(alpha = 0.35f),
                topLeft = Offset(0f, i * (segH + gap)),
                size = Size(size.width, segH),
                cornerRadius = CornerRadius(size.width / 2),
            )
        }
    }
}
