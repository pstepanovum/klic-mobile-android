package com.klic.mobile.app.feature.chat.composer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.klic.mobile.app.R
import com.klic.mobile.app.ui.theme.KlicIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

// ─────────────────────────────────────────────────────────
// §11.2: live camera tile in the attachment sheet + full in-app capture
// ─────────────────────────────────────────────────────────

/**
 * The attach sheet's camera tile: a LIVE CameraX preview (WhatsApp-style) when the
 * camera permission is granted; a static camera-icon tile that requests permission
 * on tap otherwise. Tapping the live preview opens the full capture screen.
 */
@Composable
fun CameraPreviewTile(modifier: Modifier = Modifier, onOpenCamera: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    Box(
        modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable {
                if (hasPermission) onOpenCamera()
                else permissionLauncher.launch(Manifest.permission.CAMERA)
            },
        contentAlignment = Alignment.Center,
    ) {
        if (hasPermission) {
            val lifecycleOwner = LocalLifecycleOwner.current
            val previewView = remember {
                PreviewView(context).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
            }
            DisposableEffect(lifecycleOwner) {
                var provider: ProcessCameraProvider? = null
                var preview: Preview? = null
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    runCatching {
                        provider = future.get()
                        val useCase = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        preview = useCase
                        provider?.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            useCase,
                        )
                    }
                }, ContextCompat.getMainExecutor(context))
                onDispose {
                    // Unbind only OUR use case — the full capture screen binds its own.
                    runCatching { preview?.let { provider?.unbind(it) } }
                }
            }
            AndroidView({ previewView }, Modifier.fillMaxSize())
            // Camera glyph in a dark pill so the tile reads as "camera" at a glance.
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .size(26.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(KlicIcons.camera),
                    contentDescription = stringResource(R.string.attach_camera),
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        } else {
            Icon(
                painter = painterResource(KlicIcons.camera),
                contentDescription = stringResource(R.string.attach_camera),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/**
 * Full in-app capture (§11.2): CameraX preview with a shutter that TAPS a photo and
 * HOLDS a video (release to stop), plus lens flip. Captured media lands in the same
 * pre-send flow as gallery picks via [onCaptured] (uri, isVideo).
 */
@Composable
fun KlicCameraCapture(
    onCaptured: (Uri, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var recordStartMs by remember { mutableLongStateOf(0L) }
    var elapsedText by remember { mutableStateOf("0:00") }
    var finished by remember { mutableStateOf(false) }

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    val videoCapture = remember {
        VideoCapture.withOutput(
            Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.FHD,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                    )
                )
                .build()
        )
    }

    DisposableEffect(lensFacing) {
        var provider: ProcessCameraProvider? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                provider?.unbindAll()
                provider?.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                    preview,
                    imageCapture,
                    videoCapture,
                )
            }.onFailure {
                Toast.makeText(context, context.getString(R.string.camera_unavailable), Toast.LENGTH_LONG).show()
                onClose()
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { recording?.stop() }
            runCatching { provider?.unbindAll() }
        }
    }

    // Live "0:42" while recording.
    LaunchedEffect(recording) {
        while (recording != null) {
            val s = ((System.currentTimeMillis() - recordStartMs) / 1000).toInt()
            elapsedText = "%d:%02d".format(s / 60, s % 60)
            delay(250)
        }
        elapsedText = "0:00"
    }

    fun takePhoto() {
        if (finished) return
        val file = File.createTempFile("klic_cam_", ".jpg", context.cacheDir)
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    if (finished) return
                    finished = true
                    onCaptured(shareableUri(context, file), false)
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(context, context.getString(R.string.camera_capture_failed), Toast.LENGTH_LONG).show()
                }
            },
        )
    }

    fun startVideo() {
        if (recording != null || finished) return
        val file = File.createTempFile("klic_cam_", ".mp4", context.cacheDir)
        val pending = videoCapture.output
            .prepareRecording(context, FileOutputOptions.Builder(file).build())
            .apply {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    withAudioEnabled()
                }
            }
        recordStartMs = System.currentTimeMillis()
        recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                recording = null
                when {
                    finished -> Unit
                    !event.hasError() -> {
                        finished = true
                        onCaptured(shareableUri(context, file), true)
                    }
                    else -> Toast.makeText(
                        context, context.getString(R.string.camera_capture_failed), Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    fun stopVideo() {
        recording?.stop()
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView({ previewView }, Modifier.fillMaxSize())

            // Top bar: close + recording timer.
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onClose,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.4f),
                        contentColor = Color.White,
                    ),
                ) { Icon(painter = androidx.compose.ui.res.painterResource(com.klic.mobile.app.ui.theme.KlicIcons.close), contentDescription = stringResource(R.string.common_close), modifier = Modifier.size(22.dp)) }
                Spacer(Modifier.weight(1f))
                if (recording != null) {
                    Row(
                        Modifier
                            .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.size(8.dp).background(Color.Red, CircleShape))
                        Text(elapsedText, color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            // Bottom controls: hint, shutter (tap photo / hold video), flip.
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.camera_hint),
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.size(56.dp))
                    Spacer(Modifier.size(28.dp))
                    ShutterButton(
                        isRecording = recording != null,
                        onTap = ::takePhoto,
                        onHoldStart = ::startVideo,
                        onHoldEnd = ::stopVideo,
                    )
                    Spacer(Modifier.size(28.dp))
                    IconButton(
                        onClick = {
                            lensFacing =
                                if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
                                else CameraSelector.LENS_FACING_BACK
                        },
                        modifier = Modifier.size(56.dp),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.4f),
                            contentColor = Color.White,
                        ),
                    ) {
                        Icon(
                            Icons.Filled.FlipCameraAndroid,
                            contentDescription = stringResource(R.string.camera_flip),
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Ring shutter: quick tap → photo; press-and-hold → video, release stops. */
@Composable
private fun ShutterButton(
    isRecording: Boolean,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
) {
    val scale by animateFloatAsState(if (isRecording) 1.15f else 1f, label = "shutterScale")
    Box(
        Modifier
            .size(76.dp)
            .scale(scale)
            .border(4.dp, Color.White, CircleShape)
            .padding(7.dp)
            .background(if (isRecording) Color.Red else Color.White, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        // Still pressed after 250ms → video; otherwise a photo tap.
                        val releasedQuickly = withTimeoutOrNull(250L) { tryAwaitRelease() }
                        if (releasedQuickly == null) {
                            onHoldStart()
                            tryAwaitRelease()
                            onHoldEnd()
                        } else if (releasedQuickly) {
                            onTap()
                        }
                    },
                )
            },
    )
}

private fun shareableUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
