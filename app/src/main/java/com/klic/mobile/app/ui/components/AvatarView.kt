package com.klic.mobile.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest

/** Circular avatar that loads a remote image, falling back to the user's initials. */
@Composable
fun AvatarView(url: String?, name: String, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val initials = remember(name) { initialsOf(name) }
    val context = LocalContext.current
    val request = remember(url) {
        url?.let {
            ImageRequest.Builder(context)
                .data(it)
                // §9.9: presign-stable keys — avatars never re-download on re-entry.
                .memoryCacheKey(stableImageKey(it))
                .diskCacheKey(stableImageKey(it))
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .networkCachePolicy(CachePolicy.ENABLED)
                .crossfade(false)
                .build()
        }
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        if (request == null) {
            Initials(initials, size)
        } else {
            SubcomposeAsyncImage(
                model = request,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { Initials(initials, size) },
                error = { Initials(initials, size) },
            )
        }
    }
}

@Composable
private fun Initials(text: String, size: Dp) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        fontSize = (size.value * 0.4f).sp,
    )
}

private fun initialsOf(name: String): String {
    val letters = name.trim().split(" ").filter { it.isNotEmpty() }.take(2)
        .mapNotNull { it.firstOrNull()?.toString() }.joinToString("")
    return letters.ifEmpty { "?" }.uppercase()
}
