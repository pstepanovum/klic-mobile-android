package com.klic.mobile.app.realtime

import android.os.Handler
import android.os.Looper
import com.klic.mobile.app.data.AccessToken
import com.klic.mobile.app.data.Message
import com.klic.mobile.app.data.Network
import com.klic.mobile.app.data.Reaction
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject
import java.time.Instant

/** Socket.io client mirroring klic-server/src/realtime/events.ts. */
class SocketService {
    private var socket: Socket? = null
    private var currentAccessToken: String? = null
    private var myUserId: String? = null

    val incomingMessages = MutableSharedFlow<Message>(extraBufferCapacity = 32)
    val incomingCalls = MutableSharedFlow<CallInvite>(extraBufferCapacity = 8)
    val callEvents = MutableSharedFlow<CallEvent>(extraBufferCapacity = 16)

    /** Live presence per user id (online + last-seen epoch millis, when shared). */
    val presence = MutableStateFlow<Map<String, Presence>>(emptyMap())
    val readReceipts = MutableSharedFlow<Receipt>(extraBufferCapacity = 32)
    val deliveredReceipts = MutableSharedFlow<Receipt>(extraBufferCapacity = 32)
    /** conversationId → epoch millis the peer last signalled typing (cleared on stop/expiry). */
    val typing = MutableStateFlow<Map<String, Long>>(emptyMap())
    val reactionUpdates = MutableSharedFlow<ReactionUpdate>(extraBufferCapacity = 32)
    val deletedMessages = MutableSharedFlow<DeletedUpdate>(extraBufferCapacity = 16)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val typingTokens = mutableMapOf<String, Any>()

    data class Presence(val online: Boolean, val lastSeenMs: Long? = null)
    data class Receipt(val conversationId: String, val userId: String, val atMs: Long)
    data class ReactionUpdate(val conversationId: String, val messageId: String, val reactions: List<Reaction>)
    data class DeletedUpdate(val conversationId: String, val messageId: String)

    data class CallInvite(
        val callId: String,
        val conversationId: String,
        val roomName: String,
        val livekitUrl: String,
        val kind: String,
        val fromDisplayName: String,
        val conversationType: String = "DIRECT",
        val conversationTitle: String = "",
        val participantCount: Int = 0,
    )

    data class CallEvent(
        val type: Type,
        val callId: String,
        val userId: String? = null,
    ) {
        enum class Type { ACCEPT, DECLINE, CANCEL, END, PARTICIPANT_JOINED, PARTICIPANT_LEFT }
    }

