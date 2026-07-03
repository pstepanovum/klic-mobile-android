package com.klic.mobile.app.ui.components

import android.app.Activity
import android.os.Build
import android.os.CancellationSignal
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.klic.mobile.app.R
import com.klic.mobile.app.data.AppLockStore

/**
 * §11.3 app-lock unlock surface: a Klic-styled bottom sheet card (PIN dots + rounded
 * keypad, biometric shortcut) over the app content, which MainActivity FULLY blurs
 * while locked (Modifier.blur on 12+; this overlay's scrim goes opaque on older
 * devices so the content is unreadable either way). Rendered above ALL app content
 * while [AppLockStore.locked] is true — incoming-call surfaces live in their own
 * activity and bypass it.
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

    // Pre-12 devices can't blur the content behind — go opaque instead (privacy).
    val scrim =
        if (Build.VERSION.SDK_INT >= 31) MaterialTheme.colorScheme.background.copy(alpha = 0.35f)
        else MaterialTheme.colorScheme.background

    Box(
        Modifier
            .fillMaxSize()
            .background(scrim)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {},
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        PasscodeSheetCard {
            Icon(
                painter = painterResource(R.drawable.ic_line_lock),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(38.dp),
            )
            Spacer(Modifier.height(12.dp))
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
            Spacer(Modifier.height(20.dp))
            PasscodeDots(filled = entered.length)
            Spacer(Modifier.height(24.dp))
            PasscodeKeypad(
                showBiometric = AppLockStore.biometricEnabled && Build.VERSION.SDK_INT >= 28,
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
            Spacer(Modifier.height(10.dp))
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
