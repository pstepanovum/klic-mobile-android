package com.klic.mobile.app.feature.chatinfo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.feature.KlicViewModel
import com.klic.mobile.app.ui.theme.KlicIcons
import androidx.compose.ui.res.stringResource
import com.klic.mobile.app.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val me by vm.currentUser.collectAsStateWithLifecycle()
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
                                ChatInfoSub.MEDIA -> stringResource(R.string.info_media_links_docs)
                                ChatInfoSub.STARRED -> stringResource(R.string.info_starred)
                                ChatInfoSub.STORAGE -> stringResource(R.string.info_manage_storage)
                                ChatInfoSub.THEME -> stringResource(R.string.info_chat_theme)
                                ChatInfoSub.ENCRYPTION -> stringResource(R.string.info_encryption)
                            }
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (route == GroupInfoRoute.Main) onBack() else route = GroupInfoRoute.Main }) {
                        Icon(
                            painter = painterResource(KlicIcons.back),
                            contentDescription = "Back",
                            modifier = Modifier.size(24.dp),
                        )
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
                    // §14.3: SHARED group theme — admin-only edit surface.
                    ChatInfoSub.THEME -> Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                    ) {
                        com.klic.mobile.app.feature.settings.GroupThemeContent(vm, conversationId)
                    }
                    // §14.3: encryption info page (lock row).
                    ChatInfoSub.ENCRYPTION -> Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        EncryptionInfoPage()
                    }
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
