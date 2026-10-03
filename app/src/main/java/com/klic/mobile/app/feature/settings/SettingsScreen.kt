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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.BuildConfig
import com.klic.mobile.app.R
import com.klic.mobile.app.calling.CallReliability
import com.klic.mobile.app.feature.KlicViewModel
import com.klic.mobile.app.ui.components.AvatarView
import com.klic.mobile.app.ui.components.KlicLottieView
import com.klic.mobile.app.ui.theme.KlicIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(
    vm: KlicViewModel,
    onEditProfile: () -> Unit = {},
    /** §14.4: a saved-messages row was tapped — open its conversation at the message. */
    onOpenMessage: (conversationId: String, messageId: String) -> Unit = { _, _ -> },
) {
    val user by vm.currentUser.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
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
            SettingsRoute.AutoNightMode,
            SettingsRoute.ChatTheme -> SettingsRoute.Appearance
            SettingsRoute.PrivacyBlocked,
            SettingsRoute.PrivacyAppLock,
            SettingsRoute.PrivacyPasskeys,
            SettingsRoute.PrivacyChangePassword,
            SettingsRoute.PrivacyRecoveryEmail -> SettingsRoute.Privacy
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
                                icon = painterResource(KlicIcons.userLine),
                                title = stringResource(R.string.settings_my_profile),
                                onClick = onEditProfile,
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            // §12.2: account email — add via Google, or show + remove.
                            EmailRow(vm)
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            SettingsRow(
                                icon = painterResource(KlicIcons.appearance),
                                title = stringResource(R.string.settings_appearance),
                                onClick = { route = SettingsRoute.Appearance },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            // §13.6: the Chat theme entry lives ONLY under Appearance.
                            SettingsRow(
                                icon = painterResource(KlicIcons.bell),
                                title = stringResource(R.string.settings_notifications),
                                onClick = { route = SettingsRoute.Notifications },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            SettingsRow(
                                icon = painterResource(KlicIcons.data),
                                title = stringResource(R.string.settings_data_storage),
                                onClick = { route = SettingsRoute.DataStorage },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            SettingsRow(
                                icon = painterResource(KlicIcons.language),
                                title = stringResource(R.string.settings_language),
                                onClick = { route = SettingsRoute.Language },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            SettingsRow(
                                icon = painterResource(KlicIcons.qr),
                                title = stringResource(R.string.settings_qr_code),
                                onClick = { route = SettingsRoute.QrCode },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            SettingsRow(
                                icon = painterResource(KlicIcons.tabCall),
                                title = stringResource(R.string.settings_recent_calls),
                                onClick = { route = SettingsRoute.RecentCalls },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            // §14.4: everything the user starred, across all chats.
                            SettingsRow(
                                icon = painterResource(KlicIcons.starLine),
                                title = stringResource(R.string.settings_saved_messages),
                                onClick = { route = SettingsRoute.SavedMessages },
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        // Card 2: Updates — GitHub builds only; Play delivers its own updates.
                        if (BuildConfig.SELF_UPDATER_ENABLED) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                                    .padding(horizontal = 18.dp),
                            ) {
                                SettingsRow(
                                    icon = painterResource(KlicIcons.update),
                                    title = stringResource(R.string.settings_updates),
                                    onClick = { route = SettingsRoute.Updates },
                                )
                            }

                            Spacer(Modifier.height(16.dp))
                        }

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
                            // Legal: the same documents shown during sign-up.
                            SettingsRow(
                                icon = painterResource(KlicIcons.document),
                                title = stringResource(R.string.pp_title),
                                onClick = { route = SettingsRoute.PrivacyPolicy },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            SettingsRow(
                                icon = painterResource(KlicIcons.document),
                                title = stringResource(R.string.tos_title),
                                onClick = { route = SettingsRoute.Terms },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            // §12.1: target-less report — "something in the app is broken".
                            SettingsRow(
                                icon = painterResource(KlicIcons.report),
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
                                icon = painterResource(KlicIcons.theme),
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
                                icon = painterResource(KlicIcons.moon),
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
                            KlicLottieView(
                                name = "07",
                                modifier = Modifier.fillMaxWidth().height(140.dp),
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
                            InfoRow(
                                label = stringResource(R.string.settings_distribution),
                                value = if (BuildConfig.SELF_UPDATER_ENABLED) "GitHub Releases" else "Google Play",
                            )
                        }

                        // App updates — check GitHub releases and self-install. Play builds
                        // never offer APK installs; Google Play delivers updates itself.
                        if (BuildConfig.SELF_UPDATER_ENABLED) {
                            Spacer(Modifier.height(16.dp))

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
                                PrivacySecuritySub.CHANGE_PASSWORD -> SettingsRoute.PrivacyChangePassword
                                PrivacySecuritySub.RECOVERY_EMAIL -> SettingsRoute.PrivacyRecoveryEmail
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

                    SettingsRoute.PrivacyChangePassword -> {
                        SubScreenHeader(
                            title = stringResource(R.string.privacy_change_password),
                            onBack = { route = SettingsRoute.Privacy },
                        )
                        ChangePasswordContent(vm)
                    }

                    SettingsRoute.PrivacyRecoveryEmail -> {
                        SubScreenHeader(
                            title = stringResource(R.string.privacy_recovery_email),
                            onBack = { route = SettingsRoute.Privacy },
                        )
                        RecoveryEmailContent(vm)
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
                            onBack = { route = SettingsRoute.Appearance },
                        )
                        ChatThemeContent()
                    }

                    SettingsRoute.SavedMessages -> {
                        SubScreenHeader(
                            title = stringResource(R.string.settings_saved_messages),
                            onBack = { route = SettingsRoute.Main },
                        )
                        SavedMessagesContent(vm, onOpenMessage)
                    }

                    SettingsRoute.PrivacyPolicy -> {
                        SubScreenHeader(
                            title = stringResource(R.string.pp_title),
                            onBack = { route = SettingsRoute.Main },
                        )
                        com.klic.mobile.app.feature.auth.PrivacyPolicyContent()
                    }

                    SettingsRoute.Terms -> {
                        SubScreenHeader(
                            title = stringResource(R.string.tos_title),
                            onBack = { route = SettingsRoute.Main },
                        )
                        com.klic.mobile.app.feature.auth.TermsOfServiceContent()
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
