package com.klic.mobile.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Rich link previews: scrape OpenGraph tags from the first URL in a message and render
 * a card below the bubble (mirrors iOS). The server sends no preview metadata, so we
 * fetch client-side. Spotify (and many sites) gate their OG tags on a browser-like
 * User-Agent, so we spoof one. Purely best-effort — any failure yields null and the
 * message just renders as plain linkified text.
 */
object LinkPreviewFetcher {
    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    // Some pages are huge; cap what we read so a giant HTML body can't stall the card.
    private const val MAX_BYTES = 512 * 1024L

    private val client by lazy {
        OkHttpClient.Builder()
            .addInterceptor(DataUsage.interceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    // Completed positive lookups keyed by URL (ConcurrentHashMap forbids null values,
    // so "no preview" is tracked separately in [negatives]).
    private val cache = ConcurrentHashMap<String, LinkPreview>()
    private val negatives: MutableSet<String> = ConcurrentHashMap.newKeySet()
    // In-flight dedup: one network fetch per URL even if several bubbles request it.
    private val inFlight = ConcurrentHashMap<String, Mutex>()

    suspend fun fetch(url: String): LinkPreview? {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
        cache[url]?.let { return it }
        if (url in negatives) return null

        val lock = inFlight.getOrPut(url) { Mutex() }
        lock.withLock {
            // Another caller may have populated the cache while we waited on the lock.
            cache[url]?.let { return it }
            if (url in negatives) return null
            val result = runCatching { load(url) }.getOrNull()
            if (result != null) cache[url] = result else negatives.add(url)
            inFlight.remove(url)
            return result
        }
    }

    private suspend fun load(url: String): LinkPreview? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body ?: return@withContext null
            val source = body.source()
            source.request(MAX_BYTES)
            val html = source.buffer.snapshot(minOf(source.buffer.size, MAX_BYTES).toInt()).utf8()

            val title = ogContent(html, "og:title") ?: htmlTitle(html)
            val description = ogContent(html, "og:description")
            val image = ogContent(html, "og:image")?.let { absolutize(it, url) }
            val siteName = ogContent(html, "og:site_name")
            if (title == null && description == null && image == null) return@withContext null
            LinkPreview(
                url = url,
                title = title,
                description = description,
                imageUrl = image,
                siteName = siteName,
            )
        }
    }

    /**
     * Extract the `content` of an OG meta tag. Handles both attribute orders
     * (`property` before/after `content`) and single/double quotes.
     */
    private fun ogContent(html: String, property: String): String? {
        val p = Regex.escape(property)
        val patterns = listOf(
            // property=... then content=...
            Regex("""<meta[^>]+(?:property|name)=["']$p["'][^>]+content=["']([^"']*)["']""", RegexOption.IGNORE_CASE),
            // content=... then property=...
            Regex("""<meta[^>]+content=["']([^"']*)["'][^>]+(?:property|name)=["']$p["']""", RegexOption.IGNORE_CASE),
        )
        for (regex in patterns) {
            val match = regex.find(html)?.groupValues?.getOrNull(1)?.trim()
            if (!match.isNullOrEmpty()) return decodeEntities(match)
        }
        return null
    }

    private fun htmlTitle(html: String): String? {
        val match = Regex("""<title[^>]*>([^<]*)</title>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.trim()
        return match?.takeUnless { it.isEmpty() }?.let { decodeEntities(it) }
    }

    /** Resolve a possibly-relative or protocol-relative og:image against the page URL. */
    private fun absolutize(candidate: String, pageUrl: String): String? {
        if (candidate.startsWith("http://") || candidate.startsWith("https://")) return candidate
        return runCatching { pageUrl.toHttpUrl().resolve(candidate)?.toString() }.getOrNull()
    }

    private fun decodeEntities(s: String): String = s
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#x27;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
}
