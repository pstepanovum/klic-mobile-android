package com.klic.mobile.app.data

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import retrofit2.HttpException

// ── Wire DTOs for the key-distribution endpoints (E2EE.md §6.2) ──────────────

@Serializable data class OneTimePreKeyDto(val keyId: Int, val publicKey: String)

@Serializable data class SignedPreKeyDto(val keyId: Int, val publicKey: String, val signature: String)

@Serializable data class KyberPreKeyDto(val keyId: Int, val publicKey: String, val signature: String)

@Serializable
data class PublishKeysRequest(
    val installId: String,
    val platform: String,
    val registrationId: Int,
    val identityKey: String,
    val signedPreKey: SignedPreKeyDto,
    val kyberLastResort: KyberPreKeyDto,
    val oneTimePreKeys: List<OneTimePreKeyDto>,
    val kyberPreKeys: List<KyberPreKeyDto>,
)

@Serializable data class PublishKeysResponse(val deviceId: Int)

@Serializable data class PreKeyCountResponse(val oneTimePreKeys: Int, val kyberPreKeys: Int)

@Serializable
data class TopUpPreKeysRequest(
    val installId: String,
    val oneTimePreKeys: List<OneTimePreKeyDto>,
    val kyberPreKeys: List<KyberPreKeyDto>,
)

@Serializable
data class RotateSignedPreKeyRequest(val installId: String, val signedPreKey: SignedPreKeyDto)

// ── Bundle fetch + device directory + ciphertext send (E2EE.md §6.2–6.3) ─────

@Serializable
data class DeviceBundleDto(
    val deviceId: Int,
    val registrationId: Int,
    val identityKey: String,
    val signedPreKey: SignedPreKeyDto,
    val preKey: OneTimePreKeyDto? = null,
    val kyberPreKey: KyberPreKeyDto? = null,
)

@Serializable data class UserKeysResponse(val userId: String, val devices: List<DeviceBundleDto>)

@Serializable
data class DeviceDirEntry(val userId: String, val deviceId: Int, val registrationId: Int, val identityKey: String)

@Serializable data class DeviceDirectoryResponse(val devices: List<DeviceDirEntry>)

@Serializable
data class CipherEnvelopeDto(val userId: String, val deviceId: Int, val type: Int, val ciphertext: String)

@Serializable
data class CipherSendRequest(
    val kind: String = "CIPHERTEXT",
    val senderDeviceId: Int,
    val envelopes: List<CipherEnvelopeDto>,
)

// ── Local key state ───────────────────────────────────────────────────────────

private val Context.e2eeDataStore by preferencesDataStore(name = "klic_e2ee")

/**
 * This install's Signal-protocol identity and key material. All records (prekeys,
 * signed prekeys, Kyber keys, sessions, peer identities) live in one
 * [E2eeStoreSnapshot] persisted AES-encrypted under an Android Keystore key
 * ([KeystoreCrypto]); the whole DataStore is excluded from backups.
 *
 * [mutex] serializes every protocol operation — libsignal session state is
 * read-modify-write and must never interleave.
 */
class E2eeKeyManager(private val context: Context, private val api: KlicApi) {
    val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private val schemaKey = intPreferencesKey("schemaV")
    private val installIdKey = stringPreferencesKey("installId")
    private val deviceIdKey = intPreferencesKey("deviceId")
    private val registrationIdKey = intPreferencesKey("registrationId")
    private val identityKey = stringPreferencesKey("identity") // encrypted IdentityKeyPair
    private val protocolStoreKey = stringPreferencesKey("protocolStore") // encrypted E2eeStoreSnapshot
    private val currentSignedPreKeyIdKey = intPreferencesKey("currentSignedPreKeyId")
    private val signedPreKeyCreatedAtKey = longPreferencesKey("signedPreKeyCreatedAt")
    private val nextPreKeyIdKey = intPreferencesKey("nextPreKeyId")
    private val nextKyberIdKey = intPreferencesKey("nextKyberId")
    private val nextSignedIdKey = intPreferencesKey("nextSignedId")

    private var cachedStore: KlicSignalStore? = null

