package com.klic.mobile.app.feature.chat.messagelist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.klic.mobile.app.data.LinkOpener
import com.klic.mobile.app.data.LinkPreview
import com.klic.mobile.app.data.LinkPreviewFetcher
import com.klic.mobile.app.data.Message

/**
 * Rich OpenGraph preview card shown beneath a text bubble (mirrors iOS). Finds the
 * first URL in the message body, fetches its OG metadata off the main thread, and
 * renders a compact card (image + title + site name). Renders nothing while loading
 * or when there's no usable preview, so the bubble is unchanged in the common case.
 * Tapping the card opens the link via the user's "Open links in" preference.
 */
@Composable
fun LinkPreviewCard(
    message: Message,
    modifier: Modifier = Modifier,
) {
    val url = remember(message.body) {
        LinkOpener.urlRegex.find(message.body)?.value?.let { raw ->
            when {
                raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
                else -> "https://$raw" // bare "www.…" match — OG fetch needs a scheme
            }
        }
    } ?: return

    var preview by remember(url) { mutableStateOf<LinkPreview?>(null) }
    LaunchedEffect(url) {
        preview = LinkPreviewFetcher.fetch(url)
    }

    val data = preview ?: return
    if (data.title.isNullOrBlank() && data.imageUrl.isNullOrBlank() && data.siteName.isNullOrBlank()) return

    val context = LocalContext.current
    Column(
        modifier
            .padding(top = 6.dp)
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { LinkOpener.open(context, url) },
    ) {
        if (!data.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = data.imageUrl,
                contentDescription = data.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
            )
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            data.siteName?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            data.title?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            data.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
