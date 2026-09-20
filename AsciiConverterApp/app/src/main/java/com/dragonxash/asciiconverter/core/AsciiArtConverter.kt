package com.dragonxash.asciiconverter.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.annotation.ColorInt
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 把图片转成 ASCII 字符画。
 *
 * 流程：图片按 [AsciiArtOptions.charLineWidth] 缩成"列数 × 行数"的网格（格子是正方形），
 * 每格按 BT.601 亮度查 [ASCII_CHARS] 选一个字符，再逐格绘制。
 *
 * [AsciiArtOptions.colorFillRate] 控制"亮度低于阈值的格子用原图颜色还是 [AsciiArtOptions.foregroundColor]"，
 * 这就是彩色字符画的来源；[AsciiArtOptions.reverseChar] 交换亮暗映射（浅色底用），
 * [AsciiArtOptions.reverseColor] 把字符形状从色块里抠出来。
 *
 * 全部是 CPU + Canvas 实现，不依赖任何第三方库。
 */
object AsciiArtConverter {

    const val MIN_CHAR_LINE_WIDTH = 16
    const val MAX_CHAR_LINE_WIDTH = 1024
    const val DEFAULT_CHAR_LINE_WIDTH = 128

    /** 输出 PNG 的最大宽度，防止超大图爆内存。 */
    const val MAX_OUTPUT_WIDTH = 2160

    /**
     * 网格格子总数上限。
     *
     * 每一格在内存里要占 4 个 Int（字符下标 / 颜色 / 亮度 / 采样像素），
     * 100 万格就是 16MB，再叠上位图就很容易 OOM 了。
     * 列数是用户说了算的，所以只能从**行数**上封顶——
     * 超过就把行数砍下来（极端长宽比的图会被略微压扁，好过直接崩）。
     */
    const val MAX_CELL_COUNT = 1_500_000

    /** 「文字导入」输出图片的默认宽度。 */
    const val DEFAULT_IMPORT_WIDTH = 1080

    /** 「文字导入」允许的最小输出宽度。 */
    const val MIN_IMPORT_WIDTH = 240

    /**
     * 输出位图的像素数上限（约 32MB，ARGB_8888）。
     *
     * 正常照片算出来的画布远小于这个数，不会受影响；
     * 只有极端长宽比（比如 1×N 的细长条、超长截图）才会撞上，
     * 撞上就等比缩下来，总比直接 OutOfMemoryError 强。
     */
    private const val MAX_OUTPUT_PIXELS = 8_000_000

    /**
     * 等宽字符的字形大约"高是宽的 2 倍"，所以按字符网格导出文本时要
     * 把行数压到这个比例，图片才不会被拉长。
     */
    const val DEFAULT_TEXT_ASPECT_RATIO = 0.5f
    const val MIN_TEXT_ASPECT_RATIO = 0.2f
    const val MAX_TEXT_ASPECT_RATIO = 1.0f

    /** 92 级字符表，从最暗（空格）到最亮（@）排列。 */
    const val ASCII_CHARS = " `.-':_,^=;><+!rc*/z?sLTv)J7(|Fi{C}fI31tlu[neoZ5Yxjya]2ESwqkP6h9d4VpOGbUAKXHm8RD#\$Bg0MNWQ%&@"

    /**
     * 字符 → 在 [ASCII_CHARS] 里的下标，用来做「文字导入」（文字 → 图片）的反向映射。
     *
     * 表覆盖 ASCII 可见范围（0..126），表里没有的字符返回 -1。
     */
    private val CHAR_TO_INDEX: IntArray = IntArray(127) { -1 }.also { table ->
        ASCII_CHARS.forEachIndexed { index, char ->
            val code = char.code
            if (code in table.indices) {
                table[code] = index
            }
        }
    }

    /**
     * 字符表外的字符往哪归。
     *
     * 常见字符画里偶尔会用 `"` `\` `~` 这几个不在表里的符号，给个形状相近的替代；
     * 非 ASCII（中文、日文、emoji 等）统一当实心块处理。
     */
    private val CHAR_ALIAS = mapOf(
        '"' to '\'',
        '\\' to '/',
        '~' to '-'
    )

