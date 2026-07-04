package com.klic.mobile.app.feature.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
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
 * Login page — a standalone destination (rather than a toggle-mode form) with its own
 * circle-container backdrop. Wiring (vm.login, passkey sign-in, error surfacing) matches the
 * previous combined AuthScreen; this is a visual restructure to mirror the iOS LoginView.
 */
@Composable
fun LoginScreen(vm: KlicViewModel, onCreateAccount: () -> Unit) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val error by vm.error.collectAsState()
    val focusManager = LocalFocusManager.current
    val activityContext = LocalContext.current

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var passkeyBusy by remember { mutableStateOf(false) }

    LaunchedEffect(error) {
        if (error != null) {
            isSubmitting = false
            passkeyBusy = false
        }
    }

    AuthScaffold(
        artwork = R.drawable.auth_art_login,
        tipFraction = 0.47f,
        modifier = Modifier.clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() },
        ) { focusManager.clearFocus() },
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
        ) {
            Text(
                stringResource(R.string.login_title),
                fontFamily = TikTokSansExpanded,
                fontWeight = FontWeight.Normal,
                fontSize = 30.sp,
                color = AuthStyle.titleColor(isDark),
            )

            Text(
                stringResource(R.string.login_subtitle),
                fontFamily = TikTokSans,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )

            androidx.compose.foundation.layout.Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 28.dp),
            ) {
                AuthTextField(
                    value = username,
                    onValueChange = { username = it },
                    placeholder = stringResource(R.string.auth_username),
                    prefix = "@",
                    imeAction = ImeAction.Next,
                )
                AuthTextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = stringResource(R.string.auth_password),
                    isSecure = true,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                    onImeAction = {
                        if (username.isNotBlank() && password.isNotBlank() && !isSubmitting) {
                            isSubmitting = true
                            vm.error.value = null
                            vm.login(username, password)
                        }
                    },
                )
            }

            PillButton(
                text = stringResource(R.string.login_title),
                fill = AuthStyle.ctaRed,
                fontFamily = TikTokSansExpanded,
                fontWeight = FontWeight.Medium,
                isLoading = isSubmitting,
                enabled = !isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
            ) {
                isSubmitting = true
                vm.error.value = null
                vm.login(username, password)
            }

            Row(
                modifier = Modifier
                    .padding(top = 16.dp)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        enabled = !passkeyBusy,
                    ) {
                        passkeyBusy = true
                        vm.error.value = null
                        vm.loginWithPasskey(activityContext)
                    },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (passkeyBusy) stringResource(R.string.auth_waiting_for_passkey) else stringResource(R.string.auth_sign_in_with_passkey),
                    fontFamily = TikTokSans,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = AuthStyle.smallText,
                )
            }

            error?.let {
                Text(
                    it,
                    fontFamily = TikTokSans,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            Text(
                stringResource(R.string.auth_create_account),
                fontFamily = TikTokSans,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = AuthStyle.smallText,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .padding(top = 18.dp)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onCreateAccount,
                    ),
            )
        }
    }
}
