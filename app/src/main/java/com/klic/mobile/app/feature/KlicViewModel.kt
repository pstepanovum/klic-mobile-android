package com.klic.mobile.app.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.klic.mobile.app.calling.CallManager
import com.klic.mobile.app.calling.CallNotifications
import com.klic.mobile.app.calling.CallRinger
import com.klic.mobile.app.calling.OngoingCallService
import com.klic.mobile.app.data.AttachmentInput
import com.klic.mobile.app.data.CallSession
import com.klic.mobile.app.data.Conversation
import com.klic.mobile.app.data.FriendRequest
import com.klic.mobile.app.data.KlicRepository
import com.klic.mobile.app.data.Message
import com.klic.mobile.app.data.RecentCall
import com.klic.mobile.app.data.Sticker
import com.klic.mobile.app.data.TokenStore
import com.klic.mobile.app.data.User
import com.klic.mobile.app.data.UserProfile
import com.klic.mobile.app.realtime.SocketService
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
        }
        viewModelScope.launch {
            socket.incomingMessages.collect { msg ->
                // Upsert — the server echoes our own sends back for multi-device sync.
                if (msg.conversationId == openConversationId) upsertMessage(msg)
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
            callManager.remoteParticipantDisconnected.collect {
                val id = activeCall.value?.callId ?: return@collect
                if (callStatus.value == "Connected") {
                    repo.endCall(id)
                    finishCall(callId = id)
                }
            }
        }
        viewModelScope.launch {
            callManager.isReconnecting.collect { reconnecting ->
                if (activeCall.value == null) return@collect
                if (reconnecting) callStatus.value = "Reconnecting…"
                else if (callStatus.value == "Reconnecting…") callStatus.value = "Connected"
            }
        }
        viewModelScope.launch {
            callManager.networkDisconnected.collect {
                // LiveKit gave up reconnecting → end the call (best-effort server notify).
                if (activeCall.value != null) endCall()
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
    }

    private var openConversationId: String? = null
    private var activeCallOutgoing = false
    private var ringTimeoutJob: Job? = null
    private val finishingCallIds = mutableSetOf<String>()

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
        isAuthenticated.value = false
    }

    fun setThemeMode(mode: String) {
        themeMode.value = mode
        container.themeMode = mode
    }

    fun callFriendDirect(userId: String, kind: String, peerName: String) =
        viewModelScope.launch {
            if (activeCall.value != null) return@launch
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
        runCatching { repo.conversations() }.onSuccess { conversations.value = it }
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
            friendStatus.value = "No user named \"$name\"."
        } else {
            runCatching { repo.sendFriendRequest(user.id) }
            friendStatus.value = "Request sent to ${user.displayName}."
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

    fun createGroupConversation(title: String, userIds: List<String>, onReady: (Conversation) -> Unit) =
        viewModelScope.launch {
            runCatching { repo.createGroupConversation(title.trim(), userIds.distinct()) }.onSuccess { c ->
                conversations.value = listOf(c) + conversations.value.filterNot { it.id == c.id }
                onReady(c)
            }.onFailure {
                error.value = "Couldn't create group chat. Try again."
            }
        }

    fun openChat(conversationId: String) = viewModelScope.launch {
        openConversationId = conversationId
        replyingTo.value = null
        hasMoreMessages.value = false
        isLoadingOlderMessages.value = false
        // Clear any pending notification (and its launcher badge) for this conversation.
        CallNotifications.cancelMessage(container.appContext, conversationId)
        runCatching { repo.messages(conversationId) }
            .onSuccess { msgs ->
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
            .onSuccess { upsertMessage(it) }
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
                .onFailure { error.value = "Couldn't send voice message. Try again." }
        }

    fun sendImage(conversationId: String, bytes: ByteArray, contentType: String, width: Int? = null, height: Int? = null) =
        viewModelScope.launch {
            replyingTo.value = null
            runCatching { repo.uploadImage(conversationId, bytes, contentType, width, height) }
                .onSuccess { upsertMessage(it) }
                .onFailure { error.value = "Couldn't send photo. Try again." }
        }

    fun sendAttachments(conversationId: String, body: String?, attachments: List<AttachmentInput>) =
        viewModelScope.launch {
            val replyId = replyingTo.value?.id
            replyingTo.value = null
            runCatching { repo.uploadAttachments(conversationId, attachments, body?.takeIf { it.isNotBlank() }, replyId) }
                .onSuccess { upsertMessage(it) }
                .onFailure { error.value = "Couldn't send attachment. Try again." }
        }

    fun startCall(conversationId: String, kind: String, peerName: String) = viewModelScope.launch {
        if (activeCall.value != null) return@launch
        callPeerName.value = peerName
        callPeerId.value = conversations.value.firstOrNull { it.id == conversationId }?.members?.firstOrNull()?.id
        runCatching { repo.startCall(conversationId, kind) }
            .onSuccess {
                container.activeCallConversationId.value = conversationId
                startActiveCall(it, peerName, outgoing = true)
            }
    }

    /** Answer an incoming call (from the full-screen notification): fetch a join token. */
    fun acceptIncomingCall(callId: String, peerName: String) = viewModelScope.launch {
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

    fun endCall() {
        val id = activeCall.value?.callId
        if (id != null) {
            val wasConnected = callStatus.value == "Connected"
            val wasOutgoing = activeCallOutgoing
            viewModelScope.launch {
                when {
                    wasConnected -> repo.endCall(id)
                    wasOutgoing -> repo.cancelCall(id)
                    else -> repo.declineCall(id)
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
        isAuthenticated.value = false
    }

    /** Emit a read receipt for the open conversation. */
    fun markRead(conversationId: String) {
        socket.emit("message:read", buildJsonObject { put("conversationId", conversationId) })
    }

    // ── Profile ───────────────────────────────────────────────────────────────
    fun saveProfile(displayName: String, avatarBytes: ByteArray?, contentType: String?, onDone: () -> Unit) =
        viewModelScope.launch {
            runCatching {
                val key = if (avatarBytes != null && contentType != null) {
                    repo.uploadAvatar(avatarBytes, contentType)
                } else null
                repo.updateProfile(displayName = displayName, avatarKey = key)
            }.onSuccess { currentUser.value = it; onDone() }
                .onFailure { error.value = "Couldn't save profile. Try again." }
        }

    fun setShowLastSeen(value: Boolean) = viewModelScope.launch {
        runCatching { repo.updateProfile(showLastSeen = value) }.onSuccess { currentUser.value = it }
    }

    suspend fun fetchProfile(userId: String): UserProfile? =
        runCatching { repo.userProfile(userId) }.getOrNull()

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
            SocketService.CallEvent.Type.ACCEPT -> {
                cancelRingTimeout()
                callManager.stopRingback()
                callStatus.value = "Connected"
            }
            SocketService.CallEvent.Type.DECLINE -> {
                callStatus.value = "Busy"
                finishCall(delayMs = 1500, callId = currentId)
            }
            SocketService.CallEvent.Type.CANCEL,
            SocketService.CallEvent.Type.END -> finishCall(callId = currentId)
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
        runCatching { block() }.onFailure { error.value = "Could not sign in. Check your details." }
    }
}
