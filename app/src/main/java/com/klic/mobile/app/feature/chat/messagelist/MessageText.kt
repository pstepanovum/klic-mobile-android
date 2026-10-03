package com.klic.mobile.app.feature.chat.messagelist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.klic.mobile.app.R
import com.klic.mobile.app.ui.components.MessageTicks
import androidx.compose.ui.res.stringResource

/**
 * Bubble body text: mention highlighting (§9.5) plus tappable URLs. Every link tap
 * routes through [com.klic.mobile.app.data.LinkOpener] and honors "Open links in".
 */
@Composable
internal fun MessageBodyText(
    body: String,
    highlightMentions: Boolean,
    accent: Color,
    mentionNames: List<String>,
    textColor: Color,
    modifier: Modifier = Modifier,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    val context = LocalContext.current
    val annotated = remember(body, highlightMentions, accent, mentionNames, textColor) {
        buildAnnotatedString {
            val links = com.klic.mobile.app.data.LinkOpener.urlRegex.findAll(body).toList()
            var pos = 0
            links.forEach { match ->
                if (match.range.first > pos) append(body.substring(pos, match.range.first))
                val url = match.value
                withLink(
                    LinkAnnotation.Clickable(
                        tag = url,
                        styles = TextLinkStyles(
                            style = SpanStyle(color = textColor, textDecoration = TextDecoration.Underline),
                        ),
                    ) { com.klic.mobile.app.data.LinkOpener.open(context, url) },
                ) { append(url) }
                pos = match.range.last + 1
            }
            if (pos < body.length) append(body.substring(pos))
            if (highlightMentions) {
                val ranges = mentionAllRanges(body) + mentionNames.flatMap { mentionNameRanges(body, it) }
                ranges.forEach { range ->
                    addStyle(
                        SpanStyle(color = accent, fontWeight = FontWeight.SemiBold),
                        range.first,
                        range.last + 1,
                    )
                }
            }
        }
    }
    Text(
        annotated,
        color = textColor,
        style = MaterialTheme.typography.bodyLarge,
        onTextLayout = onTextLayout,
        modifier = modifier,
    )
}

// MARK: - Inline time+ticks tucking (§15.2)

/** The star + time + ticks cluster shown at a bubble's trailing edge. */
@Composable
internal fun MetaRow(
    time: String,
    status: String?,
    starred: Boolean,
    timeColor: Color,
    isMine: Boolean,
    /** §16.4: lowercase "edited" immediately BEFORE the time ("edited 9:39 ✓"). */
    edited: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (starred) StarIndicator(timeColor)
        if (edited) {
            Text(
                stringResource(R.string.edited_label),
                style = MaterialTheme.typography.labelSmall,
                color = timeColor,
            )
        }
        Text(time, style = MaterialTheme.typography.labelSmall, color = timeColor)
        if (status != null) MessageTicks(status = status, onPrimary = isMine)
    }
}

/**
 * §15.2: lays out the body text with the [meta] cluster (time + ticks) tucked into
 * the LAST line's trailing gap when it fits there, and wrapped to a compact
 * bottom-trailing row only when it doesn't — the bubble always hugs the longest
 * text line and never reserves an empty band beside every line.
 */
@Composable
internal fun BodyWithInlineMeta(
    body: String,
    highlightMentions: Boolean,
    accent: Color,
    mentionNames: List<String>,
    textColor: Color,
    modifier: Modifier = Modifier,
    metaSpacing: Dp = 6.dp,
    meta: @Composable () -> Unit,
) {
    if (body.isBlank()) {
        Box(modifier) { meta() }
        return
    }
    // Written by the text child during its measure pass, read right after below.
    var textLayout: TextLayoutResult? = null
    Layout(
        modifier = modifier,
        content = {
            MessageBodyText(
                body = body,
                highlightMentions = highlightMentions,
                accent = accent,
                mentionNames = mentionNames,
                textColor = textColor,
                onTextLayout = { textLayout = it },
            )
            meta()
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val text = measurables[0].measure(loose)
        val metaPlaceable = measurables[1].measure(loose)
        val spacing = metaSpacing.roundToPx()
        val layoutResult = textLayout

        val lastLine = (layoutResult?.lineCount ?: 1) - 1
        val lastLineEnd = layoutResult?.getLineRight(lastLine) ?: text.width.toFloat()
        // RTL paragraphs end at the LEFT edge — always give the meta its own row there.
        val lastLineRtl = layoutResult
            ?.getParagraphDirection(layoutResult.getLineStart(lastLine)) == ResolvedTextDirection.Rtl
        val neededInline = kotlin.math.ceil(lastLineEnd).toInt() + spacing + metaPlaceable.width
        val fitsInline = !lastLineRtl && neededInline <= constraints.maxWidth

        val width: Int
        val height: Int
        if (fitsInline) {
            width = maxOf(text.width, neededInline).coerceIn(constraints.minWidth, constraints.maxWidth)
            height = text.height
        } else {
            width = maxOf(text.width, metaPlaceable.width).coerceIn(constraints.minWidth, constraints.maxWidth)
            height = text.height + metaPlaceable.height
        }
        layout(width, height) {
            text.place(0, 0)
            metaPlaceable.place(width - metaPlaceable.width, height - metaPlaceable.height)
        }
    }
}

// MARK: - Big emoji (§10.3)

// Accent for "@all" inside a bubble: primary on neutral bubbles; own (primary-coloured)
// bubbles use white so the highlight stays visible on the accent background.
@Composable
internal fun mentionAccent(isMine: Boolean): Color =
    if (isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary

/** Character ranges of "@Display Name" mentions for one member name (§9.5). */
internal fun mentionNameRanges(body: String, name: String): List<IntRange> {
    if (name.isBlank()) return emptyList()
    return Regex("""(^|\s)(@${Regex.escape(name)})""", RegexOption.IGNORE_CASE)
        .findAll(body)
        .mapNotNull { it.groups[2]?.range }
        .toList()
}

/** Character ranges of "@all" tokens (same regex as the server's push gating). */
internal fun mentionAllRanges(body: String): List<IntRange> =
    Regex("""(^|\s)(@all)\b""", RegexOption.IGNORE_CASE)
        .findAll(body)
        .mapNotNull { it.groups[2]?.range }
        .toList()
