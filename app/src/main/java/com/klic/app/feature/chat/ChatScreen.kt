package com.klic.app.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
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
import androidx.compose.ui.unit.dp
import com.klic.app.data.Conversation
import com.klic.app.data.Message
import com.klic.app.feature.KlicViewModel
import com.klic.app.ui.components.KlicTextField
import com.klic.app.ui.theme.Background
import com.klic.app.ui.theme.OnPrimary
import com.klic.app.ui.theme.Primary
import com.klic.app.ui.theme.Surface
import com.klic.app.ui.theme.SurfaceRaised
import com.klic.app.ui.theme.TextPrimary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: KlicViewModel, conversation: Conversation, onBack: () -> Unit, onCall: (String) -> Unit) {
    val messages by vm.messages.collectAsState()
    val me by vm.currentUser.collectAsState()
    var draft by remember { mutableStateOf("") }
    val title = conversation.members.firstOrNull()?.displayName ?: "Chat"

    LaunchedEffect(conversation.id) {
        vm.openChat(conversation.id)
        vm.markRead(conversation.id)
    }

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.startCall(conversation.id, "AUDIO", title); onCall("AUDIO") }) {
                        Icon(Icons.Default.Call, contentDescription = "Voice call")
                    }
                    IconButton(onClick = { vm.startCall(conversation.id, "VIDEO", title); onCall("VIDEO") }) {
                        Icon(Icons.Default.Videocam, contentDescription = "Video call")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(modifier = Modifier.weight(1f).padding(16.dp)) {
                items(messages) { msg -> MessageBubble(msg, isMine = msg.senderId == me?.id) }
            }
            Row(
                Modifier.fillMaxWidth().background(Surface).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KlicTextField(draft, { draft = it }, "Message", modifier = Modifier.weight(1f))
                IconButton(
                    onClick = {
                        if (draft.isNotBlank()) { vm.send(conversation.id, draft.trim()); draft = "" }
                    },
                    modifier = Modifier.padding(start = 8.dp).size(50.dp)
                        .background(Primary, CircleShape),
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = OnPrimary) }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: Message, isMine: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            Modifier
                .background(if (isMine) Primary else SurfaceRaised, RoundedCornerShape(22.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                message.body,
                color = if (isMine) OnPrimary else TextPrimary,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
