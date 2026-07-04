package com.klic.mobile.app.feature.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.klic.mobile.app.R
import com.klic.mobile.app.data.Message
import com.klic.mobile.app.feature.KlicViewModel
import com.klic.mobile.app.feature.chat.media.rememberVideoThumbnail
import com.klic.mobile.app.feature.chat.messagelist.messagePreview
import com.klic.mobile.app.ui.components.rememberStableImageRequest
import com.klic.mobile.app.ui.theme.KlicIcons
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * §14.4: Settings → "Saved messages" — everything the user starred across ALL
 * conversations (GET /me/starred), rendered like a message list: sender, the
 * conversation context line, timestamp and proper media previews. Tapping an entry
 * jumps to the message in its conversation; long-press unstars it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SavedMessagesContent(
    vm: KlicViewModel,
    onOpenMessage: (conversationId: String, messageId: String) -> Unit,
) {
    val conversations by vm.conversations.collectAsState()
    var items by remember { mutableStateOf(vm.cachedStarred(null)?.items ?: emptyList()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var exhausted by remember { mutableStateOf(false) }
    var unstarTarget by remember { mutableStateOf<Message?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun loadMore() {
        loading = true
        val firstPage = cursor == null
        val page = vm.fetchStarred(conversationId = null, cursor = cursor)
        if (page == null) { exhausted = true; loading = false; return }
        items = if (firstPage) page.items else items + page.items
        cursor = page.nextCursor
        exhausted = page.nextCursor == null
        loading = false
    }

    LaunchedEffect(Unit) { loadMore() }

    fun conversationLabel(conversationId: String): String {
        val convo = conversations.firstOrNull { it.id == conversationId } ?: return ""
        return when {
            !convo.title.isNullOrBlank() -> convo.title
            convo.type == "DIRECT" -> convo.members.firstOrNull()?.displayName ?: ""
            else -> convo.members.joinToString(", ") { it.displayName }
        }
    }

    if (items.isEmpty() && !loading) {
        Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painter = painterResource(KlicIcons.star),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.saved_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)),
    ) {
        items.forEachIndexed { index, msg ->
            SavedMessageRow(
                message = msg,
                senderName = vm.displayNameFor(msg.senderId)
                    ?: stringResource(R.string.call_member),
                conversationLabel = conversationLabel(msg.conversationId),
                onClick = { onOpenMessage(msg.conversationId, msg.id) },
                onLongClick = { unstarTarget = msg },
            )
            if (index != items.lastIndex) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            }
        }
        if (!exhausted) {
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = { scope.launch { loadMore() } }) {
                        Text(stringResource(R.string.common_more))
                    }
                }
            }
        }
    }

    // Long-press → confirm unstar, then drop the row (optimistic via toggleStar).
    unstarTarget?.let { msg ->
        AlertDialog(
            onDismissRequest = { unstarTarget = null },
            title = { Text(stringResource(R.string.saved_unstar_title)) },
            text = { Text(stringResource(R.string.saved_unstar_body)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.toggleStar(msg.copy(starred = true))
                    items = items.filterNot { it.id == msg.id }
                    unstarTarget = null
                }) { Text(stringResource(R.string.viewer_unstar), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { unstarTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedMessageRow(
    message: Message,
    senderName: String,
    conversationLabel: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Media preview (image/video first frame) when the message carries one.
        val mediaAtt = message.attachments.firstOrNull { it.kind == "IMAGE" || it.kind == "VIDEO" }
        if (mediaAtt != null) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (mediaAtt.kind == "VIDEO") {
                    val thumb by rememberVideoThumbnail(mediaAtt, message.conversationId)
                    thumb?.let {
                        androidx.compose.foundation.Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .size(18.dp)
                            .align(Alignment.Center),
                    )
                } else {
                    AsyncImage(
                        model = rememberStableImageRequest(mediaAtt.url),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    senderName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (conversationLabel.isNotBlank()) {
                    Text(
                        "  ·  $conversationLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    savedDate(message.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                messagePreview(message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            painter = painterResource(KlicIcons.starBold),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 10.dp).size(14.dp),
        )
    }
}

private fun savedDate(iso: String): String = runCatching {
    DateTimeFormatter.ofPattern("MMM d")
        .format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
}.getOrDefault("")
