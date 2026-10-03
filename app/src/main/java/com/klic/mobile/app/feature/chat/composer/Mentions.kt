package com.klic.mobile.app.feature.chat.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.ui.components.AvatarView

/** One row of the @mention suggestion strip. */
data class MentionCandidate(
    val display: String,
    val username: String? = null,
    val avatarUrl: String? = null,
    val isAll: Boolean = false,
)

/** The "@prefix" token sitting immediately before the cursor, if any. */
data class MentionQuery(val start: Int, val prefix: String)

/**
 * Finds an active mention being typed at [cursor]: an "@" at the start of the text or
 * after whitespace, with no whitespace between it and the cursor.
 */
fun mentionQueryAt(text: String, cursor: Int): MentionQuery? {
    if (cursor < 0 || cursor > text.length) return null
    val upToCursor = text.substring(0, cursor)
    val at = upToCursor.lastIndexOf('@')
    if (at == -1) return null
    if (at > 0 && !upToCursor[at - 1].isWhitespace()) return null
    val prefix = upToCursor.substring(at + 1)
    if (prefix.any { it.isWhitespace() }) return null
    return MentionQuery(at, prefix)
}

/** Replaces the active mention token with "@Name " and parks the cursor after it. */
fun insertMention(value: TextFieldValue, query: MentionQuery, name: String): TextFieldValue {
    val insertion = "@$name "
    val end = value.selection.start.coerceIn(query.start, value.text.length)
    val newText = value.text.replaceRange(query.start, end, insertion)
    return value.copy(text = newText, selection = TextRange(query.start + insertion.length))
}

/** Horizontal suggestion strip shown ABOVE the composer while typing an @mention. */
@Composable
fun MentionSuggestionStrip(
    candidates: List<MentionCandidate>,
    onPick: (MentionCandidate) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        candidates.forEach { candidate ->
            Row(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    .clickable { onPick(candidate) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!candidate.isAll) {
                    AvatarView(url = candidate.avatarUrl, name = candidate.display, size = 20.dp)
                }
                Text(
                    if (candidate.isAll) "@all" else candidate.display,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (candidate.isAll) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

// MARK: - Composer
