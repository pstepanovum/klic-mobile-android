package com.klic.mobile.app.feature.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.R
import com.klic.mobile.app.feature.KlicViewModel
import com.klic.mobile.app.ui.components.KlicTextField
import com.klic.mobile.app.ui.components.PillButton

/** Change password (§18.2): current + new + confirm → POST /auth/change-password. */
@Composable
fun ChangePasswordContent(vm: KlicViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var current by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val mismatch = confirm.isNotEmpty() && newPass != confirm
    val valid = current.isNotBlank() && newPass.length >= 6 && newPass == confirm

    SectionLabel(stringResource(R.string.privacy_change_password))
    SettingsCard {
        Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            KlicTextField(
                value = current,
                onValueChange = { current = it; errorText = null },
                placeholder = stringResource(R.string.change_password_current),
                isPassword = true,
            )
            KlicTextField(
                value = newPass,
                onValueChange = { newPass = it; errorText = null },
                placeholder = stringResource(R.string.change_password_new),
                isPassword = true,
            )
            KlicTextField(
                value = confirm,
                onValueChange = { confirm = it; errorText = null },
                placeholder = stringResource(R.string.change_password_confirm),
                isPassword = true,
            )
        }
    }

    if (mismatch) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.change_password_mismatch),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
    errorText?.let {
        Spacer(Modifier.height(8.dp))
        Text(
            it,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }

    Spacer(Modifier.height(16.dp))
    PillButton(
        text = stringResource(R.string.change_password_save),
        enabled = valid && !busy,
        isLoading = busy,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    ) {
        busy = true
        errorText = null
        vm.changePassword(current, newPass) { err ->
            busy = false
            if (err == null) {
                current = ""; newPass = ""; confirm = ""
                Toast.makeText(context, context.getString(R.string.change_password_success), Toast.LENGTH_SHORT).show()
            } else {
                errorText = err
            }
        }
    }
}
