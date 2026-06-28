package com.klicmobile.app.feature.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.klicmobile.app.R
import com.klicmobile.app.feature.KlicViewModel
import com.klicmobile.app.ui.components.KlicTextField
import com.klicmobile.app.ui.components.PillButton

@Composable
fun AuthScreen(vm: KlicViewModel) {
    var isRegistering by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val error by vm.error.collectAsState()
    val focusManager = LocalFocusManager.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { focusManager.clearFocus() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_klic_logo),
                contentDescription = "Klic",
                modifier = Modifier.width(88.dp),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
            )

            Text(
                if (isRegistering) "Create your account" else "Welcome back",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )

            Spacer(Modifier.height(28.dp))

            KlicTextField(username, { username = it }, "Username")
            if (isRegistering) {
                Spacer(Modifier.height(12.dp))
                KlicTextField(displayName, { displayName = it }, "Display name")
            }
            Spacer(Modifier.height(12.dp))
            KlicTextField(password, { password = it }, "Password", isPassword = true)

            AnimatedVisibility(visible = isRegistering && password.isNotEmpty()) {
                PasswordStrengthBar(password = password, modifier = Modifier.padding(top = 8.dp))
            }

            Spacer(Modifier.height(20.dp))

            PillButton(if (isRegistering) "Sign up" else "Log in") {
                if (isRegistering) vm.register(username, password, displayName)
                else vm.login(username, password)
            }

            TextButton(onClick = { isRegistering = !isRegistering }) {
                Text(
                    if (isRegistering) "I already have an account" else "Create an account",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

private data class StrengthLevel(val bars: Int, val label: String, val color: Color)

private fun passwordStrength(password: String): StrengthLevel {
    if (password.isEmpty()) return StrengthLevel(0, "", Color.Transparent)
    val hasUpper   = password.any { it.isUpperCase() }
    val hasDigit   = password.any { it.isDigit() }
    val hasSpecial = password.any { !it.isLetterOrDigit() }
    return when {
        password.length < 8            -> StrengthLevel(1, "Weak",   Color(0xFFEF5350))
        !hasUpper && !hasDigit         -> StrengthLevel(2, "Fair",   Color(0xFFFF8C00))
        hasUpper && hasDigit && hasSpecial -> StrengthLevel(4, "Strong", Color(0xFF2ECC71))
        else                           -> StrengthLevel(3, "Good",   Color(0xFF8BC34A))
    }
}

@Composable
private fun PasswordStrengthBar(password: String, modifier: Modifier = Modifier) {
    val strength = passwordStrength(password)
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(4) { i ->
            val active = i < strength.bars
            val color by animateColorAsState(
                targetValue = if (active) strength.color else Color.Gray.copy(alpha = 0.25f),
                animationSpec = tween(250),
                label = "bar$i",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
        Text(
            strength.label,
            style = MaterialTheme.typography.labelSmall,
            color = strength.color,
            modifier = Modifier.width(40.dp),
            textAlign = TextAlign.End,
        )
    }
}
