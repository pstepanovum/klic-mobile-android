package com.klic.mobile.app.feature.auth

import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import com.klic.mobile.app.R
import com.klic.mobile.app.ui.components.PillButton
import com.klic.mobile.app.ui.theme.Bangers
import com.klic.mobile.app.ui.theme.TikTokSans
import com.klic.mobile.app.ui.theme.TikTokSansExpanded

// Ornament geometry: a full-bleed background texture, nudged right of center. Values are
// expressed relative to ORNAMENT_REFERENCE_DIMENSION (the longer side of the phone canvas
// they were tuned against) so the same visual "zoom level" carries over to larger canvases
// like tablets instead of staying pinned to a flat dp value. Mirrors the iOS WelcomeView.
private const val ORNAMENT_REFERENCE_DIMENSION = 844f
private const val ORNAMENT_SHIFT_X = 200f
private const val ORNAMENT_SHIFT_Y = -80f

/** Fixed size the Lottie composable lays out at before being blown up via a graphics-layer
 *  scale. Must stay small: Lottie renders some compositions through a software bitmap sized
 *  by the LAYOUT size × density, and past ~100MB the frame throws ("trying to draw too
 *  large bitmap" — reproduced at 2000dp on a 560dpi device). All the visual size comes from
 *  the graphics-layer scale instead, which transforms the display list, not the bitmap. */
private const val ORNAMENT_DESIGN_SIDE = 500f

/** Ornament's final visual side (dp) at the reference canvas size; capped so huge canvases
 *  (tablets) don't blow it up further past taste. */
private const val ORNAMENT_FINAL_SIDE = 2000f
private const val ORNAMENT_MAX_FINAL_SIDE = 2800f

@Composable
fun WelcomeScreen(onGetStarted: () -> Unit) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val composition by rememberLottieComposition(LottieCompositionSpec.Asset("animations/12.json"))
    val progress by animateLottieCompositionAsState(composition, iterations = LottieConstants.IterateForever)

    // Flat monochrome tint so the animation reads as a background texture, not content.
    val ornamentTint = if (isDark) 0xFF232323.toInt() else 0xFFEAEAEA.toInt()
    val tintFilter = remember(ornamentTint) { PorterDuffColorFilter(ornamentTint, PorterDuff.Mode.SRC_ATOP) }
    val dynamicProperties = rememberLottieDynamicProperties(
        rememberLottieDynamicProperty(
            property = LottieProperty.COLOR_FILTER,
            value = tintFilter,
            keyPath = arrayOf("**"),
        ),
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clipToBounds(),
    ) {
        val longSide = maxOf(maxWidth.value, maxHeight.value)
        val scale = longSide / ORNAMENT_REFERENCE_DIMENSION
        val zoomFactor = minOf(ORNAMENT_FINAL_SIDE * scale, ORNAMENT_MAX_FINAL_SIDE) / ORNAMENT_DESIGN_SIDE

        // Background ornament: the "12" Lottie loop as a giant flat-tinted texture, offset
        // right of center, strictly behind the content (drawn first) and non-interactive.
        LottieAnimation(
            composition = composition,
            progress = { progress },
            dynamicProperties = dynamicProperties,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = (ORNAMENT_SHIFT_X * scale).dp, y = (ORNAMENT_SHIFT_Y * scale).dp)
                .requiredSize(ORNAMENT_DESIGN_SIDE.dp)
                .graphicsLayer {
                    scaleX = zoomFactor
                    scaleY = zoomFactor
                },
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 500.dp)
                .fillMaxWidth()
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))

            Image(
                painter = painterResource(R.drawable.ic_klic_logo),
                contentDescription = "Klic",
                modifier = Modifier.width(88.dp),
                colorFilter = ColorFilter.tint(if (isDark) Color.White else Color.Black),
            )

            Text(
                stringResource(R.string.welcome_tagline),
                fontFamily = Bangers,
                fontWeight = FontWeight.Normal,
                fontSize = 34.sp,
                letterSpacing = 0.5.sp,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 20.dp, start = 32.dp, end = 32.dp),
            )

            Text(
                stringResource(R.string.welcome_subtitle),
                fontFamily = TikTokSansExpanded,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp, start = 32.dp, end = 32.dp),
            )

            Spacer(Modifier.weight(1f))

            PillButton(
                stringResource(R.string.welcome_get_started),
                fill = AuthStyle.ctaRed,
                fontFamily = TikTokSansExpanded,
                fontWeight = FontWeight.Medium,
                fontSize = 17.sp,
                modifier = Modifier.padding(horizontal = 32.dp),
                onClick = onGetStarted,
            )

            Text(
                stringResource(R.string.welcome_footer),
                fontFamily = TikTokSans,
                fontWeight = FontWeight.Light,
                fontSize = 12.sp,
                color = AuthStyle.smallText,
                modifier = Modifier.padding(top = 20.dp, bottom = 48.dp),
            )
        }
    }
}
