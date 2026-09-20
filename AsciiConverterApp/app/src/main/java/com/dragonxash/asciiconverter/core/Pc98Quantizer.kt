package com.dragonxash.asciiconverter.core

import android.graphics.Color
import androidx.annotation.StringRes
import com.dragonxash.asciiconverter.R
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 抖动方式（dithering）——用有限几种颜色的**点阵组合**去糊出中间色。
 *
 * 16 色能直接画出来的颜色只有 16 种，像渐变色这种"中间色"按理画不出来。
 * 抖动的办法是：相邻格子轮流用两种颜色，隔远看合成出中间色——就是老杂志
 * 印刷网点那套把戏，也是 PC-98 时代表现渐变的标准手段。
 */
enum class DitherMode(@param:StringRes val titleRes: Int) {

    /** 不抖动，每格直接取最接近的颜色。色块感最强。 */
    None(R.string.dither_none),

    /** 有序抖动（Bayer 8×8 网点）。颗粒规则、稳定，最像当年的印刷网点。 */
    Ordered(R.string.dither_ordered),

    /** 误差扩散（Floyd–Steinberg）。渐变最平滑，代价是多了点噪。 */
    Diffuse(R.string.dither_diffuse)
}

/**
 * PC-98 画风的量化与抖动。
 *
 * PC-98 的两条硬约束都在这儿落地：
 *
 * | 约束 | 实现 |
 * | --- | --- |
 * | 同屏 16 色 | 中位切分从画面里挑 16 个代表色（[quantize]） |
 * | 每通道 4 bit | 每个通道吸到 16 级（[snapToPc98]），值必是 17 的倍数 |
 *
 * 这**不是固定色表**——每个程序（每张图）自己往调色板里填颜色，
 * 所以同一张照片和一张插画挑出来的 16 色完全不一样。
 */
object Pc98Quantizer {

    /** 同屏色数。 */
    const val PALETTE_SIZE = 16

    /** 每个通道的级数（4 bit）。 */
    const val CHANNEL_LEVELS = 16

    /** 相邻量化级的间隔：15 × 17 = 255。 */
    const val CHANNEL_STEP = 17

    /** [snappedColorCount] 数到几种就停，够排序用了。 */
    private const val DISTINCT_PROBE_CAP = 8

    /** 标准 Bayer 8×8 阈值矩阵，取值 0..63。 */
    private val BAYER_8 = intArrayOf(
        0, 32, 8, 40, 2, 34, 10, 42,
        48, 16, 56, 24, 50, 18, 58, 26,
        12, 44, 4, 36, 14, 46, 6, 38,
        60, 28, 52, 20, 62, 30, 54, 22,
        3, 35, 11, 43, 1, 33, 9, 41,
        51, 19, 59, 27, 49, 17, 57, 25,
        15, 47, 7, 39, 13, 45, 5, 37,
        63, 31, 55, 23, 61, 29, 53, 21
    )

    // region 量化

