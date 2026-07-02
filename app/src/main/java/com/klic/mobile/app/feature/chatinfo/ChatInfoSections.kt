package com.klic.mobile.app.feature.chatinfo

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.data.ConversationPrefs
import com.klic.mobile.app.data.SettingsStore
import com.klic.mobile.app.feature.KlicViewModel
import com.klic.mobile.app.ui.theme.KlicIcons
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Sub-pages reachable from a chat info screen (§8.4). */
enum class ChatInfoSub { MEDIA, STARRED, STORAGE }

/** "Always" mute sentinel (§8.2). */
internal const val MUTE_ALWAYS_ISO = "9999-12-31T00:00:00.000Z"

// ── Shared row/card building blocks ──────────────────────────────────────────

@Composable
internal fun InfoCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp),
    ) { content() }
}

@Composable
internal fun InfoDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
}

@Composable
internal fun InfoRowItem(
    icon: Painter? = null,
    title: String,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painter = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            trailing()
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (value != null) {
                    Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// ── Sections card: media / starred / storage / save-to-photos (§8.4) ────────

@Composable
fun ChatInfoSectionsCard(
    conversationId: String,
    onOpen: (ChatInfoSub) -> Unit,
) {
    val settings by SettingsStore.snapshot.collectAsState()
    val scope = rememberCoroutineScope()
    var showSaveSheet by remember { mutableStateOf(false) }
    val saveMode = settings.saveToPhotos[conversationId] ?: SettingsStore.SAVE_DEFAULT

    InfoCard {
        InfoRowItem(
            icon = painterResource(KlicIcons.gallery),
            title = "Media, links, docs",
            onClick = { onOpen(ChatInfoSub.MEDIA) },
        )
        InfoDivider()
        InfoRowItem(
            icon = painterResource(KlicIcons.star),
            title = "Starred",
            onClick = { onOpen(ChatInfoSub.STARRED) },
        )
        InfoDivider()
        InfoRowItem(
            icon = painterResource(KlicIcons.folder),
            title = "Manage storage",
            onClick = { onOpen(ChatInfoSub.STORAGE) },
        )
        InfoDivider()
        InfoRowItem(
            icon = painterResource(KlicIcons.document),
            title = "Save to Photos",
            value = when (saveMode) {
                SettingsStore.SAVE_ALWAYS -> "Always"
                SettingsStore.SAVE_NEVER -> "Never"
                else -> "Default (Off)"
            },
            onClick = { showSaveSheet = true },
        )
    }

    if (showSaveSheet) {
        OptionSheet(
            title = "Save to Photos",
            options = listOf(
                "Default (Off)" to SettingsStore.SAVE_DEFAULT,
                "Always" to SettingsStore.SAVE_ALWAYS,
                "Never" to SettingsStore.SAVE_NEVER,
            ),
            selected = saveMode,
            onSelect = { mode ->
                scope.launch { SettingsStore.setSaveToPhotos(conversationId, mode) }
                showSaveSheet = false
            },
            onDismiss = { showSaveSheet = false },
        )
    }
}

// ── Notifications card (§8.4, both friend and group info pages) ─────────────

@Composable
fun ChatNotificationsCard(
    vm: KlicViewModel,
    conversationId: String,
    isGroup: Boolean,
) {
    val scope = rememberCoroutineScope()
    val settings by SettingsStore.snapshot.collectAsState()
    var prefs by remember(conversationId) { mutableStateOf(ConversationPrefs()) }
    var showMessagesMuteSheet by remember { mutableStateOf(false) }
    var showCallsMuteSheet by remember { mutableStateOf(false) }

    LaunchedEffect(conversationId) { prefs = vm.fetchConversationPrefs(conversationId) }

    // System pickers for the local tone prefs (§8.4).
    val messageTonePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            scope.launch { SettingsStore.setMessageTone(conversationId, uri?.toString()) }
        }
    }
    val callTonePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            scope.launch { SettingsStore.setCallTone(conversationId, uri?.toString()) }
        }
    }

    Text(
        "NOTIFICATIONS",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 18.dp, bottom = 6.dp),
    )
    InfoCard {
        InfoRowItem(
            icon = painterResource(KlicIcons.message),
            title = "Mute messages",
            value = muteLabel(prefs.messagesMutedUntil),
            onClick = { showMessagesMuteSheet = true },
        )
        if (isGroup) {
            InfoDivider()
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Mute @all mentions", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "When off, @all rings through a mute.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = prefs.muteMentions,
                    onCheckedChange = { value ->
                        scope.launch {
                            prefs = vm.setConversationPrefs(conversationId, prefs, muteMentions = value)
                        }
                    },
                )
            }
        }
        InfoDivider()
        InfoRowItem(
            icon = painterResource(KlicIcons.notification),
            title = "Alert tone",
            value = if (settings.messageTones[conversationId] != null) "Custom" else "Default",
            onClick = {
                messageTonePicker.launch(
                    ringtonePickerIntent(
                        RingtoneManager.TYPE_NOTIFICATION,
                        "Alert tone",
                        settings.messageTones[conversationId],
                    )
                )
            },
        )
        InfoDivider()
        InfoRowItem(
            icon = painterResource(KlicIcons.phone),
            title = "Mute calls",
            value = muteLabel(prefs.callsMutedUntil),
            onClick = { showCallsMuteSheet = true },
        )
        InfoDivider()
        InfoRowItem(
            icon = painterResource(KlicIcons.video),
            title = "Ringtone",
            value = if (settings.callTones[conversationId] != null) "Custom" else "Default",
            onClick = {
                callTonePicker.launch(
                    ringtonePickerIntent(
                        RingtoneManager.TYPE_RINGTONE,
                        "Ringtone",
                        settings.callTones[conversationId],
                    )
                )
            },
        )
    }

    if (showMessagesMuteSheet) {
        MuteSheet(
            title = "Mute messages",
            muted = isMuted(prefs.messagesMutedUntil),
            onPick = { untilIso ->
                scope.launch {
                    prefs = vm.setConversationPrefs(
                        conversationId, prefs,
                        setMessagesMuted = true, messagesMutedUntil = untilIso,
                    )
                }
                showMessagesMuteSheet = false
            },
            onDismiss = { showMessagesMuteSheet = false },
        )
    }
    if (showCallsMuteSheet) {
        MuteSheet(
            title = "Mute call notifications",
            muted = isMuted(prefs.callsMutedUntil),
            onPick = { untilIso ->
                scope.launch {
                    prefs = vm.setConversationPrefs(
                        conversationId, prefs,
                        setCallsMuted = true, callsMutedUntil = untilIso,
                    )
                }
                showCallsMuteSheet = false
            },
            onDismiss = { showCallsMuteSheet = false },
        )
    }
}

