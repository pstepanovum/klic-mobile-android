package com.klic.mobile.app.feature.conversations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.data.Conversation
import com.klic.mobile.app.feature.KlicViewModel
import com.klic.mobile.app.ui.components.AvatarView
import com.klic.mobile.app.ui.components.KlicSearchBar
import com.klic.mobile.app.ui.theme.KlicIcons
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.klic.mobile.app.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(vm: KlicViewModel, onOpenChat: (Conversation) -> Unit) {
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val presenceMap by vm.presence.collectAsStateWithLifecycle()
    var searchText by remember { mutableStateOf("") }
    var showNewMessageSheet by remember { mutableStateOf(false) }
    // §18.4: server-side message search (grouped by conversation), debounced.
    var messageResults by remember { mutableStateOf<List<com.klic.mobile.app.data.MessageSearchResult>>(emptyList()) }
    var searchingMessages by remember { mutableStateOf(false) }
    // §16.5: long-press context menu + its follow-up sheets/dialogs.
    var menuTarget by remember { mutableStateOf<Conversation?>(null) }
    var menuPrefs by remember { mutableStateOf<com.klic.mobile.app.data.ConversationPrefs?>(null) }
    var muteTarget by remember { mutableStateOf<Conversation?>(null) }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.loadConversations() }
    // The mute item reflects the freshest per-chat prefs (server or local cache).
    LaunchedEffect(menuTarget?.id) {
        menuPrefs = null
        menuTarget?.let { menuPrefs = vm.fetchConversationPrefs(it.id) }
    }

    val filtered = if (searchText.isEmpty()) conversations else conversations.filter { convo ->
        conversationTitle(convo).contains(searchText, ignoreCase = true) ||
        convo.members.any {
            it.displayName.contains(searchText, ignoreCase = true) ||
                it.username.contains(searchText, ignoreCase = true)
        } ||
        (convo.lastMessage?.body?.contains(searchText, ignoreCase = true) == true)
    }
    // §16.5: pinned chats first (newest pin highest); the rest keep recency order.
    val ordered = remember(filtered) {
        val (pinned, rest) = filtered.partition { it.chatPinnedAt != null }
        pinned.sortedByDescending { it.chatPinnedAt } + rest
    }

    // §18.4: debounce the query, then hit GET /search/messages. Blank query clears.
    val trimmedQuery = searchText.trim()
    LaunchedEffect(trimmedQuery) {
        if (trimmedQuery.isBlank()) {
            messageResults = emptyList()
            searchingMessages = false
            return@LaunchedEffect
        }
        searchingMessages = true
        kotlinx.coroutines.delay(300)
        val res = vm.searchMessagesGlobal(trimmedQuery)
        messageResults = res ?: emptyList()
        searchingMessages = false
    }
    // Preserve server order but group consecutive hits by conversation.
    val groupedResults = remember(messageResults) {
        messageResults.groupBy { it.conversationId }.entries.toList()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    // §13.2: root page title — TikTok Sans 24pt Expanded Regular (size unchanged).
                    Text(
                        stringResource(R.string.tab_chats),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = com.klic.mobile.app.ui.theme.TikTokSansExpanded,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
                        ),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    IconButton(onClick = { showNewMessageSheet = true }) {
                        Icon(
                            painter = painterResource(KlicIcons.add),
                            contentDescription = stringResource(R.string.convos_new_message),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(),
            ) {
                KlicSearchBar(
                    value = searchText,
                    onValueChange = { searchText = it },
                    placeholder = stringResource(R.string.convos_search_chats),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    items(ordered, key = { it.id }) { convo ->
                        val online = convo.type == "DIRECT" &&
                            presenceMap[convo.members.firstOrNull()?.id]?.online == true
                        ConversationRow(
                            conversation = convo,
                            online = online,
                            onClick = { onOpenChat(convo) },
                            onLongPress = { menuTarget = convo },
                        )
                    }

                    // §18.4: message-search results grouped by conversation.
                    if (trimmedQuery.isNotBlank()) {
                        item(key = "msg_search_header") {
                            Text(
                                stringResource(R.string.search_messages_section),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                            )
                        }
                        if (searchingMessages && messageResults.isEmpty()) {
                            item(key = "msg_search_loading") {
                                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp), strokeWidth = 2.dp,
                                    )
                                }
                            }
                        } else if (messageResults.isEmpty()) {
                            item(key = "msg_search_empty") {
                                Text(
                                    stringResource(R.string.search_no_messages),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 12.dp),
                                )
                            }
                        } else {
                            groupedResults.forEach { (conversationId, hits) ->
                                val head = hits.first()
                                item(key = "grp_$conversationId") {
                                    Row(
                                        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        AvatarView(
                                            url = head.conversationAvatarUrl,
                                            name = head.conversationTitle ?: "",
                                            size = 28.dp,
                                        )
                                        Text(
                                            head.conversationTitle ?: stringResource(R.string.search_conversation_fallback),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(start = 10.dp),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                items(hits, key = { it.messageId }) { hit ->
                                    MessageSearchRow(hit) {
                                        val convo = conversations.firstOrNull { it.id == hit.conversationId }
                                        if (convo != null) {
                                            vm.requestJumpTo(hit.messageId)
                                            onOpenChat(convo)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item(key = "list_bottom_spacer") { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    if (showNewMessageSheet) {
        NewMessageSheet(
            vm = vm,
            onDismiss = { showNewMessageSheet = false },
            onOpenChat = { convo ->
                showNewMessageSheet = false
                onOpenChat(convo)
            },
        )
    }

    // §16.5: long-press context menu (mark as read / pin / mute / delete).
    menuTarget?.let { target ->
        // Render against the freshest copy so the pin label tracks the toggle.
        val live = conversations.firstOrNull { it.id == target.id } ?: target
        ConversationActionsOverlay(
            conversation = live,
            title = conversationTitle(live),
            isPinned = live.chatPinnedAt != null,
            isMuted = com.klic.mobile.app.feature.chatinfo.isMuted(menuPrefs?.messagesMutedUntil),
            onMarkRead = if (live.unreadCount > 0) {
                { vm.markConversationRead(live.id) }
            } else null,
            onTogglePin = { vm.setChatPinned(live.id, pinned = live.chatPinnedAt == null) },
            onMute = { muteTarget = live; menuTarget = null },
            onUnmute = {
                val current = menuPrefs ?: com.klic.mobile.app.data.ConversationPrefs()
                scope.launch {
                    vm.setConversationPrefs(
                        live.id, current,
                        setMessagesMuted = true, messagesMutedUntil = null,
                    )
                }
                menuTarget = null
            },
            onDelete = { deleteTarget = live; menuTarget = null },
            onDismiss = { menuTarget = null },
        )
    }

    // §16.5: unmuted rows get the existing mute-duration options (8h / 1w / always).
    muteTarget?.let { target ->
        val current = menuPrefs ?: com.klic.mobile.app.data.ConversationPrefs()
        com.klic.mobile.app.feature.chatinfo.MuteSelectionSheet(
            title = stringResource(R.string.info_mute_messages),
            muted = com.klic.mobile.app.feature.chatinfo.isMuted(current.messagesMutedUntil),
            onPick = { untilIso ->
                scope.launch {
                    vm.setConversationPrefs(
                        target.id, current,
                        setMessagesMuted = true, messagesMutedUntil = untilIso,
                    )
                }
                muteTarget = null
            },
            onDismiss = { muteTarget = null },
        )
    }

    // §16.5: delete keeps the existing delete-conversation semantics + confirm.
    deleteTarget?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.convo_delete_chat)) },
            text = { Text(stringResource(R.string.convo_delete_confirm)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    vm.deleteConversation(target.id)
                    deleteTarget = null
                }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}
