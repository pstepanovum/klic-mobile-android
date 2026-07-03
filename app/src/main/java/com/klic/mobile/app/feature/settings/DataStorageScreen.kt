package com.klic.mobile.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.data.CacheStats
import com.klic.mobile.app.data.DataUsage
import com.klic.mobile.app.data.SettingsStore
import com.klic.mobile.app.data.formatBytes
import com.klic.mobile.app.feature.KlicViewModel
import com.klic.mobile.app.ui.components.KlicSelectionSheet
import com.klic.mobile.app.ui.components.KlicSheetOption
import kotlinx.coroutines.launch

/**
 * Settings → "Data and Storage" (§8.3): cache scan by category with a segmented bar,
 * Clear Entire Cache, network-usage counters (All / Mobile / Wi-Fi), upload quality,
 * and the media auto-download matrix.
 */
@Composable
fun DataStorageContent(vm: KlicViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by SettingsStore.snapshot.collectAsState()
    val usage by DataUsage.totals.collectAsState()
    val stickers by vm.stickers.collectAsState()

    var categories by remember { mutableStateOf<CacheStats.Categories?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var usageTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        vm.loadStickers()   // sticker URLs let the scan attribute Coil-cached sticker bytes
    }
    LaunchedEffect(stickers) {
        categories = CacheStats.scan(context, stickers.map { it.url })
    }

    // ── Storage usage ─────────────────────────────────────────────────────────
    SectionLabel("STORAGE USAGE")
    SettingsCard {
        val cats = categories
        if (cats == null) {
            Text(
                "Scanning…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        } else {
            val slices = listOf(
                Triple("Photos", cats.photos, Color(0xFF4C9AFF)),
                Triple("Videos", cats.videos, Color(0xFFF06292)),
                Triple("Audio", cats.audio, Color(0xFF9575CD)),
                Triple("Documents", cats.documents, Color(0xFFFFB74D)),
                Triple("Stickers", cats.stickers, Color(0xFF4DB6AC)),
                Triple("Misc", cats.misc, Color(0xFF90A4AE)),
            )
            Column(Modifier.padding(vertical = 16.dp)) {
                Text(
                    formatBytes(cats.total),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Used by Klic's cache",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                // Segmented bar
                if (cats.total > 0) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp)),
                    ) {
                        slices.forEach { (_, bytes, color) ->
                            if (bytes > 0) {
                                Box(
                                    Modifier
                                        .weight(bytes.toFloat().coerceAtLeast(1f))
                                        .fillMaxHeight()
                                        .background(color),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                slices.forEach { (label, bytes, color) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(10.dp).background(color, CircleShape))
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).padding(start = 10.dp),
                        )
                        Text(
                            formatBytes(bytes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = { confirmClear = true },
        enabled = (categories?.total ?: 0L) > 0L,
        modifier = Modifier.fillMaxWidth(),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.error,
        ),
    ) { Text("Clear Entire Cache", modifier = Modifier.padding(vertical = 6.dp)) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear entire cache?") },
            text = { Text("Downloaded media and files will be removed from this device. Your messages are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        CacheStats.clearAll(context)
                        categories = CacheStats.scan(context, stickers.map { it.url })
                    }
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }

    // ── Data usage ────────────────────────────────────────────────────────────
    Spacer(Modifier.height(24.dp))
    SectionLabel("DATA USAGE")
    SettingsCard {
        Column(Modifier.padding(vertical = 12.dp)) {
            TabRow(selectedTabIndex = usageTab, containerColor = Color.Transparent) {
                listOf("All", "Mobile", "Wi-Fi").forEachIndexed { index, label ->
                    Tab(selected = usageTab == index, onClick = { usageTab = index }, text = { Text(label) })
                }
            }
            Spacer(Modifier.height(10.dp))
            val network: String? = when (usageTab) {
                1 -> DataUsage.NET_CELL
                2 -> DataUsage.NET_WIFI
                else -> null
            }
            val rows = listOf(
                "Photos" to DataUsage.CAT_PHOTOS,
                "Videos" to DataUsage.CAT_VIDEOS,
                "Audio" to DataUsage.CAT_AUDIO,
                "Documents" to DataUsage.CAT_DOCS,
                "Calls (signaling)" to DataUsage.CAT_CALLS,
                "API" to DataUsage.CAT_API,
                "Other" to DataUsage.CAT_OTHER,
            )
            var totalUp = 0L
            var totalDown = 0L
            rows.forEach { (label, cat) ->
                val up = DataUsage.sum(usage, cat, up = true, network = network)
                val down = DataUsage.sum(usage, cat, up = false, network = network)
                totalUp += up; totalDown += down
                if (up > 0 || down > 0) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "↑ ${formatBytes(up)}  ↓ ${formatBytes(down)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "Total Network Usage",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatBytes(totalUp + totalDown),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                "${formatBytes(totalUp)} sent · ${formatBytes(totalDown)} received",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
        }
    }
    Spacer(Modifier.height(12.dp))
    // Capsule to match the Settings "Log out" reference (§9.8).
    Button(
        onClick = { scope.launch { DataUsage.reset() } },
        modifier = Modifier.fillMaxWidth(),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.error,
        ),
    ) { Text("Reset Statistics", modifier = Modifier.padding(vertical = 6.dp)) }

    // ── Upload quality — Klic selection sheet, not inline radio rows (§9.2) ──
    Spacer(Modifier.height(24.dp))
    SectionLabel("UPLOAD QUALITY")
    var showQualitySheet by remember { mutableStateOf(false) }
    SettingsCard {
        Row(
            Modifier.fillMaxWidth().clickable { showQualitySheet = true }.padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Photo upload quality",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (settings.uploadHd) "HD" else "Standard",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showQualitySheet) {
        KlicSelectionSheet(
            title = "Photo upload quality",
            options = listOf(
                KlicSheetOption("standard", "Standard", "Faster uploads, smaller photos (2048px)"),
                KlicSheetOption("hd", "HD", "Best quality, larger uploads (4096px)"),
            ),
            selectedValue = if (settings.uploadHd) "hd" else "standard",
            onSelect = { value ->
                scope.launch { SettingsStore.setUploadHd(value == "hd") }
                showQualitySheet = false
            },
            onDismiss = { showQualitySheet = false },
        )
    }

    // ── Media auto-download matrix ────────────────────────────────────────────
    Spacer(Modifier.height(24.dp))
    SectionLabel("MEDIA AUTO-DOWNLOAD")
    SettingsCard {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Spacer(Modifier.weight(1f))
            Text("Wi-Fi", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(28.dp))
            Text("Cellular", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
        }
        val kinds = listOf(
            "Photos" to SettingsStore.KIND_PHOTOS,
            "Audio" to SettingsStore.KIND_AUDIO,
            "Video" to SettingsStore.KIND_VIDEO,
            "Documents" to SettingsStore.KIND_DOCS,
        )
        kinds.forEachIndexed { index, (label, kind) ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = settings.autoDownloadAllowed(kind, onWifi = true),
                    onCheckedChange = { scope.launch { SettingsStore.setAutoDownload(kind, wifi = true, allowed = it) } },
                )
                Spacer(Modifier.size(14.dp))
                Switch(
                    checked = settings.autoDownloadAllowed(kind, onWifi = false),
                    onCheckedChange = { scope.launch { SettingsStore.setAutoDownload(kind, wifi = false, allowed = it) } },
                )
            }
            if (index != kinds.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    Spacer(Modifier.height(10.dp))
    Text(
        "When auto-download is off for the current network, photos show a download button instead of loading automatically.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 18.dp, bottom = 6.dp),
    )
}

@Composable
internal fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp),
    ) { content() }
}
