package com.klic.mobile.app.feature.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.AppContainer
import com.klic.mobile.app.KlicApplication
import com.klic.mobile.app.data.AttachmentInput
import com.klic.mobile.app.data.User
import com.klic.mobile.app.feature.chat.media.loadMediaDraft
import com.klic.mobile.app.ui.components.AvatarView
import com.klic.mobile.app.ui.components.KlicSearchBar
import com.klic.mobile.app.ui.components.PillButton
import com.klic.mobile.app.ui.theme.KlicTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * System share target: other apps share images/videos/text into Klic via ACTION_SEND /
 * ACTION_SEND_MULTIPLE (see the manifest). Shows a friend picker with search + an optional
 * message; sending opens each friend's direct conversation and pushes the media through the
 * normal attachment pipeline. Loads its own session (token load) — it must work as the
 * process entry point, without MainActivity ever having run.
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as KlicApplication).container
        val uris = sharedUris(intent)
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)

        setContent {
            val systemDark = isSystemInDarkTheme()
            val isDark = when (container.themeMode) {
                "light" -> false
                "dark"  -> true
                else    -> systemDark
            }
            KlicTheme(isDark = isDark) {
                SharePanel(container, uris, sharedText) { finish() }
            }
        }
    }

    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(streamUri(intent))
        Intent.ACTION_SEND_MULTIPLE -> streamUriList(intent)
        else -> emptyList()
    }

    @Suppress("DEPRECATION")
    private fun streamUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    @Suppress("DEPRECATION")
    private fun streamUriList(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        })?.filterNotNull().orEmpty()
}

private enum class ShareStage { LOADING, NOT_SIGNED_IN, FAILED, READY, SENDING, SENT }

