package com.klic.mobile.app.ui.components

import android.app.Activity
import android.os.Build
import android.os.CancellationSignal
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.klic.mobile.app.R
import com.klic.mobile.app.data.AppLockStore
import com.klic.mobile.app.ui.theme.KlicIcons

/**
 * Full-screen app-lock overlay (§10.4): PIN dots + Klic-styled keypad, with a
 * biometric shortcut (framework BiometricPrompt, API 28+) when enabled. Rendered
 * above ALL app content in MainActivity while [AppLockStore.locked] is true —
 * incoming-call surfaces live in their own activity and bypass it.
 */
@Composable
fun AppLockOverlay() {
    val context = LocalContext.current
    var entered by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    fun submit(code: String) {
        if (AppLockStore.verify(code)) {
            AppLockStore.unlock()
        } else {
            wrong = true
            entered = ""
        }
    }

    fun tryBiometric() {
        val activity = context as? Activity ?: return
        if (!AppLockStore.biometricEnabled || Build.VERSION.SDK_INT < 28) return
        showBiometricPrompt(activity) { AppLockStore.unlock() }
    }

    // Offer biometrics right away when enabled.
    LaunchedEffect(Unit) { tryBiometric() }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(R.drawable.ic_line_lock),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.applock_enter_passcode),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (wrong) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.applock_wrong_passcode),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(22.dp))

            // PIN dots
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(6) { index ->
                    val filled = index < entered.length
                    Box(
                        Modifier
                            .size(14.dp)
                            .background(
                                if (filled) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                                CircleShape,
                            ),
                    )
                }
            }
            Spacer(Modifier.height(28.dp))

            // Keypad
            val rows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("bio", "0", "del"),
            )
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    row.forEach { keyValue ->
                        KeypadKey(
                            key = keyValue,
                            biometricAvailable = AppLockStore.biometricEnabled && Build.VERSION.SDK_INT >= 28,
                            onDigit = { digit ->
                                if (entered.length < 6) {
                                    wrong = false
                                    entered += digit
                                    if (entered.length >= 4 && AppLockStore.verify(entered)) {
                                        AppLockStore.unlock()
                                    } else if (entered.length == 6) {
                                        submit(entered)
                                    }
                                }
                            },
                            onDelete = { entered = entered.dropLast(1) },
                            onBiometric = { tryBiometric() },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            TextButton(onClick = { if (entered.length in 4..6) submit(entered) }) {
                Text(stringResource(R.string.applock_unlock))
            }
        }
    }
}

@Composable
private fun KeypadKey(
    key: String,
    biometricAvailable: Boolean,
    onDigit: (String) -> Unit,
    onDelete: () -> Unit,
    onBiometric: () -> Unit,
) {
    val isDigit = key.length == 1 && key[0].isDigit()
    Box(
        Modifier
            .size(72.dp)
            .background(
                if (isDigit) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background,
                CircleShape,
            )
            .clickable(enabled = isDigit || key == "del" || (key == "bio" && biometricAvailable)) {
                when {
                    isDigit -> onDigit(key)
                    key == "del" -> onDelete()
                    key == "bio" -> onBiometric()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            isDigit -> Text(key, fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurface)
            key == "del" -> Icon(
                painter = painterResource(KlicIcons.close),
                contentDescription = stringResource(R.string.common_delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            key == "bio" && biometricAvailable -> Icon(
                painter = painterResource(R.drawable.ic_line_lock),
                contentDescription = stringResource(R.string.applock_biometric_unlock),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Framework biometric prompt (API 28+) — avoids forcing a FragmentActivity base. */
fun showBiometricPrompt(activity: Activity, onSuccess: () -> Unit) {
    if (Build.VERSION.SDK_INT < 28) return
    runCatching {
        val prompt = android.hardware.biometrics.BiometricPrompt.Builder(activity)
            .setTitle(activity.getString(R.string.applock_biometric_title))
            .setNegativeButton(
                activity.getString(R.string.applock_use_passcode),
                activity.mainExecutor,
            ) { _, _ -> }
            .build()
        prompt.authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?,
                ) {
                    onSuccess()
                }
            },
        )
    }
}
