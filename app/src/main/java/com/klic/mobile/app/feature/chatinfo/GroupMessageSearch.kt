package com.klic.mobile.app.feature.chatinfo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.data.Message
import com.klic.mobile.app.feature.KlicViewModel
import com.klic.mobile.app.feature.chat.messagelist.messagePreview
import com.klic.mobile.app.ui.components.KlicSearchBar
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.klic.mobile.app.R

/**
 * Client-side message search with fetch-back pagination (§8.4). Filters the pages
 * fetched so far; "Search older" pulls more history. A result tap jumps to the
 * message in the chat.
 */
@Composable
internal fun GroupMessageSearch(
    vm: KlicViewModel,
    conversationId: String,
    senderName: (String) -> String,
    onResultTap: (Message) -> Unit,
) {
    var query by rememberSaveable(conversationId) { mutableStateOf("") }
    var loaded by remember(conversationId) { mutableStateOf<List<Message>>(emptyList()) }
    var oldest by remember(conversationId) { mutableStateOf<String?>(null) }
    var exhausted by remember(conversationId) { mutableStateOf(false) }
    var loading by remember(conversationId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun loadMore(pages: Int = 2) {
        if (exhausted || loading) return
        loading = true
        repeat(pages) {
            val batch = vm.fetchMessagesBefore(conversationId, oldest)
            if (batch.isNullOrEmpty()) { exhausted = true; loading = false; return }
            oldest = batch.last().createdAt
            loaded = loaded + batch
            if (batch.size < 50) { exhausted = true; loading = false; return }
        }
        loading = false
    }

    LaunchedEffect(conversationId) { loadMore() }

    val results = remember(query, loaded) {
        if (query.isBlank()) emptyList()
        else loaded.filter { !it.isDeleted && it.body.contains(query, ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize()) {
        KlicSearchBar(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.group_search_messages),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(results, key = { it.id }) { msg ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onResultTap(msg) }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Row {
                        Text(
                            senderName(msg.senderId),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            shortDate(msg.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        messagePreview(msg),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                InfoDivider()
            }
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                    when {
                        loading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        query.isNotBlank() && results.isEmpty() && exhausted ->
                            Text(
                                stringResource(R.string.group_no_matches),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        !exhausted -> TextButton(
                            onClick = { scope.launch { loadMore(pages = 4) } },
                        ) { Text(stringResource(R.string.info_search_older)) }
                    }
                }
            }
        }
    }
}
