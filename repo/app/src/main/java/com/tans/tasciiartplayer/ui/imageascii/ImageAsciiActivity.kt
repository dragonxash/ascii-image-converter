package com.tans.tasciiartplayer.ui.imageascii

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.tans.tasciiartplayer.AppLog
import com.tans.tasciiartplayer.R
import com.tans.tasciiartplayer.databinding.ImageAsciiActivityBinding
import com.tans.tasciiartplayer.image.AsciiArtConverter
import com.tans.tasciiartplayer.image.AsciiArtExporter
import com.tans.tasciiartplayer.image.AsciiArtOptions
import com.tans.tasciiartplayer.image.PictureDecoder
import com.tans.tuiutils.activity.BaseCoroutineStateActivity
import com.tans.tuiutils.systembar.annotation.SystemBarStyle
import com.tans.tuiutils.view.clicks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Convert a picture to ascii art.
 *
 * The picture can come from:
 * - The [Intent.ACTION_VIEW]/[Intent.ACTION_SEND] intent, so the activity can be a share target.
 * - The IMAGES tab of [com.tans.tasciiartplayer.ui.main.MainActivity].
 * - The system photo picker, which is opened by the menu item [R.id.pick_image].
 *
 * The result can be exported as a picture([AsciiArtExporter.savePng]) or a text file
 * ([AsciiArtExporter.saveText]), copied to the clipboard or shared.
 */
@SystemBarStyle(statusBarThemeStyle = 1, navigationBarThemeStyle = 1)
class ImageAsciiActivity : BaseCoroutineStateActivity<ImageAsciiActivity.Companion.State>(State()) {

    override val layoutId: Int = R.layout.image_ascii_activity

    /** The bitmap which is showing in the image view, recycled when a new one is rendered. */
    private var displayedBitmap: Bitmap? = null

    /** The saved file of the current ascii art, used by the share action. */
    private var savedAsciiArt: AsciiArtExporter.SavedAsciiArt? = null

