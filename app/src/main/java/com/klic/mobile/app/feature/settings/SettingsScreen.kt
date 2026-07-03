package com.klic.mobile.app.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.R
import com.klic.mobile.app.calling.CallReliability
import com.klic.mobile.app.feature.KlicViewModel
import com.klic.mobile.app.ui.components.AvatarView
import com.klic.mobile.app.ui.components.KlicLottieView
import com.klic.mobile.app.update.AppUpdater
import com.klic.mobile.app.ui.theme.KlicIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed class SettingsRoute {
    object Main : SettingsRoute()
    object Appearance : SettingsRoute()
    object AutoNightMode : SettingsRoute()
    object Updates : SettingsRoute()
    object Privacy : SettingsRoute()
    object Notifications : SettingsRoute()
    object DataStorage : SettingsRoute()
    // v0.5.3
    object PrivacyBlocked : SettingsRoute()
    object PrivacyAppLock : SettingsRoute()
    object PrivacyPasskeys : SettingsRoute()
    object Language : SettingsRoute()
    object QrCode : SettingsRoute()
    object RecentCalls : SettingsRoute()
    // v0.5.5
    object ChatTheme : SettingsRoute()
}

@Composable
fun SettingsScreen(vm: KlicViewModel, onEditProfile: () -> Unit = {}) {
    val user by vm.currentUser.collectAsState()
    val themeMode by vm.themeMode.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val versionName = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0" }
        catch (e: Exception) { "1.0" }
    }

    var route by remember { mutableStateOf<SettingsRoute>(SettingsRoute.Main) }
    // §12.1: Settings → "Report a problem" — the report sheet with no target.
    var showProblemReport by remember { mutableStateOf(false) }

    // §12.2: /me now carries email/emailVerified — refresh so the row is current.
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshMe() }

    BackHandler(enabled = route != SettingsRoute.Main) {
        route = when (route) {
            SettingsRoute.AutoNightMode -> SettingsRoute.Appearance
            SettingsRoute.PrivacyBlocked,
            SettingsRoute.PrivacyAppLock,
            SettingsRoute.PrivacyPasskeys -> SettingsRoute.Privacy
            else -> SettingsRoute.Main
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedContent(targetState = route, label = "settings_route") { currentRoute ->
            Column(
                Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                when (currentRoute) {
                    SettingsRoute.Main -> {
                        Text(
                            stringResource(R.string.tab_settings),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(Modifier.height(20.dp))

                        // Centered profile header — no card/background
                        user?.let { u ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() },
                                        onClick = onEditProfile,
                                    )
                                    .padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                AvatarView(url = u.avatarUrl, name = u.displayName, size = 80.dp)
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    u.displayName,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    textAlign = TextAlign.Center,
                                )
                                Spacer(Modifier.height(6.dp))
                                CopyableUsername(username = u.username)
                            }
                            Spacer(Modifier.height(20.dp))
                        }

                        // Card 1: My Profile + Appearance
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                                .padding(horizontal = 18.dp),
                        ) {
                            SettingsRow(
                                icon = painterResource(KlicIcons.user),
                                title = stringResource(R.string.settings_my_profile),
                                onClick = onEditProfile,
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            // §12.2: account email — add via Google, or show + remove.
                            EmailRow(vm)
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            SettingsRow(
                                icon = painterResource(R.drawable.ic_line_sun),
                                title = stringResource(R.string.settings_appearance),
                                onClick = { route = SettingsRoute.Appearance },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            // §12.3: chat theme (pattern, gradient, bubble color).
                            SettingsRow(
                                icon = painterResource(R.drawable.ic_line_gallery),
                                title = stringResource(R.string.settings_chat_themes),
                                onClick = { route = SettingsRoute.ChatTheme },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            SettingsRow(
                                icon = painterResource(R.drawable.ic_line_notification),
                                title = stringResource(R.string.settings_notifications),
                                onClick = { route = SettingsRoute.Notifications },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            SettingsRow(
                                icon = painterResource(R.drawable.ic_line_chart),
                                title = stringResource(R.string.settings_data_storage),
                                onClick = { route = SettingsRoute.DataStorage },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            SettingsRow(
                                icon = painterResource(R.drawable.ic_line_sun),
                                title = stringResource(R.string.settings_language),
                                onClick = { route = SettingsRoute.Language },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            SettingsRow(
                                icon = painterResource(KlicIcons.gallery),
                                title = stringResource(R.string.settings_qr_code),
                                onClick = { route = SettingsRoute.QrCode },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            SettingsRow(
                                icon = painterResource(KlicIcons.phone),
                                title = stringResource(R.string.settings_recent_calls),
                                onClick = { route = SettingsRoute.RecentCalls },
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        // Card 2: Updates
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                                .padding(horizontal = 18.dp),
                        ) {
                            SettingsRow(
                                icon = painterResource(R.drawable.ic_bold_arrow_bottom),
                                title = stringResource(R.string.settings_updates),
                                onClick = { route = SettingsRoute.Updates },
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        // Card 3: Privacy
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                                .padding(horizontal = 18.dp),
                        ) {
                            SettingsRow(
                                icon = painterResource(R.drawable.ic_line_lock),
                                title = stringResource(R.string.settings_privacy_security),
                                onClick = { route = SettingsRoute.Privacy },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            // §12.1: target-less report — "something in the app is broken".
                            SettingsRow(
                                icon = painterResource(R.drawable.ic_line_report),
                                title = stringResource(R.string.settings_report_problem),
                                onClick = { showProblemReport = true },
                            )
                        }

                        Spacer(Modifier.height(16.dp))

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
                                Text(stringResource(R.string.dialog_reliable_calls_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    stringResource(R.string.settings_reliable_calls_sub),
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

                        // Logout
                        Button(
                            onClick = { vm.logout() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) {
                            Text(stringResource(R.string.settings_log_out), modifier = Modifier.padding(vertical = 6.dp))
                        }

                        Spacer(Modifier.height(20.dp))

                        KlicLottieView(
                            name = "07",
                            modifier = Modifier.fillMaxWidth().height(140.dp),
                        )
                        Text(
                            stringResource(R.string.settings_version_format, versionName),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp),
                        )
                    }

                    SettingsRoute.Appearance -> {
                        SubScreenHeader(title = stringResource(R.string.settings_appearance), onBack = { route = SettingsRoute.Main })

                        // Card 1: Chat Themes (§12.3)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                                .padding(horizontal = 18.dp),
                        ) {
                            SettingsRow(
                                icon = painterResource(R.drawable.ic_line_gallery),
                                title = stringResource(R.string.settings_chat_themes),
                                onClick = { route = SettingsRoute.ChatTheme },
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        // Card 2: Auto-Night Mode — shows current mode label inline
                        val modeDisplayName = when (themeMode) {
                            "light" -> stringResource(R.string.settings_night_disabled)
                            "dark" -> stringResource(R.string.settings_night_dark)
                            else -> stringResource(R.string.settings_night_system)
                        }
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                                .padding(horizontal = 18.dp),
                        ) {
                            SettingsRow(
                                icon = painterResource(R.drawable.ic_line_moon),
                                title = stringResource(R.string.settings_auto_night),
                                onClick = { route = SettingsRoute.AutoNightMode },
                                trailing = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            modeDisplayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                },
                            )
                        }
                    }

                    SettingsRoute.AutoNightMode -> {
                        SubScreenHeader(title = stringResource(R.string.settings_auto_night), onBack = { route = SettingsRoute.Appearance })

                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                                .padding(horizontal = 18.dp),
                        ) {
                            NightModeOption(
                                title = stringResource(R.string.settings_night_system),
                                subtitle = stringResource(R.string.settings_night_system_sub),
                                isActive = themeMode == "system",
                                onClick = { vm.setThemeMode("system") },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            NightModeOption(
                                title = stringResource(R.string.settings_night_disabled),
                                subtitle = stringResource(R.string.settings_night_disabled_sub),
                                isActive = themeMode == "light",
                                onClick = { vm.setThemeMode("light") },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            NightModeOption(
                                title = stringResource(R.string.settings_night_scheduled),
                                subtitle = stringResource(R.string.settings_night_scheduled_sub),
                                isActive = themeMode == "system",
                                onClick = { vm.setThemeMode("system") },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            NightModeOption(
                                title = stringResource(R.string.settings_night_automatic),
                                subtitle = stringResource(R.string.settings_night_automatic_sub),
                                isActive = themeMode == "system",
                                onClick = { vm.setThemeMode("system") },
                            )
                        }
                    }

                    SettingsRoute.Updates -> {
                        SubScreenHeader(title = stringResource(R.string.settings_updates), onBack = { route = SettingsRoute.Main })

                        // App info card
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                                .padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                painter = painterResource(KlicIcons.add),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(56.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(R.string.settings_klic_version_format, versionName),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.settings_manage_updates),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        // Info rows card
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                                .padding(horizontal = 18.dp),
                        ) {
                            InfoRow(label = stringResource(R.string.settings_version_label), value = versionName)
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            InfoRow(label = stringResource(R.string.settings_platform), value = "Android")
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            InfoRow(label = stringResource(R.string.settings_distribution), value = "GitHub Releases")
                        }

                        Spacer(Modifier.height(16.dp))

                        // App updates — check GitHub releases and self-install (no Play Store).
                        AppUpdateCard(versionName = versionName, scope = scope, context = context)

                        Spacer(Modifier.height(12.dp))

                        Text(
                            stringResource(R.string.settings_updates_footer),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    SettingsRoute.Notifications -> {
                        SubScreenHeader(title = stringResource(R.string.settings_notifications), onBack = { route = SettingsRoute.Main })
                        NotificationsSettingsContent(vm)
                    }

                    SettingsRoute.DataStorage -> {
                        SubScreenHeader(title = stringResource(R.string.settings_data_storage), onBack = { route = SettingsRoute.Main })
                        DataStorageContent(vm)
                    }

                    SettingsRoute.Privacy -> {
                        SubScreenHeader(
                            title = stringResource(R.string.settings_privacy_security),
                            onBack = { route = SettingsRoute.Main },
                        )

                        // §11.6: the old "show last seen" toggle is replaced by the
                        // "Last seen & online" visibility row inside the Privacy card.
                        // §10.4: Blocked users, app lock, passkeys, links, data, account.
                        PrivacySecurityContent(vm) { sub ->
                            route = when (sub) {
                                PrivacySecuritySub.BLOCKED -> SettingsRoute.PrivacyBlocked
                                PrivacySecuritySub.APP_LOCK -> SettingsRoute.PrivacyAppLock
                                PrivacySecuritySub.PASSKEYS -> SettingsRoute.PrivacyPasskeys
                            }
                        }
                    }

                    SettingsRoute.PrivacyBlocked -> {
                        SubScreenHeader(
                            title = stringResource(R.string.privacy_blocked_users),
                            onBack = { route = SettingsRoute.Privacy },
                        )
                        BlockedUsersContent(vm)
                    }

                    SettingsRoute.PrivacyAppLock -> {
                        SubScreenHeader(
                            title = stringResource(R.string.privacy_passcode_biometrics),
                            onBack = { route = SettingsRoute.Privacy },
                        )
                        AppLockContent()
                    }

                    SettingsRoute.PrivacyPasskeys -> {
                        SubScreenHeader(
                            title = stringResource(R.string.privacy_passkeys),
                            onBack = { route = SettingsRoute.Privacy },
                        )
                        PasskeysContent(vm)
                    }

                    SettingsRoute.Language -> {
                        SubScreenHeader(
                            title = stringResource(R.string.settings_language),
                            onBack = { route = SettingsRoute.Main },
                        )
                        LanguageContent()
                    }

                    SettingsRoute.QrCode -> {
                        SubScreenHeader(
                            title = stringResource(R.string.settings_qr_code),
                            onBack = { route = SettingsRoute.Main },
                        )
                        QrCodeContent(vm)
                    }

                    SettingsRoute.RecentCalls -> {
                        SubScreenHeader(
                            title = stringResource(R.string.settings_recent_calls),
                            onBack = { route = SettingsRoute.Main },
                        )
                        // §10.6: the EXISTING recent-calls component — no duplicate.
                        com.klic.mobile.app.feature.call.RecentCallsList(vm)
                    }

                    SettingsRoute.ChatTheme -> {
                        SubScreenHeader(
                            title = stringResource(R.string.settings_chat_themes),
                            onBack = { route = SettingsRoute.Main },
                        )
                        ChatThemeContent()
                    }
                }
            }
        }
    }

    // §12.1: "Report a problem" — the shared report sheet with no target.
    if (showProblemReport) {
        com.klic.mobile.app.feature.report.ReportSheet(
            vm = vm,
            target = com.klic.mobile.app.feature.report.ReportTarget.Problem,
            onDismiss = { showProblemReport = false },
        )
    }
}

/**
 * §12.2 Email row: no email → "Add" launches the Google picker; linked → the address
 * with a "Verified" badge, tap → confirm-remove dialog (DELETE /me/email).
 */
@Composable
private fun EmailRow(vm: KlicViewModel) {
    val user by vm.currentUser.collectAsState()
    val busy by vm.emailBusy.collectAsState()
    val context = LocalContext.current
    var confirmRemove by remember { mutableStateOf(false) }
    val email = user?.email

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy) {
                if (email == null) vm.linkGoogleEmail(context) else confirmRemove = true
            }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_line_message),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.settings_email),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (email != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        email,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (user?.emailVerified == true) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape)
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        ) {
                            Icon(
                                painter = painterResource(KlicIcons.check),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(9.dp),
                            )
                            Text(
                                stringResource(R.string.settings_email_verified),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
        if (busy) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        } else if (email == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.settings_email_add),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }

    if (confirmRemove && email != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.settings_email_remove_title)) },
            text = { Text(stringResource(R.string.settings_email_remove_body, email)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmRemove = false
                    vm.removeEmail()
                }) {
                    Text(stringResource(R.string.settings_email_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmRemove = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun SubScreenHeader(title: String, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(KlicIcons.back),
                contentDescription = stringResource(R.string.common_back),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun SettingsRow(
    icon: Painter,
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    RoundedCornerShape(8.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            trailing()
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun NightModeOption(
    title: String,
    subtitle: String,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isActive) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
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
