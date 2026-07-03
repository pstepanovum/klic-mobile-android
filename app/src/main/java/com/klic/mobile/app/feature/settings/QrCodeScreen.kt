package com.klic.mobile.app.feature.settings

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.klic.mobile.app.R
import com.klic.mobile.app.feature.KlicViewModel
import com.klic.mobile.app.ui.components.AvatarView
import java.io.File

/**
 * Settings → QR Code (§10.7): a card with the user's avatar/name and a locally
 * generated QR encoding https://klic.app/add/<username>, plus Scan (Google code
 * scanner) → add-friend flow, and Share (exports the QR bitmap).
 */
@Composable
fun QrCodeContent(vm: KlicViewModel) {
    val context = LocalContext.current
    val me by vm.currentUser.collectAsState()
    val friendStatus by vm.friendStatus.collectAsState()
    val username = me?.username.orEmpty()
    val link = "https://klic.app/add/$username"
    val qr = remember(link) { generateQr(link, 720) }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(28.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AvatarView(url = me?.avatarUrl, name = me?.displayName ?: "?", size = 72.dp)
            Spacer(Modifier.height(10.dp))
            Text(
                me?.displayName.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "@$username",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            qr?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = stringResource(R.string.qr_title),
                    modifier = Modifier
                        .size(240.dp)
                        .background(Color.White, RoundedCornerShape(18.dp))
                        .padding(12.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.qr_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(18.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { startScan(context, vm) },
                shape = CircleShape,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.qr_scan), modifier = Modifier.padding(vertical = 6.dp)) }
            Button(
                onClick = { qr?.let { shareQr(context, it, username) } },
                shape = CircleShape,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) { Text(stringResource(R.string.qr_share), modifier = Modifier.padding(vertical = 6.dp)) }
        }

        friendStatus?.let { status ->
            Spacer(Modifier.height(12.dp))
            Text(
                status,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Launches the Google code scanner and routes a Klic QR into the add-friend flow. */
private fun startScan(context: Context, vm: KlicViewModel) {
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()
    GmsBarcodeScanning.getClient(context, options).startScan()
        .addOnSuccessListener { barcode ->
            val username = parseKlicUsername(barcode.rawValue.orEmpty())
            if (username != null) {
                vm.addFriend(username)
            } else {
                Toast.makeText(context, context.getString(R.string.qr_not_klic), Toast.LENGTH_LONG).show()
            }
        }
        .addOnFailureListener {
            Toast.makeText(
                context,
                context.getString(R.string.qr_scan_unavailable, it.message ?: ""),
                Toast.LENGTH_LONG,
            ).show()
        }
}

/** Accepts https://klic.app/add/<username>, @username or a bare username. */
internal fun parseKlicUsername(raw: String): String? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    Regex("""^https?://klic\.app/add/([A-Za-z0-9_.\-]+)/?$""", RegexOption.IGNORE_CASE)
        .find(value)?.let { return it.groupValues[1].lowercase() }
    if (value.startsWith("@")) {
        return value.removePrefix("@").takeIf { it.matches(Regex("""[A-Za-z0-9_.\-]+""")) }?.lowercase()
    }
    return value.takeIf { it.matches(Regex("""[A-Za-z0-9_.\-]+""")) }?.lowercase()
}

/** Locally rendered QR bitmap (zxing) — no server round-trip. */
internal fun generateQr(content: String, sizePx: Int): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        sizePx,
        sizePx,
        mapOf(EncodeHintType.MARGIN to 1),
    )
    val pixels = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) {
        for (x in 0 until sizePx) {
            pixels[y * sizePx + x] = if (matrix.get(x, y)) android.graphics.Color.BLACK
                                     else android.graphics.Color.WHITE
        }
    }
    Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
}.getOrNull()

/** Exports the QR bitmap through the system share sheet. */
private fun shareQr(context: Context, bitmap: Bitmap, username: String) {
    runCatching {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "klic_qr_$username.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.qr_share_failed), Toast.LENGTH_SHORT).show()
    }
}
