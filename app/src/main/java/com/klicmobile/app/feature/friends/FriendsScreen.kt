package com.klicmobile.app.feature.friends

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.klicmobile.app.data.FriendRequest
import com.klicmobile.app.data.Network
import com.klicmobile.app.data.User
import com.klicmobile.app.feature.KlicViewModel
import com.klicmobile.app.ui.components.AvatarView
import com.klicmobile.app.ui.components.KlicLottieView
import com.klicmobile.app.ui.components.KlicTextField
import com.klicmobile.app.ui.theme.KlicIcons

@Composable
fun FriendsScreen(vm: KlicViewModel, onOpenProfile: (String) -> Unit) {
    val friends by vm.friends.collectAsState()
    val requests by vm.friendRequests.collectAsState()
    val status by vm.friendStatus.collectAsState()
    val presenceMap by vm.presence.collectAsState()
    var username by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.loadFriends() }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
        modifier = Modifier
            .widthIn(max = 680.dp)
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        item {
            Text("Add by username", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                KlicTextField(username, { username = it }, "username", modifier = Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                IconButton(
                    onClick = { vm.addFriend(username); username = "" },
                    modifier = Modifier.size(50.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        painter = painterResource(KlicIcons.addUser),
                        contentDescription = "Add friend",
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.size(20.dp))
        }

        if (requests.isNotEmpty()) {
            item { SectionTitle("Requests") }
            items(requests) { req ->
                RequestRow(
                    req,
                    onAccept  = { vm.acceptRequest(req.requestId) },
                    onDecline = { vm.declineRequest(req.requestId) },
                )
            }
            item { Spacer(Modifier.size(20.dp)) }
        }

        item { SectionTitle("Your friends") }
        if (friends.isEmpty()) {
            item {
                Text(
                    "No friends yet — add someone by username above.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(friends) { friend ->
            val online = presenceMap[friend.id]?.online == true
            FriendRow(friend, online) {
                vm.openConversationWith(friend.id) { convo -> onOpenProfile(convo.id) }
            }
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                KlicLottieView(
                    name = "01",
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                )
                Text(
                    "Your people, all in one place.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
    } // Box
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
        Spacer(Modifier.width(8.dp))
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
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "View profile",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}
