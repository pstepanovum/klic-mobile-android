package com.klic.mobile.app.data

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class User(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val showLastSeen: Boolean? = null,   // present on /me + auth responses
    /** §10.4: auto-delete window in months (null = off); PATCH /me round-trips it. */
    val deleteIfAwayMonths: Int? = null,
    // ── v0.5.4 (§11.5): profile about/status line + links ──
    val about: String? = null,
    val links: List<String>? = null,
    // ── v0.5.4 (§11.6): privacy controls (EVERYBODY / FRIENDS / NOBODY) ──
    val lastSeenVisibility: String? = null,
    val aboutVisibility: String? = null,
    val avatarVisibility: String? = null,
    val linksVisibility: String? = null,
    val groupsVisibility: String? = null,
    val statusVisibility: String? = null,
    val silenceUnknownCallers: Boolean? = null,
    val readReceipts: Boolean? = null,
    // ── v0.5.5 (§12.2): verified email via Google ──
    val email: String? = null,
    val emailVerified: Boolean? = null,
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
    /** §11.5: about + links, present when the subject's visibility allows it. */
    val about: String? = null,
    val links: List<String>? = null,
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

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MobileDiagnosticRequest(
    // Force-encode even though it equals the default: the Json instance uses
    // encodeDefaults=false, so without this the field is dropped and the server's
    // own default ("ios") mislabels every Android diagnostic event.
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val source: String = "android",
    val event: String,
    val callId: String? = null,
    val detail: String? = null,
)

@Serializable
data class Member(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    /** §11.5: About/status line, present when the member's visibility allows it. */
    val about: String? = null,
)

@Serializable
data class Conversation(
    val id: String,
    val type: String,
    val title: String? = null,
    val members: List<Member> = emptyList(),
    val lastMessage: Message? = null,
    val unreadCount: Int = 0,
    val avatarUrl: String? = null,      // group cover (GET /conversations/:id/avatar)
    val description: String? = null,
    val createdById: String? = null,    // group creator (drives the "Created by" footer)
    val createdAt: String? = null,
    val isAdmin: Boolean = false,       // true when the current user created this group
    /** §14.3: shared group chat theme (admin-set, server-synced); null = none. */
    val theme: ConversationTheme? = null,
)

/**
 * §14.3: the server-shared group theme — mirrors WP-S8's zod shape on
 * PATCH /conversations/:id {theme}. All fields optional; null theme = default.
 */
@Serializable
data class ConversationTheme(
    val pattern: Int? = null,
    val patternOpacity: Float? = null,
    val gradientId: String? = null,
    val gradientIntensity: Float? = null,
    val bubbleColorId: String? = null,
)

@Serializable
data class CreateConversationRequest(
    val userId: String? = null,
    val title: String? = null,
    val userIds: List<String>? = null,
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
    val outcome: String,        // "completed" | "missed" | "declined" | "canceled" | "failed"
    val durationMs: Int? = null,
) {
    val isVideo: Boolean get() = kind == "VIDEO"
}

@Serializable
data class Reaction(
    val emoji: String,
    val count: Int,
    val mine: Boolean = false,
)

@Serializable
data class ReplyPreview(
    val id: String,
    val senderId: String,
    val kind: String,
    val preview: String,
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
    val attachments: List<Attachment> = emptyList(),
    val stickerId: String? = null,
    val stickerUrl: String? = null,
    val call: CallEvent? = null,
    val replyTo: ReplyPreview? = null,
    val reactions: List<Reaction> = emptyList(),
    val deletedAt: String? = null,
    /** True when the requesting user starred this message (§8.2). */
    val starred: Boolean = false,
    // CIPHERTEXT messages (E2EE): sender's protocol device + the envelopes
    // addressed to this user's devices (this client picks its own by deviceId).
    val senderDeviceId: Int? = null,
    val envelopes: List<MessageEnvelope> = emptyList(),
) {
    val isCallEvent: Boolean get() = kind == "CALL_EVENT"
    val isSticker: Boolean get() = kind == "STICKER"
    val isDeleted: Boolean get() = deletedAt != null
}

@Serializable
data class MessageEnvelope(val deviceId: Int, val type: Int, val ciphertext: String)

@Serializable
data class RecentCall(
    val id: String,
    val conversationId: String,
    val kind: String,
    val outgoing: Boolean,
    val outcome: String,
    val startedAt: String,
    val durationMs: Int? = null,
    /** Everyone besides me who was on the call (a 1:1 peer is the single-element case). */
    val participants: List<RecentCallPeer> = emptyList(),
    /** Pre-group servers sent a single peer; kept as a decode fallback. */
    val peer: RecentCallPeer? = null,
) {
    val isVideo: Boolean get() = kind == "VIDEO"

    /** The counterpart shown on the row — first fellow participant, or the legacy peer. */
    val primaryPeer: RecentCallPeer? get() = participants.firstOrNull() ?: peer

    val peerNames: String
        get() = participants.joinToString(", ") { it.displayName }
            .ifEmpty { peer?.displayName ?: "Unknown" }
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
data class SendStickerRequest(val stickerId: String, val replyToId: String? = null)

@Serializable
data class ReactionRequest(val emoji: String)

@Serializable
data class ReactionResponse(val reactions: List<Reaction> = emptyList())

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
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Int? = null,
    val waveform: String? = null,
    val fileName: String? = null,
    @Transient val localBytes: ByteArray? = null,
    /** §13.15: large media/files upload streamed straight from this content Uri —
     *  never buffered in memory. At most one of [localBytes]/[localUri] is set for
     *  staged uploads; both stay off the wire. */
    @Transient val localUri: String? = null,
)

@Serializable
data class SendWithAttachmentsRequest(
    val body: String? = null,
    val attachments: List<AttachmentInput>,
    val replyToId: String? = null,
)

@Serializable
data class RequestFrom(val id: String, val username: String, val displayName: String)

@Serializable
data class FriendRequest(val requestId: String, val from: RequestFrom)

@Serializable
data class SendMessageRequest(val body: String, val replyToId: String? = null)

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

/** GET /conversations/:id/active-call — a call in RINGING/ANSWERING/ONGOING/RECONNECTING. */
@Serializable
data class ActiveCallInfo(
    val callId: String,
    val conversationId: String,
    val roomName: String,
    val livekitUrl: String,
    val kind: String,
    val status: String,
    val startedBy: String? = null,
    val participants: List<ActiveCallParticipant> = emptyList(),
) {
    /** Members that actually joined media (joinedAt set), i.e. people currently in the call. */
    val joinedCount: Int get() = participants.count { it.joinedAt != null }
}

@Serializable
data class ActiveCallParticipant(val userId: String, val joinedAt: String? = null)

// ── v0.5.1 (§8.2): notification prefs, per-conversation prefs, stars, attachments ──

/** GET/PUT /me/notification-prefs — the four global notification toggles. */
@Serializable
data class NotificationPrefs(
    val messages: Boolean = true,
    val groups: Boolean = true,
    val calls: Boolean = true,
    val friendRequests: Boolean = true,
)

/**
 * GET/PUT /conversations/:id/prefs — per-conversation mutes.
 * ISO instants or null; "Always" = 9999-12-31T00:00:00Z.
 */
@Serializable
data class ConversationPrefs(
    val messagesMutedUntil: String? = null,
    val muteMentions: Boolean = false,
    val callsMutedUntil: String? = null,
)

/** One row of GET /conversations/:id/attachments — attachment + message context. */
@Serializable
data class ConversationAttachment(
    val id: String,
    val kind: String,
    val url: String,
    val contentType: String = "",
    val byteSize: Int = 0,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Int? = null,
    val fileName: String? = null,
    val messageId: String = "",
    val senderId: String = "",
    val createdAt: String = "",
) {
    fun asAttachment(): Attachment = Attachment(
        id = id, kind = kind, url = url, contentType = contentType, byteSize = byteSize,
        width = width, height = height, durationMs = durationMs, fileName = fileName,
    )
}

/** GET /conversations/:id/attachments — cursor-paged envelope. */
@Serializable
data class AttachmentPage(
    val items: List<ConversationAttachment> = emptyList(),
    val nextCursor: String? = null,
)

/** GET /me/starred — cursor-paged envelope of full message payloads. */
@Serializable
data class StarredPage(
    val items: List<Message> = emptyList(),
    val nextCursor: String? = null,
)

// ── v0.5.3 (§10.4): blocks, passkeys, contacts ──

/** One row of GET /blocks — a user this account has blocked. */
@Serializable
data class BlockedUser(
    val user: User,
    val blockedAt: String? = null,
)

/** One row of GET /me/passkeys. */
@Serializable
data class Passkey(
    val id: String,
    val label: String? = null,
    val createdAt: String? = null,
    val lastUsedAt: String? = null,
)

/** POST /me/contacts — SHA-256 hex hashes of normalized emails + phone numbers. */
@Serializable
data class ContactHashesRequest(val hashes: List<String>)

// ── v0.5.5 (§12.1/§12.2): reports + email verification ──

/**
 * POST /reports — exactly one of [targetUserId]/[messageId], or neither (a target-less
 * report = app/system problem report). Category ∈ the server's ReportCategory enum.
 */
@Serializable
data class CreateReportRequest(
    val category: String,
    val targetUserId: String? = null,
    val messageId: String? = null,
    val details: String? = null,
)

/** 201 response of POST /reports. */
@Serializable
data class CreateReportResponse(val id: String)

/** POST /me/email/google — the Google ID token proving ownership of the email. */
@Serializable
data class GoogleEmailRequest(val idToken: String)
