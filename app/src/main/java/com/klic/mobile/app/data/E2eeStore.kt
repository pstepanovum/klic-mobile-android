package com.klic.mobile.app.data

import kotlinx.serialization.Serializable
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.util.Base64
import java.util.UUID

/**
 * Everything the Signal protocol store persists, as one serializable value —
 * encrypted as a unit by [E2eeKeyManager] before it touches disk. Values are
 * base64-serialized libsignal records; map keys are "userId.deviceId" addresses
 * or integer key ids rendered as strings.
 */
@Serializable
data class E2eeStoreSnapshot(
    val sessions: Map<String, String> = emptyMap(),
    val identities: Map<String, String> = emptyMap(),
    val preKeys: Map<String, String> = emptyMap(),
    val signedPreKeys: Map<String, String> = emptyMap(),
    val kyberPreKeys: Map<String, String> = emptyMap(),
    /** "kyberId:baseKeyB64" pairs already used against the last-resort key. */
    val usedKyberBaseKeys: Set<String> = emptySet(),
    val senderKeys: Map<String, String> = emptyMap(),
)

/**
 * libsignal [SignalProtocolStore] backed by in-memory maps with write-through
 * persistence: every mutation hands a fresh [E2eeStoreSnapshot] to [persist].
 * Not thread-safe by itself — all session operations run under
 * [E2eeKeyManager]'s mutex.
 *
 * Identity trust is trust-on-first-use; a changed key is *recorded* (so the
 * Phase 6 UI can warn) but [isTrustedIdentity] returns false until the app
 * explicitly accepts it by saving the new identity.
 */
class KlicSignalStore(
    private val identity: IdentityKeyPair,
    private val registrationId: Int,
    snapshot: E2eeStoreSnapshot,
    private val persist: (E2eeStoreSnapshot) -> Unit,
) : SignalProtocolStore {
    private val b64e = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    private val sessions = snapshot.sessions.toMutableMap()
    private val identities = snapshot.identities.toMutableMap()
    private val preKeys = snapshot.preKeys.toMutableMap()
    private val signedPreKeys = snapshot.signedPreKeys.toMutableMap()
    private val kyberPreKeys = snapshot.kyberPreKeys.toMutableMap()
    private val usedKyberBaseKeys = snapshot.usedKyberBaseKeys.toMutableSet()
    private val senderKeys = snapshot.senderKeys.toMutableMap()

    fun snapshot() = E2eeStoreSnapshot(
        sessions = sessions.toMap(),
        identities = identities.toMap(),
        preKeys = preKeys.toMap(),
        signedPreKeys = signedPreKeys.toMap(),
        kyberPreKeys = kyberPreKeys.toMap(),
        usedKyberBaseKeys = usedKyberBaseKeys.toSet(),
        senderKeys = senderKeys.toMap(),
    )

    private fun save() = persist(snapshot())

    private fun addr(a: SignalProtocolAddress) = "${a.name}.${a.deviceId}"

    // ── IdentityKeyStore ──────────────────────────────────────────────────────

    override fun getIdentityKeyPair(): IdentityKeyPair = identity

    override fun getLocalRegistrationId(): Int = registrationId

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val key = addr(address)
        val previous = identities[key]
        val encoded = b64e.encodeToString(identityKey.serialize())
        identities[key] = encoded
        save()
        return if (previous == null || previous == encoded) {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        } else {
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        }
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean {
        val recorded = identities[addr(address)] ?: return true // first contact
        return recorded == b64e.encodeToString(identityKey.serialize())
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        identities[addr(address)]?.let { IdentityKey(b64d.decode(it)) }

    // ── SessionStore ──────────────────────────────────────────────────────────

    override fun loadSession(address: SignalProtocolAddress): SessionRecord? =
        sessions[addr(address)]?.let { SessionRecord(b64d.decode(it)) }

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.map { loadSession(it) ?: throw NoSessionException("no session for ${addr(it)}") }

    override fun getSubDeviceSessions(name: String): List<Int> =
        sessions.keys
            .filter { it.substringBeforeLast('.') == name }
            .map { it.substringAfterLast('.').toInt() }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        sessions[addr(address)] = b64e.encodeToString(record.serialize())
        save()
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        sessions.containsKey(addr(address))

    override fun deleteSession(address: SignalProtocolAddress) {
        sessions.remove(addr(address))
        save()
    }

    override fun deleteAllSessions(name: String) {
        sessions.keys.removeAll { it.substringBeforeLast('.') == name }
        save()
    }

    // ── PreKeyStore ───────────────────────────────────────────────────────────

    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        preKeys[preKeyId.toString()]?.let { PreKeyRecord(b64d.decode(it)) }
            ?: throw InvalidKeyIdException("no prekey $preKeyId")

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        preKeys[preKeyId.toString()] = b64e.encodeToString(record.serialize())
        save()
    }

    override fun containsPreKey(preKeyId: Int): Boolean = preKeys.containsKey(preKeyId.toString())

    override fun removePreKey(preKeyId: Int) {
        preKeys.remove(preKeyId.toString())
        save()
    }

    // ── SignedPreKeyStore ─────────────────────────────────────────────────────

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        signedPreKeys[signedPreKeyId.toString()]?.let { SignedPreKeyRecord(b64d.decode(it)) }
            ?: throw InvalidKeyIdException("no signed prekey $signedPreKeyId")

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        signedPreKeys.values.map { SignedPreKeyRecord(b64d.decode(it)) }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        signedPreKeys[signedPreKeyId.toString()] = b64e.encodeToString(record.serialize())
        save()
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        signedPreKeys.containsKey(signedPreKeyId.toString())

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        signedPreKeys.remove(signedPreKeyId.toString())
        save()
    }

    // ── KyberPreKeyStore ──────────────────────────────────────────────────────

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        kyberPreKeys[kyberPreKeyId.toString()]?.let { KyberPreKeyRecord(b64d.decode(it)) }
            ?: throw InvalidKeyIdException("no kyber prekey $kyberPreKeyId")

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> =
        kyberPreKeys.values.map { KyberPreKeyRecord(b64d.decode(it)) }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        kyberPreKeys[kyberPreKeyId.toString()] = b64e.encodeToString(record.serialize())
        save()
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean =
        kyberPreKeys.containsKey(kyberPreKeyId.toString())

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        if (kyberPreKeyId == E2eeIds.KYBER_LAST_RESORT_ID) {
            // The last-resort key is reusable across sessions but never with the
            // same base key twice (that would be a replayed handshake).
            val use = "$kyberPreKeyId:${b64e.encodeToString(baseKey.serialize())}"
            if (!usedKyberBaseKeys.add(use)) throw ReusedBaseKeyException("kyber base key reused")
        } else {
            // One-time kyber prekeys are exactly that.
            kyberPreKeys.remove(kyberPreKeyId.toString())
        }
        save()
    }

    // ── SenderKeyStore (groups — Phase 4) ─────────────────────────────────────

    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) {
        senderKeys["${addr(sender)}/$distributionId"] = b64e.encodeToString(record.serialize())
        save()
    }

    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? =
        senderKeys["${addr(sender)}/$distributionId"]?.let { SenderKeyRecord(b64d.decode(it)) }
}

/** Reserved protocol key ids. */
object E2eeIds {
    /**
     * The Kyber last-resort prekey id. Reserved so it can never collide with
     * one-time Kyber ids (which count up from 1) — the store and the wire
     * reference kyber keys by id alone.
     */
    const val KYBER_LAST_RESORT_ID = 0xFFFFFF
}
