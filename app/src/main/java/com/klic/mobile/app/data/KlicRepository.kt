package com.klic.mobile.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
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
    /** App context — resolves staged content Uris for streamed uploads (§13.15). */
    private val appContext: android.content.Context? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    // Bare client for presigned PUT uploads — no auth header, no base URL.
    // Carries the data-usage interceptor so uploads are attributed by media kind (§8.3).
    // §13.15: generous timeouts so a multi-hundred-MB video on a slow uplink can finish —
    // the write timeout only trips on a fully stalled socket, never on slow progress,
    // and there is deliberately no whole-call timeout.
    private val uploader = OkHttpClient.Builder()
        .addInterceptor(DataUsage.interceptor)
        .connectTimeout(java.time.Duration.ofSeconds(30))
        .writeTimeout(java.time.Duration.ofMinutes(5))
        .readTimeout(java.time.Duration.ofMinutes(5))
        .build()

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
        username: String? = null,
        setAbout: Boolean = false,
        about: String? = null,
        links: List<String>? = null,
    ): User {
        val body = JsonObject(buildMap {
            displayName?.let { put("displayName", JsonPrimitive(it)) }
            showLastSeen?.let { put("showLastSeen", JsonPrimitive(it)) }
            avatarKey?.let { put("avatarKey", JsonPrimitive(it)) }
            username?.let { put("username", JsonPrimitive(it)) }
            if (setAbout) put("about", about?.let { JsonPrimitive(it) } ?: JsonNull)
            links?.let { list ->
                put("links", kotlinx.serialization.json.JsonArray(list.map { JsonPrimitive(it) }))
            }
        })
        return patchMe(body)
    }

    /** §11.6: PATCH /me with one privacy field (visibility enum string or toggle). */
    suspend fun updatePrivacySetting(field: String, value: String): User =
        patchMe(JsonObject(mapOf(field to JsonPrimitive(value))))

    suspend fun updatePrivacySetting(field: String, value: Boolean): User =
        patchMe(JsonObject(mapOf(field to JsonPrimitive(value))))

    private suspend fun patchMe(body: JsonObject): User {
        val user = api.updateProfile(body)
        currentUser = user
        tokenStore.saveUser(json.encodeToString(User.serializer(), user))
        return user
    }

    /** Best-effort extraction of the server's error message (e.g. "Username is taken"). */
    fun serverMessage(e: Throwable): String? {
        val http = e as? retrofit2.HttpException ?: return null
        return runCatching {
            val raw = http.response()?.errorBody()?.string() ?: return null
            val obj = json.parseToJsonElement(raw) as? JsonObject ?: return null
            (obj["message"] as? JsonPrimitive)?.content
                ?: (obj["error"] as? JsonPrimitive)?.content
        }.getOrNull()
    }

    /** PUT raw bytes to a presigned URL off the main thread; throws on a non-2xx response.
     *  The blocking OkHttp call MUST run on Dispatchers.IO — callers launch on viewModelScope
     *  (Main), so executing here directly would crash with NetworkOnMainThreadException.
     *  [onProgress] (bytes written so far) drives the upload pill's progress bar (§9.1). */
    private suspend fun putToPresignedUrl(
        uploadUrl: String,
        bytes: ByteArray,
        contentType: String,
        label: String,
        onProgress: ((Long) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        val body = if (onProgress != null) {
            ProgressRequestBody(bytes, contentType.toMediaType(), onProgress)
        } else {
            bytes.toRequestBody(contentType.toMediaType())
        }
        executePut(uploadUrl, body, label)
    }

    /** §13.15: PUT a staged attachment streamed from its content Uri — no buffering. */
    private suspend fun putUriToPresignedUrl(
        uploadUrl: String,
        uri: String,
        contentType: String,
        byteSize: Long,
        label: String,
        onProgress: ((Long) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        val resolver = requireNotNull(appContext) { "appContext required for streamed uploads" }.contentResolver
        val body = UriStreamRequestBody(
            resolver, android.net.Uri.parse(uri), contentType.toMediaType(), byteSize, onProgress,
        )
        executePut(uploadUrl, body, label)
    }

    private fun executePut(uploadUrl: String, body: okhttp3.RequestBody, label: String) {
        val request = Request.Builder()
            .url(uploadUrl)
            .put(body)
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
     *  not hardcoded to "IMAGE" the way the old image-only path was. [onProgress] reports
     *  (bytes sent, total bytes) across ALL attachments for the pill's progress bar (§9.1). */
    suspend fun uploadAttachments(
        conversationId: String,
        attachments: List<AttachmentInput>,
        body: String? = null,
        replyToId: String? = null,
        onProgress: ((sentBytes: Long, totalBytes: Long) -> Unit)? = null,
    ): Message {
        require(attachments.isNotEmpty()) { "attachments must not be empty" }
        val totalBytes = attachments.sumOf { (it.localBytes?.size ?: it.byteSize).toLong() }
        var sentBase = 0L
        val uploaded = mutableListOf<AttachmentInput>()
        for (attachment in attachments) {
            val normalizedType = attachment.contentType.ifBlank { "application/octet-stream" }
            diagnostic("upload.${attachment.kind}.presign.start", "type=$normalizedType bytes=${attachment.byteSize}")
            val ticket = api.requestUpload(UploadRequest(conversationId, attachment.kind, normalizedType, attachment.byteSize))
            diagnostic("upload.${attachment.kind}.presign.ok", "type=$normalizedType bytes=${attachment.byteSize}")
            diagnostic("upload.${attachment.kind}.put.start", "bytes=${attachment.byteSize}")
            val base = sentBase
            val perAttachmentProgress = onProgress?.let { cb ->
                { sent: Long -> cb(base + sent, totalBytes) }
            }
            runCatching {
                // §13.15: big media staged as a Uri streams from disk; small staged
                // payloads (compressed images, voice notes) keep the in-memory path.
                val uri = attachment.localUri
                if (uri != null) {
                    putUriToPresignedUrl(
                        ticket.uploadUrl, uri, normalizedType,
                        attachment.byteSize.toLong(), attachment.kind, perAttachmentProgress,
                    )
                } else {
                    val bytes = requireNotNull(attachment.localBytes) { "AttachmentInput.localBytes or localUri required for upload" }
                    putToPresignedUrl(ticket.uploadUrl, bytes, normalizedType, attachment.kind, perAttachmentProgress)
                }
            }.onFailure {
                diagnostic("upload.${attachment.kind}.put.failed", it.message ?: it::class.java.simpleName)
                throw it
            }
            diagnostic("upload.${attachment.kind}.put.ok", "bytes=${attachment.byteSize}")
            sentBase += attachment.byteSize.toLong()
            uploaded += attachment.copy(key = ticket.key, localBytes = null, localUri = null)
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

    // ── v0.5.1 (§8.2): notification prefs, per-chat prefs, stars, attachments ──

    suspend fun notificationPrefs(): NotificationPrefs = api.notificationPrefs()

    /** Partial PUT — only the provided toggles are sent. */
    suspend fun updateNotificationPrefs(
        messages: Boolean? = null,
        groups: Boolean? = null,
        calls: Boolean? = null,
        friendRequests: Boolean? = null,
    ): NotificationPrefs = api.updateNotificationPrefs(JsonObject(buildMap {
        messages?.let { put("messages", JsonPrimitive(it)) }
        groups?.let { put("groups", JsonPrimitive(it)) }
        calls?.let { put("calls", JsonPrimitive(it)) }
        friendRequests?.let { put("friendRequests", JsonPrimitive(it)) }
    }))

    suspend fun resetNotificationPrefs() { api.resetNotificationPrefs() }

    suspend fun conversationPrefs(conversationId: String): ConversationPrefs =
        api.conversationPrefs(conversationId)

    /**
     * Partial PUT of the per-conversation mutes. [messagesMutedUntil]/[callsMutedUntil]
     * take an ISO instant, `JsonNull`-encoded null to unmute, or stay absent when the
     * corresponding `set*` flag is false.
     */
    suspend fun updateConversationPrefs(
        conversationId: String,
        setMessagesMutedUntil: Boolean = false,
        messagesMutedUntil: String? = null,
        muteMentions: Boolean? = null,
        setCallsMutedUntil: Boolean = false,
        callsMutedUntil: String? = null,
    ): ConversationPrefs = api.updateConversationPrefs(conversationId, JsonObject(buildMap {
        if (setMessagesMutedUntil) {
            put("messagesMutedUntil", messagesMutedUntil?.let { JsonPrimitive(it) } ?: JsonNull)
        }
        muteMentions?.let { put("muteMentions", JsonPrimitive(it)) }
        if (setCallsMutedUntil) {
            put("callsMutedUntil", callsMutedUntil?.let { JsonPrimitive(it) } ?: JsonNull)
        }
    }))

    suspend fun starMessage(messageId: String) { api.starMessage(messageId) }
    suspend fun unstarMessage(messageId: String) { api.unstarMessage(messageId) }

    suspend fun starredMessages(conversationId: String? = null, cursor: String? = null): StarredPage =
        api.starredMessages(conversationId, cursor)

    suspend fun conversationAttachments(
        conversationId: String,
        kind: String? = null,
        cursor: String? = null,
    ): AttachmentPage = api.conversationAttachments(conversationId, kind, cursor)

    // ── Group management (§8.4) ──────────────────────────────────────────────

    /** Admin removes a member (§9.3). Throws on failure so the caller can reconcile. */
    suspend fun removeConversationMember(conversationId: String, userId: String) {
        val res = api.removeConversationMember(conversationId, userId)
        if (!res.isSuccessful) error("Member removal failed (${res.code()})")
    }

    /** §14.3: POST /conversations/:id/transfer-admin {userId} — current admin only. */
    suspend fun transferAdmin(conversationId: String, userId: String) {
        val res = api.transferAdmin(conversationId, mapOf("userId" to userId))
        if (!res.isSuccessful) error("Admin transfer failed (${res.code()})")
    }

    /** §14.3: PATCH /conversations/:id {theme} — admin-only shared group theme
     *  (null clears it back to default for every member). */
    suspend fun updateConversationTheme(conversationId: String, theme: ConversationTheme?): Conversation =
        api.updateConversation(
            conversationId,
            JsonObject(mapOf("theme" to (theme?.let {
                json.encodeToJsonElement(ConversationTheme.serializer(), it)
            } ?: JsonNull))),
        )

    /**
     * Upload a new group cover and attach it — POST avatar-upload, PUT bytes, PATCH key.
     * §10.1: every step is diagnosed and failures are rethrown with the failing step
     * baked into the message so the UI can surface a step-specific error toast.
     */
    suspend fun uploadConversationAvatar(
        conversationId: String,
        bytes: ByteArray,
        contentType: String,
    ): Conversation {
        diagnostic("cover.presign.start", "conv=$conversationId type=$contentType bytes=${bytes.size}")
        val ticket = runCatching {
            api.conversationAvatarUpload(conversationId, AvatarUploadRequest(contentType, bytes.size))
        }.onFailure {
            diagnostic("cover.presign.failed", it.message ?: it::class.java.simpleName)
        }.getOrElse { throw CoverUploadException("presign", it) }
        diagnostic("cover.presign.ok", "key=${ticket.key}")

        runCatching {
            putToPresignedUrl(ticket.uploadUrl, bytes, contentType, "Group cover")
        }.onFailure {
            diagnostic("cover.put.failed", it.message ?: it::class.java.simpleName)
        }.getOrElse { throw CoverUploadException("storage upload", it) }
        diagnostic("cover.put.ok", "bytes=${bytes.size}")

        val updated = runCatching {
            api.updateConversation(
                conversationId,
                JsonObject(mapOf("avatarKey" to JsonPrimitive(ticket.key))),
            )
        }.onFailure {
            diagnostic("cover.patch.failed", it.message ?: it::class.java.simpleName)
        }.getOrElse { throw CoverUploadException("attach (PATCH)", it) }
        diagnostic("cover.patch.ok", "avatarUrl=${updated.avatarUrl != null}")
        return updated
    }

    // ── v0.5.3 (§10.4): blocks, passkeys, contacts, account lifecycle ────────

    suspend fun blocks(): List<BlockedUser> = api.blocks()

    suspend fun blockUser(userId: String) {
        val res = api.blockUser(mapOf("userId" to userId))
        if (!res.isSuccessful) error("Block failed (${res.code()})")
    }

    suspend fun unblockUser(userId: String) {
        val res = api.unblockUser(userId)
        if (!res.isSuccessful) error("Unblock failed (${res.code()})")
    }

    suspend fun passkeyRegisterOptions(): JsonObject = api.passkeyRegisterOptions()

    suspend fun passkeyRegisterVerify(credentialJson: JsonObject) {
        val res = api.passkeyRegisterVerify(credentialJson)
        if (!res.isSuccessful) error("Passkey registration failed (${res.code()})")
    }

    suspend fun passkeys(): List<Passkey> = api.passkeys()

    suspend fun deletePasskey(id: String) {
        val res = api.deletePasskey(id)
        if (!res.isSuccessful) error("Passkey removal failed (${res.code()})")
    }

    suspend fun passkeyLoginOptions(): JsonObject = api.passkeyLoginOptions(JsonObject(emptyMap()))

    /** Completes a passkey sign-in: the server returns the normal token pair. */
    suspend fun passkeyLogin(credentialJson: JsonObject): User {
        val res = api.passkeyLoginVerify(credentialJson)
        persist(res)
        return res.user
    }

    suspend fun uploadContactHashes(hashes: List<String>) {
        val res = api.uploadContactHashes(ContactHashesRequest(hashes))
        if (!res.isSuccessful) error("Contact sync failed (${res.code()})")
    }

    suspend fun deleteContactHashes() {
        val res = api.deleteContactHashes()
        if (!res.isSuccessful) error("Deleting synced contacts failed (${res.code()})")
    }

    /** PATCH /me {deleteIfAwayMonths} — null clears the auto-delete window. */
    suspend fun setDeleteIfAwayMonths(months: Int?): User {
        val body = JsonObject(mapOf("deleteIfAwayMonths" to (months?.let { JsonPrimitive(it) } ?: JsonNull)))
        val user = api.updateProfile(body)
        currentUser = user
        tokenStore.saveUser(json.encodeToString(User.serializer(), user))
        return user
    }

    /** DELETE /me — cascaded server-side; caller wipes local state + logs out. */
    suspend fun deleteAccount() {
        val res = api.deleteAccount()
        if (!res.isSuccessful) error("Account deletion failed (${res.code()})")
    }

    // ── v0.5.5 (§12.1): safety reports ────────────────────────────────────────

    /**
     * Submit a report: user, message, or (with neither target) an app-problem report.
     * Throws on failure — the sheet surfaces the server's message (e.g. the daily cap).
     */
    suspend fun submitReport(
        category: String,
        targetUserId: String? = null,
        messageId: String? = null,
        details: String? = null,
    ): String = api.createReport(CreateReportRequest(
        category = category,
        targetUserId = targetUserId,
        messageId = messageId,
        details = details?.trim()?.takeIf { it.isNotEmpty() },
    )).id

    // ── v0.5.5 (§12.2): email add/verify via Google ──────────────────────────

    /** Refresh the cached self user from GET /me (now carries email/emailVerified). */
    suspend fun refreshMe(): User {
        val user = api.me()
        currentUser = user
        tokenStore.saveUser(json.encodeToString(User.serializer(), user))
        return user
    }

    /** POST the Google ID token, then refresh /me so the row shows the verified email.
     *  Non-2xx throws HttpException — "Email already in use" surfaces via serverMessage(). */
    suspend fun linkGoogleEmail(idToken: String): User {
        api.linkGoogleEmail(GoogleEmailRequest(idToken))
        return refreshMe()
    }

    /** DELETE /me/email — clears email + emailVerified, then refreshes /me. */
    suspend fun removeEmail(): User {
        val res = api.removeEmail()
        if (!res.isSuccessful) error("Email removal failed (${res.code()})")
        return refreshMe()
    }

    // ── v0.5.9 (§16.3/§16.4): pins + message editing ──────────────────────────

    /** POST .../pin — DM: either side; group: admin only (server-enforced). */
    suspend fun pinMessage(conversationId: String, messageId: String, notify: Boolean) {
        val res = api.pinMessage(conversationId, messageId, PinMessageRequest(notify))
        if (!res.isSuccessful) error("Pin failed (${res.code()})")
    }

    /** DELETE .../pin — idempotent server-side. */
    suspend fun unpinMessage(conversationId: String, messageId: String) {
        val res = api.unpinMessage(conversationId, messageId)
        if (!res.isSuccessful) error("Unpin failed (${res.code()})")
    }

    /**
     * PATCH the message body (§16.4). Returns the refreshed message when the server
     * responds with a full payload, or null on a shapeless 2xx — either way the
     * `message:updated` fan-out is the authoritative sync path.
     */
    suspend fun editMessage(conversationId: String, messageId: String, body: String): Message? {
        val raw = api.editMessage(conversationId, messageId, EditMessageRequest(body)).string()
        return runCatching { json.decodeFromString(Message.serializer(), raw) }.getOrNull()
    }

    private suspend fun persist(res: AuthResponse) {
        tokenStore.save(res.accessToken, res.refreshToken)
        tokenStore.saveUser(json.encodeToString(User.serializer(), res.user))
        currentUser = res.user
    }
}

/** §10.1: carries which step of the cover chain failed, for a step-specific error toast. */
class CoverUploadException(val step: String, cause: Throwable) :
    Exception("Group cover $step failed: ${cause.message ?: cause::class.java.simpleName}", cause)
