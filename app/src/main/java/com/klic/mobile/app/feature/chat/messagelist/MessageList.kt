package com.klic.mobile.app.feature.chat.messagelist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
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
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.imageLoader
import com.klic.mobile.app.R
import com.klic.mobile.app.data.Attachment
import com.klic.mobile.app.data.DataUsage
import com.klic.mobile.app.data.GallerySaver
import com.klic.mobile.app.data.Message
import com.klic.mobile.app.data.SettingsStore
import com.klic.mobile.app.feature.chat.actions.DeletedBubble
import com.klic.mobile.app.feature.chat.actions.ReactionChipsInline
import com.klic.mobile.app.feature.chat.actions.ReactionPillsRow
import com.klic.mobile.app.feature.chat.actions.ReplyCard
import com.klic.mobile.app.feature.chat.media.FileAttachmentView
import com.klic.mobile.app.feature.chat.media.PdfFileBubbleView
import com.klic.mobile.app.feature.chat.media.isPdfAttachment
import com.klic.mobile.app.feature.chat.media.formatByteSize
import com.klic.mobile.app.feature.chat.media.isAudioAttachment
import com.klic.mobile.app.feature.chat.stickers.CallEventBubble
import com.klic.mobile.app.feature.chat.stickers.StickerBubble
import com.klic.mobile.app.feature.chat.voice.VoiceAttachmentView
import com.klic.mobile.app.feature.chat.voice.durationText
import com.klic.mobile.app.ui.components.MessageTicks
import com.klic.mobile.app.ui.components.rememberStableImageRequest
import com.klic.mobile.app.ui.components.stableImageKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.ui.res.stringResource

// MARK: - Message bubble

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
 * §19.2: tap + long-press detection for the large media/file bubbles that never fights
 * the enclosing LazyColumn's vertical scroll. Unlike [combinedClickable] it does NOT
 * consume the press, and it abandons the gesture the moment the pointer travels past
 * touch slop — leaving that drag entirely to the list, so a scroll that STARTS on an
 * image / video / file bubble pans the conversation instead of being swallowed. Only an
 * in-place release fires [onTap]; an in-place hold fires [onLongPress].
 */
@Composable
private fun Modifier.mediaTapGestures(
    onTap: () -> Unit,
    onLongPress: () -> Unit,
): Modifier {
    val currentTap by rememberUpdatedState(onTap)
    val currentLong by rememberUpdatedState(onLongPress)
    return this.pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        val longPressTimeout = viewConfiguration.longPressTimeoutMillis
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var travelX = 0f
            var travelY = 0f
            var draggedAway = false
            // Returns true on a clean in-place release; null on the long-press timeout;
            // sets draggedAway (and returns false) if the pointer leaves as a scroll/drag.
            val tapped = withTimeoutOrNull(longPressTimeout) {
                var released = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    // A null/consumed change means the list (or another node) claimed the
                    // pointer for a pan — never treat that as a tap.
                    if (change == null || change.isConsumed) { draggedAway = true; break }
                    if (!change.pressed) { released = true; break }
                    travelX += change.positionChange().x
                    travelY += change.positionChange().y
                    if (abs(travelX) > slop || abs(travelY) > slop) { draggedAway = true; break }
                }
                released
            }
            when {
                draggedAway -> Unit          // a scroll/drag — the LazyColumn owns it
                tapped == true -> currentTap()
                tapped == null -> currentLong()
                else -> Unit
            }
        }
    }
}

