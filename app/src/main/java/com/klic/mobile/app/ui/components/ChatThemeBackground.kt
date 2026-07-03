package com.klic.mobile.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.klic.mobile.app.data.ChatThemeStore

/**
 * Chat background stack (§12.3): background color → subtle two-stop gradient →
 * low-opacity line-art pattern. Rendered behind the message list in every
 * conversation, and inside the theme page's live preview.
 */
@Composable
fun ChatThemeLayers(theme: ChatThemeStore.Snapshot, modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.background
    val isDark = background.luminance() < 0.5f
    Box(modifier.background(background)) {
        theme.gradientColors(isDark)?.let { (top, bottom) ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(listOf(top, bottom)),
                        alpha = theme.gradientAlpha,
                    ),
            )
        }
        ChatPatternImage(
            patternId = theme.patternId,
            alpha = theme.patternOpacity,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * One bundled pattern (1.svg…10.svg), tinted for the current mode (dark ink on light,
 * light ink on dark) and rasterized by Coil's SVG decoder at the composable's own
 * pixel size, so thumbnails and the full-screen background both render crisply.
 */
@Composable
fun ChatPatternImage(patternId: Int, alpha: Float, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ink = MaterialTheme.colorScheme.onBackground
    val request = remember(patternId) {
        ImageRequest.Builder(context)
            .data(ChatThemeStore.patternAsset(patternId))
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alpha = alpha,
        colorFilter = ColorFilter.tint(ink),
        modifier = modifier,
    )
}

/**
 * Applies the selected own-bubble color as the scheme's `primary` for everything
 * inside — own bubbles, the send button and the other in-chat accents follow in one
 * place. With the default (Klic red) selection this is a no-op.
 */
@Composable
fun ChatBubbleTheme(theme: ChatThemeStore.Snapshot, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(primary = theme.bubbleColor),
        typography = MaterialTheme.typography,
        content = content,
    )
}
