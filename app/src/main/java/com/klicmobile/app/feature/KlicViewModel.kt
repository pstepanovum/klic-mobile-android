package com.klicmobile.app.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.klicmobile.app.calling.CallManager
import com.klicmobile.app.data.CallSession
import com.klicmobile.app.data.Conversation
import com.klicmobile.app.data.FriendRequest
import com.klicmobile.app.data.KlicRepository
import com.klicmobile.app.data.Message
import com.klicmobile.app.data.RecentCall
import com.klicmobile.app.data.Sticker
import com.klicmobile.app.data.TokenStore
import com.klicmobile.app.data.User
import com.klicmobile.app.data.UserProfile
import com.klicmobile.app.realtime.SocketService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val container: com.klicmobile.app.AppContainer,
) : ViewModel() {

    val currentUser = MutableStateFlow<User?>(null)
    val isAuthenticated = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val themeMode = MutableStateFlow(container.themeMode)

    val conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val messages = MutableStateFlow<List<Message>>(emptyList())
    val presence = socket.presence   // userId -> online / last-seen
    val activeCall = MutableStateFlow<CallSession?>(null)
    val callPeerName = MutableStateFlow("")
    val callPeerId = MutableStateFlow<String?>(null)
    val callStatus = MutableStateFlow("Calling...")

    val friends = MutableStateFlow<List<User>>(emptyList())
    val friendRequests = MutableStateFlow<List<FriendRequest>>(emptyList())
    val friendStatus = MutableStateFlow<String?>(null)

    val recentCalls = MutableStateFlow<List<RecentCall>>(emptyList())
    val stickers = MutableStateFlow<List<Sticker>>(emptyList())

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
                if (msg.conversationId == openConversationId) messages.value = messages.value + msg
            }
        }
        viewModelScope.launch {
            socket.callEvents.collect { event ->
                handleCallEvent(event)
            }
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

    fun callFriendDirect(userId: String, kind: String, peerName: String, onStarted: () -> Unit) =
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
                onStarted()
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

    fun openChat(conversationId: String) = viewModelScope.launch {
        openConversationId = conversationId
        runCatching { repo.messages(conversationId) }
            .onSuccess { messages.value = it.reversed() }
    }

    fun send(conversationId: String, body: String) = viewModelScope.launch {
        runCatching { repo.send(conversationId, body) }
            .onSuccess { messages.value = messages.value + it }
    }

    fun sendSticker(conversationId: String, stickerId: String) = viewModelScope.launch {
        runCatching { repo.sendSticker(conversationId, stickerId) }
            .onSuccess { messages.value = messages.value + it }
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
            runCatching { repo.uploadVoice(conversationId, bytes, durationMs, waveform) }
                .onSuccess { messages.value = messages.value + it }
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
        runCatching { repo.joinToken(callId) }
            .onSuccess { startActiveCall(it, peerName, outgoing = false) }
            .onFailure {
                socket.emit("call:decline", buildJsonObject { put("callId", callId) })
                callStatus.value = "Call failed"
                finishCall(delayMs = 1200)
            }
    }

    fun endCall() {
        val id = activeCall.value?.callId
        if (id != null) {
            socket.emit(
                if (callStatus.value == "Connected") "call:end" else "call:cancel",
                buildJsonObject { put("callId", id) },
            )
            viewModelScope.launch { repo.endCall(id) }
        }
        finishCall(callId = id)
    }

    fun onCallMediaJoined(callId: String) {
        if (activeCall.value?.callId != callId) return
        if (activeCallOutgoing) return
        callStatus.value = "Connected"
        socket.emit("call:accept", buildJsonObject { put("callId", callId) })
    }

    fun onCallJoinFailed(callId: String) {
        if (activeCall.value?.callId != callId) return
        callStatus.value = "Call failed"
        socket.emit("call:decline", buildJsonObject { put("callId", callId) })
        finishCall(delayMs = 1200, callId = callId)
    }

    private fun onAuthed() {
        isAuthenticated.value = true
        currentUser.value = repo.currentUser
        tokenStore.cachedAccess?.let { socket.connect(it) }
        loadConversations()
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
        finishingCallIds.remove(session.callId)
        activeCallOutgoing = outgoing
        callPeerName.value = peerName
        callStatus.value = if (outgoing) "Calling..." else "Connecting..."
        activeCall.value = session
        if (outgoing) startRingTimeout(session.callId) else cancelRingTimeout()
    }

    private fun startRingTimeout(callId: String) {
        cancelRingTimeout()
        ringTimeoutJob = viewModelScope.launch {
            delay(45_000)
            if (activeCall.value?.callId == callId && activeCallOutgoing && callStatus.value == "Calling...") {
                callStatus.value = "No answer"
                socket.emit("call:cancel", buildJsonObject { put("callId", callId) })
                repo.endCall(callId)
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
                callStatus.value = "Connected"
            }
            SocketService.CallEvent.Type.DECLINE -> {
                callStatus.value = "Busy"
                viewModelScope.launch { repo.endCall(currentId) }
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
        if (id == null || activeCall.value?.callId == id) {
            activeCall.value = null
            activeCallOutgoing = false
            callStatus.value = "Ended"
            container.activeCallConversationId.value = null
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
