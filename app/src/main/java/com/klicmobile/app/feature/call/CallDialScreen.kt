package com.klicmobile.app.feature.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import com.klicmobile.app.data.User
import com.klicmobile.app.feature.KlicViewModel
import com.klicmobile.app.ui.components.KlicSearchBar
import com.klicmobile.app.ui.theme.KlicIcons

@Composable
fun CallDialScreen(vm: KlicViewModel, onCallStarted: () -> Unit) {
    val friends by vm.friends.collectAsState()
    var searchText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.loadFriends() }

    val filtered = if (searchText.isEmpty()) friends else friends.filter {
        val q = searchText.lowercase()
        it.displayName.lowercase().contains(q) || it.username.lowercase().contains(q)
    }

    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxWidth()) {
            Text(
                "Call",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
            )
            KlicSearchBar(
                value = searchText,
                onValueChange = { searchText = it },
                placeholder = "Search contacts",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (friends.isEmpty()) {
                    item {
                        Text(
                            "No friends yet — add them in Friends.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
                items(filtered) { friend ->
                    FriendCallRow(
                        friend = friend,
                        onAudioCall = {
                            vm.callFriendDirect(friend.id, "AUDIO", friend.displayName, onCallStarted)
                        },
                        onVideoCall = {
                            vm.callFriendDirect(friend.id, "VIDEO", friend.displayName, onCallStarted)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FriendCallRow(friend: User, onAudioCall: () -> Unit, onVideoCall: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(50.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(KlicIcons.user),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(friend.displayName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text("@${friend.username}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CallIconButton(KlicIcons.phone, "Audio call", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, onAudioCall)
            CallIconButton(KlicIcons.video, "Video call", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurface, onVideoCall)
        }
    }
}

@Composable
private fun CallIconButton(iconRes: Int, desc: String, fill: Color, tint: Color, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = fill,
            contentColor = tint,
        ),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = desc,
            modifier = Modifier.size(20.dp),
        )
    }
}
