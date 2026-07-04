package com.klic.mobile.app.feature.update

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.klic.mobile.app.R
import com.klic.mobile.app.feature.auth.AuthStyle
import com.klic.mobile.app.ui.components.PillButton
import com.klic.mobile.app.ui.theme.TikTokSans
import com.klic.mobile.app.ui.theme.TikTokSansExpanded
import com.klic.mobile.app.update.AppUpdater
import kotlinx.coroutines.launch

/**
 * Mandatory, non-dismissable update gate — §14.7 restyled to the AUTH design language:
 * the Login/Sign Up circle-container scaffold, the update mascot artwork filling the
 * exposed background above the dome, a TikTok Sans title, the red capsule "Update now"
 * CTA driving the existing [AppUpdater] download → system-installer flow, and a muted
 * "What's new" section when the release carries notes.
 */
@Composable
fun ForceUpdateScreen(release: AppUpdater.Release) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    // There is intentionally no way off this screen — swallow the system back gesture.
    BackHandler(enabled = true) {}

    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var needsPermission by remember { mutableStateOf(false) }

    fun startUpdate() {
        error = null
        // Android O+ needs a one-time "install unknown apps" grant before we can install.
        if (!AppUpdater.canInstall(context)) {
            needsPermission = true
            AppUpdater.openInstallPermissionSettings(context)
            return
        }
        needsPermission = false
        scope.launch {
            downloading = true
            progress = 0f
            runCatching {
                val apk = AppUpdater.download(context, release.apkUrl) { progress = it }
                AppUpdater.install(context, apk)
            }.onFailure {
                error = it.message ?: context.getString(R.string.settings_download_failed)
            }
            downloading = false
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val widthDp = maxWidth
        val heightDp = maxHeight
        val widthScale = widthDp / AuthStyle.referenceWidth
        val scaledRadius = AuthStyle.circleRadius * widthScale
        val sheetColor = AuthStyle.circleFill(isDark, MaterialTheme.colorScheme.surface)

        val tipFraction = if (heightDp < 700.dp) 0.26f else 0.40f
        val tipY = maxOf(60.dp, heightDp * tipFraction)
        val topInset = tipY + 40.dp

        // The mascot artwork fills the exposed background above the dome's tip.
        Image(
            painter = painterResource(R.drawable.update_art),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(tipY + 90.dp),
        )

        // The dome sheet — same construction as AuthScaffold's circle container.
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
                .height(maxOf(heightDp - topInset - 16.dp, 0.dp))
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.update_title),
                fontFamily = TikTokSansExpanded,
                fontWeight = FontWeight.Normal,
                fontSize = 30.sp,
                color = AuthStyle.titleColor(isDark),
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.update_body),
                fontFamily = TikTokSans,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                stringResource(R.string.update_new_version, release.versionName),
                style = MaterialTheme.typography.labelLarge,
                color = AuthStyle.ctaRed,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )

            // Muted "What's new" — only when the release carries notes.
            val notes = release.notes.trim()
            if (notes.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .padding(top = 14.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.update_whats_new),
                        style = MaterialTheme.typography.labelMedium,
                        color = AuthStyle.smallText,
                    )
                    Text(
                        notes,
                        style = MaterialTheme.typography.labelSmall,
                        color = AuthStyle.smallText,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            if (needsPermission) {
                Text(
                    stringResource(R.string.update_allow_install),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            Spacer(Modifier.weight(1f))

            if (downloading) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    color = AuthStyle.ctaRed,
                )
                Text(
                    stringResource(R.string.update_downloading, (progress * 100).toInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else {
                PillButton(
                    text = stringResource(R.string.update_now),
                    fill = AuthStyle.ctaRed,
                    fontFamily = TikTokSansExpanded,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    onClick = ::startUpdate,
                )
            }

            Text(
                stringResource(R.string.update_current_version, AppUpdater.currentVersion),
                style = MaterialTheme.typography.labelSmall,
                color = AuthStyle.smallText,
                modifier = Modifier.padding(top = 14.dp, bottom = 36.dp),
            )
        }
    }
}
