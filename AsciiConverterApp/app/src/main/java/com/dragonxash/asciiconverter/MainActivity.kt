package com.dragonxash.asciiconverter

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dragonxash.asciiconverter.core.AnsiText
import com.dragonxash.asciiconverter.core.AnsiTextFormat
import com.dragonxash.asciiconverter.core.AppLanguage
import com.dragonxash.asciiconverter.core.AppPrefs
import com.dragonxash.asciiconverter.core.AsciiArtConverter
import com.dragonxash.asciiconverter.core.AsciiArtExporter
import com.dragonxash.asciiconverter.core.AsciiArtOptions
import com.dragonxash.asciiconverter.core.AsciiArtResult
import com.dragonxash.asciiconverter.core.Diagnostics
import com.dragonxash.asciiconverter.core.DitherMode
import com.dragonxash.asciiconverter.core.Pc98Quantizer
import com.dragonxash.asciiconverter.core.PictureDecoder
import com.dragonxash.asciiconverter.core.Preset
import com.dragonxash.asciiconverter.core.SourceCache
import com.dragonxash.asciiconverter.core.TextSource
import com.dragonxash.asciiconverter.databinding.ActivityMainBinding
import com.dragonxash.asciiconverter.databinding.DialogImportTextBinding
import com.dragonxash.asciiconverter.databinding.DialogPaletteBinding
import com.dragonxash.asciiconverter.databinding.DialogStyleBinding
import com.dragonxash.asciiconverter.databinding.DialogTextOutputBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * 转换工作台：选图 → 预览 → 调样式 → 保存/复制/分享。
 */
