package com.klic.mobile.app.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.klic.mobile.app.calling.CallManager
import com.klic.mobile.app.calling.CallNotifications
import com.klic.mobile.app.calling.CallRinger
import com.klic.mobile.app.calling.OngoingCallService
import com.klic.mobile.app.data.ActiveCallInfo
import com.klic.mobile.app.data.AttachmentInput
import com.klic.mobile.app.data.AttachmentPage
import com.klic.mobile.app.data.CallSession
import com.klic.mobile.app.data.Conversation
import com.klic.mobile.app.data.ConversationPrefs
import com.klic.mobile.app.data.FriendRequest
import com.klic.mobile.app.data.KlicRepository
import com.klic.mobile.app.data.Message
import com.klic.mobile.app.data.NotificationPrefs
import com.klic.mobile.app.data.RecentCall
import com.klic.mobile.app.data.SettingsStore
import com.klic.mobile.app.data.StarredPage
import com.klic.mobile.app.data.Sticker
import com.klic.mobile.app.data.TokenStore
import com.klic.mobile.app.data.User
import com.klic.mobile.app.data.UserProfile
import com.klic.mobile.app.realtime.SocketService
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

class KlicViewModel(
    private val repo: KlicRepository,
    private val tokenStore: TokenStore,
    private val socket: SocketService,
    val callManager: CallManager,
    private val container: com.klic.mobile.app.AppContainer,
) : ViewModel() {

    val currentUser = MutableStateFlow<User?>(null)

    /** Localized user-facing message helper (§10.5). */
    private fun str(res: Int, vararg args: Any?): String =
        container.appContext.getString(res, *args)
    val isAuthenticated = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val themeMode = MutableStateFlow(container.themeMode)

    val conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val messages = MutableStateFlow<List<Message>>(emptyList())
    val presence = socket.presence   // userId -> online / last-seen
    val typing = socket.typing       // conversationId -> last typing epoch millis
    /** The message currently being replied to (drives the composer's reply bar). */
    val replyingTo = MutableStateFlow<Message?>(null)
    // Locally hidden messages ("delete for me") — session-scoped, filtered from the list.
    private val hiddenIds = mutableSetOf<String>()
    private var lastTypingSent = 0L
    val activeCall = MutableStateFlow<CallSession?>(null)
    val callPeerName = MutableStateFlow("")
    val callPeerId = MutableStateFlow<String?>(null)
    val callStatus = MutableStateFlow("Calling...")
    /** True while the active call belongs to a GROUP conversation (drives grid + grace UX). */
    val callIsGroup = MutableStateFlow(false)
    /** True while the in-call screen is collapsed into the floating overlay — the rest of the
     *  app is fully navigable; media keeps running (CallManager is app-scoped). */
    val callMinimized = MutableStateFlow(false)
    /** Wall-clock millis when the call first connected — drives the overlay's live timer. */
    val callConnectedAt = MutableStateFlow<Long?>(null)
    /** Live call in the currently open conversation, if any — drives the "Join call" banner. */
    val chatActiveCall = MutableStateFlow<ActiveCallInfo?>(null)

    val friends = MutableStateFlow<List<User>>(emptyList())
    val friendRequests = MutableStateFlow<List<FriendRequest>>(emptyList())
    val friendStatus = MutableStateFlow<String?>(null)

    val recentCalls = MutableStateFlow<List<RecentCall>>(emptyList())
    val stickers = MutableStateFlow<List<Sticker>>(emptyList())

    // Pagination
    val hasMoreMessages = MutableStateFlow(false)
    val isLoadingOlderMessages = MutableStateFlow(false)
    /** Emits the number of messages prepended so the UI can restore scroll position. */
    val prependedCount = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    init {
        viewModelScope.launch {
            tokenStore.load()
            repo.restoreCachedUser()
            if (repo.isAuthenticated) {
                // Show the signed-in UI immediately from cached state, then renew the
                // access token (if stale) before bringing realtime online.
                isAuthenticated.value = true
                currentUser.value = repo.currentUser
                runCatching { repo.ensureFreshToken() }
                onAuthed()
            }
            // Only now are stored tokens loaded + fresh. Call joins gate on this so an
            // FCM-cold-started answer can't fire joinToken before/while the token rotates
            // (a concurrent refresh burns the rotating refresh token → surprise sign-out).
            authReady.complete(Unit)
        }
        viewModelScope.launch {
            socket.incomingMessages.collect { raw ->
                // Decrypt-or-passthrough, then upsert — the server echoes our own
                // sends back for multi-device sync.
                val msg = container.e2eeMessaging.materialize(raw, currentUser.value?.id)
                if (msg.conversationId == openConversationId) upsertMessage(msg)
                // SYSTEM fanout (e.g. "«admin» removed «target»", §9.3) means membership
                // changed — refresh the conversations list so member rows reconcile.
                if (msg.kind == "SYSTEM") loadConversations()
            }
        }
        viewModelScope.launch {
            // §9.3: this user was removed from a group — drop the conversation locally.
            // Screens showing it (chat / group info) observe the list and pop themselves.
            socket.removedConversations.collect { convId ->
                conversations.value = conversations.value.filterNot { it.id == convId }
                if (openConversationId == convId) {
                    openConversationId = null
                    messages.value = emptyList()
                    chatActiveCall.value = null
                    replyingTo.value = null
                }
                messageCache.remove(convId)
            }
        }
        viewModelScope.launch {
            socket.reactionUpdates.collect { u ->
                if (u.conversationId == openConversationId) {
                    messages.value = messages.value.map {
                        if (it.id == u.messageId) it.copy(reactions = u.reactions) else it
                    }
                }
            }
        }
        viewModelScope.launch {
            socket.deletedMessages.collect { u ->
                if (u.conversationId == openConversationId) markDeletedLocally(u.messageId)
            }
        }
        viewModelScope.launch {
            socket.callEvents.collect { event ->
                handleCallEvent(event)
            }
        }
        viewModelScope.launch {
            // Keep the open chat's "Join call" banner in sync: joins refresh it, terminal
            // events clear it. (Separate from handleCallEvent, which only tracks OUR call.)
            socket.callEvents.collect { event ->
                when (event.type) {
                    SocketService.CallEvent.Type.PARTICIPANT_JOINED ->
                        if (chatActiveCall.value?.callId == event.callId) {
                            openConversationId?.let { refreshActiveCall(it) }
                        }
                    SocketService.CallEvent.Type.END,
                    SocketService.CallEvent.Type.CANCEL ->
                        if (chatActiveCall.value?.callId == event.callId) chatActiveCall.value = null
                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            // A ringing invite for the open conversation → an active call just started there.
            socket.incomingCalls.collect { invite ->
                if (invite.conversationId == openConversationId) refreshActiveCall(invite.conversationId)
            }
        }
        viewModelScope.launch {
            // "Reconnecting…" covers both sides: our own resume/rejoin loop, and — on a 1:1 —
            // the only peer sitting in their 60s grace window after dropping from the SFU.
            combine(callManager.isReconnecting, callManager.participants) { local, parts ->
                local || (!callIsGroup.value && parts.isNotEmpty() && parts.all { it.reconnecting })
            }.collect { reconnecting ->
                if (activeCall.value == null) return@collect
                if (reconnecting) callStatus.value = "Reconnecting…"
                else if (callStatus.value == "Reconnecting…") callStatus.value = "Connected"
            }
        }
        viewModelScope.launch {
            // §7.5: audio focus stolen by another call/app → "On Hold"; regained → back to
            // Connected. Only flips a settled "Connected" status so Calling…/Reconnecting… win.
            callManager.onHold.collect { held ->
                if (activeCall.value == null) return@collect
                if (held) {
                    if (callStatus.value == "Connected") callStatus.value = "On Hold"
                } else if (callStatus.value == "On Hold") {
                    callStatus.value = "Connected"
                }
            }
        }
        viewModelScope.launch {
            // A rejoin reconnected us to the room — re-announce media presence to the server.
            callManager.rejoined.collect { id ->
                if (activeCall.value?.callId == id) repo.mediaJoined(id)
            }
        }
        viewModelScope.launch {
            callManager.rejoinFailed.collect { outcome ->
                val id = activeCall.value?.callId ?: return@collect
                // CALL_OVER: the server already retired the call (token 404/409/410) — finish
                // quietly. GAVE_UP: budget spent without a connection — best-effort end.
                if (outcome == CallManager.RejoinOutcome.GAVE_UP) {
                    viewModelScope.launch { repo.endCall(id) }
                }
                finishCall(callId = id)
            }
        }
        viewModelScope.launch {
            // A remote peer's 60s grace expired. 1:1 → the call is effectively over (outcome
            // completed). Group → their tile is already gone; the call carries on.
            callManager.peerGraceExpired.collect {
                val id = activeCall.value?.callId ?: return@collect
                if (!callIsGroup.value) {
                    repo.endCall(id)
                    finishCall(callId = id)
                }
            }
        }
        viewModelScope.launch {
            container.callHangup.collect { endCall() }
        }
        viewModelScope.launch {
            container.sessionExpired.collect { handleSessionExpired() }
        }
        viewModelScope.launch {
            socket.readReceipts.collect { applyReceipt(it, read = true) }
        }
        viewModelScope.launch {
            socket.deliveredReceipts.collect { applyReceipt(it, read = false) }
        }
        viewModelScope.launch {
            // §9.9: write-through — every message-list mutation (send, socket event,
            // pagination, delete…) lands in the per-conversation cache automatically.
            messages.collect { list ->
                openConversationId?.let { messageCache[it] = list }
            }
        }
    }

    private var openConversationId: String? = null
    private var activeCallOutgoing = false
    private var ringTimeoutJob: Job? = null
    // The in-flight server-side end/cancel/decline of the last call. A new outgoing call
    // joins this first so POST /calls can't race it into a 409 call_exists.
    private var serverTeardownJob: Job? = null
    private val finishingCallIds = mutableSetOf<String>()
    // Completed once stored tokens are loaded (and refreshed if stale) — see init.
    private val authReady = CompletableDeferred<Unit>()

    fun login(username: String, password: String) = launchAuth {
        repo.login(username, password).let { currentUser.value = it }
        onAuthed()
    }

    fun register(username: String, password: String, displayName: String) = launchAuth {
        repo.register(username, password, displayName).let { currentUser.value = it }
        onAuthed()
    }

    fun logout() = viewModelScope.launch {
        repo.logout()
        socket.disconnect()
        // §13.12: the app lock never survives the signed-out transition — the next
        // account must not inherit the previous user's passcode/biometrics/auto-lock.
        com.klic.mobile.app.data.AppLockStore.wipe()
        isAuthenticated.value = false
    }

    fun setThemeMode(mode: String) {
        themeMode.value = mode
        container.themeMode = mode
    }

    fun callFriendDirect(userId: String, kind: String, peerName: String) =
        viewModelScope.launch {
            if (activeCall.value != null) return@launch
            serverTeardownJob?.join()
            callIsGroup.value = false
            callPeerName.value = peerName
            callPeerId.value = userId
            runCatching {
                val convo = repo.openConversation(userId)
                convo.id to repo.startCall(convo.id, kind)
            }.onSuccess { (convoId, session) ->
                container.activeCallConversationId.value = convoId
                startActiveCall(session, peerName, outgoing = true)
            }
        }

    fun loadConversations() = viewModelScope.launch {
        runCatching { repo.conversations() }.onSuccess { list ->
            conversations.value = list
            // Cache which conversations are groups so the killed-app push path can pick
            // the right global toggle (message vs group notifications, §8.5).
            SettingsStore.setGroupConversationIds(
                list.filter { it.type == "GROUP" }.map { it.id }.toSet()
            )
        }
    }

    fun loadFriends() = viewModelScope.launch {
        runCatching { repo.friends() }.onSuccess { friends.value = it }
        runCatching { repo.friendRequests() }.onSuccess { friendRequests.value = it }
    }

    fun addFriend(username: String) = viewModelScope.launch {
        val name = username.trim().lowercase()
        if (name.isEmpty()) return@launch
        val user = runCatching { repo.findUser(name) }.getOrNull()
        if (user == null) {
            friendStatus.value = str(com.klic.mobile.app.R.string.err_no_user_named, name)
        } else {
            runCatching { repo.sendFriendRequest(user.id) }
            friendStatus.value = str(com.klic.mobile.app.R.string.friends_request_sent, user.displayName)
        }
    }

    /** §13.8: QR / universal-link add-friend — the outcome surfaces as a toast. */
    fun addFriendFromLink(username: String) = viewModelScope.launch {
        val name = username.trim().removePrefix("@").lowercase()
        if (name.isEmpty()) return@launch
        val user = runCatching { repo.findUser(name) }.getOrNull()
        if (user == null) {
            error.value = str(com.klic.mobile.app.R.string.err_no_user_named, name)
        } else {
            runCatching { repo.sendFriendRequest(user.id) }
            error.value = str(com.klic.mobile.app.R.string.friends_request_sent, user.displayName)
        }
    }

    fun acceptRequest(id: String) = viewModelScope.launch {
        runCatching { repo.acceptFriendRequest(id) }; loadFriends()
    }

    fun declineRequest(id: String) = viewModelScope.launch {
        runCatching { repo.declineFriendRequest(id) }; loadFriends()
    }

    fun openConversationWith(userId: String, onReady: (Conversation) -> Unit) = viewModelScope.launch {
        runCatching { repo.openConversation(userId) }.onSuccess { c ->
            if (conversations.value.none { it.id == c.id }) conversations.value = conversations.value + c
            onReady(c)
        }
    }

    fun createGroupConversation(
        title: String,
        userIds: List<String>,
        avatarBytes: ByteArray? = null,
        avatarContentType: String? = null,
        onReady: (Conversation) -> Unit,
    ) = viewModelScope.launch {
        runCatching { repo.createGroupConversation(title.trim(), userIds.distinct()) }.onSuccess { c ->
            // Cover picked during creation uploads after the fact — the group exists either
            // way, but §10.1 requires the failure to be VISIBLE (retry from GroupInfo).
            val withCover = if (avatarBytes != null && avatarContentType != null) {
                runCatching { repo.uploadConversationAvatar(c.id, avatarBytes, avatarContentType) }
                    .onFailure { e ->
                        error.value = when (e) {
                            is com.klic.mobile.app.data.CoverUploadException ->
                                str(com.klic.mobile.app.R.string.err_group_created_photo_step, e.step)
                            else -> str(com.klic.mobile.app.R.string.err_group_created_photo)
                        }
                    }
                    .getOrDefault(c)
            } else c
            conversations.value = listOf(withCover) + conversations.value.filterNot { it.id == c.id }
            onReady(withCover)
        }.onFailure {
            error.value = str(com.klic.mobile.app.R.string.err_create_group)
        }
    }

    /** Upload + attach a new group cover from GroupInfo (§8.4).
     *  §10.1: failures surface step-by-step (presign / storage PUT / PATCH) as a toast,
     *  and a successful PATCH updates the conversation in place (list + info page). */
    fun updateGroupCover(conversationId: String, bytes: ByteArray, contentType: String) =
        viewModelScope.launch {
            runCatching { repo.uploadConversationAvatar(conversationId, bytes, contentType) }
                .onSuccess { updated ->
                    conversations.value = conversations.value.map { if (it.id == updated.id) updated else it }
                }
                .onFailure { e ->
                    error.value = when (e) {
                        is com.klic.mobile.app.data.CoverUploadException ->
                            (str(com.klic.mobile.app.R.string.err_group_photo_step, e.step) +
                                " " + (e.cause?.message ?: "")).trim()
                        else -> str(com.klic.mobile.app.R.string.err_group_photo, e.message ?: "")
                    }
                }
        }

    /** Admin removes a group member (§9.3): optimistic drop, restored if the call fails. */
    fun removeGroupMember(conversationId: String, userId: String) = viewModelScope.launch {
        val removed = conversations.value
            .firstOrNull { it.id == conversationId }?.members?.firstOrNull { it.id == userId }
            ?: return@launch
        conversations.value = conversations.value.map { c ->
            if (c.id == conversationId) c.copy(members = c.members.filterNot { it.id == userId }) else c
        }
        runCatching { repo.removeConversationMember(conversationId, userId) }
            .onFailure {
                // Reconcile: put the member back and surface the failure.
                conversations.value = conversations.value.map { c ->
                    if (c.id == conversationId && c.members.none { it.id == userId }) {
                        c.copy(members = c.members + removed)
                    } else c
                }
                error.value = str(com.klic.mobile.app.R.string.err_remove_member, removed.displayName)
            }
    }

    // §9.9: last-known message list per conversation — re-opening a chat renders
    // instantly from here while the fresh page loads in the background.
    private val messageCache = mutableMapOf<String, List<Message>>()

    fun openChat(conversationId: String) = viewModelScope.launch {
        openConversationId = conversationId
        replyingTo.value = null
        isLoadingOlderMessages.value = false
        chatActiveCall.value = null
        refreshActiveCall(conversationId)
        // Clear any pending notification (and its launcher badge) for this conversation.
        CallNotifications.cancelMessage(container.appContext, conversationId)
        // Cache-first render (§9.9), then refresh; socket events stay authoritative.
        val cached = messageCache[conversationId]
        messages.value = cached?.filterNot { m -> m.id in hiddenIds } ?: emptyList()
        hasMoreMessages.value = (cached?.size ?: 0) >= 50
        runCatching { repo.messages(conversationId) }
            .onSuccess { msgs ->
                // A different chat may have been opened while this fetch was in flight.
                if (openConversationId != conversationId) return@onSuccess
                messages.value = msgs.reversed().filterNot { m -> m.id in hiddenIds }
                hasMoreMessages.value = msgs.size >= 50
            }
    }

    fun loadOlderMessages() = viewModelScope.launch {
        val convId = openConversationId ?: return@launch
        if (isLoadingOlderMessages.value || !hasMoreMessages.value) return@launch
        val oldest = messages.value.firstOrNull()?.createdAt ?: return@launch
        isLoadingOlderMessages.value = true
        runCatching { repo.messages(convId, before = oldest) }
            .onSuccess { older ->
                val filtered = older.reversed().filterNot { m -> m.id in hiddenIds }
                messages.value = filtered + messages.value
                hasMoreMessages.value = older.size >= 50
                prependedCount.tryEmit(filtered.size)
            }
        isLoadingOlderMessages.value = false
    }

    fun send(conversationId: String, body: String) = viewModelScope.launch {
        val replyId = replyingTo.value?.id
        replyingTo.value = null
        runCatching { repo.send(conversationId, body, replyId) }
            .onSuccess { upsertMessage(it); bumpSent(conversationId) }
    }

    fun sendSticker(conversationId: String, stickerId: String) = viewModelScope.launch {
        val replyId = replyingTo.value?.id
        replyingTo.value = null
        runCatching { repo.sendSticker(conversationId, stickerId, replyId) }
            .onSuccess { upsertMessage(it) }
    }

    fun setReplyTo(message: Message?) { replyingTo.value = message }

    /** Throttled typing signal — re-sent at most every 2s while typing, cleared on stop. */
    fun setTyping(conversationId: String, isTyping: Boolean) {
        if (isTyping) {
            val now = System.currentTimeMillis()
            if (now - lastTypingSent < 2000) return
            lastTypingSent = now
            socket.emit("typing", buildJsonObject { put("conversationId", conversationId); put("isTyping", true) })
        } else {
            lastTypingSent = 0L
            socket.emit("typing", buildJsonObject { put("conversationId", conversationId); put("isTyping", false) })
        }
    }

    fun react(conversationId: String, messageId: String, emoji: String) = viewModelScope.launch {
        runCatching { repo.react(conversationId, messageId, emoji) }
            .onSuccess { updated ->
                messages.value = messages.value.map { if (it.id == messageId) it.copy(reactions = updated) else it }
            }
    }

    fun deleteForMe(message: Message) {
        hiddenIds += message.id
        messages.value = messages.value.filterNot { it.id == message.id }
    }

    fun deleteForEveryone(conversationId: String, messageId: String) = viewModelScope.launch {
        repo.deleteForEveryone(conversationId, messageId)
        markDeletedLocally(messageId)
    }

    private fun markDeletedLocally(messageId: String) {
        val now = Instant.now().toString()
        messages.value = messages.value.map {
            if (it.id == messageId) it.copy(deletedAt = now, reactions = emptyList(), attachments = emptyList())
            else it
        }
    }

    private fun upsertMessage(m: Message) {
        if (m.id in hiddenIds) return
        val list = messages.value
        messages.value = if (list.any { it.id == m.id }) list.map { if (it.id == m.id) m else it } else list + m
    }

    fun loadRecentCalls() = viewModelScope.launch {
        runCatching { repo.recentCalls() }.onSuccess { recentCalls.value = it }
    }

    fun loadStickers() = viewModelScope.launch {
        if (stickers.value.isNotEmpty()) return@launch
        runCatching { repo.stickers() }.onSuccess { stickers.value = it }
    }

    fun sendVoice(conversationId: String, bytes: ByteArray, durationMs: Int, waveform: ByteArray) =
        viewModelScope.launch {
            replyingTo.value = null
            runCatching { repo.uploadVoice(conversationId, bytes, durationMs, waveform) }
                .onSuccess { upsertMessage(it) }
                .onFailure { error.value = str(com.klic.mobile.app.R.string.err_send_voice) }
        }

    fun sendImage(conversationId: String, bytes: ByteArray, contentType: String, width: Int? = null, height: Int? = null) =
        viewModelScope.launch {
            replyingTo.value = null
            runCatching { repo.uploadImage(conversationId, bytes, contentType, width, height) }
                .onSuccess { upsertMessage(it) }
                .onFailure { error.value = str(com.klic.mobile.app.R.string.err_send_photo) }
        }

    // ── Optimistic uploads (§9.1) ─────────────────────────────────────────────

    /** In-flight/failed optimistic uploads, rendered as progress pills in the chat. */
    val uploadTasks = MutableStateFlow<List<UploadTask>>(emptyList())

    /** Insert an optimistic pill and start uploading immediately — never blocks the UI. */
    fun sendAttachments(conversationId: String, body: String?, attachments: List<AttachmentInput>) {
        val replyId = replyingTo.value?.id
        replyingTo.value = null
        val task = UploadTask(
            id = java.util.UUID.randomUUID().toString(),
            conversationId = conversationId,
            body = body?.takeIf { it.isNotBlank() },
            attachments = attachments,
            replyToId = replyId,
        )
        uploadTasks.value = uploadTasks.value + task
        startUpload(task)
    }

    /**
     * §11.2 bulk send from the attach sheet: ONE message per item, sent strictly in
     * selection order — each pill uploads and lands before the next one starts.
     */
    fun sendAttachmentsSequentially(conversationId: String, items: List<AttachmentInput>) {
        if (items.isEmpty()) return
        replyingTo.value = null
        val tasks = items.map {
            UploadTask(
                id = java.util.UUID.randomUUID().toString(),
                conversationId = conversationId,
                body = null,
                attachments = listOf(it),
                replyToId = null,
            )
        }
        uploadTasks.value = uploadTasks.value + tasks
        viewModelScope.launch {
            for (task in tasks) startUpload(task).join()
        }
    }

    fun retryUpload(taskId: String) {
        val task = uploadTasks.value.firstOrNull { it.id == taskId && it.failed } ?: return
        updateUploadTask(taskId) { it.copy(failed = false, progress = 0f, errorMessage = null) }
        startUpload(task)
    }

    fun discardUpload(taskId: String) {
        uploadTasks.value = uploadTasks.value.filterNot { it.id == taskId }
    }

    private fun startUpload(task: UploadTask) = viewModelScope.launch {
        runCatching {
            repo.uploadAttachments(
                task.conversationId, task.attachments, task.body, task.replyToId,
            ) { sent, total ->
                if (total > 0) {
                    updateUploadTask(task.id) {
                        it.copy(progress = (sent.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }.onSuccess { msg ->
            // In-place replacement: the pill leaves and the server message lands in the
            // same frame, so the list doesn't jump.
            uploadTasks.value = uploadTasks.value.filterNot { it.id == task.id }
            if (msg.conversationId == openConversationId) upsertMessage(msg)
            bumpSent(task.conversationId)
        }.onFailure { e ->
            // Keep the pill with retry/discard affordances, and say WHY it failed —
            // size cap (server message / 413) vs plain network trouble (§13.15).
            val reason = repo.serverMessage(e) ?: when {
                (e as? retrofit2.HttpException)?.code() == 413 ||
                    e.message?.contains("(413)") == true ->
                    str(com.klic.mobile.app.R.string.upload_failed_too_large)
                e is java.io.IOException ->
                    str(com.klic.mobile.app.R.string.upload_failed_network)
                else -> null
            }
            updateUploadTask(task.id) { it.copy(failed = true, errorMessage = reason) }
        }
    }

    private fun updateUploadTask(id: String, transform: (UploadTask) -> UploadTask) {
        uploadTasks.value = uploadTasks.value.map { if (it.id == id) transform(it) else it }
    }

    fun startCall(conversationId: String, kind: String, peerName: String) = viewModelScope.launch {
        if (activeCall.value != null) return@launch
        // Let the previous call's server-side end/cancel land first, or POST /calls 409s
        // against the call we just hung up and the tap looks dead.
        serverTeardownJob?.join()
        val convo = conversations.value.firstOrNull { it.id == conversationId }
        callIsGroup.value = convo?.type == "GROUP"
        callPeerName.value = peerName
        callPeerId.value = if (convo?.type == "DIRECT") convo.members.firstOrNull()?.id else null
        runCatching { repo.startCall(conversationId, kind) }
            .onSuccess {
                container.activeCallConversationId.value = conversationId
                startActiveCall(it, peerName, outgoing = true)
            }
    }

    /** Answer an incoming call (from the full-screen notification): fetch a join token. */
    fun acceptIncomingCall(callId: String, peerName: String, isGroup: Boolean = false) = viewModelScope.launch {
        // On an FCM cold start this can run before the init coroutine has loaded/refreshed
        // the stored tokens — joining then would 401 and race the rotation. Wait it out.
        authReady.await()
        callIsGroup.value = isGroup
        callPeerName.value = peerName
        callStatus.value = "Connecting..."
        val result = runCatching { repo.joinToken(callId) }
        result.onSuccess { startActiveCall(it, peerName, outgoing = false) }
        if (result.isFailure) {
            repo.failCall(callId)
            callStatus.value = "Call failed"
            finishCall(delayMs = 1200)
        }
    }

    /** Fetch the open conversation's live call (if any) for the "Join call" banner. */
    fun refreshActiveCall(conversationId: String) {
        viewModelScope.launch {
            val info = runCatching { repo.activeCall(conversationId) }.getOrNull()
            if (openConversationId == conversationId) chatActiveCall.value = info
        }
    }

    /** Late-join the conversation's ongoing call from the chat banner (same flow as answering). */
    fun joinOngoingCall(conversationId: String) = viewModelScope.launch {
        if (activeCall.value != null) return@launch
        authReady.await()
        val info = runCatching { repo.activeCall(conversationId) }.getOrNull()
        if (info == null) {
            // The call ended between render and tap.
            if (openConversationId == conversationId) chatActiveCall.value = null
            return@launch
        }
        val convo = conversations.value.firstOrNull { it.id == conversationId }
        val title = when {
            convo == null -> "Call"
            !convo.title.isNullOrBlank() -> convo.title
            convo.type == "DIRECT" -> convo.members.firstOrNull()?.displayName ?: "Call"
            else -> convo.members.joinToString(", ") { it.displayName }.ifBlank { "Group" }
        }
        callIsGroup.value = convo?.type == "GROUP"
        callPeerName.value = title
        callPeerId.value = if (convo?.type == "DIRECT") convo.members.firstOrNull()?.id else null
        callStatus.value = "Connecting..."
        val result = runCatching { repo.joinToken(info.callId) }
        result.onSuccess {
            container.activeCallConversationId.value = conversationId
            startActiveCall(it, title, outgoing = false)
        }
        if (result.isFailure && openConversationId == conversationId) chatActiveCall.value = null
    }

    /** Collapse the in-call screen into the floating overlay (media untouched). */
    fun minimizeCall() {
        if (activeCall.value != null) callMinimized.value = true
    }

    /** Restore the full in-call screen from the overlay. */
    fun restoreCall() {
        callMinimized.value = false
    }

    fun endCall() {
        val id = activeCall.value?.callId
        if (id != null) {
            // "Reconnecting…"/"On Hold" are still live calls (media was up) — hang-up must
            // END them, not cancel/decline, so the outcome records as completed.
            val wasConnected = callStatus.value == "Connected" ||
                callStatus.value == "Reconnecting…" ||
                callStatus.value == "On Hold"
            val wasOutgoing = activeCallOutgoing
            serverTeardownJob = viewModelScope.launch {
                runCatching {
                    when {
                        wasConnected -> repo.endCall(id)
                        wasOutgoing -> repo.cancelCall(id)
                        else -> repo.declineCall(id)
                    }
                }
            }
        }
        finishCall(callId = id)
    }

    fun onCallMediaJoined(callId: String) {
        if (activeCall.value?.callId != callId) return
        viewModelScope.launch { repo.mediaJoined(callId) }
        if (activeCallOutgoing) return
        callStatus.value = "Connected"
        markCallConnected()
    }

    fun onCallJoinFailed(callId: String) {
        if (activeCall.value?.callId != callId) return
        callStatus.value = "Call failed"
        val wasOutgoing = activeCallOutgoing
        viewModelScope.launch {
            if (wasOutgoing) repo.cancelCall(callId) else repo.failCall(callId)
        }
        finishCall(delayMs = 1200, callId = callId)
    }

    private fun onAuthed() {
        isAuthenticated.value = true
        currentUser.value = repo.currentUser
        tokenStore.cachedAccess?.let { socket.connect(it) }
        registerPushToken()
        loadConversations()
        // E2EE: publish/refresh this install's key bundle (generates keys on first run).
        viewModelScope.launch { container.e2eeKeys.ensureReady() }
    }

    /** Register this device's FCM token so the server can ring incoming calls when killed. */
    private fun registerPushToken() {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            viewModelScope.launch { repo.registerDevice(token) }
        }
    }

    /** The server rejected our refresh token — a real sign-out (not a transient error). */
    private fun handleSessionExpired() {
        socket.disconnect()
        currentUser.value = null
        // §13.12: any transition to the signed-out state wipes the app lock.
        com.klic.mobile.app.data.AppLockStore.wipe()
        isAuthenticated.value = false
    }

    /** Emit a read receipt for the open conversation. */
    fun markRead(conversationId: String) {
        socket.emit("message:read", buildJsonObject { put("conversationId", conversationId) })
    }

    // ── v0.5.1 (§8.2/§8.4/§8.5): stars, prefs, attachments, jump-to-message ──

    /** Message id the open chat should scroll to next (search / starred taps). */
    val pendingJumpMessageId = MutableStateFlow<String?>(null)

    fun requestJumpTo(messageId: String) { pendingJumpMessageId.value = messageId }

    /** Star/unstar — optimistic flip, reverted if the server call fails. */
    fun toggleStar(message: Message) = viewModelScope.launch {
        val newValue = !message.starred
        messages.value = messages.value.map {
            if (it.id == message.id) it.copy(starred = newValue) else it
        }
        val result = runCatching {
            if (newValue) repo.starMessage(message.id) else repo.unstarMessage(message.id)
        }
        if (result.isFailure) {
            messages.value = messages.value.map {
                if (it.id == message.id) it.copy(starred = !newValue) else it
            }
        }
    }

    /** Global notification toggles (§8.5) — server-synced, cached for local gating. */
    val notificationPrefs = MutableStateFlow(NotificationPrefs())

    fun loadNotificationPrefs() = viewModelScope.launch {
        runCatching { repo.notificationPrefs() }
            .onSuccess {
                notificationPrefs.value = it
                SettingsStore.setNotificationToggles(it)
            }
            .onFailure {
                // Server not reachable/deployed — show the local cache.
                val s = SettingsStore.snapshot.value
                notificationPrefs.value =
                    NotificationPrefs(s.notifMessages, s.notifGroups, s.notifCalls, s.notifFriendRequests)
            }
    }

    fun updateNotificationPrefs(
        messages: Boolean? = null,
        groups: Boolean? = null,
        calls: Boolean? = null,
        friendRequests: Boolean? = null,
    ) = viewModelScope.launch {
        val cur = notificationPrefs.value
        val updated = cur.copy(
            messages = messages ?: cur.messages,
            groups = groups ?: cur.groups,
            calls = calls ?: cur.calls,
            friendRequests = friendRequests ?: cur.friendRequests,
        )
        // Optimistic + always cached locally so the on-device gating honors it offline.
        notificationPrefs.value = updated
        SettingsStore.setNotificationToggles(updated)
        runCatching { repo.updateNotificationPrefs(messages, groups, calls, friendRequests) }
    }

    /** "Reset notification settings": DELETE server-side + drop local tones/toggles. */
    fun resetNotificationSettings() = viewModelScope.launch {
        runCatching { repo.resetNotificationPrefs() }
        SettingsStore.resetNotificationSettings()
        notificationPrefs.value = NotificationPrefs()
    }

    /** Per-conversation prefs, from the server when possible, else the local cache. */
    suspend fun fetchConversationPrefs(conversationId: String): ConversationPrefs {
        val remote = runCatching { repo.conversationPrefs(conversationId) }.getOrNull()
        if (remote != null) {
            SettingsStore.cacheConversationPrefs(conversationId, remote)
            return remote
        }
        val s = SettingsStore.snapshot.value
        return ConversationPrefs(
            messagesMutedUntil = s.messagesMutedUntil[conversationId]
                ?.let { Instant.ofEpochMilli(it).toString() },
            muteMentions = conversationId in s.muteMentions,
            callsMutedUntil = s.callsMutedUntil[conversationId]
                ?.let { Instant.ofEpochMilli(it).toString() },
        )
    }

    /**
     * Update per-conversation mutes (partial). Applies locally even when the server
     * call fails so mutes keep working before the endpoint is deployed.
     */
    suspend fun setConversationPrefs(
        conversationId: String,
        current: ConversationPrefs,
        setMessagesMuted: Boolean = false,
        messagesMutedUntil: String? = null,
        muteMentions: Boolean? = null,
        setCallsMuted: Boolean = false,
        callsMutedUntil: String? = null,
    ): ConversationPrefs {
        val optimistic = current.copy(
            messagesMutedUntil = if (setMessagesMuted) messagesMutedUntil else current.messagesMutedUntil,
            muteMentions = muteMentions ?: current.muteMentions,
            callsMutedUntil = if (setCallsMuted) callsMutedUntil else current.callsMutedUntil,
        )
        val result = runCatching {
            repo.updateConversationPrefs(
                conversationId,
                setMessagesMuted, messagesMutedUntil,
                muteMentions,
                setCallsMuted, callsMutedUntil,
            )
        }.getOrDefault(optimistic)
        SettingsStore.cacheConversationPrefs(conversationId, result)
        return result
    }

    // §9.9: first pages of the chat-info attachment tabs + starred lists, cached for
    // instant render on re-entry (refreshed in background by the pages themselves).
    private val attachmentPageCache = mutableMapOf<String, AttachmentPage>()
    private val starredPageCache = mutableMapOf<String, StarredPage>()

    fun cachedStarred(conversationId: String?): StarredPage? =
        starredPageCache[conversationId ?: "all"]

    fun cachedAttachments(conversationId: String, kind: String?): AttachmentPage? =
        attachmentPageCache["$conversationId:${kind ?: "all"}"]

    suspend fun fetchStarred(conversationId: String?, cursor: String? = null): StarredPage? =
        runCatching { repo.starredMessages(conversationId, cursor) }.getOrNull()
            ?.also { if (cursor == null) starredPageCache[conversationId ?: "all"] = it }

    suspend fun fetchAttachments(conversationId: String, kind: String?, cursor: String? = null): AttachmentPage? =
        runCatching { repo.conversationAttachments(conversationId, kind, cursor) }.getOrNull()
            ?.also { if (cursor == null) attachmentPageCache["$conversationId:${kind ?: "all"}"] = it }

    /** Raw history fetch for the links scan / group search fetch-back (§8.4). */
    suspend fun fetchMessagesBefore(conversationId: String, before: String?): List<Message>? =
        runCatching { repo.messages(conversationId, before = before) }.getOrNull()

    // ── Profile ───────────────────────────────────────────────────────────────

    /**
     * §11.4/§11.5: save the edited profile — display name, @username, About, links and
     * (optionally) a new avatar. Only changed fields are PATCHed. Server rejections
     * (e.g. "Username is taken") surface through [onError] for inline display.
     */
    fun saveProfile(
        displayName: String,
        username: String? = null,
        setAbout: Boolean = false,
        about: String? = null,
        links: List<String>? = null,
        avatarBytes: ByteArray? = null,
        contentType: String? = null,
        onDone: () -> Unit = {},
        onError: (String?) -> Unit = { error.value = str(com.klic.mobile.app.R.string.err_save_profile) },
    ) = viewModelScope.launch {
        runCatching {
            val key = if (avatarBytes != null && contentType != null) {
                repo.uploadAvatar(avatarBytes, contentType)
            } else null
            repo.updateProfile(
                displayName = displayName,
                avatarKey = key,
                username = username,
                setAbout = setAbout,
                about = about,
                links = links,
            )
        }.onSuccess { currentUser.value = it; onDone() }
            .onFailure { onError(repo.serverMessage(it)) }
    }

    /** §11.6: PATCH /me with one privacy visibility (EVERYBODY / FRIENDS / NOBODY). */
    fun setPrivacyVisibility(field: String, value: String) = viewModelScope.launch {
        runCatching { repo.updatePrivacySetting(field, value) }
            .onSuccess { currentUser.value = it }
            .onFailure { error.value = str(com.klic.mobile.app.R.string.err_save_privacy) }
    }

    /** §11.6: PATCH /me with one privacy toggle (silenceUnknownCallers / readReceipts). */
    fun setPrivacyToggle(field: String, value: Boolean) = viewModelScope.launch {
        runCatching { repo.updatePrivacySetting(field, value) }
            .onSuccess { currentUser.value = it }
            .onFailure { error.value = str(com.klic.mobile.app.R.string.err_save_privacy) }
    }

    // §9.9: profiles render instantly from this session cache, refreshed in background.
    private val profileCache = mutableMapOf<String, UserProfile>()

    fun cachedProfile(userId: String): UserProfile? = profileCache[userId]

    suspend fun fetchProfile(userId: String): UserProfile? =
        runCatching { repo.userProfile(userId) }.getOrNull()
            ?.also { profileCache[userId] = it }

    /** Display name for any known user — me, or a member of any cached conversation. */
    fun displayNameFor(userId: String): String? {
        if (userId == currentUser.value?.id) return currentUser.value?.displayName
        return conversations.value.asSequence()
            .flatMap { it.members.asSequence() }
            .firstOrNull { it.id == userId }
            ?.displayName
    }

    // Advance ticks on the user's own messages when a read/delivered receipt arrives.
    private fun applyReceipt(receipt: SocketService.Receipt, read: Boolean) {
        val myId = currentUser.value?.id
        if (receipt.conversationId != openConversationId || receipt.userId == myId) return
        messages.value = messages.value.map { m ->
            val mine = m.senderId == myId
            val before = msMillis(m.createdAt)?.let { it <= receipt.atMs } == true
            when {
                !mine || !before -> m
                read -> m.copy(status = "read")
                m.status != "read" -> m.copy(status = "delivered")
                else -> m
            }
        }
    }

    private fun msMillis(iso: String): Long? = runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()

    /** Record the moment the call first connected (idempotent across rejoins/repeat events). */
    private fun markCallConnected() {
        if (callConnectedAt.value == null) callConnectedAt.value = System.currentTimeMillis()
    }

    private fun startActiveCall(session: CallSession, peerName: String, outgoing: Boolean) {
        if (activeCall.value != null && activeCall.value?.callId != session.callId) return
        // The call is going active — kill any incoming ring + notification so the user isn't left
        // with a second, stale call surface (e.g. after answering from the notification action,
        // which otherwise never cancels the incoming notification).
        CallRinger.stop()
        CallNotifications.cancelIncomingCall(container.appContext)
        finishingCallIds.remove(session.callId)
        activeCallOutgoing = outgoing
        callPeerName.value = peerName
        callStatus.value = if (outgoing) "Calling..." else "Connecting..."
        callMinimized.value = false
        callConnectedAt.value = null
        activeCall.value = session
        // Play the outgoing ringback while we wait for the callee to answer (stopped on connect/end).
        if (outgoing) { startRingTimeout(session.callId); callManager.startRingback() } else cancelRingTimeout()
        // Keep the call alive (mic/camera + process priority) while backgrounded.
        OngoingCallService.start(container.appContext, peerName, isVideo = session.kind == "VIDEO")
    }

    private fun startRingTimeout(callId: String) {
        cancelRingTimeout()
        ringTimeoutJob = viewModelScope.launch {
            delay(45_000)
            if (activeCall.value?.callId == callId && activeCallOutgoing && callStatus.value == "Calling...") {
                callStatus.value = "No answer"
                repo.cancelCall(callId)
                finishCall(delayMs = 1500, callId = callId)
            }
        }
    }

    private fun cancelRingTimeout() {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null
    }

    private fun handleCallEvent(event: SocketService.CallEvent) {
        val currentId = activeCall.value?.callId ?: return
        if (event.callId != currentId) return
        when (event.type) {
            // participant-joined fires on EVERY media-joined — including our own and repeats
            // after a rejoin — so it's filtered to others and applied idempotently. Either
            // event stops the caller's ringback (first member in = the group call is live).
            SocketService.CallEvent.Type.ACCEPT,
            SocketService.CallEvent.Type.PARTICIPANT_JOINED -> {
                if (event.userId != null && event.userId == currentUser.value?.id) return
                cancelRingTimeout()
                callManager.stopRingback()
                if (callStatus.value == "Calling...") callStatus.value = "Connected"
                markCallConnected()
            }
            SocketService.CallEvent.Type.DECLINE -> {
                // In a group, a decline removes just that member; the ring continues.
                if (callIsGroup.value) return
                callStatus.value = "Busy"
                finishCall(delayMs = 1500, callId = currentId)
            }
            SocketService.CallEvent.Type.CANCEL,
            SocketService.CallEvent.Type.END -> finishCall(callId = currentId)
            // In-call membership renders from the LiveKit room, not server fan-out.
            SocketService.CallEvent.Type.PARTICIPANT_LEFT -> Unit
        }
    }

    private fun finishCall(delayMs: Long = 0, callId: String? = activeCall.value?.callId) {
        val id = callId ?: activeCall.value?.callId
        if (id != null && !finishingCallIds.add(id)) return
        cancelRingTimeout()
        callManager.stopRingback()
        if (id == null || activeCall.value?.callId == id) {
            activeCall.value = null
            activeCallOutgoing = false
            callStatus.value = "Ended"
            callIsGroup.value = false
            callMinimized.value = false
            callConnectedAt.value = null
            container.activeCallConversationId.value = null
            OngoingCallService.stop(container.appContext)
        }
        viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            callManager.leave()
            if (id != null) finishingCallIds.remove(id)
        }
    }

    // Wraps a suspend auth call with error handling.
    private fun launchAuth(block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }.onFailure { error.value = str(com.klic.mobile.app.R.string.err_sign_in) }
    }

    // ── v0.5.3 (§10.4): privacy & security ────────────────────────────────────

    val blockedUsers = MutableStateFlow<List<com.klic.mobile.app.data.BlockedUser>>(emptyList())
    val passkeyList = MutableStateFlow<List<com.klic.mobile.app.data.Passkey>>(emptyList())

    fun loadBlocks() = viewModelScope.launch {
        runCatching { repo.blocks() }.onSuccess { blockedUsers.value = it }
    }

    fun blockUser(userId: String, displayName: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        runCatching { repo.blockUser(userId) }
            .onSuccess {
                loadBlocks(); loadFriends(); loadConversations()
                friendStatus.value = str(com.klic.mobile.app.R.string.privacy_blocked_toast, displayName)
                onDone()
            }
            .onFailure { error.value = (str(com.klic.mobile.app.R.string.err_block, displayName) + " " + (it.message ?: "")).trim() }
    }

    fun unblockUser(userId: String) = viewModelScope.launch {
        val before = blockedUsers.value
        blockedUsers.value = before.filterNot { it.user.id == userId }
        runCatching { repo.unblockUser(userId) }
            .onFailure {
                blockedUsers.value = before
                error.value = str(com.klic.mobile.app.R.string.err_unblock)
            }
    }

    fun loadPasskeys() = viewModelScope.launch {
        runCatching { repo.passkeys() }.onSuccess { passkeyList.value = it }
    }

    fun deletePasskey(id: String) = viewModelScope.launch {
        val before = passkeyList.value
        passkeyList.value = before.filterNot { it.id == id }
        runCatching { repo.deletePasskey(id) }
            .onFailure {
                passkeyList.value = before
                error.value = str(com.klic.mobile.app.R.string.err_passkey_remove)
            }
    }

    /** Adds a passkey via CredentialManager; surfaces platform refusals as a toast. */
    fun addPasskey(activityContext: android.content.Context) = viewModelScope.launch {
        runCatching { container.passkeyManager.register(activityContext) }
            .onSuccess { loadPasskeys(); friendStatus.value = null }
            .onFailure { error.value = it.message ?: str(com.klic.mobile.app.R.string.err_passkey_add) }
    }

    /** Passkey sign-in from the login screen (§10.4). */
    fun loginWithPasskey(activityContext: android.content.Context) = viewModelScope.launch {
        runCatching { container.passkeyManager.signIn(activityContext) }
            .onSuccess { currentUser.value = it; onAuthed() }
            .onFailure { error.value = it.message ?: str(com.klic.mobile.app.R.string.err_passkey_login) }
    }

    /** "Automatically delete my account" window (months, null = off). */
    fun setDeleteIfAwayMonths(months: Int?) = viewModelScope.launch {
        runCatching { repo.setDeleteIfAwayMonths(months) }
            .onSuccess { currentUser.value = it }
            .onFailure { error.value = str(com.klic.mobile.app.R.string.err_auto_delete) }
    }

    /** "Delete Account Now": DELETE /me → wipe local state + sign out. */
    fun deleteAccount(onDone: () -> Unit) = viewModelScope.launch {
        runCatching { repo.deleteAccount() }
            .onSuccess {
                SettingsStore.deleteAllDrafts()
                // §13.12: full wipe (passcode + biometric toggle + auto-lock mode).
                com.klic.mobile.app.data.AppLockStore.wipe()
                repo.logout()
                socket.disconnect()
                conversations.value = emptyList()
                messages.value = emptyList()
                friends.value = emptyList()
                currentUser.value = null
                isAuthenticated.value = false
                onDone()
            }
            .onFailure { error.value = (str(com.klic.mobile.app.R.string.err_delete_account) + " " + (it.message ?: "")).trim() }
    }

    /** Sync Contacts ON: hash device emails/phones and upload (hashes only). */
    fun syncContactsNow(context: android.content.Context) = viewModelScope.launch {
        val hashes = com.klic.mobile.app.data.ContactsSync.collectHashes(context)
        runCatching { repo.uploadContactHashes(hashes) }
            .onSuccess {
                SettingsStore.setContactsSyncEnabled(true)
                friendStatus.value = null
            }
            .onFailure {
                SettingsStore.setContactsSyncEnabled(false)
                error.value = (str(com.klic.mobile.app.R.string.err_contact_sync) + " " + (it.message ?: "")).trim()
            }
    }

    fun deleteSyncedContacts() = viewModelScope.launch {
        runCatching { repo.deleteContactHashes() }
            .onSuccess { SettingsStore.setContactsSyncEnabled(false) }
            .onFailure { error.value = str(com.klic.mobile.app.R.string.err_delete_contacts) }
    }

    // ── v0.5.5 (§12.1): safety reports ────────────────────────────────────────

    /**
     * POST /reports (user, message, or target-less app problem). Returns null on
     * success, or a user-facing error message (server text when available, e.g. the
     * daily report cap).
     */
    suspend fun submitReport(
        category: String,
        targetUserId: String? = null,
        messageId: String? = null,
        details: String? = null,
    ): String? = runCatching {
        repo.submitReport(category, targetUserId, messageId, details)
    }.fold(
        onSuccess = { null },
        onFailure = { repo.serverMessage(it) ?: str(com.klic.mobile.app.R.string.report_failed) },
    )

    // ── v0.5.5 (§12.2): email add/verify via Google ───────────────────────────

    /** True while the Google picker/link roundtrip is in flight (email row spinner). */
    val emailBusy = MutableStateFlow(false)

    /** Refresh the self user from GET /me (email/emailVerified now included). */
    fun refreshMe() = viewModelScope.launch {
        runCatching { repo.refreshMe() }.onSuccess { currentUser.value = it }
    }

    /** "Add email": Google account picker → POST /me/email/google → refreshed user. */
    fun linkGoogleEmail(activityContext: android.content.Context) = viewModelScope.launch {
        if (emailBusy.value) return@launch
        emailBusy.value = true
        runCatching { container.googleEmailManager.link(activityContext) }
            .onSuccess { currentUser.value = it }
            .onFailure { e ->
                // A user-cancelled picker is not an error worth toasting.
                val cancelled = (e as? com.klic.mobile.app.data.GoogleEmailException)?.cancelled == true
                if (!cancelled) {
                    error.value = e.message ?: str(com.klic.mobile.app.R.string.email_link_failed)
                }
            }
        emailBusy.value = false
    }

    /** Remove the linked email (DELETE /me/email) after the confirm dialog. */
    fun removeEmail() = viewModelScope.launch {
        runCatching { repo.removeEmail() }
            .onSuccess { currentUser.value = it }
            .onFailure { error.value = str(com.klic.mobile.app.R.string.email_remove_failed) }
    }

    // ── v0.5.3 (§10.4): drafts + frequent contacts ────────────────────────────

    /** Persist the composer draft for a conversation (cleared when blank). */
    fun saveDraft(conversationId: String, text: String?) = viewModelScope.launch {
        SettingsStore.setDraft(conversationId, text)
    }

    fun deleteAllDrafts() = viewModelScope.launch { SettingsStore.deleteAllDrafts() }

    /** Most-messaged friend ids (locally counted sends) — the "Frequent" picker row. */
    fun frequentFriendIds(limit: Int = 6): List<String> {
        if (!SettingsStore.snapshot.value.suggestFrequentContacts) return emptyList()
        val counts = SettingsStore.snapshot.value.sentCounts
        return conversations.value
            .filter { it.type == "DIRECT" }
            .mapNotNull { c -> c.members.firstOrNull()?.id?.let { it to (counts[c.id] ?: 0L) } }
            .filter { it.second > 0L }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    private fun bumpSent(conversationId: String) {
        viewModelScope.launch { SettingsStore.bumpSentCount(conversationId) }
    }
}

/**
 * One optimistic upload (§9.1): shows as a pill bubble in its conversation with a real
 * byte-level progress bar; on failure it stays with retry/discard. The staged attachments
 * (with localBytes) are kept so a retry re-uploads without re-reading the source Uri.
 */
data class UploadTask(
    val id: String,
    val conversationId: String,
    val body: String?,
    val attachments: List<AttachmentInput>,
    val replyToId: String?,
    val progress: Float = 0f,
    val failed: Boolean = false,
    /** §13.15: human-readable failure reason (size cap vs network), when known. */
    val errorMessage: String? = null,
)
