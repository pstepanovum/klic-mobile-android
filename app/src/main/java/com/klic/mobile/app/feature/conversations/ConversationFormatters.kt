package com.klic.mobile.app.feature.conversations

import androidx.compose.runtime.Composable
import com.klic.mobile.app.data.Conversation
import com.klic.mobile.app.data.Message
import androidx.compose.ui.res.stringResource
import com.klic.mobile.app.R

internal fun conversationTitle(conversation: Conversation): String =
    when {
        conversation.type == "GROUP" && !conversation.title.isNullOrBlank() -> conversation.title
        conversation.type == "GROUP" -> conversation.members.joinToString(", ") { it.displayName }.ifBlank { "Group" }
        else -> conversation.members.firstOrNull()?.displayName ?: "Direct"
    }

internal fun groupMemberSummary(conversation: Conversation): String =
    conversation.members.joinToString(", ") { it.displayName }.ifBlank { "No members yet" }

/** Last-message stamp for the chat list: clock time today (e.g. "3:26 PM"), "MM/dd" earlier this
 *  year, "MM/dd/yy" before that — or null if unknown. */
internal fun lastMessageStamp(m: Message?): String? {
    val iso = m?.createdAt?.takeIf { it.isNotBlank() } ?: return null
    return runCatching {
        val zoned = java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault())
        val today = java.time.LocalDate.now()
        val pattern = when {
            zoned.toLocalDate() == today -> "h:mm a"
            zoned.year == today.year -> "MM/dd"
            else -> "MM/dd/yy"
        }
        java.time.format.DateTimeFormatter.ofPattern(pattern).format(zoned)
    }.getOrNull()
}

/** One-line summary of the last message for the chat list (no emoji, per the design system). */
@Composable
internal fun lastMessagePreview(m: Message?): String = when {
    m == null -> stringResource(R.string.preview_say_hi)
    m.isDeleted -> stringResource(R.string.preview_message_deleted)
    m.isCallEvent -> if (m.call?.isVideo == true) stringResource(R.string.preview_video_call)
                     else stringResource(R.string.preview_voice_call)
    m.isSticker -> stringResource(R.string.preview_sticker)
    m.body.isNotBlank() -> m.body
    m.attachments.firstOrNull()?.kind == "IMAGE" -> stringResource(R.string.preview_photo)
    m.attachments.firstOrNull()?.kind == "VIDEO_NOTE" -> stringResource(R.string.preview_video_message)
    m.attachments.firstOrNull()?.kind == "VIDEO" -> stringResource(R.string.preview_video)
    m.attachments.firstOrNull()?.kind == "VOICE" -> stringResource(R.string.preview_voice_message)
    m.attachments.isNotEmpty() -> stringResource(R.string.preview_file)
    else -> stringResource(R.string.preview_say_hi)
}

// ─────────────────────────────────────────────────────────
// New Message Sheet
// ─────────────────────────────────────────────────────────
