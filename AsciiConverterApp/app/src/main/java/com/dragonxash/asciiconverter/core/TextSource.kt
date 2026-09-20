package com.dragonxash.asciiconverter.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.annotation.WorkerThread

/**
 * 文本的来路和去路：剪贴板、txt 文件。
 *
 * 文件读取复用 [ContentStreams] 那套"多种方式轮着试"的逻辑，
 * 不在这里另起一套，免得到处都是开流的坑。
 */
object TextSource {

    /** @return 剪贴板里的文本，没有或者不是文本就返回 null。 */
    fun clipboardText(context: Context): String? {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
        if (!clipboard.hasPrimaryClip()) {
            return null
        }
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount <= 0) {
            return null
        }
        return clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
    }

    /** 把 [text] 放进剪贴板。 */
    fun copyToClipboard(context: Context, text: String, label: String): Boolean {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        return runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
            true
        }.getOrDefault(false)
    }

    /**
     * 读一个 txt 文件。
     *
     * @throws java.io.IOException 读不到时抛出，异常信息里带 Uri 和尝试过的打开方式。
     * @throws IllegalArgumentException 内容明显不是文本（比如选成了图片）时抛出。
     */
    @WorkerThread
    @Throws(java.io.IOException::class)
    fun readTextFile(context: Context, uri: Uri): String {
        val text = ContentStreams.readText(context, uri)
        require(ContentStreams.isTextLike(text)) { "这看起来不是文本文件，请选一个 .txt" }
        return text
    }
}
