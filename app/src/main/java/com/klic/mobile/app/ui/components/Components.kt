package com.klic.mobile.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.klic.mobile.app.ui.theme.KlicIcons
import androidx.compose.ui.res.stringResource
import com.klic.mobile.app.R

/** Fully-rounded flat primary button — no shadow, no border (design rules). */
@Composable
fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    fill: Color = Color.Unspecified,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    fontFamily: androidx.compose.ui.text.font.FontFamily? = null,
    fontWeight: androidx.compose.ui.text.font.FontWeight? = null,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    onClick: () -> Unit,
) {
    val resolvedFill = if (fill == Color.Unspecified) MaterialTheme.colorScheme.primary else fill
    Button(
        onClick = onClick,
        enabled = enabled && !isLoading,
        modifier = modifier.fillMaxWidth(),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = resolvedFill,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = resolvedFill,
            disabledContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text(
                text,
                modifier = Modifier
                    .padding(vertical = 6.dp)
                    .alpha(if (isLoading) 0f else 1f),
                fontFamily = fontFamily,
                fontWeight = fontWeight,
                fontSize = fontSize,
            )
            if (isLoading) {
                LoadingCircle(size = 18.dp, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

/** Capsule search field with leading search icon and optional clear button. */
@Composable
fun KlicSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
        singleLine = true,
        leadingIcon = {
            Icon(
                painter = painterResource(KlicIcons.search),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        },
        trailingIcon = if (value.isNotEmpty()) ({
            IconButton(onClick = { onValueChange("") }) {
                Icon(
                    painter = painterResource(KlicIcons.close),
                    contentDescription = "Clear",
                    modifier = Modifier.size(16.dp),
                )
            }
        }) else null,
        shape = CircleShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor   = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedBorderColor      = Color.Transparent,
            unfocusedBorderColor    = Color.Transparent,
            focusedTextColor        = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor      = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

/** Flat capsule text field with no visible outline. */
@Composable
fun KlicTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder) },
        singleLine = true,
        shape = CircleShape,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor   = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedBorderColor      = Color.Transparent,
            unfocusedBorderColor    = Color.Transparent,
            focusedTextColor        = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor      = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

/** Checkbox with "I agree to the Privacy Policy" label. Solid fill, no stroke: unchecked it
 *  sits as a muted filled square matching the auth capsule inputs; checked it goes brand red. */
@Composable
fun KlicCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onPrivacyTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val primary = MaterialTheme.colorScheme.primary
        val onPrimary = MaterialTheme.colorScheme.onPrimary
        val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        val uncheckedFill = if (isDark) Color(0xFF202020) else Color(0xFFF2F2F2)
        val mutedText = Color(0xFFB2B2B2)

        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (checked) primary else uncheckedFill)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { onCheckedChange(!checked) },
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                androidx.compose.foundation.Canvas(modifier = Modifier.size(13.dp)) {
                    drawPath(
                        path = Path().apply {
                            moveTo(size.width * 0.15f, size.height * 0.52f)
                            lineTo(size.width * 0.42f, size.height * 0.78f)
                            lineTo(size.width * 0.85f, size.height * 0.22f)
                        },
                        color = onPrimary,
                        style = Stroke(
                            width = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                stringResource(R.string.checkbox_agree_prefix),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp),
                color = mutedText,
            )
            Text(
                stringResource(R.string.checkbox_privacy_policy),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp),
                color = mutedText,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { onPrivacyTap() },
            )
        }
    }
}

/** Circular in-call control using a brand Painter icon. */
@Composable
fun CircleControl(
    painter: Painter,
    contentDescription: String,
    fill: Color = Color.Unspecified,
    tint: Color = Color.Unspecified,
    diameter: Int = 64,
    onClick: () -> Unit,
) {
    val resolvedFill = if (fill == Color.Unspecified) MaterialTheme.colorScheme.surfaceVariant else fill
    val resolvedTint = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(diameter.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = resolvedFill,
            contentColor   = resolvedTint,
        ),
    ) {
        Box {
            Icon(
                painter = painter,
                contentDescription = contentDescription,
                modifier = Modifier.size((diameter * 0.44f).dp),
            )
        }
    }
}
