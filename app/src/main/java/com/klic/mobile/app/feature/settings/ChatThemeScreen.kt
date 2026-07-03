package com.klic.mobile.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.R
import com.klic.mobile.app.data.ChatThemeStore
import com.klic.mobile.app.ui.components.ChatPatternImage
import com.klic.mobile.app.ui.components.ChatThemeLayers
import com.klic.mobile.app.ui.theme.KlicIcons
import kotlinx.coroutines.launch

/**
 * Chat theme page (§12.3): live mini-chat preview, pattern grid (default = 1),
 * pattern-opacity slider (clamped subtle), curated gradient presets + intensity,
 * own-bubble color palette, and a "Reset theme" button. Everything persists locally
 * through [ChatThemeStore] and applies to ALL conversations.
 */
@Composable
fun ChatThemeContent() {
    val theme by ChatThemeStore.snapshot.collectAsState()
    val scope = rememberCoroutineScope()

    // ── Live preview ─────────────────────────────────────────────────────────
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp)),
    ) {
        ChatThemeLayers(
            theme = theme,
            // The Box wraps the mock bubbles — the layer stack fills behind them.
            modifier = Modifier.matchParentSize(),
        )
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PreviewBubble(text = stringResource(R.string.theme_preview_incoming_1), isMine = false, theme = theme)
            PreviewBubble(text = stringResource(R.string.theme_preview_outgoing), isMine = true, theme = theme)
            PreviewBubble(text = stringResource(R.string.theme_preview_incoming_2), isMine = false, theme = theme)
        }
    }

    // ── Pattern grid ─────────────────────────────────────────────────────────
    Spacer(Modifier.height(20.dp))
    ThemeSectionLabel(stringResource(R.string.theme_pattern))
    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        (1..ChatThemeStore.PATTERN_COUNT).chunked(5).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { id ->
                    PatternThumb(
                        id = id,
                        selected = theme.patternId == id,
                        modifier = Modifier.weight(1f),
                        onClick = { scope.launch { ChatThemeStore.setPattern(id) } },
                    )
                }
            }
        }
    }

    // ── Pattern opacity ──────────────────────────────────────────────────────
    Spacer(Modifier.height(16.dp))
    SliderCard(
        title = stringResource(R.string.theme_pattern_opacity),
        value = theme.patternOpacity,
        valueRange = ChatThemeStore.MIN_PATTERN_OPACITY..ChatThemeStore.MAX_PATTERN_OPACITY,
        valueLabel = "${(theme.patternOpacity * 100).toInt()}%",
        onChangeFinished = { scope.launch { ChatThemeStore.setPatternOpacity(it) } },
    )

    // ── Gradient presets + intensity ─────────────────────────────────────────
    Spacer(Modifier.height(20.dp))
    ThemeSectionLabel(stringResource(R.string.theme_gradient))
    Spacer(Modifier.height(8.dp))
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GradientSwatch(
            colors = null,
            label = stringResource(R.string.theme_gradient_none),
            selected = theme.gradientId == ChatThemeStore.DEFAULT_GRADIENT,
            modifier = Modifier.weight(1f),
            onClick = { scope.launch { ChatThemeStore.setGradient(ChatThemeStore.DEFAULT_GRADIENT) } },
        )
        ChatThemeStore.gradientPresets.forEach { preset ->
            GradientSwatch(
                colors = if (isDark) preset.dark else preset.light,
                label = null,
                selected = theme.gradientId == preset.id,
                modifier = Modifier.weight(1f),
                onClick = { scope.launch { ChatThemeStore.setGradient(preset.id) } },
            )
        }
    }
    if (theme.gradientId != ChatThemeStore.DEFAULT_GRADIENT) {
        Spacer(Modifier.height(12.dp))
        SliderCard(
            title = stringResource(R.string.theme_gradient_intensity),
            value = theme.gradientIntensity,
            valueRange = 0f..1f,
            valueLabel = "${(theme.gradientIntensity * 100).toInt()}%",
            onChangeFinished = { scope.launch { ChatThemeStore.setGradientIntensity(it) } },
        )
    }

    // ── Bubble color ─────────────────────────────────────────────────────────
    Spacer(Modifier.height(20.dp))
    ThemeSectionLabel(stringResource(R.string.theme_bubble_color))
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ChatThemeStore.bubblePalettes.forEach { palette ->
            Box(
                Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(palette.color)
                    .then(
                        if (theme.bubbleId == palette.id) {
                            Modifier.border(3.dp, MaterialTheme.colorScheme.onBackground, CircleShape)
                        } else Modifier,
                    )
                    .clickable { scope.launch { ChatThemeStore.setBubble(palette.id) } },
                contentAlignment = Alignment.Center,
            ) {
                if (theme.bubbleId == palette.id) {
                    Icon(
                        painter = painterResource(KlicIcons.check),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }

    // ── Reset ────────────────────────────────────────────────────────────────
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = { scope.launch { ChatThemeStore.reset() } },
        modifier = Modifier.fillMaxWidth(),
        shape = CircleShape,
        enabled = !theme.isDefault,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) { Text(stringResource(R.string.theme_reset), modifier = Modifier.padding(vertical = 6.dp)) }

    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.theme_footer),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Mock bubble for the live preview — own bubbles take the selected color live. */
@Composable
private fun PreviewBubble(text: String, isMine: Boolean, theme: ChatThemeStore.Snapshot) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            Modifier
                .widthIn(max = 230.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (isMine) theme.bubbleColor else MaterialTheme.colorScheme.surfaceVariant,
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isMine) Color.White else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun PatternThumb(id: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .aspectRatio(0.85f)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .then(
                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                else Modifier,
            )
            .clickable(onClick = onClick),
    ) {
        // Thumbnails render the pattern well above chat opacity so it's legible.
        ChatPatternImage(patternId = id, alpha = 0.55f, modifier = Modifier.fillMaxSize())
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .size(18.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(KlicIcons.check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(10.dp),
                )
            }
        }
    }
}

@Composable
private fun GradientSwatch(
    colors: Pair<Color, Color>?,
    label: String?,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (colors != null) {
                    Modifier.background(Brush.verticalGradient(listOf(colors.first, colors.second)))
                } else {
                    Modifier.background(MaterialTheme.colorScheme.surface)
                },
            )
            .then(
                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                else Modifier,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ThemeSectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp),
    )
}

/** Rounded card with a titled slider — commits to the store on release. */
@Composable
private fun SliderCard(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onChangeFinished: (Float) -> Unit,
) {
    // Local value while dragging so the preview tracks the thumb smoothly; the
    // DataStore write lands on release.
    var dragging by remember { mutableFloatStateOf(Float.NaN) }
    val shown = if (dragging.isNaN()) value else dragging
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                valueLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = shown,
            onValueChange = {
                dragging = it
                onChangeFinished(it)
            },
            onValueChangeFinished = {
                if (!dragging.isNaN()) onChangeFinished(dragging)
                dragging = Float.NaN
            },
            valueRange = valueRange,
        )
    }
}
