package com.tans.tasciiartplayer.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.annotation.ColorInt
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Convert a picture to ascii art.
 *
 * The player already has an ascii filter for videos
 * (`com.tans.tmediaplayer.player.playerview.filter.AsciiArtImageFilter`), this class is a CPU
 * (Canvas based) port of it, so a picture gets the same look and the same settings as a video:
 *
 * 1. The picture is scaled down to [AsciiArtOptions.charLineWidth] columns and
 *    `columns * height / width` rows, every grid cell is a square.
 * 2. Every cell is mapped to a char of [ASCII_CHARS] by its BT.601 luma
 *    (`0.299 * r + 0.587 * g + 0.114 * b`), using the same 92 levels table as the filter.
 * 3. Every char is drawn inside its cell, with the cell average color when
 *    `cellLuma < colorFillRate` otherwise with [AsciiArtOptions.foregroundColor].
 *    [AsciiArtOptions.reverseColor] punches the char shape out of the cell(inverted glyph),
 *    [AsciiArtOptions.reverseChar] flips the luma -> char mapping.
 *
 * Different to the filter, the result can also be exported as text([AsciiArtGrid.toText]) or
 * as a picture([renderImage]) file.
 */
object AsciiArtConverter {

    const val MIN_CHAR_LINE_WIDTH = 16
    const val MAX_CHAR_LINE_WIDTH = 256
    const val DEFAULT_CHAR_LINE_WIDTH = 128

    /** Max width(px) of the rendered ascii picture, to avoid oom on huge photos. */
    const val MAX_OUTPUT_WIDTH = 2160

    /**
     * The glyph of a monospace font is about twice as tall as it is wide, so the text output
     * needs only half of the rows of the square cell grid to keep the picture aspect.
     */
    const val DEFAULT_TEXT_ASPECT_RATIO = 0.5f

    /** The same chars table as tMediaPlayer's ascii filter, light level -> char. */
    const val ASCII_CHARS = " `.-':_,^=;><+!rc*/z?sLTv)J7(|Fi{C}fI31tlu[neoZ5Yxjya]2ESwqkP6h9d4VpOGbUAKXHm8RD#\$Bg0MNWQ%&@"

    /** The light level of every char of [ASCII_CHARS], same table as the filter. */
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

    /** luma(0..255) -> char index. */
    private val ASCII_LIGHT_LEVEL_INDEX: IntArray = IntArray(256) { charIndexForLightLevel(it / 255.0) }

    /** luma(0..255) -> char index, for the reversed char mode. */
    private val ASCII_LIGHT_LEVEL_INDEX_REVERSE: IntArray = IntArray(256) { ASCII_LIGHT_LEVEL_INDEX[255 - it] }

    /** How many pixels of the cell height a char takes, same as the filter's glyph texture. */
    private const val GLYPH_TEXT_SIZE_RATIO = 1.1f

