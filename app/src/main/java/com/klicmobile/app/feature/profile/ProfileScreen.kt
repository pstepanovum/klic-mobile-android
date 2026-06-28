package com.klicmobile.app.feature.profile

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
import androidx.compose.foundation.shape.CircleShape
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
import com.klicmobile.app.data.UserProfile
import com.klicmobile.app.feature.KlicViewModel
import com.klicmobile.app.ui.components.AvatarView
import com.klicmobile.app.ui.theme.KlicIcons
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
) {
    val conversations by vm.conversations.collectAsState()
    val member = conversations.firstOrNull { it.id == conversationId }?.members?.firstOrNull()
    val presenceMap by vm.presence.collectAsState()
    var profile by remember { mutableStateOf<UserProfile?>(null) }

    LaunchedEffect(member?.id) { member?.id?.let { profile = vm.fetchProfile(it) } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Profile", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
        val live = presenceMap[member.id]
        val online = live?.online ?: (profile?.online == true)
        val lastSeenMs = live?.lastSeenMs ?: profile?.lastSeenAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            AvatarView(url = profile?.avatarUrl ?: member.avatarUrl, name = member.displayName, size = 132.dp)
            Spacer(Modifier.height(16.dp))
            Text(member.displayName, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(4.dp))
            Text("@${member.username}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            presenceText(online, lastSeenMs)?.let { text ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(28.dp))
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
        }
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