    /**
     * 中位切分（median cut）：从画面里挑出最多 [maxColors] 个代表色。
     *
     * 这正是当年 PC-98 画师干的事——每张图都得从 4096 色里选 16 个。
     * 算法很直白：把所有格子塞进一个桶，反复把某个桶沿最宽通道从中间劈开，
     * 直到色数够了，每个桶取平均色。
     *
     * 有两个地方容易写错，都会白白浪费色位（16 个槽位只给出 6 种颜色）：
     *
     * 1. **判停看"不同颜色的个数"，不看"桶的个数"。** 平均色会被 [snapToPc98] 吸到
     *    12 位网格上，两个不同的桶很容易撞到同一格点。按桶数判停就会剩一堆重复。
     * 2. **选桶要按"桶内还有几种量化色"挑，不能只看原始通道跨度。** 只有被劈的桶
     *    内部本来就含多种量化色，劈开才可能多分出一个色位；按跨度挑会挑到
     *    "跨度很大但量化后只有一种色"的桶，白劈一次。
     *
     * 大块平涂的画面（正是 PC-98 CG 那种）最容易踩这两个坑。
     */
    fun quantize(colors: IntArray, maxColors: Int = PALETTE_SIZE): IntArray {
        if (colors.isEmpty() || maxColors <= 0) {
            return IntArray(0)
        }
        val buckets = mutableListOf(colors.copyOf())
        val palette = mutableListOf(averageOf(buckets[0]))
        // 兜底：万一劈分一直不增加色数，别让循环跑飞
        var guard = maxColors * 4
        while (palette.toSet().size < maxColors && guard > 0) {
            guard--
            var target = -1
            var bestDistinct = 1
            var bestSpan = 0
            for (index in buckets.indices) {
                val bucket = buckets[index]
                if (bucket.size < 2) {
                    continue
                }
                val distinct = snappedColorCount(bucket)
                if (distinct < 2) {
                    continue
                }
                val span = widestChannelRange(bucket).second
                if (distinct > bestDistinct || (distinct == bestDistinct && span > bestSpan)) {
                    bestDistinct = distinct
                    bestSpan = span
                    target = index
                }
            }
            if (target < 0) {
                // 剩下的桶劈开也分不出新颜色了
                break
            }
            val bucket = buckets[target]
            val channel = widestChannelRange(bucket).first
            val sorted = bucket.toList().sortedBy { channelValue(it, channel) }
            val middle = sorted.size / 2
            val low = sorted.subList(0, middle).toIntArray()
            val high = sorted.subList(middle, sorted.size).toIntArray()
            buckets[target] = low
            buckets.add(high)
            palette[target] = averageOf(low)
            palette.add(averageOf(high))
        }
        return palette.distinct().toIntArray()
    }

    /**
     * @return 桶里有几种不同的 12 位量化色，最多数到 [DISTINCT_PROBE_CAP] 就停。
     *
     * 只需要拿这个数给桶排序，数到一定量之后再往上数没有意义——
     * 早期每个桶都含成百上千种色，全量去重纯属浪费。
     */
    private fun snappedColorCount(bucket: IntArray): Int {
        val seen = HashSet<Int>(DISTINCT_PROBE_CAP * 2)
        for (color in bucket) {
            seen.add(snapToPc98(color))
            if (seen.size >= DISTINCT_PROBE_CAP) {
                return DISTINCT_PROBE_CAP
            }
        }
        return seen.size
    }

    /** @return (通道下标, 该通道上的最大差值)，通道 0/1/2 = 红/绿/蓝。 */
    private fun widestChannelRange(bucket: IntArray): Pair<Int, Int> {
        var widestChannel = 0
        var widest = -1
        for (channel in 0..2) {
            var min = 255
            var max = 0
            for (color in bucket) {
                val value = channelValue(color, channel)
                if (value < min) {
                    min = value
                }
                if (value > max) {
                    max = value
                }
            }
            val span = max - min
            if (span > widest) {
                widest = span
                widestChannel = channel
            }
        }
        return widestChannel to widest
    }

    private fun channelValue(color: Int, channel: Int): Int = when (channel) {
        0 -> Color.red(color)
        1 -> Color.green(color)
        else -> Color.blue(color)
    }

    /** 桶的平均色，顺便落到 PC-98 的 12 位精度上。 */
    private fun averageOf(bucket: IntArray): Int {
        var red = 0L
        var green = 0L
        var blue = 0L
        for (color in bucket) {
            red += Color.red(color)
            green += Color.green(color)
            blue += Color.blue(color)
        }
        val size = bucket.size.toLong()
        return snapToPc98(
            Color.rgb(
                (red / size).toInt(),
                (green / size).toInt(),
                (blue / size).toInt()
            )
        )
    }

    /**
     * 把颜色压到 PC-98 的精度：**每个通道 4 bit（16 级）**，合起来就是那片 4096 色的调色板。
     *
     * 16 级 → 0..255 的换算系数是 17（15 × 17 = 255），所以量化后的值一定是 17 的倍数。
     */
    fun snapToPc98(color: Int): Int = Color.rgb(
        snapChannel(Color.red(color)),
        snapChannel(Color.green(color)),
        snapChannel(Color.blue(color))
    )

    private fun snapChannel(value: Int): Int =
        ((value * (CHANNEL_LEVELS - 1) + 127) / 255) * CHANNEL_STEP

