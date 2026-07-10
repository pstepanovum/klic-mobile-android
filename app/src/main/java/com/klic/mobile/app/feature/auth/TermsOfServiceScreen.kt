package com.klic.mobile.app.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.klic.mobile.app.R

private val sections = listOf(
    R.string.tos_accept_title to R.string.tos_accept_body,
    R.string.tos_conduct_title to R.string.tos_conduct_body,
    R.string.tos_moderation_title to R.string.tos_moderation_body,
    R.string.tos_termination_title to R.string.tos_termination_body,
    R.string.tos_license_title to R.string.tos_license_body,
    R.string.tos_warranty_title to R.string.tos_warranty_body,
    R.string.tos_contact_title to R.string.tos_contact_body,
)

@Composable
fun TermsOfServiceScreen(onBack: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
    Column(
        modifier = Modifier
            .widthIn(max = 680.dp)
            .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 8.dp, top = 20.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.tos_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.common_done), color = MaterialTheme.colorScheme.primary)
            }
        }

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            TermsOfServiceContent()
        }
    }
    } // Box
}

/** The terms sections without their own scroll — also embedded in Settings. */
@Composable
fun TermsOfServiceContent() {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        sections.forEach { (title, body) ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    stringResource(body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            stringResource(R.string.tos_effective_date),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
