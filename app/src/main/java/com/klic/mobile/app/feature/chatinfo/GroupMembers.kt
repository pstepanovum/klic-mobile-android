package com.klic.mobile.app.feature.chatinfo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.data.Member
import com.klic.mobile.app.ui.components.AvatarView
import androidx.compose.ui.res.stringResource
import com.klic.mobile.app.R

@Composable
internal fun MemberRow(
    name: String,
    username: String,
    avatarUrl: String?,
    isAdmin: Boolean = false,
    about: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarView(url = avatarUrl, name = name, size = 40.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text("@$username", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // §11.5: the member's About/status line, when their visibility allows it.
            about?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isAdmin) {
            Text(
                stringResource(R.string.group_admin),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/** §14.3: member profile sheet — works for NON-FRIENDS too: avatar, display name,
 *  @username, About (per visibility) and "Add friend" when applicable; then the
 *  actions: admin-only "Transfer admin" (§14.3) + "Remove from group" (§9.3),
 *  and "Report" (§12.1) for everyone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemberActionSheet(
    member: Member,
    isCreator: Boolean,
    canRemove: Boolean,
    canTransfer: Boolean,
    isFriend: Boolean,
    onAddFriend: () -> Unit,
    onTransfer: () -> Unit,
    onRemove: () -> Unit,
    onReport: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Local "request sent" latch so a second tap can't double-send.
    var requestSent by remember(member.id) { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AvatarView(url = member.avatarUrl, name = member.displayName, size = 84.dp)
            Spacer(Modifier.height(10.dp))
            Text(
                member.displayName,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (isCreator) {
                    stringResource(R.string.group_member_username_admin, member.username)
                } else "@${member.username}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            // §11.5: About/status line, when the member's visibility allows it.
            member.about?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // §14.3: no phone numbers in Klic — the @username + Add friend stand in.
            if (!isFriend) {
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { requestSent = true; onAddFriend() },
                    enabled = !requestSent,
                    modifier = Modifier.fillMaxWidth(),
                    shape = CircleShape,
                ) {
                    Text(
                        if (requestSent) stringResource(R.string.group_member_request_sent)
                        else stringResource(R.string.group_member_add_friend),
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                    .padding(horizontal = 18.dp),
            ) {
                if (canTransfer) {
                    // §14.3: hand the group to this member (confirmed by the caller).
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = onTransfer).padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.group_transfer_admin),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    androidx.compose.material3.HorizontalDivider(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    )
                }
                // §12.1: report this member — the shared report sheet, user target.
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onReport).padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.report_user_row, member.displayName),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (canRemove) {
                    androidx.compose.material3.HorizontalDivider(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    )
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = onRemove).padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.group_remove_from_group),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) { Text(stringResource(R.string.common_cancel), modifier = Modifier.padding(vertical = 6.dp)) }
        }
    }
}
