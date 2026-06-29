package com.klicmobile.app.feature.conversations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.klicmobile.app.data.Conversation
import com.klicmobile.app.data.Message
import com.klicmobile.app.feature.KlicViewModel
import com.klicmobile.app.ui.components.AvatarView
import com.klicmobile.app.ui.components.KlicSearchBar
import com.klicmobile.app.ui.theme.KlicIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(vm: KlicViewModel, onOpenChat: (Conversation) -> Unit) {
    val conversations by vm.conversations.collectAsState()
    val presenceMap by vm.presence.collectAsState()
    var searchText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.loadConversations() }

    val filtered = if (searchText.isEmpty()) conversations else conversations.filter { convo ->
        (convo.members.firstOrNull()?.displayName?.contains(searchText, ignoreCase = true) == true) ||
        (convo.members.firstOrNull()?.username?.contains(searchText, ignoreCase = true) == true) ||
        (convo.lastMessage?.body?.contains(searchText, ignoreCase = true) == true)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Chats", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth(),
            ) {
                KlicSearchBar(
                    value = searchText,
                    onValueChange = { searchText = it },
                    placeholder = "Search chats",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    items(filtered) { convo ->
                        val online = presenceMap[convo.members.firstOrNull()?.id]?.online == true
                        ConversationRow(convo, online) { onOpenChat(convo) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(conversation: Conversation, online: Boolean, onClick: () -> Unit) {
    val member = conversation.members.firstOrNull()
    val title = member?.displayName ?: "Direct"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                AvatarView(url = member?.avatarUrl, name = title, size = 52.dp)
                if (online) {
                    Box(
                        Modifier
                            .size(14.dp)
                            .background(MaterialTheme.colorScheme.background, CircleShape)
                            .padding(2.dp)
                            .background(Color(0xFF22C55E), CircleShape),
                    )
                }
            }
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    lastMessagePreview(conversation.lastMessage),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Time (top) + unread count badge (bottom), right-aligned.
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 8.dp),
            ) {
                lastMessageTime(conversation.lastMessage)?.let { time ->
                    Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val unread = conversation.unreadCount
                if (unread > 0) {
                    Box(
                        Modifier
                            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (unread > 99) "99+" else unread.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
        // Divider inset to start under the text content, not under the avatar.
        HorizontalDivider(
            modifier = Modifier.padding(start = 70.dp),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        )
    }
}

/** Short clock time for the last message (e.g. "3:26 PM"), or null if unknown. */
private fun lastMessageTime(m: Message?): String? {
    val iso = m?.createdAt?.takeIf { it.isNotBlank() } ?: return null
    return runCatching {
        val instant = java.time.Instant.parse(iso)
        java.time.format.DateTimeFormatter.ofPattern("h:mm a")
            .withZone(java.time.ZoneId.systemDefault())
            .format(instant)
    }.getOrNull()
}

/** One-line summary of the last message for the chat list (no emoji, per the design system). */
private fun lastMessagePreview(m: Message?): String = when {
    m == null -> "Say hi"
    m.isDeleted -> "Message deleted"
    m.isCallEvent -> if (m.call?.isVideo == true) "Video call" else "Voice call"
    m.isSticker -> "Sticker"
    m.body.isNotBlank() -> m.body
    m.attachments.firstOrNull()?.kind == "IMAGE" -> "Photo"
    m.attachments.firstOrNull()?.kind == "VIDEO" -> "Video"
    m.attachments.firstOrNull()?.kind == "VOICE" -> "Voice message"
    m.attachments.isNotEmpty() -> "File"
    else -> "Say hi"
}
