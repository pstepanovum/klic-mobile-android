package com.klic.mobile.app.data

import android.content.Context
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * "Sync Contacts" (§10.4): reads device contact emails + phone numbers, normalizes
 * them, and produces SHA-256 hex hashes — ONLY hashes ever leave the device.
 */
object ContactsSync {

    /** Collects the hash set for POST /me/contacts. Requires READ_CONTACTS. */
    suspend fun collectHashes(context: Context, cap: Int = 5000): List<String> =
        withContext(Dispatchers.IO) {
            val hashes = linkedSetOf<String>()
            runCatching {
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                    null, null, null,
                )?.use { cursor ->
                    while (cursor.moveToNext() && hashes.size < cap) {
                        normalizeEmail(cursor.getString(0))?.let { hashes += sha256(it) }
                    }
                }
            }
            runCatching {
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    null, null, null,
                )?.use { cursor ->
                    while (cursor.moveToNext() && hashes.size < cap) {
                        normalizePhone(cursor.getString(0))?.let { hashes += sha256(it) }
                    }
                }
            }
            hashes.toList()
        }

    private fun normalizeEmail(raw: String?): String? =
        raw?.trim()?.lowercase()?.takeIf { it.contains('@') }

    /** Digits only, keeping a leading `+` (no country-code guessing). */
    private fun normalizePhone(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        val plus = trimmed.startsWith("+")
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length < 5) return null
        return if (plus) "+$digits" else digits
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