private fun ringtonePickerIntent(type: Int, title: String, currentUri: String?): Intent =
    Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, type)
        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, title)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        currentUri?.let { putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(it)) }
    }

internal fun isMuted(untilIso: String?): Boolean =
    untilIso?.let { runCatching { Instant.parse(it).isAfter(Instant.now()) }.getOrDefault(false) } == true

internal fun muteLabel(untilIso: String?): String {
    if (!isMuted(untilIso)) return "Off"
    val until = runCatching { Instant.parse(untilIso!!) }.getOrNull() ?: return "Off"
    // The "Always" sentinel sits absurdly far in the future.
    if (until.isAfter(Instant.now().plus(365L * 50, ChronoUnit.DAYS))) return "Always"
    return "Until " + DateTimeFormatter.ofPattern("MMM d, h:mm a")
        .format(until.atZone(ZoneId.systemDefault()))
}

/** 8 hours / 1 week / Always (+ Unmute when already muted) — shared by messages & calls. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MuteSheet(
    title: String,
    muted: Boolean,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            SheetOption("8 hours") { onPick(Instant.now().plus(8, ChronoUnit.HOURS).toString()) }
            SheetOption("1 week") { onPick(Instant.now().plus(7, ChronoUnit.DAYS).toString()) }
            SheetOption("Always") { onPick(MUTE_ALWAYS_ISO) }
            if (muted) SheetOption("Unmute", accent = true) { onPick(null) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OptionSheet(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            options.forEach { (label, value) ->
                Row(
                    Modifier.fillMaxWidth().clickable { onSelect(value) }.padding(vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (value == selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (value == selected) {
                        Icon(
                            painter = painterResource(KlicIcons.check),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetOption(label: String, accent: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 13.dp),
    )
}
