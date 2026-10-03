package com.klic.mobile.app.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.R
import com.klic.mobile.app.feature.KlicViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun PasskeysContent(vm: KlicViewModel) {
    val context = LocalContext.current
    val passkeys by vm.passkeyList.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadPasskeys() }

    SettingsCard {
        if (passkeys.isEmpty()) {
            Text(
                stringResource(R.string.passkeys_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 14.dp),
            )
        }
        passkeys.forEachIndexed { index, key ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        key.label ?: stringResource(R.string.passkeys_default_label),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    key.createdAt?.let {
                        Text(
                            stringResource(R.string.passkeys_added_format, it.take(10)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = { vm.deletePasskey(key.id) }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            }
            if (index != passkeys.lastIndex) RowDivider()
        }
    }
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { vm.addPasskey(context) },
        modifier = Modifier.fillMaxWidth(),
        shape = CircleShape,
    ) { Text(stringResource(R.string.passkeys_add), modifier = Modifier.padding(vertical = 6.dp)) }
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.passkeys_footer),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