    /** @return 调色板里离 [color] 最近的那个。 */
    fun nearestIn(palette: IntArray, color: Int): Int {
        var best = color
        var bestDistance = Long.MAX_VALUE
        for (candidate in palette) {
            val distance = distanceOf(candidate, color)
            if (distance < bestDistance) {
                bestDistance = distance
                best = candidate
            }
        }
        return best
    }

    private fun distanceOf(a: Int, b: Int): Long {
        val dr = (Color.red(a) - Color.red(b)).toLong()
        val dg = (Color.green(a) - Color.green(b)).toLong()
        val db = (Color.blue(a) - Color.blue(b)).toLong()
        return dr * dr * 3 + dg * dg * 6 + db * db
    }

    // endregion

    // region 抖动

    /**
     * 把每个格子映射到调色板上，必要时抖动。
     *
     * @param colors 逐格的原始颜色（长度 = columns × rows）
     * @param palette [quantize] 或用户手选的 16 色
     * @return 映射后的颜色，长度同 [colors]
     */
    fun apply(
        colors: IntArray,
        columns: Int,
        rows: Int,
        palette: IntArray,
        mode: DitherMode
    ): IntArray {
        if (palette.isEmpty()) {
            return colors
        }
        return when (mode) {
            DitherMode.None -> IntArray(colors.size) { nearestIn(palette, colors[it]) }
            DitherMode.Ordered -> orderedDither(colors, columns, rows, palette)
            DitherMode.Diffuse -> diffuseDither(colors, columns, rows, palette)
        }
    }

