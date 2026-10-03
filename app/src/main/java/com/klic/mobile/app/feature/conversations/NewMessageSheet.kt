package com.klic.mobile.app.feature.conversations

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.klic.mobile.app.data.Conversation
import com.klic.mobile.app.data.ImageUploads
import com.klic.mobile.app.data.User
import com.klic.mobile.app.feature.KlicViewModel
import com.klic.mobile.app.ui.components.AvatarView
import com.klic.mobile.app.ui.components.KlicSearchBar
import com.klic.mobile.app.ui.components.KlicTextField
import com.klic.mobile.app.ui.components.PillButton
import com.klic.mobile.app.ui.theme.KlicIcons
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.klic.mobile.app.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class NewMsgScreen { MAIN, NEW_GROUP_PICKER, NEW_GROUP_DETAILS, NEW_CONTACT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewMessageSheet(
    vm: KlicViewModel,
    onDismiss: () -> Unit,
    onOpenChat: (Conversation) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val friends by vm.friends.collectAsStateWithLifecycle()
    val friendStatus by vm.friendStatus.collectAsStateWithLifecycle()

    var screen by remember { mutableStateOf(NewMsgScreen.MAIN) }
    var searchText by remember { mutableStateOf("") }
    var permissionBannerDismissed by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var groupName by remember { mutableStateOf("") }
    // §11.5: the picked cover goes through the adjust step; we keep the cropped bitmap.
    var groupAvatarAdjustUri by remember { mutableStateOf<Uri?>(null) }
    var groupAvatarBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var contactUsername by remember { mutableStateOf("") }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hasContactsPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CONTACTS,
    ) == PackageManager.PERMISSION_GRANTED

    val avatarPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) groupAvatarAdjustUri = uri }

    // §11.5: pinch-zoom/drag in a rounded-square mask before the cover is attached.
    groupAvatarAdjustUri?.let { uri ->
        com.klic.mobile.app.ui.components.ImageAdjustSheet(
            uri = uri,
            mask = com.klic.mobile.app.ui.components.AdjustMask.ROUNDED_SQUARE,
            onDone = { bitmap ->
                groupAvatarBitmap = bitmap
                groupAvatarAdjustUri = null
            },
            onDismiss = { groupAvatarAdjustUri = null },
        )
    }

    LaunchedEffect(Unit) { vm.loadFriends() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Sheet header: close button + title
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = painterResource(KlicIcons.close),
                        contentDescription = "Close",
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text = when (screen) {
                        NewMsgScreen.MAIN -> stringResource(R.string.convos_new_message)
                        NewMsgScreen.NEW_GROUP_PICKER -> "${selectedIds.size} / 2,000,000 participants"
                        NewMsgScreen.NEW_GROUP_DETAILS -> stringResource(R.string.convos_group_details)
                        NewMsgScreen.NEW_CONTACT -> stringResource(R.string.convos_new_contact)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp),
                )
            }

            when (screen) {
                NewMsgScreen.MAIN -> {
                    val filteredFriends = if (searchText.isEmpty()) friends else friends.filter {
                        it.displayName.contains(searchText, ignoreCase = true) ||
                            it.username.contains(searchText, ignoreCase = true)
                    }
                    val grouped = filteredFriends
                        .sortedBy { it.displayName }
                        .groupBy { it.displayName.firstOrNull()?.uppercaseChar() ?: '#' }

                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        item {
                            KlicSearchBar(
                                value = searchText,
                                onValueChange = { searchText = it },
                                placeholder = stringResource(R.string.common_search),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        if (!hasContactsPermission && !permissionBannerDismissed) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp)
                                        .background(
                                            MaterialTheme.colorScheme.surface,
                                            RoundedCornerShape(18.dp),
                                        )
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            stringResource(R.string.convos_allow_contacts),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            stringResource(R.string.convos_allow_contacts_sub),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            stringResource(R.string.convos_allow_in_settings),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .padding(top = 4.dp)
                                                .clickable {
                                                    context.startActivity(
                                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                            data = Uri.fromParts("package", context.packageName, null)
                                                        },
                                                    )
                                                },
                                        )
                                    }
                                    IconButton(onClick = { permissionBannerDismissed = true }) {
                                        Icon(
                                            painter = painterResource(KlicIcons.close),
                                            contentDescription = "Dismiss",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        item {
                            NewMsgActionRow(
                                iconRes = KlicIcons.usersGroup,
                                label = stringResource(R.string.convos_new_group),
                                onClick = { screen = NewMsgScreen.NEW_GROUP_PICKER },
                            )
                        }
                        item {
                            NewMsgActionRow(
                                iconRes = KlicIcons.userLine,
                                label = stringResource(R.string.convos_new_contact),
                                onClick = { screen = NewMsgScreen.NEW_CONTACT },
                            )
                        }
                        item {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            )
                        }
                        grouped.forEach { (letter, group) ->
                            item(key = "letter:$letter", contentType = "letter") {
                                Text(
                                    letter.toString(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                )
                            }
                            items(group, key = { "friend:${it.id}" }, contentType = { "friend" }) { friend ->
                                FriendSheetRow(friend) {
                                    vm.openConversationWith(friend.id) { convo ->
                                        onOpenChat(convo)
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(32.dp)) }
                    }
                }

                NewMsgScreen.NEW_GROUP_PICKER -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        items(friends, key = { it.id }) { friend ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedIds = if (friend.id in selectedIds)
                                            selectedIds - friend.id
                                        else
                                            selectedIds + friend.id
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AvatarView(url = friend.avatarUrl, name = friend.displayName, size = 44.dp)
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 12.dp),
                                ) {
                                    Text(
                                        friend.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        "@${friend.username}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Checkbox(
                                    checked = friend.id in selectedIds,
                                    onCheckedChange = {
                                        selectedIds = if (friend.id in selectedIds)
                                            selectedIds - friend.id
                                        else
                                            selectedIds + friend.id
                                    },
                                )
                            }
                        }
                        item {
                            PillButton(
                                // A group can be created with no other members yet —
                                // participants can be added later from the group info page.
                                text = stringResource(R.string.common_next),
                                modifier = Modifier.padding(top = 16.dp, bottom = 32.dp),
                                onClick = { screen = NewMsgScreen.NEW_GROUP_DETAILS },
                            )
                        }
                    }
                }

                NewMsgScreen.NEW_GROUP_DETAILS -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.height(16.dp))
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                .clickable { avatarPickerLauncher.launch("image/*") },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (groupAvatarBitmap != null) {
                                AsyncImage(
                                    model = groupAvatarBitmap,
                                    contentDescription = "Group avatar",
                                    modifier = Modifier
                                        .size(80.dp)
                                        .clip(CircleShape),
                                )
                            } else {
                                Icon(
                                    painter = painterResource(KlicIcons.gallery),
                                    contentDescription = "Pick photo",
                                    modifier = Modifier.size(28.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        KlicTextField(
                            value = groupName,
                            onValueChange = { groupName = it },
                            placeholder = stringResource(R.string.convos_group_name),
                        )
                        Spacer(Modifier.height(16.dp))
                        PillButton(
                            text = stringResource(R.string.common_create),
                            enabled = groupName.isNotBlank(),
                            onClick = {
                                // Encode the adjusted cover (if any) so it uploads right after
                                // creation — previously the pick was silently dropped (§8.4).
                                scope.launch {
                                    val encoded = groupAvatarBitmap?.let { ImageUploads.encodeBitmap(it) }
                                    vm.createGroupConversation(
                                        groupName,
                                        selectedIds.toList(),
                                        avatarBytes = encoded?.bytes,
                                        avatarContentType = encoded?.contentType,
                                    ) { convo ->
                                        onOpenChat(convo)
                                    }
                                }
                            },
                        )
                        Spacer(Modifier.height(32.dp))
                    }
                }

                NewMsgScreen.NEW_CONTACT -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        Spacer(Modifier.height(8.dp))
                        KlicTextField(
                            value = contactUsername,
                            onValueChange = { contactUsername = it },
                            placeholder = stringResource(R.string.auth_username),
                        )
                        Spacer(Modifier.height(12.dp))
                        PillButton(
                            text = stringResource(R.string.friends_send_request),
                            onClick = { vm.addFriend(contactUsername) },
                        )
                        friendStatus?.let { status ->
                            Text(
                                status,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun NewMsgActionRow(iconRes: Int, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}

@Composable
private fun FriendSheetRow(friend: User, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarView(url = friend.avatarUrl, name = friend.displayName, size = 44.dp)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                friend.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "@${friend.username}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
