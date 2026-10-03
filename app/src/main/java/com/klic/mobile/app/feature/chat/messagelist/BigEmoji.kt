package com.klic.mobile.app.feature.chat.messagelist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.klic.mobile.app.R
import com.klic.mobile.app.feature.chat.actions.ReactionChipsInline
import com.klic.mobile.app.ui.components.MessageTicks
import androidx.compose.ui.res.stringResource

/**
 * Bubble-less render for 1–3 emoji-only messages: one emoji renders biggest, two or
 * three slightly smaller. Time + ticks render below the emoji, bottom-trailing (§13.7).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BigEmojiBubble(
    body: String,
    emojiCount: Int,
    time: String,
    status: String?,
    starred: Boolean,
    edited: Boolean = false,
    onLongPress: () -> Unit,
    reactions: List<com.klic.mobile.app.data.Reaction> = emptyList(),
    onReactionTap: (String) -> Unit = {},
) {
    val fontSize = when (emojiCount) {
        1 -> 46.sp
        2 -> 38.sp
        else -> 32.sp
    }
    // §13.7: time + ticks sit BELOW the emoji (bottom-trailing), never beside it.
    Column(
        horizontalAlignment = Alignment.End,
        modifier = Modifier
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
            .padding(horizontal = 2.dp, vertical = 2.dp),
    ) {
        Text(body, fontSize = fontSize, lineHeight = fontSize)
        Spacer(Modifier.height(2.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            val timeColor = MaterialTheme.colorScheme.onSurfaceVariant
            if (starred) StarIndicator(timeColor)
            if (edited) {
                Text(
                    stringResource(R.string.edited_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = timeColor,
                )
            }
            Text(time, style = MaterialTheme.typography.labelSmall, color = timeColor)
            if (status != null) MessageTicks(status = status)
        }
        // §14.5 parity: emoji-only messages have no bubble — chips sit just below.
        ReactionChipsInline(
            reactions = reactions,
            onTap = onReactionTap,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/**
 * Number of grapheme clusters in [body] when it consists ONLY of emoji (1–3), else 0.
 * Uses ICU's extended grapheme segmentation so ZWJ sequences, skin tones, flags and
 * keycaps each count as ONE emoji.
 */
internal fun emojiOnlyClusterCount(body: String): Int {
    val text = body.trim()
    if (text.isEmpty()) return 0
    val iterator = android.icu.text.BreakIterator.getCharacterInstance()
    iterator.setText(text)
    var count = 0
    var start = iterator.first()
    var end = iterator.next()
    while (end != android.icu.text.BreakIterator.DONE) {
        if (!isEmojiCluster(text.substring(start, end))) return 0
        count++
        if (count > 3) return 0
        start = end
        end = iterator.next()
    }
    return count
}

/** True when one grapheme cluster reads as an emoji (not plain text like "1" or "#"). */
private fun isEmojiCluster(cluster: String): Boolean {
    var i = 0
    var hasEmoji = false
    val hasVariation = cluster.contains('\uFE0F')
    while (i < cluster.length) {
        val cp = cluster.codePointAt(i)
        when {
            // Joiners / variation selectors / skin tones / keycap combiner — glue, not glyphs.
            cp == 0x200D || cp == 0xFE0F || cp == 0xFE0E || cp in 0x1F3FB..0x1F3FF || cp == 0x20E3 -> Unit
            cp in 0x1F1E6..0x1F1FF -> hasEmoji = true   // regional indicators (flags)
            android.icu.lang.UCharacter.hasBinaryProperty(
                cp, android.icu.lang.UProperty.EMOJI_PRESENTATION,
            ) -> hasEmoji = true
            hasVariation && android.icu.lang.UCharacter.hasBinaryProperty(
                cp, android.icu.lang.UProperty.EMOJI,
            ) -> hasEmoji = true
            else -> return false
        }
        i += Character.charCount(cp)
    }
    return hasEmoji
}
