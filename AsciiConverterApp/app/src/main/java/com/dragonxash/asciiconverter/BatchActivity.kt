package com.dragonxash.asciiconverter

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dragonxash.asciiconverter.core.AsciiArtConverter
import com.dragonxash.asciiconverter.core.AsciiArtExporter
import com.dragonxash.asciiconverter.core.AsciiArtOptions
import com.dragonxash.asciiconverter.core.AsciiArtResult
import com.dragonxash.asciiconverter.core.Diagnostics
import com.dragonxash.asciiconverter.core.PictureDecoder
import com.dragonxash.asciiconverter.core.SourceCache
import com.dragonxash.asciiconverter.core.ThumbnailLoader
import com.dragonxash.asciiconverter.databinding.ActivityBatchBinding
import com.dragonxash.asciiconverter.databinding.ItemBatchBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 批量转换：一次选多张图，全部转换后一起存进相册。
 *
 * 用的是主界面当前那套样式参数，从 [createIntent] 带过来。
 */
class BatchActivity : BaseActivity() {

    private lateinit var binding: ActivityBatchBinding

    private val items = mutableListOf<BatchItem>()
    private lateinit var adapter: BatchAdapter

    private var options: AsciiArtOptions = AsciiArtOptions()
    private var working = false
    private var pendingPermissionAction: (() -> Unit)? = null

