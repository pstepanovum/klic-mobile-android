package com.klic.mobile.app.feature.call

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.data.RecentCall
import com.klic.mobile.app.data.User
import com.klic.mobile.app.feature.KlicViewModel
import com.klic.mobile.app.ui.components.AvatarView
import com.klic.mobile.app.ui.components.KlicSearchBar
import com.klic.mobile.app.ui.theme.KlicIcons
import java.time.Duration
import java.time.Instant

@Composable
fun CallDialScreen(vm: KlicViewModel) {
    val friends by vm.friends.collectAsState()
    val recents by vm.recentCalls.collectAsState()
    var searchText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.loadFriends(); vm.loadRecentCalls() }

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
                if (searchText.isEmpty() && recents.isNotEmpty()) {
                    item { SectionHeader("Recent") }
                    items(recents, key = { it.id }) { call ->
                        RecentCallRow(call) {
                            val name = call.peerNames
                            vm.startCall(call.conversationId, call.kind, name)
                        }
                    }
                    item { SectionHeader("Contacts") }
                }
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
                items(filtered, key = { it.id }) { friend ->
                    FriendCallRow(
                        friend = friend,
                        onAudioCall = {
                            vm.callFriendDirect(friend.id, "AUDIO", friend.displayName)
                        },
                        onVideoCall = {
                            vm.callFriendDirect(friend.id, "VIDEO", friend.displayName)
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
        AvatarView(url = friend.avatarUrl, name = friend.displayName, size = 50.dp)
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
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
    )
}

@Composable
private fun RecentCallRow(call: RecentCall, onCallBack: () -> Unit) {
    val missed = call.outcome != "completed"
    val red = Color(0xFFE5484D)
    val direction = if (call.outgoing) "Outgoing" else if (missed) "Missed" else "Incoming"
    val subtitle = if (!missed && call.durationMs != null) {
        "$direction · ${callDurationText(call.durationMs)} · ${relativeTime(call.startedAt)}"
    } else {
        "$direction · ${relativeTime(call.startedAt)}"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarView(url = call.primaryPeer?.avatarUrl, name = call.primaryPeer?.displayName ?: "?", size = 50.dp)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                call.peerNames,
                style = MaterialTheme.typography.titleMedium,
                color = if (missed) red else MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    imageVector = if (call.outgoing) Icons.AutoMirrored.Filled.CallMade else Icons.AutoMirrored.Filled.CallReceived,
                    contentDescription = null,
                    tint = if (missed) red else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(12.dp),
                )
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        CallIconButton(
            if (call.isVideo) KlicIcons.video else KlicIcons.phone,
            "Call back",
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.onPrimary,
            onCallBack,
        )
    }
}

private fun callDurationText(ms: Int?): String {
    val s = (ms ?: 0).coerceAtLeast(0) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}

private fun relativeTime(iso: String): String {
    val then = runCatching { Instant.parse(iso) }.getOrNull() ?: return ""
    val d = Duration.between(then, Instant.now())
    return when {
        d.toMinutes() < 1 -> "now"
        d.toMinutes() < 60 -> "${d.toMinutes()}m ago"
        d.toHours() < 24 -> "${d.toHours()}h ago"
        else -> "${d.toDays()}d ago"
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
