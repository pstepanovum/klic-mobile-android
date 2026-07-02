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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.Block
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
import com.klic.mobile.app.data.Message
import com.klic.mobile.app.data.Reaction
import com.klic.mobile.app.data.ReplyPreview

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
                    ActionRow("Reply", Icons.AutoMirrored.Filled.Reply) { onReply(); onDismiss() }
                    if (hasBody) ActionRow("Copy", Icons.Filled.ContentCopy) { onCopy(); onDismiss() }
                    ActionRow(
                        if (message.starred) "Unstar" else "Star",
                        if (message.starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                    ) { onStar(); onDismiss() }
                    ActionRow("Delete", Icons.Filled.Delete, destructive = true) { onDelete() }
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

private fun previewText(m: Message): String = when {
    m.body.isNotBlank() -> m.body
    m.isSticker -> "Sticker"
    m.attachments.firstOrNull()?.kind == "IMAGE" -> "📷 Photo"
    m.attachments.firstOrNull()?.kind == "VOICE" -> "🎤 Voice message"
    m.attachments.firstOrNull()?.kind == "VIDEO" -> "🎥 Video"
    m.attachments.isNotEmpty() -> "📎 File"
    else -> "Message"
}

// MARK: - Reaction pills (under a bubble)

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
            Text("Reply to $authorName", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(preview, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        IconButton(onClick = onCancel) {
            Icon(Icons.Filled.Close, contentDescription = "Cancel reply", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    "  This message was deleted",
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
            model = url,
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
            Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(28.dp))
        }
    }
}