    /**
     * 「文字导入」用的伪彩色色标（亮度 0 → 1）。
     *
     * 导出的 txt 里只有字符，**原图颜色是真的丢了**，没法还原。
     * 所以按格子的亮度走一条光谱：暗处偏紫蓝、亮处偏金红，观感接近热成像。
     * 这样「彩色」风格和「色彩填充」滑杆对导入的文本才有实际作用，
     * 不点彩色时这套颜色根本不会被用到。
     */
    private val SPECTRUM_STOPS = arrayOf(
        0.00f to intArrayOf(0x3B, 0x1F, 0x6E), // 深紫
        0.25f to intArrayOf(0x2D, 0x6B, 0xE0), // 蓝
        0.50f to intArrayOf(0x24, 0xC9, 0xA8), // 青绿
        0.75f to intArrayOf(0xF2, 0xC2, 0x30), // 金黄
        1.00f to intArrayOf(0xF2, 0x44, 0x4E)  // 红
    )

    /** @return 亮度 [luma]（0..255）在光谱上的颜色（不透明）。 */
    @JvmStatic
    fun spectrumColor(luma: Int): Int {
        val t = luma.coerceIn(0, 255) / 255.0f
        var i = 0
        while (i < SPECTRUM_STOPS.size - 2 && t > SPECTRUM_STOPS[i + 1].first) {
            i++
        }
        val stop0 = SPECTRUM_STOPS[i]
        val stop1 = SPECTRUM_STOPS[i + 1]
        val span = stop1.first - stop0.first
        val k = if (span <= 0f) 0f else ((t - stop0.first) / span).coerceIn(0f, 1f)
        val r = (stop0.second[0] + (stop1.second[0] - stop0.second[0]) * k).roundToInt()
        val g = (stop0.second[1] + (stop1.second[1] - stop0.second[1]) * k).roundToInt()
        val b = (stop0.second[2] + (stop1.second[2] - stop0.second[2]) * k).roundToInt()
        return Color.rgb(
            r.coerceIn(0, 255),
            g.coerceIn(0, 255),
            b.coerceIn(0, 255)
        ) or OPAQUE_ALPHA
    }

    /** 每个字符对应的亮度阈值，和 [ASCII_CHARS] 一一对应。 */
    private val ASCII_CHARS_LIGHT_LEVEL = doubleArrayOf(
        0.0, 0.0751, 0.0829, 0.0848, 0.1227, 0.1403, 0.1559, 0.185, 0.2183, 0.2417, 0.2571, 0.2852,
        0.2902, 0.2919, 0.3099, 0.3192, 0.3232, 0.3294, 0.3384, 0.3609, 0.3619, 0.3667, 0.3737, 0.3747,
        0.3838, 0.3921, 0.396, 0.3984, 0.3993, 0.4075, 0.4091, 0.4101, 0.42, 0.423, 0.4247, 0.4274,
        0.4293, 0.4328, 0.4382, 0.4385, 0.442, 0.4473, 0.4477, 0.4503, 0.4562, 0.458, 0.461, 0.4638,
        0.4667, 0.4686, 0.4693, 0.4703, 0.4833, 0.4881, 0.4944, 0.4953, 0.4992, 0.5509, 0.5567, 0.5569,
        0.5591, 0.5602, 0.5602, 0.565, 0.5776, 0.5777, 0.5818, 0.587, 0.5972, 0.5999, 0.6043, 0.6049,
        0.6093, 0.6099, 0.6465, 0.6561, 0.6595, 0.6631, 0.6714, 0.6759, 0.6809, 0.6816, 0.6925, 0.7039,
        0.7086, 0.7235, 0.7302, 0.7332, 0.7602, 0.7834, 0.8037, 0.9999
    )

    /** 亮度(0..255) -> 字符下标，预先算好省得每次都查表。 */
    private val CHAR_INDEX_TABLE = IntArray(256) { charIndexForLightLevel(it / 255.0) }

    /** 字符反转模式用的查表。 */
    private val CHAR_INDEX_TABLE_REVERSED = IntArray(256) { CHAR_INDEX_TABLE[255 - it] }

    /** 字符占格子高度的比例，让字形顶到格子上沿，和等宽字体的实际字形接近。 */
    private const val GLYPH_TEXT_SIZE_RATIO = 1.1f

    private const val RGB_MASK = 0x00FFFFFF

