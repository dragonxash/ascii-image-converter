package com.dragonxash.asciiconverter.core

import android.content.Context
import android.net.Uri
import androidx.annotation.WorkerThread
import java.io.File
import java.io.IOException

/**
 * 外部图片源的本地中转站。
 *
 * ## 为什么必须有这一层
 *
 * 相册（照片选择器）给的 `content://media/picker/...` 和文件选择器（SAF）给的
 * `content://com.android.externalstorage.documents/...` **完全不是一回事**：
 *
 * - 文件选择器那条路，App 拿的是**可持久化**的读权限，同一个 Uri 想开几次开几次；
 * - 相册那条路，权限是**临时**的、跟着 provider 的心情走。同一个 Uri 反复开，
 *   开着开着就 `open failed: ENOENT` 了。
 *
 * 之前的写法是每次「转换」都拿原始 Uri 重新解码一次，于是：
 * 换样式重转一次、拖一下滑杆重转一次……每转一次就多开一次相册的 Uri。
 * 文件选择器经得起这么折腾，相册不经——这就是"文件浏览器没事、系统相册报错"的根源。
 *
 * 现在改规矩：**选完图立刻读一次、落到本地，之后一律读本地文件**。
 * 相册的 Uri 全程只被碰一次，provider 再小气也只打扰它一次。
 *
 * 文件放在 `cacheDir` 下：系统空间紧张时自己会清，不占用户的"应用数据"。
 * 保命规则是"最多只留最近两个"——留一个上一张做缓冲，
 * 避免正在解码的文件被下一次选图顺手删掉。
 */
object SourceCache {

    private const val DIR_NAME = "source"
    private const val PREFIX = "src_"

    /** 冷启动清理的年龄阈值：超过这个时间的残留源图直接删。 */
    private const val STALE_AGE_MS = 24L * 60 * 60 * 1000

    /** 最近一次成功落到本地的源图，用来避免刚被下一个人删掉。 */
    @Volatile
    private var current: File? = null

    fun dir(context: Context): File = File(context.cacheDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    /**
     * 把 [uri] 完整复制到本地，返回本地文件。**调用方拿到的这就是唯一数据源了。**
     *
     * 阻塞直到读完（含 [ContentStreams] 里的重试等待），必须在 IO 线程调用。
     *
     * @throws IOException 所有方式、所有重试都失败时抛出，消息里带 Uri 和探测结果。
     */
    @WorkerThread
    @Throws(IOException::class)
    fun materialize(context: Context, uri: Uri): File {
        val target = File(
            dir(context),
            PREFIX + System.currentTimeMillis() + "_" + (System.nanoTime() % 100_000L) + ".tmp"
        )
        return ContentStreams.copyTo(context, uri, target)
    }

    /**
     * 只保留 [keep]（以及上一张做缓冲），其余删掉。**每次选完图调用一次。**
     *
     * 之所以连上一张也留着：新图正在解码时旧图可能还没读完，
     * 立刻删会让上一个协程拿到"文件不存在"，白弹一次错误框。
     */
    fun keepOnly(context: Context, keep: File?) {
        val survivors = setOfNotNull(keep, current).mapTo(mutableSetOf()) { it.absolutePath }
        current = keep
        dir(context).listFiles()?.forEach { file ->
            if (file.absolutePath !in survivors) {
                runCatching { file.delete() }
            }
        }
    }

    /**
     * 冷启动清理上次留下的残留（崩溃、被系统杀掉都会留）。
     *
     * 按年龄筛，所以刚刚那份"界面重建时要恢复的源图"不会被误伤。
     */
    fun sweepStale(context: Context) {
        val deadline = System.currentTimeMillis() - STALE_AGE_MS
        dir(context).listFiles()?.forEach { file ->
            if (file.lastModified() < deadline) {
                runCatching { file.delete() }
            }
        }
    }

    /** 全部清掉（批量页退出时用）。 */
    fun clear(context: Context) {
        current = null
        dir(context).listFiles()?.forEach { runCatching { it.delete() } }
    }

    /**
     * @return 保存下来的路径是否还能用，能用就返回 [File]，否则 null。
     *
     * 界面重建（切语言、旋转）要靠它把图接回来，所以这里只认文件、不认 Uri：
     * 相册的 Uri 活不过进程重建，拿它去读只会白等一轮重试再报错。
     */
    fun existing(path: String?): File? {
        val file = path?.let { File(it) } ?: return null
        return if (file.isFile && file.length() > 0L) file else null
    }
}
