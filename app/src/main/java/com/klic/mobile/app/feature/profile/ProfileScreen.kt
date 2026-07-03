package com.klic.mobile.app.feature.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.klic.mobile.app.R
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.data.Conversation
import com.klic.mobile.app.data.UserProfile
import com.klic.mobile.app.feature.KlicViewModel
import com.klic.mobile.app.feature.chatinfo.ChatInfoSectionsCard
import com.klic.mobile.app.feature.chatinfo.ChatInfoSub
import com.klic.mobile.app.feature.chatinfo.ChatNotificationsCard
import com.klic.mobile.app.feature.chatinfo.InfoCard
import com.klic.mobile.app.feature.chatinfo.InfoDivider
import com.klic.mobile.app.feature.chatinfo.InfoSectionLabel
import com.klic.mobile.app.feature.chatinfo.ManageStoragePage
import com.klic.mobile.app.feature.chatinfo.MediaLinksDocsPage
import com.klic.mobile.app.feature.chatinfo.StarredMessagesPage
import com.klic.mobile.app.ui.components.AvatarView
import com.klic.mobile.app.ui.theme.KlicIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    vm: KlicViewModel,
    conversationId: String,
    onBack: () -> Unit,
    onCall: (String) -> Unit,
    onMessage: (() -> Unit)? = null,
    onOpenGroup: ((String) -> Unit)? = null,
) {
    val conversations by vm.conversations.collectAsState()
    val member = conversations.firstOrNull { it.id == conversationId }?.members?.firstOrNull()
    val presenceMap by vm.presence.collectAsState()
    val me by vm.currentUser.collectAsState()
    var profile by remember { mutableStateOf<UserProfile?>(null) }
    var sub by remember { mutableStateOf<ChatInfoSub?>(null) }

    LaunchedEffect(member?.id) {
        member?.id?.let { id ->
            // §9.9: render the cached profile instantly, then refresh in the background.
            vm.cachedProfile(id)?.let { profile = it }
            vm.fetchProfile(id)?.let { profile = it }
        }
    }
    BackHandler(enabled = sub != null) { sub = null }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (sub) {
                            ChatInfoSub.MEDIA -> "Media, links, docs"
                            ChatInfoSub.STARRED -> "Starred"
                            ChatInfoSub.STORAGE -> "Manage storage"
                            null -> "Profile"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (sub == null) onBack() else sub = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (member == null) {
            Box(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }

        when (sub) {
            ChatInfoSub.MEDIA -> Box(Modifier.fillMaxSize().padding(padding)) {
                MediaLinksDocsPage(vm, conversationId)
            }
            ChatInfoSub.STARRED -> Box(Modifier.fillMaxSize().padding(padding)) {
                StarredMessagesPage(
                    vm, conversationId,
                    senderName = { senderId -> if (senderId == me?.id) "You" else member.displayName },
                    onOpenMessage = { msg ->
                        vm.requestJumpTo(msg.id)
                        onMessage?.invoke()
                    },
                )
            }
            ChatInfoSub.STORAGE -> Box(Modifier.fillMaxSize().padding(padding)) {
                ManageStoragePage(conversationId)
            }
            null -> {
                val live = presenceMap[member.id]
                val online = live?.online ?: (profile?.online == true)
                val lastSeenMs = live?.lastSeenMs
                    ?: profile?.lastSeenAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

                Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 680.dp)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // §9.6: same header layout as the user's own profile — avatar,
                        // display name, copyable @username capsule, presence line.
                        Spacer(Modifier.height(8.dp))
                        AvatarView(url = profile?.avatarUrl ?: member.avatarUrl, name = member.displayName, size = 110.dp)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            member.displayName,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(6.dp))
                        CopyableUsername(username = member.username)
                        presenceText(online, lastSeenMs)?.let { text ->
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Spacer(Modifier.height(24.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            CallActionButton(KlicIcons.phone, "Audio") {
                                vm.startCall(conversationId, "AUDIO", member.displayName); onCall("AUDIO")
                            }
                            CallActionButton(KlicIcons.video, "Video") {
                                vm.startCall(conversationId, "VIDEO", member.displayName); onCall("VIDEO")
                            }
                            if (onMessage != null) {
                                CallActionButton(KlicIcons.message, "Message") { onMessage() }
                            }
                        }

                        // §8.4: notifications + media/starred/storage/save-to-photos sections.
                        Spacer(Modifier.height(28.dp))
                        ChatNotificationsCard(vm, conversationId, isGroup = false)
                        Spacer(Modifier.height(16.dp))
                        ChatInfoSectionsCard(conversationId) { sub = it }

                        // §9.6: groups in common — GROUP conversations this friend is in,
                        // derived from the cached conversations list.
                        val groupsInCommon = remember(conversations, member.id) {
                            conversations.filter { c ->
                                c.type == "GROUP" && c.members.any { it.id == member.id }
                            }
                        }
                        if (groupsInCommon.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            Box(Modifier.fillMaxWidth()) { InfoSectionLabel("GROUPS IN COMMON") }
                            InfoCard {
                                groupsInCommon.forEachIndexed { index, group ->
                                    GroupInCommonRow(group) { onOpenGroup?.invoke(group.id) }
                                    if (index != groupsInCommon.lastIndex) InfoDivider()
                                }
                            }
                        }

                        // §10.4: block action with confirm — the blocked list lives in
                        // Settings → Privacy and Security → Blocked Users.
                        var showBlockConfirm by remember { mutableStateOf(false) }
                        Spacer(Modifier.height(16.dp))
                        InfoCard {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { showBlockConfirm = true }
                                    .padding(vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(R.string.profile_block_format, member.displayName),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        if (showBlockConfirm) {
                            AlertDialog(
                                onDismissRequest = { showBlockConfirm = false },
                                title = { Text(stringResource(R.string.profile_block_format, member.displayName)) },
                                text = { Text(stringResource(R.string.profile_block_confirm)) },
                                confirmButton = {
                                    TextButton(onClick = {
                                        showBlockConfirm = false
                                        vm.blockUser(member.id, member.displayName) { onBack() }
                                    }) {
                                        Text(
                                            stringResource(R.string.profile_block),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showBlockConfirm = false }) {
                                        Text(stringResource(R.string.common_cancel))
                                    }
                                },
                            )
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

/** One "Groups in common" row: cover, title, member count — tap opens the group chat (§9.6). */
@Composable
private fun GroupInCommonRow(group: Conversation, onClick: () -> Unit) {
    val title = group.title?.takeIf { it.isNotBlank() }
        ?: group.members.joinToString(", ") { it.displayName }.ifBlank { "Group" }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarView(url = group.avatarUrl, name = title, size = 40.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "${group.members.size + 1} members",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Copyable @username capsule — mirrors the one on the user's own profile (§9.6). */
@Composable
private fun CopyableUsername(username: String) {
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(
                if (copied) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) {
                clipboardManager.setText(AnnotatedString(username))
                copied = true
                scope.launch { delay(1500); copied = false }
            }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "@$username",
            style = MaterialTheme.typography.labelMedium,
            color = if (copied) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            painter = painterResource(if (copied) KlicIcons.check else KlicIcons.copy),
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = if (copied) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        )
    }
}

@Composable
private fun CallActionButton(iconRes: Int, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() },
            onClick = onClick,
        ),
    ) {
        Box(
            modifier = Modifier.size(60.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(24.dp),
            )
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun presenceText(online: Boolean, lastSeenMs: Long?): String? {
    if (online) return "Online"
    val ms = lastSeenMs ?: return null
    val date = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val day = date.toLocalDate()
    val time = DateTimeFormatter.ofPattern("HH:mm").format(date)
    return when (day) {
        LocalDate.now() -> "last seen today at $time"
        LocalDate.now().minusDays(1) -> "last seen yesterday at $time"
        else -> "last seen ${DateTimeFormatter.ofPattern("MMM d").format(day)}"
    }
}
