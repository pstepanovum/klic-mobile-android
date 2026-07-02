package com.klic.mobile.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.ui.theme.KlicIcons
import com.klic.mobile.app.ui.theme.ReadGreen

/**
 * Message delivery-status ticks drawn with the shared "klic check" glyphs.
 *
 * "sent" = the single-check glyph (vector asset, viewport 268x190). "delivered" and
 * "read" = the combined check-and-a-half glyph — ONE asset carrying both paths
 * (viewport 376x192, §8.1) rather than two overlapped singles.
 *
 * @param status    "sent" | "delivered" | "read"
 * @param onPrimary true when sitting on a primary-coloured bubble (own messages)
 * @param onMedia   true when used inside the dark overlay pill on image or video
 */
@Composable
fun MessageTicks(
    status: String,
    onPrimary: Boolean = false,
    onMedia: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val tickColor: Color = when {
        // Read accent — stays legible on the primary bubble, the media pill, and cards.
        status == "read" -> ReadGreen
        onMedia          -> Color.White
        onPrimary        -> Color.White.copy(alpha = 0.65f)
        else             -> muted
    }
    val showDouble = status == "delivered" || status == "read"

    // Single check draws at 11x8dp (glyph is ~1.41:1); the combined glyph keeps the
    // old 16x10dp footprint (~1.96:1 fits 16x8 centered in a 10dp-tall box).
    Box(
        modifier.size(width = if (showDouble) 16.dp else 11.dp, height = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(if (showDouble) KlicIcons.klicCheckDouble else KlicIcons.klicCheck),
            contentDescription = null,
            tint = tickColor,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
