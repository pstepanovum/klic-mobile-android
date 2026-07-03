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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.data.Conversation
import com.klic.mobile.app.data.ImageUploads
import com.klic.mobile.app.data.Member
import com.klic.mobile.app.data.Message
import com.klic.mobile.app.feature.KlicViewModel
import com.klic.mobile.app.feature.chat.messagelist.messagePreview
import com.klic.mobile.app.ui.components.AvatarView
import com.klic.mobile.app.ui.components.KlicSearchBar
import com.klic.mobile.app.ui.theme.KlicIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import com.klic.mobile.app.R

private sealed class GroupInfoRoute {
    object Main : GroupInfoRoute()
    object Search : GroupInfoRoute()
    data class Sub(val sub: ChatInfoSub) : GroupInfoRoute()
}

/**
 * Group info page (§9.3): cover + title, call actions, notifications, media/starred/
 * storage sections, member management (admin remove), "Created by" footer. Every
 * internal sub-page pops back exactly one level (§9.4).
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
    val conversation = conversations.firstOrNull { it.id == conversationId }
    // The group vanished under us (e.g. we were removed, §9.3) — leave the page.
    if (conversation == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val title = conversation.title?.takeIf { it.isNotBlank() }
        ?: conversation.members.joinToString(", ") { it.displayName }.ifBlank { "Group" }
    var route by remember { mutableStateOf<GroupInfoRoute>(GroupInfoRoute.Main) }

    // System back mirrors the toolbar back: one level at a time (§9.4).
    BackHandler(enabled = route != GroupInfoRoute.Main) { route = GroupInfoRoute.Main }

    val youLabel = stringResource(R.string.common_you)
    val memberLabel = stringResource(R.string.call_member)
    fun senderName(senderId: String): String = when (senderId) {
        me?.id -> youLabel
        else -> conversation.members.firstOrNull { it.id == senderId }?.displayName ?: memberLabel
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (route) {
                            GroupInfoRoute.Main -> stringResource(R.string.group_info_title)
                            GroupInfoRoute.Search -> stringResource(R.string.group_search_messages)
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

                GroupInfoRoute.Main -> GroupInfoMain(
                    vm = vm,
                    conversation = conversation,
                    title = title,
                    meId = me?.id,
                    meName = me?.displayName,
                    meUsername = me?.username,
                    meAvatarUrl = me?.avatarUrl,
                    onOpenSub = { route = GroupInfoRoute.Sub(it) },
                )
            }
        }
    }
}

/** The main Group Info column: header, actions, sections, members, footer (§9.3). */
@Composable
private fun GroupInfoMain(
    vm: KlicViewModel,
    conversation: Conversation,
    title: String,
    meId: String?,
    meName: String?,
    meUsername: String?,
    meAvatarUrl: String?,
    onOpenSub: (ChatInfoSub) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val conversationId = conversation.id
    var memberSheet by remember { mutableStateOf<Member?>(null) }
    var removeTarget by remember { mutableStateOf<Member?>(null) }
    // §11.5: adjust step (pinch-zoom in a rounded-square mask) before the cover upload.
    var adjustCoverUri by remember { mutableStateOf<android.net.Uri?>(null) }

    // Group cover upload (§8.4) — server-side only the creator may edit.
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) adjustCoverUri = uri
    }

    Column(
        Modifier
            .widthIn(max = 680.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        // ── Cover, title, call actions ────────────────────────────────────────
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
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.group_members_count, conversation.members.size + 1),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            // Audio/Video — start the group call, or join the one already live.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                GroupCallButton(KlicIcons.phone, stringResource(R.string.action_audio)) {
                    if (vm.chatActiveCall.value?.conversationId == conversationId) {
                        vm.joinOngoingCall(conversationId)
                    } else {
                        vm.startCall(conversationId, "AUDIO", title)
                    }
                }
                GroupCallButton(KlicIcons.video, stringResource(R.string.action_video)) {
                    if (vm.chatActiveCall.value?.conversationId == conversationId) {
                        vm.joinOngoingCall(conversationId)
                    } else {
                        vm.startCall(conversationId, "VIDEO", title)
                    }
                }
            }
        }

        // ── Notifications ─────────────────────────────────────────────────────
        Spacer(Modifier.height(24.dp))
        ChatNotificationsCard(vm, conversationId, isGroup = true)

        // ── Media / starred / storage ─────────────────────────────────────────
        Spacer(Modifier.height(16.dp))
        ChatInfoSectionsCard(conversationId) { onOpenSub(it) }

        // ── Members ───────────────────────────────────────────────────────────
        Spacer(Modifier.height(16.dp))
        InfoSectionLabel(stringResource(R.string.group_members_label))
        InfoCard {
            if (meName != null) {
                MemberRow(
                    name = "$meName (you)",
                    username = meUsername.orEmpty(),
                    avatarUrl = meAvatarUrl,
                    isAdmin = conversation.createdById == meId,
                )
                if (conversation.members.isNotEmpty()) InfoDivider()
            }
            conversation.members.forEachIndexed { index, member ->
                MemberRow(
                    name = member.displayName,
                    username = member.username,
                    avatarUrl = member.avatarUrl,
                    isAdmin = conversation.createdById == member.id,
                    about = member.about,
                    onClick = { memberSheet = member },
                )
                if (index != conversation.members.lastIndex) InfoDivider()
            }
        }

        // ── Footer: created by / created at ───────────────────────────────────
        Spacer(Modifier.height(20.dp))
        val creator = conversation.createdById?.let { id ->
            if (id == meId) "you" else conversation.members.firstOrNull { it.id == id }?.displayName
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            creator?.let {
                Text(
                    stringResource(R.string.group_created_by, it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            conversation.createdAt?.let {
                Text(
                    stringResource(R.string.group_created_on, shortDate(it)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(28.dp))
    }

    // §11.5: crop the picked cover to a square via the adjust sheet, then upload
    // through the EXISTING presign/PUT/PATCH chain (§10.1 step errors intact).
    adjustCoverUri?.let { uri ->
        com.klic.mobile.app.ui.components.ImageAdjustSheet(
            uri = uri,
            mask = com.klic.mobile.app.ui.components.AdjustMask.ROUNDED_SQUARE,
            onDone = { bitmap ->
                adjustCoverUri = null
                scope.launch {
                    val encoded = withContext(Dispatchers.IO) { ImageUploads.encodeBitmap(bitmap) }
                    if (encoded == null) {
                        vm.error.value = context.getString(R.string.group_photo_read_failed)
                    } else {
                        vm.updateGroupCover(conversationId, encoded.bytes, encoded.contentType)
                    }
                }
            },
            onDismiss = { adjustCoverUri = null },
        )
    }

    // Member action sheet — admins get the danger-zone "Remove from group" (§9.3).
    memberSheet?.let { member ->
        MemberActionSheet(
            member = member,
            isCreator = conversation.createdById == member.id,
            canRemove = conversation.isAdmin && member.id != meId,
            onRemove = {
                memberSheet = null
                removeTarget = member
            },
            onDismiss = { memberSheet = null },
        )
    }

    removeTarget?.let { member ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.group_remove_confirm_title, member.displayName)) },
            text = { Text(stringResource(R.string.group_remove_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeGroupMember(conversationId, member.id)
                    removeTarget = null
                }) { Text(stringResource(R.string.group_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
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
private fun MemberRow(
    name: String,
    username: String,
    avatarUrl: String?,
    isAdmin: Boolean = false,
    about: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarView(url = avatarUrl, name = name, size = 40.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text("@$username", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // §11.5: the member's About/status line, when their visibility allows it.
            about?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isAdmin) {
            Text(
                stringResource(R.string.group_admin),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/** Rounded member sheet: identity header + admin-only "Remove from group" (§9.3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemberActionSheet(
    member: Member,
    isCreator: Boolean,
    canRemove: Boolean,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarView(url = member.avatarUrl, name = member.displayName, size = 48.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        member.displayName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        if (isCreator) "@${member.username} · admin" else "@${member.username}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // §11.5: About/status line inside the member sheet.
                    member.about?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (canRemove) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                        .padding(horizontal = 18.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = onRemove).padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.group_remove_from_group),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) { Text(stringResource(R.string.common_cancel), modifier = Modifier.padding(vertical = 6.dp)) }
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

    LaunchedEffect(conversationId) { loadMore() }

    val results = remember(query, loaded) {
        if (query.isBlank()) emptyList()
        else loaded.filter { !it.isDeleted && it.body.contains(query, ignoreCase = true) }
    }

    Column(Modifier.widthIn(max = 680.dp).fillMaxSize()) {
        KlicSearchBar(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.group_search_messages),
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
                        loading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        query.isNotBlank() && results.isEmpty() && exhausted ->
                            Text(
                                stringResource(R.string.group_no_matches),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        !exhausted -> TextButton(
                            onClick = { scope.launch { loadMore(pages = 4) } },
                        ) { Text(stringResource(R.string.info_search_older)) }
                    }
                }
            }
        }
    }
}