// §7.2/§13.17 bento grid: 2 → side-by-side; 3 → one large + two stacked; 4+ → 2x2 with
// a "+N" scrim on the fourth tile. Tiles render images AND videos (play badge +
// duration); every tile opens the media viewer paged to the tapped attachment.
//
// §19.3: each tile rounds ONLY the corners that sit on the collage's outer boundary;
// the interior junctions where tiles meet stay square. Previously every tile rounded
// all four corners at [tileRadius], so the grid read as several disconnected cards with
// rounded notches at the centre instead of "one unit" (§13.17). [roundBottom] squares
// the bottom edge when a caption follows the grid inside the same card.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BentoMediaGrid(
    atts: List<Attachment>,
    tileRadius: Dp,
    onMediaClick: (Attachment) -> Unit,
    onLongPress: () -> Unit,
    roundBottom: Boolean = true,
) {
    val spacing = 2.dp
    val r = tileRadius
    val z = 0.dp
    // Rounded corner only where a tile touches the collage's outer edge.
    fun sh(topStart: Dp, topEnd: Dp, bottomStart: Dp, bottomEnd: Dp): Shape =
        RoundedCornerShape(topStart = topStart, topEnd = topEnd, bottomStart = bottomStart, bottomEnd = bottomEnd)
    val br = if (roundBottom) r else z

    @Composable
    fun Tile(att: Attachment, modifier: Modifier, shape: Shape, overflow: Int = 0) {
        // §8.3 auto-download matrix: photos auto-fetch only when the current network
        // allows it; otherwise a placeholder with a manual download button. Already
        // Coil-cached images always render (no network needed).
        val context = LocalContext.current
        val isVideo = att.kind == "VIDEO"
        val settings by SettingsStore.snapshot.collectAsState()
        var manuallyRequested by remember(att.id) { mutableStateOf(false) }
        // §9.9: the disk cache is keyed on the presign-stable URL. openSnapshot does
        // filesystem I/O, so it runs off the composition thread; the placeholder shows
        // (as it already does for uncached images) until the check lands.
        val cached by produceState(initialValue = false, att.url) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    context.imageLoader.diskCache?.openSnapshot(stableImageKey(att.url))?.use { true } ?: false
                }.getOrDefault(false)
            }
        }
        val allowed = isVideo || cached || manuallyRequested ||
            settings.autoDownloadAllowed(SettingsStore.KIND_PHOTOS, DataUsage.isOnWifi())

        Box(
            modifier
                .clip(shape)
                .mediaTapGestures(
                    onTap = { if (allowed) onMediaClick(att) else manuallyRequested = true },
                    onLongPress = onLongPress,
                ),
        ) {
            if (isVideo) {
                // §13.17/§14.2: video tile — first-frame thumbnail + play badge + duration.
                Box(Modifier.fillMaxSize().background(Color(0xFF1A1A1A))) {
                    val thumb by com.klic.mobile.app.feature.chat.media.rememberVideoThumbnail(att)
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
                            .size(34.dp)
                            .align(Alignment.Center)
                            .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                            .padding(4.dp),
                    )
                    if (att.durationMs != null && overflow <= 0) {
                        MediaTimePill(
                            text = durationText(att.durationMs),
                            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                        )
                    }
                }
            } else if (allowed) {
                AsyncImage(
                    model = rememberStableImageRequest(att.url),
                    contentDescription = "Image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .background(Color.Black.copy(alpha = 0.35f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_bold_arrow_bottom),
                                contentDescription = "Download photo",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        if (att.byteSize > 0) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                formatByteSize(att.byteSize),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (overflow > 0) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+$overflow", style = MaterialTheme.typography.titleLarge, color = Color.White)
                }
            }
        }
    }

    when (atts.size) {
        1 -> Tile(
            atts[0],
            Modifier.width(240.dp).aspectRatio(imageAspect(atts[0]).coerceIn(0.75f, 1.6f)),
            sh(r, r, br, br),
        )
        2 -> Row(Modifier.width(240.dp), horizontalArrangement = Arrangement.spacedBy(spacing)) {
            Tile(atts[0], Modifier.weight(1f).aspectRatio(0.75f), sh(r, z, br, z))
            Tile(atts[1], Modifier.weight(1f).aspectRatio(0.75f), sh(z, r, z, br))
        }
        3 -> Row(Modifier.width(240.dp).height(240.dp), horizontalArrangement = Arrangement.spacedBy(spacing)) {
            Tile(atts[0], Modifier.weight(2f).fillMaxHeight(), sh(r, z, br, z))
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(spacing)) {
                Tile(atts[1], Modifier.weight(1f).fillMaxWidth(), sh(z, r, z, z))
                Tile(atts[2], Modifier.weight(1f).fillMaxWidth(), sh(z, z, z, br))
            }
        }
        else -> Column(Modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(spacing)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                Tile(atts[0], Modifier.weight(1f).aspectRatio(1f), sh(r, z, z, z))
                Tile(atts[1], Modifier.weight(1f).aspectRatio(1f), sh(z, r, z, z))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                Tile(atts[2], Modifier.weight(1f).aspectRatio(1f), sh(z, z, br, z))
                Tile(atts[3], Modifier.weight(1f).aspectRatio(1f), sh(z, z, z, br), overflow = atts.size - 4)
            }
        }
    }
}

// Semi-transparent dark pill used as overlay on image/video.
@Composable
internal fun MediaTimePill(
    modifier: Modifier = Modifier,
    time: String = "",
    text: String = "",           // for the duration pill on video (no ticks)
    status: String? = null,
    starred: Boolean = false,
    /** §16.4: prefix a lowercase "edited" before the time. */
    edited: Boolean = false,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (starred && text.isEmpty()) StarIndicator(Color.White)
        if (edited && text.isEmpty()) {
            Text(
                stringResource(R.string.edited_label),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
        val label = text.ifEmpty { time }
        if (label.isNotEmpty()) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
        if (status != null && text.isEmpty()) {
            MessageTicks(status = status, onMedia = true)
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

/**
 * Bubble-less render for 1–3 emoji-only messages: one emoji renders biggest, two or
 * three slightly smaller. Time + ticks render below the emoji, bottom-trailing (§13.7).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BigEmojiBubble(
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

// Accent for "@all" inside a bubble: primary on neutral bubbles; own (primary-coloured)
// bubbles use white so the highlight stays visible on the accent background.
@Composable
private fun mentionAccent(isMine: Boolean): Color =
    if (isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary

/** Highlights "@all" and member-name mentions (accent + semibold) when [highlight] is on (§9.5). */
internal fun bodyWithMentions(
    body: String,
    highlight: Boolean,
    accent: Color,
    names: List<String> = emptyList(),
): AnnotatedString {
    if (!highlight) return AnnotatedString(body)
    val matches = mentionAllRanges(body) + names.flatMap { mentionNameRanges(body, it) }
    if (matches.isEmpty()) return AnnotatedString(body)
    return buildAnnotatedString {
        append(body)
        matches.forEach { range ->
            addStyle(
                SpanStyle(color = accent, fontWeight = FontWeight.SemiBold),
                range.first,
                range.last + 1,
            )
        }
    }
}

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

// Aspect ratio for an inline image/video, clamped so extreme shapes stay reasonable.
private fun imageAspect(att: Attachment): Float {
    val w = att.width; val h = att.height
    return if (w != null && h != null && w > 0 && h > 0) (w.toFloat() / h.toFloat()).coerceIn(0.6f, 1.6f) else 1f
}

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

internal fun sameDay(a: String, b: String): Boolean = a.take(10) == b.take(10)

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
