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
data class Message(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val body: String,
    val kind: String = "TEXT",
    val createdAt: String = "",
    val status: String? = null,   // "sent" | "delivered" | "read" — own messages only
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