    /** @return [color] 的 BT.601 亮度，0(纯黑)..255(纯白)。 */
    @JvmStatic
    fun lumaOf(@ColorInt color: Int): Int =
        (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color))
            .roundToInt().coerceIn(0, 255)

    /** @return 亮度 [luma] 应该用 [ASCII_CHARS] 里第几个字符。 */
    @JvmStatic
    fun charIndexForLuma(luma: Int, reverseChar: Boolean): Int {
        val level = luma.coerceIn(0, 255)
        return if (reverseChar) CHAR_INDEX_TABLE_REVERSED[level] else CHAR_INDEX_TABLE[level]
    }

    /** @return 亮度 [luma] 对应的字符。 */
    @JvmStatic
    fun charForLuma(luma: Int, reverseChar: Boolean): Char =
        ASCII_CHARS[charIndexForLuma(luma, reverseChar)]

    /** 找最接近 [inputLightLevel] 的阈值下标。 */
    private fun charIndexForLightLevel(inputLightLevel: Double): Int {
        var targetPreIndex = 0
        var targetNextIndex = -1
        for (i in ASCII_CHARS_LIGHT_LEVEL.indices) {
            if (ASCII_CHARS_LIGHT_LEVEL[i] < inputLightLevel) {
                targetPreIndex = i
            }
            if (ASCII_CHARS_LIGHT_LEVEL[i] > inputLightLevel) {
                targetNextIndex = i
                break
            }
        }
        return if (targetNextIndex < 0) {
            ASCII_CHARS_LIGHT_LEVEL.lastIndex
        } else {
            val preValue = ASCII_CHARS_LIGHT_LEVEL[targetPreIndex]
            val nextValue = ASCII_CHARS_LIGHT_LEVEL[targetNextIndex]
            if (abs(inputLightLevel - preValue) > abs(inputLightLevel - nextValue)) {
                targetNextIndex
            } else {
                targetPreIndex
            }
        }
    }

    /**
     * 一次性算出字符网格、位图和文本，图片只缩放两次（位图一次、文本一次）。
     *
     * @param needText 是否同时生成文本结果，批量转换时可以关掉省时间。
     */
    fun convert(source: Bitmap, options: AsciiArtOptions, needText: Boolean = true): AsciiArtResult {
        val fixed = options.clamp()
        val grid = buildGrid(source, fixed, forText = false)
        val bitmap = renderBitmap(source, grid, fixed)
        // 文本用的网格是按字形高宽比压缩过行数的，和位图的网格不是同一个
        val textGrid = if (needText) buildGrid(source, fixed, forText = true) else null
        return AsciiArtResult(
            bitmap = bitmap,
            text = textGrid?.toText(),
            columns = grid.columns,
            rows = grid.rows,
            charCount = grid.cellCount,
            grid = textGrid
        )
    }

    /** @return 只有位图的结果。 */
    fun renderImage(source: Bitmap, options: AsciiArtOptions): Bitmap {
        val fixed = options.clamp()
        return renderBitmap(source, buildGrid(source, fixed, forText = false), fixed)
    }

    /** @return 只有文本的结果。 */
    fun renderText(source: Bitmap, options: AsciiArtOptions): String {
        val fixed = options.clamp()
        return buildGrid(source, fixed, forText = true).toText()
    }

    // region 文字导入（文字 → 图片）

    /**
     * @return [char] 在 [ASCII_CHARS] 里的下标。
     *
     * 表里没有的字符按 [CHAR_ALIAS] 换成形状相近的；非 ASCII（中文、日文、emoji 等）
     * 统一当实心块处理，不会因为字符缺失把画面开个洞。
     */
    @JvmStatic
    fun tableIndexOf(char: Char): Int {
        val code = char.code
        if (code in CHAR_TO_INDEX.indices) {
            val direct = CHAR_TO_INDEX[code]
            if (direct >= 0) {
                return direct
            }
        }
        CHAR_ALIAS[char]?.let { alias ->
            val aliasIndex = CHAR_TO_INDEX[alias.code]
            if (aliasIndex >= 0) {
                return aliasIndex
            }
        }
        return CHAR_TO_INDEX['#'.code].coerceAtLeast(0)
    }

    /**
     * 把一段字符画文本解析成网格。
     *
     * 每行按最长的一行补空格，行首行尾的空行会被去掉（中间的空行保留，它是画面的一部分）。
     * 这里不做亮暗映射——文本已经是最终字符了，[AsciiArtOptions.reverseChar] 对它没有意义。
     *
     * @throws IllegalArgumentException 文本里一个可见字符都没有时抛出。
     */
    fun parseAsciiText(text: String): AsciiArtGrid {
        val lines = importLines(text)
        val columns = lines.maxOfOrNull { it.text.length } ?: 0
        require(columns > 0 && lines.isNotEmpty()) { "文本里没有可用的字符" }
        val rows = lines.size
        val cellCount = columns * rows
        val charIndices = IntArray(cellCount)
        val cellColors = IntArray(cellCount)
        val cellLumas = IntArray(cellCount)
        for (row in 0 until rows) {
            val line = lines[row]
            for (column in 0 until columns) {
                val cell = row * columns + column
                val char = if (column < line.text.length) line.text[column] else ' '
                val index = tableIndexOf(char)
                charIndices[cell] = index
                val luma =
                    (ASCII_CHARS_LIGHT_LEVEL[index] * 255.0).roundToInt().coerceIn(0, 255)
                cellLumas[cell] = luma
                // 文本自带 ANSI 颜色就用它（真彩色可以原样还原）；
                // 纯字符文本没有颜色，按亮度合成一个伪彩色，留给「彩色」风格用
                val carried = line.colorAt(column)
                cellColors[cell] = if (carried != 0) carried else spectrumColor(luma)
            }
        }
        return AsciiArtGrid(columns, rows, charIndices, cellColors, cellLumas)
    }

    /** 导入时的一行：去掉 ANSI 转义之后的字符 + 每格颜色（0 = 没带颜色）。 */
    private class ImportLine(val text: String, private val colors: IntArray?) {

        fun colorAt(column: Int): Int {
            val array = colors ?: return 0
            return if (column < array.size) array[column] else 0
        }
    }

    /**
     * 把文本拆成导入用的行。
     *
     * 先试着按 ANSI 文本解析（带颜色的粘贴内容走这条路），
     * 解析不出来就按纯文本处理（统一换行、制表符换成空格、去掉首尾空行）。
     */
    private fun importLines(text: String): List<ImportLine> {
        val decoded = AnsiText.decode(text)
        if (decoded != null) {
            val trimmed = decoded.dropWhile { it.text.isBlank() }
                .dropLastWhile { it.text.isBlank() }
            if (trimmed.isNotEmpty()) {
                return trimmed.map { ImportLine(it.text, it.colors) }
            }
        }
        return normalizeLines(text).map { ImportLine(it, null) }
    }

    /**
     * 「文字导入」：把字符画文本渲染回图片。
     *
     * 行高取 `字宽 / textAspectRatio`——App 自己导出的文本是按 [AsciiArtOptions.textAspectRatio]
     * 压缩过行数的，这里乘回去，图片长宽比就和原图一致（默认 0.5 → 行高是字宽的 2 倍）。
     * 互联网上的字符画也普遍按「一个字高约等于两个字宽」排版，所以这个默认值同样合适。
     *
     * 文本里没带 ANSI 颜色时，网格的颜色字段是 [spectrumColor] 按亮度合成的伪彩色：
     * [AsciiArtOptions.keepImageColor] 打开（即「彩色」风格）时会用上它，
     * 关掉时字符统一用 [AsciiArtOptions.foregroundColor]，跟以前一样。
     * 带了 ANSI 颜色（本 App 的 16 色 / 真彩格式）就直接用文本里的真实颜色。
     *
     * @param outputWidth 输出图片宽度（像素），会被限制在
     *   [MIN_IMPORT_WIDTH]..[MAX_OUTPUT_WIDTH] 之间，也会受像素预算约束。
     */
    fun importText(
        text: String,
        options: AsciiArtOptions,
        outputWidth: Int = DEFAULT_IMPORT_WIDTH
    ): AsciiArtResult {
        val fixed = options.clamp()
        val grid = parseAsciiText(text)
        val width = outputWidth.coerceIn(MIN_IMPORT_WIDTH, MAX_OUTPUT_WIDTH)
        val bitmap = renderGrid(
            grid = grid,
            options = fixed,
            targetWidthIn = width,
            rowHeightIn = width.toFloat() / grid.columns.toFloat() / fixed.textAspectRatio,
            centerGlyphVertically = true
        )
        return AsciiArtResult(
            bitmap = bitmap,
            // 原样返回（只统一换行），复制/存 TXT 时把用户导入的内容还给他，
            // 里面的 ANSI 颜色码也保留着
            text = text.replace("\r\n", "\n").replace('\r', '\n'),
            columns = grid.columns,
            rows = grid.rows,
            charCount = grid.cellCount,
            grid = grid
        )
    }

    /** @return 拆行：统一换行符、制表符换成空格、去掉首尾的空行。 */
    private fun normalizeLines(text: String): List<String> {
        val unified = text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ")
        val lines = unified.split('\n')
        var start = 0
        var end = lines.size
        while (start < end && lines[start].isBlank()) {
            start++
        }
        while (end > start && lines[end - 1].isBlank()) {
            end--
        }
        return lines.subList(start, end)
    }

    // endregion

    /**
     * @return 字符网格。[forText] 为 true 时行数会按 [AsciiArtOptions.textAspectRatio] 压缩，
     *   这是给文本导出用的，位图导出必须传 false。
     */
    fun buildGrid(source: Bitmap, options: AsciiArtOptions, forText: Boolean = false): AsciiArtGrid {
        val fixed = options.clamp()
        val columns = fixed.charLineWidth
        val rows = rowsOf(source, fixed, columns, forText)
        val cellCount = columns * rows
        val charIndices = IntArray(cellCount)
        val cellColors = IntArray(cellCount)
        val cellLumas = IntArray(cellCount)
        val cellPixels = IntArray(cellCount)
        val scaled = scaleToCells(source, fixed.backgroundColor, columns, rows)
        try {
            scaled.getPixels(cellPixels, 0, columns, 0, 0, columns, rows)
        } finally {
            if (scaled !== source) {
                scaled.recycle()
            }
        }
        for (i in 0 until cellCount) {
            val color = cellPixels[i]
            val luma = lumaOf(color)
            charIndices[i] = charIndexForLuma(luma, fixed.reverseChar)
            cellColors[i] = color and RGB_MASK
            cellLumas[i] = luma
        }
        // PC-98 画风：把颜色压到 16 色调色板上，中间色用抖动表现
        var usedPalette: IntArray? = null
        val finalColors = if (fixed.pc98Palette) {
            val palette = fixed.customPalette
                ?.toIntArray()
                ?.takeIf { it.size >= 2 }
                ?: Pc98Quantizer.quantize(cellColors, Pc98Quantizer.PALETTE_SIZE)
            usedPalette = palette
            Pc98Quantizer.apply(cellColors, columns, rows, palette, fixed.dither)
        } else {
            cellColors
        }
        return AsciiArtGrid(
            columns = columns,
            rows = rows,
            charIndices = charIndices,
            cellColors = finalColors,
            cellLumas = cellLumas,
            palette = usedPalette
        )
    }

    /**
     * 网格行数：列数 × 高/宽，格子保持正方形。
     * 文本模式再乘 [AsciiArtOptions.textAspectRatio] 补偿字形高宽比。
     *
     * 最后按 [MAX_CELL_COUNT] 给行数封顶——列数是用户定的，只能从行这边限。
     */
    private fun rowsOf(source: Bitmap, options: AsciiArtOptions, columns: Int, forText: Boolean): Int {
        val width = source.width.coerceAtLeast(1).toFloat()
        val height = source.height.coerceAtLeast(1).toFloat()
        val aspectRatio = if (forText) options.textAspectRatio else 1.0f
        val wanted = max(1, (columns.toFloat() * height / width * aspectRatio).toInt())
        val maxRows = max(1, MAX_CELL_COUNT / max(1, columns))
        return min(wanted, maxRows)
    }

    /** 把图片缩到网格大小，透明像素先按背景色压平。 */
    private fun scaleToCells(
        source: Bitmap,
        @ColorInt backgroundColor: Int,
        columns: Int,
        rows: Int
    ): Bitmap {
        val flat = if (source.hasAlpha()) {
            Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also { composed ->
                val canvas = Canvas(composed)
                canvas.drawColor(backgroundColor)
                canvas.drawBitmap(source, 0.0f, 0.0f, Paint(Paint.FILTER_BITMAP_FLAG))
            }
        } else {
            source
        }
        val scaled = Bitmap.createScaledBitmap(flat, columns, rows, true)
        if (flat !== source && flat !== scaled) {
            flat.recycle()
        }
        return scaled
    }

    /** 把网格画成一张图（格子是正方形）。 */
    private fun renderBitmap(source: Bitmap, grid: AsciiArtGrid, options: AsciiArtOptions): Bitmap {
        val targetWidth = source.width.coerceIn(grid.columns, MAX_OUTPUT_WIDTH)
        return renderGrid(
            grid = grid,
            options = options,
            targetWidthIn = targetWidth,
            rowHeightIn = targetWidth.toFloat() / grid.columns.toFloat(),
            centerGlyphVertically = false
        )
    }

    /**
     * 把网格画成位图，普通渲染和文字导入共用。
     *
     * @param rowHeightIn 每行的高度（像素）。普通渲染传字宽，得到正方形格子；
     *   文字导入传「字宽 / textAspectRatio」，让字符画保持原本的长宽比。
     * @param centerGlyphVertically 行高和字宽不一致时，是否把字形在行内垂直居中。
     *   普通渲染传 false，沿用原滤镜“从格子上沿开始画”的做法，保证既有观感不变。
     */
    private fun renderGrid(
        grid: AsciiArtGrid,
        options: AsciiArtOptions,
        targetWidthIn: Int,
        rowHeightIn: Float,
        centerGlyphVertically: Boolean
    ): Bitmap {
        var targetWidth = targetWidthIn.coerceAtLeast(grid.columns)
        var targetHeight = max(1, (rowHeightIn * grid.rows.toFloat() + 0.5f).toInt())
        // 极端长宽比会算出超大画布，先按像素预算等比缩回来
        val pixels = targetWidth.toLong() * targetHeight.toLong()
        if (pixels > MAX_OUTPUT_PIXELS) {
            val scale = sqrt(MAX_OUTPUT_PIXELS.toDouble() / pixels.toDouble())
            targetWidth = max(grid.columns, (targetWidth * scale).toInt())
            targetHeight = max(1, (targetHeight * scale).toInt())
        }
        val cellWidth = targetWidth.toFloat() / grid.columns.toFloat()
        val cellHeight = targetHeight.toFloat() / grid.rows.toFloat()
        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(options.backgroundColor)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            isFilterBitmap = true
            typeface = options.glyphTypeface()
            textAlign = Paint.Align.LEFT
            textSize = cellWidth * GLYPH_TEXT_SIZE_RATIO
        }
        val fontMetrics = paint.fontMetrics
        val fontHeight = fontMetrics.bottom - fontMetrics.top
        // 和视频滤镜一致：从格子上沿开始画。
        val baselineInCellRatio = if (fontHeight <= 0f) 0.8f else -fontMetrics.top / fontHeight
        val cellRect = Rect()
        for (row in 0 until grid.rows) {
            val cellTop = row * cellHeight
            val baseline = if (centerGlyphVertically) {
                // 行高和字形高度不一样时，把字形在行内垂直居中
                cellTop + (cellHeight - fontHeight) / 2.0f - fontMetrics.top
            } else {
                cellTop + baselineInCellRatio * cellHeight
            }
            for (column in 0 until grid.columns) {
                val cellLeft = column * cellWidth
                if (options.blocksOnly) {
                    // 色块模式：整格填满该格的原图颜色，不画字符。
                    // 空格在字符模式下等于留白，这里必须一起填，否则画面会漏出一堆背景色的洞。
                    cellRect.set(
                        cellLeft.toInt(),
                        cellTop.toInt(),
                        (cellLeft + cellWidth).toInt() + 1,
                        (cellTop + cellHeight).toInt() + 1
                    )
                    paint.color = grid.colorAt(column, row) or OPAQUE_ALPHA
                    canvas.drawRect(cellRect, paint)
                    continue
                }
                val char = grid.charAt(column, row)
                if (char == ' ' && !options.reverseColor) {
                    continue
                }
                val luma = grid.lumaAt(column, row)
                val useImageColor = options.keepImageColor && options.colorFillRate > luma / 255.0f
                val charColor = if (useImageColor) {
                    grid.colorAt(column, row) or OPAQUE_ALPHA
                } else {
                    options.foregroundColor
                }
                val charX = cellLeft + max((cellWidth - paint.measureText(char.toString())) / 2.0f, 0.0f)
                if (options.reverseColor) {
                    // 颜色反转 = 整个格子涂满，再用背景色把字符形状"写"回去。
                    cellRect.set(
                        cellLeft.toInt(),
                        cellTop.toInt(),
                        (cellLeft + cellWidth).toInt() + 1,
                        (cellTop + cellHeight).toInt() + 1
                    )
                    paint.color = charColor
                    canvas.drawRect(cellRect, paint)
                    paint.color = options.backgroundColor
                    canvas.drawText(char.toString(), charX, baseline, paint)
                } else {
                    paint.color = charColor
                    canvas.drawText(char.toString(), charX, baseline, paint)
                }
            }
        }
        return output
    }

    private const val OPAQUE_ALPHA = 0xFF000000.toInt()
}

