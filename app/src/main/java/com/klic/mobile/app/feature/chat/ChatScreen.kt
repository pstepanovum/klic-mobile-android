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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
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
import com.klic.mobile.app.feature.chat.actions.MessageActionsOverlay
import androidx.compose.ui.focus.FocusRequester
import com.klic.mobile.app.feature.chat.actions.TypingBubble
import com.klic.mobile.app.feature.chat.composer.CaptureMode
import com.klic.mobile.app.feature.chat.composer.KlicAttachmentSheet
import com.klic.mobile.app.feature.chat.composer.KlicCameraCapture
import com.klic.mobile.app.feature.chat.composer.ComposerBar
import com.klic.mobile.app.feature.chat.composer.MentionCandidate
import com.klic.mobile.app.feature.chat.composer.MentionSuggestionStrip
import com.klic.mobile.app.feature.chat.composer.RecordDragResult
import com.klic.mobile.app.feature.chat.composer.RecordPhase
import com.klic.mobile.app.feature.chat.composer.CANCEL_MAX_TRAVEL
import com.klic.mobile.app.feature.chat.composer.CANCEL_WIDTH_FRACTION
import com.klic.mobile.app.feature.chat.composer.LOCK_TRAVEL
import com.klic.mobile.app.feature.chat.composer.RELEASE_CANCEL_PROGRESS
import com.klic.mobile.app.feature.chat.composer.insertMention
import com.klic.mobile.app.feature.chat.composer.mentionQueryAt
import com.klic.mobile.app.feature.chat.videonote.VideoNoteRecordingOverlay
import com.klic.mobile.app.feature.chat.videonote.VideoNoteSession
import com.klic.mobile.app.feature.chat.media.AttachmentDownloads
import com.klic.mobile.app.feature.chat.media.FileDetailSheet
import com.klic.mobile.app.feature.chat.media.MediaEditorDialog
import com.klic.mobile.app.feature.chat.media.MediaViewerOverlay
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
import com.klic.mobile.app.feature.chat.messagelist.SwipeToReplyContainer
import com.klic.mobile.app.feature.chat.stickers.StickerPickerSheet
import com.klic.mobile.app.feature.chat.voice.VoiceRecorder
import com.klic.mobile.app.ui.components.AvatarView
import com.klic.mobile.app.ui.components.MessageTicks
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.ui.res.stringResource
import com.klic.mobile.app.R

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
    // §11.2: in-app camera capture (photo + video) opened from the attach sheet's tile.
    var showCamera by remember { mutableStateOf(false) }
    var showStickerSheet by remember { mutableStateOf(false) }
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }
    var pendingMedia by remember(conversation.id) { mutableStateOf<List<PendingMediaDraft>>(emptyList()) }
    // §16.2: mic ↔ round-video mode persists per app session (VM-scoped).
    val captureMode by vm.captureMode.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val stickerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val stickers by vm.stickers.collectAsState()

    val typingMap by vm.typing.collectAsState()
    val replyingTo by vm.replyingTo.collectAsState()
    // §15.3: starting a reply (swipe or long-press) focuses the composer input.
    val composerFocus = remember { FocusRequester() }
    LaunchedEffect(replyingTo) {
        if (replyingTo != null) runCatching { composerFocus.requestFocus() }
    }
    val clipboard = LocalClipboardManager.current
    var menuTarget by remember { mutableStateOf<Message?>(null) }
    var deleteTarget by remember { mutableStateOf<Message?>(null) }
    // §12.1: message picked for reporting from the long-press menu.
    var reportMessageTarget by remember { mutableStateOf<Message?>(null) }
    var viewerTarget by remember { mutableStateOf<Pair<Message, Attachment>?>(null) }
    // §10.9 pre-send editor target (staged draft id).
    var editorTarget by remember { mutableStateOf<PendingMediaDraft?>(null) }
    // §7.3: FILE attachments are downloaded to cache and viewed in-app, never opened by URL.
    var pdfFile by remember { mutableStateOf<File?>(null) }
    var fileDetail by remember { mutableStateOf<Pair<Attachment, File>?>(null) }
    val peerTyping = isDirect && typingMap[conversation.id]?.let { System.currentTimeMillis() - it < 6000L } == true
    val displaySubtitle = if (peerTyping) stringResource(R.string.chat_typing) else headerSubtitle

    // Pagination
    val isLoadingOlder by vm.isLoadingOlderMessages.collectAsState()
    val hasMore by vm.hasMoreMessages.collectAsState()

    // ── §16.2: hold-to-record (audio + round video) with the lock system ──────
    val recorder = remember { VoiceRecorder(context) }
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    var recordPhase by remember { mutableStateOf(RecordPhase.IDLE) }
    var videoSession by remember { mutableStateOf<VideoNoteSession?>(null) }
    var lastRecordDrag by remember { mutableStateOf(Offset.Zero) }
    val cancelDistPx = with(density) {
        minOf(configuration.screenWidthDp.dp * CANCEL_WIDTH_FRACTION, CANCEL_MAX_TRAVEL).toPx()
    }
    val lockTravelPx = with(density) { LOCK_TRAVEL.toPx() }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) vm.error.value = context.getString(R.string.err_start_recording)
    }
    val videoNotePermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.CAMERA] != true) {
            vm.error.value = context.getString(R.string.err_video_note)
        }
    }

    /** §16.2: send the finished round-video file as a VIDEO_NOTE message. */
    fun sendVideoNote(file: File, durationMs: Int) {
        scope.launch {
            val meta = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    val w = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                    val h = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                    w to h
                } catch (e: Exception) {
                    null to null
                } finally {
                    runCatching { retriever.release() }
                }
            }
            vm.sendAttachments(
                conversation.id, null,
                listOf(
                    com.klic.mobile.app.data.AttachmentInput(
                        key = "",
                        kind = "VIDEO_NOTE",
                        contentType = "video/mp4",
                        byteSize = file.length().toInt(),
                        width = meta.first,
                        height = meta.second,
                        durationMs = durationMs,
                        localUri = Uri.fromFile(file).toString(),
                    )
                ),
            )
        }
    }

    fun cancelRecording() {
        if (captureMode == CaptureMode.AUDIO) recorder.cancel()
        else { videoSession?.cancel(); videoSession = null }
        recordPhase = RecordPhase.IDLE
    }

    fun finishAndSendRecording() {
        if (recordPhase == RecordPhase.IDLE) return
        recordPhase = RecordPhase.IDLE
        if (captureMode == CaptureMode.AUDIO) {
            recorder.stop()?.let { (bytes, durationMs, waveform) ->
                vm.sendVoice(conversation.id, bytes, durationMs, waveform)
            }
        } else {
            val session = videoSession
            videoSession = null
            session?.stop { file, durationMs ->
                if (file != null && durationMs >= 700) sendVideoNote(file, durationMs)
                else file?.delete()
            }
        }
    }

    fun startRecording() {
        lastRecordDrag = Offset.Zero
        when (captureMode) {
            CaptureMode.AUDIO -> {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    if (recorder.start()) recordPhase = RecordPhase.HELD
                    else vm.error.value = context.getString(R.string.err_start_recording)
                } else {
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            CaptureMode.VIDEO -> {
                val cameraOk = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                val micOk = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (cameraOk && micOk) {
                    videoSession = VideoNoteSession(context)
                    recordPhase = RecordPhase.HELD
                } else {
                    videoNotePermissions.launch(
                        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                    )
                }
            }
        }
    }

    // §16.2: auto-lock at 59s (so the 60s cap never rips a held button away), then
    // auto-stop + hand off to send at the 60s hard cap.
    val recordElapsed = if (captureMode == CaptureMode.VIDEO) videoSession?.elapsed ?: 0f else recorder.elapsed
    LaunchedEffect(recordPhase) {
        while (recordPhase != RecordPhase.IDLE) {
            val elapsed = if (vm.captureMode.value == CaptureMode.VIDEO) {
                videoSession?.elapsed ?: 0f
            } else {
                recorder.elapsed
            }
            if (recordPhase == RecordPhase.HELD && elapsed >= 59f) {
                recordPhase = RecordPhase.LOCKED
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            if (elapsed >= 60f) {
                finishAndSendRecording()
                break
            }
            delay(100)
        }
    }

    // ── §16.4: edit mode — original body in the field, previous draft restored ──
    val editingMessage by vm.editing.collectAsState()
    var editBackupDraft by remember { mutableStateOf<TextFieldValue?>(null) }
    var shakeTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(editingMessage?.id) {
        val target = editingMessage
        if (target != null) {
            editBackupDraft = draft
            draft = TextFieldValue(target.body, selection = androidx.compose.ui.text.TextRange(target.body.length))
            runCatching { composerFocus.requestFocus() }
        }
    }

    fun exitEditMode() {
        vm.setEditing(null)
        draft = editBackupDraft ?: TextFieldValue("")
        editBackupDraft = null
    }

    // ── §16.1/§16.3: jump-to-original + highlight flash ───────────────────────
    var highlightedId by remember { mutableStateOf<String?>(null) }

    fun jumpToMessage(messageId: String) {
        scope.launch {
            var attempts = 0
            while (vm.messages.value.none { it.id == messageId } &&
                vm.hasMoreMessages.value && attempts < 20
            ) {
                vm.loadOlderMessages().join()
                attempts++
            }
            val index = vm.messages.value.indexOfFirst { it.id == messageId }
            if (index >= 0) {
                listState.animateScrollToItem(index)
                highlightedId = messageId
                delay(900)
                if (highlightedId == messageId) highlightedId = null
            }
        }
    }

    // ── §16.3: pins ───────────────────────────────────────────────────────────
    val pinnedMessages by vm.pinnedMessages.collectAsState()
    val pinBarHiddenAt by vm.pinBarHiddenAt.collectAsState()
    var pinStep by remember(conversation.id) { mutableIntStateOf(0) }
    var pinTarget by remember { mutableStateOf<Message?>(null) }
    var unpinTargetId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pinnedMessages.size) { pinStep = 0 }
    val canPinHere = isDirect || conversation.isAdmin

    // Multi-select photos + videos together, matching iOS's PhotosPicker(.any(of: [.images, .videos])).
    val mediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val drafts = uris.mapNotNull { loadMediaDraft(context, it) }
                if (drafts.isEmpty()) {
                    vm.error.value = context.getString(R.string.err_read_media)
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
                    vm.error.value = context.getString(R.string.err_read_photo)
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
                    vm.error.value = context.getString(R.string.err_read_file)
                }
            }
        }
    }

    LaunchedEffect(conversation.id) {
        vm.openChat(conversation.id)
        vm.markRead(conversation.id)
        // §16.6: blocked-by-me state gates the composer (blocks list is cheap + cached).
        if (isDirect) vm.loadBlocks()
    }

    // §16.6: when the DM peer is blocked BY ME the composer swaps for a banner.
    val blockedUsers by vm.blockedUsers.collectAsState()
    val peerBlockedByMe = isDirect && peer != null && blockedUsers.any { it.user.id == peer.id }

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

    // §12.3: chat theme — bubble color becomes `primary` inside, and the scaffold body
    // turns transparent over the background → gradient → pattern layer stack.
    // §14.3 precedence: group theme (server) > per-chat local override > global.
    val globalTheme by com.klic.mobile.app.data.ChatThemeStore.snapshot.collectAsState()
    val themeOverrides by com.klic.mobile.app.data.ChatThemeStore.overrides.collectAsState()
    val chatTheme = remember(globalTheme, themeOverrides, conversation.id, conversation.theme) {
        com.klic.mobile.app.data.ChatThemeStore.resolve(
            globalTheme, themeOverrides[conversation.id], conversation.theme,
        )
    }
    com.klic.mobile.app.ui.components.ChatBubbleTheme(chatTheme) {
    Box(Modifier.fillMaxSize()) {
    // §13.4: the background stack is anchored to the SCREEN, not the keyboard-adjusted
    // content area — measure it at the full window height (the incoming constraints
    // shrink when the IME opens) and pin it to the top, so only messages/composer move.
    val hostView = androidx.compose.ui.platform.LocalView.current
    com.klic.mobile.app.ui.components.ChatThemeLayers(
        theme = chatTheme,
        modifier = Modifier
            .matchParentSize()
            .layout { measurable, constraints ->
                val fullHeight = maxOf(constraints.maxHeight, hostView.rootView.height)
                val placeable = measurable.measure(
                    constraints.copy(minHeight = fullHeight, maxHeight = fullHeight),
                )
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
            },
    )
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
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
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(com.klic.mobile.app.ui.theme.KlicIcons.back),
                            contentDescription = "Back",
                            modifier = Modifier.size(24.dp),
                        )
                    }
                },
                actions = {
                    // Groups too: POST /calls rings every conversation member.
                    IconButton(onClick = { vm.startCall(conversation.id, "AUDIO", title); onCall("AUDIO") }) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(com.klic.mobile.app.ui.theme.KlicIcons.callSolid),
                            contentDescription = "Voice call",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    IconButton(onClick = { vm.startCall(conversation.id, "VIDEO", title); onCall("VIDEO") }) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(com.klic.mobile.app.ui.theme.KlicIcons.videoSolid),
                            contentDescription = "Video call",
                            modifier = Modifier.size(24.dp),
                        )
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
            // §16.3: pinned bar — newest pin first; taps step back through pins.
            val newestPinId = pinnedMessages.lastOrNull()?.id
            if (pinnedMessages.isNotEmpty() && pinBarHiddenAt[conversation.id] != newestPinId) {
                val pinIndex = (pinnedMessages.size - 1 - (pinStep % pinnedMessages.size))
                    .coerceIn(0, pinnedMessages.size - 1)
                PinnedMessagesBar(
                    pins = pinnedMessages,
                    currentIndex = pinIndex,
                    onTap = {
                        jumpToMessage(pinnedMessages[pinIndex].id)
                        pinStep++
                    },
                    onClose = {
                        if (canPinHere) unpinTargetId = pinnedMessages[pinIndex].id
                        else vm.hidePinBar(conversation.id)
                    },
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)
                    .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(messages.indices.toList()) { idx ->
                    val raw = messages[idx]
                    // §11.6: read receipts OFF → blue ticks hide in DMs both ways
                    // (own messages cap at "delivered"; the server stops peer events).
                    val msg = if (isDirect && me?.readReceipts == false && raw.status == "read") {
                        raw.copy(status = "delivered")
                    } else raw
                    val isMine = msg.senderId == me?.id
                    val isFirst = idx == 0 || messages[idx - 1].senderId != msg.senderId
                    val isLast  = idx == messages.size - 1 || messages[idx + 1].senderId != msg.senderId

                    if (idx == 0 || !sameDay(messages[idx - 1].createdAt, msg.createdAt)) {
                        DateSeparator(msg.createdAt)
                    }
                    // §16.1/§16.3: brief tinted pulse on the jump target bubble.
                    val highlightAlpha by animateFloatAsState(
                        targetValue = if (msg.id == highlightedId) 0.18f else 0f,
                        animationSpec = tween(250),
                        label = "jumpHighlight",
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = highlightAlpha),
                                RoundedCornerShape(10.dp),
                            ),
                    ) {
                    // §16.1: when the server's ReplyPreview lacks the parent's media
                    // (pre-WP-S9 servers) but the parent is loaded in this window,
                    // enrich the quote card locally so the thumbnail still renders.
                    val displayMsg = msg.replyTo?.let { r ->
                        if (r.attachment != null || r.deleted == true) {
                            msg
                        } else {
                            val parent = messages.firstOrNull { it.id == r.id }
                            val parentAtt = parent?.attachments?.firstOrNull()
                            when {
                                parent == null -> msg
                                parent.isDeleted -> msg.copy(replyTo = r.copy(deleted = true))
                                parentAtt != null -> msg.copy(
                                    replyTo = r.copy(
                                        attachment = com.klic.mobile.app.data.ReplyAttachment(
                                            id = parentAtt.id,
                                            kind = parentAtt.kind,
                                            url = parentAtt.url,
                                            contentType = parentAtt.contentType,
                                            width = parentAtt.width,
                                            height = parentAtt.height,
                                            durationMs = parentAtt.durationMs,
                                            fileName = parentAtt.fileName,
                                        ),
                                    ),
                                )
                                else -> msg
                            }
                        }
                    } ?: msg
                    // §15.3: swipe any bubble LEFT to reply (own and peer, every kind).
                    SwipeToReplyContainer(
                        enabled = !msg.isDeleted && msg.kind != "SYSTEM" && !msg.isCallEvent,
                        onReply = { vm.setReplyTo(msg) },
                    ) {
                        MessageBubble(
                            message = displayMsg,
                            isMine  = isMine,
                            isFirst = isFirst,
                            isLast  = isLast,
                            // §16.1: resolve the QUOTED sender's display name (group-aware).
                            replyAuthorName = msg.replyTo?.let { r ->
                                when {
                                    r.senderId == me?.id -> stringResource(R.string.common_you)
                                    else -> conversation.members.firstOrNull { it.id == r.senderId }?.displayName
                                        ?: title
                                }
                            } ?: "",
                            highlightMentions = !isDirect,
                            mentionNames = mentionableNames,
                            onCallBack = { kind -> vm.startCall(conversation.id, kind, title); onCall(kind) },
                            onLongPress = { menuTarget = msg },
                            onReactionTap = { emoji -> vm.react(conversation.id, msg.id, emoji) },
                            onMediaClick = { att -> viewerTarget = msg to att },
                            onFileClick = { att ->
                                scope.launch {
                                    val file = AttachmentDownloads.ensureLocal(context, att, conversation.id)
                                    when {
                                        file == null -> vm.error.value = context.getString(R.string.err_download_file)
                                        isPdfAttachment(att) -> pdfFile = file
                                        else -> fileDetail = att to file
                                    }
                                }
                            },
                            // §16.1: tap the quote card → scroll to the original + flash.
                            onQuoteClick = { msg.replyTo?.id?.let { jumpToMessage(it) } },
                        )
                    }
                    }
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
            // §16.2: circular live camera overlay while recording a round video note.
            if (recordPhase != RecordPhase.IDLE && captureMode == CaptureMode.VIDEO) {
                videoSession?.let { session ->
                    VideoNoteRecordingOverlay(session, Modifier.matchParentSize())
                }
            }
            } // Box (messages)

            if (recordPhase == RecordPhase.IDLE) {
                if (pendingMedia.isNotEmpty()) {
                    PendingMediaBar(
                        items = pendingMedia,
                        onRemove = { id -> pendingMedia = pendingMedia.filterNot { it.id == id } },
                        onEdit = { id -> editorTarget = pendingMedia.firstOrNull { it.id == id } },
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
            }
            if (peerBlockedByMe) {
                // §16.6: "You blocked <name>" + Unblock restores the composer in place
                // (unblockUser updates blockedUsers optimistically).
                BlockedComposerBanner(
                    name = peer?.displayName ?: title,
                    onUnblock = { peer?.id?.let { vm.unblockUser(it) } },
                )
            } else {
            ComposerBar(
                draft    = draft,
                // §15.1: reply preview renders inside the composer's input container.
                replyAuthor = replyingTo?.let { target ->
                    if (target.senderId == me?.id) stringResource(R.string.chat_yourself) else title
                },
                replyPreview = replyingTo?.let { messagePreview(it) } ?: "",
                onCancelReply = { vm.setReplyTo(null) },
                // §16.4: edit banner + checkmark send while editing.
                editingOriginal = editingMessage?.body,
                onCancelEdit = { exitEditMode() },
                shakeTrigger = shakeTrigger,
                focusRequester = composerFocus,
                onChange = { draft = it; vm.setTyping(conversation.id, it.text.isNotBlank()) },
                onSend   = {
                    val editTarget = editingMessage
                    if (editTarget != null) {
                        val text = draft.text.trim()
                        when {
                            // Empty apply → error shake, keep editing (§16.4).
                            text.isEmpty() -> shakeTrigger++
                            // Unchanged → silently exit edit mode, no request.
                            text == editTarget.body -> exitEditMode()
                            else -> {
                                vm.editMessage(conversation.id, editTarget.id, text)
                                exitEditMode()
                            }
                        }
                    } else if (pendingMedia.isNotEmpty()) {
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
                onToggleCaptureMode = { vm.toggleCaptureMode() },
                recordPhase = recordPhase,
                recordElapsed = recordElapsed,
                onHoldStart = { startRecording() },
                onHoldDrag = { total ->
                    lastRecordDrag = total
                    when {
                        recordPhase != RecordPhase.HELD -> RecordDragResult.LOCKED
                        // §16.2: slide UP past 57dp → LOCK (haptic fires in the button).
                        -total.y >= lockTravelPx -> {
                            recordPhase = RecordPhase.LOCKED
                            RecordDragResult.LOCKED
                        }
                        // §16.2: slide LEFT past min(35% width, 140dp) → cancel + discard.
                        -total.x >= cancelDistPx -> {
                            cancelRecording()
                            RecordDragResult.CANCELED
                        }
                        else -> RecordDragResult.CONTINUE
                    }
                },
                onHoldEnd = {
                    if (recordPhase == RecordPhase.HELD) {
                        // Release: cancel when slid past 55% of the cancel distance
                        // (progress < 0.45, reference-style), else stop + send.
                        val progress = 1f + lastRecordDrag.x.coerceAtMost(0f) / cancelDistPx
                        if (progress < RELEASE_CANCEL_PROGRESS) cancelRecording()
                        else finishAndSendRecording()
                    }
                },
                onRecordCancel = { cancelRecording() },
                onRecordSend = { finishAndSendRecording() },
            )
            } // if (peerBlockedByMe) else
        }
        } // Box
    }

    if (showAttachSheet) {
        // §10.11/§11.2: ONE Klic attachment sheet — camera tile + Gallery | Files tabs.
        KlicAttachmentSheet(
            onSendMedia = { uris ->
                // §13.17: bulk image/video selections stage in SELECTION ORDER and go
                // out as ONE message with multiple attachments (a bento grid bubble);
                // oversized selections chunk into batches of 10.
                scope.launch {
                    val staged = uris.mapNotNull { loadMediaDraft(context, it)?.attachment }
                    if (staged.isEmpty()) {
                        vm.error.value = context.getString(R.string.err_read_media)
                    } else {
                        staged.chunked(10).forEach { batch ->
                            vm.sendAttachments(conversation.id, null, batch)
                        }
                    }
                }
            },
            onOpenCamera = { showCamera = true },
            onSystemGallery = {
                mediaLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
            onSelectFiles = { fileLauncher.launch(arrayOf("*/*")) },
            onScannedPdf = { uri ->
                scope.launch {
                    val attachment = loadFileAttachment(context, uri)
                    if (attachment != null) {
                        vm.sendAttachments(conversation.id, null, listOf(attachment))
                    } else {
                        vm.error.value = context.getString(R.string.err_read_scan)
                    }
                }
            },
            onDismiss = { showAttachSheet = false },
        )
    }

    if (showCamera) {
        // §11.2: full in-app capture — the result joins the SAME pre-send flow
        // (pending-media bar with edit/caption) as gallery picks.
        KlicCameraCapture(
            onCaptured = { uri, isVideo ->
                showCamera = false
                scope.launch {
                    val draft = if (isVideo) loadVideoDraft(context, uri) else loadImageDraft(context, uri)
                    if (draft != null) {
                        pendingMedia = pendingMedia + draft
                    } else {
                        vm.error.value = context.getString(
                            if (isVideo) R.string.err_read_video else R.string.err_read_photo
                        )
                    }
                }
            },
            onClose = { showCamera = false },
        )
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

    // Long-press action menu (reactions + reply/edit/pin/copy/report/delete).
    menuTarget?.let { target ->
        val targetIsMine = target.senderId == me?.id
        val targetPinned = target.pinnedAt != null || pinnedMessages.any { it.id == target.id }
        // §16.4: own, non-deleted text/caption messages within 48h of sending.
        val editableKind = target.kind in setOf("TEXT", "IMAGE", "VIDEO", "VOICE", "FILE", "VIDEO_NOTE")
        val within48h = runCatching {
            java.time.Instant.parse(target.createdAt)
                .isAfter(java.time.Instant.now().minus(48, java.time.temporal.ChronoUnit.HOURS))
        }.getOrDefault(false)
        val canEdit = targetIsMine && !target.isDeleted && editableKind && within48h
        val pinnable = canPinHere && !target.isDeleted &&
            target.kind != "SYSTEM" && !target.isCallEvent
        MessageActionsOverlay(
            message = target,
            isMine = targetIsMine,
            onReact = { emoji -> vm.react(conversation.id, target.id, emoji); menuTarget = null },
            onReply = { vm.setReplyTo(target); menuTarget = null },
            onCopy = { clipboard.setText(AnnotatedString(target.body)) },
            onStar = { vm.toggleStar(target) },
            onDelete = { deleteTarget = target },
            onDismiss = { menuTarget = null },
            onReport = { reportMessageTarget = target; menuTarget = null },
            // §16.3: Pin/Unpin (DM: both sides; group: admin only).
            onPin = if (pinnable) {
                {
                    if (targetPinned) unpinTargetId = target.id else pinTarget = target
                    menuTarget = null
                }
            } else null,
            isPinned = targetPinned,
            // §16.4: Edit — loads the original into the composer with the edit banner.
            onEdit = if (canEdit) {
                { vm.setEditing(target); menuTarget = null }
            } else null,
        )
    }

    // §16.3: pin confirmation — groups choose whether to notify; DMs simply confirm.
    pinTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { pinTarget = null },
            title = { Text(stringResource(R.string.actions_pin)) },
            text = { if (isDirect) Text(stringResource(R.string.pin_confirm_dm)) else null },
            confirmButton = {
                if (isDirect) {
                    TextButton(onClick = {
                        vm.pinMessage(conversation.id, target.id, notify = false)
                        pinTarget = null
                    }) { Text(stringResource(R.string.actions_pin)) }
                } else {
                    Column {
                        TextButton(onClick = {
                            vm.pinMessage(conversation.id, target.id, notify = true)
                            pinTarget = null
                        }) { Text(stringResource(R.string.pin_notify_all)) }
                        TextButton(onClick = {
                            vm.pinMessage(conversation.id, target.id, notify = false)
                            pinTarget = null
                        }) { Text(stringResource(R.string.pin_only)) }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { pinTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    // §16.3: unpin always confirms.
    unpinTargetId?.let { messageId ->
        AlertDialog(
            onDismissRequest = { unpinTargetId = null },
            title = { Text(stringResource(R.string.actions_unpin)) },
            text = { Text(stringResource(R.string.unpin_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.unpinMessage(conversation.id, messageId)
                    unpinTargetId = null
                }) { Text(stringResource(R.string.actions_unpin)) }
            },
            dismissButton = {
                TextButton(onClick = { unpinTargetId = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    // §12.1: report sheet for a message — sender info powers one-tap block.
    reportMessageTarget?.let { target ->
        val sender = conversation.members.firstOrNull { it.id == target.senderId }
        com.klic.mobile.app.feature.report.ReportSheet(
            vm = vm,
            target = com.klic.mobile.app.feature.report.ReportTarget.Message(
                messageId = target.id,
                senderId = target.senderId,
                senderDisplayName = sender?.displayName,
                senderUsername = sender?.username,
            ),
            onDismiss = { reportMessageTarget = null },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null; menuTarget = null },
            title = { Text(stringResource(R.string.chat_delete_message)) },
            text = { Text(stringResource(R.string.chat_delete_cant_undo)) },
            confirmButton = {
                Column {
                    if (target.senderId == me?.id) {
                        TextButton(onClick = {
                            vm.deleteForEveryone(conversation.id, target.id); deleteTarget = null; menuTarget = null
                        }) { Text(stringResource(R.string.chat_delete_for_everyone), color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(onClick = {
                        vm.deleteForMe(target); deleteTarget = null; menuTarget = null
                    }) { Text(stringResource(R.string.chat_delete_for_me), color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null; menuTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    viewerTarget?.let { (msg, att) ->
        MediaViewerOverlay(
            vm = vm,
            message = msg,
            att = att,
            conversationId = conversation.id,
            onReply = { /* reply bar appears in this same chat */ },
            onDismiss = { viewerTarget = null },
        )
    }

    pdfFile?.let { file ->
        PdfViewerOverlay(file = file, onDismiss = { pdfFile = null })
    }

    // §10.9: pre-send media editor (caption + draw/text/crop/quality).
    editorTarget?.let { target ->
        MediaEditorDialog(
            draft = target,
            initialCaption = draft.text,
            onDone = { updated, caption ->
                pendingMedia = pendingMedia.map { if (it.id == updated.id) updated else it }
                draft = TextFieldValue(caption, selection = androidx.compose.ui.text.TextRange(caption.length))
                editorTarget = null
            },
            onDismiss = { editorTarget = null },
        )
    }

    fileDetail?.let { (att, file) ->
        FileDetailSheet(att = att, file = file, onDismiss = { fileDetail = null })
    }
    } // Box
    } // ChatBubbleTheme (§12.3)
}

/** §16.6: replaces the composer while the DM peer is blocked by this account. */
@Composable
private fun BlockedComposerBanner(name: String, onUnblock: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.chat_blocked_banner, name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onUnblock) {
                Text(
                    stringResource(R.string.chat_unblock),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
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
                if (joinedCount > 0) stringResource(R.string.chat_ongoing_call_count, joinedCount)
                else stringResource(R.string.chat_ongoing_call),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(start = 10.dp).weight(1f),
            )
            Text(
                stringResource(R.string.chat_join),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
