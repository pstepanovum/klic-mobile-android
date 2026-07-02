package com.klic.mobile.app.feature.chat.messagelist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import com.klic.mobile.app.data.Attachment
import com.klic.mobile.app.data.Message
import com.klic.mobile.app.feature.chat.actions.DeletedBubble
import com.klic.mobile.app.feature.chat.actions.ReactionPillsRow
import com.klic.mobile.app.feature.chat.actions.ReplyQuote
import com.klic.mobile.app.feature.chat.media.FileAttachmentView
import com.klic.mobile.app.feature.chat.media.isAudioAttachment
import com.klic.mobile.app.feature.chat.stickers.CallEventBubble
import com.klic.mobile.app.feature.chat.stickers.StickerBubble
import com.klic.mobile.app.feature.chat.voice.VoiceAttachmentView
import com.klic.mobile.app.feature.chat.voice.durationText
import com.klic.mobile.app.ui.components.MessageTicks
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// MARK: - Message bubble

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageBubble(
    message: Message,
    isMine: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    replyAuthorName: String = "",
    onCallBack: (String) -> Unit = {},
    onLongPress: () -> Unit = {},
    onReactionTap: (String) -> Unit = {},
    onImageClick: (String) -> Unit = {},
    onFileClick: (Attachment) -> Unit = {},
) {
    if (message.isDeleted) { DeletedBubble(isMine); return }
    if (message.isCallEvent && message.call != null) {
        CallEventBubble(message.call, outgoing = isMine, time = shortTime(message.createdAt), onCallBack = onCallBack)
        return
    }
    if (message.isSticker) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 1.dp)
                .combinedClickable(onClick = {}, onLongClick = onLongPress),
            horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
        ) {
            StickerBubble(message, isMine = isMine, time = if (isLast) shortTime(message.createdAt) else null)
            if (message.reactions.isNotEmpty()) ReactionPillsRow(message.reactions, onReactionTap)
        }
        return
    }

    val tailRadius = 4.dp
    val fullRadius = 18.dp
    val shape = RoundedCornerShape(
        topStart     = if (!isMine && isFirst) fullRadius else if (!isMine) 4.dp else fullRadius,
        topEnd       = if (isMine  && isFirst) fullRadius else if (isMine)  4.dp else fullRadius,
        bottomEnd    = if (isMine  && isLast)  tailRadius else fullRadius,
        bottomStart  = if (!isMine && isLast)  tailRadius else fullRadius,
    )

    val voiceAtt = message.attachments.firstOrNull { it.kind == "VOICE" }
    val imageAtts = message.attachments.filter { it.kind == "IMAGE" }
    val videoAtt = message.attachments.firstOrNull { it.kind == "VIDEO" }
    val fileAtt = message.attachments.firstOrNull { it.kind == "FILE" }

    val time = shortTime(message.createdAt)
    val status = if (isMine) message.status else null

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
    ) {
        when {
            voiceAtt != null ->
                Box(Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress)) {
                    VoiceAttachmentView(
                        att = voiceAtt,
                        isMine = isMine,
                        time = time,
                        status = status,
                    )
                }

            imageAtts.isNotEmpty() ->
                Column(horizontalAlignment = if (isMine) Alignment.End else Alignment.Start) {
                    if (message.body.isBlank()) {
                        // No caption: bare image/bento with the overlay time + ticks pill.
                        message.replyTo?.let { ReplyQuote(it, replyAuthorName) }
                        Box(Modifier.clip(RoundedCornerShape(16.dp))) {
                            BentoImageGrid(imageAtts, tileRadius = 12.dp, onImageClick = onImageClick, onLongPress = onLongPress)
                            MediaTimePill(
                                time = time,
                                status = status,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                            )
                        }
                    } else {
                        // §7.2: image(s) + caption unified into ONE card — image(s) on top
                        // (inner radius 4dp under the card's), caption + inline time/ticks below.
                        val cardColor = if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                        val textColor = if (isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        val timeColor = if (isMine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.65f)
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(cardColor)
                                .combinedClickable(onClick = {}, onLongClick = onLongPress)
                                .padding(4.dp),
                        ) {
                            message.replyTo?.let {
                                Box(Modifier.padding(start = 6.dp, top = 4.dp, end = 6.dp, bottom = 6.dp)) {
                                    ReplyQuote(it, replyAuthorName, onPrimary = isMine)
                                }
                            }
                            BentoImageGrid(imageAtts, tileRadius = 14.dp, onImageClick = onImageClick, onLongPress = onLongPress)
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                modifier = Modifier.width(240.dp).padding(horizontal = 6.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    message.body,
                                    color = textColor,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Spacer(Modifier.width(6.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(time, style = MaterialTheme.typography.labelSmall, color = timeColor)
                                    if (status != null) {
                                        MessageTicks(status = status, onPrimary = isMine)
                                    }
                                }
                            }
                        }
                    }
                }

            videoAtt != null ->
                Column(horizontalAlignment = if (isMine) Alignment.End else Alignment.Start) {
                    message.replyTo?.let { ReplyQuote(it, replyAuthorName) }
                    Box(
                        Modifier
                            .widthIn(max = 240.dp)
                            .heightIn(max = 320.dp)
                            .aspectRatio(imageAspect(videoAtt))
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF1A1A1A))
                            .combinedClickable(onClick = {}, onLongClick = onLongPress),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Play video",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(48.dp).align(Alignment.Center),
                        )
                        // Duration pill — bottom-left.
                        if (videoAtt.durationMs != null) {
                            MediaTimePill(
                                text = durationText(videoAtt.durationMs),
                                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                            )
                        }
                        // Time + ticks pill — bottom-right.
                        MediaTimePill(
                            time = time,
                            status = status,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                        )
                    }
                }

            fileAtt != null ->
                // §7.3: files open in-app (pdf viewer / audio player / detail sheet) —
                // the tap goes through onFileClick, never straight to the URL.
                Column(horizontalAlignment = if (isMine) Alignment.End else Alignment.Start) {
                    message.replyTo?.let { ReplyQuote(it, replyAuthorName) }
                    Box(
                        Modifier.combinedClickable(
                            onClick = { if (!isAudioAttachment(fileAtt)) onFileClick(fileAtt) },
                            onLongClick = onLongPress,
                        ),
                    ) {
                        FileAttachmentView(att = fileAtt, isMine = isMine, time = time, status = status)
                    }
                }

            else ->
                Box(
                    Modifier
                        .widthIn(max = 280.dp)
                        .background(
                            if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            shape,
                        )
                        .combinedClickable(onClick = {}, onLongClick = onLongPress)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    val textColor = if (isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    val timeColor = if (isMine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.65f)
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                    Column {
                        message.replyTo?.let { ReplyQuote(it, replyAuthorName, onPrimary = isMine) }
                        // Message body + inline time + ticks, aligned to bottom of last text line.
                        Row(verticalAlignment = Alignment.Bottom) {
                            if (message.body.isNotBlank()) {
                                Text(
                                    message.body,
                                    color = textColor,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    time,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = timeColor,
                                )
                                if (status != null) {
                                    MessageTicks(status = status, onPrimary = isMine)
                                }
                            }
                        }
                    }
                }
        }

        if (message.reactions.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            ReactionPillsRow(message.reactions, onReactionTap)
        }
    }
}