/**
 * 转换结果：位图 + 文本 + 网格尺寸。
 */
data class AsciiArtResult(
    val bitmap: Bitmap,
    /** 文本结果，[AsciiArtConverter.convert] 传 needText=false 时为 null。 */
    val text: String?,
    val columns: Int,
    val rows: Int,
    val charCount: Int,
    /**
     * 文本网格，用来按不同格式重新编码文本（纯字符 / 16 色 / 真彩）。
     *
     * 批量转换时为 null——那边只关心位图，50 张图的网格留在内存里不划算。
     */
    val grid: AsciiArtGrid? = null
) {

    val info: String
        get() = "$columns x $rows"
}

/**
 * 字符网格：每个格子的字符下标 + 原图颜色 + 亮度。
 */
class AsciiArtGrid internal constructor(
    val columns: Int,
    val rows: Int,
    private val charIndices: IntArray,
    private val cellColors: IntArray,
    private val cellLumas: IntArray,
    /**
     * 这张网格实际用到的调色板（PC-98 画风开着时才不为 null）。
     *
     * 留着它是为了「调色板」面板能拿画面上真实的 16 色当起点——
     * 自动取色出来的那 16 个色，用户看得见才好接着微调。
     */
    val palette: IntArray? = null
) {

    val cellCount: Int
        get() = charIndices.size

    fun charAt(column: Int, row: Int): Char =
        AsciiArtConverter.ASCII_CHARS[charIndexAt(column, row)]

    fun charIndexAt(column: Int, row: Int): Int =
        charIndices[row * columns + column].coerceIn(0, AsciiArtConverter.ASCII_CHARS.length - 1)

    @ColorInt
    fun colorAt(column: Int, row: Int): Int = cellColors[row * columns + column]

    fun lumaAt(column: Int, row: Int): Int = cellLumas[row * columns + column]

    /** 导出成文本，顺便去掉每行末尾多余的空格。 */
    fun toText(trimTrailingSpaces: Boolean = true): String {
        val builder = StringBuilder(cellCount + rows)
        for (row in 0 until rows) {
            var end = columns
            if (trimTrailingSpaces) {
                while (end > 0 && charIndexAt(end - 1, row) == SPACE_CHAR_INDEX) {
                    end--
                }
            }
            for (column in 0 until end) {
                builder.append(charAt(column, row))
            }
            if (row != rows - 1) {
                builder.append('\n')
            }
        }
        return builder.toString()
    }

    private companion object {
        /** 空格在字符表里的下标。 */
        private const val SPACE_CHAR_INDEX = 0
    }
}

