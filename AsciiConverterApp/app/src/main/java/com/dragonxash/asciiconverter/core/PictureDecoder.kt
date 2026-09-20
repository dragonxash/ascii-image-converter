package com.dragonxash.asciiconverter.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.WorkerThread
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.math.max

/**
 * 把图片解码成可以逐像素读的 [Bitmap]，顺便把拍照/相册图片的 EXIF 方向掰正。
 *
 * ## 这里只认本地文件
 *
 * 相册/文件选择器给的 `content://` Uri 由 [SourceCache] 在**选图那一刻**只读一次、
 * 落到本地，之后所有环节（量尺寸、读 EXIF、解码、重新转换）都读那个本地文件。
 *
 * 早先这里接过 Uri：每次转换都拿原始 Uri 重新解码一遍，等于每次改参数都去开一次
 * 相册的 Uri。文件选择器（SAF）给的是可持久化权限，经得起；相册（照片选择器）
 * 给的是临时权限，开几次就 `ENOENT` 了——"文件浏览器正常、系统相册报错"就是这么来的。
 *
 * 所以这个类**故意不接受 Uri**，从类型上堵死"再回去读一次 provider"的可能。
 */
object PictureDecoder {

    /** 解码后的最长边上限，太大的图先降采样，避免爆内存。 */
    const val DEFAULT_MAX_SIZE = 2400

    /**
     * @return [file] 对应的图片，EXIF 方向已经应用过了（拍照结果和本地中转文件都用这个）。
     */
    @WorkerThread
    fun decode(file: File, maxSize: Int = DEFAULT_MAX_SIZE): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val rotation = readRotation { file.inputStream() }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxSize)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IOException("解码失败（不是图片或已损坏）：${file.name}")
        return applyRotation(bitmap, rotation)
    }

    /**
     * @return [uri] 的文件名，读不到就退回路径末段。
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

    // region 内部

    /**
     * 按最长边算出合适的 2 的幂降采样倍数。
     *
     * 条件是「缩放后仍然超过 [maxSize] 就再翻一倍」，所以退出时
     * `max(width, height) / sampleSize <= maxSize`，即最长边真的被压到 [maxSize] 以内。
     * （旧写法用的是 `/ (sampleSize * 2) >= maxSize`，等于提前一轮停手，
     * 4000px 的图经过它还是 4000px，等于没降采样。）
     */
    private fun sampleSizeFor(width: Int, height: Int, maxSize: Int): Int {
        if (width <= 0 || height <= 0 || maxSize <= 0) {
            return 1
        }
        var sampleSize = 1
        while (max(width, height) / sampleSize > maxSize) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /** 读 EXIF 方向，返回需要顺时针旋转的角度。 */
    private fun readRotation(openStream: () -> InputStream?): Int {
        return runCatching {
            openStream()?.use { stream ->
                when (
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        }.getOrDefault(0)
    }

    private fun applyRotation(bitmap: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) {
            return bitmap
        }
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
        if (rotated !== bitmap) {
            bitmap.recycle()
        }
        return rotated
    }

    // endregion
}
