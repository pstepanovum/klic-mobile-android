package com.klic.mobile.app.feature.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.R
import com.klic.mobile.app.data.AppLockStore
import com.klic.mobile.app.ui.components.KlicSelectionSheet
import com.klic.mobile.app.ui.components.KlicSheetOption
import com.klic.mobile.app.ui.theme.KlicIcons
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AppLockContent() {
    val context = LocalContext.current
    val lockEnabled by AppLockStore.enabled.collectAsStateWithLifecycle()
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
