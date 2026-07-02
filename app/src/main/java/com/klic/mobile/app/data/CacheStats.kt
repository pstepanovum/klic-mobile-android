package com.klic.mobile.app.data

import android.content.Context
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Storage-usage scan for the Data & Storage page (§8.3), by category.
 *
 * Mapping (documented in the page footer too):
 * - `cache/attachments/` (recursive) — downloaded chat attachments, classified by file
 *   extension (the cache keeps the original file name): image → Photos, video → Videos,
 *   audio → Audio, known document types → Documents, unknown → Misc.
 * - `cache/coil_image_cache` — Coil's disk cache. Sticker SVG bytes are recovered by
 *   probing the cache for each catalog URL → Stickers; the remainder is inline chat
 *   photos/avatars → Photos.
 * - Everything else under `cacheDir` (temp captures, http caches…) → Misc.
 */
object CacheStats {
    data class Categories(
        val photos: Long = 0,
        val videos: Long = 0,
        val audio: Long = 0,
        val documents: Long = 0,
        val stickers: Long = 0,
        val misc: Long = 0,
    ) {
        val total: Long get() = photos + videos + audio + documents + stickers + misc
    }

    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "svg")
    private val videoExt = setOf("mp4", "mov", "webm", "mkv", "3gp", "m4v", "avi")
    private val audioExt = setOf("m4a", "mp3", "aac", "ogg", "opus", "wav", "flac", "amr")
    private val docExt = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf", "zip", "rar", "7z",
    )

    private fun attachmentsDir(context: Context) = File(context.cacheDir, "attachments")
    private fun coilDir(context: Context) = File(context.cacheDir, "coil_image_cache")

    suspend fun scan(context: Context, stickerUrls: List<String>): Categories =
        withContext(Dispatchers.IO) {
            var photos = 0L; var videos = 0L; var audio = 0L; var docs = 0L; var misc = 0L

            attachmentsDir(context).walkTopDown().filter { it.isFile }.forEach { file ->
                val size = file.length()
                when (file.extension.lowercase()) {
                    in imageExt -> photos += size
                    in videoExt -> videos += size
                    in audioExt -> audio += size
                    in docExt -> docs += size
                    else -> misc += size
                }
            }

            // Coil cache: recover sticker bytes via their catalog URLs; rest counts as Photos.
            val coilTotal = coilDir(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }
            var stickers = 0L
            val diskCache = runCatching { context.imageLoader.diskCache }.getOrNull()
            if (diskCache != null) {
                stickerUrls.forEach { url ->
                    runCatching {
                        diskCache.openSnapshot(url)?.use { snapshot ->
                            stickers += snapshot.data.toFile().length()
                        }
                    }
                }
            }
            stickers = stickers.coerceAtMost(coilTotal)
            photos += coilTotal - stickers

            // Everything else in the cache dir → Misc.
            context.cacheDir.listFiles().orEmpty().forEach { entry ->
                if (entry.name == "attachments" || entry.name == "coil_image_cache") return@forEach
                misc += entry.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }

            Categories(photos, videos, audio, docs, stickers, misc)
        }

    /** "Clear Entire Cache" — wipes every category above. */
    suspend fun clearAll(context: Context) = withContext(Dispatchers.IO) {
        attachmentsDir(context).deleteRecursively()
        runCatching { context.imageLoader.diskCache?.clear() }
        runCatching { context.imageLoader.memoryCache?.clear() }
        context.cacheDir.listFiles().orEmpty().forEach { entry ->
            if (entry.name == "coil_image_cache") return@forEach
            runCatching { entry.deleteRecursively() }
        }
    }

    /** Bytes cached for one conversation's attachments ("Manage storage", §8.4). */
    suspend fun conversationCachedBytes(context: Context, conversationId: String): Long =
        withContext(Dispatchers.IO) {
            File(attachmentsDir(context), conversationId)
                .walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }

    suspend fun clearConversation(context: Context, conversationId: String) =
        withContext(Dispatchers.IO) {
            File(attachmentsDir(context), conversationId).deleteRecursively()
            Unit
        }
}
