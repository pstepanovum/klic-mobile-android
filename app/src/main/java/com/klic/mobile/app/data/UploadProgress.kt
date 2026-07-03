package com.klic.mobile.app.data

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

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
