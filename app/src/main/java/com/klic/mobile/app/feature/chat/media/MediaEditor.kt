package com.klic.mobile.app.feature.chat.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.klic.mobile.app.R
import com.klic.mobile.app.data.SettingsStore
import java.io.ByteArrayOutputStream
import java.util.UUID

// ── Editor state types ──────────────────────────────────────────────────────

private enum class EditorTool { NONE, DRAW, TEXT, CROP, QUALITY }

/** One freehand stroke, points normalized to the displayed image (0..1). */
private data class EditorStroke(val points: List<Offset>, val color: Color)

/** One draggable/scalable text overlay, position normalized (0..1). */
private data class EditorText(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val color: Color,
    val position: Offset = Offset(0.5f, 0.5f),
    val scale: Float = 1f,
)

private val editorPalette = listOf(
    Color.White, Color.Black, Color(0xFFE5484D), Color(0xFFFF8C00),
    Color(0xFFFFD60A), Color(0xFF2ECC71), Color(0xFF3B82F6), Color(0xFFB755F0),
)

/**
 * Pre-send media editor (§10.9): caption + Draw / Text / Crop-rotate / Quality for
 * images (Compose Canvas overlays, flattened into the upload bytes); caption +
 * quality only for videos.
 */
@Composable
fun MediaEditorDialog(
    draft: PendingMediaDraft,
    initialCaption: String,
    onDone: (PendingMediaDraft, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val isImage = !draft.isVideo && draft.attachment.localBytes != null
    val source = remember(draft.id) {
        draft.attachment.localBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            ?: draft.previewBitmap
    }

    var tool by remember { mutableStateOf(EditorTool.NONE) }
    var caption by remember { mutableStateOf(initialCaption) }
    var strokes by remember { mutableStateOf(listOf<EditorStroke>()) }
    var activeStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var texts by remember { mutableStateOf(listOf<EditorText>()) }
    var drawColor by remember { mutableStateOf(editorPalette[2]) }
    var rotation by remember { mutableStateOf(0) }        // quarter turns
    var cropAspect by remember { mutableStateOf<Float?>(null) }  // null = free/original
    var hd by remember { mutableStateOf(SettingsStore.snapshot.value.uploadHd) }
    var editingText by remember { mutableStateOf<EditorText?>(null) }
    var addingText by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Working bitmap = source with rotation + crop applied (overlays draw on top).
    val working = remember(source, rotation, cropAspect) {
        source?.let { transformBitmap(it, rotation, cropAspect) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            // Top bar: close + tools + done.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_cancel), tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                if (isImage) {
                    EditorToolButton(Icons.Filled.Draw, tool == EditorTool.DRAW) {
                        tool = if (tool == EditorTool.DRAW) EditorTool.NONE else EditorTool.DRAW
                    }
                    EditorToolButton(Icons.Filled.Title, tool == EditorTool.TEXT) {
                        tool = EditorTool.TEXT
                        addingText = true
                    }
                    EditorToolButton(Icons.Filled.Crop, tool == EditorTool.CROP) {
                        tool = if (tool == EditorTool.CROP) EditorTool.NONE else EditorTool.CROP
                    }
                }
                EditorToolButton(Icons.Filled.HighQuality, tool == EditorTool.QUALITY) {
                    tool = if (tool == EditorTool.QUALITY) EditorTool.NONE else EditorTool.QUALITY
                }
                IconButton(onClick = {
                    if (isImage && working != null) {
                        val flattened = flatten(working, strokes, texts, hd)
                        onDone(
                            draft.copy(
                                previewUri = null,
                                previewBitmap = flattened.bitmap,
                                attachment = draft.attachment.copy(
                                    byteSize = flattened.bytes.size,
                                    width = flattened.bitmap.width,
                                    height = flattened.bitmap.height,
                                    contentType = "image/jpeg",
                                    localBytes = flattened.bytes,
                                ),
                            ),
                            caption,
                        )
                    } else {
                        onDone(draft, caption)
                    }
                }) {
                    Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.common_done), tint = Color.White)
                }
            }

            // Canvas area.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (working != null) {
                    val ratio = working.width.toFloat() / working.height.toFloat()
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(ratio)
                            .clipToBounds()
                            .onGloballyPositioned { canvasSize = it.size },
                    ) {
                        Image(
                            bitmap = working.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize(),
                        )
                        // Freehand strokes overlay.
                        androidx.compose.foundation.Canvas(
                            Modifier
                                .fillMaxSize()
                                .let { base ->
                                    if (isImage && tool == EditorTool.DRAW) {
                                        base.pointerInput(drawColor) {
                                            detectDragGestures(
                                                onDragStart = { start ->
                                                    activeStroke = listOf(normalize(start, canvasSize))
                                                },
                                                onDrag = { change, _ ->
                                                    activeStroke = activeStroke + normalize(change.position, canvasSize)
                                                },
                                                onDragEnd = {
                                                    if (activeStroke.size > 1) {
                                                        strokes = strokes + EditorStroke(activeStroke, drawColor)
                                                    }
                                                    activeStroke = emptyList()
                                                },
                                            )
                                        }
                                    } else base
                                },
                        ) {
                            (strokes + listOfNotNull(
                                activeStroke.takeIf { it.size > 1 }?.let { EditorStroke(it, drawColor) },
                            )).forEach { stroke ->
                                val path = Path()
                                stroke.points.forEachIndexed { index, p ->
                                    val x = p.x * size.width
                                    val y = p.y * size.height
                                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                }
                                drawPath(
                                    path,
                                    color = stroke.color,
                                    style = Stroke(
                                        width = size.width * 0.012f,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round,
                                    ),
                                )
                            }
                        }
                        // Text overlays — drag to move, pinch to scale, tap to edit.
                        texts.forEach { overlay ->
                            androidx.compose.runtime.key(overlay.id) {
                                TextOverlayView(
                                    overlay = overlay,
                                    canvasSize = canvasSize,
                                    onUpdate = { updated ->
                                        texts = texts.map { if (it.id == updated.id) updated else it }
                                    },
                                    onEdit = { editingText = overlay },
                                )
                            }
                        }
                    }
                } else if (draft.previewBitmap != null) {
                    Image(
                        bitmap = draft.previewBitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // Tool strips.
            when (tool) {
                EditorTool.DRAW -> Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    editorPalette.forEach { color ->
                        Box(
                            Modifier
                                .size(if (color == drawColor) 30.dp else 24.dp)
                                .background(color, CircleShape)
                                .clickable { drawColor = color },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { strokes = strokes.dropLast(1) }, enabled = strokes.isNotEmpty()) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            contentDescription = stringResource(R.string.editor_undo),
                            tint = if (strokes.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.35f),
                        )
                    }
                }
                EditorTool.CROP -> Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CropCapsule(stringResource(R.string.editor_crop_free), cropAspect == null) { cropAspect = null }
                    CropCapsule("1:1", cropAspect == 1f) { cropAspect = 1f }
                    CropCapsule("4:5", cropAspect == 0.8f) { cropAspect = 0.8f }
                    CropCapsule("16:9", cropAspect == 16f / 9f) { cropAspect = 16f / 9f }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { rotation = (rotation + 1) % 4 }) {
                        Icon(
                            Icons.Filled.RotateRight,
                            contentDescription = stringResource(R.string.editor_rotate),
                            tint = Color.White,
                        )
                    }
                }
                EditorTool.QUALITY -> Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CropCapsule(stringResource(R.string.editor_quality_hd), hd) { hd = true }
                    CropCapsule(stringResource(R.string.editor_quality_standard), !hd) { hd = false }
                }
                else -> Unit
            }

            // Caption field.
            TextField(
                value = caption,
                onValueChange = { caption = it },
                placeholder = {
                    Text(stringResource(R.string.editor_add_caption), color = Color.White.copy(alpha = 0.5f))
                },
                maxLines = 3,
                shape = CircleShape,
                keyboardOptions = KeyboardOptions.Default,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.White.copy(alpha = 0.12f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.12f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Color.White,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }

    // Add/edit text overlay dialog.
    if (addingText || editingText != null) {
        var value by remember(editingText?.id) { mutableStateOf(editingText?.text.orEmpty()) }
        var color by remember(editingText?.id) { mutableStateOf(editingText?.color ?: Color.White) }
        AlertDialog(
            onDismissRequest = { addingText = false; editingText = null },
            title = { Text(stringResource(R.string.editor_text)) },
            text = {
                Column {
                    TextField(value = value, onValueChange = { value = it }, singleLine = true)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        editorPalette.take(6).forEach { c ->
                            Box(
                                Modifier
                                    .size(if (c == color) 28.dp else 22.dp)
                                    .background(c, CircleShape)
                                    .clickable { color = c },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val editing = editingText
                    if (value.isNotBlank()) {
                        texts = if (editing != null) {
                            texts.map { if (it.id == editing.id) it.copy(text = value, color = color) else it }
                        } else {
                            texts + EditorText(text = value, color = color)
                        }
                    } else if (editing != null) {
                        texts = texts.filterNot { it.id == editing.id }
                    }
                    addingText = false
                    editingText = null
                }) { Text(stringResource(R.string.common_done)) }
            },
            dismissButton = {
                TextButton(onClick = { addingText = false; editingText = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun TextOverlayView(
    overlay: EditorText,
    canvasSize: IntSize,
    onUpdate: (EditorText) -> Unit,
    onEdit: () -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val xDp = with(density) { (overlay.position.x * canvasSize.width).toDp() }
    val yDp = with(density) { (overlay.position.y * canvasSize.height).toDp() }
    Box(
        Modifier
            .padding(start = (xDp - 60.dp).coerceAtLeast(0.dp), top = (yDp - 20.dp).coerceAtLeast(0.dp))
            .pointerInput(overlay.id) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (canvasSize.width == 0 || canvasSize.height == 0) return@detectTransformGestures
                    onUpdate(
                        overlay.copy(
                            position = Offset(
                                (overlay.position.x + pan.x / canvasSize.width).coerceIn(0f, 1f),
                                (overlay.position.y + pan.y / canvasSize.height).coerceIn(0f, 1f),
                            ),
                            scale = (overlay.scale * zoom).coerceIn(0.4f, 4f),
                        ),
                    )
                }
            }
            .clickable(onClick = onEdit),
    ) {
        Text(
            overlay.text,
            color = overlay.color,
            fontSize = (26 * overlay.scale).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun EditorToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (active) MaterialTheme.colorScheme.primary else Color.White,
        )
    }
}

@Composable
private fun CropCapsule(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(
                if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.14f),
                CircleShape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.onPrimary else Color.White,
        )
    }
}

// ── Bitmap operations ───────────────────────────────────────────────────────

private fun normalize(point: Offset, size: IntSize): Offset =
    if (size.width == 0 || size.height == 0) Offset.Zero
    else Offset(
        (point.x / size.width).coerceIn(0f, 1f),
        (point.y / size.height).coerceIn(0f, 1f),
    )

/** Rotation (90° steps) + center-crop to an aspect preset. */
private fun transformBitmap(source: Bitmap, quarterTurns: Int, aspect: Float?): Bitmap {
    var bitmap = source
    if (quarterTurns % 4 != 0) {
        val matrix = Matrix().apply { postRotate(90f * (quarterTurns % 4)) }
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
    if (aspect != null && bitmap.height > 0) {
        val current = bitmap.width.toFloat() / bitmap.height
        if (kotlin.math.abs(current - aspect) > 0.01f) {
            bitmap = if (current > aspect) {
                val newWidth = (bitmap.height * aspect).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(bitmap, (bitmap.width - newWidth) / 2, 0, newWidth, bitmap.height)
            } else {
                val newHeight = (bitmap.width / aspect).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(bitmap, 0, (bitmap.height - newHeight) / 2, bitmap.width, newHeight)
            }
        }
    }
    return bitmap
}

private class FlattenResult(val bitmap: Bitmap, val bytes: ByteArray)

/** Bakes strokes + text overlays into the bitmap and re-encodes per the quality pick. */
private fun flatten(
    working: Bitmap,
    strokes: List<EditorStroke>,
    texts: List<EditorText>,
    hd: Boolean,
): FlattenResult {
    val out = working.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(out)
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = out.width * 0.012f
    }
    strokes.forEach { stroke ->
        strokePaint.color = stroke.color.toArgb()
        val path = android.graphics.Path()
        stroke.points.forEachIndexed { index, p ->
            val x = p.x * out.width
            val y = p.y * out.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, strokePaint)
    }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setShadowLayer(4f, 0f, 2f, android.graphics.Color.argb(120, 0, 0, 0))
    }
    texts.forEach { overlay ->
        textPaint.color = overlay.color.toArgb()
        textPaint.textSize = out.width * 0.07f * overlay.scale
        canvas.drawText(
            overlay.text,
            (overlay.position.x * out.width - textPaint.measureText(overlay.text) / 2f)
                .coerceIn(0f, out.width.toFloat()),
            overlay.position.y * out.height,
            textPaint,
        )
    }
    val bytes = ByteArrayOutputStream().use { stream ->
        out.compress(Bitmap.CompressFormat.JPEG, if (hd) 95 else 85, stream)
        stream.toByteArray()
    }
    return FlattenResult(out, bytes)
}
