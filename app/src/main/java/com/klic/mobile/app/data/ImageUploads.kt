package com.klic.mobile.app.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import kotlin.math.max
import kotlin.math.roundToInt

data class EncodedImage(
    val bytes: ByteArray,
    val contentType: String,
    val width: Int,
    val height: Int,
)

object ImageUploads {
    fun encodeImage(
        context: Context,
        uri: Uri,
        maxDimension: Int = 2048,
        quality: Int = 85,
    ): EncodedImage? {
        val bitmap = decodeBitmap(context.contentResolver, uri) ?: return null
        val scaled = scaleDown(bitmap, maxDimension)
        val bytes = java.io.ByteArrayOutputStream().use { out ->
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
            out.toByteArray()
        }
        val result = EncodedImage(
            bytes = bytes,
            contentType = "image/jpeg",
            width = scaled.width,
            height = scaled.height,
        )
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return result
    }

    fun encodeAvatar(
        context: Context,
        uri: Uri,
        maxDimension: Int = 2048,
        quality: Int = 85,
    ): EncodedImage? = encodeImage(context, uri, maxDimension, quality)

    /**
     * §11.5: decode a picked photo for the pinch-zoom adjust step. Forces a SOFTWARE
     * allocation (the crop draws it onto a software Canvas) and bounds it to 4096px.
     */
    fun decodeForAdjust(context: Context, uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                val maxSide = max(info.size.width, info.size.height)
                if (maxSide > 4096) decoder.setTargetSampleSize((maxSide / 4096f).roundToInt().coerceAtLeast(1))
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }
    }.getOrNull()

    /** §11.5: JPEG-encode an already-cropped bitmap (adjusted avatar / group cover). */
    fun encodeBitmap(bitmap: Bitmap, quality: Int = 85): EncodedImage? {
        val bytes = java.io.ByteArrayOutputStream().use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
            out.toByteArray()
        }
        return EncodedImage(
            bytes = bytes,
            contentType = "image/jpeg",
            width = bitmap.width,
            height = bitmap.height,
        )
    }

    private fun decodeBitmap(resolver: ContentResolver, uri: Uri): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(resolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.isMutableRequired = false
                val maxSide = max(info.size.width, info.size.height)
                if (maxSide > 4096) decoder.setTargetSampleSize((maxSide / 4096f).roundToInt().coerceAtLeast(1))
            }
        } else {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }

    private fun scaleDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val maxSide = max(bitmap.width, bitmap.height)
        if (maxSide <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / maxSide.toFloat()
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}
