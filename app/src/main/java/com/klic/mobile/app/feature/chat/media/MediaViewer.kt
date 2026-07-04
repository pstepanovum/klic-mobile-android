package com.klic.mobile.app.feature.chat.media

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.klic.mobile.app.R
import com.klic.mobile.app.data.Attachment
import com.klic.mobile.app.data.AttachmentInput
import com.klic.mobile.app.data.Conversation
import com.klic.mobile.app.data.Message
import com.klic.mobile.app.feature.KlicViewModel
import com.klic.mobile.app.feature.chat.voice.durationText
import com.klic.mobile.app.ui.components.AvatarView
import com.klic.mobile.app.ui.components.rememberStableImageRequest
import kotlinx.coroutines.launch

/**
 * Full-screen media viewer (§10.9). Type detection keys STRICTLY on the attachment
 * kind — images never get a Play overlay. Single tap toggles immersive chrome; the
 * footer actions (Share / Forward / Star / Reply) all work and are Klic-styled.
 * §13.17: bento messages open at the tapped attachment and page horizontally through
 * ALL of the message's media (images + videos) with a position chip up top.
 */
@Composable
fun MediaViewerOverlay(
    vm: KlicViewModel,
    message: Message,
    att: Attachment,
    conversationId: String,
    onReply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var chrome by remember { mutableStateOf(true) }
    var showForward by remember { mutableStateOf(false) }

    // §13.17: all pageable media in this message, starting on the tapped one.
    val mediaAtts = remember(message.id) {
        message.attachments.filter { it.kind == "IMAGE" || it.kind == "VIDEO" }
            .ifEmpty { listOf(att) }
    }
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = mediaAtts.indexOfFirst { it.id == att.id }.coerceAtLeast(0),
    ) { mediaAtts.size }
    val current = mediaAtts[pagerState.currentPage.coerceIn(mediaAtts.indices)]
    val isVideo = current.kind == "VIDEO"

    // Live star state — follows the message in the open list.
    val messages by vm.messages.collectAsState()
    val starred = messages.firstOrNull { it.id == message.id }?.starred ?: message.starred

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val pageAtt = mediaAtts[page]
            if (pageAtt.kind == "VIDEO") {
                // Only the settled page owns a playing player — neighbors stay idle.
                VideoSurface(
                    att = pageAtt,
                    autoPlay = page == pagerState.settledPage,
                    onChromeChange = { chrome = it },
                )
            } else {
                ZoomableImage(att = pageAtt, onTap = { chrome = !chrome })
            }
        }

        // Top bar (fades in immersive mode).
        AnimatedVisibility(
            visible = chrome,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(com.klic.mobile.app.ui.theme.KlicIcons.close),
                        contentDescription = stringResource(R.string.common_close),
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(26.dp),
                    )
                }
                // §10.9: duration chip top-left for videos (after the back affordance).
                if (isVideo && current.durationMs != null) {
                    Box(
                        Modifier
                            .background(Color.White.copy(alpha = 0.16f), CircleShape)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            durationText(current.durationMs!!),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                    }
                }
                // §13.17: "n / N" position chip for multi-media messages.
                if (mediaAtts.size > 1) {
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .background(Color.White.copy(alpha = 0.16f), CircleShape)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            "${pagerState.currentPage + 1} / ${mediaAtts.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                    }
                    Spacer(Modifier.width(44.dp))
                }
            }
        }

        // Footer actions.
        AnimatedVisibility(
            visible = chrome,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .navigationBarsPadding()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ViewerAction(Icons.Filled.Share, stringResource(R.string.viewer_share)) {
                    scope.launch {
                        val file = AttachmentDownloads.ensureLocal(context, current, conversationId)
                        if (file != null) shareFile(context, file, current.contentType)
                        else Toast.makeText(context, context.getString(R.string.viewer_share_failed), Toast.LENGTH_SHORT).show()
                    }
                }
                ViewerAction(Icons.AutoMirrored.Filled.Send, stringResource(R.string.viewer_forward)) {
                    showForward = true
                }
                ViewerAction(
                    if (starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                    if (starred) stringResource(R.string.viewer_unstar) else stringResource(R.string.viewer_star),
                ) {
                    vm.toggleStar(messages.firstOrNull { it.id == message.id } ?: message)
                }
                ViewerAction(Icons.AutoMirrored.Filled.Reply, stringResource(R.string.viewer_reply)) {
                    vm.setReplyTo(message)
                    onReply()
                    onDismiss()
                }
            }
        }
    }

    if (showForward) {
        ForwardPickerSheet(
            vm = vm,
            onDismiss = { showForward = false },
            onSend = { targets ->
                showForward = false
                scope.launch {
                    val file = AttachmentDownloads.ensureLocal(context, current, conversationId)
                    val bytes = file?.readBytes()
                    if (bytes == null) {
                        Toast.makeText(context, context.getString(R.string.viewer_forward_failed), Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val input = AttachmentInput(
                        key = "",
                        kind = current.kind,
                        contentType = current.contentType,
                        byteSize = bytes.size,
                        width = current.width,
                        height = current.height,
                        durationMs = current.durationMs,
                        fileName = current.fileName,
                        localBytes = bytes,
                    )
                    targets.forEach { convId -> vm.sendAttachments(convId, null, listOf(input)) }
                    Toast.makeText(context, context.getString(R.string.viewer_forwarded), Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}

@Composable
private fun ZoomableImage(att: Attachment, onTap: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // §13.17: consume only pinches (2+ pointers) and single-finger pans
                // while zoomed — an unzoomed one-finger swipe falls through to the
                // pager so multi-media messages page naturally.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2 || scale > 1f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zoom != 1f || pan != Offset.Zero) {
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offset = if (scale > 1f) offset + pan else Offset.Zero
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = rememberStableImageRequest(att.url),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                },
        )
    }
}

@Composable
private fun VideoSurface(att: Attachment, autoPlay: Boolean = true, onChromeChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val player = remember(att.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(att.url))
            prepare()
        }
    }
    // §13.17: only the pager's settled page plays; swiping away pauses it.
    LaunchedEffect(autoPlay) { player.playWhenReady = autoPlay }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                // §10.9: our top/footer chrome follows the player controls — one tap
                // hides both (immersive), the next brings both back.
                setControllerVisibilityListener(
                    PlayerView.ControllerVisibilityListener { visibility ->
                        onChromeChange(visibility == View.VISIBLE)
                    },
                )
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun ViewerAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() },
            onClick = onClick,
        ),
    ) {
        Box(
            Modifier.size(44.dp).background(Color.White.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f))
    }
}