@Composable
private fun SharePanel(
    container: AppContainer,
    uris: List<Uri>,
    sharedText: String?,
    onClose: () -> Unit,
) {
    val repo = container.repository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var stage by remember { mutableStateOf(ShareStage.LOADING) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var friends by remember { mutableStateOf<List<User>>(emptyList()) }
    var attachments by remember { mutableStateOf<List<AttachmentInput>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var query by remember { mutableStateOf("") }
    // A text shared alongside media pre-fills the message; text-only shares keep it separate.
    var message by remember { mutableStateOf(if (uris.isNotEmpty()) sharedText.orEmpty() else "") }
    var sentCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        // Own lightweight session load — this activity can be the process entry point, so it
        // can't rely on MainActivity's ViewModel having loaded the stored tokens.
        runCatching {
            container.tokenStore.load()
            repo.restoreCachedUser()
        }
        if (!repo.isAuthenticated) {
            stage = ShareStage.NOT_SIGNED_IN
            return@LaunchedEffect
        }
        runCatching { repo.ensureFreshToken() }
        val loaded = runCatching { repo.friends() }.getOrNull()
        if (loaded == null) {
            errorText = "Couldn't load your friends. Check your connection and try again."
            stage = ShareStage.FAILED
            return@LaunchedEffect
        }
        friends = loaded
        if (uris.isNotEmpty()) {
            // Same staging as the in-app picker: images are downscaled/re-encoded, videos
            // read as-is with metadata (see ChatMedia.loadMediaDraft).
            attachments = uris.mapNotNull { uri -> loadMediaDraft(context, uri)?.attachment }
            if (attachments.isEmpty()) {
                errorText = "Couldn't read the shared media."
                stage = ShareStage.FAILED
                return@LaunchedEffect
            }
        } else if (sharedText.isNullOrBlank()) {
            errorText = "Nothing to share."
            stage = ShareStage.FAILED
            return@LaunchedEffect
        }
        stage = ShareStage.READY
    }

    fun send() {
        if (stage != ShareStage.READY || selected.isEmpty()) return
        stage = ShareStage.SENDING
        errorText = null
        sentCount = 0
        val targets = friends.filter { it.id in selected }
        scope.launch {
            val result = runCatching {
                for (friend in targets) {
                    val convo = repo.openConversation(friend.id)
                    if (attachments.isNotEmpty()) {
                        repo.uploadAttachments(convo.id, attachments, body = message.trim().takeIf { it.isNotBlank() })
                    } else {
                        val body = listOfNotNull(
                            sharedText?.trim()?.takeIf { it.isNotBlank() },
                            message.trim().takeIf { it.isNotBlank() },
                        ).joinToString("\n\n")
                        repo.send(convo.id, body)
                    }
                    sentCount++
                }
            }
            if (result.isSuccess) {
                stage = ShareStage.SENT
                delay(900)
                onClose()
            } else {
                errorText = "Couldn't send. Try again."
                stage = ShareStage.READY
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Share with",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            ShareSummary(uris.size, attachments, sharedText)
            Spacer(Modifier.height(12.dp))

            when (stage) {
                ShareStage.LOADING -> CenteredNote { CircularProgressIndicator() }
                ShareStage.NOT_SIGNED_IN -> CenteredNote {
                    Text(
                        "You're not signed in.\nOpen Klic and sign in to share.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ShareStage.FAILED -> CenteredNote {
                    Text(
                        errorText ?: "Something went wrong.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    KlicSearchBar(value = query, onValueChange = { query = it })
                    Spacer(Modifier.height(8.dp))
                    val filtered = friends.filter {
                        query.isBlank() ||
                            it.displayName.contains(query, ignoreCase = true) ||
                            it.username.contains(query, ignoreCase = true)
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        items(filtered, key = { it.id }) { friend ->
                            FriendRow(
                                friend = friend,
                                isSelected = friend.id in selected,
                                enabled = stage == ShareStage.READY,
                            ) {
                                selected = if (friend.id in selected) selected - friend.id else selected + friend.id
                            }
                        }
                        if (filtered.isEmpty()) {
                            item {
                                Text(
                                    if (friends.isEmpty()) "No friends yet — add friends in Klic first."
                                    else "No match for \"$query\".",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 16.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = message,
                        onValueChange = { message = it },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = stage == ShareStage.READY,
                        placeholder = { Text("Add a message", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        maxLines = 3,
                        shape = RoundedCornerShape(22.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor      = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor    = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContainerColor     = MaterialTheme.colorScheme.surfaceVariant,
                            focusedIndicatorColor      = Color.Transparent,
                            unfocusedIndicatorColor    = Color.Transparent,
                            disabledIndicatorColor     = Color.Transparent,
                            focusedTextColor           = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor         = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                    errorText?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(12.dp))
                    PillButton(
                        text = when (stage) {
                            ShareStage.SENDING -> "Sending ${(sentCount + 1).coerceAtMost(selected.size)} of ${selected.size}…"
                            ShareStage.SENT -> "Sent"
                            else -> "Send"
                        },
                        enabled = stage == ShareStage.READY && selected.isNotEmpty(),
                    ) { send() }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

/** One line under the title describing what is being shared. */
@Composable
private fun ShareSummary(uriCount: Int, attachments: List<AttachmentInput>, sharedText: String?) {
    val label = when {
        attachments.isNotEmpty() -> {
            val videos = attachments.count { it.kind == "VIDEO" }
            val images = attachments.size - videos
            listOfNotNull(
                images.takeIf { it > 0 }?.let { if (it == 1) "1 photo" else "$it photos" },
                videos.takeIf { it > 0 }?.let { if (it == 1) "1 video" else "$it videos" },
            ).joinToString(", ")
        }
        uriCount > 0 -> if (uriCount == 1) "1 item" else "$uriCount items"
        !sharedText.isNullOrBlank() -> "“${sharedText.take(80)}${if (sharedText.length > 80) "…" else ""}”"
        else -> return
    }
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun CenteredNote(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun FriendRow(friend: User, isSelected: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) { onToggle() }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarView(url = friend.avatarUrl, name = friend.displayName, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            friend.displayName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isSelected) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        } else {
            Icon(
                Icons.Outlined.Circle,
                contentDescription = "Not selected",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
