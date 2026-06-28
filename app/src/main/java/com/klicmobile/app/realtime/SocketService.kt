package com.klicmobile.app.realtime

import com.klicmobile.app.data.AccessToken
import com.klicmobile.app.data.Message
import com.klicmobile.app.data.Network
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject
import java.time.Instant

/** Socket.io client mirroring klick-server/src/realtime/events.ts. */
class SocketService {
    private var socket: Socket? = null
    private var myUserId: String? = null

    val incomingMessages = MutableSharedFlow<Message>(extraBufferCapacity = 32)
    val incomingCalls = MutableSharedFlow<CallInvite>(extraBufferCapacity = 8)
    val callEvents = MutableSharedFlow<CallEvent>(extraBufferCapacity = 16)

    /** Live presence per user id (online + last-seen epoch millis, when shared). */
    val presence = MutableStateFlow<Map<String, Presence>>(emptyMap())
    val readReceipts = MutableSharedFlow<Receipt>(extraBufferCapacity = 32)
    val deliveredReceipts = MutableSharedFlow<Receipt>(extraBufferCapacity = 32)

    data class Presence(val online: Boolean, val lastSeenMs: Long? = null)
    data class Receipt(val conversationId: String, val userId: String, val atMs: Long)

    data class CallInvite(
        val callId: String,
        val conversationId: String,
        val roomName: String,
        val livekitUrl: String,
        val kind: String,
        val fromDisplayName: String,
    )

    data class CallEvent(
        val type: Type,
        val callId: String,
        val userId: String? = null,
    ) {
        enum class Type { ACCEPT, DECLINE, CANCEL, END }
    }

    fun connect(accessToken: String) {
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
        socket.connect()
    }

    fun emit(event: String, payload: JsonObject) = socket?.emit(event, JSONObject(payload.toString()))
    fun disconnect() { socket?.disconnect(); socket = null }

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
