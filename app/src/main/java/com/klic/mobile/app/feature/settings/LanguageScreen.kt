package com.klic.mobile.app.feature.settings

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.R
import com.klic.mobile.app.data.LocaleHelper

/** Settings → Language (§10.5): System default / English / Русский / 中文. */
@Composable
fun LanguageContent() {
    val context = LocalContext.current
    var current by remember { mutableStateOf(LocaleHelper.currentTag(context)) }

    val options = listOf(
        "" to stringResource(R.string.language_system_default),
        "en" to "English",
        "ru" to "Русский",
        "zh-CN" to "中文",
    )

    SettingsCard {
        options.forEachIndexed { index, (tag, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        current = tag
                        (context as? Activity)?.let { LocaleHelper.apply(it, tag) }
                    }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (current.equals(tag, ignoreCase = true) ||
                    (tag.isNotEmpty() && current.startsWith(tag, ignoreCase = true))
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (index != options.lastIndex) RowDivider()
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.language_footer),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
