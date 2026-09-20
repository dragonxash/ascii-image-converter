package com.dragonxash.asciiconverter.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 出错时的取证工具。
 *
 * 之前只把 `e.message` 弹出来，信息往往不够定位问题（很多异常 message 是 null，
 * 或者只有半句话）。这里统一做三件事：
 * 1. 完整堆栈写 Logcat（tag 固定，方便 `adb logcat -s AsciiConverter:E` 捞）；
 * 2. 完整堆栈落盘到 `cacheDir/last-error.txt`，界面崩了也能事后取；
 * 3. 返回一段可以直接长按复制的文本，弹给用户看。
 *
 * 纯本地，不外发。
 */
object Diagnostics {

    const val TAG = "AsciiConverter"

    /** 最近一次错误落盘的文件名。 */
    private const val LAST_ERROR_FILE = "last-error.txt"

    /** @return 带时间戳和完整堆栈的描述，用于落盘和展示。 */
    fun describe(throwable: Throwable, scene: String): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return buildString {
            append("时间: ").append(stamp).append('\n')
            append("场景: ").append(scene).append('\n')
            append("机型: ").append(android.os.Build.MANUFACTURER)
                .append(' ').append(android.os.Build.MODEL).append('\n')
            append("系统: Android ").append(android.os.Build.VERSION.RELEASE)
                .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
            append("异常: ").append(throwable.javaClass.name).append('\n')
            append("信息: ").append(throwable.message ?: "(无)")
                .append("\n\n")
            append("--- 堆栈 ---\n")
            append(writer.toString())
        }
    }

    /**
     * 记录一个错误：写 Logcat + 落盘。
     *
     * @return 可直接展示给用户、可复制的完整描述。
     */
    fun record(context: Context?, throwable: Throwable, scene: String): String {
        val text = describe(throwable, scene)
        Log.e(TAG, "[$scene] $text")
        context?.let { ctx ->
            runCatching {
                File(ctx.cacheDir, LAST_ERROR_FILE).writeText(text)
            }
        }
        return text
    }

    /** @return 上一次落盘的错误内容，没有就返回 null。 */
    fun lastError(context: Context): String? {
        val file = File(context.cacheDir, LAST_ERROR_FILE)
        return if (file.isFile) runCatching { file.readText() }.getOrNull() else null
    }

    /** 清掉上一次的错误记录。 */
    fun clearLastError(context: Context) {
        runCatching { File(context.cacheDir, LAST_ERROR_FILE).delete() }
    }
}
