package com.klic.mobile.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest

/**
 * Stable Coil cache key for a remote media URL (§9.9): presigned URLs rotate their
 * signature query on every fetch, which would bust the disk cache and re-download
 * media on each re-entry. Keying on the path (which contains the object key) keeps
 * the cache hit while still refreshing when the underlying object changes.
 *
 * §10.1: the server appends `?v=<hash of avatarKey>` to avatar/cover URLs as a
 * content version. That param MUST stay in the key — dropping the whole query (as
 * v0.5.2 did) froze covers/avatars on their first cached bytes forever. Only the
 * rotating presign params (X-Amz-*, Signature, Expires, …) are stripped.
 */
fun stableImageKey(url: String): String {
    val base = url.substringBefore('?')
    val query = url.substringAfter('?', "")
    if (query.isEmpty()) return base
    val version = query.split('&').firstOrNull { it.startsWith("v=") }
    return if (version != null) "$base?$version" else base
}

/** ImageRequest with stable memory/disk cache keys for a (possibly presigned) URL. */
@Composable
fun rememberStableImageRequest(url: String?): ImageRequest? {
    val context = LocalContext.current
    return remember(url) {
        url?.let {
            ImageRequest.Builder(context)
                .data(it)
                .memoryCacheKey(stableImageKey(it))
                .diskCacheKey(stableImageKey(it))
                .build()
        }
    }
}
