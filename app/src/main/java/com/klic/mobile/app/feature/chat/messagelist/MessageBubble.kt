package com.klic.mobile.app.feature.chat.messagelist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.data.Attachment
import com.klic.mobile.app.data.GallerySaver
import com.klic.mobile.app.data.Message
import com.klic.mobile.app.feature.chat.actions.DeletedBubble
import com.klic.mobile.app.feature.chat.actions.ReactionChipsInline
import com.klic.mobile.app.feature.chat.actions.ReactionPillsRow
import com.klic.mobile.app.feature.chat.actions.ReplyCard
import com.klic.mobile.app.feature.chat.media.FileAttachmentView
import com.klic.mobile.app.feature.chat.media.PdfFileBubbleView
import com.klic.mobile.app.feature.chat.media.isPdfAttachment
import com.klic.mobile.app.feature.chat.media.isAudioAttachment
import com.klic.mobile.app.feature.chat.stickers.CallEventBubble
import com.klic.mobile.app.feature.chat.stickers.StickerBubble
import com.klic.mobile.app.feature.chat.voice.VoiceAttachmentView
import com.klic.mobile.app.feature.chat.voice.durationText

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageBubble(
    message: Message,
    isMine: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    replyAuthorName: String = "",
    /** Group chats highlight "@all" mentions in bubble bodies (§8.4). */
    highlightMentions: Boolean = false,
    /** Member display names that highlight like @all when mentioned (§9.5). */
    mentionNames: List<String> = emptyList(),
    onCallBack: (String) -> Unit = {},
    onLongPress: () -> Unit = {},
    onReactionTap: (String) -> Unit = {},
    /** §10.9: opens the media viewer on the tapped IMAGE or VIDEO attachment. */
    onMediaClick: (Attachment) -> Unit = {},
    onFileClick: (Attachment) -> Unit = {},
    /** §16.1: tap on the reply quote card → scroll to the original + highlight. */
    onQuoteClick: () -> Unit = {},
) {
    if (message.isDeleted) { DeletedBubble(isMine); return }
    // SYSTEM notices ("«admin» removed «target»", §9.3) render as a centred pill.
    if (message.kind == "SYSTEM") { SystemNotice(message.body); return }
    if (message.isCallEvent && message.call != null) {
        CallEventBubble(message.call, outgoing = isMine, time = shortTime(message.createdAt), onCallBack = onCallBack)
        return
    }
    if (message.isSticker) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 1.dp)
                .combinedClickable(onClick = {}, onLongClick = onLongPress),
            horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
        ) {
            StickerBubble(message, isMine = isMine, time = if (isLast) shortTime(message.createdAt) else null)
            if (message.reactions.isNotEmpty()) ReactionPillsRow(message.reactions, onReactionTap)
        }
        return
    }

    val tailRadius = 4.dp
    val fullRadius = 18.dp
    val shape = RoundedCornerShape(
        topStart     = if (!isMine && isFirst) fullRadius else if (!isMine) 4.dp else fullRadius,
        topEnd       = if (isMine  && isFirst) fullRadius else if (isMine)  4.dp else fullRadius,
        bottomEnd    = if (isMine  && isLast)  tailRadius else fullRadius,
        bottomStart  = if (!isMine && isLast)  tailRadius else fullRadius,
    )

    val voiceAtt = message.attachments.firstOrNull { it.kind == "VOICE" }
    // §16.2: round video notes render as a bubble-less circle, like stickers.
    val videoNoteAtt = message.attachments.firstOrNull { it.kind == "VIDEO_NOTE" }
    // §13.17: images AND videos share one media list so a bulk message renders as a
    // single bento grid; a lone video keeps its dedicated player-style bubble.
    val mediaAtts = message.attachments.filter { it.kind == "IMAGE" || it.kind == "VIDEO" }
    val soleVideoAtt = mediaAtts.singleOrNull()?.takeIf { it.kind == "VIDEO" }
    val fileAtt = message.attachments.firstOrNull { it.kind == "FILE" }

    // §19.1: date formatting and emoji segmentation are pure functions of the message
    // content — memoize them so they don't re-run on every scroll-path recomposition.
    val time = remember(message.createdAt) { shortTime(message.createdAt) }
    val emojiCount = remember(message.body) { emojiOnlyClusterCount(message.body) }
    val status = if (isMine) message.status else null
    // §16.4: lowercase "edited" immediately before the time in every meta placement.
    val edited = message.editedAt != null

    // §8.4 Save to Photos (Always): incoming media auto-saves once, deduped by attachment id.
    val autoSaveContext = LocalContext.current
    if (!isMine && mediaAtts.isNotEmpty()) {
        LaunchedEffect(message.id) { GallerySaver.maybeAutoSave(autoSaveContext, message) }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
    ) {
        when {
            voiceAtt != null ->
                Box(Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress)) {
                    VoiceAttachmentView(
                        att = voiceAtt,
                        isMine = isMine,
                        time = time,
                        status = status,
                        starred = message.starred,
                        reactions = message.reactions,
                        onReactionTap = onReactionTap,
                    )
                }

            // §16.2: circular video-note playback — no bubble chrome, ring progress.
            videoNoteAtt != null ->
                Column(horizontalAlignment = if (isMine) Alignment.End else Alignment.Start) {
                    message.replyTo?.let { StandaloneReplyCard(it, replyAuthorName, isMine, onQuoteClick) }
                    Box(Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress)) {
                        com.klic.mobile.app.feature.chat.videonote.VideoNoteBubble(
                            att = videoNoteAtt,
                            conversationId = message.conversationId,
                            time = time,
                            status = status,
                            starred = message.starred,
                            edited = edited,
                            onLongPress = onLongPress,
                        )
                    }
                    if (message.reactions.isNotEmpty()) ReactionPillsRow(message.reactions, onReactionTap)
                }

            mediaAtts.isNotEmpty() && (soleVideoAtt == null || message.body.isNotBlank()) ->
                Column(horizontalAlignment = if (isMine) Alignment.End else Alignment.Start) {
                    if (message.body.isBlank()) {
                        // No caption: bare image/bento with the overlay time + ticks pill.
                        message.replyTo?.let { StandaloneReplyCard(it, replyAuthorName, isMine, onQuoteClick) }
                        Box(Modifier.clip(RoundedCornerShape(16.dp))) {
                            BentoMediaGrid(mediaAtts, tileRadius = 12.dp, onMediaClick = onMediaClick, onLongPress = onLongPress)
                            // §14.5: reactions live INSIDE the media edge, scrim-backed.
                            ReactionChipsInline(
                                reactions = message.reactions,
                                onTap = onReactionTap,
                                onMedia = true,
                                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                            )
                            MediaTimePill(
                                time = time,
                                status = status,
                                starred = message.starred,
                                edited = edited,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                            )
                        }
                    } else {
                        // §7.2: image(s) + caption unified into ONE card — image(s) on top
                        // (inner radius 4dp under the card's), caption + inline time/ticks below.
                        val cardColor = if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                        val textColor = if (isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        val timeColor = if (isMine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.65f)
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(cardColor)
                                .mediaTapGestures(onTap = {}, onLongPress = onLongPress)
                                .padding(4.dp),
                        ) {
                            message.replyTo?.let {
                                Box(Modifier.padding(start = 2.dp, top = 2.dp, end = 2.dp, bottom = 4.dp)) {
                                    ReplyCard(
                                        it, replyAuthorName, onPrimary = isMine,
                                        onClick = onQuoteClick,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                            BentoMediaGrid(mediaAtts, tileRadius = 14.dp, onMediaClick = onMediaClick, onLongPress = onLongPress, roundBottom = false)
                            // §15.2: caption + tucked time/ticks share the media card width.
                            BodyWithInlineMeta(
                                body = message.body,
                                highlightMentions = highlightMentions,
                                accent = mentionAccent(isMine),
                                mentionNames = mentionNames,
                                textColor = textColor,
                                modifier = Modifier.width(240.dp).padding(horizontal = 6.dp, vertical = 6.dp),
                            ) {
                                MetaRow(time, status, message.starred, timeColor, isMine, edited)
                            }
                            // §14.5: reactions inside the card's bottom edge.
                            ReactionChipsInline(
                                reactions = message.reactions,
                                onTap = onReactionTap,
                                onPrimary = isMine,
                                modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
                            )
                        }
                    }
                }

            soleVideoAtt != null && message.body.isBlank() ->
                Column(horizontalAlignment = if (isMine) Alignment.End else Alignment.Start) {
                    message.replyTo?.let { StandaloneReplyCard(it, replyAuthorName, isMine, onQuoteClick) }
                    Box(
                        Modifier
                            .widthIn(max = 240.dp)
                            .heightIn(max = 320.dp)
                            .aspectRatio(imageAspect(soleVideoAtt))
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF1A1A1A))
                            .mediaTapGestures(onTap = { onMediaClick(soleVideoAtt) }, onLongPress = onLongPress),
                    ) {
                        // §14.2: real first-frame thumbnail behind the play badge.
                        val thumb by com.klic.mobile.app.feature.chat.media.rememberVideoThumbnail(
                            soleVideoAtt, message.conversationId,
                        )
                        thumb?.let {
                            androidx.compose.foundation.Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "Video",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.matchParentSize(),
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Play video",
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier
                                .size(48.dp)
                                .align(Alignment.Center)
                                .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                                .padding(6.dp),
                        )
                        // §14.5: reactions on the media's bottom edge, above the pills.
                        ReactionChipsInline(
                            reactions = message.reactions,
                            onTap = onReactionTap,
                            onMedia = true,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 8.dp, bottom = 34.dp),
                        )
                        // Duration pill — bottom-left.
                        if (soleVideoAtt.durationMs != null) {
                            MediaTimePill(
                                text = durationText(soleVideoAtt.durationMs),
                                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                            )
                        }
                        // Time + ticks pill — bottom-right.
                        MediaTimePill(
                            time = time,
                            status = status,
                            starred = message.starred,
                            edited = edited,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                        )
                    }
                }

            fileAtt != null ->
                // §7.3: files open in-app (pdf viewer / audio player / detail sheet) —
                // the tap goes through onFileClick, never straight to the URL.
                Column(horizontalAlignment = if (isMine) Alignment.End else Alignment.Start) {
                    message.replyTo?.let { StandaloneReplyCard(it, replyAuthorName, isMine, onQuoteClick) }
                    Box(
                        Modifier.mediaTapGestures(
                            onTap = { if (!isAudioAttachment(fileAtt)) onFileClick(fileAtt) },
                            onLongPress = onLongPress,
                        ),
                    ) {
                        // §10.10: PDFs preview their first page; other files keep the pill.
                        if (isPdfAttachment(fileAtt)) {
                            PdfFileBubbleView(
                                att = fileAtt,
                                isMine = isMine,
                                time = time,
                                status = status,
                                conversationId = message.conversationId,
                                starred = message.starred,
                                reactions = message.reactions,
                                onReactionTap = onReactionTap,
                            )
                        } else {
                            FileAttachmentView(
                                att = fileAtt,
                                isMine = isMine,
                                time = time,
                                status = status,
                                conversationId = message.conversationId,
                                starred = message.starred,
                                reactions = message.reactions,
                                onReactionTap = onReactionTap,
                            )
                        }
                    }
                }

            // §10.3: 1–3 emoji-only messages render WhatsApp-style — no bubble, big glyphs.
            message.replyTo == null && emojiCount in 1..3 ->
                BigEmojiBubble(
                    body = message.body.trim(),
                    emojiCount = emojiCount,
                    time = time,
                    status = status,
                    starred = message.starred,
                    edited = edited,
                    onLongPress = onLongPress,
                    reactions = message.reactions,
                    onReactionTap = onReactionTap,
                )

            else ->
                Box(
                    Modifier
                        // §13.3: bubbles cap at ~85% of the row (own AND peer) so text
                        // fills more width before wrapping; short messages still hug.
                        .bubbleWidthCap()
                        .background(
                            if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            shape,
                        )
                        .combinedClickable(onClick = {}, onLongClick = onLongPress)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    val textColor = if (isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    val timeColor = if (isMine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.65f)
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                    // §16.1: with a quote card the bubble grows to the wider of card/body
                    // (and the card stretches to the bubble when the body is wider).
                    Column(
                        if (message.replyTo != null) {
                            Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max)
                        } else Modifier,
                    ) {
                        message.replyTo?.let {
                            ReplyCard(
                                it, replyAuthorName, onPrimary = isMine,
                                onClick = onQuoteClick,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            )
                        }
                        // §15.2: body text with the time+ticks tucked into the last
                        // line's trailing gap when they fit, else a compact trailing row.
                        BodyWithInlineMeta(
                            body = message.body,
                            highlightMentions = highlightMentions,
                            accent = mentionAccent(isMine),
                            mentionNames = mentionNames,
                            textColor = textColor,
                        ) {
                            MetaRow(time, status, message.starred, timeColor, isMine, edited)
                        }
                        // Rich OG link-preview card below the text (mirrors iOS); renders
                        // nothing unless the first URL yields usable preview metadata.
                        LinkPreviewCard(message, modifier = Modifier.fillMaxWidth())
                        // §14.5: reactions at the bubble's bottom edge, inside it.
                        ReactionChipsInline(
                            reactions = message.reactions,
                            onTap = onReactionTap,
                            onPrimary = isMine,
                            modifier = Modifier.padding(top = 5.dp),
                        )
                    }
                }
        }
    }
}

