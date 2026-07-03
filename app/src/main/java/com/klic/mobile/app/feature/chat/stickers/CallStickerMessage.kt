package com.klic.mobile.app.feature.chat.stickers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.klic.mobile.app.data.CallEvent
import com.klic.mobile.app.data.Message
import com.klic.mobile.app.data.Sticker
import com.klic.mobile.app.ui.components.rememberStableImageRequest
import androidx.compose.ui.res.stringResource
import com.klic.mobile.app.R

/** A centered, tappable call-log row (Telegram/WhatsApp style). Tap calls the peer back. */
@Composable
fun CallEventBubble(call: CallEvent, outgoing: Boolean, time: String, onCallBack: (String) -> Unit) {
    val video = call.isVideo
    val missed = call.outcome != "completed"
    val title = when {
        missed && outgoing -> if (video) stringResource(R.string.call_event_video) else stringResource(R.string.call_event_voice)
        missed -> if (video) stringResource(R.string.call_event_missed_video) else stringResource(R.string.call_event_missed_voice)
        else -> if (video) stringResource(R.string.call_event_video) else stringResource(R.string.call_event_voice)
    }
    val detail = when {
        !missed -> callDuration(call.durationMs)
        outgoing -> stringResource(R.string.call_status_no_answer)
        else -> stringResource(R.string.call_event_tap_to_call_back)
    }
    val tint = if (missed) Color(0xFFE5484D) else MaterialTheme.colorScheme.primary

    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .clickable { onCallBack(call.kind) }
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = if (video) Icons.Filled.Videocam else Icons.Filled.Call,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = if (missed) tint else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A sent/received sticker, rendered from the server SVG via the SVG-capable Coil loader. */
@Composable
fun StickerBubble(message: Message, isMine: Boolean, time: String?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
    ) {
        AsyncImage(
            // §9.9: stable cache key survives the sticker URL's rotating presign query.
            model = rememberStableImageRequest(message.stickerUrl),
            contentDescription = "Sticker",
            modifier = Modifier.size(124.dp),
        )
        if (time != null) {
            Text(
                time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/** Grid of the built-in sticker pack inside the composer's bottom sheet. */
@Composable
fun StickerPickerSheet(stickers: List<Sticker>, onPick: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(stickers, key = { it.id }) { sticker ->
            AsyncImage(
                model = rememberStableImageRequest(sticker.url),
                contentDescription = sticker.id,
                modifier = Modifier
                    .size(96.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                    .clickable { onPick(sticker.id) }
                    .padding(8.dp),
            )
        }
    }
}

private fun callDuration(ms: Int?): String {
    val s = (ms ?: 0).coerceAtLeast(0) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}
