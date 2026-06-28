package com.klic.app.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.klic.app.calling.CallManager
import com.klic.app.data.CallSession
import com.klic.app.data.Conversation
import com.klic.app.data.FriendRequest
import com.klic.app.data.KlicRepository
import com.klic.app.data.Message
import com.klic.app.data.TokenStore
import com.klic.app.data.User
import com.klic.app.realtime.SocketService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class KlicViewModel(
    private val repo: KlicRepository,
    private val tokenStore: TokenStore,
    private val socket: SocketService,
    val callManager: CallManager,
) : ViewModel() {

    val currentUser = MutableStateFlow<User?>(null)
    val isAuthenticated = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    val conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val messages = MutableStateFlow<List<Message>>(emptyList())
    val activeCall = MutableStateFlow<CallSession?>(null)
    val callPeerName = MutableStateFlow("")

    val friends = MutableStateFlow<List<User>>(emptyList())
    val friendRequests = MutableStateFlow<List<FriendRequest>>(emptyList())
    val friendStatus = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            tokenStore.load()
            if (repo.isAuthenticated) onAuthed()
        }
        viewModelScope.launch {
            socket.incomingMessages.collect { msg ->
                if (msg.conversationId == openConversationId) messages.value = messages.value + msg
            }
        }
    }

    private var openConversationId: String? = null

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

    fun startCall(conversationId: String, kind: String, peerName: String) = viewModelScope.launch {
        callPeerName.value = peerName
        runCatching { repo.startCall(conversationId, kind) }.onSuccess { activeCall.value = it }
    }

    /** Answer an incoming call (from the full-screen notification): fetch a join token. */
    fun acceptIncomingCall(callId: String, peerName: String) = viewModelScope.launch {
        callPeerName.value = peerName
        runCatching { repo.joinToken(callId) }.onSuccess { activeCall.value = it }
    }

    fun endCall() {
        val id = activeCall.value?.callId
        callManager.leave()
        activeCall.value = null
        if (id != null) viewModelScope.launch { repo.endCall(id) }
    }

    private fun onAuthed() {
        isAuthenticated.value = true
        currentUser.value = repo.currentUser
        tokenStore.cachedAccess?.let { socket.connect(it) }
        loadConversations()
    }

    /** Emit a read receipt for the open conversation. */
    fun markRead(conversationId: String) {
        socket.emit("message:read", buildJsonObject { put("conversationId", conversationId) })
    }

    // Wraps a suspend auth call with error handling.
    private fun launchAuth(block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }.onFailure { error.value = "Could not sign in. Check your details." }
    }
}
