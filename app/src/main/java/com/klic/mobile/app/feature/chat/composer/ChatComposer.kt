package com.klic.mobile.app.feature.chat.composer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.ui.components.AvatarView
import com.klic.mobile.app.ui.theme.KlicIcons
import kotlinx.coroutines.withTimeoutOrNull

/** Which action the composer's hold-to-record button performs. */
enum class CaptureMode { AUDIO, VIDEO }

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
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(200)
        runCatching { focusRequester.requestFocus() }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconButton(
            onClick = onAttach,
            modifier = Modifier.size(44.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor   = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(
                painter = painterResource(KlicIcons.add),
                contentDescription = "Attach",
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(
            onClick = onStickers,
            modifier = Modifier.size(44.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor   = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(
                imageVector = Icons.Filled.EmojiEmotions,
                contentDescription = "Stickers",
                modifier = Modifier.size(22.dp),
            )
        }
        TextField(
            value = draft,
            onValueChange = onChange,
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            placeholder = { Text("Message", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            maxLines = 4,
            // Fully rounded capsule, matching the Login-page inputs (§9.8).
            shape = CircleShape,
            colors = TextFieldDefaults.colors(
                focusedContainerColor      = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor    = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor      = Color.Transparent,
                unfocusedIndicatorColor    = Color.Transparent,
                disabledIndicatorColor     = Color.Transparent,
                focusedTextColor           = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor         = MaterialTheme.colorScheme.onSurface,
            ),
        )

        val canSend = hasPendingAttachments || draft.text.isNotBlank()
        if (canSend) {
            IconButton(
                onClick = onSend,
                modifier = Modifier.size(44.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor   = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(Icons.Filled.Send, contentDescription = "Send", modifier = Modifier.size(20.dp))
            }
        } else {
            CaptureActionButton(
                icon = if (captureMode == CaptureMode.AUDIO) Icons.Filled.Mic else Icons.Filled.Videocam,
                enabled = true,
                onTap = onToggleCaptureMode,
                onHoldStart = onHoldStart,
                onHoldEnd = onHoldEnd,
            )
        }
    }
}

/** Tap to switch between mic/camera mode; press-and-hold to record voice or launch video capture. */
@Composable
private fun CaptureActionButton(
    icon: ImageVector,
    enabled: Boolean,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
) {
    var isHolding by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isHolding) 1.08f else 1f, label = "captureScale")

    Box(
        modifier = Modifier
            .size(44.dp)
            .scale(scale)
            .background(
                if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                CircleShape,
            )
            .then(
                if (enabled) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                // Still pressed after 180ms → treat it as a hold; otherwise a quick tap.
                                val releasedQuickly = withTimeoutOrNull(180L) { tryAwaitRelease() }
                                if (releasedQuickly == null) {
                                    isHolding = true
                                    onHoldStart()
                                    tryAwaitRelease()
                                    isHolding = false
                                    onHoldEnd()
                                } else if (releasedQuickly) {
                                    onTap()
                                }
                            },
                        )
                    }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = if (icon == Icons.Filled.Mic) "Hold to record voice, tap to switch to video"
                                  else "Hold to record video, tap to switch to voice",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
fun RecordingBar(elapsed: Float, onCancel: () -> Unit, onSend: () -> Unit) {
    val s = elapsed.toInt()
    val timeText = "%d:%02d".format(s / 60, s % 60)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconButton(
            onClick = onCancel,
            modifier = Modifier.size(44.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = Color.Transparent,
                contentColor   = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(Icons.Filled.Delete, contentDescription = "Cancel recording", modifier = Modifier.size(22.dp))
        }
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(Color.Red, CircleShape)
        )
        Text(
            timeText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "Recording…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(
            onClick = onSend,
            modifier = Modifier.size(44.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor   = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(Icons.Filled.Send, contentDescription = "Send voice", modifier = Modifier.size(20.dp))
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