/**
 * §16.1: reply quote card floated above a bubble-less render (bare media, round video
 * notes, files) — a solid bubble-coloured backing keeps the accent-tinted card
 * readable over any chat wallpaper.
 */
@Composable
internal fun StandaloneReplyCard(
    reply: com.klic.mobile.app.data.ReplyPreview,
    authorName: String,
    isMine: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.padding(bottom = 2.dp),
    ) {
        Box(Modifier.padding(4.dp)) {
            ReplyCard(reply, authorName, onPrimary = isMine, onClick = onClick)
        }
    }
}

// MARK: - Message body text with tappable links (§10.4)

/** Small star next to the timestamp on starred bubbles (§8.4). */
@Composable
internal fun StarIndicator(tint: Color) {
    Icon(
        painter = painterResource(com.klic.mobile.app.ui.theme.KlicIcons.starBold),
        contentDescription = "Starred",
        tint = tint,
        modifier = Modifier.size(10.dp),
    )
}

/** §13.3: constrains a bubble to 85% of the incoming row width without forcing it wide. */
private fun Modifier.bubbleWidthCap(fraction: Float = 0.85f): Modifier =
    layout { measurable, constraints ->
        val cap = if (constraints.hasBoundedWidth) {
            (constraints.maxWidth * fraction).toInt()
        } else {
            constraints.maxWidth
        }
        val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = cap))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

/** Centred pill for SYSTEM notices — same visual language as the date separator. */
@Composable
private fun SystemNotice(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

// MARK: - Date separator
