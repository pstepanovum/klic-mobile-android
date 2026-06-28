package com.klicmobile.app.data

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long

private val Context.dataStore by preferencesDataStore(name = "klic_tokens")

/** Persists access + refresh tokens (and the cached user). Swap to EncryptedSharedPreferences before release. */
class TokenStore(private val context: Context) {
    private val accessKey = stringPreferencesKey("access")
    private val refreshKey = stringPreferencesKey("refresh")
    private val userKey = stringPreferencesKey("user")

    @Volatile var cachedAccess: String? = null
        private set
    @Volatile var cachedRefresh: String? = null
        private set

    /** We have a refresh token worth trying to restore a session from. */
    val hasSession: Boolean get() = cachedRefresh != null

    suspend fun load() {
        val prefs = context.dataStore.data.first()
        cachedAccess = prefs[accessKey]
        cachedRefresh = prefs[refreshKey]
    }

    suspend fun save(access: String, refresh: String) {
        cachedAccess = access
        cachedRefresh = refresh
        context.dataStore.edit {
            it[accessKey] = access
            it[refreshKey] = refresh
        }
    }

    suspend fun clear() {
        cachedAccess = null
        cachedRefresh = null
        context.dataStore.edit { it.clear() }
    }

    suspend fun saveUser(json: String) {
        context.dataStore.edit { it[userKey] = json }
    }

    suspend fun loadUser(): String? = context.dataStore.data.first()[userKey]

    // Synchronous variants for the OkHttp Authenticator, which runs off the main thread.
    fun saveBlocking(access: String, refresh: String) = runBlocking { save(access, refresh) }
    fun clearBlocking() = runBlocking { clear() }
}

/**
 * Lightweight inspection of the access-token JWT so the client can tell whether a
 * proactive refresh is actually needed — avoiding a token rotation on every launch.
 */
object AccessToken {
    private val json = Json { ignoreUnknownKeys = true }

    /** True when there is no token, it can't be parsed, or it expires within [leewaySec]. */
    fun isExpired(token: String?, leewaySec: Long = 30): Boolean {
        val exp = (claims(token)?.get("exp") as? JsonPrimitive)?.long ?: return true
        return exp - System.currentTimeMillis() / 1000 <= leewaySec
    }

    /** The `sub` claim (user id) of the access token. */
    fun subject(token: String?): String? =
        (claims(token)?.get("sub") as? JsonPrimitive)?.contentOrNull

    private fun claims(token: String?): Map<String, kotlinx.serialization.json.JsonElement>? {
        if (token == null) return null
        val parts = token.split(".")
        if (parts.size != 3) return null
        return try {
            val flags = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            val payload = String(Base64.decode(parts[1], flags))
            json.parseToJsonElement(payload).jsonObject
        } catch (_: Exception) {
            null
        }
    }
}