/**
 * 转换参数。
 */
data class AsciiArtOptions(
    /** 每行字符数，[AsciiArtConverter.MIN_CHAR_LINE_WIDTH]..[AsciiArtConverter.MAX_CHAR_LINE_WIDTH]。 */
    val charLineWidth: Int = AsciiArtConverter.DEFAULT_CHAR_LINE_WIDTH,
    /** 亮暗对调，浅色底用它。 */
    val reverseChar: Boolean = false,
    /** 字符形状从色块里抠出来。 */
    val reverseColor: Boolean = false,
    /**
     * 亮度低于这个比例的格子用原图颜色画，其余用 [foregroundColor]。
     * 0 = 纯单色，越大越多彩色。
     */
    val colorFillRate: Float = 0.0f,
    /** 单色模式下字符的颜色。 */
    @param:ColorInt val foregroundColor: Int = Color.WHITE,
    /** 背景色。 */
    @param:ColorInt val backgroundColor: Int = Color.BLACK,
    /** 是否允许用原图颜色画字符（总开关）。 */
    val keepImageColor: Boolean = true,
    /** 等宽字体还是系统默认字体。 */
    val useMonospaceFont: Boolean = true,
    /**
     * 只保留色块：整格填该格的原图颜色，不画任何字符。
     *
     * 相当于把画面降采样成一张马赛克——格子多大由 [charLineWidth] 决定。
     * 和 PC-98 的 16 色量化叠起来用，出来的就是复古像素画。
     *
     * 注意：这个模式下 [keepImageColor]、[colorFillRate]、[reverseChar]、
     * [reverseColor]、[useMonospaceFont] 都不参与运算（没有字符可画，配色也不该被"单色"掐掉）。
     */
    val blocksOnly: Boolean = false,
    /**
     * 开「PC-98 画风」：把所有格子的颜色压到一张 16 色调色板上。
     *
     * PC-98 的图形模式是「同屏 16 色，从 4096 色（每通道 4 bit）里选」，
     * 打开这个就等于主动接受那两条约束，出来的就是当年那种配色。
     */
    val pc98Palette: Boolean = false,
    /** 抖动方式，用来表现 16 色直接画不出来的中间色（渐变）。 */
    val dither: DitherMode = DitherMode.Ordered,
    /**
     * 手动指定的调色板（16 个 `0xRRGGBB`，已吸到 12 位）。
     * 为 null 时按画面自动取色（中位切分）。
     */
    val customPalette: List<Int>? = null,
    /** 文本导出的行高比例，见 [AsciiArtConverter.DEFAULT_TEXT_ASPECT_RATIO]。 */
    val textAspectRatio: Float = AsciiArtConverter.DEFAULT_TEXT_ASPECT_RATIO
) {

    /** @return 字符宽度的百分比表示（0..100），给滑杆用。 */
    fun charLineWidthInPercent(): Int = charLineWidthToPercent(charLineWidth)

    /** @return 文本行高比例的百分比表示（0..100）。 */
    fun textAspectRatioInPercent(): Int = (textAspectRatio * 100.0f + 0.5f).toInt()

    /** @return 原图彩色填充的百分比表示（0..100）。 */
    fun colorFillRateInPercent(): Int = (colorFillRate * 100.0f + 0.5f).toInt()

    internal fun clamp(): AsciiArtOptions = copy(
        charLineWidth = charLineWidth.coerceIn(
            AsciiArtConverter.MIN_CHAR_LINE_WIDTH,
            AsciiArtConverter.MAX_CHAR_LINE_WIDTH
        ),
        colorFillRate = colorFillRate.coerceIn(0.0f, 1.0f),
        textAspectRatio = textAspectRatio.coerceIn(
            AsciiArtConverter.MIN_TEXT_ASPECT_RATIO,
            AsciiArtConverter.MAX_TEXT_ASPECT_RATIO
        )
    )

    internal fun glyphTypeface(): Typeface = if (useMonospaceFont) Typeface.MONOSPACE else Typeface.DEFAULT

    companion object {

        /** @return 滑杆进度(0..100)对应的字符宽度。 */
        fun percentToCharLineWidth(percent: Int): Int {
            val fixedPercent = percent.coerceIn(0, 100)
            return (fixedPercent.toFloat() / 100.0f *
                (AsciiArtConverter.MAX_CHAR_LINE_WIDTH - AsciiArtConverter.MIN_CHAR_LINE_WIDTH).toFloat() +
                AsciiArtConverter.MIN_CHAR_LINE_WIDTH.toFloat() + 0.5f).toInt()
        }

        /** @return 字符宽度对应的滑杆进度(0..100)。 */
        fun charLineWidthToPercent(charLineWidth: Int): Int {
            val fixedWidth = charLineWidth.coerceIn(
                AsciiArtConverter.MIN_CHAR_LINE_WIDTH,
                AsciiArtConverter.MAX_CHAR_LINE_WIDTH
            )
            return ((fixedWidth.toFloat() - AsciiArtConverter.MIN_CHAR_LINE_WIDTH.toFloat()) /
                (AsciiArtConverter.MAX_CHAR_LINE_WIDTH - AsciiArtConverter.MIN_CHAR_LINE_WIDTH).toFloat() *
                100.0f + 0.5f).toInt()
        }
    }
}
