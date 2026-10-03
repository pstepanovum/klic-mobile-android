package com.klic.mobile.app.feature.chat.messagelist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.R
import com.klic.mobile.app.data.Message
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.ui.res.stringResource

// Compact preview text for the composer's reply bar.
@Composable
internal fun messagePreview(m: Message): String = when {
    m.body.isNotBlank() -> m.body
    m.isSticker -> stringResource(R.string.preview_sticker)
    m.attachments.firstOrNull()?.kind == "IMAGE" -> stringResource(R.string.preview_photo)
    m.attachments.firstOrNull()?.kind == "VOICE" -> stringResource(R.string.preview_voice_message)
    m.attachments.firstOrNull()?.kind == "VIDEO_NOTE" -> stringResource(R.string.preview_video_message)
    m.attachments.firstOrNull()?.kind == "VIDEO" -> stringResource(R.string.preview_video)
    m.attachments.isNotEmpty() -> stringResource(R.string.preview_file)
    else -> stringResource(R.string.preview_message)
}

// Presence subtitle for the chat header: "Online" or "last seen …".
@Composable
internal fun presenceSubtitle(presence: com.klic.mobile.app.realtime.SocketService.Presence?): String? {
    if (presence == null) return null
    if (presence.online) return stringResource(R.string.presence_online)
    val ms = presence.lastSeenMs ?: return null
    val date = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val time = DateTimeFormatter.ofPattern("HH:mm").format(date)
    return when (date.toLocalDate()) {
        LocalDate.now() -> stringResource(R.string.presence_last_seen_at, time)
        LocalDate.now().minusDays(1) -> stringResource(R.string.presence_last_seen_yesterday)
        else -> stringResource(R.string.presence_last_seen_on, DateTimeFormatter.ofPattern("MMM d").format(date))
    }
}

@Composable
internal fun DateSeparator(isoDate: String) {
    val label = dateLabelText(isoDate)
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

// MARK: - Helpers

/**
 * §19.1: a stable LazyColumn `contentType` for a message row so the list only recycles
 * a row's layout nodes into structurally-similar rows (image bubble ↔ image bubble),
 * instead of re-inflating a text bubble's slot into an image grid mid-scroll. Rows that
 * share a content type reuse their sub-composition, which is what keeps scrolling smooth.
 */
internal fun messageContentType(m: Message): String = when {
    m.isDeleted -> "deleted"
    m.kind == "SYSTEM" -> "system"
    m.isCallEvent -> "call"
    m.isSticker -> "sticker"
    m.attachments.any { it.kind == "VOICE" } -> "voice"
    m.attachments.any { it.kind == "VIDEO_NOTE" } -> "videonote"
    m.attachments.any { it.kind == "IMAGE" || it.kind == "VIDEO" } -> "media"
    m.attachments.any { it.kind == "FILE" } -> "file"
    else -> "text"
}

// Compare local calendar days (the ISO strings are UTC, so their date prefix
// would split day separators at UTC midnight rather than the user's midnight).
internal fun sameDay(a: String, b: String): Boolean {
    val zone = ZoneId.systemDefault()
    val da = runCatching { Instant.parse(a).atZone(zone).toLocalDate() }.getOrNull()
    val db = runCatching { Instant.parse(b).atZone(zone).toLocalDate() }.getOrNull()
    return if (da != null && db != null) da == db else a.take(10) == b.take(10)
}

internal fun shortTime(iso: String): String = runCatching {
    val instant = Instant.parse(iso)
    DateTimeFormatter.ofPattern("h:mm a").format(instant.atZone(ZoneId.systemDefault()))
}.getOrDefault("")

@Composable
private fun dateLabelText(iso: String): String {
    val date = runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
        ?: return ""
    val today = LocalDate.now()
    return when (date) {
        today              -> stringResource(R.string.date_today)
        today.minusDays(1) -> stringResource(R.string.date_yesterday)
        else               -> DateTimeFormatter.ofPattern("MMMM d").format(date)
    }
}