    /**
     * @return the luma of [color], 0(pure black)..255(pure white).
     */
    @JvmStatic
    fun lumaOf(@ColorInt color: Int): Int =
        (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)).roundToInt().coerceIn(0, 255)

    /**
     * @return the index of the char of [ASCII_CHARS] which should be used for [luma].
     */
    @JvmStatic
    fun charIndexForLuma(luma: Int, reverseChar: Boolean): Int {
        val level = luma.coerceIn(0, 255)
        return if (reverseChar) ASCII_LIGHT_LEVEL_INDEX_REVERSE[level] else ASCII_LIGHT_LEVEL_INDEX[level]
    }

    /**
     * @return the char of [ASCII_CHARS] which should be used for [luma].
     */
    @JvmStatic
    fun charForLuma(luma: Int, reverseChar: Boolean): Char =
        ASCII_CHARS[charIndexForLuma(luma, reverseChar)]

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
     * @return the cell grid of [source].
     */
    fun buildGrid(source: Bitmap, options: AsciiArtOptions): AsciiArtGrid =
        buildGrid(source, options, forText = false)

    /**
     * @return the ascii art text of [source].
     */
    fun renderText(source: Bitmap, options: AsciiArtOptions, trimTrailingSpaces: Boolean = true): String =
        buildGrid(source, options, forText = true).toText(trimTrailingSpaces = trimTrailingSpaces)

    /**
     * @return the ascii art picture of [source].
     */
    fun renderImage(source: Bitmap, options: AsciiArtOptions): Bitmap {
        val fixedOptions = options.clamp()
        val grid = buildGrid(source, fixedOptions, forText = false)
        val targetWidth = source.width.coerceIn(grid.columns, MAX_OUTPUT_WIDTH)
        val cellSize = targetWidth.toFloat() / grid.columns.toFloat()
        val targetHeight = max(1, (cellSize * grid.rows.toFloat() + 0.5f).toInt())
        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(fixedOptions.backgroundColor)

        val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            isFilterBitmap = true
            typeface = fixedOptions.glyphTypeface()
            textAlign = Paint.Align.LEFT
            textSize = cellSize * GLYPH_TEXT_SIZE_RATIO
        }
        val fontMetrics = glyphPaint.fontMetrics
        val fontHeight = fontMetrics.bottom - fontMetrics.top
        // Same as the filter: draw the char from the top of the cell.
        val baselineInCellRatio = if (fontHeight <= 0f) 0.8f else -fontMetrics.top / fontHeight
        val cellRect = Rect()
        for (row in 0 until grid.rows) {
            val cellTop = row * cellSize
            val baseline = cellTop + baselineInCellRatio * cellSize
            for (column in 0 until grid.columns) {
                val char = grid.charAt(column, row)
                if (char == ' ' && !fixedOptions.reverseColor) {
                    continue
                }
                val luma = grid.lumaAt(column, row)
                val useImageColor = fixedOptions.keepImageColor && fixedOptions.colorFillRate > luma / 255.0f
                val charColor = if (useImageColor) {
                    grid.colorAt(column, row) or OPAQUE_ALPHA
                } else {
                    fixedOptions.foregroundColor
                }
                val cellLeft = column * cellSize
                val charX = cellLeft + max((cellSize - glyphPaint.measureText(char.toString())) / 2.0f, 0.0f)
                if (fixedOptions.reverseColor) {
                    // Reverse color = the char shape is punched out of a solid cell.
                    cellRect.set(
                        cellLeft.toInt(),
                        cellTop.toInt(),
                        (cellLeft + cellSize).toInt() + 1,
                        (cellTop + cellSize).toInt() + 1
                    )
                    glyphPaint.color = charColor
                    canvas.drawRect(cellRect, glyphPaint)
                    glyphPaint.color = fixedOptions.backgroundColor
                    canvas.drawText(char.toString(), charX, baseline, glyphPaint)
                } else {
                    glyphPaint.color = charColor
                    canvas.drawText(char.toString(), charX, baseline, glyphPaint)
                }
            }
        }
        return output
    }

    private const val OPAQUE_ALPHA = 0xFF000000.toInt()

    private fun buildGrid(source: Bitmap, options: AsciiArtOptions, forText: Boolean): AsciiArtGrid {
        val fixedOptions = options.clamp()
        val columns = fixedOptions.charLineWidth
        val rows = rowsOf(source, fixedOptions, columns, forText)
        val cellCount = columns * rows
        val charIndices = IntArray(cellCount)
        val cellColors = IntArray(cellCount)
        val cellLumas = IntArray(cellCount)
        val cellPixels = IntArray(cellCount)
        val scaled = scaleToCells(source, fixedOptions.backgroundColor, columns, rows)
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
            charIndices[i] = charIndexForLuma(luma, fixedOptions.reverseChar)
            cellColors[i] = color and RGB_MASK
            cellLumas[i] = luma
        }
        return AsciiArtGrid(
            columns = columns,
            rows = rows,
            charIndices = charIndices,
            cellColors = cellColors,
            cellLumas = cellLumas
        )
    }

    private const val RGB_MASK = 0x00FFFFFF

    /**
     * Rows of the cell grid, same as the filter: `columns * height / width`.
     * For the text output the rows are corrected by [AsciiArtOptions.textAspectRatio].
     */
    private fun rowsOf(source: Bitmap, options: AsciiArtOptions, columns: Int, forText: Boolean): Int {
        val width = source.width.coerceAtLeast(1).toFloat()
        val height = source.height.toFloat()
        val aspectRatio = if (forText) options.textAspectRatio else 1.0f
        return max(1, (columns.toFloat() * height / width * aspectRatio).toInt())
    }

    /**
     * Scale [source] to the cell grid(with the average color of every cell), transparent pixels
     * are composited on [backgroundColor] first.
     */
    private fun scaleToCells(source: Bitmap, @ColorInt backgroundColor: Int, columns: Int, rows: Int): Bitmap {
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
}

