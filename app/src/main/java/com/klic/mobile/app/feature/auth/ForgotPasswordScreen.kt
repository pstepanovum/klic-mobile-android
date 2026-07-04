package com.klic.mobile.app.feature.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.klic.mobile.app.R
import com.klic.mobile.app.feature.KlicViewModel
import com.klic.mobile.app.ui.components.PillButton
import com.klic.mobile.app.ui.theme.TikTokSans
import com.klic.mobile.app.ui.theme.TikTokSansExpanded

/**
 * §18.2 forgot-password page. The user enters their recovery email; we fire Firebase's
 * hosted password-reset email and always show a uniform "check your email" confirmation
 * (never revealing whether the address is on file). After resetting in Firebase's page
 * they log in with the new password — the server's sync-back re-hashes it into Postgres.
 */
@Composable
fun ForgotPasswordScreen(vm: KlicViewModel, onBack: () -> Unit) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val focusManager = LocalFocusManager.current
    val busy by vm.recoveryBusy.collectAsState()

    var email by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }

    AuthScaffold(
        artwork = R.drawable.auth_art_login,
        tipFraction = 0.47f,
        modifier = Modifier.clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() },
        ) { focusManager.clearFocus() },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
        ) {
            Text(
                stringResource(R.string.forgot_password_title),
                fontFamily = TikTokSansExpanded,
                fontWeight = FontWeight.Normal,
                fontSize = 28.sp,
                color = AuthStyle.titleColor(isDark),
                textAlign = TextAlign.Center,
            )

            Text(
                if (sent) stringResource(R.string.forgot_password_sent)
                else stringResource(R.string.forgot_password_subtitle),
                fontFamily = TikTokSans,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )

            if (!sent) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 28.dp),
                ) {
                    AuthTextField(
                        value = email,
                        onValueChange = { email = it },
                        placeholder = stringResource(R.string.recovery_email_placeholder),
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Done,
                    )
                }

                PillButton(
                    text = stringResource(R.string.forgot_password_send),
                    fill = AuthStyle.ctaRed,
                    fontFamily = TikTokSansExpanded,
                    fontWeight = FontWeight.Medium,
                    isLoading = busy,
                    enabled = !busy && email.contains("@"),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp),
                ) {
                    focusManager.clearFocus()
                    vm.sendPasswordReset(email) { sent = true }
                }
            }

            Text(
                stringResource(R.string.forgot_password_back_to_login),
                fontFamily = TikTokSans,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = AuthStyle.smallText,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .padding(top = 20.dp)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onBack,
                    ),
            )
        }
    }
}
