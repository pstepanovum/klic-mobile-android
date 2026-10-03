package com.klic.mobile.app.data

import com.klic.mobile.app.data.PasscodeHashing.Verification
import com.klic.mobile.app.data.PasscodeHashing.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasscodeHashingTest {

    @Test
    fun hash_isVersionedPbkdf2WithRandomSalt() {
        val a = PasscodeHashing.hash("1234")
        val b = PasscodeHashing.hash("1234")
        assertNotEquals("fresh salt per hash", a, b)
        val parts = a.split('$')
        assertEquals(4, parts.size)
        assertEquals("pbkdf2_sha256", parts[0])
        assertTrue(parts[1].toInt() >= 120_000)
        assertEquals(PasscodeHashing.SALT_BYTES * 2, parts[2].length)
        assertEquals(PasscodeHashing.KEY_BYTES * 2, parts[3].length)
        assertFalse("passcode never stored", a.contains("1234"))
        assertTrue(PasscodeHashing.isCurrentFormat(a))
    }

    @Test
    fun verify_currentFormat() {
        val stored = PasscodeHashing.hash("482916")
        assertEquals(Verification.Match(needsRehash = false), PasscodeHashing.verify("482916", stored, null))
        assertEquals(Verification.Mismatch, PasscodeHashing.verify("482917", stored, null))
        assertEquals(Verification.Mismatch, PasscodeHashing.verify("48291", stored, null))
        // A stray legacy salt left behind must not change the outcome.
        assertEquals(Verification.Match(needsRehash = false), PasscodeHashing.verify("482916", stored, "00ff"))
    }

    @Test
    fun verify_knownVector() {
        // RFC-style cross-check: PBKDF2-HMAC-SHA256("password", "salt", 1, 32).
        val stored = "pbkdf2_sha256\$1\$${"salt".toByteArray().toHex()}\$" +
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b"
        assertEquals(Verification.Match(needsRehash = true), PasscodeHashing.verify("password", stored, null))
    }

    @Test
    fun verify_lowerIterationsFlagsRehash() {
        val stored = PasscodeHashing.hash("1111", iterations = 1_000)
        assertEquals(Verification.Match(needsRehash = true), PasscodeHashing.verify("1111", stored, null))
    }

    @Test
    fun verify_legacySha256_matchesAndRequestsMigration() {
        val salt = ByteArray(16) { it.toByte() }
        val legacy = PasscodeHashing.legacySha256("9876", salt).toHex()
        assertEquals(64, legacy.length)
        assertFalse(PasscodeHashing.isCurrentFormat(legacy))
        assertEquals(Verification.Match(needsRehash = true), PasscodeHashing.verify("9876", legacy, salt.toHex()))
        assertEquals(Verification.Mismatch, PasscodeHashing.verify("9875", legacy, salt.toHex()))
        // Legacy hash without its salt can't verify.
        assertEquals(Verification.Mismatch, PasscodeHashing.verify("9876", legacy, null))

        // Migration: re-hash the accepted passcode; the new hash verifies without the salt.
        val migrated = PasscodeHashing.hash("9876")
        assertEquals(Verification.Match(needsRehash = false), PasscodeHashing.verify("9876", migrated, null))
    }

    @Test
    fun verify_legacyMatchesOriginalStoreImplementation() {
        // Exactly what AppLockStore ≤ v0.6.19 wrote: hex(SHA-256(salt ‖ passcode)).
        val salt = ByteArray(16) { (it * 7).toByte() }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update("2580".toByteArray())
        val old = digest.digest().joinToString("") { "%02x".format(it) }
        val oldSaltHex = salt.joinToString("") { "%02x".format(it) }
        assertEquals(Verification.Match(needsRehash = true), PasscodeHashing.verify("2580", old, oldSaltHex))
    }

    @Test
    fun verify_malformedNeverThrows() {
        listOf(
            "", "pbkdf2_sha256$", "pbkdf2_sha256\$x\$00\$00", "pbkdf2_sha256\$0\$00\$00",
            "pbkdf2_sha256\$10\$zz\$00", "pbkdf2_sha256\$10\$\$", "pbkdf2_sha256\$10\$00\$00\$00",
            "nothex", "abc",
        ).forEach { stored ->
            assertEquals(stored, Verification.Mismatch, PasscodeHashing.verify("1234", stored, "0011"))
        }
    }

    @Test
    fun lockout_freeAttemptsThenExponentialCapped() {
        for (n in 0..4) assertEquals(0L, PasscodeLockout.lockoutDurationMs(n))
        assertEquals(30_000L, PasscodeLockout.lockoutDurationMs(5))
        assertEquals(60_000L, PasscodeLockout.lockoutDurationMs(6))
        assertEquals(120_000L, PasscodeLockout.lockoutDurationMs(7))
        assertEquals(1_920_000L, PasscodeLockout.lockoutDurationMs(11))
        assertEquals(3_600_000L, PasscodeLockout.lockoutDurationMs(12))
        assertEquals(3_600_000L, PasscodeLockout.lockoutDurationMs(100))
        assertEquals(3_600_000L, PasscodeLockout.lockoutDurationMs(Int.MAX_VALUE))
    }

    @Test
    fun lockout_remainingCountsDownAndHandlesClockRollback() {
        val t = 1_000_000L
        assertEquals(0L, PasscodeLockout.remainingMs(4, t, t))
        assertEquals(30_000L, PasscodeLockout.remainingMs(5, t, t))
        assertEquals(20_000L, PasscodeLockout.remainingMs(5, t, t + 10_000))
        assertEquals(0L, PasscodeLockout.remainingMs(5, t, t + 30_000))
        assertEquals(0L, PasscodeLockout.remainingMs(5, t, t + 99_000))
        // Clock set back before the last failure: the full lockout still applies.
        assertEquals(60_000L, PasscodeLockout.remainingMs(6, t, t - 500_000))
    }
}
