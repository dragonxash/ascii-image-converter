package com.dragonxash.asciiconverter.core

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.WorkerThread
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * 保存/分享字符画。
 *
 * - Android 10+ 走 MediaStore，文件直接出现在相册/下载里，不需要任何权限。
 * - Android 9 及以下写公共目录，需要 [android.Manifest.permission.WRITE_EXTERNAL_STORAGE]。
 * - 分享走 FileProvider（cache 目录），不需要权限。
 */
object AsciiArtExporter {

    /** 相册/下载里的子目录名。 */
    private const val ALBUM_NAME = "AsciiConverter"

    const val PNG_MIME_TYPE = "image/png"
    const val TEXT_MIME_TYPE = "text/plain"

    private const val PNG_FILE_SUFFIX = "_ascii.png"
    private const val TEXT_FILE_SUFFIX = "_ascii.txt"

    private const val CACHE_EXPORT_DIR = "export"

    data class SavedFile(
        val uri: Uri,
        val displayName: String,
        val mimeType: String,
        /** 给用户看的路径，不参与任何逻辑。 */
        val readablePath: String
    )

    /** @return true 表示这台设备保存文件需要写外部存储权限。 */
    fun needsWritePermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** 保存成 PNG 到相册。 */
    @WorkerThread
    fun savePng(context: Context, bitmap: Bitmap, baseName: String): SavedFile = save(
        context = context,
        displayName = buildFileName(baseName, PNG_FILE_SUFFIX),
        mimeType = PNG_MIME_TYPE,
        relativeDirectory = Environment.DIRECTORY_PICTURES,
        isPicture = true
    ) { output ->
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
            error("PNG 编码失败")
        }
    }

    /** 保存成 TXT 到下载目录。 */
    @WorkerThread
    fun saveText(context: Context, text: String, baseName: String): SavedFile = save(
        context = context,
        displayName = buildFileName(baseName, TEXT_FILE_SUFFIX),
        mimeType = TEXT_MIME_TYPE,
        relativeDirectory = Environment.DIRECTORY_DOWNLOADS,
        isPicture = false
    ) { output ->
        output.write(text.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    /** 把 PNG 写到 cache，用于分享（不需要权限）。 */
    @WorkerThread
    fun writePngToCache(context: Context, bitmap: Bitmap, baseName: String): File {
        val file = File(exportCacheDir(context), buildFileName(baseName, PNG_FILE_SUFFIX))
        FileOutputStream(file).use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                error("PNG 编码失败")
            }
        }
        return file
    }

    /** 把 TXT 写到 cache，用于分享（不需要权限）。 */
    @WorkerThread
    fun writeTextToCache(context: Context, text: String, baseName: String): File {
        val file = File(exportCacheDir(context), buildFileName(baseName, TEXT_FILE_SUFFIX))
        file.writeText(text, Charsets.UTF_8)
        return file
    }

    /** @return 分享单个文件用的 ACTION_SEND intent。 */
    fun createShareIntent(context: Context, file: File, mimeType: String): Intent {
        val uri = uriForFile(context, file)
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** @return 分享纯文本用的 ACTION_SEND intent。 */
    fun createShareTextIntent(text: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = TEXT_MIME_TYPE
            putExtra(Intent.EXTRA_TEXT, text)
        }

    fun uriForFile(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** cache 里的分享文件目录。 */
    fun exportCacheDir(context: Context): File =
        File(context.cacheDir, CACHE_EXPORT_DIR).apply { if (!exists()) mkdirs() }

    /** @return 给拍照输出用的文件（在 cache/capture 下）。 */
    fun newCaptureFile(context: Context): File {
        val dir = File(context.cacheDir, "capture").apply { if (!exists()) mkdirs() }
        return File(dir, "capture_${System.currentTimeMillis()}.jpg")
    }

    private fun save(
        context: Context,
        displayName: String,
        mimeType: String,
        relativeDirectory: String,
        isPicture: Boolean,
        writeContent: (OutputStream) -> Unit
    ): SavedFile {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (isPicture) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$relativeDirectory/$ALBUM_NAME")
                if (isPicture) {
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(collection, values) ?: error("无法写入 MediaStore")
            try {
                resolver.openOutputStream(uri)?.use(writeContent) ?: error("无法打开输出流")
            } catch (e: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            if (isPicture) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            return SavedFile(
                uri = uri,
                displayName = displayName,
                mimeType = mimeType,
                readablePath = "$relativeDirectory/$ALBUM_NAME/$displayName"
            )
        }

        val directory = File(
            Environment.getExternalStoragePublicDirectory(relativeDirectory),
            ALBUM_NAME
        )
        if (!directory.exists() && !directory.mkdirs()) {
            error("无法创建目录：${directory.absolutePath}")
        }
        val file = File(directory, displayName)
        FileOutputStream(file).use(writeContent)
        return SavedFile(
            uri = insertLegacyFile(context, file, mimeType, isPicture),
            displayName = displayName,
            mimeType = mimeType,
            readablePath = file.absolutePath
        )
    }

    /** Android 9 及以下：把公共目录里的文件登记进 MediaStore，好让别的 App 能读到。 */
    @Suppress("DEPRECATION")
    private fun insertLegacyFile(context: Context, file: File, mimeType: String, isPicture: Boolean): Uri {
        val now = System.currentTimeMillis() / 1000L
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DATA, file.absolutePath)
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.SIZE, file.length())
            put(MediaStore.MediaColumns.DATE_ADDED, now)
            put(MediaStore.MediaColumns.DATE_MODIFIED, now)
        }
        val collection = if (isPicture) {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }
        return runCatching { context.contentResolver.insert(collection, values) }
            .getOrNull()
            ?: Uri.fromFile(file)
    }

    /** 把原文件名洗成安全的文件名。 */
    private fun buildFileName(baseName: String, suffix: String): String {
        val fixed = baseName
            .substringBeforeLast('.')
            .replace(Regex("[/\\\\:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .take(MAX_BASE_NAME_LENGTH)
            .ifBlank { "ascii_${System.currentTimeMillis()}" }
        return "$fixed$suffix"
    }

    private const val MAX_BASE_NAME_LENGTH = 60
}
