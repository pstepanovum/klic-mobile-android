package com.klic.mobile.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Single entry point for the UI to reach the API + token storage. */
class KlicRepository(
    private val api: KlicApi,
    private val tokenStore: TokenStore,
) {
    private val json = Json { ignoreUnknownKeys = true }
    // Bare client for presigned PUT uploads — no auth header, no base URL.
    private val uploader = OkHttpClient()

    var currentUser: User? = null
        private set

    /** E2EE bridge — set by the container right after construction. */
    var e2ee: E2eeMessaging? = null

    // Authenticated as long as we hold a refresh token — the access token may be
    // expired but is renewable, so a stale access token must not look like a sign-out.
    val isAuthenticated: Boolean get() = tokenStore.hasSession

    suspend fun register(username: String, password: String, displayName: String): User {
        val res = api.register(RegisterRequest(username, password, displayName))
        persist(res)
        return res.user
    }

    suspend fun login(username: String, password: String): User {
        val res = api.login(LoginRequest(username, password))
        persist(res)
        return res.user
    }

    /** Optimistic restore: load the cached user so the UI has it instantly on launch. */
    suspend fun restoreCachedUser() {
        currentUser = tokenStore.loadUser()?.let {
            runCatching { json.decodeFromString(User.serializer(), it) }.getOrNull()
        }
    }

    /** Renew the access token if it's missing/expired — needed before opening the socket. */
    suspend fun ensureFreshToken() {
        if (!AccessToken.isExpired(tokenStore.cachedAccess)) return
        val refresh = tokenStore.cachedRefresh ?: return
        val res = api.refresh(RefreshRequest(refresh))
        tokenStore.save(res.accessToken, res.refreshToken)
    }

    suspend fun logout() {
        tokenStore.clear()
        currentUser = null
    }

    // ── Profile ──────────────────────────────────────────────────────────────
    suspend fun updateProfile(
        displayName: String? = null,
        showLastSeen: Boolean? = null,
        avatarKey: String? = null,
    ): User {
        val body = JsonObject(buildMap {
            displayName?.let { put("displayName", JsonPrimitive(it)) }
            showLastSeen?.let { put("showLastSeen", JsonPrimitive(it)) }
            avatarKey?.let { put("avatarKey", JsonPrimitive(it)) }
        })
        val user = api.updateProfile(body)
        currentUser = user
        tokenStore.saveUser(json.encodeToString(User.serializer(), user))
        return user
    }

    /** PUT raw bytes to a presigned URL off the main thread; throws on a non-2xx response.
     *  The blocking OkHttp call MUST run on Dispatchers.IO — callers launch on viewModelScope
     *  (Main), so executing here directly would crash with NetworkOnMainThreadException. */
    private suspend fun putToPresignedUrl(uploadUrl: String, bytes: ByteArray, contentType: String, label: String) =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(uploadUrl)
                .put(bytes.toRequestBody(contentType.toMediaType()))
                .build()
            uploader.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("$label upload failed (${resp.code})")
            }
        }

    /** Upload new avatar bytes and return the object key to attach via [updateProfile]. */
    suspend fun uploadAvatar(bytes: ByteArray, contentType: String): String {
        val ticket = api.avatarUpload(AvatarUploadRequest(contentType, bytes.size))
        putToPresignedUrl(ticket.uploadUrl, bytes, contentType, "Avatar")
        return ticket.key
    }

    suspend fun userProfile(id: String): UserProfile = api.userProfile(id)

    suspend fun registerDevice(pushToken: String) {
        runCatching { api.registerDevice(mapOf("platform" to "ANDROID", "pushToken" to pushToken)) }
    }

    suspend fun mobileDiagnostic(event: String, callId: String? = null, detail: String? = null) {
        runCatching {
            api.mobileDiagnostic(MobileDiagnosticRequest(event = event, callId = callId, detail = detail))
        }
    }

    suspend fun endCall(callId: String) { runCatching { api.endCall(callId) } }
    suspend fun mediaJoined(callId: String) { runCatching { api.mediaJoined(callId) } }
    suspend fun declineCall(callId: String) { runCatching { api.declineCall(callId) } }
    suspend fun cancelCall(callId: String) { runCatching { api.cancelCall(callId) } }
    suspend fun failCall(callId: String) { runCatching { api.failCall(callId) } }

    suspend fun friends(): List<User> = api.friends()
    suspend fun friendRequests(): List<FriendRequest> = api.friendRequests()
    suspend fun findUser(username: String): User? = api.findUser(username).firstOrNull()
    suspend fun sendFriendRequest(userId: String) { api.sendFriendRequest(mapOf("userId" to userId)) }
    suspend fun acceptFriendRequest(id: String) { api.acceptFriendRequest(id) }
    suspend fun declineFriendRequest(id: String) { api.declineFriendRequest(id) }
    suspend fun openConversation(userId: String): Conversation =
        api.createConversation(CreateConversationRequest(userId = userId))

    suspend fun createGroupConversation(title: String, userIds: List<String>): Conversation =
        api.createConversation(CreateConversationRequest(title = title, userIds = userIds))

    suspend fun conversations(): List<Conversation> {
        val raw = api.conversations()
        val bridge = e2ee ?: return raw
        return raw.map { convo ->
            convo.lastMessage
                ?.takeIf { it.kind == "CIPHERTEXT" }
                ?.let { convo.copy(lastMessage = bridge.materialize(it, currentUser?.id)) }
                ?: convo
        }
    }
    suspend fun messages(conversationId: String, before: String? = null): List<Message> =
        api.messages(conversationId, before = before)
    suspend fun send(conversationId: String, body: String, replyToId: String? = null): Message {
        val bridge = e2ee
        if (E2eeConfig.SEND_ENABLED && bridge != null) {
            // Reply quotes travel inside the ciphertext at the cutover; plaintext
            // replyToId is dropped then. Until the flag flips this path is dormant.
            return bridge.sendText(conversationId, body)
        }
        return sendLegacy(conversationId, body, replyToId)
    }

    private suspend fun sendLegacy(conversationId: String, body: String, replyToId: String? = null): Message =
        api.send(conversationId, SendMessageRequest(body, replyToId))

    suspend fun react(conversationId: String, messageId: String, emoji: String): List<Reaction> =
        api.react(conversationId, messageId, ReactionRequest(emoji)).reactions

    suspend fun deleteForEveryone(conversationId: String, messageId: String) {
        runCatching { api.deleteMessage(conversationId, messageId, "everyone") }
    }

    suspend fun recentCalls(): List<RecentCall> = api.recentCalls()
    suspend fun stickers(): List<Sticker> = api.stickers().stickers
    suspend fun sendSticker(conversationId: String, stickerId: String, replyToId: String? = null): Message =
        api.sendSticker(conversationId, SendStickerRequest(stickerId, replyToId))

    suspend fun uploadVoice(
        conversationId: String,
        bytes: ByteArray,
        durationMs: Int,
        waveform: ByteArray,
    ): Message {
        val contentType = "audio/m4a"
        diagnostic("upload.voice.presign.start", "bytes=${bytes.size} durationMs=$durationMs")
        val ticket = api.requestUpload(UploadRequest(conversationId, "VOICE", contentType, bytes.size))
        diagnostic("upload.voice.presign.ok", "bytes=${bytes.size}")
        diagnostic("upload.voice.put.start", "bytes=${bytes.size}")
        runCatching {
            putToPresignedUrl(ticket.uploadUrl, bytes, contentType, "Voice")
        }.onFailure {
            diagnostic("upload.voice.put.failed", it.message ?: it::class.java.simpleName)
            throw it
        }
        diagnostic("upload.voice.put.ok", "bytes=${bytes.size}")
        diagnostic("upload.voice.message.start")
        return runCatching {
            api.sendMessage(conversationId, SendWithAttachmentsRequest(
                attachments = listOf(AttachmentInput(
                    key = ticket.key,
                    kind = "VOICE",
                    contentType = contentType,
                    byteSize = bytes.size,
                    durationMs = durationMs,
                    waveform = android.util.Base64.encodeToString(waveform, android.util.Base64.NO_WRAP),
                ))
            ))
        }.onSuccess {
            diagnostic("upload.voice.message.ok", it.id)
        }.onFailure {
            diagnostic("upload.voice.message.failed", it.message ?: it::class.java.simpleName)
        }.getOrThrow()
    }

    suspend fun uploadImage(
        conversationId: String,
        bytes: ByteArray,
        contentType: String,
        width: Int? = null,
        height: Int? = null,
    ): Message {
        val normalizedType = contentType.ifBlank { "image/jpeg" }
        diagnostic("upload.image.presign.start", "type=$normalizedType bytes=${bytes.size}")
        val ticket = api.requestUpload(UploadRequest(conversationId, "IMAGE", normalizedType, bytes.size))
        diagnostic("upload.image.presign.ok", "type=$normalizedType bytes=${bytes.size}")
        diagnostic("upload.image.put.start", "bytes=${bytes.size}")
        runCatching {
            putToPresignedUrl(ticket.uploadUrl, bytes, normalizedType, "Image")
        }.onFailure {
            diagnostic("upload.image.put.failed", it.message ?: it::class.java.simpleName)
            throw it
        }
        diagnostic("upload.image.put.ok", "bytes=${bytes.size}")
        diagnostic("upload.image.message.start")
        return runCatching {
            api.sendMessage(conversationId, SendWithAttachmentsRequest(
                attachments = listOf(AttachmentInput(
                    key = ticket.key,
                    kind = "IMAGE",
                    contentType = normalizedType,
                    byteSize = bytes.size,
                    width = width,
                    height = height,
                ))
            ))
        }.onSuccess {
            diagnostic("upload.image.message.ok", it.id)
        }.onFailure {
            diagnostic("upload.image.message.failed", it.message ?: it::class.java.simpleName)
        }.getOrThrow()
    }

    /** Uploads one or more staged attachments (mixed images/videos/files) and sends them as
     *  a single message — each attachment is presigned/PUT using its own kind/contentType,
     *  not hardcoded to "IMAGE" the way the old image-only path was. */
    suspend fun uploadAttachments(
        conversationId: String,
        attachments: List<AttachmentInput>,
        body: String? = null,
        replyToId: String? = null,
    ): Message {
        require(attachments.isNotEmpty()) { "attachments must not be empty" }
        val uploaded = mutableListOf<AttachmentInput>()
        for (attachment in attachments) {
            val normalizedType = attachment.contentType.ifBlank { "application/octet-stream" }
            diagnostic("upload.${attachment.kind}.presign.start", "type=$normalizedType bytes=${attachment.byteSize}")
            val ticket = api.requestUpload(UploadRequest(conversationId, attachment.kind, normalizedType, attachment.byteSize))
            diagnostic("upload.${attachment.kind}.presign.ok", "type=$normalizedType bytes=${attachment.byteSize}")
            diagnostic("upload.${attachment.kind}.put.start", "bytes=${attachment.byteSize}")
            val bytes = requireNotNull(attachment.localBytes) { "AttachmentInput.localBytes required for upload" }
            runCatching {
                putToPresignedUrl(ticket.uploadUrl, bytes, normalizedType, attachment.kind)
            }.onFailure {
                diagnostic("upload.${attachment.kind}.put.failed", it.message ?: it::class.java.simpleName)
                throw it
            }
            diagnostic("upload.${attachment.kind}.put.ok", "bytes=${attachment.byteSize}")
            uploaded += attachment.copy(key = ticket.key, localBytes = null)
        }
        diagnostic("upload.attachments.message.start")
        return runCatching {
            api.sendMessage(conversationId, SendWithAttachmentsRequest(
                body = body,
                attachments = uploaded,
                replyToId = replyToId,
            ))
        }.onSuccess {
            diagnostic("upload.attachments.message.ok", it.id)
        }.onFailure {
            diagnostic("upload.attachments.message.failed", it.message ?: it::class.java.simpleName)
        }.getOrThrow()
    }

    private suspend fun diagnostic(event: String, detail: String? = null) {
        runCatching {
            api.mobileDiagnostic(MobileDiagnosticRequest(source = "android", event = event, detail = detail))
        }
    }

    suspend fun startCall(conversationId: String, kind: String): CallSession =
        api.startCall(StartCallRequest(conversationId, kind))

    suspend fun joinToken(callId: String): CallSession = api.joinToken(callId)

    /** The conversation's live call, or null when there is none (the endpoint 404s). */
    suspend fun activeCall(conversationId: String): ActiveCallInfo? =
        try {
            api.activeCall(conversationId)
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 404) null else throw e
        }

    private suspend fun persist(res: AuthResponse) {
        tokenStore.save(res.accessToken, res.refreshToken)
        tokenStore.saveUser(json.encodeToString(User.serializer(), res.user))
        currentUser = res.user
    }
}
