package com.dragonxash.asciiconverter.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * 批量列表用的缩略图加载器。
 *
 * 为了不把 Glide 这种大块头拉进来，这里就一个 LRU 缓存 + 按需降采样解码，够用且够小。
 *
 * 和 [PictureDecoder] 一样，这里**只认本地文件**——批量页也是先让 [SourceCache]
 * 把每张图落到本地再取缩略图。原因见 [SourceCache] 的说明：
 * 相册给的 Uri 每多开一次就多一次 `ENOENT` 的机会，而列表滚动会把每一项
 * 反复重新绑定，等于反复开流，是最容易把相册 provider 惹毛的地方。
 */
object ThumbnailLoader {

    /** 缓存上限，按像素占用量算，大约 16MB。 */
    private const val MAX_CACHE_BYTES = 16 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * @return [file] 的缩略图，最长边不超过 [maxSizePx]，失败返回 null。
     */
    suspend fun load(file: File, maxSizePx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "${file.absolutePath}@$maxSizePx"
        cache.get(key)?.let { return@withContext it }
        val bitmap = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return@runCatching null
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxSizePx)
                // 缩略图用 RGB_565 省一半内存，肉眼看不出差别
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
        }.getOrNull()
        if (bitmap != null) {
            cache.put(key, bitmap)
        }
        bitmap
    }

    /**
     * 按最长边算出 2 的幂降采样倍数。
     *
     * 退出时保证 `max(width, height) / sampleSize <= maxSizePx`。
     * （旧写法是 `/ (sampleSize * 2) >= maxSizePx`，等于提前一轮停手，
     * 结果缩略图比目标大一倍，白占一倍内存。）
     */
    private fun sampleSizeFor(width: Int, height: Int, maxSizePx: Int): Int {
        if (width <= 0 || height <= 0 || maxSizePx <= 0) {
            return 1
        }
        var sampleSize = 1
        while (max(width, height) / sampleSize > maxSizePx) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /** 清空缓存，退出批量页时调用。 */
    fun clear() {
        cache.evictAll()
    }
}
