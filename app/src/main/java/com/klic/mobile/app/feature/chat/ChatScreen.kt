package com.klic.mobile.app.feature.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.klic.mobile.app.data.Attachment
import com.klic.mobile.app.data.Conversation
import com.klic.mobile.app.data.Message
import com.klic.mobile.app.feature.KlicViewModel
import com.klic.mobile.app.feature.chat.actions.ImageViewerOverlay
import com.klic.mobile.app.feature.chat.actions.MessageActionsOverlay
import com.klic.mobile.app.feature.chat.actions.ReplyComposerBar
import com.klic.mobile.app.feature.chat.actions.TypingBubble
import com.klic.mobile.app.feature.chat.composer.AttachSheet
import com.klic.mobile.app.feature.chat.composer.CaptureMode
import com.klic.mobile.app.feature.chat.composer.ComposerBar
import com.klic.mobile.app.feature.chat.composer.MentionCandidate
import com.klic.mobile.app.feature.chat.composer.MentionSuggestionStrip
import com.klic.mobile.app.feature.chat.composer.RecordingBar
import com.klic.mobile.app.feature.chat.composer.insertMention
import com.klic.mobile.app.feature.chat.composer.mentionQueryAt
import com.klic.mobile.app.feature.chat.media.AttachmentDownloads
import com.klic.mobile.app.feature.chat.media.FileDetailSheet
import com.klic.mobile.app.feature.chat.media.PdfViewerOverlay
import com.klic.mobile.app.feature.chat.media.PendingMediaBar
import com.klic.mobile.app.feature.chat.media.PendingMediaDraft
import com.klic.mobile.app.feature.chat.media.UploadProgressPill
import com.klic.mobile.app.feature.chat.media.isPdfAttachment
import com.klic.mobile.app.feature.chat.media.loadFileAttachment
import com.klic.mobile.app.feature.chat.media.loadImageDraft
import com.klic.mobile.app.feature.chat.media.loadMediaDraft
import com.klic.mobile.app.feature.chat.media.loadVideoDraft
import com.klic.mobile.app.feature.chat.messagelist.DateSeparator
import com.klic.mobile.app.feature.chat.messagelist.MessageBubble
import com.klic.mobile.app.feature.chat.messagelist.messagePreview
import com.klic.mobile.app.feature.chat.messagelist.presenceSubtitle
import com.klic.mobile.app.feature.chat.messagelist.sameDay
import com.klic.mobile.app.feature.chat.stickers.StickerPickerSheet
import com.klic.mobile.app.feature.chat.voice.VoiceRecorder
import com.klic.mobile.app.ui.components.AvatarView
import com.klic.mobile.app.ui.components.MessageTicks
import kotlinx.coroutines.launch
import java.io.File

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
    // §10.4: composer drafts persist per conversation (restored here, saved on leave).
    var draft by remember(conversation.id) {
        val saved = com.klic.mobile.app.data.SettingsStore.snapshot.value.drafts[conversation.id].orEmpty()
        mutableStateOf(TextFieldValue(saved, selection = androidx.compose.ui.text.TextRange(saved.length)))
    }
    DisposableEffect(conversation.id) {
        onDispose { vm.saveDraft(conversation.id, draft.text) }
    }
    val peer = conversation.members.firstOrNull()
    val isDirect = conversation.type == "DIRECT"
    val title = when {
        !conversation.title.isNullOrBlank() -> conversation.title
        isDirect -> peer?.displayName ?: "Chat"
        else -> conversation.members.joinToString(", ") { it.displayName }.ifBlank { "Group" }
    }
    val headerSubtitle = when {
        isDirect -> peer?.id?.let { presenceSubtitle(presenceMap[it]) }
        conversation.members.isNotEmpty() -> "${conversation.members.size + 1} members"
        else -> null
    }
    // §9.5: member names highlighted as mentions in bubbles (groups only).
    val mentionableNames = remember(conversation.id, conversation.members, me?.displayName) {
        if (isDirect) emptyList()
        else conversation.members.map { it.displayName } + listOfNotNull(me?.displayName)
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val focusManager = LocalFocusManager.current
    var showAttachSheet by remember { mutableStateOf(false) }
    var showStickerSheet by remember { mutableStateOf(false) }
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }
    var tempVideoUri by remember { mutableStateOf<Uri?>(null) }
    var pendingMedia by remember(conversation.id) { mutableStateOf<List<PendingMediaDraft>>(emptyList()) }
    var captureMode by remember(conversation.id) { mutableStateOf(CaptureMode.AUDIO) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val stickerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val stickers by vm.stickers.collectAsState()

    val typingMap by vm.typing.collectAsState()
    val replyingTo by vm.replyingTo.collectAsState()
    val clipboard = LocalClipboardManager.current
    var menuTarget by remember { mutableStateOf<Message?>(null) }
    var deleteTarget by remember { mutableStateOf<Message?>(null) }
    var viewerUrl by remember { mutableStateOf<String?>(null) }
    // §7.3: FILE attachments are downloaded to cache and viewed in-app, never opened by URL.
    var pdfFile by remember { mutableStateOf<File?>(null) }
    var fileDetail by remember { mutableStateOf<Pair<Attachment, File>?>(null) }
    val peerTyping = isDirect && typingMap[conversation.id]?.let { System.currentTimeMillis() - it < 6000L } == true
    val displaySubtitle = if (peerTyping) "typing…" else headerSubtitle

    // Pagination
    val isLoadingOlder by vm.isLoadingOlderMessages.collectAsState()
    val hasMore by vm.hasMoreMessages.collectAsState()

    val recorder = remember { VoiceRecorder(context) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && !recorder.start()) vm.error.value = "Couldn't start recording."
    }

    // Multi-select photos + videos together, matching iOS's PhotosPicker(.any(of: [.images, .videos])).
    val mediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val drafts = uris.mapNotNull { loadMediaDraft(context, it) }
                if (drafts.isEmpty()) {
                    vm.error.value = "Couldn't read selected media."
                } else {
                    pendingMedia = pendingMedia + drafts
                }
            }
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = tempCameraUri
        tempCameraUri = null
        if (success && uri != null) {
            scope.launch {
                loadImageDraft(context, uri)?.let { draft ->
                    pendingMedia = pendingMedia + draft
                } ?: run {
                    vm.error.value = "Couldn't read captured photo."
                }
            }
        }
    }
    // Video capture from the composer's hold-to-record button (captureMode == VIDEO).
    val videoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        val uri = tempVideoUri
        tempVideoUri = null
        if (success && uri != null) {
            scope.launch {
                loadVideoDraft(context, uri)?.let { draft ->
                    pendingMedia = pendingMedia + draft
                } ?: run {
                    vm.error.value = "Couldn't read captured video."
                }
            }
        }
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { docUri ->
            scope.launch {
                val attachment = loadFileAttachment(context, docUri)
                if (attachment != null) {
                    // Optimistic upload pill (§9.1) — the list stays fully interactive.
                    vm.sendAttachments(conversation.id, null, listOf(attachment))
                } else {
                    vm.error.value = "Couldn't read selected file."
                }
            }
        }
    }

    LaunchedEffect(conversation.id) {
        vm.openChat(conversation.id)
        vm.markRead(conversation.id)
    }

    // §9.7: re-verify the "Ongoing call" banner whenever the app returns to the
    // foreground with this chat open — GET active-call drops it on 404/none.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(conversation.id, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshActiveCall(conversation.id)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Optimistic upload pills for this conversation (§9.1).
    val allUploadTasks by vm.uploadTasks.collectAsState()
    val uploadsHere = allUploadTasks.filter { it.conversationId == conversation.id }

    // Initial open: instant scroll to bottom (no animation).
    // Subsequent new messages: animated scroll.
    // Older messages prepended: no scroll-to-bottom (lastMessageId doesn't change).
    val lastMessageId = messages.lastOrNull()?.id
    var initialScrollDone by remember(conversation.id) { mutableStateOf(false) }

    LaunchedEffect(lastMessageId, peerTyping, uploadsHere.size) {
        val target = messages.size - 1 + (if (peerTyping) 1 else 0) + uploadsHere.size
        if (target >= 0) {
            if (!initialScrollDone) {
                listState.scrollToItem(target)
                initialScrollDone = true
            } else {
                scope.launch { listState.animateScrollToItem(target) }
            }
        }
    }

    // Restore scroll position after older messages are prepended.
    LaunchedEffect(Unit) {
        vm.prependedCount.collect { count ->
            if (count > 0) {
                val newIndex = (listState.firstVisibleItemIndex + count)
                    .coerceAtMost(messages.size - 1)
                listState.scrollToItem(newIndex, listState.firstVisibleItemScrollOffset)
            }
        }
    }

    // Trigger pagination when the user reaches the top.
    val isAtTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset < 10
        }
    }
    LaunchedEffect(isAtTop) {
        if (isAtTop && hasMore && !isLoadingOlder && initialScrollDone) {
            vm.loadOlderMessages()
        }
    }

    // Jump-to-message (§8.4): search results / starred taps land here. Fetch back
    // through history (bounded) until the target is loaded, then scroll to it.
    val pendingJump by vm.pendingJumpMessageId.collectAsState()
    LaunchedEffect(pendingJump, initialScrollDone) {
        val targetId = pendingJump ?: return@LaunchedEffect
        if (!initialScrollDone) return@LaunchedEffect
        var attempts = 0
        while (vm.messages.value.none { it.id == targetId } &&
            vm.hasMoreMessages.value && attempts < 20
        ) {
            vm.loadOlderMessages().join()
            attempts++
        }
        val index = vm.messages.value.indexOfFirst { it.id == targetId }
        if (index >= 0) listState.scrollToItem(index)
        vm.pendingJumpMessageId.value = null
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        // Direct → friend profile; group → GroupInfo (§8.4).
                        modifier = Modifier.clickable(onClick = onOpenProfile),
                    ) {
                        AvatarView(url = peer?.avatarUrl, name = title, size = 34.dp)
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            if (displaySubtitle != null) {
                                Text(
                                    displaySubtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (peerTyping || presenceMap[peer?.id]?.online == true)
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
                    // Groups too: POST /calls rings every conversation member.
                    IconButton(onClick = { vm.startCall(conversation.id, "AUDIO", title); onCall("AUDIO") }) {
                        Icon(Icons.Filled.Call, contentDescription = "Voice call", modifier = Modifier.size(22.dp))
                    }
                    IconButton(onClick = { vm.startCall(conversation.id, "VIDEO", title); onCall("VIDEO") }) {
                        Icon(Icons.Filled.Videocam, contentDescription = "Video call", modifier = Modifier.size(24.dp))
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
            // "Join call" banner: the conversation has a live call we're not part of yet.
            val chatCall by vm.chatActiveCall.collectAsState()
            val ownCall by vm.activeCall.collectAsState()
            chatCall?.let { info ->
                if (info.conversationId == conversation.id && ownCall?.callId != info.callId) {
                    JoinCallBanner(
                        joinedCount = info.joinedCount,
                        isVideo = info.kind == "VIDEO",
                        onJoin = { vm.joinOngoingCall(conversation.id) },
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)
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
                        replyAuthorName = msg.replyTo?.let { if (it.senderId == me?.id) "You" else title } ?: "",
                        highlightMentions = !isDirect,
                        mentionNames = mentionableNames,
                        onCallBack = { kind -> vm.startCall(conversation.id, kind, title); onCall(kind) },
                        onLongPress = { menuTarget = msg },
                        onReactionTap = { emoji -> vm.react(conversation.id, msg.id, emoji) },
                        onImageClick = { url -> viewerUrl = url },
                        onFileClick = { att ->
                            scope.launch {
                                val file = AttachmentDownloads.ensureLocal(context, att, conversation.id)
                                when {
                                    file == null -> vm.error.value = "Couldn't download the file."
                                    isPdfAttachment(att) -> pdfFile = file
                                    else -> fileDetail = att to file
                                }
                            }
                        },
                    )
                }
                if (peerTyping) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.Start) {
                            TypingBubble()
                        }
                    }
                }
                // Optimistic upload pills (§9.1) — each tracks its own progress.
                items(uploadsHere, key = { it.id }) { task ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        UploadProgressPill(
                            task = task,
                            onRetry = { vm.retryUpload(task.id) },
                            onDiscard = { vm.discardUpload(task.id) },
                        )
                    }
                }
            }

            // Loading indicator overlay at the top while fetching older messages.
            if (isLoadingOlder) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // Scroll-to-latest button: shown only when the newest item is off-screen.
            val isAtBottom by remember {
                derivedStateOf {
                    val info = listState.layoutInfo
                    val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                    info.totalItemsCount == 0 || lastVisible >= info.totalItemsCount - 1
                }
            }
            if (!isAtBottom) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 10.dp, bottom = 8.dp)
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                        .clickable {
                            scope.launch {
                                val target = maxOf(0, listState.layoutInfo.totalItemsCount - 1)
                                listState.animateScrollToItem(target)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Scroll to latest",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            } // Box (messages)

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
                if (pendingMedia.isNotEmpty()) {
                    PendingMediaBar(
                        items = pendingMedia,
                        onRemove = { id -> pendingMedia = pendingMedia.filterNot { it.id == id } },
                    )
                }
                replyingTo?.let { target ->
                    ReplyComposerBar(
                        authorName = if (target.senderId == me?.id) "yourself" else title,
                        preview = messagePreview(target),
                        onCancel = { vm.setReplyTo(null) },
                    )
                }
                // §9.5: typing "@" in a group composer offers members + @all above the input.
                val mentionQuery = if (isDirect) null else mentionQueryAt(draft.text, draft.selection.start)
                if (mentionQuery != null) {
                    val candidates = buildList {
                        if ("all".startsWith(mentionQuery.prefix, ignoreCase = true)) {
                            add(MentionCandidate("all", isAll = true))
                        }
                        conversation.members
                            .filter {
                                mentionQuery.prefix.isEmpty() ||
                                    it.displayName.startsWith(mentionQuery.prefix, ignoreCase = true) ||
                                    it.username.startsWith(mentionQuery.prefix, ignoreCase = true)
                            }
                            .forEach { add(MentionCandidate(it.displayName, it.username, it.avatarUrl)) }
                    }
                    if (candidates.isNotEmpty()) {
                        MentionSuggestionStrip(candidates) { pick ->
                            draft = insertMention(draft, mentionQuery, pick.display)
                        }
                    }
                }
                ComposerBar(
                    draft    = draft,
                    onChange = { draft = it; vm.setTyping(conversation.id, it.text.isNotBlank()) },
                    onSend   = {
                        if (pendingMedia.isNotEmpty()) {
                            val toSend = pendingMedia
                            val caption = draft.text.trim().takeIf { it.isNotBlank() }
                            pendingMedia = emptyList()
                            draft = TextFieldValue("")
                            vm.saveDraft(conversation.id, null)
                            // Optimistic pill takes over (§9.1); the composer frees up instantly.
                            vm.sendAttachments(conversation.id, caption, toSend.map { it.attachment })
                        } else if (draft.text.isNotBlank()) {
                            vm.send(conversation.id, draft.text.trim()); draft = TextFieldValue("")
                            vm.saveDraft(conversation.id, null)
                        }
                    },
                    onAttach = { showAttachSheet = true },
                    onStickers = { focusManager.clearFocus(); vm.loadStickers(); showStickerSheet = true },
                    hasPendingAttachments = pendingMedia.isNotEmpty(),
                    captureMode = captureMode,
                    onToggleCaptureMode = {
                        captureMode = if (captureMode == CaptureMode.AUDIO) CaptureMode.VIDEO else CaptureMode.AUDIO
                    },
                    onHoldStart = {
                        when (captureMode) {
                            CaptureMode.AUDIO -> {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                    == PackageManager.PERMISSION_GRANTED
                                ) {
                                    if (!recorder.start()) vm.error.value = "Couldn't start recording."
                                } else {
                                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                            CaptureMode.VIDEO -> {
                                val file = File.createTempFile("klic_vid_", ".mp4", context.cacheDir)
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                tempVideoUri = uri
                                videoLauncher.launch(uri)
                            }
                        }
                    },
                    onHoldEnd = {
                        if (captureMode == CaptureMode.AUDIO && recorder.isRecording) {
                            recorder.stop()?.let { (bytes, durationMs, waveform) ->
                                vm.sendVoice(conversation.id, bytes, durationMs, waveform)
                            }
                        }
                    },
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
                    mediaLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
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

    // Long-press action menu (reactions + reply/copy/delete).
    menuTarget?.let { target ->
        MessageActionsOverlay(
            message = target,
            isMine = target.senderId == me?.id,
            onReact = { emoji -> vm.react(conversation.id, target.id, emoji); menuTarget = null },
            onReply = { vm.setReplyTo(target); menuTarget = null },
            onCopy = { clipboard.setText(AnnotatedString(target.body)) },
            onStar = { vm.toggleStar(target) },
            onDelete = { deleteTarget = target },
            onDismiss = { menuTarget = null },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null; menuTarget = null },
            title = { Text("Delete message") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                Column {
                    if (target.senderId == me?.id) {
                        TextButton(onClick = {
                            vm.deleteForEveryone(conversation.id, target.id); deleteTarget = null; menuTarget = null
                        }) { Text("Delete for everyone", color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(onClick = {
                        vm.deleteForMe(target); deleteTarget = null; menuTarget = null
                    }) { Text("Delete for me", color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null; menuTarget = null }) { Text("Cancel") }
            },
        )
    }

    viewerUrl?.let { url ->
        ImageViewerOverlay(url = url, onDismiss = { viewerUrl = null })
    }

    pdfFile?.let { file ->
        PdfViewerOverlay(file = file, onDismiss = { pdfFile = null })
    }

    fileDetail?.let { (att, file) ->
        FileDetailSheet(att = att, file = file, onDismiss = { fileDetail = null })
    }
    } // Box
}

/** Banner shown while this conversation has an ongoing call the user hasn't joined. */
@Composable
private fun JoinCallBanner(joinedCount: Int, isVideo: Boolean, onJoin: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onJoin).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (isVideo) Icons.Filled.Videocam else Icons.Filled.Call,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Text(
                if (joinedCount > 0) "Ongoing call · $joinedCount in call" else "Ongoing call",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(start = 10.dp).weight(1f),
            )
            Text(
                "Join",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
