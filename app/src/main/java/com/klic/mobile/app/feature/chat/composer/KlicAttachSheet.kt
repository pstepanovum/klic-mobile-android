package com.klic.mobile.app.feature.chat.composer

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.klic.mobile.app.R
import com.klic.mobile.app.feature.chat.voice.durationText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ── Gallery data ────────────────────────────────────────────────────────────

private data class GalleryItem(
    val uri: Uri,
    val isVideo: Boolean,
    val durationMs: Long,
)

private enum class GalleryAlbum { RECENTS, FAVORITES, VIDEOS, SELFIES }
private enum class AttachTab { GALLERY, FILES }

/**
 * §10.11/§11.2: THE attachment sheet — one Klic bottom sheet (drag-to-expand) with
 * Gallery | Files tabs. Gallery = live camera tile (top-left, 2 rows tall) + MediaStore
 * grid newest-first with ordered multi-select ("Send N" fires one message per item,
 * in order, through the upload-pill pipeline), album dropdown (Recents/Favorites/
 * Videos/Selfies) and a system-picker fallback. Files = system document picker +
 * ML Kit document scanner (multi-page PDF).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KlicAttachmentSheet(
    onSendMedia: (List<Uri>) -> Unit,
    onOpenCamera: () -> Unit,
    onSystemGallery: () -> Unit,
    onSelectFiles: () -> Unit,
    onScannedPdf: (Uri) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var tab by remember { mutableStateOf(AttachTab.GALLERY) }
    var album by remember { mutableStateOf(GalleryAlbum.RECENTS) }
    var showAlbumSheet by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var selected by remember { mutableStateOf(listOf<Uri>()) }
    var hasPermission by remember { mutableStateOf(hasGalleryPermission(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        hasPermission = grants.values.any { it }
    }

    LaunchedEffect(album, hasPermission) {
        if (hasPermission) {
            items = withContext(Dispatchers.IO) { queryGallery(context, album) }
        }
    }

    // Scan Document (ML Kit) → multi-page PDF → normal file-send flow.
    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val pdfUri = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pdf?.uri
        if (result.resultCode == Activity.RESULT_OK && pdfUri != null) {
            onScannedPdf(pdfUri)
            onDismiss()
        }
    }

    fun toggle(uri: Uri) {
        selected = if (uri in selected) selected - uri else selected + uri
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            // Segmented capsule: Gallery | Files.
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    .padding(4.dp),
            ) {
                TabCapsule(
                    label = stringResource(R.string.attach_tab_gallery),
                    active = tab == AttachTab.GALLERY,
                    modifier = Modifier.weight(1f),
                ) { tab = AttachTab.GALLERY }
                TabCapsule(
                    label = stringResource(R.string.attach_tab_files),
                    active = tab == AttachTab.FILES,
                    modifier = Modifier.weight(1f),
                ) { tab = AttachTab.FILES }
            }
            Spacer(Modifier.height(12.dp))

            when (tab) {
                AttachTab.GALLERY -> {
                    if (!hasPermission) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                stringResource(R.string.attach_gallery_permission),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { permissionLauncher.launch(galleryPermissions()) },
                                shape = CircleShape,
                            ) { Text(stringResource(R.string.attach_allow_access)) }
                        }
                    } else {
                        // Header: album dropdown pill + live selection counter.
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                Modifier
                                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                    .clickable { showAlbumSheet = true }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    albumLabel(album),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Icon(
                                    Icons.Filled.ArrowDropDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            if (selected.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.attach_selected_count, selected.size),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier.fillMaxWidth().height(400.dp),
                        ) {
                            // §11.2: camera tile — first column, spanning two rows, with a
                            // LIVE preview; the first four gallery items fill the 2x2 beside it.
                            item(key = "camera_block", span = { GridItemSpan(maxLineSpan) }) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    CameraPreviewTile(
                                        modifier = Modifier.weight(1f).aspectRatio(0.5f),
                                        onOpenCamera = { onOpenCamera(); onDismiss() },
                                    )
                                    Column(
                                        Modifier.weight(2f).aspectRatio(1f),
                                        verticalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        listOf(0 to 1, 2 to 3).forEach { (a, b) ->
                                            Row(
                                                Modifier.weight(1f).fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                            ) {
                                                listOf(a, b).forEach { index ->
                                                    Box(Modifier.weight(1f).fillMaxHeight()) {
                                                        items.getOrNull(index)?.let { item ->
                                                            GalleryCell(
                                                                item = item,
                                                                selectionIndex = selected.indexOf(item.uri),
                                                                modifier = Modifier.fillMaxSize(),
                                                                onToggle = { toggle(item.uri) },
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            items(items.drop(4), key = { it.uri }) { item ->
                                GalleryCell(
                                    item = item,
                                    selectionIndex = selected.indexOf(item.uri),
                                    modifier = Modifier.aspectRatio(1f),
                                    onToggle = { toggle(item.uri) },
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        if (selected.isNotEmpty()) {
                            // §11.2: bulk send — one message per item, selection order,
                            // through the existing upload-pill pipeline.
                            Button(
                                onClick = { onSendMedia(selected); onDismiss() },
                                modifier = Modifier.fillMaxWidth(),
                                shape = CircleShape,
                            ) {
                                Text(
                                    stringResource(R.string.picker_send_count, selected.size),
                                    modifier = Modifier.padding(vertical = 6.dp),
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        // Fallback capsule: the full system picker (current flow).
                        Button(
                            onClick = { onSystemGallery(); onDismiss() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) {
                            Text(
                                stringResource(R.string.attach_select_from_gallery),
                                modifier = Modifier.padding(vertical = 6.dp),
                            )
                        }
                    }
                }

                AttachTab.FILES -> {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Button(
                            onClick = { onSelectFiles(); onDismiss() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = CircleShape,
                        ) {
                            Text(
                                stringResource(R.string.attach_select_from_files),
                                modifier = Modifier.padding(vertical = 6.dp),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        val scanFailed = stringResource(R.string.attach_scan_unavailable)
                        Button(
                            onClick = {
                                val activity = context as? Activity ?: return@Button
                                val options = GmsDocumentScannerOptions.Builder()
                                    .setGalleryImportAllowed(true)
                                    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                                    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                                    .build()
                                GmsDocumentScanning.getClient(options)
                                    .getStartScanIntent(activity)
                                    .addOnSuccessListener { sender ->
                                        scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
                                    }
                                    .addOnFailureListener {
                                        // Play-services scanner unavailable — fall back to
                                        // the plain file picker with a clear notice.
                                        Toast.makeText(context, scanFailed, Toast.LENGTH_LONG).show()
                                    }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) {
                            Text(
                                stringResource(R.string.attach_scan_document),
                                modifier = Modifier.padding(vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAlbumSheet) {
        com.klic.mobile.app.ui.components.KlicSelectionSheet(
            title = stringResource(R.string.attach_album_title),
            options = buildList {
                add(com.klic.mobile.app.ui.components.KlicSheetOption("RECENTS", albumLabelValue(context, GalleryAlbum.RECENTS)))
                // IS_FAVORITE exists from API 30.
                if (Build.VERSION.SDK_INT >= 30) {
                    add(com.klic.mobile.app.ui.components.KlicSheetOption("FAVORITES", albumLabelValue(context, GalleryAlbum.FAVORITES)))
                }
                add(com.klic.mobile.app.ui.components.KlicSheetOption("VIDEOS", albumLabelValue(context, GalleryAlbum.VIDEOS)))
                add(com.klic.mobile.app.ui.components.KlicSheetOption("SELFIES", albumLabelValue(context, GalleryAlbum.SELFIES)))
            },
            selectedValue = album.name,
            onSelect = { value ->
                album = GalleryAlbum.valueOf(value)
                showAlbumSheet = false
            },
            onDismiss = { showAlbumSheet = false },
        )
    }
}

/** One gallery cell: thumbnail, video-duration pill, numbered selection badge. */
@Composable
private fun GalleryCell(
    item: GalleryItem,
    selectionIndex: Int,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle),
    ) {
        AsyncImage(
            model = item.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (item.isVideo) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    durationText(item.durationMs.toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
        // Numbered check badge — the number is the SEND ORDER (§11.2).
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(22.dp)
                .background(
                    if (selectionIndex >= 0) MaterialTheme.colorScheme.primary
                    else Color.Black.copy(alpha = 0.35f),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selectionIndex >= 0) {
                Text(
                    "${selectionIndex + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun TabCapsule(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(CircleShape)
            .background(
                if (active) MaterialTheme.colorScheme.surface else Color.Transparent,
                CircleShape,
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun albumLabel(album: GalleryAlbum): String = when (album) {
    GalleryAlbum.RECENTS -> stringResource(R.string.attach_album_recents)
    GalleryAlbum.FAVORITES -> stringResource(R.string.attach_album_favorites)
    GalleryAlbum.VIDEOS -> stringResource(R.string.attach_album_videos)
    GalleryAlbum.SELFIES -> stringResource(R.string.attach_album_selfies)
}

private fun albumLabelValue(context: Context, album: GalleryAlbum): String = when (album) {
    GalleryAlbum.RECENTS -> context.getString(R.string.attach_album_recents)
    GalleryAlbum.FAVORITES -> context.getString(R.string.attach_album_favorites)
    GalleryAlbum.VIDEOS -> context.getString(R.string.attach_album_videos)
    GalleryAlbum.SELFIES -> context.getString(R.string.attach_album_selfies)
}

private fun galleryPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

private fun hasGalleryPermission(context: Context): Boolean =
    galleryPermissions().any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

/** MediaStore query, newest first, capped at 300 rows. */
private fun queryGallery(context: Context, album: GalleryAlbum): List<GalleryItem> {
    val collection = MediaStore.Files.getContentUri("external")
    val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.Files.FileColumns.DATE_ADDED,
        MediaStore.Video.Media.DURATION,
        MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
    )
    val mediaTypes = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN " +
        "(${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
    val selection = when (album) {
        GalleryAlbum.RECENTS -> mediaTypes
        GalleryAlbum.VIDEOS ->
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}"
        GalleryAlbum.FAVORITES ->
            if (Build.VERSION.SDK_INT >= 30) "$mediaTypes AND ${MediaStore.MediaColumns.IS_FAVORITE} = 1"
            else mediaTypes
        GalleryAlbum.SELFIES ->
            "$mediaTypes AND ${MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME} LIKE '%selfie%'"
    }
    val result = mutableListOf<GalleryItem>()
    runCatching {
        context.contentResolver.query(
            collection,
            projection,
            selection,
            null,
            "${MediaStore.Files.FileColumns.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val typeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val durCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
            while (cursor.moveToNext() && result.size < 300) {
                val id = cursor.getLong(idCol)
                val isVideo = cursor.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val uri = if (isVideo) {
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                } else {
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                }
                result += GalleryItem(
                    uri = uri,
                    isVideo = isVideo,
                    durationMs = if (durCol >= 0) cursor.getLong(durCol) else 0L,
                )
            }
        }
    }
    return result
}
