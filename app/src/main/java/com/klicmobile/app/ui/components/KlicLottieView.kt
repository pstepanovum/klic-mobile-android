package com.klicmobile.app.ui.components

import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty

@Composable
fun KlicLottieView(name: String, modifier: Modifier = Modifier) {
    val composition by rememberLottieComposition(LottieCompositionSpec.Asset("animations/$name.json"))
    val progress by animateLottieCompositionAsState(composition, iterations = LottieConstants.IterateForever)
    val isDark = isSystemInDarkTheme()

    val whiteFilter = remember { PorterDuffColorFilter(android.graphics.Color.WHITE, PorterDuff.Mode.SRC_ATOP) }
    val colorFilterProp = rememberLottieDynamicProperty(
        property = LottieProperty.COLOR_FILTER,
        value = whiteFilter,
        keyPath = arrayOf("**"),
    )
    val dynamicProperties = rememberLottieDynamicProperties(colorFilterProp)

    LottieAnimation(
        composition = composition,
        progress = { progress },
        dynamicProperties = if (isDark) dynamicProperties else null,
        modifier = modifier,
    )
}
