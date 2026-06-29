package com.klicmobile.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.klicmobile.app.calling.CallReliability
import com.klicmobile.app.feature.KlicViewModel
import com.klicmobile.app.ui.components.AvatarView
import com.klicmobile.app.update.AppUpdater
import com.klicmobile.app.ui.components.KlicLottieView
import com.klicmobile.app.ui.theme.KlicIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: KlicViewModel, onEditProfile: () -> Unit = {}) {
    val user by vm.currentUser.collectAsState()
    val themeMode by vm.themeMode.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showLastSeen by remember(user?.showLastSeen) { mutableStateOf(user?.showLastSeen ?: true) }
    val versionName = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0" }
        catch (e: Exception) { "1.0" }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
    Column(
        Modifier
            .widthIn(max = 680.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(20.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                .padding(18.dp),
        ) {
            Text(
                "Appearance",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemeChip(label = "Light",  isSelected = themeMode == "light")  { vm.setThemeMode("light") }
                ThemeChip(label = "Dark",   isSelected = themeMode == "dark")   { vm.setThemeMode("dark") }
                ThemeChip(label = "System", isSelected = themeMode == "system") { vm.setThemeMode("system") }
            }
        }

        Spacer(Modifier.height(16.dp))

        user?.let {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                    .clickable(onClick = onEditProfile)
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarView(url = it.avatarUrl, name = it.displayName, size = 52.dp)
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(
                        it.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    CopyableUsername(username = it.username)
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.height(16.dp))

            // Privacy
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                    .padding(18.dp),
            ) {
                Text(
                    "Privacy",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Last seen", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "If turned off, you won't see anyone else's last seen.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = showLastSeen,
                        onCheckedChange = { value ->
                            showLastSeen = value
                            vm.setShowLastSeen(value)
                        },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // Reliable calls — battery-optimization exemption (OEM killers) + full-screen intent.
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                .clickable { CallReliability.requestDisableBatteryOptimization(context) }
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Reliable calls", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Allow Klic to run in the background so calls ring on time and don't drop.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }

        Spacer(Modifier.height(16.dp))

        // App updates — check GitHub releases and self-install (no Play Store).
        AppUpdateCard(versionName = versionName, scope = scope, context = context)

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { vm.logout() },
            modifier = Modifier.fillMaxWidth(),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Text("Log out", modifier = Modifier.padding(vertical = 6.dp))
        }

        Spacer(Modifier.height(20.dp))

        KlicLottieView(
            name = "07",
            modifier = Modifier.fillMaxWidth().height(140.dp),
        )
        Text(
            "Version $versionName",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp),
        )
    }
    } // Box
}

@Composable
private fun CopyableUsername(username: String) {
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(
                if (copied) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) {
                clipboardManager.setText(AnnotatedString(username))
                copied = true
                scope.launch { delay(1500); copied = false }
            }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "@$username",
            style = MaterialTheme.typography.labelMedium,
            color = if (copied) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            painter = painterResource(if (copied) KlicIcons.check else KlicIcons.copy),
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = if (copied) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        )
    }
}

@Composable
private fun AppUpdateCard(versionName: String, scope: CoroutineScope, context: android.content.Context) {
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
                Text("App updates", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    statusMsg ?: "Version $versionName",
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
                                r == null -> statusMsg = "Couldn't check for updates"
                                AppUpdater.isNewerThanInstalled(r.versionName) -> {
                                    available = r
                                    statusMsg = "Update available: ${r.versionName}"
                                }
                                else -> statusMsg = "You're on the latest version"
                            }
                        }
                    },
                    enabled = !checking,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) { Text(if (checking) "Checking…" else "Check") }
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
                                    statusMsg = "Download failed"
                                }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = CircleShape,
                ) { Text("Download & install ${update.versionName}") }
            }
        }
    }
}

@Composable
private fun ThemeChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary
                             else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(label)
    }
}
