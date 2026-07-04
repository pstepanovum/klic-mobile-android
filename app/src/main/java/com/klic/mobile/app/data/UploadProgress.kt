package com.klic.mobile.app.data

import android.content.ContentResolver
import android.net.Uri
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException

/**
 * RequestBody wrapper that reports real bytes as they hit the socket (§9.1) —
 * this is what drives the optimistic upload pill's progress bar, so the bar
 * tracks actual network transfer instead of a fake animation.
 */
class ProgressRequestBody(
    private val bytes: ByteArray,
    private val mediaType: MediaType?,
    private val onBytesWritten: (Long) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = bytes.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        var written = 0
        while (written < bytes.size) {
            val chunk = minOf(SEGMENT, bytes.size - written)
            sink.write(bytes, written, chunk)
            sink.flush()
            written += chunk
            onBytesWritten(written.toLong())
        }
    }

    private companion object {
        // Small enough for a lively bar on photos, large enough not to throttle videos.
        const val SEGMENT = 32 * 1024
    }
}

/**
 * §13.15: streaming RequestBody for large media — reads straight from the content
 * Uri in fixed-size chunks so a multi-hundred-MB video never sits in memory, while
 * reporting the same byte-accurate progress as [ProgressRequestBody]. Re-opens the
 * stream on every attempt, so OkHttp-level and pill-level retries both work.
 */
class UriStreamRequestBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val mediaType: MediaType?,
    private val byteSize: Long,
    private val onBytesWritten: ((Long) -> Unit)? = null,
) : RequestBody() {

    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = byteSize

    override fun writeTo(sink: BufferedSink) {
        val input = resolver.openInputStream(uri)
            ?: throw IOException("Could not open $uri for upload")
        input.source().use { source ->
            var written = 0L
            while (true) {
                val read = source.read(sink.buffer, SEGMENT)
                if (read == -1L) break
                sink.flush()
                written += read
                onBytesWritten?.invoke(written)
            }
        }
    }

    private companion object {
        // Larger than the in-memory body's segment: big files favor throughput and
        // still update the bar many times per second on any realistic uplink.
        const val SEGMENT = 256L * 1024L
    }
}