private fun shareFile(context: Context, file: java.io.File, contentType: String) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = contentType.ifBlank { "*/*" }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }
}

// ─────────────────────────────────────────────────────────
// Forward picker (§10.9) — multi-select chats + Frequent row (§10.4)
// ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardPickerSheet(
    vm: KlicViewModel,
    onDismiss: () -> Unit,
    onSend: (List<String>) -> Unit,
) {
    val conversations by vm.conversations.collectAsState()
    var selected by remember { mutableStateOf(setOf<String>()) }

    // §10.4: "Frequent" — most-messaged friends' DMs on top, when enabled.
    val frequentIds = remember(conversations) { vm.frequentFriendIds() }
    val frequent = remember(conversations, frequentIds) {
        frequentIds.mapNotNull { friendId ->
            conversations.firstOrNull { c -> c.type == "DIRECT" && c.members.firstOrNull()?.id == friendId }
        }
    }
    val rest = remember(conversations, frequent) {
        conversations.filterNot { c -> frequent.any { it.id == c.id } }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.viewer_forward_to),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 4.dp, bottom = 12.dp),
            )
            LazyColumn(Modifier.weight(1f, fill = false)) {
                if (frequent.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.picker_frequent).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                        )
                    }
                    items(frequent, key = { "f-${it.id}" }) { convo ->
                        ForwardRow(convo, convo.id in selected) {
                            selected = if (convo.id in selected) selected - convo.id else selected + convo.id
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
                items(rest, key = { it.id }) { convo ->
                    ForwardRow(convo, convo.id in selected) {
                        selected = if (convo.id in selected) selected - convo.id else selected + convo.id
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onSend(selected.toList()) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape,
            ) {
                Text(
                    if (selected.isEmpty()) stringResource(R.string.common_send)
                    else stringResource(R.string.picker_send_count, selected.size),
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ForwardRow(convo: Conversation, selected: Boolean, onToggle: () -> Unit) {
    val title = when {
        !convo.title.isNullOrBlank() -> convo.title
        convo.type == "DIRECT" -> convo.members.firstOrNull()?.displayName ?: "Chat"
        else -> convo.members.joinToString(", ") { it.displayName }.ifBlank { "Group" }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .clickable(onClick = onToggle)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarView(url = convo.avatarUrl ?: convo.members.firstOrNull()?.avatarUrl, name = title, size = 40.dp)
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        Box(
            Modifier
                .size(22.dp)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Text("✓", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
