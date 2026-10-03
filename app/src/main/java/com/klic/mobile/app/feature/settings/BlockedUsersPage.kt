package com.klic.mobile.app.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.R
import com.klic.mobile.app.feature.KlicViewModel
import com.klic.mobile.app.ui.components.AvatarView
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun BlockedUsersContent(vm: KlicViewModel) {
    val blocked by vm.blockedUsers.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadBlocks() }

    if (blocked.isEmpty()) {
        Text(
            stringResource(R.string.privacy_no_blocked_users),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        return
    }
    SettingsCard {
        blocked.forEachIndexed { index, row ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarView(url = row.user.avatarUrl, name = row.user.displayName, size = 42.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(row.user.displayName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text("@${row.user.username}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(
                    onClick = { vm.unblockUser(row.user.id) },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) { Text(stringResource(R.string.privacy_unblock)) }
            }
            if (index != blocked.lastIndex) RowDivider()
        }
    }
}

// ─────────────────────────────────────────────────────────
// Passcode & biometrics
// ─────────────────────────────────────────────────────────
