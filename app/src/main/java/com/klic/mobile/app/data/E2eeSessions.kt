package com.klic.mobile.app.data

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle

/**
 * What actually gets encrypted (E2EE.md §7): a small versioned payload. Replies,
 * reactions, deletes and stickers travel here — never as server-readable fields.
 * Unknown `type`s render as "update Klic" on old clients (ignoreUnknownKeys).
 */
@Serializable
data class E2eeContent(
    val v: Int = 1,
    val type: String, // "text" | "reaction" | "sticker" | "delete"
    val text: String? = null,
    val emoji: String? = null,
    val remove: Boolean? = null,
    val targetMessageId: String? = null,
    val stickerId: String? = null,
    val quote: E2eeQuote? = null,
) {
    companion object {
        fun text(body: String, quote: E2eeQuote? = null) = E2eeContent(type = "text", text = body, quote = quote)
    }
}

/** Sender-built preview of the quoted message (the recipient verifies locally). */
@Serializable
data class E2eeQuote(val messageId: String, val preview: String, val kind: String)

object E2eeCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(content: E2eeContent): ByteArray = json.encodeToString(content).encodeToByteArray()

    fun decode(plaintext: ByteArray): E2eeContent? =
        runCatching { json.decodeFromString<E2eeContent>(plaintext.decodeToString()) }.getOrNull()
}

/** An encrypted content payload addressed to every device in a conversation. */
data class EncryptedFanOut(val senderDeviceId: Int, val envelopes: List<CipherEnvelopeDto>)

/**
 * Session establishment + envelope encryption/decryption on top of
 * [E2eeKeyManager]'s protocol store. All operations serialize on the key
 * manager's mutex — libsignal session state is read-modify-write.
 */
class E2eeSessions(private val keys: E2eeKeyManager, private val api: KlicApi) {

    /**
     * Encrypt [content] for every device in [directory] except our own sending
     * device. Missing sessions are established by fetching prekey bundles.
     * The caller sends the result and retries once with the fresh directory on
     * a 409 STALE_DEVICES response.
     */
    suspend fun encryptForDirectory(content: E2eeContent, directory: List<DeviceDirEntry>): EncryptedFanOut =
        keys.mutex.withLock {
            val store = keys.protocolStore() ?: error("E2EE keys not ready")
            val myDeviceId = keys.localDeviceId() ?: error("E2EE device not registered")
            val plaintext = E2eeCodec.encode(content)

            val targets = directory.filterNot { it.deviceId == myDeviceId && isSelf(it, store) }
            // Establish sessions first, one bundle fetch per user that needs any.
            val missing = targets.filter { !store.containsSession(address(it)) }
            for (userId in missing.map { it.userId }.distinct()) {
                val bundles = api.userKeys(userId)
                for (target in missing.filter { it.userId == userId }) {
                    val bundle = bundles.devices.find { it.deviceId == target.deviceId }
                        ?: continue // vanished device — the server's coverage check will 409 us
                    SessionBuilder(store, address(target)).process(bundle.toPreKeyBundle(target.deviceId))
                }
            }

            val envelopes = targets.mapNotNull { target ->
                runCatching {
                    val message = SessionCipher(store, address(target)).encrypt(plaintext)
                    CipherEnvelopeDto(
                        userId = target.userId,
                        deviceId = target.deviceId,
                        type = message.type,
                        ciphertext = Base64.encodeToString(message.serialize(), Base64.NO_WRAP),
                    )
                }.onFailure {
                    Log.w(TAG, "encrypt to ${target.userId}/${target.deviceId} failed", it)
                }.getOrNull()
            }
            EncryptedFanOut(senderDeviceId = myDeviceId, envelopes = envelopes)
        }

    /**
     * Decrypt an incoming envelope from (senderUserId, senderDeviceId).
     * Returns null when the envelope is not decryptable — the UI shows a
     * placeholder rather than blocking the chat (E2EE.md §8).
     */
    suspend fun decrypt(senderUserId: String, senderDeviceId: Int, type: Int, ciphertextB64: String): E2eeContent? =
        keys.mutex.withLock {
            val store = keys.protocolStore() ?: return null
            val address = SignalProtocolAddress(senderUserId, senderDeviceId)
            val bytes = Base64.decode(ciphertextB64, Base64.NO_WRAP)
            runCatching {
                val cipher = SessionCipher(store, address)
                val plaintext = when (type) {
                    CiphertextMessage.PREKEY_TYPE -> cipher.decrypt(PreKeySignalMessage(bytes))
                    CiphertextMessage.WHISPER_TYPE -> cipher.decrypt(SignalMessage(bytes))
                    else -> return null
                }
                E2eeCodec.decode(plaintext)
            }.onFailure {
                Log.w(TAG, "decrypt from $senderUserId/$senderDeviceId failed", it)
            }.getOrNull()
        }

    private fun address(entry: DeviceDirEntry) = SignalProtocolAddress(entry.userId, entry.deviceId)

    /** Whether a directory entry is this install (same deviceId AND our identity key). */
    private fun isSelf(entry: DeviceDirEntry, store: KlicSignalStore): Boolean =
        entry.identityKey == Base64.encodeToString(store.identityKeyPair.publicKey.serialize(), Base64.NO_WRAP)

    private fun DeviceBundleDto.toPreKeyBundle(deviceId: Int): PreKeyBundle {
        val b64 = { s: String -> Base64.decode(s, Base64.NO_WRAP) }
        // Kyber is mandatory in modern bundles — the server always supplies one
        // (falling back to the device's last-resort key when one-times are drained).
        val kyber = kyberPreKey ?: error("bundle for device $deviceId lacks a kyber prekey")
        return PreKeyBundle(
            registrationId,
            deviceId,
            preKey?.keyId ?: PreKeyBundle.NULL_PRE_KEY_ID,
            preKey?.let { ECPublicKey(b64(it.publicKey)) },
            signedPreKey.keyId,
            ECPublicKey(b64(signedPreKey.publicKey)),
            b64(signedPreKey.signature),
            IdentityKey(b64(identityKey)),
            kyber.keyId,
            KEMPublicKey(b64(kyber.publicKey)),
            b64(kyber.signature),
        )
    }

    private companion object {
        const val TAG = "KlicE2ee"
    }
}
