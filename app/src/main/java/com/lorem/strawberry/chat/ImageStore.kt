package com.lorem.strawberry.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.lorem.strawberry.core.AppLogger
import com.lorem.strawberry.core.EncodedImage
import com.lorem.strawberry.core.ImageEncoder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Imports picked images into app storage (downscaled + re-encoded as JPEG so LLM
 * payloads stay small) and base64-encodes them for LLM requests.
 */
@Singleton
class ImageStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: AppLogger
) : ImageEncoder {

    companion object {
        private const val TAG = "ImageStore"
        private const val MAX_DIMENSION = 1280
        private const val JPEG_QUALITY = 85
    }

    private val imagesDir: File
        get() = File(context.filesDir, "chat_images").apply { mkdirs() }

    /**
     * Copy the picked image into app storage, downscaled. Returns the absolute path,
     * or null if the image couldn't be read.
     */
    suspend fun importImage(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            // First pass: bounds only, to pick a sample size without loading full pixels
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            } ?: return@withContext null

            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_DIMENSION) {
                sample *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: return@withContext null

            val scaled = scaleDown(bitmap)
            val file = File(imagesDir, "img_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (scaled !== bitmap) bitmap.recycle()
            scaled.recycle()

            logger.d(TAG, "Imported image: ${file.name} (${file.length() / 1024} KB)")
            file.absolutePath
        } catch (e: Exception) {
            logger.e(TAG, "Failed to import image", e)
            null
        }
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val largest = max(bitmap.width, bitmap.height)
        if (largest <= MAX_DIMENSION) return bitmap
        val scale = MAX_DIMENSION.toFloat() / largest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(),
            true
        )
    }

    override fun encode(path: String): EncodedImage? = try {
        val bytes = File(path).readBytes()
        EncodedImage(
            mimeType = "image/jpeg",
            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        )
    } catch (e: Exception) {
        logger.e(TAG, "Failed to encode image $path", e)
        null
    }
}
