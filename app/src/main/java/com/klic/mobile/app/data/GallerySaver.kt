package com.klic.mobile.app.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * "Save to Photos" (§8.4): when a chat is set to Always, incoming photos/videos are
 * written to the OS gallery via MediaStore as they're downloaded. Requires API 29+
 * (scoped storage — no write permission needed); older devices skip auto-save.
 */
object GallerySaver {
    private val client by lazy { OkHttpClient.Builder().addInterceptor(DataUsage.interceptor).build() }

    /** Auto-save an incoming message's media per the chat's Save-to-Photos pref. */
    suspend fun maybeAutoSave(context: Context, message: Message) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val snap = SettingsStore.snapshot.value
        if (snap.saveToPhotos[message.conversationId] != SettingsStore.SAVE_ALWAYS) return
        val onWifi = DataUsage.isOnWifi()
        message.attachments.forEach { att ->
            val isImage = att.kind == "IMAGE"
            val isVideo = att.kind == "VIDEO"
            if (!isImage && !isVideo) return@forEach
            // Videos aren't auto-fetched by the chat UI — only pull one here when the
            // auto-download matrix allows video on the current network.
            if (isVideo && !snap.autoDownloadAllowed(SettingsStore.KIND_VIDEO, onWifi)) return@forEach
            if (isImage && !snap.autoDownloadAllowed(SettingsStore.KIND_PHOTOS, onWifi)) return@forEach
            if (att.id in SettingsStore.snapshot.value.savedGalleryIds) return@forEach
            if (save(context, att)) SettingsStore.markSavedToGallery(att.id)
        }
    }

    /** Download [att] and insert it into the gallery. True on success. */
    suspend fun save(context: Context, att: Attachment): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext false
        runCatching {
            val isVideo = att.kind == "VIDEO" || att.contentType.startsWith("video/")
            val collection =
                if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val name = att.fileName ?: "klic_${att.id}.${if (isVideo) "mp4" else "jpg"}"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, att.contentType.ifBlank { if (isVideo) "video/mp4" else "image/jpeg" })
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${if (isVideo) "Movies" else "Pictures"}/Klic")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(collection, values) ?: return@runCatching false
            client.newCall(Request.Builder().url(att.url).build()).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    resolver.delete(uri, null, null)
                    return@runCatching false
                }
                resolver.openOutputStream(uri)?.use { out -> body.byteStream().copyTo(out) }
                    ?: run { resolver.delete(uri, null, null); return@runCatching false }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        }.getOrDefault(false)
    }
}
