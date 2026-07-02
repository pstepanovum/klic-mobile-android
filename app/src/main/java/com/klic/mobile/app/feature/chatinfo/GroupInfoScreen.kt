package com.klic.mobile.app.feature.chatinfo

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.data.ImageUploads
import com.klic.mobile.app.data.Message
import com.klic.mobile.app.feature.KlicViewModel
import com.klic.mobile.app.feature.chat.messagelist.messagePreview
import com.klic.mobile.app.ui.components.AvatarView
import com.klic.mobile.app.ui.components.KlicSearchBar
import com.klic.mobile.app.ui.theme.KlicIcons
import kotlinx.coroutines.launch

private sealed class GroupInfoRoute {
    object Main : GroupInfoRoute()
    object Search : GroupInfoRoute()
    data class Sub(val sub: ChatInfoSub) : GroupInfoRoute()
}

/**
 * Group info page (§8.4): cover upload, call buttons, members, message search,
 * notifications, media/starred/storage sections, "Created by" footer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    vm: KlicViewModel,
    conversationId: String,
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
) {
    val conversations by vm.conversations.collectAsState()
    val me by vm.currentUser.collectAsState()
    val conversation = conversations.firstOrNull { it.id == conversationId } ?: run {
        Box(Modifier.fillMaxSize())
        return
    }
    val title = conversation.title?.takeIf { it.isNotBlank() }
        ?: conversation.members.joinToString(", ") { it.displayName }.ifBlank { "Group" }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var route by remember { mutableStateOf<GroupInfoRoute>(GroupInfoRoute.Main) }

    BackHandler(enabled = route != GroupInfoRoute.Main) { route = GroupInfoRoute.Main }

    // Group cover upload (§8.4) — server-side only the creator may edit.
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val encoded = ImageUploads.encodeAvatar(context, uri)
                if (encoded == null) {
                    vm.error.value = "Couldn't read the selected photo."
                } else {
                    vm.updateGroupCover(conversationId, encoded.bytes, encoded.contentType)
                }
            }
        }
    }

    fun senderName(senderId: String): String = when (senderId) {
        me?.id -> "You"
        else -> conversation.members.firstOrNull { it.id == senderId }?.displayName ?: "Member"
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (route) {
                            GroupInfoRoute.Main -> "Group Info"
                            GroupInfoRoute.Search -> "Search messages"
                            is GroupInfoRoute.Sub -> when ((route as GroupInfoRoute.Sub).sub) {
                                ChatInfoSub.MEDIA -> "Media, links, docs"
                                ChatInfoSub.STARRED -> "Starred"
                                ChatInfoSub.STORAGE -> "Manage storage"
                            }
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (route == GroupInfoRoute.Main) onBack() else route = GroupInfoRoute.Main }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (route == GroupInfoRoute.Main) {
                        IconButton(onClick = { route = GroupInfoRoute.Search }) {
                            Icon(
                                painter = painterResource(KlicIcons.search),
                                contentDescription = "Search messages",
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when (val current = route) {
                is GroupInfoRoute.Sub -> when (current.sub) {
                    ChatInfoSub.MEDIA -> MediaLinksDocsPage(vm, conversationId)
                    ChatInfoSub.STARRED -> StarredMessagesPage(
                        vm, conversationId,
                        senderName = ::senderName,
                        onOpenMessage = { msg ->
                            vm.requestJumpTo(msg.id)
                            onOpenChat()
                        },
                    )
                    ChatInfoSub.STORAGE -> ManageStoragePage(conversationId)
                }

                GroupInfoRoute.Search -> GroupMessageSearch(
                    vm = vm,
                    conversationId = conversationId,
                    senderName = ::senderName,
                    onResultTap = { msg ->
                        vm.requestJumpTo(msg.id)
                        onOpenChat()
                    },
                )

                GroupInfoRoute.Main -> Column(
                    Modifier
                        .widthIn(max = 680.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                ) {
                    // Cover + title
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box {
                            AvatarView(url = conversation.avatarUrl, name = title, size = 110.dp)
                            if (conversation.isAdmin) {
                                Box(
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(34.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                                        .clickable { coverPicker.launch("image/*") },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(KlicIcons.camera),
                                        contentDescription = "Change group photo",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${conversation.members.size + 1} members",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(20.dp))
                        // Audio/Video — start the group call, or join the one already live.
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            GroupCallButton(KlicIcons.phone, "Audio") {
                                if (vm.chatActiveCall.value?.conversationId == conversationId) {
                                    vm.joinOngoingCall(conversationId)
                                } else {
                                    vm.startCall(conversationId, "AUDIO", title)
                                }
                            }
                            GroupCallButton(KlicIcons.video, "Video") {
                                if (vm.chatActiveCall.value?.conversationId == conversationId) {
                                    vm.joinOngoingCall(conversationId)
                                } else {
                                    vm.startCall(conversationId, "VIDEO", title)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    ChatNotificationsCard(vm, conversationId, isGroup = true)
                    Spacer(Modifier.height(16.dp))
                    ChatInfoSectionsCard(conversationId) { route = GroupInfoRoute.Sub(it) }
                    Spacer(Modifier.height(16.dp))

                    // Members
                    Text(
                        "MEMBERS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 18.dp, bottom = 6.dp),
                    )
                    InfoCard {
                        me?.let { u ->
                            MemberRow(name = "${u.displayName} (you)", username = u.username, avatarUrl = u.avatarUrl)
                            if (conversation.members.isNotEmpty()) InfoDivider()
                        }
                        conversation.members.forEachIndexed { index, member ->
                            MemberRow(name = member.displayName, username = member.username, avatarUrl = member.avatarUrl)
                            if (index != conversation.members.lastIndex) InfoDivider()
                        }
                    }

                    // Footer: created by / created at (§8.4)
                    Spacer(Modifier.height(20.dp))
                    val creator = conversation.createdById?.let { id ->
                        if (id == me?.id) "you" else conversation.members.firstOrNull { it.id == id }?.displayName
                    }
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        creator?.let {
                            Text(
                                "Created by $it",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        conversation.createdAt?.let {
                            Text(
                                "Created ${shortDate(it)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                }
            }
        }
    }
}

@Composable
private fun GroupCallButton(iconRes: Int, label: String, onClick: () -> Unit) {
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
            modifier = Modifier.size(56.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MemberRow(name: String, username: String, avatarUrl: String?) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarView(url = avatarUrl, name = name, size = 40.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text("@$username", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Client-side message search with fetch-back pagination (§8.4). Filters the pages
 * fetched so far; "Search older" pulls more history. A result tap jumps to the
 * message in the chat.
 */
