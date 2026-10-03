package com.klic.mobile.app.feature.chatinfo

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.data.Conversation
import com.klic.mobile.app.data.ImageUploads
import com.klic.mobile.app.data.Member
import com.klic.mobile.app.feature.KlicViewModel
import com.klic.mobile.app.ui.components.AvatarView
import com.klic.mobile.app.ui.theme.KlicIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import com.klic.mobile.app.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The main Group Info column: header, actions, sections, members, footer (§9.3). */
@Composable
internal fun GroupInfoMain(
    vm: KlicViewModel,
    conversation: Conversation,
    title: String,
    meId: String?,
    meName: String?,
    meUsername: String?,
    meAvatarUrl: String?,
    onOpenSub: (ChatInfoSub) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val conversationId = conversation.id
    var memberSheet by remember { mutableStateOf<Member?>(null) }
    var removeTarget by remember { mutableStateOf<Member?>(null) }
    // §14.3: member picked "Transfer admin" — confirm before the POST.
    var transferTarget by remember { mutableStateOf<Member?>(null) }
    // §12.1: member picked "Report" on the action sheet.
    var reportTarget by remember { mutableStateOf<Member?>(null) }
    // §14.3: friends list powers the member sheet's "Add friend" affordance.
    val friends by vm.friends.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadFriends() }
    // §11.5: adjust step (pinch-zoom in a rounded-square mask) before the cover upload.
    var adjustCoverUri by remember { mutableStateOf<android.net.Uri?>(null) }

    // Group cover upload (§8.4) — server-side only the creator may edit.
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) adjustCoverUri = uri
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        // ── Cover, title, call actions ────────────────────────────────────────
        Column(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                AvatarView(url = conversation.avatarUrl, name = title, size = 110.dp)
                if (conversation.isAdmin) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(34.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .clickable { coverPicker.launch("image/*") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(KlicIcons.camera),
                            contentDescription = "Change group photo",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.group_members_count, conversation.members.size + 1),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            // Audio/Video — start the group call, or join the one already live.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                GroupCallButton(KlicIcons.callSolid, stringResource(R.string.action_audio)) {
                    if (vm.chatActiveCall.value?.conversationId == conversationId) {
                        vm.joinOngoingCall(conversationId)
                    } else {
                        vm.startCall(conversationId, "AUDIO", title)
                    }
                }
                GroupCallButton(KlicIcons.videoSolid, stringResource(R.string.action_video)) {
                    if (vm.chatActiveCall.value?.conversationId == conversationId) {
                        vm.joinOngoingCall(conversationId)
                    } else {
                        vm.startCall(conversationId, "VIDEO", title)
                    }
                }
            }
        }

        // ── Notifications ─────────────────────────────────────────────────────
        Spacer(Modifier.height(24.dp))
        ChatNotificationsCard(vm, conversationId, isGroup = true)

        // ── Media / starred / theme / encryption / storage ────────────────────
        Spacer(Modifier.height(16.dp))
        // §14.3: the shared group theme is admin-only; encryption info shows for all.
        ChatInfoSectionsCard(conversationId, showThemeRow = conversation.isAdmin) { onOpenSub(it) }

        // ── Members ───────────────────────────────────────────────────────────
        Spacer(Modifier.height(16.dp))
        InfoSectionLabel(stringResource(R.string.group_members_label))
        InfoCard {
            if (meName != null) {
                MemberRow(
                    name = "$meName (you)",
                    username = meUsername.orEmpty(),
                    avatarUrl = meAvatarUrl,
                    isAdmin = conversation.createdById == meId,
                )
                if (conversation.members.isNotEmpty()) InfoDivider()
            }
            conversation.members.forEachIndexed { index, member ->
                MemberRow(
                    name = member.displayName,
                    username = member.username,
                    avatarUrl = member.avatarUrl,
                    isAdmin = conversation.createdById == member.id,
                    about = member.about,
                    onClick = { memberSheet = member },
                )
                if (index != conversation.members.lastIndex) InfoDivider()
            }
        }

        // ── Footer: created by / created at ───────────────────────────────────
        Spacer(Modifier.height(20.dp))
        val creator = conversation.createdById?.let { id ->
            if (id == meId) "you" else conversation.members.firstOrNull { it.id == id }?.displayName
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            creator?.let {
                Text(
                    stringResource(R.string.group_created_by, it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            conversation.createdAt?.let {
                Text(
                    stringResource(R.string.group_created_on, shortDate(it)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(28.dp))
    }

    // §11.5: crop the picked cover to a square via the adjust sheet, then upload
    // through the EXISTING presign/PUT/PATCH chain (§10.1 step errors intact).
    adjustCoverUri?.let { uri ->
        com.klic.mobile.app.ui.components.ImageAdjustSheet(
            uri = uri,
            mask = com.klic.mobile.app.ui.components.AdjustMask.ROUNDED_SQUARE,
            onDone = { bitmap ->
                adjustCoverUri = null
                scope.launch {
                    val encoded = withContext(Dispatchers.IO) { ImageUploads.encodeBitmap(bitmap) }
                    if (encoded == null) {
                        vm.error.value = context.getString(R.string.group_photo_read_failed)
                    } else {
                        vm.updateGroupCover(conversationId, encoded.bytes, encoded.contentType)
                    }
                }
            },
            onDismiss = { adjustCoverUri = null },
        )
    }

    // §14.3: member profile sheet — identity (avatar/name/@username/About) with
    // "Add friend" when applicable, plus admin actions: transfer admin (§14.3),
    // remove from group (§9.3) and "Report" (§12.1) for everyone.
    memberSheet?.let { member ->
        MemberActionSheet(
            member = member,
            isCreator = conversation.createdById == member.id,
            canRemove = conversation.isAdmin && member.id != meId,
            canTransfer = conversation.isAdmin && member.id != meId,
            isFriend = friends.any { it.id == member.id },
            onAddFriend = { vm.sendFriendRequestTo(member.id, member.displayName) },
            onTransfer = {
                memberSheet = null
                transferTarget = member
            },
            onRemove = {
                memberSheet = null
                removeTarget = member
            },
            onReport = {
                memberSheet = null
                reportTarget = member
            },
            onDismiss = { memberSheet = null },
        )
    }

    reportTarget?.let { member ->
        com.klic.mobile.app.feature.report.ReportSheet(
            vm = vm,
            target = com.klic.mobile.app.feature.report.ReportTarget.User(
                userId = member.id,
                displayName = member.displayName,
                username = member.username,
            ),
            onDismiss = { reportTarget = null },
        )
    }

    removeTarget?.let { member ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.group_remove_confirm_title, member.displayName)) },
            text = { Text(stringResource(R.string.group_remove_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeGroupMember(conversationId, member.id)
                    removeTarget = null
                }) { Text(stringResource(R.string.group_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    // §14.3: confirm before handing the group over — permissions follow immediately.
    transferTarget?.let { member ->
        AlertDialog(
            onDismissRequest = { transferTarget = null },
            title = { Text(stringResource(R.string.group_transfer_confirm_title, member.displayName)) },
            text = { Text(stringResource(R.string.group_transfer_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.transferAdmin(conversationId, member.id)
                    transferTarget = null
                }) { Text(stringResource(R.string.group_transfer_admin), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { transferTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun GroupCallButton(iconRes: Int, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() },
            onClick = onClick,
        ),
    ) {
        Box(
            modifier = Modifier.size(56.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
