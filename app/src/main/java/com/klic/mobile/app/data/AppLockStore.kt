package com.klic.mobile.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Local app lock (§10.4 "Passcode & Biometrics"): a 4–6 digit passcode stored as a
 * salted PBKDF2-HMAC-SHA256 hash ([PasscodeHashing]) in EncryptedSharedPreferences —
 * NEVER server-side, never the passcode itself — plus the biometric-unlock toggle, the
 * auto-lock window and the failed-attempt throttle ([PasscodeLockout]). Legacy salted
 * SHA-256 hashes are re-hashed on the next correct entry. [locked] drives the overlay.
 * Both prefs files are excluded from backups (res/xml backup + data-extraction rules).
 */
object AppLockStore {
    /** Auto-lock modes. */
    const val LOCK_IMMEDIATELY = "immediately"
    const val LOCK_AFTER_1_MIN = "1min"
    const val LOCK_AFTER_5_MIN = "5min"
    const val LOCK_ON_BACKGROUND = "background"

    private const val KEY_HASH = "passcode_hash"
    private const val KEY_SALT = "passcode_salt"
    private const val KEY_BIOMETRIC = "biometric_enabled"
    private const val KEY_AUTOLOCK = "autolock_mode"
    private const val KEY_LENGTH = "passcode_length"
    private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
    private const val KEY_LAST_FAILURE_AT = "last_failure_at"

    /** Outcome of a passcode attempt. */
    sealed interface VerifyResult {
        data object Success : VerifyResult
        data object Wrong : VerifyResult
        /** Throttled — the attempt was NOT checked. Retry after [remainingMs]. */
        data class LockedOut(val remainingMs: Long) : VerifyResult
    }

    private lateinit var prefs: SharedPreferences

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    /** True while the lock overlay must cover the app. */
    val locked = MutableStateFlow(false)

    /** Wall-clock millis when the app last went to background (for timed auto-lock). */
    private var backgroundedAt: Long? = null

    /** Idempotent; call once from Application.onCreate. */
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = runCatching {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context.applicationContext,
                "klic_app_lock",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse {
            // Keystore corruption fallback: plain (unencrypted, MODE_PRIVATE) prefs. They
            // still only ever hold the salted PBKDF2 hash (or a legacy salted SHA-256 one
            // awaiting migration), the passcode length and throttle counters — never the
            // passcode — and the file is excluded from cloud backup / device transfer.
            context.applicationContext.getSharedPreferences("klic_app_lock_fallback", Context.MODE_PRIVATE)
        }
        _enabled.value = prefs.contains(KEY_HASH)
        locked.value = _enabled.value
    }

    val isEnabled: Boolean get() = _enabled.value