@Composable
private fun GroupMessageSearch(
    vm: KlicViewModel,
    conversationId: String,
    senderName: (String) -> String,
    onResultTap: (Message) -> Unit,
) {
    var query by rememberSaveable(conversationId) { mutableStateOf("") }
    var loaded by remember(conversationId) { mutableStateOf<List<Message>>(emptyList()) }
    var oldest by remember(conversationId) { mutableStateOf<String?>(null) }
    var exhausted by remember(conversationId) { mutableStateOf(false) }
    var loading by remember(conversationId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun loadMore(pages: Int = 2) {
        if (exhausted || loading) return
        loading = true
        repeat(pages) {
            val batch = vm.fetchMessagesBefore(conversationId, oldest)
            if (batch.isNullOrEmpty()) { exhausted = true; loading = false; return }
            oldest = batch.last().createdAt
            loaded = loaded + batch
            if (batch.size < 50) { exhausted = true; loading = false; return }
        }
        loading = false
    }

    androidx.compose.runtime.LaunchedEffect(conversationId) { loadMore() }

    val results = remember(query, loaded) {
        if (query.isBlank()) emptyList()
        else loaded.filter { !it.isDeleted && it.body.contains(query, ignoreCase = true) }
    }

    Column(Modifier.widthIn(max = 680.dp).fillMaxSize()) {
        KlicSearchBar(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search messages",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(results, key = { it.id }) { msg ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onResultTap(msg) }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Row {
                        Text(
                            senderName(msg.senderId),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            shortDate(msg.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        messagePreview(msg),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                InfoDivider()
            }
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                    when {
                        loading -> androidx.compose.material3.CircularProgressIndicator(
                            Modifier.size(22.dp), strokeWidth = 2.dp,
                        )
                        query.isNotBlank() && results.isEmpty() && exhausted ->
                            Text(
                                "No matches.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        !exhausted -> androidx.compose.material3.TextButton(
                            onClick = { scope.launch { loadMore(pages = 4) } },
                        ) { Text("Search older messages") }
                    }
                }
            }
        }
    }
}
