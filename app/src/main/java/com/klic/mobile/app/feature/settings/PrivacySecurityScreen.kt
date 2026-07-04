package com.klic.mobile.app.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.klic.mobile.app.R
import com.klic.mobile.app.data.AppLockStore
import com.klic.mobile.app.data.LinkOpener
import com.klic.mobile.app.data.SettingsStore
import com.klic.mobile.app.feature.KlicViewModel
import com.klic.mobile.app.ui.components.AvatarView
import com.klic.mobile.app.ui.components.KlicSelectionSheet
import com.klic.mobile.app.ui.components.KlicSheetOption
import com.klic.mobile.app.ui.components.KlicTextField
import com.klic.mobile.app.ui.components.PillButton
import kotlinx.coroutines.delay
import com.klic.mobile.app.ui.theme.KlicIcons
import kotlinx.coroutines.launch

/** Sub-pages of Privacy and Security (§10.4, §18.2). */
enum class PrivacySecuritySub { BLOCKED, APP_LOCK, PASSKEYS, CHANGE_PASSWORD, RECOVERY_EMAIL }

// ─────────────────────────────────────────────────────────
// Main page
// ─────────────────────────────────────────────────────────

@Composable
fun PrivacySecurityContent(vm: KlicViewModel, onOpenSub: (PrivacySecuritySub) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by SettingsStore.snapshot.collectAsState()
    val lockEnabled by AppLockStore.enabled.collectAsState()

    var showLinkSheet by remember { mutableStateOf(false) }
    var showClearCookies by remember { mutableStateOf(false) }
    var showAwaySheet by remember { mutableStateOf(false) }
    var showDeleteDrafts by remember { mutableStateOf(false) }
    var showDeleteAccount by remember { mutableStateOf(false) }
    val me by vm.currentUser.collectAsState()
    var awayMonths by remember(me?.deleteIfAwayMonths) { mutableStateOf(me?.deleteIfAwayMonths) }
    // §11.6: which visibility field the picker sheet currently edits (null = closed).
    var visibilityField by remember { mutableStateOf<String?>(null) }

    // ── Card 0 (§11.6): Privacy — visibility pickers + call/read-receipt toggles ──
    // Server defaults: everything EVERYBODY except lastSeenVisibility (FRIENDS).
    fun visibilityOf(field: String): String = when (field) {
        "lastSeenVisibility" -> me?.lastSeenVisibility ?: "FRIENDS"
        "aboutVisibility" -> me?.aboutVisibility ?: "EVERYBODY"
        "avatarVisibility" -> me?.avatarVisibility ?: "EVERYBODY"
        "linksVisibility" -> me?.linksVisibility ?: "EVERYBODY"
        "groupsVisibility" -> me?.groupsVisibility ?: "EVERYBODY"
        else -> me?.statusVisibility ?: "EVERYBODY"
    }

    SectionLabel(stringResource(R.string.privacy_privacy_section))
    SettingsCard {
        PrivacyRow(
            icon = KlicIcons.lastSeen,
            title = stringResource(R.string.privacy_last_seen_online),
            value = visibilityLabel(visibilityOf("lastSeenVisibility")),
            onClick = { visibilityField = "lastSeenVisibility" },
        )
        RowDivider()
        PrivacyRow(
            icon = KlicIcons.userLine,
            title = stringResource(R.string.privacy_about_row),
            value = visibilityLabel(visibilityOf("aboutVisibility")),
            onClick = { visibilityField = "aboutVisibility" },
        )
        RowDivider()
        PrivacyRow(
            icon = KlicIcons.photo,
            title = stringResource(R.string.privacy_profile_picture),
            value = visibilityLabel(visibilityOf("avatarVisibility")),
            onClick = { visibilityField = "avatarVisibility" },
        )
        RowDivider()
        PrivacyRow(
            icon = KlicIcons.link,
            title = stringResource(R.string.privacy_links_row),
            value = visibilityLabel(visibilityOf("linksVisibility")),
            onClick = { visibilityField = "linksVisibility" },
        )
        RowDivider()
        PrivacyRow(
            icon = KlicIcons.usersGroup,
            title = stringResource(R.string.privacy_groups_row),
            value = visibilityLabel(visibilityOf("groupsVisibility")),
            onClick = { visibilityField = "groupsVisibility" },
        )
        RowDivider()
        PrivacyRow(
            icon = KlicIcons.status,
            title = stringResource(R.string.privacy_status_row),
            value = visibilityLabel(visibilityOf("statusVisibility")),
            onClick = { visibilityField = "statusVisibility" },
        )
        RowDivider()
        // Calls: silence unknown callers (§11.6) — no ring from non-friends.
        ToggleRow(
            title = stringResource(R.string.privacy_silence_unknown),
            subtitle = stringResource(R.string.privacy_silence_unknown_sub),
            checked = me?.silenceUnknownCallers == true,
            onChange = { value -> vm.setPrivacyToggle("silenceUnknownCallers", value) },
        )
        RowDivider()
        // Read receipts: reciprocal, DMs only (§11.6).
        ToggleRow(
            title = stringResource(R.string.privacy_read_receipts),
            subtitle = stringResource(R.string.privacy_read_receipts_sub),
            checked = me?.readReceipts != false,
            onChange = { value -> vm.setPrivacyToggle("readReceipts", value) },
        )
    }

    Spacer(Modifier.height(16.dp))

    // Card 1: Blocked / App lock / Passkeys
    SettingsCard {
        PrivacyRow(
            icon = KlicIcons.userBlock,
            title = stringResource(R.string.privacy_blocked_users),
            onClick = { onOpenSub(PrivacySecuritySub.BLOCKED) },
        )
        RowDivider()
        PrivacyRow(
            icon = KlicIcons.passcode,
            title = stringResource(R.string.privacy_passcode_biometrics),
            value = if (lockEnabled) stringResource(R.string.common_on) else stringResource(R.string.common_off),
            onClick = { onOpenSub(PrivacySecuritySub.APP_LOCK) },
        )
        RowDivider()
        PrivacyRow(
            icon = KlicIcons.passkey,
            title = stringResource(R.string.privacy_passkeys),
            onClick = { onOpenSub(PrivacySecuritySub.PASSKEYS) },
        )
    }

    Spacer(Modifier.height(16.dp))

    // Card 1b (§18.2): Account recovery — change password + recovery email
    SectionLabel(stringResource(R.string.privacy_recovery_section))
    SettingsCard {
        PrivacyRow(
            icon = KlicIcons.passcode,
            title = stringResource(R.string.privacy_change_password),
            onClick = { onOpenSub(PrivacySecuritySub.CHANGE_PASSWORD) },
        )
        RowDivider()
        PrivacyRow(
            icon = KlicIcons.email,
            title = stringResource(R.string.privacy_recovery_email),
            value = me?.email ?: stringResource(R.string.recovery_email_none),
            onClick = { onOpenSub(PrivacySecuritySub.RECOVERY_EMAIL) },
        )
    }

    Spacer(Modifier.height(16.dp))

    // Card 2: Open links in
    SectionLabel(stringResource(R.string.privacy_links_section))
    SettingsCard {
        PrivacyRow(
            icon = KlicIcons.globe,
            title = stringResource(R.string.privacy_open_links_in),
            value = when (settings.linkOpenMode) {
                SettingsStore.LINKS_CHROME -> stringResource(R.string.privacy_links_chrome)
                SettingsStore.LINKS_SYSTEM -> stringResource(R.string.privacy_links_system)
                else -> stringResource(R.string.privacy_links_in_app)
            },
            onClick = { showLinkSheet = true },
        )
        RowDivider()
        ToggleRow(
            title = stringResource(R.string.privacy_dont_open_in_app),
            subtitle = stringResource(R.string.privacy_dont_open_in_app_sub),
            checked = settings.neverOpenLinksInApp,
            onChange = { value -> scope.launch { SettingsStore.setNeverOpenLinksInApp(value) } },
        )
        RowDivider()
        PrivacyRow(
            icon = KlicIcons.cookie,
            title = stringResource(R.string.privacy_clear_cookies),
            onClick = { showClearCookies = true },
        )
    }

    Spacer(Modifier.height(16.dp))

    // Card 3: Data settings
    SectionLabel(stringResource(R.string.privacy_data_section))
    val contactsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.syncContactsNow(context)
        else Toast.makeText(context, context.getString(R.string.privacy_contacts_permission_denied), Toast.LENGTH_LONG).show()
    }
    SettingsCard {
        ToggleRow(
            title = stringResource(R.string.privacy_sync_contacts),
            subtitle = stringResource(R.string.privacy_sync_contacts_sub),
            checked = settings.contactsSyncEnabled,
            onChange = { value ->
                if (value) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        vm.syncContactsNow(context)
                    } else {
                        contactsPermission.launch(Manifest.permission.READ_CONTACTS)
                    }
                } else {
                    scope.launch { SettingsStore.setContactsSyncEnabled(false) }
                }
            },
        )
        RowDivider()
        PrivacyRow(
            icon = KlicIcons.userRemove,
            title = stringResource(R.string.privacy_delete_synced_contacts),
            onClick = { vm.deleteSyncedContacts() },
        )
        RowDivider()
        ToggleRow(
            title = stringResource(R.string.privacy_suggest_frequent),
            subtitle = stringResource(R.string.privacy_suggest_frequent_sub),
            checked = settings.suggestFrequentContacts,
            onChange = { value -> scope.launch { SettingsStore.setSuggestFrequentContacts(value) } },
        )
        RowDivider()
        PrivacyRow(
            icon = KlicIcons.slashCircle,
            title = stringResource(R.string.privacy_delete_all_drafts),
            onClick = { showDeleteDrafts = true },
        )
    }

    Spacer(Modifier.height(16.dp))

    // Card 4: Automatically delete my account
    SectionLabel(stringResource(R.string.privacy_account_section))
    SettingsCard {
        PrivacyRow(
            icon = KlicIcons.trash,
            title = stringResource(R.string.privacy_delete_if_away),
            value = awayMonths?.let { stringResource(R.string.privacy_months_format, it) }
                ?: stringResource(R.string.common_off),
            onClick = { showAwaySheet = true },
        )
        RowDivider()
        Row(
            Modifier.fillMaxWidth().clickable { showDeleteAccount = true }.padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.privacy_delete_account_now),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    Spacer(Modifier.height(24.dp))

    if (showLinkSheet) {
        KlicSelectionSheet(
            title = stringResource(R.string.privacy_open_links_in),
            options = listOf(
                KlicSheetOption(SettingsStore.LINKS_IN_APP, stringResource(R.string.privacy_links_in_app),
                    stringResource(R.string.privacy_links_in_app_sub)),
                KlicSheetOption(SettingsStore.LINKS_CHROME, stringResource(R.string.privacy_links_chrome)),
                KlicSheetOption(SettingsStore.LINKS_SYSTEM, stringResource(R.string.privacy_links_system)),
            ),
            selectedValue = settings.linkOpenMode,
            onSelect = { mode ->
                scope.launch { SettingsStore.setLinkOpenMode(mode) }
                showLinkSheet = false
            },
            onDismiss = { showLinkSheet = false },
        )
    }

    if (showClearCookies) {
        val clearedToast = stringResource(R.string.privacy_cookies_cleared)
        AlertDialog(
            onDismissRequest = { showClearCookies = false },
            title = { Text(stringResource(R.string.privacy_clear_cookies)) },
            text = { Text(stringResource(R.string.privacy_clear_cookies_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearCookies = false
                    LinkOpener.clearCookies {
                        Toast.makeText(context, clearedToast, Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.common_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearCookies = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showAwaySheet) {
        KlicSelectionSheet(
            title = stringResource(R.string.privacy_delete_if_away),
            options = listOf(
                KlicSheetOption("off", stringResource(R.string.common_off)),
                KlicSheetOption("1", stringResource(R.string.privacy_months_format, 1)),
                KlicSheetOption("3", stringResource(R.string.privacy_months_format, 3)),
                KlicSheetOption("6", stringResource(R.string.privacy_months_format, 6)),
                KlicSheetOption("12", stringResource(R.string.privacy_months_format, 12)),
                KlicSheetOption("18", stringResource(R.string.privacy_months_format, 18)),
                KlicSheetOption("24", stringResource(R.string.privacy_months_format, 24)),
            ),
            selectedValue = awayMonths?.toString() ?: "off",
            onSelect = { value ->
                val months = value.toIntOrNull()
                awayMonths = months
                vm.setDeleteIfAwayMonths(months)
                showAwaySheet = false
            },
            onDismiss = { showAwaySheet = false },
        )
    }

    if (showDeleteDrafts) {
        val draftsDeleted = stringResource(R.string.privacy_drafts_deleted)
        AlertDialog(
            onDismissRequest = { showDeleteDrafts = false },
            title = { Text(stringResource(R.string.privacy_delete_all_drafts)) },
            text = { Text(stringResource(R.string.privacy_delete_drafts_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDrafts = false
                    vm.deleteAllDrafts()
                    Toast.makeText(context, draftsDeleted, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDrafts = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showDeleteAccount) {
        DeleteAccountDialog(vm, onDismiss = { showDeleteAccount = false })
    }

    // §11.6: Everybody / My friends / Nobody picker for the tapped visibility row.
    visibilityField?.let { field ->
        KlicSelectionSheet(
            title = when (field) {
                "lastSeenVisibility" -> stringResource(R.string.privacy_last_seen_online)
                "aboutVisibility" -> stringResource(R.string.privacy_about_row)
                "avatarVisibility" -> stringResource(R.string.privacy_profile_picture)
                "linksVisibility" -> stringResource(R.string.privacy_links_row)
                "groupsVisibility" -> stringResource(R.string.privacy_groups_row)
                else -> stringResource(R.string.privacy_status_row)
            },
            options = listOf(
                KlicSheetOption("EVERYBODY", stringResource(R.string.privacy_everybody)),
                KlicSheetOption("FRIENDS", stringResource(R.string.privacy_my_friends)),
                KlicSheetOption("NOBODY", stringResource(R.string.privacy_nobody)),
            ),
            selectedValue = visibilityOf(field),
            onSelect = { value ->
                vm.setPrivacyVisibility(field, value)
                visibilityField = null
            },
            onDismiss = { visibilityField = null },
        )
    }
}

/** §11.6: display label for a visibility enum value. */
@Composable
private fun visibilityLabel(value: String): String = when (value) {
    "FRIENDS" -> stringResource(R.string.privacy_my_friends)
    "NOBODY" -> stringResource(R.string.privacy_nobody)
    else -> stringResource(R.string.privacy_everybody)
}

/** Double confirm: first warning, then type-the-username, then DELETE /me. */
@Composable
private fun DeleteAccountDialog(vm: KlicViewModel, onDismiss: () -> Unit) {
    val me by vm.currentUser.collectAsState()
    var step by remember { mutableStateOf(1) }
    var typed by remember { mutableStateOf("") }
    val username = me?.username.orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.privacy_delete_account_now)) },
        text = {
            Column {
                if (step == 1) {
                    Text(stringResource(R.string.privacy_delete_account_warning))
                } else {
                    Text(stringResource(R.string.privacy_delete_account_type_username, username))
                    Spacer(Modifier.height(10.dp))
                    TextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                        shape = CircleShape,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        placeholder = { Text(username) },
                    )
                }
            }
        },
        confirmButton = {
            if (step == 1) {
                TextButton(onClick = { step = 2 }) {
                    Text(stringResource(R.string.common_continue), color = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(
                    onClick = { vm.deleteAccount(onDone = onDismiss) },
                    enabled = typed.trim().equals(username, ignoreCase = true) && username.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.privacy_delete_forever), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

// ─────────────────────────────────────────────────────────
// Blocked users
// ─────────────────────────────────────────────────────────

@Composable
fun BlockedUsersContent(vm: KlicViewModel) {
    val blocked by vm.blockedUsers.collectAsState()
    LaunchedEffect(Unit) { vm.loadBlocks() }

    if (blocked.isEmpty()) {
        Text(
            stringResource(R.string.privacy_no_blocked_users),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        return
    }
    SettingsCard {
        blocked.forEachIndexed { index, row ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarView(url = row.user.avatarUrl, name = row.user.displayName, size = 42.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(row.user.displayName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text("@${row.user.username}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(
                    onClick = { vm.unblockUser(row.user.id) },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) { Text(stringResource(R.string.privacy_unblock)) }
            }
            if (index != blocked.lastIndex) RowDivider()
        }
    }
}

// ─────────────────────────────────────────────────────────
// Passcode & biometrics
// ─────────────────────────────────────────────────────────

@Composable
fun AppLockContent() {
    val context = LocalContext.current
    val lockEnabled by AppLockStore.enabled.collectAsState()
    var showSetDialog by remember { mutableStateOf(false) }
    var showAutoLockSheet by remember { mutableStateOf(false) }
    var autoLock by remember { mutableStateOf(AppLockStore.autoLockMode) }
    var biometric by remember { mutableStateOf(AppLockStore.biometricEnabled) }

    SettingsCard {
        PrivacyRow(
            icon = R.drawable.ic_line_lock,
            title = if (lockEnabled) stringResource(R.string.applock_change_passcode)
                    else stringResource(R.string.applock_set_passcode),
            onClick = { showSetDialog = true },
        )
        if (lockEnabled) {
            RowDivider()
            PrivacyRow(
                icon = KlicIcons.close,
                title = stringResource(R.string.applock_remove_passcode),
                onClick = { AppLockStore.clearPasscode() },
            )
            RowDivider()
            ToggleRow(
                title = stringResource(R.string.applock_biometric_unlock),
                subtitle = stringResource(R.string.applock_biometric_sub),
                checked = biometric,
                onChange = { value ->
                    biometric = value
                    AppLockStore.biometricEnabled = value
                },
            )
            RowDivider()
            PrivacyRow(
                icon = R.drawable.ic_line_moon,
                title = stringResource(R.string.applock_auto_lock),
                value = autoLockLabel(autoLock),
                onClick = { showAutoLockSheet = true },
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.applock_footer),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (showSetDialog) {
        // §11.3: Klic-styled keypad sheet over blurred content (was an AlertDialog).
        com.klic.mobile.app.ui.components.SetPasscodeSheet(
            onDismiss = { showSetDialog = false },
            onSet = { code ->
                AppLockStore.setPasscode(code)
                showSetDialog = false
                Toast.makeText(context, context.getString(R.string.applock_passcode_saved), Toast.LENGTH_SHORT).show()
            },
        )
    }

    if (showAutoLockSheet) {
        KlicSelectionSheet(
            title = stringResource(R.string.applock_auto_lock),
            options = listOf(
                KlicSheetOption(AppLockStore.LOCK_IMMEDIATELY, stringResource(R.string.applock_immediately)),
                KlicSheetOption(AppLockStore.LOCK_AFTER_1_MIN, stringResource(R.string.applock_after_1min)),
                KlicSheetOption(AppLockStore.LOCK_AFTER_5_MIN, stringResource(R.string.applock_after_5min)),
                KlicSheetOption(AppLockStore.LOCK_ON_BACKGROUND, stringResource(R.string.applock_on_background)),
            ),
            selectedValue = autoLock,
            onSelect = { mode ->
                autoLock = mode
                AppLockStore.autoLockMode = mode
                showAutoLockSheet = false
            },
            onDismiss = { showAutoLockSheet = false },
        )
    }
}

@Composable
private fun autoLockLabel(mode: String): String = when (mode) {
    AppLockStore.LOCK_IMMEDIATELY -> stringResource(R.string.applock_immediately)
    AppLockStore.LOCK_AFTER_1_MIN -> stringResource(R.string.applock_after_1min)
    AppLockStore.LOCK_AFTER_5_MIN -> stringResource(R.string.applock_after_5min)
    else -> stringResource(R.string.applock_on_background)
}

// ─────────────────────────────────────────────────────────
// Passkeys
// ─────────────────────────────────────────────────────────

@Composable
fun PasskeysContent(vm: KlicViewModel) {
    val context = LocalContext.current
    val passkeys by vm.passkeyList.collectAsState()
    LaunchedEffect(Unit) { vm.loadPasskeys() }

    SettingsCard {
        if (passkeys.isEmpty()) {
            Text(
                stringResource(R.string.passkeys_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 14.dp),
            )
        }
        passkeys.forEachIndexed { index, key ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        key.label ?: stringResource(R.string.passkeys_default_label),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    key.createdAt?.let {
                        Text(
                            stringResource(R.string.passkeys_added_format, it.take(10)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = { vm.deletePasskey(key.id) }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            }
            if (index != passkeys.lastIndex) RowDivider()
        }
    }
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { vm.addPasskey(context) },
        modifier = Modifier.fillMaxWidth(),
        shape = CircleShape,
    ) { Text(stringResource(R.string.passkeys_add), modifier = Modifier.padding(vertical = 6.dp)) }
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.passkeys_footer),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ─────────────────────────────────────────────────────────
// Shared building blocks (SettingsScreen visual language)
// ─────────────────────────────────────────────────────────

// SettingsCard + SectionLabel are shared with DataStorageScreen (same package).

@Composable
internal fun RowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
}

@Composable
internal fun PrivacyRow(
    icon: Int,
    title: String,
    value: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
                painter = painterResource(icon),
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
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────
// §18.2: Account recovery — Change password + Recovery email
// ─────────────────────────────────────────────────────────

/** Change password (§18.2): current + new + confirm → POST /auth/change-password. */
@Composable
fun ChangePasswordContent(vm: KlicViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var current by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val mismatch = confirm.isNotEmpty() && newPass != confirm
    val valid = current.isNotBlank() && newPass.length >= 6 && newPass == confirm

    SectionLabel(stringResource(R.string.privacy_change_password))
    SettingsCard {
        Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            KlicTextField(
                value = current,
                onValueChange = { current = it; errorText = null },
                placeholder = stringResource(R.string.change_password_current),
                isPassword = true,
            )
            KlicTextField(
                value = newPass,
                onValueChange = { newPass = it; errorText = null },
                placeholder = stringResource(R.string.change_password_new),
                isPassword = true,
            )
            KlicTextField(
                value = confirm,
                onValueChange = { confirm = it; errorText = null },
                placeholder = stringResource(R.string.change_password_confirm),
                isPassword = true,
            )
        }
    }

    if (mismatch) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.change_password_mismatch),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
    errorText?.let {
        Spacer(Modifier.height(8.dp))
        Text(
            it,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }

    Spacer(Modifier.height(16.dp))
    PillButton(
        text = stringResource(R.string.change_password_save),
        enabled = valid && !busy,
        isLoading = busy,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    ) {
        busy = true
        errorText = null
        vm.changePassword(current, newPass) { err ->
            busy = false
            if (err == null) {
                current = ""; newPass = ""; confirm = ""
                Toast.makeText(context, context.getString(R.string.change_password_success), Toast.LENGTH_SHORT).show()
            } else {
                errorText = err
            }
        }
    }
}

/** Recovery email (§18.2): shows current state or an add flow; polls verification. */
@Composable
fun RecoveryEmailContent(vm: KlicViewModel) {
    val context = LocalContext.current
    val me by vm.currentUser.collectAsState()
    val status by vm.emailStatus.collectAsState()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var showRemove by remember { mutableStateOf(false) }

    // The verified bit comes from Firebase via GET /me/email/status; fall back to /me.
    val currentEmail = status?.email ?: me?.email
    val verified = status?.emailVerified ?: (me?.emailVerified == true)
    val hasEmail = !currentEmail.isNullOrBlank()

    LaunchedEffect(Unit) { vm.refreshEmailStatus() }
    // While an email is on file but unverified, poll for the verification to land.
    LaunchedEffect(hasEmail, verified) {
        if (hasEmail && !verified) {
            while (true) {
                delay(4000)
                vm.refreshEmailStatus()
            }
        }
    }

    if (!hasEmail) {
        // Gentle prompt for username-only users.
        Text(
            stringResource(R.string.recovery_email_prompt),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(12.dp))
        SettingsCard {
            Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                KlicTextField(
                    value = email,
                    onValueChange = { email = it; errorText = null },
                    placeholder = stringResource(R.string.recovery_email_placeholder),
                )
                // Server needs the current password to give the Firebase shadow a matching
                // one, so a later reset can sync back to login (§18.2).
                KlicTextField(
                    value = password,
                    onValueChange = { password = it; errorText = null },
                    placeholder = stringResource(R.string.recovery_email_password),
                    isPassword = true,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.recovery_email_password_hint),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        errorText?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 8.dp))
        }
        Spacer(Modifier.height(16.dp))
        PillButton(
            text = stringResource(R.string.recovery_email_add),
            enabled = email.contains("@") && password.isNotBlank() && !busy,
            isLoading = busy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        ) {
            busy = true
            errorText = null
            vm.setRecoveryEmail(email, password) { err ->
                busy = false
                if (err == null) {
                    email = ""; password = ""
                    Toast.makeText(context, context.getString(R.string.recovery_email_sent), Toast.LENGTH_LONG).show()
                } else {
                    errorText = err
                }
            }
        }
    } else {
        SettingsCard {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(currentEmail!!, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        if (verified) stringResource(R.string.recovery_email_verified)
                        else stringResource(R.string.recovery_email_pending),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (verified) {
                    Box(
                        Modifier
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape)
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    ) {
                        Text(
                            stringResource(R.string.recovery_email_verified_badge),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        PillButton(
            text = stringResource(R.string.recovery_email_remove),
            fill = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        ) { showRemove = true }
    }

    if (showRemove) {
        AlertDialog(
            onDismissRequest = { showRemove = false },
            title = { Text(stringResource(R.string.recovery_email_remove_title)) },
            text = { Text(stringResource(R.string.recovery_email_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showRemove = false
                    vm.removeEmail()
                    vm.refreshEmailStatus()
                }) { Text(stringResource(R.string.common_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { showRemove = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
internal fun ToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
