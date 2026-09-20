package com.dragonxash.asciiconverter.core

import android.content.ContentUris
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * 读别的应用给的 `content://` 数据。
 *
 * 相册、文件选择器给的 Uri，数据流能不能开、能开几次，**完全取决于对面那个 provider**。
 * 对同一个 Uri 反复 `openInputStream` 是最容易踩的坑：常见表现是
 * `open failed: ENOENT (No such file or directory)`——看着像文件不存在，
 * 其实是 provider 拒绝了。
 *
 * 所以这里统一三件事：
 * 1. 备了好几种打开方式（含相册照片选择器专用的兜底），一种不行换下一种；
 * 2. 失败的会**等一会儿再整体重试**——云端图第一次打开往往只是触发下载，
 *    下载完再开就成了，立刻放弃是最亏的；
 * 3. 全失败时抛出带 Uri、每种方式失败原因、以及 provider 元信息探测结果的异常，
 *    方便直接定位到底是"云端没下下来"还是"权限被拒"。
 *
 * 调用方记住一条就够了：**要么一次性读完，要么落到本地再用**，别反复去开流。
 * 需要落本地请用 [SourceCache]，它把"什么时候读一次、什么时候删"这套规矩也管了。
 */
object ContentStreams {

    private const val TAG = "ContentStreams"

    /**
     * 整体重试的等待节奏（毫秒）。
     *
     * 长度就是额外重试次数：默认第一轮失败后再来 3 轮，共 4 轮、累计约 4 秒。
     * 之所以敢等，是因为云端图的首轮失败几乎都是"正在下载"，等它有实际收益；
     * 而纯权限问题会被识别出来直接放弃，不会白等。
     */
    private val RETRY_DELAYS_MS = longArrayOf(400L, 1_200L, 2_400L)

    /** 一种打开数据流的写法。 */
    private class Strategy(val name: String, val open: () -> InputStream)

    /** 一轮里所有方式的失败记录，外加"还值不值得再试"的判断依据。 */
    private class Attempts {
        val failures = LinkedHashMap<String, String>()
        private var permissionOnly = true

        fun fail(name: String, cause: Throwable) {
            failures.putIfAbsent(name, "${cause.javaClass.simpleName}: ${cause.message}")
            if (cause !is SecurityException) {
                permissionOnly = false
            }
        }

        /** 全是权限问题就没必要重试了——再等多久都还是被拒。 */
        fun worthRetrying(): Boolean = !permissionOnly
    }

    /**
     * @return [uri] 的数据流，调用方负责关闭。
     * @throws IOException 所有方式都失败时抛出。
     */
    @Throws(IOException::class)
    fun open(context: Context, uri: Uri): InputStream {
        val attempts = Attempts()
        var stream: InputStream? = null
        // closeStream = false：这条流是要交回给调用方的，不能在这里被 use{} 关掉
        val opened = runAll(context, uri, attempts, closeStream = false) { _, input ->
            stream = input
            true
        }
        if (opened) {
            return stream ?: throw IOException("打开成功却拿不到数据流：$uri")
        }
        throw failure("打不开数据流", context, uri, attempts)
    }

    /**
     * 把 [uri] 的内容完整复制到 [target]。
     *
     * @return [target]，方便链式使用。
     */
    @Throws(IOException::class)
    fun copyTo(context: Context, uri: Uri, target: File): File {
        val attempts = Attempts()
        val copied = runAll(context, uri, attempts) { name, input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
            if (target.length() > 0L) {
                Log.i(TAG, "通过「$name」读到 $uri（${target.length()} 字节）")
                true
            } else {
                attempts.fail(name, IOException("读到 0 字节"))
                runCatching { target.delete() }
                false
            }
        }
        if (copied) {
            return target
        }
        runCatching { target.delete() }
        throw failure("读不到数据", context, uri, attempts)
    }

    /**
     * @return [uri] 的全部字节，上限 [maxBytes]（超过就截断，防止误读超大文件把内存撑爆）。
     */
    @Throws(IOException::class)
    fun readBytes(context: Context, uri: Uri, maxBytes: Int = DEFAULT_MAX_BYTES): ByteArray {
        val attempts = Attempts()
        var bytes: ByteArray? = null
        if (runAll(context, uri, attempts) { _, input ->
                bytes = input.readAtMost(maxBytes)
                true
            }
        ) {
            return bytes ?: ByteArray(0)
        }
        throw failure("读不到数据", context, uri, attempts)
    }

    /**
     * 按文本读取 [uri]。
     *
     * 编码上先按 UTF-8 解，解出来带替换符（乱码）再退回 GBK——
     * 中文环境下老的 .txt 经常是 GBK，直接按 UTF-8 读会整片乱码。
     */
    @Throws(IOException::class)
    fun readText(context: Context, uri: Uri, maxBytes: Int = DEFAULT_MAX_BYTES): String {
        val bytes = readBytes(context, uri, maxBytes)
        return decodeText(bytes)
    }

