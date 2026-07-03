package com.klic.mobile.app.ui.components

import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.ui.theme.KlicIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource

/** One row of a [KlicSelectionSheet]. */
data class KlicSheetOption(
    val value: String,
    val label: String,
    val subtitle: String? = null,
    /** Accent-colored row (e.g. "Unmute") — never shows a check mark. */
    val accent: Boolean = false,
)

/**
 * THE Klic option picker (§9.2): every selection that used to be a native dropdown,
 * menu or system dialog goes through this one bottom sheet — rounded card of rows,
 * check on the selected row, capsule Cancel affordance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KlicSelectionSheet(
    title: String,
    options: List<KlicSheetOption>,
    selectedValue: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 4.dp, bottom = 12.dp),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                    .padding(horizontal = 18.dp),
            ) {
                options.forEachIndexed { index, option ->
                    SelectionRow(
                        option = option,
                        selected = !option.accent && option.value == selectedValue,
                        onClick = { onSelect(option.value) },
                    )
                    if (index != options.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            SheetCancelButton(onDismiss)
        }
    }
}

@Composable
private fun SelectionRow(option: KlicSheetOption, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                option.label,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    option.accent || selected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            option.subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Icon(
                painter = painterResource(KlicIcons.check),
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Capsule cancel affordance shared by the Klic sheets — Log-out button styling. */
@Composable
private fun SheetCancelButton(onClick: () -> Unit) {
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.CircleShape,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) { Text(stringResource(com.klic.mobile.app.R.string.common_cancel), modifier = Modifier.padding(vertical = 6.dp)) }
}

/**
 * In-app tone/ringtone picker in the same Klic sheet language (§9.2) — replaces the
 * stock RingtoneManager picker activity. Tapping a row previews the tone and selects
 * it; Done commits. Leaving the sheet ALWAYS stops the preview (§9.4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KlicTonePickerSheet(
    title: String,
    ringtoneType: Int,
    selectedUri: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var tones by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var selection by remember { mutableStateOf(selectedUri) }
    var preview by remember { mutableStateOf<Ringtone?>(null) }

    LaunchedEffect(ringtoneType) {
        tones = withContext(Dispatchers.IO) {
            runCatching {
                val manager = RingtoneManager(context).apply { setType(ringtoneType) }
                val cursor = manager.cursor
                buildList {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)
                        add(name to manager.getRingtoneUri(cursor.position).toString())
                    }
                }
            }.getOrDefault(emptyList())
        }
    }

    fun stopPreview() {
        runCatching { preview?.stop() }
        preview = null
    }

    fun previewTone(uri: String?) {
        stopPreview()
        val resolved = uri?.let(Uri::parse) ?: RingtoneManager.getDefaultUri(ringtoneType)
        preview = runCatching { RingtoneManager.getRingtone(context, resolved) }.getOrNull()
        runCatching { preview?.play() }
    }

    // §9.4: sound must never follow the user out of the picker.
    DisposableEffect(Unit) {
        onDispose { stopPreview() }
    }

    ModalBottomSheet(
        onDismissRequest = { stopPreview(); onDismiss() },
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 4.dp, bottom = 12.dp),
            )
            val rows = listOf<Pair<String, String?>>(
                stringResource(com.klic.mobile.app.R.string.common_default) to null,
            ) + tones.map { (name, uri) -> name to uri }
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)),
            ) {
                items(rows.size) { index ->
                    val (name, uri) = rows[index]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { selection = uri; previewTone(uri) }
                            .padding(horizontal = 18.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (uri == selection) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (uri == selection) {
                            Icon(
                                painter = painterResource(KlicIcons.check),
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    if (index != rows.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            modifier = Modifier.padding(horizontal = 18.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.Button(
                onClick = { stopPreview(); onPick(selection) },
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.CircleShape,
            ) { Text(stringResource(com.klic.mobile.app.R.string.common_done), modifier = Modifier.padding(vertical = 6.dp)) }
            Spacer(Modifier.height(8.dp))
            SheetCancelButton { stopPreview(); onDismiss() }
        }
    }
}
