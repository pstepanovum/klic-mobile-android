package com.klic.mobile.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Local app lock (§10.4 "Passcode & Biometrics"): a 4–6 digit passcode stored as a
 * salted SHA-256 hash in EncryptedSharedPreferences — NEVER server-side — plus the
 * biometric-unlock toggle and the auto-lock window. [locked] drives the lock overlay.
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
            // Keystore corruption fallback — plain prefs still only ever hold a salted hash.
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

    /** Sets (or changes) the passcode. Digits only, 4–6 long — validated by the UI. */
    fun setPasscode(passcode: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_SALT, salt.joinToString("") { "%02x".format(it) })
            .putString(KEY_HASH, hash(passcode, salt))
            .apply()
        _enabled.value = true
    }

    /** Removes the passcode and unlocks. */
    fun clearPasscode() {
        prefs.edit().remove(KEY_HASH).remove(KEY_SALT).remove(KEY_BIOMETRIC).apply()
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

    fun verify(passcode: String): Boolean {
        val saltHex = prefs.getString(KEY_SALT, null) ?: return false
        val stored = prefs.getString(KEY_HASH, null) ?: return false
        val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return MessageDigest.isEqual(hash(passcode, salt).toByteArray(), stored.toByteArray())
    }

    fun unlock() { locked.value = false }

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

    private fun hash(passcode: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(passcode.toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