    /** The protocol deviceId assigned by the server, or null before first publish. */
    suspend fun localDeviceId(): Int? = context.e2eeDataStore.data.first()[deviceIdKey]

    /**
     * The libsignal store for session operations. Callers MUST hold [mutex].
     * Null until keys have been generated and published.
     */
    suspend fun protocolStore(): KlicSignalStore? {
        cachedStore?.let { return it }
        val prefs = context.e2eeDataStore.data.first()
        if ((prefs[schemaKey] ?: 0) != SCHEMA) return null
        val identity = prefs[identityKey]?.let { KeystoreCrypto.decrypt(it) }
            ?.let { IdentityKeyPair(Base64.decode(it, Base64.NO_WRAP)) } ?: return null
        val registrationId = prefs[registrationIdKey] ?: return null
        val snapshot = prefs[protocolStoreKey]?.let { KeystoreCrypto.decrypt(it) }
            ?.let { json.decodeFromString<E2eeStoreSnapshot>(it) } ?: E2eeStoreSnapshot()
        return KlicSignalStore(identity, registrationId, snapshot, ::persistSnapshot).also {
            cachedStore = it
        }
    }

    /** Write-through hook for [KlicSignalStore] — called from inside libsignal ops. */
    private fun persistSnapshot(snapshot: E2eeStoreSnapshot) = runBlocking {
        context.e2eeDataStore.edit {
            it[protocolStoreKey] = KeystoreCrypto.encrypt(json.encodeToString(snapshot))
        }
    }

    /**
     * Bring this install's published bundle up to date. Called on every successful
     * auth; safe to call repeatedly. Failures are logged and retried on next auth.
     */
    suspend fun ensureReady() = mutex.withLock {
        runCatching {
            val prefs = context.e2eeDataStore.data.first()
            when {
                prefs[identityKey] == null -> generateAndPublish()
                (prefs[schemaKey] ?: 0) != SCHEMA -> {
                    // Pre-session key layout (e.g. the last-resort id collision fixed
                    // in schema 2): no sessions exist yet, so a clean regenerate is safe.
                    Log.i(TAG, "key schema ${prefs[schemaKey] ?: 0} -> $SCHEMA: regenerating")
                    context.e2eeDataStore.edit { it.clear() }
                    cachedStore = null
                    generateAndPublish()
                }
                prefs[deviceIdKey] == null -> publishExisting()
                else -> maintain(prefs[installIdKey]!!)
            }
        }.onFailure { Log.w(TAG, "key upkeep failed (will retry on next auth)", it) }
    }

    // ── Generation + publish ──────────────────────────────────────────────────

    private suspend fun generateAndPublish() {
        val identity = IdentityKeyPair.generate()
        val registrationId = SecureRandom().nextInt(16380) + 1
        val installId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val signedRecord = signedPreKey(identity, 1, now)
        val kyberLastResort = kyberPreKey(identity, E2eeIds.KYBER_LAST_RESORT_ID, now)
        val preKeys = (1..PRE_KEY_BATCH).map { PreKeyRecord(it, ECKeyPair.generate()) }
        val kyberPreKeys = (1..KYBER_BATCH).map { kyberPreKey(identity, it, now) }

        val response = api.publishKeys(
            PublishKeysRequest(
                installId = installId,
                platform = "ANDROID",
                registrationId = registrationId,
                identityKey = b64(identity.publicKey.serialize()),
                signedPreKey = signedRecord.toDto(),
                kyberLastResort = kyberLastResort.toDto(),
                oneTimePreKeys = preKeys.map { it.toDto() },
                kyberPreKeys = kyberPreKeys.map { it.toDto() },
            ),
        )

        val snapshot = E2eeStoreSnapshot(
            preKeys = preKeys.associate { it.id.toString() to b64(it.serialize()) },
            signedPreKeys = mapOf("1" to b64(signedRecord.serialize())),
            kyberPreKeys = (kyberPreKeys + kyberLastResort).associate { it.id.toString() to b64(it.serialize()) },
        )
        context.e2eeDataStore.edit { p ->
            p[schemaKey] = SCHEMA
            p[installIdKey] = installId
            p[deviceIdKey] = response.deviceId
            p[registrationIdKey] = registrationId
            p[identityKey] = KeystoreCrypto.encrypt(b64(identity.serialize()))
            p[protocolStoreKey] = KeystoreCrypto.encrypt(json.encodeToString(snapshot))
            p[currentSignedPreKeyIdKey] = 1
            p[signedPreKeyCreatedAtKey] = now
            p[nextSignedIdKey] = 2
            p[nextPreKeyIdKey] = PRE_KEY_BATCH + 1
            p[nextKyberIdKey] = KYBER_BATCH + 1
        }
        cachedStore = null
        Log.i(TAG, "published key bundle as device ${response.deviceId}")
    }

