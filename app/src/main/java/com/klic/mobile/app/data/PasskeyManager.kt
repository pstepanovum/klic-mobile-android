package com.klic.mobile.app.data

import android.content.Context
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Passkeys (§10.4) via androidx.credentials CredentialManager:
 * - register: server options → platform create → server verify
 * - sign-in: server options → platform get → server verify → token pair
 * Both degrade gracefully — the platform can refuse (no screen lock, no Play
 * services, assetlinks not yet live); callers surface [PasskeyException.message].
 */
class PasskeyManager(private val repo: KlicRepository) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Adds a passkey for the signed-in user. Throws [PasskeyException] on refusal. */
    suspend fun register(context: Context) {
        val options = runCatching { repo.passkeyRegisterOptions() }
            .getOrElse { throw PasskeyException("Couldn't fetch passkey options from the server.", it) }
        val request = CreatePublicKeyCredentialRequest(requestJson = options.toString())
        val response = try {
            CredentialManager.create(context).createCredential(context, request)
        } catch (e: Exception) {
            throw PasskeyException(friendlyPlatformError(e), e)
        }
        val credentialJson = (response as? CreatePublicKeyCredentialResponse)?.registrationResponseJson
            ?: throw PasskeyException("Unexpected credential type from the platform.")
        runCatching { repo.passkeyRegisterVerify(parse(credentialJson)) }
            .getOrElse { throw PasskeyException("The server rejected the new passkey.", it) }
    }

    /** Passkey sign-in from the login screen. Returns the signed-in user. */
    suspend fun signIn(context: Context): User {
        val options = runCatching { repo.passkeyLoginOptions() }
            .getOrElse { throw PasskeyException("Couldn't fetch sign-in options from the server.", it) }
        val request = GetCredentialRequest(
            listOf(GetPublicKeyCredentialOption(requestJson = options.toString())),
        )
        val result = try {
            CredentialManager.create(context).getCredential(context, request)
        } catch (e: Exception) {
            throw PasskeyException(friendlyPlatformError(e), e)
        }
        val credential = result.credential as? PublicKeyCredential
            ?: throw PasskeyException("Unexpected credential type from the platform.")
        return runCatching { repo.passkeyLogin(parse(credential.authenticationResponseJson)) }
            .getOrElse { throw PasskeyException("The server rejected the passkey sign-in.", it) }
    }

    private fun parse(raw: String): JsonObject =
        runCatching { json.parseToJsonElement(raw) as JsonObject }
            .getOrElse { throw PasskeyException("Malformed credential payload.") }

    private fun friendlyPlatformError(e: Exception): String = when {
        e is androidx.credentials.exceptions.GetCredentialCancellationException ||
            e is androidx.credentials.exceptions.CreateCredentialCancellationException ->
            "Passkey prompt was cancelled."
        e is androidx.credentials.exceptions.NoCredentialException ->
            "No passkey available on this device for Klic."
        else -> "This device refused the passkey request (${e::class.java.simpleName})."
    }
}

class PasskeyException(message: String, cause: Throwable? = null) : Exception(message, cause)
