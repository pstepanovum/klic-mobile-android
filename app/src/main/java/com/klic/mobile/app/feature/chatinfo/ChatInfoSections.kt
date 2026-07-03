package com.klic.mobile.app.feature.chatinfo

import android.media.RingtoneManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.klic.mobile.app.ui.components.KlicSelectionSheet
import com.klic.mobile.app.ui.components.KlicSheetOption
import com.klic.mobile.app.ui.components.KlicTonePickerSheet
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
internal fun InfoSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 18.dp, bottom = 6.dp),
    )
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
        KlicSelectionSheet(
            title = "Save to Photos",
            options = listOf(
                KlicSheetOption(SettingsStore.SAVE_DEFAULT, "Default (Off)"),
                KlicSheetOption(SettingsStore.SAVE_ALWAYS, "Always"),
                KlicSheetOption(SettingsStore.SAVE_NEVER, "Never"),
            ),
            selectedValue = saveMode,
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
    // In-app tone pickers (§9.2) — the stock RingtoneManager activity is gone.
    var showMessageToneSheet by remember { mutableStateOf(false) }
    var showCallToneSheet by remember { mutableStateOf(false) }

    LaunchedEffect(conversationId) { prefs = vm.fetchConversationPrefs(conversationId) }

    InfoSectionLabel("NOTIFICATIONS")
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
            onClick = { showMessageToneSheet = true },
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
            onClick = { showCallToneSheet = true },
        )
    }

    if (showMessagesMuteSheet) {
        MuteSelectionSheet(
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
        MuteSelectionSheet(
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
    if (showMessageToneSheet) {
        KlicTonePickerSheet(
            title = "Alert tone",
            ringtoneType = RingtoneManager.TYPE_NOTIFICATION,
            selectedUri = settings.messageTones[conversationId],
            onPick = { uri ->
                scope.launch { SettingsStore.setMessageTone(conversationId, uri) }
                showMessageToneSheet = false
            },
            onDismiss = { showMessageToneSheet = false },
        )
    }
    if (showCallToneSheet) {
        KlicTonePickerSheet(
            title = "Ringtone",
            ringtoneType = RingtoneManager.TYPE_RINGTONE,
            selectedUri = settings.callTones[conversationId],
            onPick = { uri ->
                scope.launch { SettingsStore.setCallTone(conversationId, uri) }
                showCallToneSheet = false
            },
            onDismiss = { showCallToneSheet = false },
        )
    }
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

// Values for the mute duration sheet — mapped to ISO instants on pick.
private const val MUTE_8H = "8h"
private const val MUTE_WEEK = "1w"
private const val MUTE_ALWAYS = "always"
private const val MUTE_OFF = "off"

/** 8 hours / 1 week / Always (+ Unmute when muted) via the Klic selection sheet (§9.2). */
@Composable
internal fun MuteSelectionSheet(
    title: String,
    muted: Boolean,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    KlicSelectionSheet(
        title = title,
        options = buildList {
            add(KlicSheetOption(MUTE_8H, "8 hours"))
            add(KlicSheetOption(MUTE_WEEK, "1 week"))
            add(KlicSheetOption(MUTE_ALWAYS, "Always"))
            if (muted) add(KlicSheetOption(MUTE_OFF, "Unmute", accent = true))
        },
        selectedValue = null,
        onSelect = { value ->
            onPick(
                when (value) {
                    MUTE_8H -> Instant.now().plus(8, ChronoUnit.HOURS).toString()
                    MUTE_WEEK -> Instant.now().plus(7, ChronoUnit.DAYS).toString()
                    MUTE_ALWAYS -> MUTE_ALWAYS_ISO
                    else -> null
                }
            )
        },
        onDismiss = onDismiss,
    )
}
