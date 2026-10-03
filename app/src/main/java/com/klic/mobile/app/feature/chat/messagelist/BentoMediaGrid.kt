package com.klic.mobile.app.feature.chat.messagelist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.imageLoader
import com.klic.mobile.app.R
import com.klic.mobile.app.data.Attachment
import com.klic.mobile.app.data.DataUsage
import com.klic.mobile.app.data.SettingsStore
import com.klic.mobile.app.feature.chat.media.formatByteSize
import com.klic.mobile.app.feature.chat.voice.durationText
import com.klic.mobile.app.ui.components.MessageTicks
import com.klic.mobile.app.ui.components.rememberStableImageRequest
import com.klic.mobile.app.ui.components.stableImageKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
internal fun BentoMediaGrid(
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
        val settings by SettingsStore.snapshot.collectAsStateWithLifecycle()
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

// Aspect ratio for an inline image/video, clamped so extreme shapes stay reasonable.
internal fun imageAspect(att: Attachment): Float {
    val w = att.width; val h = att.height
    return if (w != null && h != null && w > 0 && h > 0) (w.toFloat() / h.toFloat()).coerceIn(0.6f, 1.6f) else 1f
}