// §7.2 bento grid: 2 → side-by-side; 3 → one large + two stacked; 4+ → 2x2 with a "+N"
// scrim on the fourth tile. Every tile opens the existing viewer on the tapped image.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BentoImageGrid(
    atts: List<Attachment>,
    tileRadius: Dp,
    onImageClick: (String) -> Unit,
    onLongPress: () -> Unit,
) {
    val spacing = 2.dp

    @Composable
    fun Tile(att: Attachment, modifier: Modifier, overflow: Int = 0) {
        Box(
            modifier
                .clip(RoundedCornerShape(tileRadius))
                .combinedClickable(onClick = { onImageClick(att.url) }, onLongClick = onLongPress),
        ) {
            AsyncImage(
                model = att.url,
                contentDescription = "Image",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (overflow > 0) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+$overflow", style = MaterialTheme.typography.titleLarge, color = Color.White)
                }
            }
        }
    }

    when (atts.size) {
        1 -> Tile(atts[0], Modifier.width(240.dp).aspectRatio(imageAspect(atts[0]).coerceIn(0.75f, 1.6f)))
        2 -> Row(Modifier.width(240.dp), horizontalArrangement = Arrangement.spacedBy(spacing)) {
            Tile(atts[0], Modifier.weight(1f).aspectRatio(0.75f))
            Tile(atts[1], Modifier.weight(1f).aspectRatio(0.75f))
        }
        3 -> Row(Modifier.width(240.dp).height(240.dp), horizontalArrangement = Arrangement.spacedBy(spacing)) {
            Tile(atts[0], Modifier.weight(2f).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(spacing)) {
                Tile(atts[1], Modifier.weight(1f).fillMaxWidth())
                Tile(atts[2], Modifier.weight(1f).fillMaxWidth())
            }
        }
        else -> Column(Modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(spacing)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                Tile(atts[0], Modifier.weight(1f).aspectRatio(1f))
                Tile(atts[1], Modifier.weight(1f).aspectRatio(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                Tile(atts[2], Modifier.weight(1f).aspectRatio(1f))
                Tile(atts[3], Modifier.weight(1f).aspectRatio(1f), overflow = atts.size - 4)
            }
        }
    }
}

