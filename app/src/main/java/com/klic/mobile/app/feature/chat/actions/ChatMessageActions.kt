package com.klic.mobile.app.feature.chat.actions

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.klic.mobile.app.ui.components.rememberStableImageRequest
import com.klic.mobile.app.data.Attachment
import com.klic.mobile.app.data.Message
import com.klic.mobile.app.data.Reaction
import com.klic.mobile.app.data.ReplyPreview
import androidx.compose.ui.res.stringResource
import com.klic.mobile.app.R

// Quick-reaction palette shown on the long-press menu (Telegram-style).
val quickReactions = listOf("❤️", "👍", "👎", "😂", "😮", "😢", "🔥")

// MARK: - Long-press actions overlay

@Composable
fun MessageActionsOverlay(
    message: Message,
    isMine: Boolean,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onStar: () -> Unit = {},
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    /** §12.1: "Report message" — only offered on other people's messages. */
    onReport: (() -> Unit)? = null,
    /** "Hide" — locally hides another user's message on this device (UGC filter). */
    onHide: (() -> Unit)? = null,
    /** §16.3: "Pin"/"Unpin" — null when the user lacks pin rights here. */
    onPin: (() -> Unit)? = null,
    isPinned: Boolean = false,
    /** §16.4: "Edit" — only on OWN editable messages within the 48h window. */
    onEdit: (() -> Unit)? = null,
) {
    val mine = remember(message.reactions) { message.reactions.filter { it.mine }.map { it.emoji }.toSet() }
    val hasBody = message.body.isNotBlank()
    // §19.4: a message carrying multiple images/videos gets a swipeable, paged preview
    // in the action sheet; single-image / non-image messages keep the compact pill.
    val previewMedia = remember(message.id) {
        message.attachments.filter { it.kind == "IMAGE" || it.kind == "VIDEO" }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 28.dp),
        ) {
            // Reaction bar
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    quickReactions.forEach { emoji ->
                        Box(
                            Modifier
                                .size(40.dp)
                                .then(if (emoji in mine) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), RoundedCornerShape(20.dp)) else Modifier)
                                .clickable { onReact(emoji) },
                            contentAlignment = Alignment.Center,
                        ) { Text(emoji, fontSize = 24.sp) }
                    }
                }
            }

            // Preview of the message — paged media for multi-image, else a compact pill.
            if (previewMedia.size > 1) {
                MediaPreviewPager(previewMedia, isMine)
            } else {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        previewText(message),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 6,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }

            // Actions card
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                Column(Modifier.width(240.dp)) {
                    ActionRow(stringResource(R.string.viewer_reply), Icons.AutoMirrored.Filled.Reply) { onReply(); onDismiss() }
                    if (onEdit != null) {
                        ActionRow(stringResource(R.string.actions_edit), Icons.Filled.Edit) { onEdit(); onDismiss() }
                    }
                    if (onPin != null) {
                        ActionRow(
                            if (isPinned) stringResource(R.string.actions_unpin) else stringResource(R.string.actions_pin),
                            Icons.Filled.PushPin,
                        ) { onPin() }
                    }
                    if (hasBody) ActionRow(stringResource(R.string.actions_copy), Icons.Filled.ContentCopy) { onCopy(); onDismiss() }
                    ActionRow(
                        if (message.starred) stringResource(R.string.viewer_unstar) else stringResource(R.string.viewer_star),
                        if (message.starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                    ) { onStar(); onDismiss() }
                    if (!isMine && onHide != null) {
                        ActionRow(stringResource(R.string.actions_hide), Icons.Outlined.VisibilityOff) { onHide() }
                    }
                    if (!isMine && onReport != null) {
                        ActionRow(
                            stringResource(R.string.report_message_action),
                            Icons.Outlined.Flag,
                            destructive = true,
                        ) { onReport() }
                    }
                    ActionRow(stringResource(R.string.common_delete), Icons.Filled.Delete, destructive = true) { onDelete() }
                }
            }
        }
    }
}

/**
 * §19.4: swipeable, paged preview of a multi-image/video message inside the action
 * sheet. Left/right swipes page through the message's media (paging is owned by the
 * pager, so it never dismisses the overlay), with a dot indicator. Videos show a
 * static first-frame thumbnail + play badge; the action buttons below stay live.
 */
