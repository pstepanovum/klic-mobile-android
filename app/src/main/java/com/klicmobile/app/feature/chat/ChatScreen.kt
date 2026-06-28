package com.klicmobile.app.feature.chat

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.klicmobile.app.data.Attachment
import com.klicmobile.app.data.Conversation
import com.klicmobile.app.data.Message
import com.klicmobile.app.feature.KlicViewModel
import com.klicmobile.app.ui.components.AvatarView
import com.klicmobile.app.ui.theme.KlicIcons
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: KlicViewModel,
    conversation: Conversation,
    onBack: () -> Unit,
    onCall: (String) -> Unit,
    onOpenProfile: () -> Unit = {},
) {
    val messages by vm.messages.collectAsState()
    val me by vm.currentUser.collectAsState()
    val presenceMap by vm.presence.collectAsState()
    var draft by remember { mutableStateOf("") }
    val peer = conversation.members.firstOrNull()
    val title = peer?.displayName ?: "Chat"
    val headerSubtitle = peer?.id?.let { presenceSubtitle(presenceMap[it]) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val focusManager = LocalFocusManager.current
    var showAttachSheet by remember { mutableStateOf(false) }
    var showStickerSheet by remember { mutableStateOf(false) }
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val stickerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val stickers by vm.stickers.collectAsState()

    val recorder = remember { VoiceRecorder(context) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) recorder.start()
    }

    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { /* TODO: upload via vm */ }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) tempCameraUri?.let { /* TODO: upload via vm */ }
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { /* TODO: upload via vm */ }
    }

    LaunchedEffect(conversation.id) {
        vm.openChat(conversation.id)
        vm.markRead(conversation.id)
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            scope.launch { listState.animateScrollToItem(messages.size - 1) }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(onClick = onOpenProfile),
                    ) {
                        AvatarView(url = peer?.avatarUrl, name = title, size = 34.dp)
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            if (headerSubtitle != null) {
                                Text(
                                    headerSubtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (presenceMap[peer?.id]?.online == true)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.startCall(conversation.id, "AUDIO", title); onCall("AUDIO") }) {
                        Icon(painterResource(KlicIcons.phone), contentDescription = "Voice call", modifier = Modifier.size(22.dp))
                    }
                    IconButton(onClick = { vm.startCall(conversation.id, "VIDEO", title); onCall("VIDEO") }) {
                        Icon(painterResource(KlicIcons.video), contentDescription = "Video call", modifier = Modifier.size(22.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
        Column(
            Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .fillMaxHeight(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                    .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(messages.indices.toList()) { idx ->
                    val msg = messages[idx]
                    val isMine = msg.senderId == me?.id
                    val isFirst = idx == 0 || messages[idx - 1].senderId != msg.senderId
                    val isLast  = idx == messages.size - 1 || messages[idx + 1].senderId != msg.senderId

                    if (idx == 0 || !sameDay(messages[idx - 1].createdAt, msg.createdAt)) {
                        DateSeparator(msg.createdAt)
                    }
                    MessageBubble(
                        message = msg,
                        isMine  = isMine,
                        isFirst = isFirst,
                        isLast  = isLast,
                        onCallBack = { kind -> vm.startCall(conversation.id, kind, title); onCall(kind) },
                    )
                }
            }

            if (recorder.isRecording) {
                RecordingBar(
                    elapsed  = recorder.elapsed,
                    onCancel = { recorder.cancel() },
                    onSend   = {
                        recorder.stop()?.let { (bytes, durationMs, waveform) ->
                            vm.sendVoice(conversation.id, bytes, durationMs, waveform)
                        }
                    },
                )
            } else {
                ComposerBar(
                    draft    = draft,
                    onChange = { draft = it },
                    onSend   = {
                        if (draft.isNotBlank()) { vm.send(conversation.id, draft.trim()); draft = "" }
                    },
                    onAttach = { showAttachSheet = true },
                    onStickers = { focusManager.clearFocus(); vm.loadStickers(); showStickerSheet = true },
                    onMic    = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                )
            }
        }
        } // Box
    }

    if (showAttachSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAttachSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            AttachSheet(
                onPhotos = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { showAttachSheet = false }
                    imageLauncher.launch("image/*")
                },
                onCamera = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { showAttachSheet = false }
                    val file = File.createTempFile("klic_", ".jpg", context.cacheDir)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    tempCameraUri = uri
                    cameraLauncher.launch(uri)
                },
                onFile = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { showAttachSheet = false }
                    fileLauncher.launch(arrayOf("*/*"))
                },
            )
        }
    }

    if (showStickerSheet) {
        ModalBottomSheet(
            onDismissRequest = { showStickerSheet = false },
            sheetState = stickerSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            StickerPickerSheet(stickers = stickers) { id ->
                vm.sendSticker(conversation.id, id)
                scope.launch { stickerSheetState.hide() }.invokeOnCompletion { showStickerSheet = false }
            }
        }
    }
}

