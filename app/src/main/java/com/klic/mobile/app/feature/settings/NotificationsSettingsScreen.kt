package com.klic.mobile.app.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.feature.KlicViewModel

/**
 * Settings → global "Notifications" page (§8.5): four enable toggles synced with
 * GET/PUT /me/notification-prefs, plus "Reset notification settings" (DELETE + local
 * tone/pref reset). The local notification path honors these too.
 */
@Composable
fun NotificationsSettingsContent(vm: KlicViewModel) {
    val prefs by vm.notificationPrefs.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.loadNotificationPrefs() }

    SettingsCard {
        NotificationToggleRow(
            title = "Message notifications",
            subtitle = "Direct messages from friends",
            checked = prefs.messages,
        ) { vm.updateNotificationPrefs(messages = it) }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        NotificationToggleRow(
            title = "Group notifications",
            subtitle = "Messages in group chats",
            checked = prefs.groups,
        ) { vm.updateNotificationPrefs(groups = it) }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        NotificationToggleRow(
            title = "Call notifications",
            subtitle = "Incoming voice and video calls",
            checked = prefs.calls,
        ) { vm.updateNotificationPrefs(calls = it) }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        NotificationToggleRow(
            title = "Friend-request notifications",
            subtitle = "New friend requests",
            checked = prefs.friendRequests,
        ) { vm.updateNotificationPrefs(friendRequests = it) }
    }

    Spacer(Modifier.height(24.dp))
    Button(
        onClick = { confirmReset = true },
        modifier = Modifier.fillMaxWidth(),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.error,
        ),
    ) { Text("Reset notification settings", modifier = Modifier.padding(vertical = 6.dp)) }
    Spacer(Modifier.height(10.dp))
    Text(
        "Turns everything back on and removes custom per-chat tones.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset notification settings?") },
            text = { Text("All notification toggles return to on, and per-chat alert tones and ringtones go back to default.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    vm.resetNotificationSettings()
                }) { Text("Reset", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NotificationToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