    var biometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC, false)
        set(value) { prefs.edit().putBoolean(KEY_BIOMETRIC, value).apply() }

    var autoLockMode: String
        get() = prefs.getString(KEY_AUTOLOCK, LOCK_ON_BACKGROUND) ?: LOCK_ON_BACKGROUND
        set(value) { prefs.edit().putString(KEY_AUTOLOCK, value).apply() }

    /**
     * Length of the current passcode, so the unlock keypad can check exactly once per
     * attempt (instead of at every length 4–6, which would burn throttle attempts).
     * Null for legacy installs until their first successful unlock migrates them.
     */
    val passcodeLength: Int?
        get() = prefs.getInt(KEY_LENGTH, 0).takeIf { it in 4..6 }

    /** Sets (or changes) the passcode. Digits only, 4–6 long — validated by the UI. */
    @Synchronized
    fun setPasscode(passcode: String) {
        prefs.edit()
            .remove(KEY_SALT)
            .putString(KEY_HASH, PasscodeHashing.hash(passcode))
            .putInt(KEY_LENGTH, passcode.length)
            .remove(KEY_FAILED_ATTEMPTS)
            .remove(KEY_LAST_FAILURE_AT)
            .apply()
        _enabled.value = true
    }

    /** Removes the passcode and unlocks. */
    @Synchronized
    fun clearPasscode() {
        prefs.edit()
            .remove(KEY_HASH).remove(KEY_SALT).remove(KEY_LENGTH).remove(KEY_BIOMETRIC)
            .remove(KEY_FAILED_ATTEMPTS).remove(KEY_LAST_FAILURE_AT)
            .apply()
        _enabled.value = false
        locked.value = false
    }

    /**
     * §13.12: full reset on ANY transition to the signed-out state (logout, account
     * deletion, rejected refresh token) — passcode hash, biometric toggle AND the
     * auto-lock mode all go, so the next account starts from a clean slate.
     */
    fun wipe() {
        if (!::prefs.isInitialized) return
        prefs.edit().clear().apply()
        _enabled.value = false
        locked.value = false
        backgroundedAt = null
    }

    /** Millis until another passcode attempt is allowed (0 = not throttled). */
    @Synchronized
    fun lockoutRemainingMs(): Long {
        val failures = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
        if (failures == 0) return 0L
        val now = System.currentTimeMillis()
        var lastFailureAt = prefs.getLong(KEY_LAST_FAILURE_AT, now)
        if (now < lastFailureAt) {
            // Clock moved backwards: re-anchor so the lockout runs its full length from
            // now (rather than never expiring, or being skipped by a clock rollback).
            lastFailureAt = now
            prefs.edit().putLong(KEY_LAST_FAILURE_AT, now).commit()
        }
        return PasscodeLockout.remainingMs(failures, lastFailureAt, now)
    }

    /**
     * Checks [passcode]. Slow by design (PBKDF2) — call off the main thread. While
     * throttled the attempt is rejected unchecked. A wrong passcode is counted unless
     * [countFailure] is false (only for the legacy keypad's intermediate-length probes,
     * see [passcodeLength]). Counter writes use commit() so killing the app right after
     * a wrong guess can't drop the failure. Success resets the counter and migrates a
     * legacy / weaker hash to the current PBKDF2 format.
     */
    @Synchronized
    fun verify(passcode: String, countFailure: Boolean = true): VerifyResult {
        val remaining = lockoutRemainingMs()
        if (remaining > 0) return VerifyResult.LockedOut(remaining)
        val stored = prefs.getString(KEY_HASH, null) ?: return VerifyResult.Wrong
        val legacySalt = prefs.getString(KEY_SALT, null)
        return when (val v = PasscodeHashing.verify(passcode, stored, legacySalt)) {
            is PasscodeHashing.Verification.Match -> {
                val edit = prefs.edit()
                    .remove(KEY_FAILED_ATTEMPTS)
                    .remove(KEY_LAST_FAILURE_AT)
                    .putInt(KEY_LENGTH, passcode.length)
                if (v.needsRehash) {
                    edit.putString(KEY_HASH, PasscodeHashing.hash(passcode)).remove(KEY_SALT)
                }
                edit.commit()
                VerifyResult.Success
            }
            PasscodeHashing.Verification.Mismatch -> {
                if (!countFailure) return VerifyResult.Wrong
                val failures = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
                val now = System.currentTimeMillis()
                prefs.edit()
                    .putInt(KEY_FAILED_ATTEMPTS, failures)
                    .putLong(KEY_LAST_FAILURE_AT, now)
                    .commit()
                val lockout = PasscodeLockout.remainingMs(failures, now, now)
                if (lockout > 0) VerifyResult.LockedOut(lockout) else VerifyResult.Wrong
            }
        }
    }

    /** Called after a successful passcode or biometric check. */
    @Synchronized
    fun unlock() {
        // Biometric success proves the owner too — clear any pending passcode throttle.
        if (prefs.contains(KEY_FAILED_ATTEMPTS)) {
            prefs.edit().remove(KEY_FAILED_ATTEMPTS).remove(KEY_LAST_FAILURE_AT).apply()
        }
        locked.value = false
    }

    /** App moved to background — remember when, for the timed auto-lock windows. */
    fun onAppBackgrounded() {
        if (!isEnabled) return
        backgroundedAt = System.currentTimeMillis()
        if (autoLockMode == LOCK_IMMEDIATELY || autoLockMode == LOCK_ON_BACKGROUND) {
            locked.value = true
        }
    }

    /** App returned to foreground — lock if the auto-lock window elapsed. */
    fun onAppForegrounded() {
        if (!isEnabled) return
        val away = backgroundedAt?.let { System.currentTimeMillis() - it } ?: return
        val threshold = when (autoLockMode) {
            LOCK_AFTER_1_MIN -> 60_000L
            LOCK_AFTER_5_MIN -> 300_000L
            else -> return  // immediately/background already locked in onAppBackgrounded
        }
        if (away >= threshold) locked.value = true
    }
}
