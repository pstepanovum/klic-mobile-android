package com.klicmobile.app.feature.auth

import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
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
import com.klicmobile.app.R
import com.klicmobile.app.ui.components.PillButton

@Composable
fun WelcomeScreen(onGetStarted: () -> Unit) {
    val composition by rememberLottieComposition(LottieCompositionSpec.Asset("animations/12.json"))
    val progress by animateLottieCompositionAsState(composition, iterations = LottieConstants.IterateForever)
    val isDark = isSystemInDarkTheme()

    val whiteFilter = remember { PorterDuffColorFilter(android.graphics.Color.WHITE, PorterDuff.Mode.SRC_ATOP) }
    val colorFilterProp = rememberLottieDynamicProperty(
        property = LottieProperty.COLOR_FILTER,
        value = whiteFilter,
        keyPath = arrayOf("**"),
    )
    val dynamicProperties = rememberLottieDynamicProperties(colorFilterProp)

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(max = 500.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LottieAnimation(
            composition = composition,
            progress = { progress },
            dynamicProperties = if (isDark) dynamicProperties else null,
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .padding(top = 60.dp),
        )

        Image(
            painter = painterResource(R.drawable.ic_klic_logo),
            contentDescription = "Klic",
            modifier = Modifier
                .width(88.dp)
                .padding(top = 28.dp),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
        )

        Text(
            "Talk. Chat. Connect.",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = com.klicmobile.app.ui.theme.Bangers,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
                letterSpacing = 0.5.sp,
            ),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 20.dp, start = 32.dp, end = 32.dp),
        )

        Text(
            "Crystal-clear calls and instant messages,\nall in one place.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp, start = 32.dp, end = 32.dp),
        )

        Spacer(Modifier.weight(1f))

        PillButton(
            "Get Started",
            modifier = Modifier.padding(horizontal = 28.dp),
            onClick = onGetStarted,
        )

        Text(
            "Free forever · No ads · Private by design",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp, bottom = 48.dp),
        )
    }
    } // Box
}
