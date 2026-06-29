package com.klicmobile.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.klicmobile.app.ui.theme.ReadGreen

/**
 * Custom double-checkmark icon for message delivery status.
 *
 * Draws a single tick for "sent", double tick for "delivered" and "read".
 * SVG source viewBox: 12 × 7.
 *
 * @param status    "sent" | "delivered" | "read"
 * @param onPrimary true when sitting on a primary-coloured bubble (own messages)
 * @param onMedia   true when used as an overlay on image or video
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

    // Build once, reuse every frame.
    val path1 = remember {
        Path().apply {
            moveTo(8.28033f, 1.28033f)
            cubicTo(8.57322f, 0.987437f, 8.57322f, 0.512563f, 8.28033f, 0.21967f)
            cubicTo(7.98744f, -0.0732232f, 7.51256f, -0.0732232f, 7.21967f, 0.21967f)
            lineTo(3.25f, 4.18934f)
            lineTo(1.28033f, 2.21967f)
            cubicTo(0.987437f, 1.92678f, 0.512563f, 1.92678f, 0.21967f, 2.21967f)
            cubicTo(-0.0732232f, 2.51256f, -0.0732232f, 2.98744f, 0.21967f, 3.28033f)
            lineTo(2.71967f, 5.78033f)
            cubicTo(3.01256f, 6.07322f, 3.48744f, 6.07322f, 3.78033f, 5.78033f)
            lineTo(8.28033f, 1.28033f)
            close()
        }
    }
    val path2 = remember {
        Path().apply {
            moveTo(11.7066f, 0.21967f)
            cubicTo(11.4137f, -0.0732232f, 10.9388f, -0.0732232f, 10.6459f, 0.21967f)
            lineTo(6.08527f, 4.78033f)
            cubicTo(5.79238f, 5.07322f, 5.79238f, 5.5481f, 6.08527f, 5.84099f)
            cubicTo(6.37817f, 6.13388f, 6.85304f, 6.13388f, 7.14594f, 5.84099f)
            lineTo(11.7066f, 1.28033f)
            cubicTo(11.9995f, 0.987437f, 11.9995f, 0.512563f, 11.7066f, 0.21967f)
            close()
        }
    }

    Canvas(modifier = modifier.size(width = 16.dp, height = 10.dp)) {
        val sx = size.width / 12f
        val sy = size.height / 7f
        withTransform({
            scale(scaleX = sx, scaleY = sy, pivot = Offset.Zero)
        }) {
            drawPath(path1, color = tickColor)
            if (showDouble) drawPath(path2, color = tickColor)
        }
    }
}
