package com.tans.tasciiartplayer.image

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.WorkerThread
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Save the ascii art result as a picture or a text file, and share it.
 *
 * - Android 10+ : files are inserted into MediaStore, so they show up in the gallery/file
 *   manager and can be shared, no storage permission is needed.
 * - Android 9-  : files are written to the public pictures/downloads directory, which needs
 *   [android.Manifest.permission.WRITE_EXTERNAL_STORAGE].
 */
object AsciiArtExporter {

    private const val ALBUM_NAME = "tAsciiArtPlayer"

    const val PNG_MIME_TYPE = "image/png"
    const val TEXT_MIME_TYPE = "text/plain"

    const val PNG_FILE_SUFFIX = ".ascii.png"
    const val TEXT_FILE_SUFFIX = ".ascii.txt"

    data class SavedAsciiArt(
        val uri: Uri,
        val displayName: String,
        val mimeType: String,
        /** Path of the file, only for showing to the user. */
        val readablePath: String
    )

    /**
     * @return true if saving files on this device needs [android.Manifest.permission.WRITE_EXTERNAL_STORAGE].
     */
    fun needsWritePermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    @WorkerThread
    fun savePng(context: Context, bitmap: Bitmap, baseName: String): SavedAsciiArt {
        val displayName = buildFileName(baseName, PNG_FILE_SUFFIX)
        return save(
            context = context,
            displayName = displayName,
            mimeType = PNG_MIME_TYPE,
            relativeDirectory = Environment.DIRECTORY_PICTURES,
            isPicture = true
        ) { outputStream ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)) {
                error("Compress ascii art png fail.")
            }
        }
    }

    @WorkerThread
    fun saveText(context: Context, text: String, baseName: String): SavedAsciiArt {
        val displayName = buildFileName(baseName, TEXT_FILE_SUFFIX)
        return save(
            context = context,
            displayName = displayName,
            mimeType = TEXT_MIME_TYPE,
            relativeDirectory = Environment.DIRECTORY_DOWNLOADS,
            isPicture = false
        ) { outputStream ->
            outputStream.write(text.toByteArray(Charsets.UTF_8))
            outputStream.flush()
        }
    }

    /**
     * @return an [Intent.ACTION_SEND] intent of a saved ascii art file.
     */
    fun createShareIntent(savedAsciiArt: SavedAsciiArt): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = savedAsciiArt.mimeType
            putExtra(Intent.EXTRA_STREAM, savedAsciiArt.uri)
            putExtra(Intent.EXTRA_TITLE, savedAsciiArt.displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * @return an [Intent.ACTION_SEND] intent of a text ascii art.
     */
    fun createShareTextIntent(text: String): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = TEXT_MIME_TYPE
            putExtra(Intent.EXTRA_TEXT, text)
        }
    }

    private fun save(
        context: Context,
        displayName: String,
        mimeType: String,
        relativeDirectory: String,
        isPicture: Boolean,
        writeContent: (OutputStream) -> Unit
    ): SavedAsciiArt {
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
            val uri = resolver.insert(collection, values) ?: error("Can not insert $displayName to MediaStore.")
            try {
                resolver.openOutputStream(uri)?.use(writeContent) ?: error("Can not open output stream of $uri.")
            } catch (e: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            if (isPicture) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            return SavedAsciiArt(
                uri = uri,
                displayName = displayName,
                mimeType = mimeType,
                readablePath = "$relativeDirectory/$ALBUM_NAME/$displayName"
            )
        } else {
            val directory = File(Environment.getExternalStoragePublicDirectory(relativeDirectory), ALBUM_NAME)
            if (!directory.exists() && !directory.mkdirs()) {
                error("Can not create directory ${directory.absolutePath}.")
            }
            val file = File(directory, displayName)
            FileOutputStream(file).use(writeContent)
            val uri = insertLegacyFile(context, file, mimeType, isPicture)
            return SavedAsciiArt(
                uri = uri,
                displayName = displayName,
                mimeType = mimeType,
                readablePath = file.absolutePath
            )
        }
    }

    /**
     * Android 9 and lower, insert a public file to MediaStore by its path to get a shareable uri.
     */
    @Suppress("DEPRECATION")
    private fun insertLegacyFile(context: Context, file: File, mimeType: String, isPicture: Boolean): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DATA, file.absolutePath)
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.SIZE, file.length())
            put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000L)
            put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
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

    private fun buildFileName(baseName: String, suffix: String): String {
        val fixedBaseName = baseName
            .substringBeforeLast('.')
            .replace(Regex("[/\\\\:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .take(MAX_BASE_NAME_LENGTH)
            .ifBlank { "ascii_art_${System.currentTimeMillis()}" }
        return "$fixedBaseName$suffix"
    }

    private const val MAX_BASE_NAME_LENGTH = 60
}
