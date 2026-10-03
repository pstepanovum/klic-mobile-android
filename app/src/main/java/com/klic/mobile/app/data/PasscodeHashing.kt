package com.klic.mobile.app.data

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * App-lock passcode hashing (§10.4) — pure JVM so it is unit-testable.
 *
 * Current format (one string): `pbkdf2_sha256$<iterations>$<saltHex>$<hashHex>` —
 * PBKDF2WithHmacSHA256, 16-byte random salt, 32-byte output. Storing the iteration
 * count lets a future bump re-hash transparently via [Verification.Match.needsRehash].
 *
 * Legacy format (≤ v0.6.19): a bare hex SHA-256(salt ‖ passcode) with the salt hex kept
 * under a separate key. It is still accepted by [verify] and flagged for re-hashing.
 */
object PasscodeHashing {
    const val ITERATIONS = 120_000
    const val SALT_BYTES = 16
    const val KEY_BYTES = 32
    private const val PREFIX = "pbkdf2_sha256"

    sealed interface Verification {
        /** Correct passcode; [needsRehash] → caller should store [hash] of it now. */
        data class Match(val needsRehash: Boolean) : Verification
        data object Mismatch : Verification
    }

    private val random = SecureRandom()

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { random.nextBytes(it) }

    /** Hashes [passcode] in the current (versioned PBKDF2) format. */
    fun hash(passcode: String, salt: ByteArray = newSalt(), iterations: Int = ITERATIONS): String =
        "$PREFIX\$$iterations\$${salt.toHex()}\$${pbkdf2(passcode, salt, iterations).toHex()}"

    /** True if [stored] is in the current PBKDF2 format (vs. the legacy SHA-256 one). */
    fun isCurrentFormat(stored: String): Boolean = stored.startsWith("$PREFIX\$")

    /**
     * Constant-time check of [passcode] against [stored]. [legacySaltHex] is only
     * consulted for a legacy (non-prefixed) hash. Malformed input never throws — it
     * simply doesn't match.
     */
    fun verify(passcode: String, stored: String, legacySaltHex: String?): Verification {
        if (isCurrentFormat(stored)) {
            val parts = stored.split('$')
            if (parts.size != 4) return Verification.Mismatch
            val iterations = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return Verification.Mismatch
            val salt = parts[2].hexToBytesOrNull() ?: return Verification.Mismatch
            val expected = parts[3].hexToBytesOrNull() ?: return Verification.Mismatch
            if (salt.isEmpty() || expected.isEmpty()) return Verification.Mismatch
            val actual = pbkdf2(passcode, salt, iterations, expected.size)
            return if (MessageDigest.isEqual(actual, expected)) {
                Verification.Match(needsRehash = iterations < ITERATIONS)
            } else {
                Verification.Mismatch
            }
        }
        // Legacy salted SHA-256 — correct passcodes get migrated by the caller.
        val salt = legacySaltHex?.hexToBytesOrNull() ?: return Verification.Mismatch
        val expected = stored.hexToBytesOrNull() ?: return Verification.Mismatch
        return if (MessageDigest.isEqual(legacySha256(passcode, salt), expected)) {
            Verification.Match(needsRehash = true)
        } else {
            Verification.Mismatch
        }
    }

    /** The pre-PBKDF2 scheme, kept for verifying/migrating existing installs (and tests). */
    fun legacySha256(passcode: String, salt: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(passcode.toByteArray())
        return digest.digest()
    }

    private fun pbkdf2(passcode: String, salt: ByteArray, iterations: Int, bytes: Int = KEY_BYTES): ByteArray {
        val chars = passcode.toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, bytes * 8)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    fun String.hexToBytesOrNull(): ByteArray? {
        if (length % 2 != 0) return null
        val out = ByteArray(length / 2)
        for (i in out.indices) {
            val hi = Character.digit(this[2 * i], 16)
            val lo = Character.digit(this[2 * i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}

/**
 * Failed-attempt throttling for the app lock. The first [FREE_ATTEMPTS] − 1 consecutive
 * failures are free; the [FREE_ATTEMPTS]th and every one after it start a lockout of
 * [BASE_LOCKOUT_MS] doubling per extra failure (30 s, 60 s, 120 s, …) capped at
 * [MAX_LOCKOUT_MS]. A success resets the counter.
 */
object PasscodeLockout {
    const val FREE_ATTEMPTS = 5
    const val BASE_LOCKOUT_MS = 30_000L
    const val MAX_LOCKOUT_MS = 3_600_000L

    /** Lockout that starts when the [failures]th consecutive failure is recorded. */
    fun lockoutDurationMs(failures: Int): Long {
        if (failures < FREE_ATTEMPTS) return 0L
        val doublings = failures - FREE_ATTEMPTS
        // 30 s · 2^7 already exceeds the 1 h cap — avoid shifting into overflow.
        if (doublings >= 7) return MAX_LOCKOUT_MS
        return minOf(BASE_LOCKOUT_MS shl doublings, MAX_LOCKOUT_MS)
    }

    /**
     * Millis left before another attempt is allowed. A wall clock that moved backwards
     * past the last failure (manual clock rollback) is treated as "full lockout still
     * pending" rather than as elapsed time.
     */
    fun remainingMs(failures: Int, lastFailureAt: Long, now: Long): Long {
        val duration = lockoutDurationMs(failures)
        if (duration == 0L) return 0L
        if (now < lastFailureAt) return duration
        return (lastFailureAt + duration - now).coerceAtLeast(0L)
    }
}
