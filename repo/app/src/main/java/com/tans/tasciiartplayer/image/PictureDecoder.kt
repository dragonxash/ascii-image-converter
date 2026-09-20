package com.tans.tasciiartplayer.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.WorkerThread
import kotlin.math.max

/**
 * Load a picture of a uri to a [Bitmap] which can be read pixel by pixel.
 */
object PictureDecoder {

    /** Max pixels of the decoded picture, bigger pictures are sampled down. */
    const val DEFAULT_MAX_SIZE = 2160

    /**
     * @return the picture of [uri], the exif orientation is already applied on Android 9+.
     */
    @WorkerThread
    fun decode(
        context: Context,
        uri: Uri,
        maxSize: Int = DEFAULT_MAX_SIZE
    ): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeByImageDecoder(context, uri, maxSize)
        } else {
            decodeByBitmapFactory(context, uri, maxSize)
        }
    }

    /**
     * @return the name of the file of [uri], null if it can not be read.
     */
    @WorkerThread
    fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) cursor.getString(index) else null
                    } else {
                        null
                    }
                }
        }.getOrNull() ?: uri.lastPathSegment
    }

    private fun decodeByImageDecoder(context: Context, uri: Uri, maxSize: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // The ascii art conversion reads the pixels of the bitmap, a hardware bitmap can not be read.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val size = info.size
            var sampleSize = 1
            while (size.width / (sampleSize * 2) >= maxSize || size.height / (sampleSize * 2) >= maxSize) {
                sampleSize *= 2
            }
            decoder.setTargetSampleSize(sampleSize)
        }
    }

    @Suppress("DEPRECATION")
    private fun decodeByBitmapFactory(context: Context, uri: Uri, maxSize: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= maxSize || bounds.outHeight / (sampleSize * 2) >= maxSize) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = max(1, sampleSize)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("Decode image fail: $uri")
    }

    /**
     * @return the rotation(0/90/180/270) of the picture of [uri], only used on Android 8 and lower.
     */
    @WorkerThread
    fun queryRotationDegrees(context: Context, uri: Uri): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return 0
        }
        return runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.ORIENTATION), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(MediaStore.Images.Media.ORIENTATION)
                        if (index >= 0) cursor.getInt(index) else 0
                    } else {
                        0
                    }
                } ?: 0
        }.getOrDefault(0)
    }
}
