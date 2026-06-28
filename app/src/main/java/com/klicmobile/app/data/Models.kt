package com.klicmobile.app.data

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val showLastSeen: Boolean? = null,   // present on /me + auth responses
)

/** A friend's profile (GET /users/:id). lastSeenAt/online are null when hidden by privacy. */
@Serializable
data class UserProfile(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val lastSeenAt: String? = null,
    val online: Boolean? = null,
)

@Serializable
data class AvatarUploadRequest(val contentType: String, val byteSize: Int)

@Serializable
data class UploadTicket(
    val key: String,
    val uploadUrl: String,
    val expiresAt: String? = null,
    val maxBytes: Long? = null,
)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val user: User,
)

@Serializable
data class RegisterRequest(val username: String, val password: String, val displayName: String)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class Member(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
)

@Serializable
data class Conversation(
    val id: String,
    val type: String,
    val members: List<Member> = emptyList(),
    val lastMessage: Message? = null,
)

@Serializable
data class Attachment(
    val id: String,
    val kind: String,
    val url: String,
    val contentType: String,
    val byteSize: Int,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Int? = null,
    val waveform: String? = null,   // base64-encoded 5-bit packed waveform (VOICE only)
    val fileName: String? = null,
)

@Serializable
data class CallEvent(
    val kind: String,           // "AUDIO" | "VIDEO"
    val outcome: String,        // "completed" | "missed" | "declined" | "canceled"
    val durationMs: Int? = null,
) {
    val isVideo: Boolean get() = kind == "VIDEO"
}

@Serializable
data class Message(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val body: String,
    val kind: String = "TEXT",
    val createdAt: String = "",
    val status: String? = null,   // "sent" | "delivered" | "read" — own messages only
    val attachments: List<Attachment> = emptyList(),
    val stickerId: String? = null,
    val stickerUrl: String? = null,
    val call: CallEvent? = null,
) {
    val isCallEvent: Boolean get() = kind == "CALL_EVENT"
    val isSticker: Boolean get() = kind == "STICKER"
}

@Serializable
data class RecentCall(
    val id: String,
    val conversationId: String,
    val kind: String,
    val outgoing: Boolean,
    val outcome: String,
    val startedAt: String,
    val durationMs: Int? = null,
    val peer: RecentCallPeer? = null,
) {
    val isVideo: Boolean get() = kind == "VIDEO"
}

@Serializable
data class RecentCallPeer(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
)

@Serializable
data class Sticker(val id: String, val url: String)

@Serializable
data class StickerCatalog(val stickers: List<Sticker> = emptyList())

@Serializable
data class SendStickerRequest(val stickerId: String)

@Serializable
data class UploadRequest(
    val conversationId: String,
    val kind: String,
    val contentType: String,
    val byteSize: Int,
)

@Serializable
data class AttachmentInput(
    val key: String,
    val kind: String,
    val contentType: String,
    val byteSize: Int,
    val durationMs: Int? = null,
    val waveform: String? = null,
)

@Serializable
data class SendWithAttachmentsRequest(
    val body: String? = null,
    val attachments: List<AttachmentInput>,
)

@Serializable
data class RequestFrom(val id: String, val username: String, val displayName: String)

@Serializable
data class FriendRequest(val requestId: String, val from: RequestFrom)

@Serializable
data class SendMessageRequest(val body: String)

@Serializable
data class StartCallRequest(val conversationId: String, val kind: String)

@Serializable
data class CallSession(
    val callId: String,
    val roomName: String,
    val livekitUrl: String,
    val token: String,
    val kind: String? = null,
)
