package com.klic.mobile.app.ui.components

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.klic.mobile.app.R
import com.klic.mobile.app.data.ImageUploads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/** Mask shape for [ImageAdjustSheet]: circle for profile pictures, rounded square for group covers. */
enum class AdjustMask { CIRCLE, ROUNDED_SQUARE }

/**
 * §11.5 image-adjust step: pinch-zoom + drag the picked photo inside a circular
 * (profile) or rounded-square (group cover) mask, then crop to a square bitmap
 * that feeds the EXISTING upload flow. Full-screen, Klic-styled.
 */
@Composable
fun ImageAdjustSheet(
    uri: Uri,
    mask: AdjustMask,
    onDone: (Bitmap) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }

    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) { ImageUploads.decodeForAdjust(context, uri) }
        if (bitmap == null) failed = true
    }
    // Unreadable photo — bail out with the standard error path.
    LaunchedEffect(failed) { if (failed) onDismiss() }

    // Captured in the host (edge-to-edge) composition. Reading window insets directly inside the
    // Dialog below can resolve to zero on some devices, which would let the action buttons slide
    // under the system navigation bar; sourcing them here keeps the controls clear of it.
    val systemBarsInsets = WindowInsets.systemBars.asPaddingValues()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val bmp = bitmap
            if (bmp == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
            } else {
                AdjustCanvas(bmp = bmp, mask = mask, insets = systemBarsInsets, onDone = onDone, onDismiss = onDismiss)
            }
        }
    }
}

@Composable
private fun AdjustCanvas(
    bmp: Bitmap,
    mask: AdjustMask,
    insets: PaddingValues,
    onDone: (Bitmap) -> Unit,
    onDismiss: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val viewW = with(density) { maxWidth.toPx() }
        val viewH = with(density) { maxHeight.toPx() }
        // Mask window: a centered square inset from the edges.
        val maskSide = min(viewW, viewH) - with(density) { 48.dp.toPx() }

        // Base scale renders the image covering the mask at zoom = 1.
        val baseScale = maskSide / min(bmp.width, bmp.height).toFloat()
        var zoom by remember(bmp) { mutableFloatStateOf(1f) }
        var offset by remember(bmp) { mutableStateOf(Offset.Zero) }

        fun clampOffset(candidate: Offset, atZoom: Float): Offset {
            val s = baseScale * atZoom
            val maxX = max(0f, (bmp.width * s - maskSide) / 2f)
            val maxY = max(0f, (bmp.height * s - maskSide) / 2f)
            return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(bmp) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        val newZoom = (zoom * gestureZoom).coerceIn(1f, 6f)
                        // Keep the pan feeling 1:1 while re-clamping against the new zoom.
                        val scaled = offset * (newZoom / zoom) + pan
                        zoom = newZoom
                        offset = clampOffset(scaled, newZoom)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // requiredSize lays the bitmap out at EXACTLY its pixel size (ignoring the
            // parent constraints), centered — so the crop math below is 1:1 with pixels.
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .requiredSize(
                        with(density) { bmp.width.toDp() },
                        with(density) { bmp.height.toDp() },
                    )
                    .graphicsLayer {
                        scaleX = baseScale * zoom
                        scaleY = baseScale * zoom
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
            // Dim everything outside the mask window; stroke the window edge.
            androidx.compose.foundation.Canvas(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
            ) {
                val topLeft = Offset((size.width - maskSide) / 2f, (size.height - maskSide) / 2f)
                val window = Path().apply {
                    if (mask == AdjustMask.CIRCLE) {
                        addOval(Rect(topLeft, androidx.compose.ui.geometry.Size(maskSide, maskSide)))
                    } else {
                        addRoundRect(
                            RoundRect(
                                Rect(topLeft, androidx.compose.ui.geometry.Size(maskSide, maskSide)),
                                androidx.compose.ui.geometry.CornerRadius(maskSide * 0.12f),
                            )
                        )
                    }
                }
                val full = Path().apply { addRect(Rect(Offset.Zero, size)) }
                drawPath(
                    Path.combine(PathOperation.Difference, full, window),
                    color = Color.Black.copy(alpha = 0.6f),
                )
                drawPath(window, color = Color.White.copy(alpha = 0.8f), style = Stroke(width = 1.dp.toPx()))
            }
        }

        // Header label.
        Text(
            stringResource(R.string.adjust_title),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = insets.calculateTopPadding())
                .padding(top = 18.dp),
        )

        // Capsule actions.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = insets.calculateBottomPadding())
                .padding(horizontal = 24.dp, vertical = 20.dp)
                .fillMaxWidth(),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.14f),
                        contentColor = Color.White,
                    ),
                ) { Text(stringResource(R.string.common_cancel), Modifier.padding(vertical = 6.dp)) }
                Button(
                    onClick = {
                        onDone(cropToSquare(bmp, baseScale * zoom, offset, maskSide))
                    },
                    modifier = Modifier.weight(1f),
                    shape = CircleShape,
                ) { Text(stringResource(R.string.adjust_use_photo), Modifier.padding(vertical = 6.dp)) }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** Extracts the mask window from [bmp] (rendered at [renderScale] with [offset]) into a square bitmap. */
private fun cropToSquare(
    bmp: Bitmap,
    renderScale: Float,
    offset: Offset,
    maskSide: Float,
    outputSide: Int = 1024,
): Bitmap {
    // The mask window's center sits at the image center shifted by -offset (screen px),
    // converted into bitmap px by dividing by the render scale.
    val visibleSide = maskSide / renderScale
    val centerX = bmp.width / 2f - offset.x / renderScale
    val centerY = bmp.height / 2f - offset.y / renderScale
    val srcLeft = centerX - visibleSide / 2f
    val srcTop = centerY - visibleSide / 2f

    val out = Bitmap.createBitmap(outputSide, outputSide, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    val scale = outputSide / visibleSide
    val matrix = Matrix().apply {
        postTranslate(-srcLeft, -srcTop)
        postScale(scale, scale)
    }
    canvas.drawBitmap(bmp, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    return out
}
