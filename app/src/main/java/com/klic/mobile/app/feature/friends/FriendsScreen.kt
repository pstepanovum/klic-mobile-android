package com.klic.mobile.app.feature.friends

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.data.Conversation
import com.klic.mobile.app.data.FriendRequest
import com.klic.mobile.app.data.Network
import com.klic.mobile.app.data.User
import com.klic.mobile.app.feature.KlicViewModel
import com.klic.mobile.app.ui.components.AvatarView
import com.klic.mobile.app.ui.components.KlicLottieView
import com.klic.mobile.app.ui.components.KlicTextField
import com.klic.mobile.app.ui.components.PillButton
import com.klic.mobile.app.ui.theme.KlicIcons
import androidx.compose.ui.res.stringResource
import com.klic.mobile.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(
    vm: KlicViewModel,
    onOpenProfile: (String) -> Unit,
    onOpenChat: (Conversation) -> Unit,
) {
    val friends by vm.friends.collectAsState()
    val requests by vm.friendRequests.collectAsState()
    val presenceMap by vm.presence.collectAsState()
    var showAddFriendSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.loadFriends() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    // §13.2: root page title — TikTok Sans 24pt Expanded Regular (size unchanged).
                    Text(
                        stringResource(R.string.tab_friends),
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
                    IconButton(onClick = { showAddFriendSheet = true }) {
                        Icon(
                            painter = painterResource(KlicIcons.add),
                            contentDescription = stringResource(R.string.friends_add_friend),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            ) {
                if (requests.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.friends_requests)) }
                    items(requests) { req ->
                        RequestRow(
                            req,
                            onAccept  = { vm.acceptRequest(req.requestId) },
                            onDecline = { vm.declineRequest(req.requestId) },
                        )
                    }
                    item { Spacer(Modifier.size(20.dp)) }
                }

                item { SectionTitle(stringResource(R.string.friends_your_friends)) }
                if (friends.isEmpty()) {
                    // Empty state: the 01 doodle centered in the remaining screen.
                    item {
                        Column(
                            modifier = Modifier
                                .fillParentMaxHeight(0.75f)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            KlicLottieView(
                                name = "01",
                                modifier = Modifier.fillMaxWidth().height(180.dp),
                            )
                            Text(
                                stringResource(R.string.friends_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                    }
                }
                items(friends) { friend ->
                    val online = presenceMap[friend.id]?.online == true
                    FriendRow(friend = friend, online = online) {
                        vm.openConversationWith(friend.id) { convo -> onOpenProfile(convo.id) }
                    }
                }
                if (friends.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.friends_footer),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 16.dp),
                        )
                    }
                }
            }
        }
    }

    if (showAddFriendSheet) {
        AddFriendSheet(vm = vm, onDismiss = { showAddFriendSheet = false })
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun RequestRow(req: FriendRequest, onAccept: () -> Unit, onDecline: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarView(url = Network.avatarUrl(req.from.id), name = req.from.displayName, size = 44.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(req.from.displayName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text("@${req.from.username}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(
            onClick = onAccept,
            modifier = Modifier.size(40.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(painter = painterResource(KlicIcons.message), contentDescription = "Accept", modifier = Modifier.size(18.dp))
        }
        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        IconButton(
            onClick = onDecline,
            modifier = Modifier.size(40.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(painter = painterResource(KlicIcons.close), contentDescription = "Decline", modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun FriendRow(friend: User, online: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            AvatarView(url = friend.avatarUrl, name = friend.displayName, size = 44.dp)
            if (online) {
                Box(
                    Modifier
                        .size(13.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                        .padding(2.dp)
                        .background(Color(0xFF22C55E), CircleShape),
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(friend.displayName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text("@${friend.username}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "View profile",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────
// Add Friend Sheet
// ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddFriendSheet(vm: KlicViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val friendStatus by vm.friendStatus.collectAsState()
    var username by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = painterResource(KlicIcons.close),
                        contentDescription = "Close",
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    stringResource(R.string.friends_add_friend),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp),
                )
            }
            KlicTextField(
                value = username,
                onValueChange = { username = it },
                placeholder = stringResource(R.string.auth_username),
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Spacer(Modifier.height(12.dp))
            PillButton(
                text = stringResource(R.string.friends_send_request),
                onClick = { vm.addFriend(username) },
            )
            friendStatus?.let { status ->
                Text(
                    status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
