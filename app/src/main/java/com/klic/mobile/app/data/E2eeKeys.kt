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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.Curve
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

// ── Local key store ───────────────────────────────────────────────────────────

private val Context.e2eeDataStore by preferencesDataStore(name = "klic_e2ee")

/**
 * This install's Signal-protocol identity: identity keypair, signed prekey, and one-time
 * EC + Kyber prekeys. Private material is AES-encrypted with a Keystore key before it
 * touches disk ([KeystoreCrypto]) and the whole store is excluded from backups.
 *
 * Phase 1 scope: generate, publish, top up, rotate. Sessions (encrypt/decrypt) are Phase 2 —
 * the private prekey records are retained here so Phase 2 can process incoming PreKey
 * messages that reference them.
 */
class E2eeKeyManager(private val context: Context, private val api: KlicApi) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private val installIdKey = stringPreferencesKey("installId")
    private val deviceIdKey = intPreferencesKey("deviceId")
    private val registrationIdKey = intPreferencesKey("registrationId")
    private val identityKey = stringPreferencesKey("identity") // encrypted IdentityKeyPair
    private val signedPreKeysKey = stringPreferencesKey("signedPreKeys") // encrypted {id: record}
    private val currentSignedPreKeyIdKey = intPreferencesKey("currentSignedPreKeyId")
    private val signedPreKeyCreatedAtKey = longPreferencesKey("signedPreKeyCreatedAt")
    private val kyberLastResortKey = stringPreferencesKey("kyberLastResort") // encrypted record
    private val preKeysKey = stringPreferencesKey("preKeys") // encrypted {id: record}
    private val kyberPreKeysKey = stringPreferencesKey("kyberPreKeys") // encrypted {id: record}
    private val nextPreKeyIdKey = intPreferencesKey("nextPreKeyId")
    private val nextKyberIdKey = intPreferencesKey("nextKyberId")
    private val nextSignedIdKey = intPreferencesKey("nextSignedId")

    /**
     * Bring this install's published bundle up to date. Called on every successful auth;
     * safe to call repeatedly. Failures are logged and retried on the next auth — messaging
     * (still plaintext until Phase 2) is never blocked on key upkeep.
     */
    suspend fun ensureReady() = mutex.withLock {
        runCatching {
            val prefs = context.e2eeDataStore.data.first()
            when {
                prefs[identityKey] == null -> generateAndPublish()
                prefs[deviceIdKey] == null -> publishExisting() // earlier publish never landed
                else -> maintain()
            }
        }.onFailure { Log.w(TAG, "key upkeep failed (will retry on next auth)", it) }
    }

    // ── Generation + publish ──────────────────────────────────────────────────

    private suspend fun generateAndPublish() {
        val identity = IdentityKeyPair.generate()
        val registrationId = SecureRandom().nextInt(16380) + 1
        val installId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val signedPair = Curve.generateKeyPair()
        val signedRecord = SignedPreKeyRecord(
            1, now, signedPair, identity.privateKey.calculateSignature(signedPair.publicKey.serialize()))

        val kyberLastResortPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberLastResort = KyberPreKeyRecord(
            1, now, kyberLastResortPair,
            identity.privateKey.calculateSignature(kyberLastResortPair.publicKey.serialize()))

        val preKeys = (1..PRE_KEY_BATCH).map { PreKeyRecord(it, Curve.generateKeyPair()) }
        val kyberPreKeys = (1..KYBER_BATCH).map { id ->
            val pair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            KyberPreKeyRecord(id, now, pair, identity.privateKey.calculateSignature(pair.publicKey.serialize()))
        }

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

        context.e2eeDataStore.edit { p ->
            p[installIdKey] = installId
            p[deviceIdKey] = response.deviceId
            p[registrationIdKey] = registrationId
            p[identityKey] = KeystoreCrypto.encrypt(b64(identity.serialize()))
            p[signedPreKeysKey] = encryptRecordMap(mapOf(1 to signedRecord.serialize()))
            p[currentSignedPreKeyIdKey] = 1
            p[signedPreKeyCreatedAtKey] = now
            p[nextSignedIdKey] = 2
            p[kyberLastResortKey] = KeystoreCrypto.encrypt(b64(kyberLastResort.serialize()))
            p[preKeysKey] = encryptRecordMap(preKeys.associate { it.id to it.serialize() })
            p[kyberPreKeysKey] = encryptRecordMap(kyberPreKeys.associate { it.id to it.serialize() })
            p[nextPreKeyIdKey] = PRE_KEY_BATCH + 1
            p[nextKyberIdKey] = KYBER_BATCH + 1
        }
        Log.i(TAG, "published key bundle as device ${response.deviceId}")
    }

    /** A publish failed after keygen (e.g. offline at first login): retry with stored keys. */
    private suspend fun publishExisting() {
        val prefs = context.e2eeDataStore.data.first()
        val identity = loadIdentity() ?: return resetAndRegenerate("identity undecryptable")
        val installId = prefs[installIdKey] ?: return resetAndRegenerate("installId missing")
        val signedId = prefs[currentSignedPreKeyIdKey] ?: return resetAndRegenerate("spk missing")
        val signedRecord = decryptRecordMap(prefs[signedPreKeysKey])[signedId]
            ?.let { SignedPreKeyRecord(it) } ?: return resetAndRegenerate("spk record missing")
        val kyberLastResort = prefs[kyberLastResortKey]?.let { KeystoreCrypto.decrypt(it) }
            ?.let { KyberPreKeyRecord(Base64.decode(it, Base64.NO_WRAP)) }
            ?: return resetAndRegenerate("kyber last-resort missing")
        val preKeys = decryptRecordMap(prefs[preKeysKey]).map { PreKeyRecord(it.value) }
        val kyberPreKeys = decryptRecordMap(prefs[kyberPreKeysKey]).map { KyberPreKeyRecord(it.value) }

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

    // ── Upkeep: top-up + rotation ─────────────────────────────────────────────

    private suspend fun maintain() {
        val prefs = context.e2eeDataStore.data.first()
        val installId = prefs[installIdKey] ?: return
        val identity = loadIdentity() ?: return resetAndRegenerate("identity undecryptable")

        val counts = try {
            api.preKeyCount(installId)
        } catch (e: HttpException) {
            // The server no longer knows this install (e.g. a dev DB reset): publish again.
            if (e.code() == 404) return publishExisting()
            throw e
        }

        if (counts.oneTimePreKeys < TOP_UP_THRESHOLD || counts.kyberPreKeys < TOP_UP_THRESHOLD) {
            topUp(installId, identity, prefs[nextPreKeyIdKey] ?: 1, prefs[nextKyberIdKey] ?: 1)
        }

        val createdAt = prefs[signedPreKeyCreatedAtKey] ?: 0
        if (System.currentTimeMillis() - createdAt > SIGNED_PRE_KEY_MAX_AGE_MS) {
            rotateSignedPreKey(installId, identity, prefs[nextSignedIdKey] ?: 2)
        }
    }

    private suspend fun topUp(installId: String, identity: IdentityKeyPair, nextPreId: Int, nextKyberId: Int) {
        val now = System.currentTimeMillis()
        val preKeys = (nextPreId until nextPreId + PRE_KEY_BATCH).map { PreKeyRecord(it, Curve.generateKeyPair()) }
        val kyberPreKeys = (nextKyberId until nextKyberId + KYBER_BATCH).map { id ->
            val pair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            KyberPreKeyRecord(id, now, pair, identity.privateKey.calculateSignature(pair.publicKey.serialize()))
        }

        api.topUpPreKeys(
            TopUpPreKeysRequest(
                installId = installId,
                oneTimePreKeys = preKeys.map { it.toDto() },
                kyberPreKeys = kyberPreKeys.map { it.toDto() },
            ),
        )

        context.e2eeDataStore.edit { p ->
            p[preKeysKey] = encryptRecordMap(
                decryptRecordMap(p[preKeysKey]) + preKeys.associate { it.id to it.serialize() })
            p[kyberPreKeysKey] = encryptRecordMap(
                decryptRecordMap(p[kyberPreKeysKey]) + kyberPreKeys.associate { it.id to it.serialize() })
            p[nextPreKeyIdKey] = nextPreId + PRE_KEY_BATCH
            p[nextKyberIdKey] = nextKyberId + KYBER_BATCH
        }
        Log.i(TAG, "topped up prekeys (+$PRE_KEY_BATCH EC, +$KYBER_BATCH kyber)")
    }

    private suspend fun rotateSignedPreKey(installId: String, identity: IdentityKeyPair, nextId: Int) {
        val now = System.currentTimeMillis()
        val pair = Curve.generateKeyPair()
        val record = SignedPreKeyRecord(nextId, now, pair,
            identity.privateKey.calculateSignature(pair.publicKey.serialize()))

        api.rotateSignedPreKey(RotateSignedPreKeyRequest(installId, record.toDto()))

        context.e2eeDataStore.edit { p ->
            // Keep superseded records: in-flight PreKey messages may still reference them.
            p[signedPreKeysKey] = encryptRecordMap(
                decryptRecordMap(p[signedPreKeysKey]) + (nextId to record.serialize()))
            p[currentSignedPreKeyIdKey] = nextId
            p[signedPreKeyCreatedAtKey] = now
            p[nextSignedIdKey] = nextId + 1
        }
        Log.i(TAG, "rotated signed prekey to id $nextId")
    }

    /** Local state is unusable (Keystore key lost, partial write): start over cleanly. */
    private suspend fun resetAndRegenerate(reason: String) {
        Log.w(TAG, "resetting E2EE keys: $reason")
        context.e2eeDataStore.edit { it.clear() }
        generateAndPublish()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun loadIdentity(): IdentityKeyPair? =
        context.e2eeDataStore.data.first()[identityKey]
            ?.let { KeystoreCrypto.decrypt(it) }
            ?.let { IdentityKeyPair(Base64.decode(it, Base64.NO_WRAP)) }

    /** {keyId: base64(record)} as one encrypted JSON string. */
    private fun encryptRecordMap(records: Map<Int, ByteArray>): String =
        KeystoreCrypto.encrypt(
            json.encodeToString(records.entries.associate { it.key.toString() to b64(it.value) }))

    private fun decryptRecordMap(stored: String?): Map<Int, ByteArray> {
        val plain = stored?.let { KeystoreCrypto.decrypt(it) } ?: return emptyMap()
        return json.decodeFromString<Map<String, String>>(plain)
            .entries.associate { it.key.toInt() to Base64.decode(it.value, Base64.NO_WRAP) }
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun PreKeyRecord.toDto() = OneTimePreKeyDto(id, b64(keyPair.publicKey.serialize()))

    private fun SignedPreKeyRecord.toDto() =
        SignedPreKeyDto(id, b64(keyPair.publicKey.serialize()), b64(signature))

    private fun KyberPreKeyRecord.toDto() =
        KyberPreKeyDto(id, b64(keyPair.publicKey.serialize()), b64(signature))

    private companion object {
        const val TAG = "KlicE2ee"
        const val PRE_KEY_BATCH = 100
        const val KYBER_BATCH = 50
        const val TOP_UP_THRESHOLD = 20
        const val SIGNED_PRE_KEY_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