    /**
     * 有序抖动：按 Bayer 网点给每格加一个固定的偏移再找最近色。
     *
     * 偏移幅度取「调色板内相邻两色的平均距离」——太小球没效果，太大整张图会糊。
     * 用调色板自己的间距来定幅度，换任何一套 16 色都能自适应。
     */
    private fun orderedDither(
        colors: IntArray,
        columns: Int,
        rows: Int,
        palette: IntArray
    ): IntArray {
        val amplitude = neighbourSpacing(palette)
        val out = IntArray(colors.size)
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val index = row * columns + column
                // Bayer 取值 0..63 → 归一化到 -0.5 .. +0.5
                val threshold = (BAYER_8[(row % 8) * 8 + (column % 8)] + 0.5f) / 64.0f - 0.5f
                val shift = threshold * amplitude
                val color = colors[index]
                val adjusted = Color.rgb(
                    clamp(Color.red(color) + shift),
                    clamp(Color.green(color) + shift),
                    clamp(Color.blue(color) + shift)
                )
                out[index] = nearestIn(palette, adjusted)
            }
        }
        return out
    }

    /**
     * 误差扩散（Floyd–Steinberg）：把"这一格本来想显示的颜色"与"实际给的颜色"
     * 之间的差，按权重摊给右边和下一行的邻居，让渐变过渡得更自然。
     *
     * 权重沿用经典的那组：右 7/16、左下 3/16、下 5/16、右下 1/16。
     */
    private fun diffuseDither(
        colors: IntArray,
        columns: Int,
        rows: Int,
        palette: IntArray
    ): IntArray {
        val out = IntArray(colors.size)
        // 用浮点缓冲，误差要能带上小数，不然会被截断抹平
        val buffer = FloatArray(colors.size * 3)
        for (i in colors.indices) {
            buffer[i * 3] = Color.red(colors[i]).toFloat()
            buffer[i * 3 + 1] = Color.green(colors[i]).toFloat()
            buffer[i * 3 + 2] = Color.blue(colors[i]).toFloat()
        }

        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val index = row * columns + column
                val base = index * 3
                val want = Color.rgb(
                    clamp(buffer[base]),
                    clamp(buffer[base + 1]),
                    clamp(buffer[base + 2])
                )
                val got = nearestIn(palette, want)
                out[index] = got
                val errR = Color.red(want) - Color.red(got)
                val errG = Color.green(want) - Color.green(got)
                val errB = Color.blue(want) - Color.blue(got)
                spread(buffer, columns, rows, column + 1, row, errR, errG, errB, 7f / 16f)
                spread(buffer, columns, rows, column - 1, row + 1, errR, errG, errB, 3f / 16f)
                spread(buffer, columns, rows, column, row + 1, errR, errG, errB, 5f / 16f)
                spread(buffer, columns, rows, column + 1, row + 1, errR, errG, errB, 1f / 16f)
            }
        }
        return out
    }

    private fun spread(
        buffer: FloatArray,
        columns: Int,
        rows: Int,
        column: Int,
        row: Int,
        errR: Int,
        errG: Int,
        errB: Int,
        weight: Float
    ) {
        if (column < 0 || column >= columns || row < 0 || row >= rows) {
            return
        }
        val base = (row * columns + column) * 3
        buffer[base] += errR * weight
        buffer[base + 1] += errG * weight
        buffer[base + 2] += errB * weight
    }

    /** @return 调色板里每个色到"最近另一个色"的平均距离（按通道平均）。 */
    private fun neighbourSpacing(palette: IntArray): Float {
        if (palette.size < 2) {
            return 0f
        }
        var total = 0f
        for (i in palette.indices) {
            var best = Float.MAX_VALUE
            for (j in palette.indices) {
                if (i == j) {
                    continue
                }
                val dr = Color.red(palette[i]) - Color.red(palette[j])
                val dg = Color.green(palette[i]) - Color.green(palette[j])
                val db = Color.blue(palette[i]) - Color.blue(palette[j])
                // 欧氏距离换算成"每通道平均"的量级，好和 0..255 的偏移对齐
                val d = sqrt((dr * dr + dg * dg + db * db).toFloat()) / sqrt(3f)
                if (d < best) {
                    best = d
                }
            }
            total += best
        }
        // 取一半：网点偏移在 ±半步长时，正好能跨到相邻那一色
        return total / palette.size * 0.5f
    }

    private fun clamp(value: Float): Int = value.toInt().coerceIn(0, 255)

    // endregion

    /**
     * @return 两个颜色在感知上的接近程度，给调试和验证用。
     */
    fun difference(a: Int, b: Int): Int =
        abs(Color.red(a) - Color.red(b)) +
            abs(Color.green(a) - Color.green(b)) +
            abs(Color.blue(a) - Color.blue(b))

    // region 内置调色板

    /** 把 16 个 `0xRRGGBB` 数字转成调色板（自动吸到 12 位网格上）。 */
    private fun paletteOf(vararg hex: Int): IntArray =
        IntArray(hex.size) { snapToPc98(0xFF000000.toInt() or hex[it]) }

    /**
     * 内置的几套 16 色。都是「每通道 16 级」的取值（0/17/34/.../255），
     * 所以下面这些数看着都不零不整，那是 12 位精度的本来面目。
     */
    val BUILT_IN: List<Pair<String, IntArray>> = listOf(
        // PC-98 数字 RGB 16 色：黑、蓝、红、洋红、绿、青、黄、白 + 各自的亮版
        "PC-98 标准 16 色" to paletteOf(
            0x000000, 0x0000AA, 0xAA0000, 0xAA00AA, 0x00AA00, 0x00AAAA, 0xAAAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0xFF5555, 0xFF55FF, 0x55FF55, 0x55FFFF, 0xFFFF55, 0xFFFFFF
        ),
        // 偏暖的怀旧 CRT 色，适合黄昏/室内场景
        "怀旧暖色 16 色" to paletteOf(
            0x000000, 0x221111, 0x442211, 0x663311, 0x884422, 0xAA5533, 0xCC7744, 0xEE9966,
            0x000022, 0x222244, 0x444466, 0x666688, 0x8888AA, 0xAAAACC, 0xCCCCEE, 0xFFFFFF
        ),
        // 冷色调，适合夜景/水下
        "冷调夜色 16 色" to paletteOf(
            0x000000, 0x001122, 0x002244, 0x003366, 0x004488, 0x1155AA, 0x2277CC, 0x3399EE,
            0x001111, 0x003333, 0x115555, 0x227777, 0x339999, 0x55BBBB, 0x88DDDD, 0xFFFFFF
        ),
        // 16 级灰阶（就是那条灰轴，拿来当对照）
        "16 级灰阶" to paletteOf(
            0x000000, 0x111111, 0x222222, 0x333333, 0x444444, 0x555555, 0x666666, 0x777777,
            0x888888, 0x999999, 0xAAAAAA, 0xBBBBBB, 0xCCCCCC, 0xDDDDDD, 0xEEEEEE, 0xFFFFFF
        )
    )

    // endregion
}