// MARK: - Message bubble

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: Message,
    isMine: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onCallBack: (String) -> Unit = {},
) {
    if (message.isCallEvent && message.call != null) {
        CallEventBubble(message.call, outgoing = isMine, time = shortTime(message.createdAt), onCallBack = onCallBack)
        return
    }
    if (message.isSticker) {
        StickerBubble(message, isMine = isMine, time = if (isLast) shortTime(message.createdAt) else null)
        return
    }

    val tailRadius = 4.dp
    val fullRadius = 18.dp
    val clipboard = LocalClipboardManager.current

    val shape = RoundedCornerShape(
        topStart     = if (!isMine && isFirst) fullRadius else if (!isMine) 4.dp else fullRadius,
        topEnd       = if (isMine  && isFirst) fullRadius else if (isMine)  4.dp else fullRadius,
        bottomEnd    = if (isMine  && isLast)  tailRadius else fullRadius,
        bottomStart  = if (!isMine && isLast)  tailRadius else fullRadius,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
    ) {
        val voiceAtt = message.attachments.firstOrNull { it.kind == "VOICE" }
        if (voiceAtt != null) {
            VoiceAttachmentView(att = voiceAtt, isMine = isMine)
        } else {
            Box(
                Modifier
                    .widthIn(max = 280.dp)
                    .background(
                        if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        shape,
                    )
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            if (message.body.isNotBlank()) clipboard.setText(AnnotatedString(message.body))
                        },
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    message.body,
                    color = if (isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        if (isLast) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    shortTime(message.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isMine && message.status != null) {
                    MessageTicks(message.status)
                }
            }
        }
    }
}

@Composable
private fun MessageTicks(status: String) {
    val icon = if (status == "sent") Icons.Filled.Check else Icons.Filled.DoneAll
    val tint = if (status == "read") MaterialTheme.colorScheme.primary
               else MaterialTheme.colorScheme.onSurfaceVariant
    Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
}

// Presence subtitle for the chat header: "Online" or "last seen …".
private fun presenceSubtitle(presence: com.klicmobile.app.realtime.SocketService.Presence?): String? {
    if (presence == null) return null
    if (presence.online) return "Online"
    val ms = presence.lastSeenMs ?: return null
    val date = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val time = DateTimeFormatter.ofPattern("HH:mm").format(date)
    return when (date.toLocalDate()) {
        LocalDate.now() -> "last seen $time"
        LocalDate.now().minusDays(1) -> "last seen yesterday"
        else -> "last seen ${DateTimeFormatter.ofPattern("MMM d").format(date)}"
    }
}

// MARK: - Date separator

@Composable
private fun DateSeparator(isoDate: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.surfaceVariant))
        Text(
            dateLabel(isoDate),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

// MARK: - Attach sheet

@Composable
private fun AttachSheet(onPhotos: () -> Unit, onCamera: () -> Unit, onFile: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .navigationBarsPadding()
            .padding(bottom = 32.dp),
    ) {
        Text(
            "Attach",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(bottom = 24.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            AttachTile(
                iconRes = KlicIcons.gallery,
                label = "Photos",
                color = Color(0xFF3B82F6),
                onClick = onPhotos,
                modifier = Modifier.weight(1f),
            )
            AttachTile(
                iconRes = KlicIcons.camera,
                label = "Camera",
                color = Color(0xFF22C55E),
                onClick = onCamera,
                modifier = Modifier.weight(1f),
            )
            AttachTile(
                iconRes = KlicIcons.document,
                label = "File",
                color = Color(0xFFF97316),
                onClick = onFile,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AttachTile(iconRes: Int, label: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .background(color, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = label,
                modifier = Modifier.size(30.dp),
                tint = Color.White,
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// MARK: - Composer

@Composable
private fun ComposerBar(
    draft: String,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onStickers: () -> Unit,
    onMic: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(200)
        runCatching { focusRequester.requestFocus() }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconButton(
            onClick = onAttach,
            modifier = Modifier.size(44.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor   = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(
                painter = painterResource(KlicIcons.add),
                contentDescription = "Attach",
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(
            onClick = onStickers,
            modifier = Modifier.size(44.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor   = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(
                imageVector = Icons.Filled.EmojiEmotions,
                contentDescription = "Stickers",
                modifier = Modifier.size(22.dp),
            )
        }
        TextField(
            value = draft,
            onValueChange = onChange,
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            placeholder = { Text("Message", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            maxLines = 4,
            shape = RoundedCornerShape(22.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor      = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor    = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor      = Color.Transparent,
                unfocusedIndicatorColor    = Color.Transparent,
                disabledIndicatorColor     = Color.Transparent,
                focusedTextColor           = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor         = MaterialTheme.colorScheme.onSurface,
            ),
        )

        val canSend = draft.isNotBlank()
        IconButton(
            onClick = if (canSend) onSend else onMic,
            modifier = Modifier.size(44.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor   = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(
                imageVector = if (canSend) Icons.Filled.Send else Icons.Filled.Mic,
                contentDescription = if (canSend) "Send" else "Record",
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun RecordingBar(elapsed: Float, onCancel: () -> Unit, onSend: () -> Unit) {
    val s = elapsed.toInt()
    val timeText = "%d:%02d".format(s / 60, s % 60)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconButton(
            onClick = onCancel,
            modifier = Modifier.size(44.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = Color.Transparent,
                contentColor   = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(Icons.Filled.Delete, contentDescription = "Cancel recording", modifier = Modifier.size(22.dp))
        }
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(Color.Red, CircleShape)
        )
        Text(
            timeText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "Recording…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(
            onClick = onSend,
            modifier = Modifier.size(44.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor   = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(Icons.Filled.Send, contentDescription = "Send voice", modifier = Modifier.size(20.dp))
        }
    }
}

// MARK: - Helpers

private fun sameDay(a: String, b: String): Boolean = a.take(10) == b.take(10)

private fun shortTime(iso: String): String = runCatching {
    val instant = Instant.parse(iso)
    DateTimeFormatter.ofPattern("HH:mm").format(instant.atZone(ZoneId.systemDefault()))
}.getOrDefault("")

private fun dateLabel(iso: String): String = runCatching {
    val date = Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    when (date) {
        today            -> "Today"
        today.minusDays(1) -> "Yesterday"
        else             -> DateTimeFormatter.ofPattern("MMMM d").format(date)
    }
}.getOrDefault("")