    private val pickLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK_COUNT)
    ) { uris ->
        if (uris.isNotEmpty()) {
            addItems(uris)
        }
    }

    /**
     * 文件浏览器多选（SAF）。
     *
     * 和主界面一个道理：相册（照片选择器）给的是 `content://media/picker/...` 这种
     * **临时授权** Uri，某些机型上连第一次都读不出来；SAF 给的
     * `content://com.android.externalstorage.documents/...` 走的是整套文档框架，
     * 兼容性好得多。
     *
     * 批量页以前只有相册一个入口——相册这条通道一挂，整个批量功能就成了死路，
     * 所以必须把这条路摆上来。
     *
     * 用 [PersistableOpenMultipleDocuments] 多要一个可持久化读权限：批量页退出时
     * 会把中转文件清掉，万一之后还想拿同一个 Uri 补捞一次，权限还在才捞得动。
     */
    private val pickFileLauncher = registerForActivityResult(
        PersistableOpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            addItems(uris)
        }
    }

    /** [ActivityResultContracts.OpenMultipleDocuments] 的加强版：额外申请可持久化读权限。 */
    private class PersistableOpenMultipleDocuments :
        ActivityResultContracts.OpenMultipleDocuments() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            super.createIntent(context, input).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
    }

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
        options = readOptions(intent)
        binding = ActivityBatchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = BatchAdapter()
        binding.listRv.layoutManager = LinearLayoutManager(this)
        binding.listRv.adapter = adapter

        binding.toolBar.setNavigationOnClickListener { finish() }
        binding.toolBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_pick -> {
                    showPickChooser()
                    true
                }

                R.id.action_clear -> {
                    clearAll()
                    true
                }

                else -> false
            }
        }

        binding.convertAllBtn.setOnClickListener { convertAll() }
        binding.saveAllBtn.setOnClickListener { saveAll() }

        refreshUi()
    }

    /**
     * 选图入口：让用户在「系统相册」和「文件浏览器」之间挑。
     *
     * 不给默认值是有意的——这台机器上哪条通道好使只有用户知道。
     * 两条路都摆在明面上，一条不通还有另一条。
     */
    private fun showPickChooser() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.batch_pick_title)
            .setItems(
                arrayOf(
                    getString(R.string.batch_pick_gallery),
                    getString(R.string.batch_pick_file)
                )
            ) { _, which ->
                if (which == 0) {
                    launchGalleryPick()
                } else {
                    launchFilePick()
                }
            }
            .show()
    }

    /** 相册这条路在部分机型上会抛 ActivityNotFoundException，兜住别让界面挂了。 */
    private fun launchGalleryPick() {
        runCatching {
            pickLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }.onFailure {
            Diagnostics.record(applicationContext, it, "批量选图（相册）")
            toast(getString(R.string.toast_source_failed, it.message ?: it.javaClass.simpleName))
        }
    }

    /** 文件浏览器（SAF）多选。 */
    private fun launchFilePick() {
        runCatching {
            pickFileLauncher.launch(arrayOf("image/*"))
        }.onFailure {
            Diagnostics.record(applicationContext, it, "批量选图（文件浏览器）")
            toast(getString(R.string.toast_source_failed, it.message ?: it.javaClass.simpleName))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        items.forEach { it.result?.bitmap?.recycle() }
        items.clear()
        ThumbnailLoader.clear()
        // 批量页是这些中转文件的唯一使用者，退出时一起收掉，别让它们留在 cache 里过夜
        SourceCache.clear(applicationContext)
    }

    // region 列表

    private fun addItems(uris: List<Uri>) {
        // 相册那条路有张数上限，文件浏览器没有——不加约束的话用户一次勾 300 张，
        // 中转文件和位图能把内存和 cache 目录一起顶满，所以两边统一按 MAX_PICK_COUNT 封顶。
        val room = MAX_PICK_COUNT - items.size
        if (room <= 0) {
            toast(getString(R.string.batch_pick_too_many, MAX_PICK_COUNT))
            return
        }
        val accepted = if (uris.size > room) {
            toast(getString(R.string.batch_pick_too_many, MAX_PICK_COUNT))
            uris.take(room)
        } else {
            uris
        }

        val start = items.size
        accepted.forEach { uri -> items.add(BatchItem(uri = uri, name = uri.lastPathSegment ?: "image")) }
        adapter.notifyItemRangeInserted(start, accepted.size)
        refreshUi()
        // 文件名异步补上，不阻塞列表显示
        lifecycleScope.launch {
            accepted.forEachIndexed { index, uri ->
                val name = withContext(Dispatchers.IO) {
                    PictureDecoder.queryDisplayName(applicationContext, uri)
                }
                val position = start + index
                if (position < items.size && name != null) {
                    items[position].name = name
                    adapter.notifyItemChanged(position)
                }
            }
        }
    }

    private fun clearAll() {
        items.forEach { it.result?.bitmap?.recycle() }
        val count = items.size
        items.clear()
        adapter.notifyItemRangeRemoved(0, count)
        refreshUi()
    }

    private fun refreshUi() {
        val hasItems = items.isNotEmpty()
        binding.emptyTv.visibility = if (hasItems) View.GONE else View.VISIBLE
        binding.listRv.visibility = if (hasItems) View.VISIBLE else View.GONE
        binding.countTv.text = getString(R.string.batch_selected_count, items.size)
    }

    // endregion

    // region 转换与保存

    private fun convertAll() {
        if (working) {
            return
        }
        val targets = items.indices.filter { items[it].state !is BatchState.Done }
        if (targets.isEmpty()) {
            toast(if (items.isEmpty()) getString(R.string.batch_nothing_to_convert) else getString(R.string.toast_no_result))
            return
        }
        working = true
        binding.convertAllBtn.isEnabled = false
        binding.progressPb.visibility = View.VISIBLE
        binding.progressPb.progress = 0

        lifecycleScope.launch {
            var failed = 0
            try {
                targets.forEachIndexed { order, index ->
                    val item = items.getOrNull(index) ?: return@forEachIndexed
                    item.state = BatchState.Converting
                    adapter.notifyItemChanged(index)
                    binding.progressPb.progress = order * 100 / targets.size
                    try {
                        // 先落到本地再进后台线程：相册给的 Uri 只读这一次，
                        // 之后无论重试多少次都是读本地文件
                        val local = ensureLocal(item)
                        val converted = withContext(Dispatchers.Default) { convertOne(local) }
                        item.result?.bitmap?.recycle()
                        item.result = converted
                        item.state = BatchState.Done
                    } catch (e: Throwable) {
                        // 完整堆栈写日志/落盘，列表里只显示一行，免得撑爆界面
                        Diagnostics.record(applicationContext, e, "批量转换")
                        item.state = BatchState.Failed(
                            (e.message ?: e.javaClass.simpleName).lineSequence().first().take(60)
                        )
                        failed++
                    }
                    adapter.notifyItemChanged(index)
                }
                binding.progressPb.progress = 100
            } finally {
                binding.progressPb.visibility = View.GONE
                binding.convertAllBtn.isEnabled = true
                working = false
            }
            // 整批一张都没成：几乎可以断定是「相册」这条通道在这台机器上不通，
            // 而不是图片有问题。这时候把用户丢在原地看一屏「失败」是最没用的，
            // 直接给一条换道的路（换文件浏览器重选）。
            if (targets.isNotEmpty() && failed == targets.size && targets.size == items.size) {
                showAllFailedDialog()
            }
        }
    }

    /**
     * 整批都读不出来时的出路。
     *
     * 只换入口不够——用户会以为"这软件读不了我的图"。所以顺手把注定读不出来的
     * 那些项清掉（它们全是同一个 Uri，重试多少次结果都一样），再跳到文件浏览器。
     */
    private fun showAllFailedDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.batch_all_failed_title)
            .setMessage(R.string.batch_all_failed_message)
            .setPositiveButton(R.string.source_retry_with_file) { _, _ ->
                clearAll()
                launchFilePick()
            }
            .setNegativeButton(R.string.about_ok, null)
            .show()
    }

    /**
     * 确保这一项已经有本地副本，返回本地文件。
     *
     * 相册给的 Uri 在整页生命周期里只被读**一次**，缩略图和正式转换共用同一个文件。
     * 列表滚动会反复重新绑定同一项，如果每次都去开相册的 Uri，滚几下就该 ENOENT 了——
     * 这也是批量页比单图页更容易翻车的原因。
     *
     * 并发调用是安全的：整个方法在同一个 [lifecycleScope]（主线程）里跑，
     * 检查和赋值之间没有挂起点，所以两个调用者只会共享同一个 [BatchItem.loading]。
     */
    private suspend fun ensureLocal(item: BatchItem): File {
        item.file?.let { return it }
        val job: Deferred<File> = item.loading ?: lifecycleScope.async(Dispatchers.IO) {
            SourceCache.materialize(applicationContext, item.uri)
        }.also { item.loading = it }
        return try {
            job.await().also { item.file = it }
        } finally {
            item.loading = null
        }
    }

    private fun convertOne(file: File): AsciiArtResult {
        val bitmap = PictureDecoder.decode(file)
        return try {
            AsciiArtConverter.convert(bitmap, options, needText = false)
        } finally {
            bitmap.recycle()
        }
    }

    private fun saveAll() {
        val done = items.filter { it.result != null }
        if (done.isEmpty()) {
            toast(getString(R.string.toast_no_result))
            return
        }
        withStoragePermission {
            lifecycleScope.launch {
                var saved = 0
                done.forEach { item ->
                    val bitmap = item.result?.bitmap ?: return@forEach
                    try {
                        withContext(Dispatchers.IO) {
                            AsciiArtExporter.savePng(applicationContext, bitmap, item.name)
                        }
                        saved++
                    } catch (e: Throwable) {
                        // 单张失败不影响其他张
                    }
                }
                toast(getString(R.string.batch_saved_count, saved))
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

    // region 适配器

    private inner class BatchAdapter : RecyclerView.Adapter<BatchAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(
                ItemBatchBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        inner class ViewHolder(private val itemBinding: ItemBatchBinding) :
            RecyclerView.ViewHolder(itemBinding.root) {

            private var thumbJob: Job? = null

            private val ctx: Context
                get() = itemBinding.root.context

            fun bind(item: BatchItem) {
                itemBinding.nameTv.text = item.name
                itemBinding.statusTv.text = when (val state = item.state) {
                    is BatchState.Pending -> ctx.getString(R.string.batch_item_pending)
                    is BatchState.Converting -> ctx.getString(R.string.batch_item_converting)
                    is BatchState.Done -> item.result?.let {
                        "${it.columns} x ${it.rows} · " +
                            ctx.getString(R.string.batch_item_done, it.charCount)
                    } ?: ctx.getString(R.string.batch_item_pending)

                    is BatchState.Failed -> ctx.getString(R.string.batch_item_failed) + " · " + state.message
                }
                itemBinding.itemPb.visibility =
                    if (item.state is BatchState.Converting) View.VISIBLE else View.GONE
                itemBinding.statusTv.setTextColor(
                    if (item.state is BatchState.Failed) {
                        Color.parseColor("#FF7A7A")
                    } else {
                        ContextCompat.getColor(ctx, R.color.text_secondary)
                    }
                )

                val uri = item.uri
                val key = uri.toString()
                itemBinding.thumbIv.tag = key
                itemBinding.thumbIv.setImageDrawable(null)
                thumbJob?.cancel()
                thumbJob = lifecycleScope.launch {
                    // 缩略图也读本地副本：滚动会反复重新绑定，读原 Uri 等于反复开相册的流
                    val local = runCatching { ensureLocal(item) }.getOrNull() ?: return@launch
                    val thumb = ThumbnailLoader.load(local, THUMB_SIZE_PX)
                    if (thumb != null && itemBinding.thumbIv.tag == key) {
                        itemBinding.thumbIv.setImageBitmap(thumb)
                    }
                }
            }
        }
    }

    // endregion

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /**
     * 列表里的一项。
     *
     * [uri] 只用来"第一次把数据捞出来"，捞完就靠 [file] 了。
     * 相册（照片选择器）给的授权是临时的，反复去开必然翻车，所以这里缓存本地副本。
     */
    private class BatchItem(
        val uri: Uri,
        var name: String,
        var state: BatchState = BatchState.Pending,
        var result: AsciiArtResult? = null
    ) {
        /** 本地副本，null 表示还没捞过。 */
        var file: File? = null

        /** 正在捞的那份工作，缩略图和转换同时找上门时共用，避免各捞一份。 */
        var loading: Deferred<File>? = null
    }

    private sealed interface BatchState {
        data object Pending : BatchState
        data object Converting : BatchState
        data object Done : BatchState
        data class Failed(val message: String) : BatchState
    }

    companion object {

        private const val MAX_PICK_COUNT = 50
        private const val THUMB_SIZE_PX = 160

        private const val EXTRA_CHAR_WIDTH = "char_width"
        private const val EXTRA_REVERSE_CHAR = "reverse_char"
        private const val EXTRA_REVERSE_COLOR = "reverse_color"
        private const val EXTRA_COLOR_FILL = "color_fill"
        private const val EXTRA_FOREGROUND = "foreground"
        private const val EXTRA_BACKGROUND = "background"
        private const val EXTRA_KEEP_COLOR = "keep_color"
        private const val EXTRA_MONOSPACE = "monospace"
        private const val EXTRA_BLOCKS_ONLY = "blocks_only"

        /** @return 带着当前样式参数去批量页，保证两边风格一致。 */
        fun createIntent(context: Context, options: AsciiArtOptions): Intent =
            Intent(context, BatchActivity::class.java).apply {
                putExtra(EXTRA_CHAR_WIDTH, options.charLineWidth)
                putExtra(EXTRA_REVERSE_CHAR, options.reverseChar)
                putExtra(EXTRA_REVERSE_COLOR, options.reverseColor)
                putExtra(EXTRA_COLOR_FILL, options.colorFillRate)
                putExtra(EXTRA_FOREGROUND, options.foregroundColor)
                putExtra(EXTRA_BACKGROUND, options.backgroundColor)
                putExtra(EXTRA_KEEP_COLOR, options.keepImageColor)
                putExtra(EXTRA_MONOSPACE, options.useMonospaceFont)
                putExtra(EXTRA_BLOCKS_ONLY, options.blocksOnly)
            }

        private fun readOptions(intent: Intent): AsciiArtOptions {
            val defaults = AsciiArtOptions()
            return defaults.copy(
                charLineWidth = intent.getIntExtra(EXTRA_CHAR_WIDTH, defaults.charLineWidth),
                reverseChar = intent.getBooleanExtra(EXTRA_REVERSE_CHAR, defaults.reverseChar),
                reverseColor = intent.getBooleanExtra(EXTRA_REVERSE_COLOR, defaults.reverseColor),
                colorFillRate = intent.getFloatExtra(EXTRA_COLOR_FILL, defaults.colorFillRate),
                foregroundColor = intent.getIntExtra(EXTRA_FOREGROUND, defaults.foregroundColor),
                backgroundColor = intent.getIntExtra(EXTRA_BACKGROUND, defaults.backgroundColor),
                keepImageColor = intent.getBooleanExtra(EXTRA_KEEP_COLOR, defaults.keepImageColor),
                useMonospaceFont = intent.getBooleanExtra(EXTRA_MONOSPACE, defaults.useMonospaceFont),
                blocksOnly = intent.getBooleanExtra(EXTRA_BLOCKS_ONLY, defaults.blocksOnly)
            )
        }
    }
}
