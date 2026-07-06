package com.klic.mobile.app.feature.chat.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.klic.mobile.app.data.Attachment
import com.klic.mobile.app.ui.components.stableImageKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * §14.2: real first-frame thumbnails for VIDEO attachments, everywhere a video renders
 * (bubbles, bento tiles, the media browser). Cached like other images: an in-memory LRU
 * in front of JPEGs on disk, keyed on the presign-stable URL key (§9.9) so rotating
 * presign signatures never bust the cache.
 *
 * Sent videos are seeded instantly from the local file at send time ([seedFromLocal]);
 * received videos extract the frame with [MediaMetadataRetriever] straight off the
 * remote URL (range requests — the whole video is never downloaded), or from the
 * already-downloaded local copy when one exists.
 */
object VideoThumbnails {
    private const val TARGET_EDGE = 512

    private val memory = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun cacheKey(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(stableImageKey(url).toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(40)
    }

    private fun diskFile(context: Context, url: String): File =
        File(File(context.cacheDir, "video_thumbs").apply { mkdirs() }, "${cacheKey(url)}.jpg")

    /** Seed the cache from the local source right when the video is sent (instant). */
    suspend fun seedFromLocal(context: Context, remoteUrl: String, localUri: String) =
        withContext(Dispatchers.IO) {
            val key = cacheKey(remoteUrl)
            if (memory.get(key) != null || diskFile(context, remoteUrl).length() > 0L) return@withContext
            val bitmap = runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, Uri.parse(localUri))
                    retriever.frameAtTime?.let(::downscale)
                } finally {
                    retriever.release()
                }
            }.getOrNull() ?: return@withContext
            store(context, remoteUrl, bitmap)
        }

    /** Cached-or-extracted thumbnail for a VIDEO attachment; null while unavailable. */
    suspend fun thumbnail(context: Context, att: Attachment, conversationId: String?): Bitmap? {
        val key = cacheKey(att.url)
        memory.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            locks.getOrPut(key) { Mutex() }.withLock {
                memory.get(key)?.let { return@withLock it }
                val disk = diskFile(context, att.url)
                if (disk.length() > 0L) {
                    android.graphics.BitmapFactory.decodeFile(disk.absolutePath)?.let {
                        memory.put(key, it)
                        return@withLock it
                    }
                }
                val bitmap = extract(context, att, conversationId) ?: return@withLock null
                store(context, att.url, bitmap)
                bitmap
            }
        }
    }

    private fun extract(context: Context, att: Attachment, conversationId: String?): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            // Prefer an already-downloaded local copy; else stream the remote URL —
            // MMR issues ranged reads, so only the needed bytes travel.
            val local = AttachmentDownloads.cachedFile(context, att, conversationId)
                .takeIf { it.length() > 0L }
            if (local != null) {
                retriever.setDataSource(local.absolutePath)
            } else {
                retriever.setDataSource(att.url, emptyMap())
            }
            retriever.frameAtTime?.let(::downscale)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun store(context: Context, url: String, bitmap: Bitmap) {
        memory.put(cacheKey(url), bitmap)
        runCatching {
            diskFile(context, url).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)
            }
        }
    }

    private fun downscale(src: Bitmap): Bitmap {
        val edge = maxOf(src.width, src.height)
        if (edge <= TARGET_EDGE) return src
        val scale = TARGET_EDGE.toFloat() / edge
        val result = Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        // Free the full-resolution frame; only the 512px scaled copy is kept.
        if (result !== src) src.recycle()
        return result
    }
}

/** Composable hook: the thumbnail for a VIDEO attachment, landing as soon as it's ready. */
@Composable
fun rememberVideoThumbnail(att: Attachment, conversationId: String? = null): State<Bitmap?> {
    val context = LocalContext.current
    return produceState<Bitmap?>(initialValue = null, att.id) {
        value = VideoThumbnails.thumbnail(context, att, conversationId)
    }
}