class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding

    /** 当前样式参数。 */
    private var options: AsciiArtOptions = Preset.default.applyTo(AsciiArtOptions())

    /** 文本预览模式。 */
    private var textMode: Boolean = false

    /**
     * 当前图片源——**本地文件**，不是相册给的 Uri。
     *
     * 选图时由 [SourceCache] 把外部 Uri 读一次落成本地文件，之后所有转换都读它。
     * 这样改样式、拖滑杆导致的重转不会再去碰相册 provider，
     * 也就不会再出现"文件浏览器正常、系统相册 ENOENT"。
     */
    private var sourceFile: File? = null
    private var sourceName: String? = null
    private var sourceWidth: Int = 0
    private var sourceHeight: Int = 0

    /**
     * 当前结果是不是由「导入文字」产生的。
     *
     * 图片源靠 [sourceFile] 重新转换，文字源靠 [importedText] 重新渲染，
     * 所以改样式、切语言之后得知道该走哪条路。
     */
    private var sourceIsText: Boolean = false

    /** 导入进来的原始文本，改参数后重新渲染要用。 */
    private var importedText: String? = null

    /** 「文字导入」的输出宽度。 */
    private var importOutputWidth: Int = AsciiArtConverter.DEFAULT_IMPORT_WIDTH

    private var result: AsciiArtResult? = null
    private var convertJob: Job? = null

    /** 选完图后「把 Uri 落成本地文件」这个动作，连着选两张时要能被后一张掐掉。 */
    private var loadJob: Job? = null

    private var pendingCaptureFile: File? = null
    private var pendingPermissionAction: (() -> Unit)? = null

    private var styleSheetBinding: DialogStyleBinding? = null
    private val presetChips = mutableMapOf<Preset, Chip>()
    private var syncingChips = false
    private var syncingSheet = false

    /** 「中间色处理」的三个标签，切选项时要在它们之间同步选中状态。 */
    private val ditherChips = mutableMapOf<DitherMode, Chip>()

    /**
     * 调色板面板里正在编辑的 16 色。
     *
     * 面板一打开就把 [AsciiArtOptions.customPalette]（没设过就用画面自动取的那 16 色）
     * 拷进来，用户点「确定」才写回参数——中途反悔直接关掉面板不影响画面。
     */
    private var editingPalette: MutableList<Int> = mutableListOf()

    /** 调色板面板里被选中的那个色块下标。 */
    private var editingSwatch = 0

    /** 调色板面板的 16 个色块，改色之后要回头刷它们。 */
    private val swatchViews = mutableListOf<View>()

    /** 「导入文字」面板，开着的时候才不为 null（还没解析的文本在里面）。 */
    private var importSheetBinding: DialogImportTextBinding? = null

    /** 文本输出格式（纯字符 / 16 色 / 真彩），跟着设置持久化。 */
    private var textFormat: AnsiTextFormat = AnsiTextFormat.Plain

    /** 「文字结果」面板里三种格式的标签，切格式时要在它们之间同步选中状态。 */
    private val formatChips = mutableMapOf<AnsiTextFormat, Chip>()

    /** 导入面板的网格信息刷新要防抖——粘一大段带颜色的文本时不必每个字符都解析一遍。 */
    private val uiHandler = Handler(Looper.getMainLooper())
    private var importInfoRefresh: Runnable? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            loadImage(uri, null)
        }
    }

    /**
     * 文件选择器。
     *
     * 相册（照片选择器）给的是 `content://media/picker/...`，某些机型/某些图（比如只在云端的）
     * 会读不出来；换成文档选择器拿到的 Uri 兼容性更好，作为兜底入口。
     *
     * 用 [PersistableOpenDocument] 而不是默认的 OpenDocument，是为了拿到**可持久化**的读权限，
     * 这样进程重启、切换语言重建界面之后，之前选的文件还读得到。
     */
    private val pickFileLauncher = registerForActivityResult(
        PersistableOpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            loadImage(uri, null)
        }
    }

    /** OpenDocument 的加强版：额外申请可持久化的读权限。 */
    private class PersistableOpenDocument : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            super.createIntent(context, input).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
    }

    /** 打开 .txt 文件当字符画输入。 */
    private val openTxtLauncher = registerForActivityResult(
        PersistableOpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            loadTxtIntoImportSheet(uri)
        }
    }

    /**
     * 拍照结果。
     *
     * 用 [ActivityResultContracts.StartActivityForResult] 而不是 `TakePicture`，理由有三条：
     *
     * 1. **`TakePicture` 不给相机写权限。** 它的 `createIntent` 只做
     *    `putExtra(EXTRA_OUTPUT, uri)`，**一次 `addFlags` 都没有**（反汇编 androidx.activity
     *    1.11.0 的 `ActivityResultContracts$TakePicture.class` 确认过）。
     *    而相机的 Uri 写权限就是靠 grant flag 授的——不给，相机写不进去，
     *    拍完回来我们那个文件还是 0 字节。原来对此**一声不吭**，界面照旧停在「还没有图片」。
     *    （FileProvider 的 `grantUriPermissions="true"` 只是"允许你授权"，不是"已经授权了"。）
     * 2. **拿得到回传的 Intent。** 少数相机、以及把 `IMAGE_CAPTURE` 接成图库的应用，
     *    根本不写 `EXTRA_OUTPUT`，而是把图塞在 `data` 里（`intent.data` 的 Uri，
     *    或者 `extras["data"]` 的缩略图 Bitmap）。`TakePicture` 只回一个 Boolean，这些全丢。
     * 3. **没拿到图的时候要说话**，不能静默。
     */
    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val file = pendingCaptureFile
        pendingCaptureFile = null
        if (result.resultCode != Activity.RESULT_OK) {
            // 用户在相机里按了返回 / 取消，什么也不该弹
            return@registerForActivityResult
        }
        val picked = resolveCaptureResult(result.data, file)
        if (picked == null) {
            showCaptureEmptyDialog()
            return@registerForActivityResult
        }
        loadImage(picked.first, picked.second)
    }

    /**
     * 拍照什么都没拿回来时的提示。
     *
     * 不用 `Toast`：这东西一闪就没了，而用户这会儿正盯着"预览区怎么还是空的"，
     * 很容易错过。更重要的是**要给一条能走通的路**——照 1.4.1/1.4.2 立下的规矩，
     * 与其让人对着一个错误框发呆，不如把出口直接摆在按钮上。
     * 这里复用 [showSourceChooser]：相册和文件选择器两条通道都给他。
     */
    private fun showCaptureEmptyDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.capture_empty_title)
            .setMessage(R.string.capture_empty_message)
            .setPositiveButton(R.string.capture_empty_use_pick) { _, _ -> showSourceChooser() }
            .setNegativeButton(R.string.about_ok, null)
            .show()
    }

    /**
     * 从拍照结果里把图找出来，按可靠程度依次试三条路。
     *
     * @return `(Uri, 文件名)`；三条路都没拿到东西时返回 `null`，由调用方提示用户。
     */
    private fun resolveCaptureResult(data: Intent?, file: File?): Pair<Uri, String?>? {
        // 1. 正常情况：相机按 EXTRA_OUTPUT 把照片写进了我们给的那个文件
        if (file != null && file.length() > 0L) {
            return Uri.fromFile(file) to file.name
        }
        // 2. 有的应用不认 EXTRA_OUTPUT，改用 data 回传一个 Uri
        data?.data?.let { return it to null }
        // 3. 再退一步：老式写法回传缩略图 Bitmap，自己落盘
        //
        // 取值要分版本：无类型的 `Bundle.get(String)` / `getParcelable(String)` 在 API 33 起
        // 已废弃，直接用会破掉"代码级编译警告 0 条"这条规矩；而 minSdk 是 26，低版本那条路
        // 又必须留着，所以低版本分支上加 @Suppress，并说明为什么免不了。
        val thumb: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            data?.extras?.getParcelable(EXTRA_CAPTURE_BITMAP, Bitmap::class.java)
        } else {
            @Suppress("DEPRECATION")
            data?.extras?.getParcelable(EXTRA_CAPTURE_BITMAP)
        } ?: return null
        val saved = runCatching {
            val out = AsciiArtExporter.newCaptureFile(this)
            FileOutputStream(out).use { thumb.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            out
        }.getOrNull() ?: return null
        if (saved.length() <= 0L) {
            return null
        }
        return Uri.fromFile(saved) to saved.name
    }

    private val batchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* 批量页自己处理结果，这里不需要做别的 */ }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingPermissionAction
        pendingPermissionAction = null
        if (granted) {
            action?.invoke()
        } else {
            toast(getString(R.string.toast_permission_denied))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        textFormat = AppPrefs.textFormat(this)
        bindViews()
        // 切换语言会让界面重建，重建时把之前选的图和参数接回来
        if (savedInstanceState != null) {
            restoreState(savedInstanceState)
        } else {
            handleIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        saveState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        result = null
    }

    /** 把当前状态写进 [outState]，切语言重建界面时不至于白屏。 */
    private fun saveState(outState: Bundle) {
        // 存的是本地中转文件的路径，不是相册 Uri——
        // 相册 Uri 的临时授权活不过进程重建，拿它去读只会白等一轮重试再报错。
        outState.putString(KEY_FILE, sourceFile?.absolutePath)
        outState.putString(KEY_NAME, sourceName)
        outState.putBoolean(KEY_IS_TEXT, sourceIsText)
        outState.putInt(KEY_IMPORT_WIDTH, importOutputWidth)
        outState.putBoolean(KEY_TEXT_MODE, textMode)
        outState.putInt(KEY_CHAR_WIDTH, options.charLineWidth)
        outState.putBoolean(KEY_REVERSE_CHAR, options.reverseChar)
        outState.putBoolean(KEY_REVERSE_COLOR, options.reverseColor)
        outState.putFloat(KEY_COLOR_FILL, options.colorFillRate)
        outState.putInt(KEY_FG_COLOR, options.foregroundColor)
        outState.putInt(KEY_BG_COLOR, options.backgroundColor)
        outState.putBoolean(KEY_KEEP_COLOR, options.keepImageColor)
        outState.putBoolean(KEY_MONO_FONT, options.useMonospaceFont)
        outState.putBoolean(KEY_BLOCKS_ONLY, options.blocksOnly)
        outState.putBoolean(KEY_PC98, options.pc98Palette)
        outState.putString(KEY_DITHER, options.dither.name)
        options.customPalette?.let { outState.putIntArray(KEY_PALETTE, it.toIntArray()) }
        outState.putFloat(KEY_TEXT_ASPECT, options.textAspectRatio)
        outState.putInt(KEY_SRC_WIDTH, sourceWidth)
        outState.putInt(KEY_SRC_HEIGHT, sourceHeight)
        // 拍照的"待回填文件"也必须存：相机是个重 Activity，我们的界面在后台很可能被
        // 系统回收重建。重建后 `pendingCaptureFile` 会变回 null，拍照结果回来时就
        // 找不到文件、**一声不吭地什么都不做**——界面停在「还没有图片」。
        outState.putString(KEY_CAPTURE, pendingCaptureFile?.absolutePath)
        // 导入的文本可能很大，塞 Bundle 有 TransactionTooLarge 的风险，落一份到 cache
        if (sourceIsText) {
            importedText?.let { text ->
                runCatching { File(cacheDir, IMPORT_STASH_FILE).writeText(text) }
            }
        }
    }

    /** [saveState] 的逆操作。 */
    private fun restoreState(state: Bundle) {
        options = options.copy(
            charLineWidth = state.getInt(KEY_CHAR_WIDTH, options.charLineWidth),
            reverseChar = state.getBoolean(KEY_REVERSE_CHAR, options.reverseChar),
            reverseColor = state.getBoolean(KEY_REVERSE_COLOR, options.reverseColor),
            colorFillRate = state.getFloat(KEY_COLOR_FILL, options.colorFillRate),
            foregroundColor = state.getInt(KEY_FG_COLOR, options.foregroundColor),
            backgroundColor = state.getInt(KEY_BG_COLOR, options.backgroundColor),
            keepImageColor = state.getBoolean(KEY_KEEP_COLOR, options.keepImageColor),
            useMonospaceFont = state.getBoolean(KEY_MONO_FONT, options.useMonospaceFont),
            blocksOnly = state.getBoolean(KEY_BLOCKS_ONLY, options.blocksOnly),
            pc98Palette = state.getBoolean(KEY_PC98, options.pc98Palette),
            dither = state.getString(KEY_DITHER)
                ?.let { name -> DitherMode.entries.firstOrNull { it.name == name } }
                ?: options.dither,
            customPalette = state.getIntArray(KEY_PALETTE)?.toList(),
            textAspectRatio = state.getFloat(KEY_TEXT_ASPECT, options.textAspectRatio)
        )
        textMode = state.getBoolean(KEY_TEXT_MODE, false)
        sourceWidth = state.getInt(KEY_SRC_WIDTH, 0)
        sourceHeight = state.getInt(KEY_SRC_HEIGHT, 0)
        importOutputWidth = state.getInt(KEY_IMPORT_WIDTH, importOutputWidth)
        // 界面被相机挤掉过再重建时，把待回填的拍照文件接回来（见 saveState 里的说明）
        pendingCaptureFile = state.getString(KEY_CAPTURE)?.let(::File)

        if (state.getBoolean(KEY_IS_TEXT, false)) {
            val stashed = runCatching { File(cacheDir, IMPORT_STASH_FILE).readText() }.getOrNull()
            if (!stashed.isNullOrEmpty()) {
                applyImportedText(stashed)
                return
            }
        }

        // 中转文件还在就直接接回来；被系统清掉了就当没选过图，不出错框
        val restored = SourceCache.existing(state.getString(KEY_FILE))
        if (restored != null) {
            sourceFile = restored
            sourceName = state.getString(KEY_NAME)
            sourceIsText = false
            binding.emptyGroup.visibility = View.GONE
            scheduleConvert(0L)
        }
    }

    // region 入口与操作

    private fun bindViews() {
        binding.toolBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_style -> {
                    showStyleSheet()
                    true
                }

                R.id.action_text_output -> {
                    showTextOutputSheet()
                    true
                }

                R.id.action_import -> {
                    showImportSheet()
                    true
                }

                R.id.action_language -> {
                    showLanguageDialog()
                    true
                }

                R.id.action_last_error -> {
                    showLastError()
                    true
                }

                R.id.action_about -> {
                    showAbout()
                    true
                }

                else -> false
            }
        }

        // clicks 内部已经统一兜异常并弹出完整堆栈，这里不用再各自 try/catch
        binding.pickBtn.clicks { showSourceChooser() }
        binding.captureBtn.clicks { launchCamera() }
        binding.importBtn.clicks { showImportSheet() }
        binding.batchBtn.clicks { batchLauncher.launch(BatchActivity.createIntent(this, options)) }
        binding.saveBtn.clicks { savePngFile() }
        binding.shareBtn.clicks { shareResult() }

        // 预览区双指缩放：放大后才显示倍数角标，点角标回 1×
        binding.previewPane.onScaleChanged = { updateZoomChip(it) }
        binding.zoomResetTv.clicks { binding.previewPane.resetZoom() }
    }

    // region 预览缩放

    /**
     * 放大之后在右下角挂一个「1.8× 复位」的小角标。
     *
     * 没做双击复位：双击在文本预览里是选词，抢过来会把「可长按复制」这个功能弄坏。
     * 角标 + 「往里捏到底就回到 1×」这两条路都留着。
     */
    private fun updateZoomChip(zoom: Float) {
        if (zoom <= 1.01f) {
            binding.zoomResetTv.visibility = View.GONE
        } else {
            binding.zoomResetTv.visibility = View.VISIBLE
            binding.zoomResetTv.text =
                getString(R.string.preview_zoom_reset, String.format(Locale.US, "%.1f", zoom))
        }
    }

    /**
     * 换了一批内容（换图、换导入的文字）就回到 1×。
     *
     * 调样式（拖滑杆、切开关）**不**复位——用户往往是先放大看清细节、再微调参数，
     * 每调一次就被弹回原始大小会很难受。
     */
    private fun resetPreviewZoom() {
        binding.previewPane.resetZoom()
        updateZoomChip(1f)
    }

    // endregion
    /** 选图入口：让用户在「系统相册」和「文件选择器」之间挑。 */
    private fun showSourceChooser() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.source_title)
            .setItems(
                arrayOf(
                    getString(R.string.source_gallery),
                    getString(R.string.source_file)
                )
            ) { _, which ->
                when (which) {
                    0 -> pickImageLauncher.launch(imageOnlyRequest())
                    else -> pickFileLauncher.launch(arrayOf("image/*"))
                }
            }
            .show()
    }

    /** 界面语言。默认简体中文，不受系统语言影响。 */
    private fun showLanguageDialog() {
        val languages = AppLanguage.selectable
        val labels = languages.map { getString(it.labelRes) }.toTypedArray()
        val current = languages.indexOf(AppLanguage.load(this))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lang_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val picked = languages[which]
                dialog.dismiss()
                // 这里不能拿「存储值」比对来短路：
                // 存储值本来就是中文、界面却还是英文（系统 per-app language 静默失败），
                // 那时候点「中文」会被判成"没变化"，什么都不做——本次要修的就是这个。
                AppLanguage.save(this, picked)
                toast(getString(R.string.lang_changed))
                ensureLanguageApplied()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun handleIntent(intent: Intent?) {
        // 外部调起拿到的是别的应用给的 Uri，读图失败很正常，别让它把界面掀了。
        // 解析 Uri 本身出错走 reportError；真正的读取失败在 loadImage 内部处理，
        // 那边会弹一个带「换文件浏览器重选」的提示。
        val uri = runCatching { extractInputUri(intent) }
            .onFailure { reportError(it, "读取外部传入的图片") }
            .getOrNull() ?: return
        loadImage(uri, null)
    }

    private fun extractInputUri(intent: Intent?): Uri? {
        if (intent == null) {
            return null
        }
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { return it }
            Intent.ACTION_SEND -> {
                val stream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (stream != null) {
                    return stream
                }
            }
        }
        // ClipData 里的 item 允许没有 uri（只有文本之类），取之前必须判空
        val clip = intent.clipData ?: return null
        return if (clip.itemCount > 0) clip.getItemAt(0).uri else null
    }

    private fun imageOnlyRequest() =
        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    // endregion

    // region 转换

    /**
     * 选定一张新图：**先把外部 Uri 落成本地文件，再开始转换**。
     *
     * 落盘放在转换之前是有意的——相册（照片选择器）给的临时权限只在
     * "刚选完"这段时间里最靠谱，能早就别晚。之后这个 Uri 就再也不碰了。
     */
    private fun loadImage(uri: Uri, name: String?) {
        sourceName = name
        // 换成图片源了，之前导入的文字作废
        sourceIsText = false
        importedText = null
        sourceFile = null
        binding.emptyGroup.visibility = View.GONE
        // 换图了，预览回到 1×，别让上一张的放大倍数跟过来
        resetPreviewZoom()

        loadJob?.cancel()
        convertJob?.cancel()
        loadJob = lifecycleScope.launch {
            showLoading(true)
            val local = try {
                withContext(Dispatchers.IO) { SourceCache.materialize(applicationContext, uri) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                showLoading(false)
                reportSourceError(e, uri)
                return@launch
            }
            if (!isActive) {
                return@launch
            }
            showLoading(false)
            // 只留最近两张，避免中转目录无限长大
            SourceCache.keepOnly(applicationContext, local)
            sourceFile = local
            if (name == null) {
                // 文件名用来当导出文件的默认名，异步补上就行
                val queried = withContext(Dispatchers.IO) {
                    runCatching { PictureDecoder.queryDisplayName(applicationContext, uri) }.getOrNull()
                }
                if (isActive && sourceFile === local && !queried.isNullOrBlank()) {
                    sourceName = queried
                }
            }
            scheduleConvert(0L)
        }
    }

    /** 参数变化时重新转换，[delayMs] 用来防抖，避免拖滑杆时疯狂转换。 */
    private fun scheduleConvert(delayMs: Long) {
        if (sourceFile == null) {
            return
        }
        convertJob?.cancel()
        convertJob = lifecycleScope.launch {
            if (delayMs > 0) {
                delay(delayMs)
            }
            doConvert()
        }
    }

    private suspend fun doConvert() {
        val file = sourceFile ?: return
        showLoading(true)
        try {
            val snapshotOptions = options
            val converted = withContext(Dispatchers.Default) {
                // 只读本地文件——不再回头去开相册给的 Uri
                val bitmap = PictureDecoder.decode(file)
                try {
                    AsciiArtConverter.convert(bitmap, snapshotOptions, needText = true) to
                        (bitmap.width to bitmap.height)
                } finally {
                    bitmap.recycle()
                }
            }
            if (isFinishing || isDestroyed) {
                converted.first.bitmap.recycle()
                return
            }
            val newResult = converted.first
            val oldResult = result
            sourceWidth = converted.second.first
            sourceHeight = converted.second.second
            result = newResult
            renderResult(newResult)
            // 换完图再回收旧的，避免 ImageView 还指着已经被回收的 bitmap
            oldResult?.bitmap?.recycle()
        } catch (e: CancellationException) {
            // 拖滑杆时会连续取消上一个转换，这不是错误，别弹框
            throw e
        } catch (e: Throwable) {
            // 光弹 e.message 经常看不出问题，这里同时写日志、落盘，并给出完整堆栈对话框
            reportError(e, "转换图片")
        } finally {
            showLoading(false)
        }
    }

    private fun renderResult(converted: AsciiArtResult) {
        binding.previewPane.visibility = View.VISIBLE
        binding.infoTv.visibility = View.VISIBLE
        // 文字导入没有源图尺寸，换成另一套文案
        binding.infoTv.text = if (sourceIsText) {
            getString(
                R.string.main_info_imported,
                converted.columns,
                converted.rows,
                converted.charCount
            )
        } else {
            getString(
                R.string.main_info,
                sourceWidth,
                sourceHeight,
                converted.columns,
                converted.rows,
                converted.charCount
            )
        }
        binding.previewPane.setBackgroundColor(options.backgroundColor)
        if (textMode) {
            binding.asciiArtIv.visibility = View.GONE
            binding.asciiTextTv.visibility = View.VISIBLE
            binding.asciiTextTv.setTextColor(options.foregroundColor)
            binding.asciiTextTv.setBackgroundColor(options.backgroundColor)
            binding.asciiTextTv.text = converted.text.orEmpty()
            applyTextSize(converted.columns)
        } else {
            binding.asciiTextTv.visibility = View.GONE
            binding.asciiArtIv.visibility = View.VISIBLE
            binding.asciiArtIv.setBackgroundColor(options.backgroundColor)
            binding.asciiArtIv.setImageBitmap(converted.bitmap)
        }
    }

    /** 让文本预览正好铺满屏幕宽度，不用横向滚动。 */
    private fun applyTextSize(columns: Int) {
        val padding = binding.asciiTextTv.paddingLeft + binding.asciiTextTv.paddingRight
        binding.asciiTextTv.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            previewTextSizePx(columns, padding)
        )
    }

    /** @return 让 [columns] 列字符刚好铺满可用宽度的字号（像素）。 */
    private fun previewTextSizePx(columns: Int, horizontalPadding: Int): Float {
        val metrics = resources.displayMetrics
        val available = (metrics.widthPixels - 8 * metrics.density).coerceAtLeast(64f)
        val usable = (available - horizontalPadding).coerceAtLeast(32f)
        // 等宽字符宽度约为字号 * 0.6
        val textSizePx = usable / (columns.coerceAtLeast(1) * 0.6f)
        return textSizePx.coerceIn(3f * metrics.density, 40f * metrics.density)
    }

    private fun showLoading(loading: Boolean) {
        binding.loadingPb.visibility = if (loading) View.VISIBLE else View.GONE
        binding.convertingTv.visibility = if (loading) View.VISIBLE else View.GONE
    }

    // endregion

    // region 文字导入（文字 → 图片）

    /** 把 [text] 设成当前源并渲染，样式面板再改参数会走 [reschedule] 重新渲染。 */
    private fun applyImportedText(text: String) {
        importedText = text
        sourceIsText = true
        sourceFile = null
        sourceName = null
        sourceWidth = 0
        sourceHeight = 0
        // 导入的目的就是看图，别停在文本预览模式
        textMode = false
        binding.emptyGroup.visibility = View.GONE
        // 换了内容，预览回到 1×
        resetPreviewZoom()
        scheduleImportRender(0L)
    }

    private fun scheduleImportRender(delayMs: Long) {
        val text = importedText ?: return
        convertJob?.cancel()
        convertJob = lifecycleScope.launch {
            if (delayMs > 0) {
                delay(delayMs)
            }
            doImportRender(text)
        }
    }

    private suspend fun doImportRender(text: String) {
        showLoading(true)
        try {
            val width = importOutputWidth
            val snapshot = options
            val rendered = withContext(Dispatchers.Default) {
                AsciiArtConverter.importText(text, snapshot, width)
            }
            if (isFinishing || isDestroyed) {
                rendered.bitmap.recycle()
                return
            }
            val oldResult = result
            result = rendered
            // 导入结果也带上原文，复制 / 存 TXT 直接用
            renderResult(rendered)
            oldResult?.bitmap?.recycle()
        } catch (e: Throwable) {
            reportError(e, "文字导入")
        } finally {
            showLoading(false)
        }
    }

    // endregion

    // region 面板：导入文字 / 文字结果

    /** 「导入文字」面板：粘贴或打开 txt，生成图片。 */
    private fun showImportSheet() {
        val sheetBinding = DialogImportTextBinding.inflate(layoutInflater)
        importSheetBinding = sheetBinding

        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheetBinding.root)
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(ContextCompat.getColor(this, R.color.bg_surface_high))
        dialog.setOnDismissListener {
            importSheetBinding = null
            importInfoRefresh?.let { uiHandler.removeCallbacks(it) }
            importInfoRefresh = null
        }

        sheetBinding.importWidthSb.progress = widthToPercent(importOutputWidth)
        sheetBinding.importWidthValueTv.text =
            getString(R.string.import_width_value, importOutputWidth)
        refreshImportInfo(sheetBinding)

        sheetBinding.importEt.doAfterTextChanged { scheduleImportInfo(sheetBinding) }

        sheetBinding.importWidthSb.setOnSeekBarChangeListener(
            object : SimpleSeekBarListener() {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) {
                        return
                    }
                    importOutputWidth = percentToWidth(progress)
                    sheetBinding.importWidthValueTv.text =
                        getString(R.string.import_width_value, importOutputWidth)
                }
            }
        )

        sheetBinding.pasteBtn.clicks {
            val text = TextSource.clipboardText(this)
            if (text.isNullOrBlank()) {
                toast(getString(R.string.toast_clipboard_empty))
            } else {
                sheetBinding.importEt.setText(text)
                sheetBinding.importEt.setSelection(sheetBinding.importEt.text?.length ?: 0)
            }
        }

        sheetBinding.openTxtBtn.clicks {
            // 有的文件管理器不给 .txt 打 text/plain，所以再放宽一层
            openTxtLauncher.launch(arrayOf("text/*", "application/octet-stream"))
        }

        sheetBinding.importClearBtn.clicks {
            sheetBinding.importEt.setText("")
            dialog.dismiss()
        }

        sheetBinding.importRenderBtn.clicks {
            val text = sheetBinding.importEt.text?.toString().orEmpty()
            if (text.isBlank()) {
                toast(getString(R.string.toast_import_empty))
                return@clicks
            }
            dialog.dismiss()
            applyImportedText(text)
            val colors = ansiColorCount(text)
            toast(
                if (colors > 0) {
                    getString(R.string.toast_ansi_detected, colors)
                } else {
                    getString(R.string.toast_render_done)
                }
            )
        }

        dialog.show()
    }

    /** 把打开的 txt 内容填进导入面板。 */
    private fun loadTxtIntoImportSheet(uri: Uri) {
        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    TextSource.readTextFile(applicationContext, uri)
                }
                val colors = ansiColorCount(text)
                val sheetBinding = importSheetBinding
                if (sheetBinding == null) {
                    // 面板被关掉了也别白读，直接拿去渲染
                    applyImportedText(text)
                    toast(noticeFor(text, colors))
                    return@launch
                }
                sheetBinding.importEt.setText(text)
                sheetBinding.importEt.setSelection(sheetBinding.importEt.text?.length ?: 0)
                // 带颜色的文件先说一声，用户才知道颜色能还原
                toast(
                    if (colors > 0) {
                        getString(R.string.toast_ansi_detected, colors)
                    } else {
                        getString(R.string.toast_txt_loaded, uri.lastPathSegment ?: "")
                    }
                )
            } catch (e: Throwable) {
                reportError(e, "读取 TXT 文件")
            }
        }
    }

    private fun noticeFor(text: String, colors: Int): String = if (colors > 0) {
        getString(R.string.toast_ansi_detected, colors)
    } else {
        getString(R.string.toast_render_done)
    }

    /** 更新导入面板里的「网格 96×48 · 4608 字符」。 */
    private fun refreshImportInfo(sheetBinding: DialogImportTextBinding) {
        val text = sheetBinding.importEt.text?.toString().orEmpty()
        // 带 ANSI 真彩的字符画能有几百 KB，这种就别边输边解析了
        if (text.length > MAX_LIVE_PARSE_LENGTH) {
            sheetBinding.importInfoTv.text = getString(R.string.import_info_large, text.length)
            return
        }
        sheetBinding.importInfoTv.text = runCatching {
            val grid = AsciiArtConverter.parseAsciiText(text)
            getString(R.string.import_info, grid.columns, grid.rows, grid.cellCount)
        }.getOrDefault("")
    }

    /** 输入变化后稍等一会儿再统计，避免每敲一个字符都全量解析一遍。 */
    private fun scheduleImportInfo(sheetBinding: DialogImportTextBinding) {
        importInfoRefresh?.let { uiHandler.removeCallbacks(it) }
        val task = Runnable {
            if (importSheetBinding === sheetBinding) {
                refreshImportInfo(sheetBinding)
            }
        }
        importInfoRefresh = task
        uiHandler.postDelayed(task, LIVE_PARSE_DEBOUNCE_MS)
    }

    /** @return 文本里带的 ANSI 颜色数；0 表示没有颜色码（纯字符文本）。 */
    private fun ansiColorCount(text: String): Int =
        runCatching { AnsiText.distinctColorCount(text) }.getOrDefault(0)

    /** 「文字结果」面板：看字符画正文、选文本格式，复制 / 存 TXT / 分享。 */
    private fun showTextOutputSheet() {
        val converted = result
        if (converted?.text.isNullOrEmpty()) {
            toast(getString(R.string.toast_text_empty))
            return
        }
        val sheetBinding = DialogTextOutputBinding.inflate(layoutInflater)

        // 文本预览的字号跟主界面一样，按列数缩到刚好铺满
        sheetBinding.textOutputTv.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            previewTextSizePx(
                converted.columns,
                sheetBinding.textOutputTv.paddingLeft + sheetBinding.textOutputTv.paddingRight
            )
        )

        buildFormatChips(sheetBinding)
        refreshTextOutput(sheetBinding)

        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheetBinding.root)
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(ContextCompat.getColor(this, R.color.bg_surface_high))

        sheetBinding.textCopyBtn.clicks { copyTextOutput() }
        sheetBinding.textSaveBtn.clicks { textForFormat()?.let { saveTextFile(it) } }
        sheetBinding.textShareBtn.clicks { textForFormat()?.let { shareTextFile(it) } }

        dialog.show()
    }

    /** 建三种文本格式的标签。 */
    private fun buildFormatChips(sheetBinding: DialogTextOutputBinding) {
        sheetBinding.textFormatGroup.removeAllViews()
        formatChips.clear()
        // 批量页的结果没留网格，换格式无从下手，只能给纯字符
        val switchable = result?.grid != null
        AnsiTextFormat.entries.forEach { format ->
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = getString(format.titleRes)
                isCheckable = true
                isClickable = true
                isEnabled = switchable
                setOnClickListener {
                    if (textFormat != format) {
                        textFormat = format
                        AppPrefs.setTextFormat(this@MainActivity, format)
                        refreshTextOutput(sheetBinding)
                    }
                }
            }
            formatChips[format] = chip
            sheetBinding.textFormatGroup.addView(chip)
        }
        formatChips[textFormat]?.isChecked = true
    }

    /** @return 当前结果按当前格式编码出来的文本；没有网格时退回纯字符。 */
    private fun textForFormat(): String? {
        val converted = result ?: return null
        val grid = converted.grid ?: return converted.text
        return AnsiText.encode(grid, textFormat)
    }

    /** 刷新预览正文、格式说明和体积统计。 */
    private fun refreshTextOutput(sheetBinding: DialogTextOutputBinding) {
        val converted = result ?: return
        val text = textForFormat().orEmpty()
        // 预览只着色前 N 个字符：真彩格式下 span 数 = 字符数，全量着色会 OOM。
        // 复制/保存/分享拿的还是完整文本，只有这块预览被截。
        val truncated = text.length > MAX_PREVIEW_CHARS
        val preview = if (truncated) text.substring(0, MAX_PREVIEW_CHARS) else text
        // 带颜色的格式直接把 ANSI 转成彩色 span，预览里所见即所得
        sheetBinding.textOutputTv.text = AnsiText.toColorSpans(preview, MAX_PREVIEW_CHARS) ?: preview
        sheetBinding.textFormatHintTv.text = getString(hintOf(textFormat))
        // 输出全是 ASCII，字符数就当字节数用
        val size = formatByteSize(text.length)
        val stats = if (textFormat == AnsiTextFormat.Plain) {
            getString(R.string.text_format_stats_plain, size)
        } else {
            getString(R.string.text_format_stats, AnsiText.distinctColorCount(text), size)
        }
        val notice = if (truncated) " · " + getString(R.string.text_output_truncated) else ""
        sheetBinding.textOutputInfoTv.text = getString(
            R.string.text_output_info,
            converted.columns,
            converted.rows,
            converted.charCount
        ) + " · " + stats + notice
    }

    private fun hintOf(format: AnsiTextFormat): Int = when (format) {
        AnsiTextFormat.Plain -> R.string.text_format_hint_plain
        AnsiTextFormat.Palette16 -> R.string.text_format_hint_16
        AnsiTextFormat.TrueColor -> R.string.text_format_hint_true
    }

    private fun formatByteSize(bytes: Int): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    }

    private fun copyTextOutput() {
        val text = textForFormat()
        if (text.isNullOrEmpty()) {
            toast(getString(R.string.toast_text_empty))
            return
        }
        if (TextSource.copyToClipboard(this, text, getString(R.string.app_name))) {
            toast(getString(R.string.toast_text_copied))
        } else {
            toast(getString(R.string.toast_copy_failed, "clipboard unavailable"))
        }
    }

    // endregion

    // region 样式面板

    private fun showStyleSheet() {
        val sheetBinding = DialogStyleBinding.inflate(layoutInflater)
        styleSheetBinding = sheetBinding
        buildPresetChips(sheetBinding)
        buildDitherChips(sheetBinding)

        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheetBinding.root)
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(ContextCompat.getColor(this, R.color.bg_surface_high))
        dialog.setOnDismissListener { styleSheetBinding = null }

        syncStyleSheet(sheetBinding)
        wireStyleSheet(sheetBinding, dialog)
        dialog.show()
    }

    /** 建预设标签，预设列表变了也不用改这里。 */
    private fun buildPresetChips(sheetBinding: DialogStyleBinding) {
        sheetBinding.presetGroup.removeAllViews()
        presetChips.clear()
        Preset.selectablePresets.forEach { preset ->
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = getString(preset.titleRes)
                isCheckable = true
                isClickable = true
                setOnClickListener { if (!syncingChips) applyPreset(preset) }
            }
            presetChips[preset] = chip
            sheetBinding.presetGroup.addView(chip)
        }
    }

    /**
     * 建「中间色处理」标签。
     *
     * 直接遍历 [DitherMode] 的枚举项，以后加一种抖动方式不用回来改这里。
     */
    private fun buildDitherChips(sheetBinding: DialogStyleBinding) {
        sheetBinding.ditherGroup.removeAllViews()
        ditherChips.clear()
        DitherMode.entries.forEach { mode ->
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = getString(mode.titleRes)
                isCheckable = true
                isClickable = true
                setOnClickListener {
                    if (!syncingChips) {
                        updateOptions { it.copy(dither = mode) }
                    }
                }
            }
            ditherChips[mode] = chip
            sheetBinding.ditherGroup.addView(chip)
        }
    }

    /** 把面板上的操作接回参数，改完立刻重新转换。 */
    private fun wireStyleSheet(sheetBinding: DialogStyleBinding, dialog: BottomSheetDialog) {
        sheetBinding.charWidthSb.setOnSeekBarChangeListener(
            object : SimpleSeekBarListener() {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) {
                        return
                    }
                    val width = AsciiArtOptions.percentToCharLineWidth(progress)
                    sheetBinding.charWidthValueTv.text =
                        getString(R.string.settings_char_width_value, width)
                    updateOptions { it.copy(charLineWidth = width) }
                }
            }
        )

        sheetBinding.colorFillSb.setOnSeekBarChangeListener(
            object : SimpleSeekBarListener() {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) {
                        return
                    }
                    sheetBinding.colorFillValueTv.text =
                        getString(R.string.settings_color_fill_value, progress)
                    updateOptions { it.copy(colorFillRate = progress / 100.0f) }
                }
            }
        )

        sheetBinding.blocksOnlySw.setOnCheckedChangeListener { _, checked ->
            if (!syncingSheet) {
                updateOptions { it.copy(blocksOnly = checked) }
                // 色块模式下有一批开关不再参与运算，当场置灰，免得用户点了没反应
                syncBlocksOnlyUi(sheetBinding)
            }
        }
        sheetBinding.pc98Sw.setOnCheckedChangeListener { _, checked ->
            if (!syncingSheet) {
                updateOptions { it.copy(pc98Palette = checked) }
                // 中间色处理、调色板都只在 16 色模式下才有意义，跟着开关走
                syncPc98Ui(sheetBinding)
            }
        }
        sheetBinding.paletteBtn.clicks { showPaletteDialog() }

        // 数字点一下就能直接敲——拖滑杆很难精确停在某个值上
        sheetBinding.charWidthValueTv.clicks { showCharWidthInput() }
        sheetBinding.colorFillValueTv.clicks { showColorFillInput() }
        sheetBinding.charReverseSw.setOnCheckedChangeListener { _, checked ->
            if (!syncingSheet) updateOptions { it.copy(reverseChar = checked) }
        }
        sheetBinding.colorReverseSw.setOnCheckedChangeListener { _, checked ->
            if (!syncingSheet) updateOptions { it.copy(reverseColor = checked) }
        }
        sheetBinding.keepColorSw.setOnCheckedChangeListener { _, checked ->
            if (!syncingSheet) updateOptions { it.copy(keepImageColor = checked) }
        }
        sheetBinding.monospaceSw.setOnCheckedChangeListener { _, checked ->
            if (!syncingSheet) updateOptions { it.copy(useMonospaceFont = checked) }
        }
        sheetBinding.textModeSw.setOnCheckedChangeListener { _, checked ->
            if (!syncingSheet) {
                textMode = checked
                result?.let { renderResult(it) }
            }
        }

        sheetBinding.resetBtn.setOnClickListener {
            // 「恢复默认」= 回到出厂状态，模式开关也一起复位
            updateOptions { Preset.default.applyTo(it).copy(blocksOnly = false) }
            styleSheetBinding?.let { syncStyleSheet(it) }
        }
        sheetBinding.doneBtn.setOnClickListener { dialog.dismiss() }
    }

    /** 改参数 → 同步面板高亮 → 防抖重转。 */
    private fun updateOptions(update: (AsciiArtOptions) -> AsciiArtOptions) {
        options = update(options)
        styleSheetBinding?.let { sheet ->
            val current = Preset.detect(options)
            syncPresetChips(sheet, current)
        }
        reschedule(CONVERT_DEBOUNCE_MS)
    }

    private fun applyPreset(preset: Preset) {
        options = preset.applyTo(options)
        styleSheetBinding?.let { syncStyleSheet(it) }
        reschedule(0L)
    }

    /**
     * 参数变了要重出一遍结果。
     *
     * 图片源重新解码转换，文字源直接拿原文重新渲染 —— 走错路的话，
     * 导入文字之后改样式会毫无反应。
     */
    private fun reschedule(delayMs: Long) {
        if (sourceIsText) {
            scheduleImportRender(delayMs)
        } else {
            scheduleConvert(delayMs)
        }
    }

    /** 把当前参数刷到面板控件上。 */
    private fun syncStyleSheet(sheetBinding: DialogStyleBinding) {
        syncingSheet = true
        try {
            syncPresetChips(sheetBinding, Preset.detect(options))

            sheetBinding.charWidthSb.progress = options.charLineWidthInPercent()
            sheetBinding.charWidthValueTv.text =
                getString(R.string.settings_char_width_value, options.charLineWidth)

            sheetBinding.colorFillSb.progress = options.colorFillRateInPercent()
            sheetBinding.colorFillValueTv.text =
                getString(R.string.settings_color_fill_value, options.colorFillRateInPercent())

            sheetBinding.charReverseSw.isChecked = options.reverseChar
            sheetBinding.colorReverseSw.isChecked = options.reverseColor
            sheetBinding.keepColorSw.isChecked = options.keepImageColor
            sheetBinding.monospaceSw.isChecked = options.useMonospaceFont
            sheetBinding.blocksOnlySw.isChecked = options.blocksOnly
            sheetBinding.pc98Sw.isChecked = options.pc98Palette
            sheetBinding.textModeSw.isChecked = textMode
            syncDitherChips(sheetBinding, options.dither)
            syncPc98Ui(sheetBinding)
            syncBlocksOnlyUi(sheetBinding)
        } finally {
            syncingSheet = false
        }
    }

    private fun syncDitherChips(sheetBinding: DialogStyleBinding, current: DitherMode) {
        syncingChips = true
        try {
            val chip = ditherChips[current]
            if (chip != null) {
                sheetBinding.ditherGroup.check(chip.id)
            } else {
                sheetBinding.ditherGroup.clearCheck()
            }
        } finally {
            syncingChips = false
        }
    }

    /**
     * 16 色模式没开时，「中间色处理」和「调色板」都无从谈起，一起置灰。
     *
     * 注意别和 [syncBlocksOnlyUi] 打架：两边动的控件不重叠——
     * 色块模式管字符相关的开关，这里管调色板相关的，PC-98 预设两个都开，正好互不干扰。
     */
    private fun syncPc98Ui(sheetBinding: DialogStyleBinding) {
        val enabled = options.pc98Palette
        val alpha = if (enabled) 1.0f else DISABLED_ALPHA
        // ChipGroup 的 isEnabled 管不到里面的 Chip——必须逐个关，
        // 不然置灰了照样能点，点了还看不出效果（因为算的时候压根没用上）
        ditherChips.values.forEach { it.isEnabled = enabled }
        sheetBinding.ditherGroup.alpha = alpha
        sheetBinding.paletteBtn.isEnabled = enabled
        sheetBinding.paletteBtn.alpha = alpha
        sheetBinding.paletteSummaryTv.alpha = alpha
        sheetBinding.paletteSummaryTv.text = paletteSummary()
    }

    /** @return 调色板当前是自动取色还是自己调的，和调了几个色。 */
    private fun paletteSummary(): String {
        val custom = options.customPalette
        return if (custom == null || custom.size < 2) {
            getString(R.string.palette_summary_auto)
        } else {
            getString(R.string.palette_summary_custom, custom.size)
        }
    }

    /**
     * 色块模式下有一批参数不参与运算（没有字符可画，配色也不该被"单色"掐掉），
     * 把它们置灰——用户点了没反应比按钮不可用更让人困惑。
     */
    private fun syncBlocksOnlyUi(sheetBinding: DialogStyleBinding) {
        val enabled = !options.blocksOnly
        val alpha = if (enabled) 1.0f else DISABLED_ALPHA
        listOf(
            sheetBinding.charReverseSw,
            sheetBinding.colorReverseSw,
            sheetBinding.keepColorSw,
            sheetBinding.monospaceSw
        ).forEach { switch ->
            switch.isEnabled = enabled
            (switch.parent as? View)?.alpha = alpha
        }
        sheetBinding.colorFillSb.isEnabled = enabled
        sheetBinding.colorFillSb.alpha = alpha
        // 这个数字现在是可点的，置灰了还留着能点就说不通了
        sheetBinding.colorFillValueTv.isEnabled = enabled
        sheetBinding.colorFillValueTv.alpha = alpha
    }

    private fun syncPresetChips(sheetBinding: DialogStyleBinding, current: Preset) {
        syncingChips = true
        try {
            val chip = presetChips[current]
            if (chip != null) {
                sheetBinding.presetGroup.check(chip.id)
            } else {
                sheetBinding.presetGroup.clearCheck()
            }
        } finally {
            syncingChips = false
        }
    }

    /** 只关心进度变化的 SeekBar 监听器，省得写一堆空方法。 */
    private abstract class SimpleSeekBarListener : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    // endregion

    // region 数字直输

    /**
     * 点数字直接敲，比拖滑杆精确。
     *
     * 输入不合法（空、非数字、超范围）就弹一句提示、**不动**当前值——
     * 用户边删边打的过程中必然是空字符串，不能因为一个中间态就把已经调好的参数清掉。
     *
     * [hintText] 收的是拼好的文本而不是资源 id：带参和不带参的提示混在一个函数里，
     * 很容易写出"没有占位符的串却传了两个实参"这种没意义还埋雷的调用。
     *
     * 参数名刻意不叫 `hint`：`apply` 块里 EditText 自己就有个 `hint` 属性，
     * 同名的话 `this.hint = hint` 会变成自我赋值，提示文字根本显示不出来。
     */
    private fun showNumberInput(
        @StringRes titleRes: Int,
        hintText: String,
        min: Int,
        max: Int,
        current: Int,
        onAccept: (Int) -> Unit
    ) {
        val density = resources.displayMetrics.density
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
            hint = hintText
            setPadding(
                (density * 4).toInt(),
                (density * 12).toInt(),
                (density * 4).toInt(),
                (density * 12).toInt()
            )
            selectAll()
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((density * 20).toInt(), (density * 4).toInt(), (density * 20).toInt(), 0)
            addView(
                input,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.settings_done) { _, _ ->
                val value = input.text.toString().trim().toIntOrNull()
                if (value == null || value < min || value > max) {
                    toast(getString(R.string.input_out_of_range, min, max))
                } else {
                    onAccept(value)
                }
            }
            .show()
    }

    private fun showCharWidthInput() {
        showNumberInput(
            titleRes = R.string.input_title_char_width,
            hintText = getString(
                R.string.input_hint_char_width,
                AsciiArtConverter.MIN_CHAR_LINE_WIDTH,
                AsciiArtConverter.MAX_CHAR_LINE_WIDTH
            ),
            min = AsciiArtConverter.MIN_CHAR_LINE_WIDTH,
            max = AsciiArtConverter.MAX_CHAR_LINE_WIDTH,
            current = options.charLineWidth
        ) { width ->
            updateOptions { it.copy(charLineWidth = width) }
            // 滑杆和数字都拉齐——只改一处的话，关掉面板再打开会看到滑杆在别的位置
            styleSheetBinding?.let { sheet ->
                sheet.charWidthSb.progress = options.charLineWidthInPercent()
                sheet.charWidthValueTv.text =
                    getString(R.string.settings_char_width_value, options.charLineWidth)
            }
        }
    }

    private fun showColorFillInput() {
        showNumberInput(
            titleRes = R.string.input_title_color_fill,
            hintText = getString(R.string.input_hint_color_fill),
            min = 0,
            max = 100,
            current = options.colorFillRateInPercent()
        ) { percent ->
            updateOptions { it.copy(colorFillRate = percent / 100.0f) }
            styleSheetBinding?.let { sheet ->
                sheet.colorFillSb.progress = options.colorFillRateInPercent()
                sheet.colorFillValueTv.text =
                    getString(R.string.settings_color_fill_value, options.colorFillRateInPercent())
            }
        }
    }

    // endregion

    // region 调色板面板

    /**
     * 调色板面板：16 个色块 + 每通道一根滑杆（0..15 级）。
     *
     * 起始色优先用用户自己调过的那套；没调过就拿画面**自动取出来的那 16 色**当起点——
     * 让用户以"眼前实际用的颜色"为基准微调，比从一套不相干的预设开始靠谱得多。
     * 全部改动先在本地副本上做，点「确定」才写回参数，中途退出一律不生效。
     */
    private fun showPaletteDialog() {
        val sheetBinding = DialogPaletteBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheetBinding.root)
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(ContextCompat.getColor(this, R.color.bg_surface_high))
        dialog.setOnDismissListener { swatchViews.clear() }

        editingPalette = seedPalette().toMutableList()
        editingSwatch = 0
        sheetBinding.paletteHintTv.text = getString(R.string.palette_hint, editingPalette.size)

        buildPaletteSwatches(sheetBinding)
        buildBuiltinChips(sheetBinding)
        wirePaletteEditor(sheetBinding, dialog)
        dialog.show()
    }

    /** @return 面板打开时的 16 个起始色。 */
    private fun seedPalette(): List<Int> {
        options.customPalette?.takeIf { it.size >= 2 }?.let { custom ->
            return fillTo16(custom)
        }
        result?.grid?.palette?.takeIf { it.size >= 2 }?.let { auto ->
            return fillTo16(auto.toList())
        }
        // 还没转过图：退回内置第一套，好歹有个能看能改的起点
        return Pc98Quantizer.BUILT_IN.first().second.toList()
    }

    /** 把不足 16 个的调色板补齐到 16（末色重复填），面板固定摆 16 个格子。 */
    private fun fillTo16(colors: List<Int>): List<Int> =
        List(Pc98Quantizer.PALETTE_SIZE) { index -> colors.getOrElse(index) { colors.last() } }

    /** 铺 16 个色块（4 列 × 4 行），点一下选中。 */
    private fun buildPaletteSwatches(sheetBinding: DialogPaletteBinding) {
        sheetBinding.swatchGrid.removeAllViews()
        swatchViews.clear()
        val density = resources.displayMetrics.density
        val size = (density * 34).toInt()
        val margin = (density * 4).toInt()
        for (index in editingPalette.indices) {
            val swatch = View(this).apply { clicks { selectSwatch(sheetBinding, index) } }
            val params = GridLayout.LayoutParams().apply {
                width = size
                height = size
                setMargins(margin, margin, margin, margin)
                columnSpec = GridLayout.spec(index % PALETTE_COLUMNS)
                rowSpec = GridLayout.spec(index / PALETTE_COLUMNS)
            }
            swatchViews.add(swatch)
            sheetBinding.swatchGrid.addView(swatch, params)
        }
        refreshSwatches()
    }

    /**
     * 重画色块的底色和描边。
     *
     * 选中态必须画一圈描边——黑块和深色背景糊在一起的时候，
     * 光看色块本身分不出哪个被选中了。
     */
    private fun refreshSwatches() {
        val density = resources.displayMetrics.density
        val accent = ContextCompat.getColor(this, R.color.accent)
        val divider = ContextCompat.getColor(this, R.color.divider)
        swatchViews.forEachIndexed { index, view ->
            val selected = index == editingSwatch
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = density * 6f
                setColor(editingPalette.getOrElse(index) { Color.BLACK })
                setStroke((density * (if (selected) 3f else 1f)).toInt(), if (selected) accent else divider)
            }
        }
    }

    /** 内置的那几套 16 色，点一下整盘换掉，换完还能接着用滑杆微调。 */
    private fun buildBuiltinChips(sheetBinding: DialogPaletteBinding) {
        sheetBinding.builtinGroup.removeAllViews()
        Pc98Quantizer.BUILT_IN.forEach { (name, palette) ->
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = name
                isCheckable = true
                isClickable = true
                setOnClickListener {
                    editingPalette = palette.toMutableList()
                    editingSwatch = 0
                    buildPaletteSwatches(sheetBinding)
                    syncChannelSliders(sheetBinding)
                }
            }
            sheetBinding.builtinGroup.addView(chip)
        }
    }

    private fun wirePaletteEditor(sheetBinding: DialogPaletteBinding, dialog: BottomSheetDialog) {
        listOf(sheetBinding.rSb to 0, sheetBinding.gSb to 1, sheetBinding.bSb to 2)
            .forEach { (slider, channel) ->
                slider.setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (!fromUser) {
                            return
                        }
                        setPaletteChannel(channel, progress)
                        refreshSwatches()
                        syncChannelSliders(sheetBinding)
                    }
                })
            }

        sheetBinding.paletteAutoBtn.clicks {
            // 「自动取色」= 清掉自定义，回到按画面中位切分
            updateOptions { it.copy(customPalette = null) }
            styleSheetBinding?.let { syncStyleSheet(it) }
            dialog.dismiss()
        }
        sheetBinding.paletteDoneBtn.clicks {
            updateOptions { it.copy(customPalette = editingPalette.toList()) }
            styleSheetBinding?.let { syncStyleSheet(it) }
            dialog.dismiss()
        }

        selectSwatch(sheetBinding, 0)
    }

    private fun selectSwatch(sheetBinding: DialogPaletteBinding, index: Int) {
        if (editingPalette.isEmpty()) {
            return
        }
        editingSwatch = index.coerceIn(0, editingPalette.lastIndex)
        refreshSwatches()
        syncChannelSliders(sheetBinding)
    }

    /** 改当前选中色的某一个通道（[channel] 0/1/2 = 红/绿/蓝）。 */
    private fun setPaletteChannel(channel: Int, level: Int) {
        if (editingSwatch !in editingPalette.indices) {
            return
        }
        val value = level.coerceIn(0, Pc98Quantizer.CHANNEL_LEVELS - 1) * Pc98Quantizer.CHANNEL_STEP
        val current = editingPalette[editingSwatch]
        editingPalette[editingSwatch] = when (channel) {
            0 -> Color.rgb(value, Color.green(current), Color.blue(current))
            1 -> Color.rgb(Color.red(current), value, Color.blue(current))
            else -> Color.rgb(Color.red(current), Color.green(current), value)
        }
    }

    /** 把当前选中色的三个通道刷到滑杆和数值文案上。 */
    private fun syncChannelSliders(sheetBinding: DialogPaletteBinding) {
        val color = editingPalette.getOrElse(editingSwatch) { Color.BLACK }
        // 量化后的值必是 17 的倍数，除掉就是 0..15 的级数
        val red = Color.red(color) / Pc98Quantizer.CHANNEL_STEP
        val green = Color.green(color) / Pc98Quantizer.CHANNEL_STEP
        val blue = Color.blue(color) / Pc98Quantizer.CHANNEL_STEP
        // 先设进度（fromUser=false 不会回调），再刷文案
        sheetBinding.rSb.progress = red
        sheetBinding.gSb.progress = green
        sheetBinding.bSb.progress = blue
        sheetBinding.rValueTv.text = getString(R.string.palette_channel, "R", red, Color.red(color))
        sheetBinding.gValueTv.text = getString(R.string.palette_channel, "G", green, Color.green(color))
        sheetBinding.bValueTv.text = getString(R.string.palette_channel, "B", blue, Color.blue(color))
    }

    // endregion

    // region 保存 / 复制 / 分享

    private fun baseName(): String = sourceName ?: "ascii"

    private fun savePngFile() {
        val converted = result
        if (converted == null) {
            toast(getString(R.string.toast_no_image))
            return
        }
        withStoragePermission {
            lifecycleScope.launch {
                try {
                    val saved = withContext(Dispatchers.IO) {
                        AsciiArtExporter.savePng(applicationContext, converted.bitmap, baseName())
                    }
                    toast(getString(R.string.toast_saved, saved.readablePath))
                } catch (e: Throwable) {
                    toast(getString(R.string.toast_save_failed, e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    private fun saveTextFile(text: String) {
        withStoragePermission {
            lifecycleScope.launch {
                try {
                    val saved = withContext(Dispatchers.IO) {
                        AsciiArtExporter.saveText(applicationContext, text, baseName())
                    }
                    toast(getString(R.string.toast_text_saved, saved.readablePath))
                } catch (e: Throwable) {
                    reportError(e, "保存 TXT")
                }
            }
        }
    }

    /** 把字符画当 .txt 分享出去，走 cache + FileProvider，不需要权限。 */
    private fun shareTextFile(text: String) {
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    AsciiArtExporter.writeTextToCache(applicationContext, text, baseName())
                }
                val intent = AsciiArtExporter.createShareIntent(
                    this@MainActivity,
                    file,
                    AsciiArtExporter.TEXT_MIME_TYPE
                )
                startActivity(Intent.createChooser(intent, getString(R.string.text_share)))
            } catch (e: Throwable) {
                reportError(e, "分享 TXT")
            }
        }
    }

    private fun shareResult() {
        val converted = result
        if (converted == null) {
            toast(getString(R.string.toast_no_result))
            return
        }
        // 分享走 cache 目录 + FileProvider，不需要任何权限
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    AsciiArtExporter.writePngToCache(applicationContext, converted.bitmap, baseName())
                }
                val intent = AsciiArtExporter.createShareIntent(
                    this@MainActivity,
                    file,
                    AsciiArtExporter.PNG_MIME_TYPE
                )
                startActivity(
                    Intent.createChooser(intent, getString(R.string.main_share))
                )
            } catch (e: Throwable) {
                toast(getString(R.string.toast_save_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun showAbout() {
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "1.0.0"
        // 把"存的语言"和"界面实际用的语言"都列出来，
        // 以后再出现"选了中文还是英文"，看这行就知道是哪一段没生效
        val setting = AppLanguage.load(this)
        val actual = resources.configuration.locales[0].toLanguageTag()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(
                getString(R.string.about_message, version) + "\n\n" +
                    getString(
                        R.string.about_language,
                        getString(setting.labelRes),
                        actual
                    )
            )
            .setPositiveButton(R.string.about_ok, null)
            .show()
    }

    /**
     * 调起拍照。
     *
     * 关键在 [grantCaptureUri]：**必须在启动之前把 Uri 权限显式授出去**，
     * 否则相机拿到 `content://…fileprovider/…` 却没有写权限，拍完什么都不写。
     */
    private fun launchCamera() {
        try {
            val file = AsciiArtExporter.newCaptureFile(this)
            val uri = AsciiArtExporter.uriForFile(this, file)
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(CAPTURE_GRANT_FLAGS)
            if (intent.resolveActivity(packageManager) == null) {
                throw ActivityNotFoundException("没有应用能处理 ACTION_IMAGE_CAPTURE")
            }
            grantCaptureUri(uri)
            pendingCaptureFile = file
            captureLauncher.launch(intent)
        } catch (e: Throwable) {
            pendingCaptureFile = null
            toast(getString(R.string.toast_capture_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * 把我们这个 FileProvider Uri 的读写权限**按包名**授给每一个能响应拍照的应用。
     *
     * 只在 intent 上加 `FLAG_GRANT_WRITE_URI_PERMISSION` 不够用：有些 ROM 的相机是"转发"的
     * （自己再起一个 Activity 去拍），转发时 flag 会丢。`grantUriPermission` 是直接按包授权，
     * 不经过 intent，转发也还在。
     *
     * 授权范围只覆盖刚创建的那**一个**拍照文件，不会放宽别的目录。
     * 收尾由系统管：这个 Activity 一销毁，非持久化的授权就会失效。
     */
    private fun grantCaptureUri(uri: Uri) {
        val probe = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
            .forEach { info ->
                runCatching {
                    grantUriPermission(info.activityInfo.packageName, uri, CAPTURE_GRANT_FLAGS)
                }
            }
    }

    private fun withStoragePermission(action: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (!AsciiArtExporter.needsWritePermission() || granted) {
            action()
            return
        }
        pendingPermissionAction = action
        storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    // endregion

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /**
     * 出错统一入口：写 Logcat、落盘，再弹一个能看全、能复制的详情框。
     *
     * 只弹 `e.message` 太容易抓瞎——很多异常 message 是 null 或只有半句，
     * 所以这里给完整堆栈，方便直接把内容发出来定位。
     */
    private fun reportError(throwable: Throwable, scene: String) {
        val detail = Diagnostics.record(applicationContext, throwable, scene)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.error_title, scene))
            .setMessage(detail)
            .setPositiveButton(R.string.error_copy) { _, _ ->
                copyPlainText(detail)
                toast(getString(R.string.toast_error_copied))
            }
            .setNegativeButton(R.string.about_ok, null)
            .show()
    }

    /**
     * 选中的图读不出来时的专属提示。
     *
     * 和 [reportError] 的区别是多给一个出口：**换文件选择器重选**。
     * 相册（照片选择器）给的是临时授权、还可能连的是云端图，读不到并不稀奇；
     * 而文件选择器走的是文档通道，拿到的是可持久化权限，同一张图基本都读得动。
     * 与其让用户对着错误框发呆，不如直接把能走通的那条路摆在按钮上。
     */
    private fun reportSourceError(throwable: Throwable, uri: Uri) {
        val detail = Diagnostics.record(applicationContext, throwable, SOURCE_SCENE)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.source_read_failed)
            .setMessage(detail)
            .setPositiveButton(R.string.error_copy) { _, _ ->
                copyPlainText(detail)
                toast(getString(R.string.toast_error_copied))
            }
            .setNeutralButton(R.string.source_retry_with_file) { _, _ ->
                pickFileLauncher.launch(arrayOf("image/*"))
            }
            .setNegativeButton(R.string.about_ok, null)
            .show()
    }

    /** 展示上一次记录下来的错误（含未捕获崩溃）。 */
    private fun showLastError() {
        val detail = Diagnostics.lastError(this)
        if (detail.isNullOrBlank()) {
            toast(getString(R.string.error_none))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.error_last_title)
            .setMessage(detail)
            .setPositiveButton(R.string.error_copy) { _, _ ->
                copyPlainText(detail)
                toast(getString(R.string.toast_error_copied))
            }
            .setNeutralButton(R.string.error_clear) { _, _ ->
                Diagnostics.clearLastError(this)
                toast(getString(R.string.error_cleared))
            }
            .setNegativeButton(R.string.about_ok, null)
            .show()
    }

    private fun copyPlainText(text: String) {
        runCatching {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        }
    }

    private fun View.clicks(action: () -> Unit) {
        setOnClickListener {
            runCatching { action() }.onFailure { reportError(it, "按钮点击") }
        }
    }

    /** 只关心"改完了"的文本监听，省得写一堆空方法。 */
    private fun EditText.doAfterTextChanged(action: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                action(s?.toString().orEmpty())
            }
        })
    }

    /** 输出宽度（像素）→ 滑杆进度（0..100）。 */
    private fun widthToPercent(width: Int): Int {
        val range = AsciiArtConverter.MAX_OUTPUT_WIDTH - AsciiArtConverter.MIN_IMPORT_WIDTH
        val fixed = width.coerceIn(
            AsciiArtConverter.MIN_IMPORT_WIDTH,
            AsciiArtConverter.MAX_OUTPUT_WIDTH
        )
        return ((fixed - AsciiArtConverter.MIN_IMPORT_WIDTH).toFloat() / range * 100.0f + 0.5f).toInt()
    }

    /** 滑杆进度（0..100）→ 输出宽度（像素）。 */
    private fun percentToWidth(percent: Int): Int {
        val range = AsciiArtConverter.MAX_OUTPUT_WIDTH - AsciiArtConverter.MIN_IMPORT_WIDTH
        return (percent.coerceIn(0, 100) / 100.0f * range + AsciiArtConverter.MIN_IMPORT_WIDTH + 0.5f).toInt()
    }

    private companion object {
        /** 拖滑杆时的防抖时间，等用户停一下再转，免得卡。 */
        private const val CONVERT_DEBOUNCE_MS = 220L

        /** 导入的文本落在 cache 里的文件名，切语言重建时用它把内容接回来。 */
        private const val IMPORT_STASH_FILE = "import-source.txt"

        /**
         * 授给相机的那**一个**拍照文件的权限。读也一起给——
         * 有的相机写完要回读做预览，只给写会失败。
         *
         * 用 `val` 而非 `const`：`Intent.FLAG_*` 是 Java 常量，但 Kotlin 里 `or`
         * 是 infix 函数，不保证能当编译期常量求值。
         */
        private val CAPTURE_GRANT_FLAGS =
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION

        /** 老式相机把缩略图塞在 extras 里用的键，就是字面量 `"data"`。 */
        private const val EXTRA_CAPTURE_BITMAP = "data"

        /** 导入面板边输边解析的上限，超过就不实时统计了。 */
        private const val MAX_LIVE_PARSE_LENGTH = 120_000
        private const val LIVE_PARSE_DEBOUNCE_MS = 250L

        /** 色块模式下被置灰的控件透明度。 */
        private const val DISABLED_ALPHA = 0.35f

        /** 调色板面板的色块列数（16 个色 → 4 × 4）。 */
        private const val PALETTE_COLUMNS = 4

        /**
         * 「文字结果」预览最多着色多少个字符。
         *
         * 真彩格式每个字符一个 span，格子多的时候能到上百万个，不截必 OOM。
         * 复制 / 保存 / 分享走的是完整文本，不受这个上限影响。
         */
        private const val MAX_PREVIEW_CHARS = 200_000

        private const val KEY_FILE = "state_file"
        private const val KEY_NAME = "state_name"
        private const val KEY_IS_TEXT = "state_is_text"
        private const val KEY_IMPORT_WIDTH = "state_import_width"
        private const val KEY_TEXT_MODE = "state_text_mode"
        private const val KEY_CHAR_WIDTH = "state_char_width"
        private const val KEY_REVERSE_CHAR = "state_reverse_char"
        private const val KEY_REVERSE_COLOR = "state_reverse_color"
        private const val KEY_COLOR_FILL = "state_color_fill"
        private const val KEY_FG_COLOR = "state_fg_color"
        private const val KEY_BG_COLOR = "state_bg_color"
        private const val KEY_KEEP_COLOR = "state_keep_color"
        private const val KEY_MONO_FONT = "state_mono_font"
        private const val KEY_BLOCKS_ONLY = "state_blocks_only"
        private const val KEY_PC98 = "state_pc98"
        private const val KEY_DITHER = "state_dither"
        private const val KEY_PALETTE = "state_palette"
        private const val KEY_TEXT_ASPECT = "state_text_aspect"
        private const val KEY_SRC_WIDTH = "state_src_width"
        private const val KEY_SRC_HEIGHT = "state_src_height"
        /** 待回填的拍照文件路径。界面被相机挤掉重建后靠它接回来。 */
        private const val KEY_CAPTURE = "state_capture"

        /** 选图读取失败的诊断场景名，和 [Diagnostics] 落盘里的那行一致，方便对照。 */
        private const val SOURCE_SCENE = "读取图片"
    }
}
