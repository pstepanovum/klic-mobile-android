package com.klic.mobile.app.data

import android.util.Log
import kotlinx.serialization.json.Json
import retrofit2.HttpException

/** Feature gate: flips on together with iOS at the cutover release (E2EE.md §16). */
object E2eeConfig {
    const val SEND_ENABLED = false
}

/**
 * The bridge between the wire and the UI for E2EE messages: turns incoming
 * CIPHERTEXT payloads into renderable [Message]s (decrypt once, then serve from
 * the local store) and encrypts outgoing content for a conversation's device
 * directory. The rest of the app keeps rendering plain [Message]s — no UI
 * changes.
 */
class E2eeMessaging(
    private val keys: E2eeKeyManager,
    private val sessions: E2eeSessions,
    private val store: E2eeMessageStore,
    private val api: KlicApi,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Make a CIPHERTEXT message renderable: local store first, else decrypt the
     * envelope addressed to this device and persist the result. Non-ciphertext
     * messages pass through untouched.
     */
    suspend fun materialize(message: Message, myUserId: String?): Message {
        if (message.kind != "CIPHERTEXT" || message.isDeleted) return message

        val content = store.get(message.id) ?: decryptAndStore(message)
        return when {
            content == null -> message.copy(
                kind = "TEXT",
                body = if (message.senderId == myUserId) SENT_ELSEWHERE_PLACEHOLDER else UNDECRYPTABLE_PLACEHOLDER,
            )
            content.type == "text" -> message.copy(
                kind = "TEXT",
                body = content.text.orEmpty(),
                replyTo = content.quote?.let { ReplyPreview(it.messageId, "", it.kind, it.preview) },
            )
            content.type == "sticker" -> message.copy(kind = "STICKER", stickerId = content.stickerId, body = "")
            else -> message.copy(kind = "TEXT", body = FUTURE_TYPE_PLACEHOLDER)
        }
    }

    suspend fun materializeAll(messages: List<Message>, myUserId: String?): List<Message> =
        messages.map { materialize(it, myUserId) }

    /**
     * Encrypt and send a text message. Retries once when the server reports
     * 409 STALE_DEVICES (a device joined/left since our directory fetch).
     */
    suspend fun sendText(conversationId: String, text: String, quote: E2eeQuote? = null): Message {
        val content = E2eeContent.text(text, quote)
        var directory = api.conversationDevices(conversationId).devices

        repeat(2) { attempt ->
            val fanOut = sessions.encryptForDirectory(content, directory)
            try {
                val sent = api.sendCiphertext(
                    conversationId,
                    CipherSendRequest(senderDeviceId = fanOut.senderDeviceId, envelopes = fanOut.envelopes),
                )
                store.save(sent.id, conversationId, sent.senderId, fanOut.senderDeviceId, content, sent.createdAt)
                return materialize(sent, sent.senderId)
            } catch (e: HttpException) {
                if (e.code() != 409 || attempt > 0) throw e
                directory = staleDirectory(e) ?: api.conversationDevices(conversationId).devices
                Log.i(TAG, "device directory was stale — re-encrypting for ${directory.size} devices")
            }
        }
        error("unreachable")
    }

    private suspend fun decryptAndStore(message: Message): E2eeContent? {
        val myDeviceId = keys.localDeviceId() ?: return null
        val senderDeviceId = message.senderDeviceId ?: return null
        val envelope = message.envelopes.find { it.deviceId == myDeviceId } ?: return null
        val content = sessions.decrypt(message.senderId, senderDeviceId, envelope.type, envelope.ciphertext)
            ?: return null
        store.save(message.id, message.conversationId, message.senderId, senderDeviceId, content, message.createdAt)
        return content
    }

    /** The fresh device directory carried on a 409 STALE_DEVICES response. */
    private fun staleDirectory(e: HttpException): List<DeviceDirEntry>? = runCatching {
        val body = e.response()?.errorBody()?.string() ?: return null
        json.decodeFromString<StaleDevicesResponse>(body)
            .takeIf { it.error == "STALE_DEVICES" }?.devices
    }.getOrNull()

    @kotlinx.serialization.Serializable
    private data class StaleDevicesResponse(val error: String, val devices: List<DeviceDirEntry>)

    private companion object {
        const val TAG = "KlicE2ee"
        const val SENT_ELSEWHERE_PLACEHOLDER = "🔒 Sent from another device"
        const val UNDECRYPTABLE_PLACEHOLDER = "🔒 Waiting for keys — open Klic on the sending device"
        const val FUTURE_TYPE_PLACEHOLDER = "🔒 Update Klic to view this message"
    }
}
