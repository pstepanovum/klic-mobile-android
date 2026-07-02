package com.klic.mobile.app.ui.components

import androidx.compose.foundation.layout.Box
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
 * Message delivery-status ticks drawn with the shared "klic check" glyph
 * (vector asset, viewport 268x190).
 *
 * Single check for "sent"; "delivered" and "read" overlap a second copy of the
 * same glyph, shifted right; "read" tints green.
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
        status == "read" -> ReadGreen
        onMedia          -> Color.White
        onPrimary        -> Color.White.copy(alpha = 0.65f)
        else             -> muted
    }
    val showDouble = status == "delivered" || status == "read"
    val check = painterResource(KlicIcons.klicCheck)

    // Each check draws at 11x8dp (glyph is ~1.41:1); the double state overlaps a second
    // copy at the trailing edge, keeping the old 16x10dp footprint.
    Box(modifier.size(width = if (showDouble) 16.dp else 11.dp, height = 10.dp)) {
        Icon(
            painter = check,
            contentDescription = null,
            tint = tickColor,
            modifier = Modifier.align(Alignment.CenterStart).size(width = 11.dp, height = 8.dp),
        )
        if (showDouble) {
            Icon(
                painter = check,
                contentDescription = null,
                tint = tickColor,
                modifier = Modifier.align(Alignment.CenterEnd).size(width = 11.dp, height = 8.dp),
            )
        }
    }
}
