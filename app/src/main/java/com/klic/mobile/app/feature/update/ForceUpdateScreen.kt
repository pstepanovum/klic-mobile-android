package com.klic.mobile.app.feature.update

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.klic.mobile.app.ui.components.KlicLottieView
import com.klic.mobile.app.ui.components.PillButton
import com.klic.mobile.app.ui.theme.Bangers
import com.klic.mobile.app.update.AppUpdater
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.klic.mobile.app.R

/**
 * Mandatory, non-dismissable update gate. Rendered over the entire app whenever a newer
 * release exists, so the user cannot proceed — or back out — without installing the update.
 * Single, fixed (non-scrolling) screen with a "version control" Lottie animation, driving
 * the existing [AppUpdater] download → system-installer flow.
 */
@Composable
fun ForceUpdateScreen(release: AppUpdater.Release) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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
                error = it.message ?: "Update failed. Check your connection and try again."
            }
            downloading = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 500.dp)
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            KlicLottieView(
                name = "23", // "version control"
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .padding(top = 72.dp),
            )

            Text(
                stringResource(R.string.update_title),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = Bangers,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.5.sp,
                ),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 24.dp),
            )

            Text(
                stringResource(R.string.update_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )

            Text(
                stringResource(R.string.update_new_version, release.versionName),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )

            if (needsPermission) {
                Text(
                    stringResource(R.string.update_allow_install),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            Spacer(Modifier.weight(1f))

            if (downloading) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                )
                Text(
                    stringResource(R.string.update_downloading, (progress * 100).toInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else {
                PillButton("Update Your App", onClick = ::startUpdate)
            }

            Text(
                stringResource(R.string.update_current_version, AppUpdater.currentVersion),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp, bottom = 48.dp),
            )
        }
    }
}