@Composable
private fun MediaPreviewPager(atts: List<Attachment>, isMine: Boolean) {
    val pagerState = rememberPagerState(initialPage = 0) { atts.size }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(Modifier.width(240.dp).padding(4.dp)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth(),
                pageSpacing = 6.dp,
            ) { page ->
                val att = atts[page]
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1A1A1A)),
                ) {
                    if (att.kind == "VIDEO") {
                        val thumb by com.klic.mobile.app.feature.chat.media.rememberVideoThumbnail(att)
                        thumb?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.matchParentSize(),
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier
                                .size(44.dp)
                                .align(Alignment.Center)
                                .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                                .padding(6.dp),
                        )
                    } else {
                        AsyncImage(
                            model = rememberStableImageRequest(att.url),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            // Page indicator dots over the media's bottom edge.
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                repeat(atts.size) { i ->
                    val active = i == pagerState.currentPage
                    Box(
                        Modifier
                            .size(if (active) 7.dp else 6.dp)
                            .clip(CircleShape)
                            .background(if (active) Color.White else Color.White.copy(alpha = 0.45f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionRow(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, destructive: Boolean = false, onClick: () -> Unit) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = tint, modifier = Modifier.weight(1f))
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun previewText(m: Message): String = when {
    m.body.isNotBlank() -> m.body
    m.isSticker -> stringResource(R.string.preview_sticker)
    m.attachments.firstOrNull()?.kind == "IMAGE" -> stringResource(R.string.preview_photo)
    m.attachments.firstOrNull()?.kind == "VOICE" -> stringResource(R.string.preview_voice_message)
    m.attachments.firstOrNull()?.kind == "VIDEO_NOTE" -> stringResource(R.string.preview_video_message)
    m.attachments.firstOrNull()?.kind == "VIDEO" -> stringResource(R.string.preview_video)
    m.attachments.isNotEmpty() -> stringResource(R.string.preview_file)
    else -> stringResource(R.string.preview_message)
}

// MARK: - Reaction chips (INSIDE the bubble, §14.5)

/**
 * §14.5: reaction chips rendered at the bubble's bottom edge, within its background.
 * Subtle contrasting chip fills keep them readable on own-color bubbles ([onPrimary]),
 * neutral peer bubbles, and directly over media ([onMedia], scrim-backed). The user's
 * own reaction gets a slightly stronger fill. Tap behavior (toggle) is unchanged.
 */
@Composable
fun ReactionChipsInline(
    reactions: List<Reaction>,
    onTap: (String) -> Unit,
    modifier: Modifier = Modifier,
    onPrimary: Boolean = false,
    onMedia: Boolean = false,
) {
    if (reactions.isEmpty()) return
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        reactions.forEach { r ->
            val chipColor = when {
                onMedia && r.mine -> Color.Black.copy(alpha = 0.62f)
                onMedia -> Color.Black.copy(alpha = 0.42f)
                onPrimary && r.mine -> Color.White.copy(alpha = 0.38f)
                onPrimary -> Color.White.copy(alpha = 0.20f)
                r.mine -> MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            }
            val countColor = when {
                onMedia -> Color.White
                onPrimary -> Color.White
                r.mine -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Surface(
                shape = RoundedCornerShape(50),
                color = chipColor,
                modifier = Modifier.clickable { onTap(r.emoji) },
            ) {
                Row(
                    Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(r.emoji, fontSize = 12.sp)
                    if (r.count > 1) {
                        Text(
                            " ${r.count}",
                            style = MaterialTheme.typography.labelSmall,
                            color = countColor,
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Reaction pills (under a bubble — stickers/bubble-less renders only)

@Composable
fun ReactionPillsRow(reactions: List<Reaction>, onTap: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        reactions.forEach { r ->
            Surface(
                shape = RoundedCornerShape(50),
                color = if (r.mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.clickable { onTap(r.emoji) },
            ) {
                Row(Modifier.padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.emoji, fontSize = 13.sp)
                    if (r.count > 1) {
                        Text(
                            " ${r.count}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (r.mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Reply views (§16.1)

/**
 * §16.1: the label shown for a reply/pin snippet when the parent carries no text —
 * "Photo", "Video", "GIF", "Voice message", "Video message", "Sticker" or the file name.
 */
@Composable
fun replySnippetText(reply: ReplyPreview): String {
    if (reply.deleted == true) return stringResource(R.string.reply_deleted_message)
    if (reply.preview.isNotBlank()) return reply.preview
    val att = reply.attachment
    return when {
        reply.kind == "STICKER" -> stringResource(R.string.preview_sticker)
        att?.kind == "IMAGE" && att.contentType.contains("gif", ignoreCase = true) ->
            stringResource(R.string.preview_gif)
        att?.kind == "IMAGE" || reply.kind == "IMAGE" -> stringResource(R.string.preview_photo)
        att?.kind == "VIDEO_NOTE" || reply.kind == "VIDEO_NOTE" ->
            stringResource(R.string.preview_video_message)
        att?.kind == "VIDEO" || reply.kind == "VIDEO" -> stringResource(R.string.preview_video)
        att?.kind == "VOICE" || reply.kind == "VOICE" -> stringResource(R.string.preview_voice_message)
        att?.kind == "FILE" || reply.kind == "FILE" ->
            att?.fileName ?: stringResource(R.string.preview_file)
        else -> stringResource(R.string.preview_message)
    }
}

/** True when the reply's parent has visual media worth a thumbnail (§16.1). */
private fun hasVisualThumb(reply: ReplyPreview): Boolean {
    if (reply.deleted == true) return false
    val att = reply.attachment ?: return false
    return when (att.kind) {
        "IMAGE", "VIDEO", "VIDEO_NOTE" -> true
        "FILE" -> att.contentType.startsWith("image/")
        else -> false
    }
}

/**
 * §16.1: quote card at the TOP, INSIDE the message bubble — 3dp rounded accent bar,
 * background = accent at ~10% alpha, optional 38dp thumbnail (circular for round
 * video notes), sender name in the accent color and a one-line snippet. Tapping the
 * card scrolls to the original message ([onClick]).
 */
@Composable
fun ReplyCard(
    reply: ReplyPreview,
    authorName: String,
    onPrimary: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val accent = if (onPrimary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    val snippetColor = if (onPrimary) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                       else MaterialTheme.colorScheme.onSurfaceVariant
    val deleted = reply.deleted == true
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.10f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Accent bar — full card height, rounded.
        Box(
            Modifier
                .fillMaxHeight()
                .width(3.dp)
                .background(accent, RoundedCornerShape(2.dp)),
        )
        if (hasVisualThumb(reply)) {
            val att = reply.attachment!!
            // Round video notes keep their circular identity in the thumb.
            val thumbShape = if (att.kind == "VIDEO_NOTE") CircleShape else RoundedCornerShape(4.dp)
            Box(
                Modifier
                    .padding(start = 7.dp, top = 5.dp, bottom = 5.dp)
                    .size(38.dp)
                    .clip(thumbShape)
                    .background(Color.Black.copy(alpha = 0.15f)),
            ) {
                if (att.kind == "VIDEO" || att.kind == "VIDEO_NOTE") {
                    val thumb by com.klic.mobile.app.feature.chat.media.rememberVideoThumbnail(att.asAttachment())
                    thumb?.let {
                        androidx.compose.foundation.Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                } else {
                    AsyncImage(
                        model = rememberStableImageRequest(att.url),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
        }
        Column(Modifier.padding(start = 8.dp, top = 5.dp, bottom = 5.dp, end = 10.dp)) {
            Text(
                authorName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Plain (non-image) files get a small doc glyph beside the name.
                if (!deleted && reply.attachment?.kind == "FILE" &&
                    reply.attachment.contentType.startsWith("image/").not()
                ) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(
                            com.klic.mobile.app.ui.theme.KlicIcons.document,
                        ),
                        contentDescription = null,
                        tint = snippetColor,
                        modifier = Modifier.size(12.dp).padding(end = 2.dp),
                    )
                }
                Text(
                    replySnippetText(reply),
                    style = MaterialTheme.typography.labelSmall,
                    fontStyle = if (deleted) FontStyle.Italic else FontStyle.Normal,
                    color = snippetColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// MARK: - Tombstone

@Composable
fun DeletedBubble(isMine: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Block, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                Text(
                    "  " + stringResource(R.string.actions_message_deleted),
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// MARK: - Typing indicator

@Composable
fun TypingBubble() {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) {
                Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(50)))
            }
        }
    }
}

// MARK: - Fullscreen zoomable image viewer

@Composable
fun ImageViewerOverlay(url: String, onDismiss: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (scale > 1f) offset + pan else Offset.Zero
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            // §9.9: same stable key as the inline bubble — the viewer opens from cache.
            model = rememberStableImageRequest(url),
            contentDescription = "Image",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                },
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
        ) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(com.klic.mobile.app.ui.theme.KlicIcons.close),
                contentDescription = "Close",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(26.dp),
            )
        }
    }
}
