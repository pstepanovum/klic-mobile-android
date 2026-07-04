package com.klic.mobile.app.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.klic.mobile.app.R
import com.klic.mobile.app.ui.theme.TikTokSans
import androidx.compose.foundation.text.BasicTextField

/**
 * Fully-rounded capsule input used on the Login / Sign Up pages. Distinct from the generic
 * [com.klic.mobile.app.ui.components.KlicTextField] — this one matches the design mock's
 * specific fill/hint colors and fixed ~52dp height, with an optional fixed leading glyph
 * (the "@" on the username field) and a themed reveal toggle for password fields.
 */
@Composable
fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    prefix: String? = null,
    isSecure: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: (() -> Unit)? = null,
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    var revealSecure by rememberSaveable { mutableStateOf(false) }
    val textColor = MaterialTheme.colorScheme.onBackground
    val keyboardController = LocalSoftwareKeyboardController.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(AuthStyle.fieldFill(isDark), CircleShape)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (prefix != null) {
            Text(
                prefix,
                fontFamily = TikTokSans,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                fontSize = 16.sp,
                color = AuthStyle.fieldHint,
            )
        }

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    fontFamily = TikTokSans,
                    fontSize = 14.sp,
                    color = AuthStyle.fieldHint,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = TextStyle(
                    fontFamily = TikTokSans,
                    fontSize = 16.sp,
                    color = textColor,
                ),
                cursorBrush = SolidColor(AuthStyle.ctaRed),
                visualTransformation = if (isSecure && !revealSecure) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (isSecure) KeyboardType.Password else keyboardType,
                    imeAction = imeAction,
                    autoCorrect = false,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { onImeAction?.invoke() },
                    onDone = {
                        onImeAction?.invoke()
                        keyboardController?.hide()
                    },
                ),
            )
        }

        if (isSecure) {
            IconButton(
                onClick = { revealSecure = !revealSecure },
                modifier = Modifier.size(28.dp),
            ) {
                androidx.compose.material3.Icon(
                    painter = painterResource(if (revealSecure) R.drawable.ic_eye_closed else R.drawable.ic_eye_open),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    // The icons are authored in the light-theme ink (#282930); on the dark
                    // field fill that ink disappears, so retint to the hint gray there.
                    tint = if (isDark) AuthStyle.fieldHint else Color.Unspecified,
                )
            }
        }
    }
}
