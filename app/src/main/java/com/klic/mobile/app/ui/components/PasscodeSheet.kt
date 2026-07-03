package com.klic.mobile.app.ui.components

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.klic.mobile.app.R
import com.klic.mobile.app.ui.theme.KlicIcons

// ─────────────────────────────────────────────────────────
// Shared passcode building blocks (§11.3): dots + keypad + sheet card
// ─────────────────────────────────────────────────────────

/** Row of six PIN dots, [filled] of them highlighted. */
@Composable
fun PasscodeDots(filled: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(6) { index ->
            Box(
                Modifier
                    .size(14.dp)
                    .background(
                        if (index < filled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape,
                    ),
            )
        }
    }
}

/** Rounded Klic keypad: 1-9, biometric (optional), 0, delete. */
@Composable
fun PasscodeKeypad(
    showBiometric: Boolean,
    onDigit: (String) -> Unit,
    onDelete: () -> Unit,
    onBiometric: () -> Unit = {},
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("bio", "0", "del"),
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEachIndexed { index, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                row.forEach { key ->
                    PasscodeKey(
                        key = key,
                        biometricAvailable = showBiometric,
                        onDigit = onDigit,
                        onDelete = onDelete,
                        onBiometric = onBiometric,
                    )
                }
            }
            if (index != rows.lastIndex) Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun PasscodeKey(
    key: String,
    biometricAvailable: Boolean,
    onDigit: (String) -> Unit,
    onDelete: () -> Unit,
    onBiometric: () -> Unit,
) {
    val isDigit = key.length == 1 && key[0].isDigit()
    Box(
        Modifier
            .size(68.dp)
            .background(
                if (isDigit) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
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
            isDigit -> Text(key, fontSize = 25.sp, color = MaterialTheme.colorScheme.onSurface)
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

/** Rounded-top Klic sheet card hosting passcode content, pinned to the bottom edge. */
@Composable
fun PasscodeSheetCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.background,
                RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            )
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(top = 14.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Drag-handle affordance (visual only — the lock sheet is not dismissable).
        Box(
            Modifier
                .width(36.dp)
                .height(4.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
        )
        Spacer(Modifier.height(18.dp))
        content()
    }
}

// ─────────────────────────────────────────────────────────
// Set / change passcode sheet (§11.3)
// ─────────────────────────────────────────────────────────

/**
 * Klic-styled set/change-passcode flow: keypad entry, then repeat-to-confirm — a
 * bottom sheet card over FULLY blurred content (blur-behind on Android 12+, a
 * near-opaque dim on older devices so the content stays unreadable either way).
 */
@Composable
fun SetPasscodeSheet(onSet: (String) -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    var mismatch by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BlurBehindDialogWindow()
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            PasscodeSheetCard {
                Icon(
                    painter = painterResource(R.drawable.ic_line_lock),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    if (confirming) stringResource(R.string.applock_repeat_passcode)
                    else stringResource(R.string.applock_choose_passcode),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        mismatch -> stringResource(R.string.applock_passcodes_dont_match)
                        else -> stringResource(R.string.applock_passcode_hint)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (mismatch) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(18.dp))
                PasscodeDots(filled = if (confirming) second.length else first.length)
                Spacer(Modifier.height(22.dp))
                PasscodeKeypad(
                    showBiometric = false,
                    onDigit = { digit ->
                        mismatch = false
                        if (!confirming) {
                            if (first.length < 6) first += digit
                        } else if (second.length < 6) {
                            second += digit
                            // Auto-confirm once the repeat reaches the first entry's length.
                            if (second.length == first.length) {
                                if (second == first) onSet(first)
                                else {
                                    mismatch = true
                                    second = ""
                                }
                            }
                        }
                    },
                    onDelete = {
                        mismatch = false
                        if (confirming) second = second.dropLast(1) else first = first.dropLast(1)
                    },
                )
                Spacer(Modifier.height(18.dp))
                if (!confirming) {
                    Button(
                        onClick = { confirming = true },
                        enabled = first.length in 4..6,
                        modifier = Modifier.fillMaxWidth(),
                        shape = CircleShape,
                    ) { Text(stringResource(R.string.common_next), Modifier.padding(vertical = 6.dp)) }
                    Spacer(Modifier.height(8.dp))
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) { Text(stringResource(R.string.common_cancel), Modifier.padding(vertical = 6.dp)) }
            }
        }
    }
}

/**
 * §11.3 privacy: makes the content behind a passcode dialog unreadable — real
 * window blur on Android 12+, and a near-opaque dim on everything older.
 */
@Composable
fun BlurBehindDialogWindow() {
    val view = LocalView.current
    LaunchedEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= 31) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = 90 }
            window.setDimAmount(0.35f)
        } else {
            window.setDimAmount(0.94f)
        }
    }
}
