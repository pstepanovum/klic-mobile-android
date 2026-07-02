package com.klic.mobile.app.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.klic.mobile.app.calling.CallManager
import com.klic.mobile.app.calling.CallNotifications
import com.klic.mobile.app.calling.CallRinger
import com.klic.mobile.app.calling.OngoingCallService
import com.klic.mobile.app.data.ActiveCallInfo
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
    }

    private var openConversationId: String? = null
    private var activeCallOutgoing = false
    private var ringTimeoutJob: Job? = null
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
        isAuthenticated.value = false
    }

    fun setThemeMode(mode: String) {
        themeMode.value = mode
        container.themeMode = mode
    }

    fun callFriendDirect(userId: String, kind: String, peerName: String) =
        viewModelScope.launch {
            if (activeCall.value != null) return@launch
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
        chatActiveCall.value = null
        refreshActiveCall(conversationId)
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

    fun endCall() {
        val id = activeCall.value?.callId
        if (id != null) {
            // "Reconnecting…" is still a live call (media was up) — hang-up must END it,
            // not cancel/decline, so the outcome records as completed.
            val wasConnected = callStatus.value == "Connected" || callStatus.value == "Reconnecting…"
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
            // participant-joined fires on EVERY media-joined — including our own and repeats
            // after a rejoin — so it's filtered to others and applied idempotently. Either
            // event stops the caller's ringback (first member in = the group call is live).
            SocketService.CallEvent.Type.ACCEPT,
            SocketService.CallEvent.Type.PARTICIPANT_JOINED -> {
                if (event.userId != null && event.userId == currentUser.value?.id) return
                cancelRingTimeout()
                callManager.stopRingback()
                if (callStatus.value == "Calling...") callStatus.value = "Connected"
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
