package com.klic.mobile.app.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent

/**
 * "Open links in" (§10.4): every chat link tap routes through here and honors the
 * user's choice — Klic in-app (Chrome Custom Tabs), Chrome, or the system default.
 * The "Don't open links in-app" toggle forces external regardless of the mode.
 */
object LinkOpener {

    fun open(context: Context, rawUrl: String) {
        val url = normalize(rawUrl) ?: return
        val settings = SettingsStore.snapshot.value
        val mode = if (settings.neverOpenLinksInApp) SettingsStore.LINKS_SYSTEM else settings.linkOpenMode
        when (mode) {
            SettingsStore.LINKS_IN_APP -> openInApp(context, url)
            SettingsStore.LINKS_CHROME -> openInChrome(context, url)
            else -> openExternal(context, url)
        }
    }

    private fun openInApp(context: Context, url: Uri) {
        runCatching {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(context, url)
        }.onFailure { openExternal(context, url) }
    }

    private fun openInChrome(context: Context, url: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, url).setPackage("com.android.chrome")
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            openExternal(context, url)
        }
    }

    private fun openExternal(context: Context, url: Uri) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url)) }
            .onFailure { Toast.makeText(context, "No app can open this link.", Toast.LENGTH_SHORT).show() }
    }

    /** "Clear cookies": wipes the in-app browsing website data store (best-effort). */
    fun clearCookies(onDone: () -> Unit) {
        runCatching {
            CookieManager.getInstance().removeAllCookies { onDone() }
            WebStorage.getInstance().deleteAllData()
        }.onFailure { onDone() }
    }

    private fun normalize(raw: String): Uri? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
                         else "https://$trimmed"
        return runCatching { Uri.parse(withScheme) }.getOrNull()
    }

    /** URL ranges inside a message body — drives tappable link styling in bubbles. */
    val urlRegex = Regex("""(?i)\b((?:https?://|www\.)[^\s<>()\[\]{}"']+)""")
}
