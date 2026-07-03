package com.klic.mobile.app.data

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.klic.mobile.app.R

/**
 * §12.2: add + verify an email through Google. CredentialManager shows the Google
 * account picker, we send the resulting ID token to POST /me/email/google, and the
 * server verifies it against the web OAuth client (audience) before storing the email.
 *
 * The serverClientId is the Firebase WEB client id — read from the google-services
 * plugin's generated `default_web_client_id` resource, never hardcoded a second time.
 */
class GoogleEmailManager(private val repo: KlicRepository) {

    /** Runs the picker + server link. Returns the refreshed user (email now set). */
    suspend fun link(context: Context): User {
        val serverClientId = context.getString(R.string.default_web_client_id)
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            // Show every Google account on the device, not only previously authorized
            // ones — this is a first-time link, so there are none yet.
            .setFilterByAuthorizedAccounts(false)
            .build()
        val request = GetCredentialRequest(listOf(option))

        val result = try {
            CredentialManager.create(context).getCredential(context, request)
        } catch (e: GetCredentialCancellationException) {
            throw GoogleEmailException(context.getString(R.string.email_google_cancelled), e, cancelled = true)
        } catch (e: NoCredentialException) {
            throw GoogleEmailException(context.getString(R.string.email_google_no_account), e)
        } catch (e: Exception) {
            throw GoogleEmailException(context.getString(R.string.email_google_unavailable), e)
        }

        val credential = result.credential
        val idToken = if (
            credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            runCatching { GoogleIdTokenCredential.createFrom(credential.data).idToken }
                .getOrElse { throw GoogleEmailException(context.getString(R.string.email_google_unavailable), it) }
        } else {
            throw GoogleEmailException(context.getString(R.string.email_google_unavailable))
        }

        return try {
            repo.linkGoogleEmail(idToken)
        } catch (e: Exception) {
            throw GoogleEmailException(
                repo.serverMessage(e) ?: context.getString(R.string.email_link_failed),
                e,
            )
        }
    }
}

/** [cancelled] lets the UI swallow user-initiated dismissals silently. */
class GoogleEmailException(
    message: String,
    cause: Throwable? = null,
    val cancelled: Boolean = false,
) : Exception(message, cause)
