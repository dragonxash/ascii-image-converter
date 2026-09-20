package com.dragonxash.asciiconverter.core

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.annotation.StringRes
import com.dragonxash.asciiconverter.R

/** 颜色「没有设置」的占位值（真彩色永远带不透明 alpha，不会和它撞）。 */
private const val NO_COLOR = 0

private const val OPAQUE = 0xFF000000.toInt()

/**
 * 字符画的「带颜色文本」格式。
 *
 * 纯字符的 txt 里只有字符，颜色是真的丢了。想让文本带颜色，就得**在字符外面挂一段
 * ANSI 转义序列**告诉终端"这个字用什么颜色画"：
 *
 * ```
 * ESC[38;2;255;128;64m@
 * ```
 *
 * `38;2;R;G;B` 就是「24 位真彩前景色」，后面紧跟的字符就用这个颜色画。
 * 这样 txt 里躺着的是真实 RGB，导入时把转义序列剥掉，颜色就能原样还原。
 */
enum class AnsiTextFormat(@param:StringRes val titleRes: Int) {

    /** 纯字符，最干净、体积最小，任何地方都能看，但没有颜色。 */
    Plain(R.string.text_format_plain),

    /**
     * 16 色（PC-98 风）。
     *
     * PC-98 的图形模式是「从 4096 色里同屏选 16 色」，
     * 而 4096 色的来源是**每个通道只有 4 bit（16 级）**。
     * 这个模式把这两条限制都照搬：先从画面里挑出最合适的 16 个颜色，
     * 再把每个通道压到 16 级。
     */
    Palette16(R.string.text_format_16),

    /** 24 位真彩，一个格子一个颜色，无损。 */
    TrueColor(R.string.text_format_true);

    /** @return 文本大概会有多大（字节），给界面提示用。 */
    fun estimatedBytes(cellCount: Int): Int = when (this) {
        Plain -> cellCount + cellCount / 64
        Palette16 -> cellCount / 4 + cellCount / 8
        TrueColor -> cellCount * ESCAPE_TRUE_MAX
    }

    private companion object {
        /** `ESC[38;2;255;255;255m` 最长 20 字节。 */
        const val ESCAPE_TRUE_MAX = 20
    }
}

/**
 * ANSI 文本的编码 / 解码。
 *
 * 编码只产出两类东西：普通的字符，和 `ESC[38;2;R;G;Bm` 这样的前景色设置。
 * 解码则尽量宽容——除了自己产出的真彩序列，也认 16 色（`ESC[31m`）、
 * 256 色（`ESC[38;5;Nm`）这些别处来的转义，其它 SGR 参数（粗体、反显等）直接忽略。
 */
object AnsiText {

    /** ANSI 转义引导符。 */
    const val ESC = '\u001B'

    /**
     * 16 色模式的目标色数。
     *
     * 真正的量化算法（中位切分 + PC-98 12 位吸附）在 [Pc98Quantizer] 里，
     * 位图渲染那条路也要用同一套，所以归到那边去了。
     */
    private const val PALETTE_SIZE = Pc98Quantizer.PALETTE_SIZE

    /** xterm 默认的 16 色，解析别处来的 `ESC[31m` 时用。 */
    private val ANSI_16 = intArrayOf(
        0x000000, 0xCD0000, 0x00CD00, 0xCDCD00,
        0x0000EE, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
        0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00,
        0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF
    )

    /** 256 色里的 6×6×6 色立方体每一级的取值。 */
    private val CUBE_LEVELS = intArrayOf(0, 95, 135, 175, 215, 255)

    private val RESET = "$ESC[0m"

    // region 编码

    /**
     * 把网格编码成带颜色的文本。
     *
     * 同一个颜色**连续出现时只写一次转义**（终端里颜色会一直保持到下次修改），
     * 这在 16 色模式下能省掉大量重复，也是它比真彩小得多的原因。
     * 每行结束回一次车就重置颜色，免得串到下一行。
     */
    fun encode(grid: AsciiArtGrid, format: AnsiTextFormat): String {
        if (format == AnsiTextFormat.Plain) {
            return grid.toText()
        }
        val colors = resolveColors(grid, format)
        val builder = StringBuilder(
            format.estimatedBytes(grid.cellCount).coerceAtLeast(64)
        )
        var current = NO_COLOR
        for (row in 0 until grid.rows) {
            val end = trimmedEnd(grid, row)
            var rowHasColor = false
            for (column in 0 until end) {
                val color = colors[row * grid.columns + column]
                if (color != current) {
                    builder.append(escape(color))
                    current = color
                    rowHasColor = true
                }
                builder.append(grid.charAt(column, row))
            }
            if (rowHasColor) {
                builder.append(RESET)
                current = NO_COLOR
            }
            if (row != grid.rows - 1) {
                builder.append('\n')
            }
        }
        return builder.toString()
    }

