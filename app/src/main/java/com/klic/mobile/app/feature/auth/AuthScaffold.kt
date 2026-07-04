package com.klic.mobile.app.feature.auth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/**
 * Shared backdrop for Login / Sign Up: a huge dome-topped "sheet" (a half-circle of radius
 * [AuthStyle.circleRadius], extended straight down to the bottom edge) rising from below,
 * with the page artwork centered behind it in the exposed background area. Mirrors the iOS
 * AuthScaffold.
 *
 * [tipFraction] places the dome's top tip as a fraction of screen height — smaller pushes
 * the sheet (and the content that sits just below its tip) higher. Sign Up has more fields
 * than Login so it uses a smaller fraction to leave room.
 *
 * The whole scaffold sits inside [Modifier.imePadding] so the available height shrinks (and
 * every fraction-based measurement below recomputes) when the keyboard opens, keeping the
 * form clear of it without a manual inset calculation.
 */
@Composable
fun AuthScaffold(
    artwork: Int,
    tipFraction: Float,
    modifier: Modifier = Modifier,
    radius: androidx.compose.ui.unit.Dp = AuthStyle.circleRadius,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val sheetColor = AuthStyle.circleFill(isDark, MaterialTheme.colorScheme.surface)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
    ) {
        val widthDp = maxWidth
        val heightDp = maxHeight

        val widthScale = widthDp / AuthStyle.referenceWidth
        val scaledRadius = radius * widthScale

        val shortSide = if (widthDp < heightDp) widthDp else heightDp
        val artRatio = if (shortSide < 600.dp) 2.8f else 3.9f
        val artSide = shortSide * artRatio

        // The form below isn't scrollable, so on short canvases the dome is flattened toward
        // the top to guarantee enough room for it.
        val effectiveTipFraction = if (heightDp < 700.dp) tipFraction * 0.6f else tipFraction
        val tipY = maxOf(60.dp, heightDp * effectiveTipFraction)
        val topInset = tipY + 44.dp
        val contentRegionHeight = maxOf(heightDp - topInset - 24.dp, 0.dp)

        // Background artwork, centered above the dome's tip. requiredSize (not size) so the
        // oversized bleed isn't clamped by the incoming constraints; the box is coerced to
        // the parent's size and centered, so the offset is expressed from the canvas center.
        Image(
            painter = painterResource(artwork),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = tipY * 0.5f - heightDp / 2)
                .requiredSize(artSide),
        )

        // The dome sheet: a half-circle top arc extended straight down to the bottom edge.
        // Drawn on a full-size canvas so its enormous footprint can never be clamped or
        // participate in layout, and never intercepts touches.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = scaledRadius.toPx()
            val tip = tipY.toPx()
            val cx = size.width / 2f
            val path = Path().apply {
                moveTo(cx - r, tip + r)
                arcTo(
                    rect = Rect(Offset(cx, tip + r), r),
                    startAngleDegrees = 180f,
                    sweepAngleDegrees = 180f,
                    forceMoveTo = false,
                )
                lineTo(cx + r, size.height)
                lineTo(cx - r, size.height)
                close()
            }
            drawPath(path, sheetColor)
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = AuthStyle.contentMaxWidth)
                .fillMaxWidth()
                .padding(top = topInset)
                .height(contentRegionHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            content = content,
        )
    }
}
