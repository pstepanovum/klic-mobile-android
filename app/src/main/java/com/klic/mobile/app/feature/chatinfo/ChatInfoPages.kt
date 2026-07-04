package com.klic.mobile.app.feature.chatinfo

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.klic.mobile.app.data.CacheStats
import com.klic.mobile.app.data.ConversationAttachment
import com.klic.mobile.app.data.Message
import com.klic.mobile.app.data.formatBytes
import com.klic.mobile.app.feature.KlicViewModel
import com.klic.mobile.app.feature.chat.actions.ImageViewerOverlay
import com.klic.mobile.app.feature.chat.media.AttachmentDownloads
import com.klic.mobile.app.feature.chat.media.FileDetailSheet
import com.klic.mobile.app.feature.chat.media.PdfViewerOverlay
import com.klic.mobile.app.feature.chat.media.formatByteSize
import com.klic.mobile.app.feature.chat.media.isPdfAttachment
import com.klic.mobile.app.feature.chat.messagelist.messagePreview
import com.klic.mobile.app.ui.components.rememberStableImageRequest
import com.klic.mobile.app.ui.theme.KlicIcons
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.ui.res.stringResource
import com.klic.mobile.app.R

// ── "Media, links, docs" tabbed browser (§8.4) ───────────────────────────────

@Composable
fun MediaLinksDocsPage(
    vm: KlicViewModel,
    conversationId: String,
) {
    var tab by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            listOf(
                stringResource(R.string.info_tab_media),
                stringResource(R.string.info_tab_links),
                stringResource(R.string.info_tab_docs),
            ).forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
            }
        }
        when (tab) {
            0 -> MediaTab(vm, conversationId)
            1 -> LinksTab(vm, conversationId)
            else -> DocsTab(vm, conversationId)
        }
    }
}

@Composable
private fun MediaTab(vm: KlicViewModel, conversationId: String) {
    // §9.9: seed from the cached first page so re-entry renders instantly; the
    // initial loadMore() then REPLACES the first page with fresh data.
    var items by remember(conversationId) {
        mutableStateOf(
            vm.cachedAttachments(conversationId, null)?.items
                ?.filter { it.kind == "IMAGE" || it.kind == "VIDEO" }
                ?: emptyList()
        )
    }
    var cursor by remember(conversationId) { mutableStateOf<String?>(null) }
    var loading by remember(conversationId) { mutableStateOf(true) }
    var exhausted by remember(conversationId) { mutableStateOf(false) }
    var viewerUrl by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun loadMore() {
        loading = true
        val firstPage = cursor == null
        // No kind filter — one paged stream, filtered to visual media client-side.
        val page = vm.fetchAttachments(conversationId, kind = null, cursor = cursor)
        if (page == null) { exhausted = true; loading = false; return }
        val fresh = page.items.filter { it.kind == "IMAGE" || it.kind == "VIDEO" }
        items = if (firstPage) fresh else items + fresh
        cursor = page.nextCursor
        exhausted = page.nextCursor == null
        loading = false
    }

    LaunchedEffect(conversationId) { loadMore() }

    if (items.isEmpty() && !loading) {
        EmptyTab(stringResource(R.string.info_no_media))
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize().padding(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(items, key = { it.id }) { att ->
            Box(
                Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { if (att.kind == "IMAGE") viewerUrl = att.url },
            ) {
                if (att.kind == "VIDEO") {
                    // §14.2: media-browser video tiles get a real first-frame thumbnail.
                    val thumb by com.klic.mobile.app.feature.chat.media.rememberVideoThumbnail(
                        att.asAttachment(), conversationId,
                    )
                    thumb?.let {
                        androidx.compose.foundation.Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Video",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(28.dp)
                            .background(Color.Black.copy(alpha = 0.4f), CircleShape),
                    )
                } else {
                    AsyncImage(
                        // §9.9: stable cache key — the grid never re-downloads thumbnails.
                        model = rememberStableImageRequest(att.url),
                        contentDescription = "Photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        if (!exhausted) {
            item {
                Box(Modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
                    if (loading) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = { scope.launch { loadMore() } }) { Text(stringResource(R.string.common_more)) }
                    }
                }
            }
        }
    }

    viewerUrl?.let { url -> ImageViewerOverlay(url = url, onDismiss = { viewerUrl = null }) }
}

@Composable
private fun LinksTab(vm: KlicViewModel, conversationId: String) {
    val context = LocalContext.current
    var links by remember { mutableStateOf<List<Pair<String, Message>>>(emptyList()) }
    var oldest by remember { mutableStateOf<String?>(null) }
    var exhausted by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // URL-regex scan over fetched history, with fetch-back pagination (§8.4).
    suspend fun loadMore(pages: Int) {
        loading = true
        repeat(pages) {
            val batch = vm.fetchMessagesBefore(conversationId, oldest)
            if (batch.isNullOrEmpty()) { exhausted = true; loading = false; return }
            oldest = batch.last().createdAt   // newest-first from the API
            links = links + batch.flatMap { msg ->
                if (msg.isDeleted) emptyList()
                else extractLinks(msg.body).map { it to msg }
            }
            if (batch.size < 50) { exhausted = true; loading = false; return }
        }
        loading = false
    }

    LaunchedEffect(conversationId) { loadMore(pages = 4) }

    if (links.isEmpty() && exhausted && !loading) {
        EmptyTab(stringResource(R.string.info_no_links))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(links) { (link, msg) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(linkToOpenableUrl(link))))
                        }
                    }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(36.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(KlicIcons.link),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        link,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        shortDate(msg.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                when {
                    loading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    !exhausted -> TextButton(onClick = { scope.launch { loadMore(pages = 4) } }) {
                        Text(stringResource(R.string.info_search_older))
                    }
                }
            }
        }
    }
}