    /** @return [row] 行去掉末尾空格后的长度，和 [AsciiArtGrid.toText] 保持一致。 */
    private fun trimmedEnd(grid: AsciiArtGrid, row: Int): Int {
        var end = grid.columns
        while (end > 0 && grid.charAt(end - 1, row) == ' ') {
            end--
        }
        return end
    }

    /** @return 每个格子最终要用的颜色；16 色模式下已经量化过。 */
    private fun resolveColors(grid: AsciiArtGrid, format: AnsiTextFormat): IntArray {
        val colors = IntArray(grid.cellCount)
        for (row in 0 until grid.rows) {
            for (column in 0 until grid.columns) {
                colors[row * grid.columns + column] = grid.colorAt(column, row) or OPAQUE
            }
        }
        if (format != AnsiTextFormat.Palette16) {
            return colors
        }
        val palette = Pc98Quantizer.quantize(colors, PALETTE_SIZE)
        for (index in colors.indices) {
            colors[index] = Pc98Quantizer.nearestIn(palette, colors[index])
        }
        return colors
    }

    private fun escape(color: Int): String {
        val builder = StringBuilder(20)
        builder.append(ESC).append("[38;2;")
        builder.append(Color.red(color)).append(';')
        builder.append(Color.green(color)).append(';')
        builder.append(Color.blue(color)).append('m')
        return builder.toString()
    }

    // endregion

    // region 解码

    /** 解码出来的一行：字符 + 每格的颜色（[NO_COLOR] 表示这格没有颜色）。 */
    class AnsiLine(val text: String, val colors: IntArray?) {

        val hasColor: Boolean
            get() = colors != null && colors.any { it != NO_COLOR }
    }

