package com.klic.mobile.app.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.klic.mobile.app.R
import com.klic.mobile.app.data.AppLockStore
import com.klic.mobile.app.data.LinkOpener
import com.klic.mobile.app.data.SettingsStore
import com.klic.mobile.app.feature.KlicViewModel
import com.klic.mobile.app.ui.components.KlicSelectionSheet
import com.klic.mobile.app.ui.components.KlicSheetOption
import com.klic.mobile.app.ui.theme.KlicIcons
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Sub-pages of Privacy and Security (§10.4, §18.2). */
enum class PrivacySecuritySub { BLOCKED, APP_LOCK, PASSKEYS, CHANGE_PASSWORD, RECOVERY_EMAIL }

// ─────────────────────────────────────────────────────────
// Main page
// ─────────────────────────────────────────────────────────

@Composable
fun PrivacySecurityContent(vm: KlicViewModel, onOpenSub: (PrivacySecuritySub) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by SettingsStore.snapshot.collectAsStateWithLifecycle()
    val lockEnabled by AppLockStore.enabled.collectAsStateWithLifecycle()

    var showLinkSheet by remember { mutableStateOf(false) }
    var showClearCookies by remember { mutableStateOf(false) }
    var showAwaySheet by remember { mutableStateOf(false) }
    var showDeleteDrafts by remember { mutableStateOf(false) }
    var showResetHidden by remember { mutableStateOf(false) }
    var showDeleteAccount by remember { mutableStateOf(false) }
    val me by vm.currentUser.collectAsStateWithLifecycle()
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
        RowDivider()
        // Messages hidden via the long-press "Hide" action become visible again.
        PrivacyRow(
            icon = KlicIcons.lastSeen,
            title = stringResource(R.string.privacy_reset_hidden),
            onClick = { showResetHidden = true },
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

    if (showResetHidden) {
        val hiddenReset = stringResource(R.string.privacy_hidden_reset_done)
        AlertDialog(
            onDismissRequest = { showResetHidden = false },
            title = { Text(stringResource(R.string.privacy_reset_hidden)) },
            text = { Text(stringResource(R.string.privacy_reset_hidden_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showResetHidden = false
                    scope.launch { SettingsStore.resetHiddenMessages() }
                    Toast.makeText(context, hiddenReset, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.common_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetHidden = false }) { Text(stringResource(R.string.common_cancel)) }
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
    val me by vm.currentUser.collectAsStateWithLifecycle()
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
