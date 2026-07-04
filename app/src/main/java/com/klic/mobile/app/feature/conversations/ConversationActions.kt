package com.klic.mobile.app.feature.conversations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.R
import com.klic.mobile.app.data.Conversation
import com.klic.mobile.app.ui.components.AvatarView
import com.klic.mobile.app.ui.theme.KlicIcons

// MARK: - §16.5: chat-list long-press context menu
//
// Same visual language as the message long-press overlay (ChatMessageActions.kt):
// dimmed scrim, a compact preview of the pressed row, and a 240dp actions card.

@Composable
fun ConversationActionsOverlay(
    conversation: Conversation,
    title: String,
    isPinned: Boolean,
    isMuted: Boolean,
    /** Null when the row has nothing unread — the item is hidden then. */
    onMarkRead: (() -> Unit)?,
    onTogglePin: () -> Unit,
    /** Unmuted rows open the existing mute-duration options. */
    onMute: () -> Unit,
    onUnmute: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 28.dp),
        ) {
            // Compact preview of the pressed conversation row.
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AvatarView(
                        url = if (conversation.type == "GROUP") conversation.avatarUrl
                              else conversation.members.firstOrNull()?.avatarUrl,
                        name = title,
                        size = 34.dp,
                    )
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 10.dp).width(180.dp),
                    )
                }
            }

            // Actions card.
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                Column(Modifier.width(240.dp)) {
                    if (onMarkRead != null) {
                        ConvoActionRow(
                            stringResource(R.string.convo_mark_read),
                            painterResource(KlicIcons.klicCheckDouble),
                        ) { onMarkRead(); onDismiss() }
                    }
                    ConvoActionRow(
                        if (isPinned) stringResource(R.string.actions_unpin) else stringResource(R.string.actions_pin),
                        rememberVectorPainter(Icons.Filled.PushPin),
                    ) { onTogglePin(); onDismiss() }
                    ConvoActionRow(
                        if (isMuted) stringResource(R.string.info_unmute) else stringResource(R.string.convo_mute),
                        painterResource(KlicIcons.bell),
                    ) { if (isMuted) onUnmute() else onMute() }
                    ConvoActionRow(
                        stringResource(R.string.common_delete),
                        painterResource(KlicIcons.trash),
                        destructive = true,
                    ) { onDelete() }
                }
            }
        }
    }
}

@Composable
private fun ConvoActionRow(
    title: String,
    icon: Painter,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = tint, modifier = Modifier.weight(1f))
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}