    /** A publish failed after keygen (e.g. offline at first login): retry with stored keys. */
    private suspend fun publishExisting() {
        val prefs = context.e2eeDataStore.data.first()
        val store = protocolStoreForPublish() ?: return resetAndRegenerate("state unusable")
        val identity = store.identityKeyPair
        val installId = prefs[installIdKey] ?: return resetAndRegenerate("installId missing")
        val signedId = prefs[currentSignedPreKeyIdKey] ?: return resetAndRegenerate("spk id missing")

        val signedRecord = runCatching { store.loadSignedPreKey(signedId) }.getOrNull()
            ?: return resetAndRegenerate("spk record missing")
        val kyberLastResort = runCatching { store.loadKyberPreKey(E2eeIds.KYBER_LAST_RESORT_ID) }.getOrNull()
            ?: return resetAndRegenerate("kyber last-resort missing")
        val preKeys = store.snapshot().preKeys.values.map { PreKeyRecord(Base64.decode(it, Base64.NO_WRAP)) }
        val kyberPreKeys = store.snapshot().kyberPreKeys
            .filterKeys { it != E2eeIds.KYBER_LAST_RESORT_ID.toString() }
            .values.map { KyberPreKeyRecord(Base64.decode(it, Base64.NO_WRAP)) }

        val response = api.publishKeys(
            PublishKeysRequest(
                installId = installId,
                platform = "ANDROID",
                registrationId = prefs[registrationIdKey] ?: return resetAndRegenerate("regId missing"),
                identityKey = b64(identity.publicKey.serialize()),
                signedPreKey = signedRecord.toDto(),
                kyberLastResort = kyberLastResort.toDto(),
                oneTimePreKeys = preKeys.map { it.toDto() },
                kyberPreKeys = kyberPreKeys.map { it.toDto() },
            ),
        )
        context.e2eeDataStore.edit { it[deviceIdKey] = response.deviceId }
        Log.i(TAG, "re-published key bundle as device ${response.deviceId}")
    }

    /** Like [protocolStore] but tolerates a missing deviceId (publish retry path). */
    private suspend fun protocolStoreForPublish(): KlicSignalStore? {
        cachedStore?.let { return it }
        val prefs = context.e2eeDataStore.data.first()
        val identity = prefs[identityKey]?.let { KeystoreCrypto.decrypt(it) }
            ?.let { IdentityKeyPair(Base64.decode(it, Base64.NO_WRAP)) } ?: return null
        val registrationId = prefs[registrationIdKey] ?: return null
        val snapshot = prefs[protocolStoreKey]?.let { KeystoreCrypto.decrypt(it) }
            ?.let { json.decodeFromString<E2eeStoreSnapshot>(it) } ?: return null
        return KlicSignalStore(identity, registrationId, snapshot, ::persistSnapshot).also {
            cachedStore = it
        }
    }

    // ── Upkeep: top-up + rotation ─────────────────────────────────────────────

    private suspend fun maintain(installId: String) {
        val counts = try {
            api.preKeyCount(installId)
        } catch (e: HttpException) {
            // The server no longer knows this install (e.g. a dev DB reset): publish again.
            if (e.code() == 404) return publishExisting()
            throw e
        }

        if (counts.oneTimePreKeys < TOP_UP_THRESHOLD || counts.kyberPreKeys < TOP_UP_THRESHOLD) {
            topUp(installId)
        }

        val prefs = context.e2eeDataStore.data.first()
        val createdAt = prefs[signedPreKeyCreatedAtKey] ?: 0
        if (System.currentTimeMillis() - createdAt > SIGNED_PRE_KEY_MAX_AGE_MS) {
            rotateSignedPreKey(installId)
        }
    }

