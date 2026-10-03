package com.klic.mobile.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.R
import com.klic.mobile.app.update.AppUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun AppUpdateCard(versionName: String, scope: CoroutineScope, context: android.content.Context) {
    var checking by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var available by remember { mutableStateOf<AppUpdater.Release?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_app_updates), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    statusMsg ?: stringResource(R.string.settings_version_format, versionName),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (available == null && !downloading) {
                Button(
                    onClick = {
                        checking = true
                        statusMsg = null
                        scope.launch {
                            val r = AppUpdater.fetchLatest()
                            checking = false
                            when {
                                r == null -> statusMsg = context.getString(R.string.settings_update_check_failed)
                                AppUpdater.isNewerThanInstalled(r.versionName) -> {
                                    available = r
                                    statusMsg = context.getString(R.string.settings_update_available, r.versionName)
                                }
                                else -> statusMsg = context.getString(R.string.settings_update_latest)
                            }
                        }
                    },
                    enabled = !checking,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) { Text(if (checking) stringResource(R.string.settings_checking) else stringResource(R.string.settings_check)) }
            }
        }

        val update = available
        if (update != null) {
            Spacer(Modifier.height(12.dp))
            if (downloading) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else {
                Button(
                    onClick = {
                        if (!AppUpdater.canInstall(context)) {
                            AppUpdater.openInstallPermissionSettings(context)
                            return@Button
                        }
                        downloading = true
                        progress = 0f
                        scope.launch {
                            runCatching { AppUpdater.download(context, update.apkUrl) { progress = it } }
                                .onSuccess { file ->
                                    downloading = false
                                    AppUpdater.install(context, file)
                                }
                                .onFailure {
                                    downloading = false
                                    statusMsg = context.getString(R.string.settings_download_failed)
                                }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = CircleShape,
                ) { Text(stringResource(R.string.settings_download_install, update.versionName)) }
            }
        }
    }
}
