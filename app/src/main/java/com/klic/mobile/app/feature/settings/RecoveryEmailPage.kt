package com.klic.mobile.app.feature.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.R
import com.klic.mobile.app.feature.KlicViewModel
import com.klic.mobile.app.ui.components.KlicTextField
import com.klic.mobile.app.ui.components.PillButton
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Recovery email (§18.2): shows current state or an add flow; polls verification. */
@Composable
fun RecoveryEmailContent(vm: KlicViewModel) {
    val context = LocalContext.current
    val me by vm.currentUser.collectAsStateWithLifecycle()
    val status by vm.emailStatus.collectAsStateWithLifecycle()

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
