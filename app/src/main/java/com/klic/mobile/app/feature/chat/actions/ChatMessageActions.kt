package com.klic.mobile.app.feature.chat.actions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Flag
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.klic.mobile.app.ui.components.rememberStableImageRequest
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
) {
    val mine = remember(message.reactions) { message.reactions.filter { it.mine }.map { it.emoji }.toSet() }
    val hasBody = message.body.isNotBlank()

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

            // Compact preview of the message
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

            // Actions card
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                Column(Modifier.width(240.dp)) {
                    ActionRow(stringResource(R.string.viewer_reply), Icons.AutoMirrored.Filled.Reply) { onReply(); onDismiss() }
                    if (hasBody) ActionRow(stringResource(R.string.actions_copy), Icons.Filled.ContentCopy) { onCopy(); onDismiss() }
                    ActionRow(
                        if (message.starred) stringResource(R.string.viewer_unstar) else stringResource(R.string.viewer_star),
                        if (message.starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                    ) { onStar(); onDismiss() }
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

// MARK: - Reply views

@Composable
fun ReplyQuote(reply: ReplyPreview, authorName: String, onPrimary: Boolean = false) {
    val accent = if (onPrimary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 3.dp, height = 30.dp).background(accent, RoundedCornerShape(2.dp)))
        Column(Modifier.padding(start = 8.dp)) {
            Text(authorName, style = MaterialTheme.typography.labelSmall, color = accent)
            Text(
                reply.preview,
                style = MaterialTheme.typography.labelSmall,
                color = if (onPrimary) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun ReplyComposerBar(authorName: String, preview: String, onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 3.dp, height = 32.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(stringResource(R.string.actions_reply_to, authorName), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(preview, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        IconButton(onClick = onCancel) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(com.klic.mobile.app.ui.theme.KlicIcons.close),
                contentDescription = "Cancel reply",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
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
