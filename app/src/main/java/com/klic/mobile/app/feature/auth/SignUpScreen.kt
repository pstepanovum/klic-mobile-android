package com.klic.mobile.app.feature.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.klic.mobile.app.ui.components.KlicCheckbox
import com.klic.mobile.app.ui.components.PillButton
import com.klic.mobile.app.ui.theme.TikTokSans
import com.klic.mobile.app.ui.theme.TikTokSansExpanded

/**
 * Sign Up page — same circle-container language as Login, but the circle sits higher since
 * there's more content: username, display name, password (+ strength meter), and the
 * privacy-policy checkbox that gates the submit button. Mirrors the iOS SignUpView.
 */
@Composable
fun SignUpScreen(vm: KlicViewModel, onHaveAccount: () -> Unit) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val error by vm.error.collectAsState()
    val focusManager = LocalFocusManager.current

    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var agreedToPrivacy by remember { mutableStateOf(false) }
    var showPrivacyPolicy by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }

    LaunchedEffect(error) {
        if (error != null) isSubmitting = false
    }

    if (showPrivacyPolicy) {
        PrivacyPolicyScreen(onBack = { showPrivacyPolicy = false })
        return
    }

    AuthScaffold(
        artwork = R.drawable.auth_art_signup,
        tipFraction = 0.34f,
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
                stringResource(R.string.signup_title),
                fontFamily = TikTokSansExpanded,
                fontWeight = FontWeight.Normal,
                fontSize = 30.sp,
                color = AuthStyle.titleColor(isDark),
            )

            Text(
                stringResource(R.string.signup_subtitle),
                fontFamily = TikTokSans,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
            ) {
                AuthTextField(
                    value = username,
                    onValueChange = { username = it },
                    placeholder = stringResource(R.string.auth_username),
                    prefix = "@",
                    imeAction = ImeAction.Next,
                )
                AuthTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    placeholder = stringResource(R.string.auth_display_name),
                    imeAction = ImeAction.Next,
                )
                AuthTextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = stringResource(R.string.auth_password),
                    isSecure = true,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                )

                AnimatedVisibility(visible = password.isNotEmpty()) {
                    PasswordStrengthBar(password = password)
                }
            }

            KlicCheckbox(
                checked = agreedToPrivacy,
                onCheckedChange = { agreedToPrivacy = it },
                onPrivacyTap = { showPrivacyPolicy = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            )

            PillButton(
                text = stringResource(R.string.auth_sign_up),
                fill = if (agreedToPrivacy) AuthStyle.ctaRed else Color(0xFFB2B2B2),
                fontFamily = TikTokSansExpanded,
                fontWeight = FontWeight.Medium,
                isLoading = isSubmitting,
                enabled = agreedToPrivacy && !isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp),
            ) {
                isSubmitting = true
                vm.error.value = null
                vm.register(username, password, displayName)
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
                stringResource(R.string.auth_have_account),
                fontFamily = TikTokSans,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = AuthStyle.smallText,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onHaveAccount,
                    ),
            )
        }
    }
}

private data class Strength(val bars: Int, val labelRes: Int, val color: Color)

private fun passwordStrength(password: String): Strength {
    if (password.isEmpty()) return Strength(0, 0, Color.Transparent)
    val hasUpper = password.any { it.isUpperCase() }
    val hasDigit = password.any { it.isDigit() }
    val hasSpecial = password.any { !it.isLetterOrDigit() }
    return when {
        password.length < 8 -> Strength(1, R.string.auth_pw_weak, Color(0xFFEF5350))
        !hasUpper && !hasDigit -> Strength(2, R.string.auth_pw_fair, Color(0xFFFF8C00))
        hasUpper && hasDigit && hasSpecial -> Strength(4, R.string.auth_pw_strong, Color(0xFF2ECC71))
        else -> Strength(3, R.string.auth_pw_good, Color(0xFF8BC34A))
    }
}

@Composable
private fun PasswordStrengthBar(password: String, modifier: Modifier = Modifier) {
    val strength = passwordStrength(password)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(4) { i ->
            val active = i < strength.bars
            val color by animateColorAsState(
                targetValue = if (active) strength.color else MaterialTheme.colorScheme.surfaceVariant,
                animationSpec = tween(250),
                label = "bar$i",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .background(color, CircleShape),
            )
        }
        Text(
            if (strength.labelRes != 0) stringResource(strength.labelRes) else "",
            fontFamily = TikTokSans,
            fontSize = 12.sp,
            color = strength.color,
            modifier = Modifier.width(44.dp),
            textAlign = TextAlign.End,
        )
    }
}