    fun connect(accessToken: String) {
        if (socket?.connected() == true && currentAccessToken == accessToken) return
        socket?.off()
        socket?.disconnect()
        currentAccessToken = accessToken
        myUserId = AccessToken.subject(accessToken)
        val opts = IO.Options().apply { auth = mapOf("token" to accessToken) }
        val socket = IO.socket(Network.BASE_HTTP, opts).also { this.socket = it }

        socket.on("message:new") { args ->
            (args.firstOrNull() as? JSONObject)?.let { json ->
                runCatching {
                    val msg = Json.decodeFromString(Message.serializer(), json.toString())
                    incomingMessages.tryEmit(msg)
                    // Acknowledge delivery for messages from others (drives the 2nd tick).
                    if (msg.senderId != myUserId) {
                        emit("message:delivered", buildPayload("conversationId" to msg.conversationId))
                    }
                }
            }
        }
        socket.on("presence:update") { args ->
            (args.firstOrNull() as? JSONObject)?.let { json ->
                val userId = json.optString("userId").takeIf { it.isNotBlank() } ?: return@let
                val online = json.optBoolean("online", false)
                val lastSeen = json.optString("lastSeen").takeIf { it.isNotBlank() }?.let(::parseMs)
                presence.value = presence.value + (userId to Presence(online, lastSeen))
            }
        }
        socket.on("message:read") { args ->
            (args.firstOrNull() as? JSONObject)?.let { json ->
                val at = json.optString("readAt").takeIf { it.isNotBlank() }?.let(::parseMs) ?: return@let
                readReceipts.tryEmit(
                    Receipt(json.optString("conversationId"), json.optString("userId"), at)
                )
            }
        }
        socket.on("message:delivered") { args ->
            (args.firstOrNull() as? JSONObject)?.let { json ->
                val at = json.optString("deliveredAt").takeIf { it.isNotBlank() }?.let(::parseMs) ?: return@let
                deliveredReceipts.tryEmit(
                    Receipt(json.optString("conversationId"), json.optString("userId"), at)
                )
            }
        }
        socket.on("typing") { args ->
            (args.firstOrNull() as? JSONObject)?.let { json ->
                val conversationId = json.optString("conversationId").takeIf { it.isNotBlank() } ?: return@let
                val isTyping = json.optBoolean("isTyping", false)
                mainHandler.post {
                    if (isTyping) {
                        val token = Any()
                        typingTokens[conversationId] = token
                        typing.value = typing.value + (conversationId to System.currentTimeMillis())
                        // Auto-clear if the peer goes quiet without sending a stop.
                        mainHandler.postDelayed({
                            if (typingTokens[conversationId] === token) typing.value = typing.value - conversationId
                        }, 6000)
                    } else {
                        typingTokens[conversationId] = Any()
                        typing.value = typing.value - conversationId
                    }
                }
            }
        }
        socket.on("message:reaction") { args ->
            (args.firstOrNull() as? JSONObject)?.let { json ->
                val conversationId = json.optString("conversationId").takeIf { it.isNotBlank() } ?: return@let
                val messageId = json.optString("messageId").takeIf { it.isNotBlank() } ?: return@let
                val arr = json.optJSONArray("reactions")
                val reactions = buildList {
                    if (arr != null) for (i in 0 until arr.length()) {
                        val r = arr.getJSONObject(i)
                        add(Reaction(r.optString("emoji"), r.optInt("count"), r.optBoolean("mine", false)))
                    }
                }
                reactionUpdates.tryEmit(ReactionUpdate(conversationId, messageId, reactions))
            }
        }
        socket.on("message:deleted") { args ->
            (args.firstOrNull() as? JSONObject)?.let { json ->
                val conversationId = json.optString("conversationId").takeIf { it.isNotBlank() } ?: return@let
                val messageId = json.optString("messageId").takeIf { it.isNotBlank() } ?: return@let
                deletedMessages.tryEmit(DeletedUpdate(conversationId, messageId))
            }
        }
        socket.on("call:invite") { args ->
            (args.firstOrNull() as? JSONObject)?.let { json ->
                val from = json.optJSONObject("from")
                incomingCalls.tryEmit(
                    CallInvite(
                        callId = json.optString("callId"),
                        conversationId = json.optString("conversationId"),
                        roomName = json.optString("roomName"),
                        livekitUrl = json.optString("livekitUrl"),
                        kind = json.optString("kind", "AUDIO"),
                        fromDisplayName = from?.optString("displayName") ?: "Unknown",
                        conversationType = json.optString("conversationType", "DIRECT"),
                        conversationTitle = json.optString("conversationTitle"),
                        participantCount = json.optInt("participantCount", 0),
                    )
                )
            }
        }
        socket.on("call:accept") { args ->
            emitCallEvent(args, CallEvent.Type.ACCEPT)
        }
        socket.on("call:decline") { args ->
            emitCallEvent(args, CallEvent.Type.DECLINE)
        }
        socket.on("call:cancel") { args ->
            emitCallEvent(args, CallEvent.Type.CANCEL)
        }
        socket.on("call:end") { args ->
            emitCallEvent(args, CallEvent.Type.END)
        }
        // Group ring/banner signals: fired on every media-joined (including our own and
        // repeats after a rejoin — consumers treat them idempotently) and on each leave.
        socket.on("call:participant-joined") { args ->
            emitCallEvent(args, CallEvent.Type.PARTICIPANT_JOINED)
        }
        socket.on("call:participant-left") { args ->
            emitCallEvent(args, CallEvent.Type.PARTICIPANT_LEFT)
        }
        socket.connect()
    }

    fun emit(event: String, payload: JsonObject) = socket?.emit(event, JSONObject(payload.toString()))
    fun disconnect() {
        socket?.off()
        socket?.disconnect()
        socket = null
        currentAccessToken = null
    }

    private fun buildPayload(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { it.first to kotlinx.serialization.json.JsonPrimitive(it.second) })

    private fun parseMs(iso: String): Long? = runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()

    private fun emitCallEvent(args: Array<Any>, type: CallEvent.Type) {
        (args.firstOrNull() as? JSONObject)?.let { json ->
            callEvents.tryEmit(
                CallEvent(
                    type = type,
                    callId = json.optString("callId"),
                    userId = json.optString("userId").takeIf { it.isNotBlank() },
                )
            )
        }
    }
}