@Composable
private fun DocsTab(vm: KlicViewModel, conversationId: String) {
    val context = LocalContext.current
    // §9.9: cached first page renders instantly; the refresh replaces it.
    var items by remember(conversationId) {
        mutableStateOf(vm.cachedAttachments(conversationId, "FILE")?.items ?: emptyList())
    }
    var cursor by remember(conversationId) { mutableStateOf<String?>(null) }
    var loading by remember(conversationId) { mutableStateOf(true) }
    var exhausted by remember(conversationId) { mutableStateOf(false) }
    var pdfFile by remember { mutableStateOf<File?>(null) }
    var fileDetail by remember { mutableStateOf<Pair<ConversationAttachment, File>?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun loadMore() {
        loading = true
        val firstPage = cursor == null
        val page = vm.fetchAttachments(conversationId, kind = "FILE", cursor = cursor)
        if (page == null) { exhausted = true; loading = false; return }
        items = if (firstPage) page.items else items + page.items
        cursor = page.nextCursor
        exhausted = page.nextCursor == null
        loading = false
    }

    LaunchedEffect(conversationId) { loadMore() }

    if (items.isEmpty() && !loading) {
        EmptyTab(stringResource(R.string.info_no_docs))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { doc ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        scope.launch {
                            val att = doc.asAttachment()
                            val file = AttachmentDownloads.ensureLocal(context, att, conversationId)
                            when {
                                file == null -> vm.error.value = context.getString(R.string.err_download_file)
                                isPdfAttachment(att) -> pdfFile = file
                                else -> fileDetail = doc to file
                            }
                        }
                    }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(36.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(KlicIcons.document),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        doc.fileName ?: "File",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${formatByteSize(doc.byteSize)} · ${shortDate(doc.createdAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (!exhausted) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                    if (loading) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = { scope.launch { loadMore() } }) { Text(stringResource(R.string.common_more)) }
                    }
                }
            }
        }
    }

    pdfFile?.let { file -> PdfViewerOverlay(file = file, onDismiss = { pdfFile = null }) }
    fileDetail?.let { (doc, file) ->
        FileDetailSheet(att = doc.asAttachment(), file = file, onDismiss = { fileDetail = null })
    }
}

// ── "Starred" list (§8.4) ────────────────────────────────────────────────────

@Composable
fun StarredMessagesPage(
    vm: KlicViewModel,
    conversationId: String,
    senderName: (String) -> String,
    onOpenMessage: (Message) -> Unit,
) {
    // §9.9: cached first page renders instantly; the refresh replaces it.
    var items by remember(conversationId) {
        mutableStateOf(vm.cachedStarred(conversationId)?.items ?: emptyList())
    }
    var cursor by remember(conversationId) { mutableStateOf<String?>(null) }
    var loading by remember(conversationId) { mutableStateOf(true) }
    var exhausted by remember(conversationId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun loadMore() {
        loading = true
        val firstPage = cursor == null
        val page = vm.fetchStarred(conversationId, cursor)
        if (page == null) { exhausted = true; loading = false; return }
        items = if (firstPage) page.items else items + page.items
        cursor = page.nextCursor
        exhausted = page.nextCursor == null
        loading = false
    }

    LaunchedEffect(conversationId) { loadMore() }

    if (items.isEmpty() && !loading) {
        EmptyTab(stringResource(R.string.info_no_starred))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { msg ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpenMessage(msg) }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            senderName(msg.senderId),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.weight(1f))
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
                Icon(
                    painter = painterResource(KlicIcons.starBold),
                    contentDescription = "Unstar",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .size(18.dp)
                        .clickable {
                            vm.toggleStar(msg.copy(starred = true))
                            items = items.filterNot { it.id == msg.id }
                        },
                )
            }
            InfoDivider()
        }
        if (!exhausted) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                    if (loading) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = { scope.launch { loadMore() } }) { Text(stringResource(R.string.common_more)) }
                    }
                }
            }
        }
    }
}

// ── "Manage storage" (§8.4) ──────────────────────────────────────────────────

@Composable
fun ManageStoragePage(conversationId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bytes by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(conversationId) {
        bytes = CacheStats.conversationCachedBytes(context, conversationId)
    }

    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        InfoCard {
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.info_cached_in_chat), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        stringResource(R.string.info_cached_sub),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    bytes?.let { formatBytes(it) } ?: "…",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                scope.launch {
                    CacheStats.clearConversation(context, conversationId)
                    bytes = CacheStats.conversationCachedBytes(context, conversationId)
                }
            },
            enabled = (bytes ?: 0L) > 0L,
            modifier = Modifier.fillMaxWidth(),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) { Text(stringResource(R.string.info_clear_chat_cache), modifier = Modifier.padding(vertical = 6.dp)) }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.info_cache_footer),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Small shared bits ────────────────────────────────────────────────────────

@Composable
internal fun EmptyTab(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun shortDate(iso: String): String = runCatching {
    DateTimeFormatter.ofPattern("MMM d, yyyy")
        .format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
}.getOrDefault("")