    /** @return 按「UTF-8 优先，乱码则 GBK」解码出来的文本。 */
    fun decodeText(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        // U+FFFD 是解码失败时的替换符，出现就说明不是 UTF-8
        if ('\uFFFD' !in utf8) {
            return utf8
        }
        return runCatching { String(bytes, charset("GBK")) }.getOrDefault(utf8)
    }

    /**
     * @return 解码结果看起来是不是真的文本。
     *
     * 用户可能手滑选了个图片/压缩包进来，直接当文本解析会得到一屏乱码，
     * 不如早点判出来给个明确提示。
     */
    fun isTextLike(text: String): Boolean {
        if (text.isBlank()) {
            return false
        }
        val sample = text.take(4096)
        val bad = sample.count { char ->
            char == '\uFFFD' || (char.code < 0x20 && char != '\n' && char != '\r' && char != '\t')
        }
        return bad.toDouble() / sample.length < 0.05
    }

    /** 文本类文件的读取上限：8MB 足够放任何字符画了。 */
    const val DEFAULT_MAX_BYTES = 8 * 1024 * 1024

    // region 内部

    /**
     * 跑一轮所有方式，没成功就按 [RETRY_DELAYS_MS] 等一会儿再跑，直到成功或放弃。
     *
     * @param closeStream 成功后要不要顺手把流关掉。默认 true——
     *   读字节/复制文件的写法都是"用完即关"；只有 [open] 需要把流原样交出去，
     *   那时必须传 false，否则调用方拿到的是一条已经关了的流。
     * @param consume 拿到流后干什么，返回 true 表示这轮算成功（外层就不再重试）。
     *   参数是这条路的可读名字（进日志/异常用）和刚打开的数据流。
     */
    private fun runAll(
        context: Context,
        uri: Uri,
        attempts: Attempts,
        closeStream: Boolean = true,
        consume: (name: String, input: InputStream) -> Boolean
    ): Boolean {
        var round = 0
        while (true) {
            for (strategy in strategies(context, uri)) {
                try {
                    val input = strategy.open()
                    if (closeStream) {
                        input.use { if (consume(strategy.name, it)) return true }
                    } else {
                        if (consume(strategy.name, input)) return true
                        runCatching { input.close() }
                    }
                } catch (e: Throwable) {
                    attempts.fail(strategy.name, e)
                }
            }
            if (round >= RETRY_DELAYS_MS.size || !attempts.worthRetrying()) {
                return false
            }
            val wait = RETRY_DELAYS_MS[round]
            round++
            Log.w(TAG, "第 $round 次重试 $uri，${wait}ms 后再来（云端图首次打开常常只是触发下载）")
            runCatching { Thread.sleep(wait) }
        }
    }

    /**
     * @return 失败信息：试过的每一种方式、各自报了什么，外加 provider 元信息探测结果。
     *
     * 探测这一段是**为了定位**——光看 ENOENT 分不清是"图还在云端"还是"权限被拒"，
     * 但把 provider 能查到的列（尤其 `_data`、`_size`、`is_pending`）打出来就一目了然。
     */
    private fun failure(
        action: String,
        context: Context,
        uri: Uri,
        attempts: Attempts
    ): IOException = IOException(buildString {
        append(action).append('\n')
        append("Uri: ").append(uri).append('\n')
        append("试过的方式：\n")
        attempts.failures.forEach { (name, why) -> append("  · ").append(name).append("：").append(why).append('\n') }
        append('\n')
        append("来源探测：\n")
        probe(context, uri).forEach { append("  ").append(it).append('\n') }
        append('\n')
        append("怎么办：\n")
        append("  · 若 _data 为空 / is_pending=1，多半是图还在云端，先在相册里打开一次下载到本机\n")
        append("  · 换成「文件选择器」重新选同一张图，走的是文档通道，兼容性更好\n")
    })

