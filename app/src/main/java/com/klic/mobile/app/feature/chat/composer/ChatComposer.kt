package com.klic.mobile.app.feature.chat.composer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import com.klic.mobile.app.ui.components.AvatarView
import com.klic.mobile.app.ui.theme.KlicIcons
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import com.klic.mobile.app.R

/** Which action the composer's hold-to-record button performs. */
enum class CaptureMode { AUDIO, VIDEO }

/** §16.2: lifecycle of one hold-to-record interaction. */
enum class RecordPhase { IDLE, HELD, LOCKED }

/** §16.2: what a drag update did to the in-flight recording. */
enum class RecordDragResult { CONTINUE, LOCKED, CANCELED }

// §16.2 gesture geometry, studied from the reference client (§15.0):
// slide-left cancel distance = min(35% of screen width, 140dp); releasing past 55%
// of it (progress < 0.45) also cancels; slide-up lock threshold = 57dp.
internal val LOCK_TRAVEL = 57.dp
internal const val CANCEL_WIDTH_FRACTION = 0.35f
internal val CANCEL_MAX_TRAVEL = 140.dp
internal const val RELEASE_CANCEL_PROGRESS = 0.45f

// MARK: - Mentions (§9.5)

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

@Composable
fun ComposerBar(
    draft: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onStickers: () -> Unit,
    hasPendingAttachments: Boolean,
    captureMode: CaptureMode,
    onToggleCaptureMode: () -> Unit,
    // §16.2: hold-to-record with the slide-to-cancel / slide-up-to-lock system.
    recordPhase: RecordPhase = RecordPhase.IDLE,
    recordElapsed: Float = 0f,
    onHoldStart: () -> Unit,
    onHoldDrag: (Offset) -> RecordDragResult = { RecordDragResult.CONTINUE },
    onHoldEnd: () -> Unit,
    onRecordCancel: () -> Unit = {},
    onRecordSend: () -> Unit = {},
    // §15.1: reply preview lives INSIDE the input container (banner above the field).
    replyAuthor: String? = null,
    replyPreview: String = "",
    onCancelReply: () -> Unit = {},
    // §16.4: edit banner ("Edit Message" + the original text) inside the container.
    editingOriginal: String? = null,
    onCancelEdit: () -> Unit = {},
    /** Increment to shake the input (empty edit apply, §16.4). */
    shakeTrigger: Int = 0,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(200)
        runCatching { focusRequester.requestFocus() }
    }

    val density = LocalDensity.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val cancelDistPx = with(density) {
        minOf(configuration.screenWidthDp.dp * CANCEL_WIDTH_FRACTION, CANCEL_MAX_TRAVEL).toPx()
    }
    val lockTravelPx = with(density) { LOCK_TRAVEL.toPx() }

    // Live drag offsets while HELD — drive the hint/button/padlock translations.
    var dragX by remember { mutableStateOf(0f) }
    var dragY by remember { mutableStateOf(0f) }
    LaunchedEffect(recordPhase) { if (recordPhase != RecordPhase.HELD) { dragX = 0f; dragY = 0f } }

    // §16.4: error shake for an empty edit apply.
    val shakeOffset = remember { Animatable(0f) }
    LaunchedEffect(shakeTrigger) {
        if (shakeTrigger > 0) {
            repeat(3) {
                shakeOffset.animateTo(10f, tween(45))
                shakeOffset.animateTo(-10f, tween(70))
            }
            shakeOffset.animateTo(0f, tween(45))
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        // All controls pin to the BOTTOM edge as the input grows (§15.1).
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (recordPhase) {
            RecordPhase.IDLE -> {
                IconButton(
                    onClick = onAttach,
                    modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor   = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(
                        painter = painterResource(KlicIcons.plus),
                        contentDescription = "Attach",
                        modifier = Modifier.size(24.dp),
                    )
                }
                // §15.1: ONE rounded container holding the reply/edit banner (when active)
                // on top and the emoji button + text field below. The corner radius
                // continuously flattens as the container grows: a full capsule at one
                // line, easing to 16dp.
                var inputHeightPx by remember { mutableIntStateOf(0) }
                val singleLine = 56.dp   // M3 text-field single-line height → 28dp capsule radius
                val cornerTarget = run {
                    val h = with(density) { inputHeightPx.toDp() }
                    if (inputHeightPx == 0 || h <= singleLine) singleLine / 2
                    else (singleLine / 2 * (singleLine.value / h.value)).coerceAtLeast(16.dp)
                }
                val corner by animateDpAsState(cornerTarget, label = "composerCorner")
                Column(
                    Modifier
                        .weight(1f)
                        .onSizeChanged { inputHeightPx = it.height }
                        .graphicsLayer { translationX = shakeOffset.value }
                        .clip(RoundedCornerShape(corner))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    if (editingOriginal != null) {
                        // §16.4: edit banner wins over the reply banner.
                        InlineComposerBanner(
                            title = stringResource(R.string.edit_message_title),
                            preview = editingOriginal,
                            onCancel = onCancelEdit,
                        )
                    } else if (replyAuthor != null) {
                        InlineComposerBanner(
                            title = stringResource(R.string.actions_reply_to, replyAuthor),
                            preview = replyPreview,
                            onCancel = onCancelReply,
                        )
                    }
                    // Emoji lives INSIDE the input pill, pinned to its bottom edge.
                    Row(verticalAlignment = Alignment.Bottom) {
                        IconButton(
                            onClick = onStickers,
                            modifier = Modifier.size(width = 40.dp, height = 56.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.EmojiEmotions,
                                contentDescription = "Stickers",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        TextField(
                            value = draft,
                            onValueChange = onChange,
                            modifier = Modifier.weight(1f).focusRequester(focusRequester),
                            placeholder = { Text(stringResource(R.string.composer_message), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            maxLines = 6,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor      = Color.Transparent,
                                unfocusedContainerColor    = Color.Transparent,
                                focusedIndicatorColor      = Color.Transparent,
                                unfocusedIndicatorColor    = Color.Transparent,
                                disabledIndicatorColor     = Color.Transparent,
                                focusedTextColor           = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor         = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }
                }
            }
            RecordPhase.HELD -> {
                RecordingStatus(recordElapsed)
                SlideToCancelHint(
                    dragX = dragX,
                    cancelDistPx = cancelDistPx,
                    modifier = Modifier.weight(1f).height(44.dp),
                )
            }
            RecordPhase.LOCKED -> {
                IconButton(
                    onClick = onRecordCancel,
                    modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Transparent,
                        contentColor   = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = "Cancel recording", modifier = Modifier.size(22.dp))
                }
                RecordingStatus(recordElapsed)
                Spacer(Modifier.weight(1f))
            }
        }

        // Trailing slot: send / capture button / locked send — plus the floating
        // padlock and mode tooltip anchored above it.
        var tooltipVisible by remember { mutableStateOf(false) }
        LaunchedEffect(tooltipVisible, captureMode) {
            if (tooltipVisible) {
                kotlinx.coroutines.delay(2600)
                tooltipVisible = false
            }
        }
        // §16.2: padlock snap-closed flash right after locking (~250ms ease-out).
        val lockSnap = remember { Animatable(0f) }
        LaunchedEffect(recordPhase) {
            if (recordPhase == RecordPhase.LOCKED) {
                lockSnap.snapTo(1f)
                lockSnap.animateTo(0f, tween(250, easing = androidx.compose.animation.core.EaseOut))
            } else {
                lockSnap.snapTo(0f)
            }
        }
        Box {
            val canSend = hasPendingAttachments || draft.text.isNotBlank()
            when {
                recordPhase == RecordPhase.LOCKED -> {
                    IconButton(
                        onClick = onRecordSend,
                        modifier = Modifier.size(44.dp),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor   = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = "Send recording", modifier = Modifier.size(20.dp))
                    }
                }
                canSend -> {
                    IconButton(
                        onClick = onSend,
                        modifier = Modifier.size(44.dp),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor   = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(
                            // §16.4: the apply-edit affordance is a CHECKMARK.
                            imageVector = if (editingOriginal != null) Icons.Filled.Check else Icons.Filled.Send,
                            contentDescription = if (editingOriginal != null) "Apply edit" else "Send",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                else -> {
                    CaptureActionButton(
                        mode = captureMode,
                        held = recordPhase == RecordPhase.HELD,
                        dragX = dragX,
                        dragY = dragY,
                        onTap = { onToggleCaptureMode(); tooltipVisible = true },
                        onHoldStart = { tooltipVisible = false; onHoldStart() },
                        onHoldDrag = { total ->
                            dragX = total.x.coerceIn(-cancelDistPx, 0f)
                            dragY = total.y.coerceIn(-lockTravelPx * 1.4f, 0f)
                            onHoldDrag(total)
                        },
                        onHoldEnd = onHoldEnd,
                    )
                }
            }
            // §16.2: floating padlock — OPEN and tilted while unlocked, closing and
            // straightening as the finger travels up; snaps shut on lock.
            if (recordPhase == RecordPhase.HELD || lockSnap.value > 0f) {
                val lockProgress =
                    if (recordPhase == RecordPhase.HELD) (-dragY / lockTravelPx).coerceIn(0f, 1f) else 1f
                RecordPadlock(
                    lockProgress = lockProgress,
                    snap = lockSnap.value,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset {
                            IntOffset(
                                0,
                                (-64.dp.toPx() + dragY * 0.3f).roundToInt(),
                            )
                        },
                )
            }
            // §16.2: helper tooltip near the button, shown on each mode toggle.
            if (tooltipVisible && recordPhase == RecordPhase.IDLE) {
                CaptureModeTooltip(
                    text = if (captureMode == CaptureMode.AUDIO) {
                        stringResource(R.string.composer_hold_audio_hint)
                    } else {
                        stringResource(R.string.composer_hold_video_hint)
                    },
                )
            }
        }
    }
}

/**
 * §15.1/§16.4: banner rendered INSIDE the composer's input container, directly above
 * the text field — "Reply to X" while replying, "Edit Message" while editing.
 */
@Composable
private fun InlineComposerBanner(title: String, preview: String, onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = 32.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
        )
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                preview,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
            Icon(
                painter = painterResource(KlicIcons.close),
                contentDescription = "Cancel",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * §16.2: the two-mode capture button. TAP toggles audio ↔ video-note mode (with a
 * small haptic); HOLD records in the current mode, tracking the finger so slides
 * left (cancel) and up (lock) work — the button itself rides along with the drag.
 */
@Composable
private fun CaptureActionButton(
    mode: CaptureMode,
    held: Boolean,
    dragX: Float,
    dragY: Float,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldDrag: (Offset) -> RecordDragResult,
    onHoldEnd: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val scale by animateFloatAsState(if (held) 1.7f else 1f, label = "captureScale")

    Box(
        modifier = Modifier
            .size(44.dp)
            .graphicsLayer {
                translationX = if (held) dragX else 0f
                translationY = if (held) dragY else 0f
                scaleX = scale
                scaleY = scale
            }
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var releasedQuickly = false
                    var cancelledEarly = false
                    val beforeTimeout = withTimeoutOrNull(180L) {
                        val up = waitForUpOrCancellation()
                        if (up != null) releasedQuickly = true else cancelledEarly = true
                        true
                    }
                    when {
                        cancelledEarly -> return@awaitEachGesture
                        beforeTimeout != null && releasedQuickly -> {
                            // Quick tap → toggle mic ↔ camera with a small haptic.
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onTap()
                            return@awaitEachGesture
                        }
                    }
                    // Still pressed after 180ms → hold-to-record; track the drag.
                    onHoldStart()
                    var total = Offset.Zero
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || change.changedToUpIgnoreConsumed()) {
                            change?.consume()
                            onHoldEnd()
                            break
                        }
                        val delta = change.positionChange()
                        if (delta != Offset.Zero) {
                            change.consume()
                            total += delta
                            when (onHoldDrag(total)) {
                                RecordDragResult.LOCKED -> {
                                    // One crisp haptic exactly at the lock threshold.
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    break
                                }
                                RecordDragResult.CANCELED -> break
                                RecordDragResult.CONTINUE -> Unit
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (mode == CaptureMode.AUDIO) Icons.Filled.Mic else Icons.Filled.Videocam,
            contentDescription = if (mode == CaptureMode.AUDIO) "Hold to record audio" else "Hold to record video",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** §16.2: pulsing red dot + elapsed timer shown while recording. */
@Composable
private fun RecordingStatus(elapsed: Float) {
    val s = elapsed.toInt()
    val timeText = "%d:%02d".format(s / 60, s % 60)
    val pulse = rememberInfiniteTransition(label = "recordPulse")
    val dotAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "recordDot",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(44.dp),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .graphicsLayer { alpha = dotAlpha }
                .background(Color.Red, CircleShape),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            timeText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** §16.2: "‹ Slide to cancel" hint — rides with the finger, fading as it travels. */
@Composable
private fun SlideToCancelHint(dragX: Float, cancelDistPx: Float, modifier: Modifier = Modifier) {
    // 1 at rest → 0 at the cancel threshold (the reference's slide progress).
    val progress = (1f + dragX / cancelDistPx).coerceIn(0f, 1f)
    Row(
        modifier.graphicsLayer {
            translationX = dragX * 0.55f
            alpha = progress
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.ChevronLeft,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            stringResource(R.string.record_slide_to_cancel),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * §16.2: the floating padlock above the record button — open and tilted while
 * unlocked, closing/straightening as [lockProgress] → 1; [snap] > 0 renders the
 * just-locked pop (closed padlock scaling back down over ~250ms).
 */
@Composable
private fun RecordPadlock(lockProgress: Float, snap: Float, modifier: Modifier = Modifier) {
    val closed = snap > 0f || lockProgress > 0.8f
    Box(
        modifier
            .size(40.dp)
            .graphicsLayer {
                val pop = 1f + 0.25f * snap
                scaleX = pop
                scaleY = pop
            }
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (closed) Icons.Filled.Lock else Icons.Filled.LockOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer { rotationZ = -18f * (1f - lockProgress.coerceIn(0f, 1f)) },
        )
    }
}

/** §16.2: helper tooltip anchored above the capture button. */
@Composable
private fun CaptureModeTooltip(text: String) {
    Popup(
        popupPositionProvider = object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset = IntOffset(
                x = (anchorBounds.right - popupContentSize.width).coerceAtLeast(8),
                y = anchorBounds.top - popupContentSize.height - 12,
            )
        },
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            shadowElevation = 4.dp,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

// MARK: - Attach sheet

@Composable
fun AttachSheet(onPhotos: () -> Unit, onCamera: () -> Unit, onFile: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .navigationBarsPadding()
            .padding(bottom = 32.dp),
    ) {
        Text(
            "Attach",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(bottom = 24.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            AttachTile(
                iconRes = KlicIcons.gallery,
                label = "Photos",
                color = Color(0xFF3B82F6),
                onClick = onPhotos,
                modifier = Modifier.weight(1f),
            )
            AttachTile(
                iconRes = KlicIcons.camera,
                label = "Camera",
                color = Color(0xFF10B981),
                onClick = onCamera,
                modifier = Modifier.weight(1f),
            )
            AttachTile(
                iconRes = KlicIcons.document,
                label = "File",
                color = Color(0xFFF59E0B),
                onClick = onFile,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AttachTile(iconRes: Int, label: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .background(color, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = label,
                modifier = Modifier.size(30.dp),
                tint = Color.White,
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
