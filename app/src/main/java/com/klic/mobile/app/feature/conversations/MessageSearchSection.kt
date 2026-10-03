package com.klic.mobile.app.feature.conversations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.klic.mobile.app.R

/** §18.4: a single message hit row (sender · snippet · date). */
@Composable
internal fun MessageSearchRow(hit: com.klic.mobile.app.data.MessageSearchResult, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 38.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            hit.senderName?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                stripHighlight(hit.snippet).ifBlank { stringResource(R.string.search_result_no_preview) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        searchResultStamp(hit.createdAt)?.let { stamp ->
            Text(
                stamp,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** Server snippets wrap matches in <b>…</b>; strip the tags for plain rendering. */
private fun stripHighlight(snippet: String?): String =
    snippet.orEmpty().replace("<b>", "").replace("</b>", "").trim()

private fun searchResultStamp(iso: String): String? = runCatching {
    val zoned = java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault())
    val today = java.time.LocalDate.now()
    val pattern = if (zoned.year == today.year) "MM/dd" else "MM/dd/yy"
    java.time.format.DateTimeFormatter.ofPattern(pattern).format(zoned)
}.getOrNull()