// Semi-transparent dark pill used as overlay on image/video.
@Composable
private fun MediaTimePill(
    modifier: Modifier = Modifier,
    time: String = "",
    text: String = "",           // for the duration pill on video (no ticks)
    status: String? = null,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        val label = text.ifEmpty { time }
        if (label.isNotEmpty()) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
        if (status != null && text.isEmpty()) {
            MessageTicks(status = status, onMedia = true)
        }
    }
}

// Aspect ratio for an inline image/video, clamped so extreme shapes stay reasonable.
private fun imageAspect(att: Attachment): Float {
    val w = att.width; val h = att.height
    return if (w != null && h != null && w > 0 && h > 0) (w.toFloat() / h.toFloat()).coerceIn(0.6f, 1.6f) else 1f
}

// Compact preview text for the composer's reply bar.
internal fun messagePreview(m: Message): String = when {
    m.body.isNotBlank() -> m.body
    m.isSticker -> "Sticker"
    m.attachments.firstOrNull()?.kind == "IMAGE" -> "Photo"
    m.attachments.firstOrNull()?.kind == "VOICE" -> "Voice message"
    m.attachments.firstOrNull()?.kind == "VIDEO" -> "Video"
    m.attachments.isNotEmpty() -> "File"
    else -> "Message"
}

// Presence subtitle for the chat header: "Online" or "last seen …".
internal fun presenceSubtitle(presence: com.klic.mobile.app.realtime.SocketService.Presence?): String? {
    if (presence == null) return null
    if (presence.online) return "Online"
    val ms = presence.lastSeenMs ?: return null
    val date = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val time = DateTimeFormatter.ofPattern("HH:mm").format(date)
    return when (date.toLocalDate()) {
        LocalDate.now() -> "last seen $time"
        LocalDate.now().minusDays(1) -> "last seen yesterday"
        else -> "last seen ${DateTimeFormatter.ofPattern("MMM d").format(date)}"
    }
}

// MARK: - Date separator

@Composable
internal fun DateSeparator(isoDate: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                dateLabel(isoDate),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

// MARK: - Helpers

internal fun sameDay(a: String, b: String): Boolean = a.take(10) == b.take(10)

internal fun shortTime(iso: String): String = runCatching {
    val instant = Instant.parse(iso)
    DateTimeFormatter.ofPattern("h:mm a").format(instant.atZone(ZoneId.systemDefault()))
}.getOrDefault("")

private fun dateLabel(iso: String): String = runCatching {
    val date = Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    when (date) {
        today            -> "Today"
        today.minusDays(1) -> "Yesterday"
        else             -> DateTimeFormatter.ofPattern("MMMM d").format(date)
    }
}.getOrDefault("")