    /**
     * 解析带 ANSI 转义的文本。
     *
     * @return 每行的字符和颜色；文本里**没有** ANSI 转义时返回 null，
     *   调用方据此走原来的纯字符路径。
     */
    fun decode(raw: String): List<AnsiLine>? {
        if (!raw.contains(ESC)) {
            return null
        }
        val lines = mutableListOf<AnsiLine>()
        var text = StringBuilder()
        var colors = IntArray(64)
        var length = 0
        var current = NO_COLOR
        var sawEscape = false

        fun finishLine() {
            lines.add(AnsiLine(text.toString(), if (sawEscape) colors.copyOf(length) else null))
            text = StringBuilder()
            length = 0
        }

        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            if (char == ESC) {
                val params = sgrParamsAt(raw, index)
                if (params != null) {
                    current = applySgr(params, current)
                    sawEscape = true
                    index += params.length + 3
                    continue
                }
                index++
                continue
            }
            if (char == '\n') {
                finishLine()
                index++
                continue
            }
            if (char == '\r') {
                index++
                continue
            }
            text.append(char)
            if (length == colors.size) {
                colors = colors.copyOf(colors.size * 2)
            }
            colors[length] = current
            length++
            index++
        }
        finishLine()
        return lines
    }

    /** @return `ESC[` 和 `m` 之间的参数字符串；不是合法的 SGR 时返回 null。 */
    private fun sgrParamsAt(raw: String, start: Int): String? {
        if (start + 1 >= raw.length || raw[start + 1] != '[') {
            return null
        }
        var index = start + 2
        while (index < raw.length) {
            val char = raw[index]
            if (char == 'm') {
                return raw.substring(start + 2, index)
            }
            if (!char.isDigit() && char != ';') {
                return null
            }
            index++
        }
        return null
    }

    /**
     * 处理一条 SGR 参数串，返回更新后的前景色。
     *
     * 只关心前景色，其它参数（粗体 `1`、反显 `7`、下划线 `4` 等）看了就跳过。
     */
    private fun applySgr(params: String, current: Int): Int {
        if (params.isEmpty()) {
            return NO_COLOR
        }
        val parts = params.split(';')
        var color = current
        var index = 0
        while (index < parts.size) {
            val code = parts[index].toIntOrNull() ?: 0
            when {
                code == 0 || code == 39 -> color = NO_COLOR

                code in 30..37 -> color = ANSI_16[code - 30] or OPAQUE

                code in 90..97 -> color = ANSI_16[code - 90 + 8] or OPAQUE

                code == 38 || code == 48 -> {
                    val foreground = code == 38
                    when (parts.getOrNull(index + 1)?.toIntOrNull()) {
                        5 -> {
                            val entry = parts.getOrNull(index + 2)?.toIntOrNull() ?: -1
                            if (foreground) {
                                color = colorOf256(entry)
                            }
                            index += 2
                        }

                        2 -> {
                            val red = parts.getOrNull(index + 2)?.toIntOrNull() ?: 0
                            val green = parts.getOrNull(index + 3)?.toIntOrNull() ?: 0
                            val blue = parts.getOrNull(index + 4)?.toIntOrNull() ?: 0
                            if (foreground) {
                                color = Color.rgb(clamp(red), clamp(green), clamp(blue)) or OPAQUE
                            }
                            index += 4
                        }
                    }
                }
            }
            index++
        }
        return color
    }

    private fun clamp(value: Int): Int = value.coerceIn(0, 255)

    /** @return 256 色表里的第 [entry] 个颜色。 */
    private fun colorOf256(entry: Int): Int {
        val index = entry.coerceIn(0, 255)
        if (index < 16) {
            return ANSI_16[index] or OPAQUE
        }
        if (index < 232) {
            val offset = index - 16
            return Color.rgb(
                CUBE_LEVELS[offset / 36],
                CUBE_LEVELS[(offset / 6) % 6],
                CUBE_LEVELS[offset % 6]
            ) or OPAQUE
        }
        val level = 8 + (index - 232) * 10
        return Color.rgb(level, level, level) or OPAQUE
    }

    // endregion

    /**
     * 把带颜色的文本转成能在 TextView 里直接显示的彩色文本。
     *
     * @param maxChars 最多着色多少个字符（按**整行**截断，不会把一行切一半）。
     *
     *   这个上限是必须的：真彩格式下每个字符的颜色都不一样，span 数就等于字符数，
     *   1024 列 × 上千行能到上百万个 [ForegroundColorSpan]，直接 OOM。
     *   16 色格式因为相邻同色的字符会合并成一个 span，实际 span 数是"色块段数"，
     *   离上限很远，但依然走同一条路——上限只是兜底。
     * @return 带前景色 span 的内容；文本里没有 ANSI 转义时返回 null。
     */
    fun toColorSpans(raw: String, maxChars: Int = Int.MAX_VALUE): CharSequence? {
        val lines = decode(raw) ?: return null
        val builder = SpannableStringBuilder()
        var colored = false
        var used = 0
        lines.forEachIndexed { index, line ->
            if (used >= maxChars) {
                return@forEachIndexed
            }
            if (index > 0) {
                builder.append('\n')
            }
            val start = builder.length
            builder.append(line.text)
            used += line.text.length + 1
            val colors = line.colors ?: return@forEachIndexed
            var i = 0
            while (i < colors.size) {
                val color = colors[i]
                var j = i
                while (j < colors.size && colors[j] == color) {
                    j++
                }
                if (color != NO_COLOR && j > i) {
                    builder.setSpan(
                        ForegroundColorSpan(color),
                        start + i,
                        start + j,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    colored = true
                }
                i = j
            }
        }
        return if (colored) builder else null
    }

    /**
     * @return 文本里出现了多少个不同的颜色（用来在界面上显示"用了 N 色"）。
     *
     * 只统计 0..255 之间的通道值，避免把 alpha 也算进去造成颜色爆炸式增长。
     */
    fun distinctColorCount(raw: String): Int {
        val lines = decode(raw) ?: return 0
        val seen = HashSet<Int>()
        lines.forEach { line ->
            line.colors?.forEach { if (it != NO_COLOR) seen.add(it) }
        }
        return seen.size
    }

    /** @return 去掉所有 ANSI 转义之后的纯字符文本。 */
    fun stripAnsi(raw: String): String {
        if (!raw.contains(ESC)) {
            return raw
        }
        val builder = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            if (char == ESC) {
                val params = sgrParamsAt(raw, index)
                if (params != null) {
                    index += params.length + 3
                    continue
                }
            }
            builder.append(char)
            index++
        }
        return builder.toString()
    }

    /** @return 两个颜色在感知上的接近程度，给调试和验证用。 */
    fun difference(a: Int, b: Int): Int = Pc98Quantizer.difference(a, b)
}