    private suspend fun topUp(installId: String) {
        val store = protocolStoreForPublish() ?: return
        val identity = store.identityKeyPair
        val prefs = context.e2eeDataStore.data.first()
        val nextPre = prefs[nextPreKeyIdKey] ?: 1
        val nextKyber = prefs[nextKyberIdKey] ?: 1
        val now = System.currentTimeMillis()

        val preKeys = (nextPre until nextPre + PRE_KEY_BATCH).map { PreKeyRecord(it, ECKeyPair.generate()) }
        val kyberPreKeys = (nextKyber until nextKyber + KYBER_BATCH).map { kyberPreKey(identity, it, now) }

        api.topUpPreKeys(
            TopUpPreKeysRequest(
                installId = installId,
                oneTimePreKeys = preKeys.map { it.toDto() },
                kyberPreKeys = kyberPreKeys.map { it.toDto() },
            ),
        )

        preKeys.forEach { store.storePreKey(it.id, it) }
        kyberPreKeys.forEach { store.storeKyberPreKey(it.id, it) }
        context.e2eeDataStore.edit { p ->
            p[nextPreKeyIdKey] = nextPre + PRE_KEY_BATCH
            p[nextKyberIdKey] = nextKyber + KYBER_BATCH
        }
        Log.i(TAG, "topped up prekeys (+$PRE_KEY_BATCH EC, +$KYBER_BATCH kyber)")
    }

    private suspend fun rotateSignedPreKey(installId: String) {
        val store = protocolStoreForPublish() ?: return
        val prefs = context.e2eeDataStore.data.first()
        val nextId = prefs[nextSignedIdKey] ?: 2
        val now = System.currentTimeMillis()
        val record = signedPreKey(store.identityKeyPair, nextId, now)

        api.rotateSignedPreKey(RotateSignedPreKeyRequest(installId, record.toDto()))

        // Keep superseded records: in-flight PreKey messages may still reference them.
        store.storeSignedPreKey(record.id, record)
        context.e2eeDataStore.edit { p ->
            p[currentSignedPreKeyIdKey] = record.id
            p[signedPreKeyCreatedAtKey] = now
            p[nextSignedIdKey] = record.id + 1
        }
        Log.i(TAG, "rotated signed prekey to id ${record.id}")
    }

    /** Local state is unusable (Keystore key lost, partial write): start over cleanly. */
    private suspend fun resetAndRegenerate(reason: String) {
        Log.w(TAG, "resetting E2EE keys: $reason")
        context.e2eeDataStore.edit { it.clear() }
        cachedStore = null
        generateAndPublish()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun signedPreKey(identity: IdentityKeyPair, id: Int, now: Long): SignedPreKeyRecord {
        val pair = ECKeyPair.generate()
        return SignedPreKeyRecord(id, now, pair, identity.privateKey.calculateSignature(pair.publicKey.serialize()))
    }

    private fun kyberPreKey(identity: IdentityKeyPair, id: Int, now: Long): KyberPreKeyRecord {
        val pair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        return KyberPreKeyRecord(id, now, pair, identity.privateKey.calculateSignature(pair.publicKey.serialize()))
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun PreKeyRecord.toDto() = OneTimePreKeyDto(id, b64(keyPair.publicKey.serialize()))

    private fun SignedPreKeyRecord.toDto() =
        SignedPreKeyDto(id, b64(keyPair.publicKey.serialize()), b64(signature))

    private fun KyberPreKeyRecord.toDto() =
        KyberPreKeyDto(id, b64(keyPair.publicKey.serialize()), b64(signature))

    private companion object {
        const val TAG = "KlicE2ee"
        const val SCHEMA = 2 // v2: records live in the protocol-store snapshot; reserved kyber last-resort id
        const val PRE_KEY_BATCH = 100
        const val KYBER_BATCH = 50
        const val TOP_UP_THRESHOLD = 20
        const val SIGNED_PRE_KEY_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