    /**
     * 查一下这个 Uri 后面到底是什么东西。
     *
     * 只读第一行、只挑几个关键列，而且整段包在 `runCatching` 里——
     * 探测本身失败（provider 连 query 都不让）也是有用的结论，不该反过来把错误盖掉。
     */
    private fun probe(context: Context, uri: Uri): List<String> = buildList {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    add("query 成功但 0 行")
                    return@use
                }
                val columns = cursor.columnNames
                add("可见列（${columns.size}）：" + columns.take(MAX_PROBE_COLUMNS).joinToString(", "))
                INTERESTING_COLUMNS.forEach { name ->
                    val index = cursor.getColumnIndex(name)
                    if (index >= 0) {
                        val value = runCatching { cursor.getString(index) }.getOrNull()
                        add("$name = ${value?.take(160) ?: "null"}")
                    }
                }
            }
        }.onFailure { e ->
            add("query 也失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 探测时优先看的列：能不能定位到真实文件、是不是还在下载，全在这几个里。 */
    private val INTERESTING_COLUMNS = listOf(
        "_id",
        "_data",
        "_size",
        "_display_name",
        "mime_type",
        "is_pending",
        "is_trashed",
        "is_downloaded",
        "relative_path",
        "date_modified"
    )

    private const val MAX_PROBE_COLUMNS = 40

    private fun strategies(context: Context, uri: Uri): List<Strategy> = buildList {
        val resolver = context.contentResolver

        add(
            Strategy("ContentResolver.openInputStream") {
                resolver.openInputStream(uri) ?: throw IOException("返回了 null")
            }
        )
        add(
            Strategy("ContentResolver.openFileDescriptor") {
                val pfd: ParcelFileDescriptor =
                    resolver.openFileDescriptor(uri, "r") ?: throw IOException("返回了 null")
                // 用 AutoCloseInputStream，关流的同时把 fd 也放掉；
                // 旧写法 FileInputStream(pfd.fileDescriptor) 会让 pfd 泄漏。
                ParcelFileDescriptor.AutoCloseInputStream(pfd)
            }
        )
        add(
            Strategy("ContentResolver.openAssetFileDescriptor") {
                val afd: AssetFileDescriptor = resolver.openAssetFileDescriptor(uri, "r")
                    ?: throw IOException("返回了 null")
                AssetFileDescriptor.AutoCloseInputStream(afd)
            }
        )
        add(
            Strategy("ContentResolver.openTypedAssetFileDescriptor") {
                val afd: AssetFileDescriptor = resolver.openTypedAssetFileDescriptor(uri, "*/*", null)
                    ?: throw IOException("返回了 null")
                AssetFileDescriptor.AutoCloseInputStream(afd)
            }
        )

        // 系统相册（照片选择器）给的 Uri 长这样：
        //   content://media/picker/0/com.android.providers.media.photopicker/media/1001204890
        // 末段那串数字就是 MediaStore 的 _id。某些机型上 picker 这一层转不出数据，
        // 但直接拿 id 去问 MediaStore 能拿到——这是最值得一试的兜底。
        // 没拿到媒体读权限时这里会抛 SecurityException，被 runAll 记一笔跳过，不影响其他路。
        mediaStoreCandidates(uri).forEach { (label, candidate) ->
            add(
                Strategy(label) {
                    resolver.openInputStream(candidate) ?: throw IOException("返回了 null")
                }
            )
        }

        add(
            Strategy("MediaStore._data 指向的真实文件") {
                val path = queryDataColumn(context, uri) ?: throw IOException("provider 没给出 _data")
                val file = File(path)
                if (!file.isFile) {
                    throw IOException("_data 指向的文件在本机不存在：$path")
                }
                FileInputStream(file)
            }
        )

        if (uri.scheme == "file") {
            add(
                Strategy("FileInputStream(file://)") {
                    val path = uri.path ?: throw IOException("路径为空")
                    val file = File(path)
                    if (!file.isFile) {
                        throw IOException("文件不存在：$path")
                    }
                    FileInputStream(file)
                }
            )
        }
    }

    /**
     * @return 照片选择器 Uri 对应的 MediaStore 候选（名字 + Uri），不是 picker 形状就返回空。
     *
     * 只认「末段是纯数字」的情况，避免把普通 Uri 也拉进来白试一轮。
     */
    private fun mediaStoreCandidates(uri: Uri): List<Pair<String, Uri>> {
        val id = uri.lastPathSegment?.toLongOrNull() ?: return emptyList()
        if (id <= 0L) {
            return emptyList()
        }
        return listOf(
            "MediaStore.Images id=$id" to ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                id
            ),
            "MediaStore.Files id=$id" to ContentUris.withAppendedId(
                MediaStore.Files.getContentUri("external"),
                id
            )
        )
    }

    /**
     * @return [uri] 在 MediaStore 里记的 `_data` 绝对路径，查不到返回 null。
     *
     * `_data` 从 API 29 起就算废弃（分区存储下不该直接摸路径），但这里只是**多试一条路**：
     * 能查到且文件真的在本机、又恰好读得动，那就赚了；读不动会照常抛异常被记一笔，
     * 不影响别的路。所以保留 deprecated 调用是有意为之。
     */
    @Suppress("DEPRECATION")
    private fun queryDataColumn(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    null
                } else {
                    val index = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (index >= 0) cursor.getString(index)?.takeIf { it.isNotBlank() } else null
                }
            }
    }.getOrNull()

    /** 最多读 [limit] 字节，避免把超大文件整个读进内存。 */
    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = read(chunk)
            if (read < 0) {
                break
            }
            val usable = minOf(read, limit - total)
            if (usable > 0) {
                buffer.write(chunk, 0, usable)
                total += usable
            }
            if (total >= limit) {
                break
            }
        }
        return buffer.toByteArray()
    }

    // endregion
}