    private var pendingWritePermissionAction: (() -> Unit)? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            updateState { it.copy(uri = uri, displayName = null) }
        }
    }

    override fun CoroutineScope.firstLaunchInitDataCoroutine() {
        val inputUri = intent.getInputPictureUri()
        if (inputUri != null) {
            updateState { it.copy(uri = inputUri, displayName = intent.getInputPictureName()) }
        }
    }

    override fun CoroutineScope.bindContentViewCoroutine(contentView: View) {
        val viewBinding = ImageAsciiActivityBinding.bind(contentView)
        bindToolbar(viewBinding)
        bindSettings(viewBinding)

        // Convert the picture again when the input picture or the ascii art settings changed.
        launch {
            var convertJob: Job? = null
            stateFlow()
                .map { ConvertRequest(uri = it.uri, options = it.options) }
                .distinctUntilChanged()
                .collect { request ->
                    convertJob?.cancel()
                    convertJob = null
                    if (request.uri == null) {
                        updateState {
                            it.copy(
                                converting = false,
                                asciiArt = null,
                                asciiArtText = null,
                                sourceWidth = 0,
                                sourceHeight = 0,
                                gridColumns = 0,
                                gridRows = 0
                            )
                        }
                    } else {
                        convertJob = launch(Dispatchers.IO) { convert(request) }
                    }
                }
        }

        // Render the conversion error.
        renderStateNewCoroutine({ it.error ?: "" }) { error ->
            if (error.isNotEmpty()) {
                Toast.makeText(this@ImageAsciiActivity, error, Toast.LENGTH_LONG).show()
            }
        }

        // Render the result.
        renderStateNewCoroutine(
            { state ->
                RenderResult(
                    converting = state.converting,
                    hasInput = state.uri != null,
                    displayText = state.textMode,
                    asciiArt = state.asciiArt,
                    asciiArtText = state.asciiArtText,
                    info = if (state.gridColumns > 0) {
                        getString(
                            R.string.image_ascii_act_info,
                            state.sourceWidth,
                            state.sourceHeight,
                            state.gridColumns,
                            state.gridRows,
                            state.gridColumns * state.gridRows
                        )
                    } else {
                        ""
                    }
                )
            }
        ) { result ->
            viewBinding.loadingPb.visibility = if (result.converting) View.VISIBLE else View.GONE
            viewBinding.emptyTv.visibility = if (!result.converting && !result.hasInput) View.VISIBLE else View.GONE
            viewBinding.infoTv.text = result.info

            val showText = result.displayText && !result.asciiArtText.isNullOrEmpty()
            if (showText) {
                if (viewBinding.asciiTextTv.text?.toString() != result.asciiArtText) {
                    viewBinding.asciiTextTv.text = result.asciiArtText
                }
                viewBinding.asciiTextTv.visibility = View.VISIBLE
                viewBinding.asciiArtHsv.visibility = View.GONE
            } else {
                viewBinding.asciiTextTv.visibility = View.GONE
                viewBinding.asciiArtHsv.visibility = if (result.asciiArt != null) View.VISIBLE else View.GONE
                if (result.asciiArt !== displayedBitmap) {
                    viewBinding.asciiArtIv.setImageBitmap(result.asciiArt)
                    displayedBitmap?.recycle()
                    displayedBitmap = result.asciiArt
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val inputUri = intent.getInputPictureUri()
        if (inputUri != null) {
            updateState { it.copy(uri = inputUri, displayName = intent.getInputPictureName()) }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_WRITE_EXTERNAL_STORAGE) {
            val action = pendingWritePermissionAction
            pendingWritePermissionAction = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                action?.invoke()
            } else {
                Toast.makeText(this, R.string.image_ascii_act_permission_denied, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        displayedBitmap = null
    }

    private fun bindToolbar(viewBinding: ImageAsciiActivityBinding) {
        viewBinding.toolBar.setNavigationOnClickListener { finish() }
        val menu = viewBinding.toolBar.menu
        menu.findItem(R.id.pick_image).setOnMenuItemClickListener {
            try {
                pickImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            } catch (e: Throwable) {
                AppLog.e(TAG, "Open photo picker fail.", e)
            }
            true
        }
        menu.findItem(R.id.ascii_settings).setOnMenuItemClickListener {
            viewBinding.settingsSv.visibility =
                if (viewBinding.settingsSv.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            true
        }
        menu.findItem(R.id.save_png).setOnMenuItemClickListener {
            saveAsciiArtPng()
            true
        }
        menu.findItem(R.id.save_txt).setOnMenuItemClickListener {
            saveAsciiArtText()
            true
        }
        menu.findItem(R.id.copy_text).setOnMenuItemClickListener {
            copyAsciiArtText()
            true
        }
        menu.findItem(R.id.share_ascii).setOnMenuItemClickListener {
            shareAsciiArt()
            true
        }
    }

    private fun bindSettings(viewBinding: ImageAsciiActivityBinding) {
        val options = currentState().options

        viewBinding.charReverseSw.isChecked = options.reverseChar
        viewBinding.charReverseSw.setOnCheckedChangeListener { _, isChecked ->
            applyOptions { it.copy(reverseChar = isChecked) }
        }

        viewBinding.colorReverseSw.isChecked = options.reverseColor
        viewBinding.colorReverseSw.setOnCheckedChangeListener { _, isChecked ->
            applyOptions { it.copy(reverseColor = isChecked) }
        }

        viewBinding.keepImageColorSw.isChecked = options.keepImageColor
        viewBinding.keepImageColorSw.setOnCheckedChangeListener { _, isChecked ->
            applyOptions { it.copy(keepImageColor = isChecked) }
        }

        viewBinding.monospaceSw.isChecked = options.useMonospaceFont
        viewBinding.monospaceSw.setOnCheckedChangeListener { _, isChecked ->
            applyOptions { it.copy(useMonospaceFont = isChecked) }
        }

        viewBinding.textModeSw.isChecked = currentState().textMode
        viewBinding.textModeSw.setOnCheckedChangeListener { _, isChecked ->
            updateState { it.copy(textMode = isChecked) }
        }

        viewBinding.charWidthSb.progress = options.charLineWidthInPercent()
        viewBinding.charWidthTv.text =
            getString(R.string.image_ascii_setting_char_width, options.charLineWidth)
        viewBinding.charWidthSb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val charLineWidth = AsciiArtOptions.percentToCharLineWidth(seekBar?.progress ?: 0)
                applyOptions { it.copy(charLineWidth = charLineWidth) }
            }

            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                viewBinding.charWidthTv.text = getString(
                    R.string.image_ascii_setting_char_width,
                    AsciiArtOptions.percentToCharLineWidth(progress)
                )
            }
        })

        val colorFillRatePercent = (options.colorFillRate * 100.0f + 0.5f).toInt()
        viewBinding.colorFillRateSb.progress = colorFillRatePercent
        viewBinding.colorFillRateTv.text =
            getString(R.string.image_ascii_setting_color_fill_rate, colorFillRatePercent)
        viewBinding.colorFillRateSb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val rate = (seekBar?.progress ?: 0).toFloat() / 100.0f
                applyOptions { it.copy(colorFillRate = rate) }
            }

            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                viewBinding.colorFillRateTv.text =
                    getString(R.string.image_ascii_setting_color_fill_rate, progress)
            }
        })

        viewBinding.textRowsSb.progress = options.textAspectRatioInPercent()
        viewBinding.textRowsTv.text =
            getString(R.string.image_ascii_setting_text_rows, options.textAspectRatioInPercent())
        viewBinding.textRowsSb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val ratio = (seekBar?.progress ?: 0).toFloat() / 100.0f
                applyOptions { it.copy(textAspectRatio = ratio) }
            }

            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                viewBinding.textRowsTv.text =
                    getString(R.string.image_ascii_setting_text_rows, progress)
            }
        })

        viewBinding.closeSettingsBtn.clicks(uiCoroutineScope) {
            viewBinding.settingsSv.visibility = View.GONE
        }
    }

    private fun applyOptions(update: (AsciiArtOptions) -> AsciiArtOptions) {
        updateState { it.copy(options = update(it.options)) }
    }

    /**
     * Convert [ConvertRequest.uri] to ascii art, the text result and the picture result are both
     * rendered, so the user can copy/save/share the text even in text mode.
     */
    private suspend fun convert(request: ConvertRequest) {
        val uri = request.uri ?: return
        val context = applicationContext
        updateState { it.copy(converting = true, error = null) }
        var source: Bitmap? = null
        try {
            source = PictureDecoder.decode(context, uri, PictureDecoder.DEFAULT_MAX_SIZE)
            val rotation = PictureDecoder.queryRotationDegrees(context, uri)
            if (rotation != 0) {
                val rotated = Bitmap.createBitmap(
                    source,
                    0,
                    0,
                    source.width,
                    source.height,
                    Matrix().apply { postRotate(rotation.toFloat()) },
                    true
                )
                source.recycle()
                source = rotated
            }
            val options = request.options
            val grid = AsciiArtConverter.buildGrid(source, options)
            val asciiArtText = AsciiArtConverter.renderText(source, options)
            val asciiArt = AsciiArtConverter.renderImage(source, options)
            val displayName = currentState().displayName ?: PictureDecoder.queryDisplayName(context, uri)
            savedAsciiArt = null
            updateState {
                it.copy(
                    converting = false,
                    displayName = displayName,
                    asciiArt = asciiArt,
                    asciiArtText = asciiArtText,
                    sourceWidth = source.width,
                    sourceHeight = source.height,
                    gridColumns = grid.columns,
                    gridRows = grid.rows,
                    error = null
                )
            }
            AppLog.d(TAG, "Convert picture success: grid=${grid.columns}x${grid.rows}, chars=${grid.cellCount}")
        } catch (e: Throwable) {
            AppLog.e(TAG, "Convert picture fail: uri=$uri", e)
            updateState {
                it.copy(
                    converting = false,
                    error = getString(
                        R.string.image_ascii_act_convert_error,
                        e.message ?: e.javaClass.simpleName
                    )
                )
            }
        } finally {
            source?.recycle()
        }
    }

    private fun saveAsciiArtPng() {
        val state = currentState()
        val asciiArt = state.asciiArt
        if (asciiArt == null) {
            Toast.makeText(this, R.string.image_ascii_act_no_text, Toast.LENGTH_SHORT).show()
            return
        }
        withWritePermission {
            val baseName = state.displayName ?: DEFAULT_BASE_NAME
            dataCoroutineScope.launch {
                try {
                    val saved = AsciiArtExporter.savePng(applicationContext, asciiArt, baseName)
                    savedAsciiArt = saved
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@ImageAsciiActivity,
                            getString(R.string.image_ascii_act_saved, saved.readablePath),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Throwable) {
                    AppLog.e(TAG, "Save ascii art png fail.", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@ImageAsciiActivity,
                            getString(R.string.image_ascii_act_save_error, e.message ?: e.javaClass.simpleName),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun saveAsciiArtText() {
        val state = currentState()
        val asciiArtText = state.asciiArtText
        if (asciiArtText.isNullOrEmpty()) {
            Toast.makeText(this, R.string.image_ascii_act_no_text, Toast.LENGTH_SHORT).show()
            return
        }
        withWritePermission {
            val baseName = state.displayName ?: DEFAULT_BASE_NAME
            dataCoroutineScope.launch {
                try {
                    val saved = AsciiArtExporter.saveText(applicationContext, asciiArtText, baseName)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@ImageAsciiActivity,
                            getString(R.string.image_ascii_act_saved, saved.readablePath),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Throwable) {
                    AppLog.e(TAG, "Save ascii art text fail.", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@ImageAsciiActivity,
                            getString(R.string.image_ascii_act_save_error, e.message ?: e.javaClass.simpleName),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun copyAsciiArtText() {
        val asciiArtText = currentState().asciiArtText
        if (asciiArtText.isNullOrEmpty()) {
            Toast.makeText(this, R.string.image_ascii_act_no_text, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText(getString(R.string.image_ascii_act_title), asciiArtText)
            )
            Toast.makeText(this, R.string.image_ascii_act_copy_success, Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            AppLog.e(TAG, "Copy ascii art text fail.", e)
        }
    }

    private fun shareAsciiArt() {
        val state = currentState()
        val asciiArt = state.asciiArt
        if (asciiArt == null) {
            Toast.makeText(this, R.string.image_ascii_act_no_text, Toast.LENGTH_SHORT).show()
            return
        }
        withWritePermission {
            val baseName = state.displayName ?: DEFAULT_BASE_NAME
            dataCoroutineScope.launch {
                try {
                    val saved = savedAsciiArt ?: AsciiArtExporter.savePng(applicationContext, asciiArt, baseName)
                        .also { savedAsciiArt = it }
                    withContext(Dispatchers.Main) {
                        try {
                            startActivity(
                                Intent.createChooser(
                                    AsciiArtExporter.createShareIntent(saved),
                                    getString(R.string.image_ascii_menu_share)
                                )
                            )
                        } catch (e: Throwable) {
                            AppLog.e(TAG, "Share ascii art fail.", e)
                        }
                    }
                } catch (e: Throwable) {
                    AppLog.e(TAG, "Save ascii art png for sharing fail.", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@ImageAsciiActivity,
                            getString(R.string.image_ascii_act_save_error, e.message ?: e.javaClass.simpleName),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    /**
     * Android 10+ saves files with MediaStore and needs no permission, Android 9- needs
     * [Manifest.permission.WRITE_EXTERNAL_STORAGE].
     */
    private fun withWritePermission(action: () -> Unit) {
        if (!AsciiArtExporter.needsWritePermission()) {
            action()
            return
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            pendingWritePermissionAction = action
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_WRITE_EXTERNAL_STORAGE
            )
        }
    }

    companion object {

        private const val TAG = "ImageAsciiActivity"

        private const val REQUEST_WRITE_EXTERNAL_STORAGE = 1001

        private const val DEFAULT_BASE_NAME = "ascii_art"

        private const val INPUT_PICTURE_URI_EXTRA = "input_picture_uri_extra"
        private const val INPUT_PICTURE_NAME_EXTRA = "input_picture_name_extra"

        private data class ConvertRequest(
            val uri: Uri?,
            val options: AsciiArtOptions
        )

        private data class RenderResult(
            val converting: Boolean,
            val hasInput: Boolean,
            val displayText: Boolean,
            val asciiArt: Bitmap?,
            val asciiArtText: String?,
            val info: String
        )

        /**
         * Open this activity with a picture.
         */
        fun createIntent(context: Context, uri: Uri, displayName: String?): Intent {
            return Intent(context, ImageAsciiActivity::class.java).apply {
                putExtra(INPUT_PICTURE_URI_EXTRA, uri.toString())
                if (displayName != null) {
                    putExtra(INPUT_PICTURE_NAME_EXTRA, displayName)
                }
            }
        }

        private fun Intent.getInputPictureUri(): Uri? {
            val intentData = data
            if (intentData != null && action == Intent.ACTION_VIEW) {
                return intentData
            }
            if (action == Intent.ACTION_SEND) {
                val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (streamUri != null) {
                    return streamUri
                }
            }
            val clipUri = clipData?.let { if (it.itemCount > 0) it.getItemAt(0).uri else null }
            if (clipUri != null) {
                return clipUri
            }
            val extraUri = getStringExtra(INPUT_PICTURE_URI_EXTRA)
            return if (extraUri.isNullOrBlank()) null else Uri.parse(extraUri)
        }

        private fun Intent.getInputPictureName(): String? = getStringExtra(INPUT_PICTURE_NAME_EXTRA)

        data class State(
            val uri: Uri? = null,
            val displayName: String? = null,
            val options: AsciiArtOptions = AsciiArtOptions(),
            val textMode: Boolean = false,
            val converting: Boolean = false,
            val asciiArt: Bitmap? = null,
            val asciiArtText: String? = null,
            val sourceWidth: Int = 0,
            val sourceHeight: Int = 0,
            val gridColumns: Int = 0,
            val gridRows: Int = 0,
            val error: String? = null
        )
    }
}