/**
 * The result of [AsciiArtConverter]: chars + the source color/luma of every cell.
 */
class AsciiArtGrid internal constructor(
    val columns: Int,
    val rows: Int,
    private val charIndices: IntArray,
    private val cellColors: IntArray,
    private val cellLumas: IntArray
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

    /**
     * @param trimTrailingSpaces trim the redundant spaces of every line, keeps the file smaller.
     */
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
        /** Index of the space char in [AsciiArtConverter.ASCII_CHARS]. */
        private const val SPACE_CHAR_INDEX = 0
    }
}

/**
 * Setting of the ascii art conversion, the same knobs as the player's ascii filter.
 */
data class AsciiArtOptions(
    /** Chars of every line, [AsciiArtConverter.MIN_CHAR_LINE_WIDTH]..[AsciiArtConverter.MAX_CHAR_LINE_WIDTH]. */
    val charLineWidth: Int = AsciiArtConverter.DEFAULT_CHAR_LINE_WIDTH,
    /** Flip the luma -> char mapping, dark picture becomes light ascii art. */
    val reverseChar: Boolean = false,
    /** Punch the char shape out of the cell, same as the filter's reverse color. */
    val reverseColor: Boolean = false,
    /** Chars with a cell luma lower than this rate are drawn with the picture color, else with [foregroundColor]. */
    val colorFillRate: Float = 0.0f,
    /** Color of the chars, when they are not drawn with the picture color. */
    @ColorInt val foregroundColor: Int = Color.WHITE,
    /** Color of the ascii art background. */
    @ColorInt val backgroundColor: Int = Color.BLACK,
    /** Draw a char with the color of its cell instead of [foregroundColor]. */
    val keepImageColor: Boolean = true,
    /** The player's filter uses the default font, a monospace font looks more like a terminal. */
    val useMonospaceFont: Boolean = true,
    /** Rows ratio of the text output, see [AsciiArtConverter.DEFAULT_TEXT_ASPECT_RATIO]. */
    val textAspectRatio: Float = AsciiArtConverter.DEFAULT_TEXT_ASPECT_RATIO
) {

    /** Progress(0..100) of the char line width seek bar, same as the player's settings dialog. */
    fun charLineWidthInPercent(): Int = charLineWidthToPercent(charLineWidth)

    fun textAspectRatioInPercent(): Int = (textAspectRatio * 100.0f + 0.5f).toInt()

    internal fun clamp(): AsciiArtOptions = copy(
        charLineWidth = charLineWidth.coerceIn(
            AsciiArtConverter.MIN_CHAR_LINE_WIDTH,
            AsciiArtConverter.MAX_CHAR_LINE_WIDTH
        ),
        colorFillRate = colorFillRate.coerceIn(0.0f, 1.0f),
        textAspectRatio = textAspectRatio.coerceIn(MIN_TEXT_ASPECT_RATIO, MAX_TEXT_ASPECT_RATIO)
    )

    internal fun glyphTypeface(): Typeface = if (useMonospaceFont) Typeface.MONOSPACE else Typeface.DEFAULT

    companion object {

        const val MIN_TEXT_ASPECT_RATIO = 0.2f
        const val MAX_TEXT_ASPECT_RATIO = 1.0f

        /** @return the char line width of a seek bar progress(0..100). */
        fun percentToCharLineWidth(percent: Int): Int {
            val fixedPercent = percent.coerceIn(0, 100)
            return (fixedPercent.toFloat() / 100.0f *
                (AsciiArtConverter.MAX_CHAR_LINE_WIDTH - AsciiArtConverter.MIN_CHAR_LINE_WIDTH).toFloat() +
                AsciiArtConverter.MIN_CHAR_LINE_WIDTH.toFloat() + 0.5f).toInt()
        }

        /** @return the seek bar progress(0..100) of a char line width. */
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
