package com.dragonxash.asciiconverter.core

import android.graphics.Color
import androidx.annotation.StringRes
import com.dragonxash.asciiconverter.R

/**
 * 内置的几种风格，点一下就把一整套参数换掉。
 *
 * [Custom] 不是真的预设，只是"用户手动改过参数"的标记，用来让预设标签不高亮。
 */
enum class Preset(@param:StringRes val titleRes: Int) {

    /** 黑底绿字，最经典的终端味。 */
    Terminal(R.string.preset_terminal),

    /** 米白底黑字，用字符反转把亮暗对调，看起来像报纸插图。 */
    Newspaper(R.string.preset_newspaper),

    /** 用原图颜色画字符，彩色。 */
    Colorful(R.string.preset_colorful),

    /** 低密度纯字符，适合当头像。 */
    Minimal(R.string.preset_minimal),

    /** 深蓝底青字。 */
    DeepSea(R.string.preset_deepsea),

    /**
     * PC-98 画风：色块 + 16 色调色板 + 网点抖动。
     *
     * 把 PC-98 那两条硬约束（同屏 16 色、每通道 4 bit）直接照搬，
     * 中间色用抖动糊出来——就是当年 640×400 那张脸的观感。
     */
    Pc98(R.string.preset_pc98),

    /** 手动调过参数。 */
    Custom(R.string.preset_custom);

    /**
     * @return 把本预设作用在 [base] 上（只改视觉参数，不动文本行高比例这类无关项）。
     *
     * 注意 [AsciiArtOptions.customPalette] 不会被预设改动——那是用户自己调出来的东西，
     * 点个预设就丢掉太粗暴了。
     */
    fun applyTo(base: AsciiArtOptions): AsciiArtOptions = when (this) {
        Terminal -> base.copy(
            charLineWidth = 120,
            reverseChar = false,
            reverseColor = false,
            colorFillRate = 0.0f,
            foregroundColor = Color.parseColor("#4CE38B"),
            backgroundColor = Color.parseColor("#0B1017"),
            keepImageColor = false,
            useMonospaceFont = true,
            blocksOnly = false,
            pc98Palette = false,
            dither = DitherMode.Ordered
        )

        Newspaper -> base.copy(
            charLineWidth = 170,
            reverseChar = true,
            reverseColor = false,
            colorFillRate = 0.0f,
            foregroundColor = Color.parseColor("#1B1B1B"),
            backgroundColor = Color.parseColor("#F2EDE3"),
            keepImageColor = false,
            useMonospaceFont = false,
            blocksOnly = false,
            pc98Palette = false,
            dither = DitherMode.Ordered
        )

        Colorful -> base.copy(
            charLineWidth = 140,
            reverseChar = false,
            reverseColor = false,
            colorFillRate = 0.65f,
            foregroundColor = Color.WHITE,
            backgroundColor = Color.BLACK,
            keepImageColor = true,
            useMonospaceFont = true,
            blocksOnly = false,
            pc98Palette = false,
            dither = DitherMode.Ordered
        )

        Minimal -> base.copy(
            charLineWidth = 64,
            reverseChar = false,
            reverseColor = false,
            colorFillRate = 0.0f,
            foregroundColor = Color.parseColor("#E6EDF3"),
            backgroundColor = Color.BLACK,
            keepImageColor = false,
            useMonospaceFont = true,
            blocksOnly = false,
            pc98Palette = false,
            dither = DitherMode.Ordered
        )

        DeepSea -> base.copy(
            charLineWidth = 150,
            reverseChar = false,
            reverseColor = false,
            colorFillRate = 0.0f,
            foregroundColor = Color.parseColor("#35C9E8"),
            backgroundColor = Color.parseColor("#06131F"),
            keepImageColor = false,
            useMonospaceFont = true,
            blocksOnly = false,
            pc98Palette = false,
            dither = DitherMode.Ordered
        )

        Pc98 -> base.copy(
            // 320 列是照着 PC-98 原生的 640×400 折半来的
            charLineWidth = 320,
            reverseChar = false,
            reverseColor = false,
            // 色块要能看见颜色，填充必须拉满
            colorFillRate = 1.0f,
            foregroundColor = Color.WHITE,
            backgroundColor = Color.BLACK,
            keepImageColor = true,
            useMonospaceFont = true,
            blocksOnly = true,
            pc98Palette = true,
            dither = DitherMode.Ordered
        )

        Custom -> base
    }

    /** @return [options] 是否正好等于本预设。 */
    fun matches(options: AsciiArtOptions): Boolean {
        if (this == Custom) {
            return false
        }
        val preset = applyTo(AsciiArtOptions())
        return options.charLineWidth == preset.charLineWidth &&
            options.reverseChar == preset.reverseChar &&
            options.reverseColor == preset.reverseColor &&
            options.colorFillRate == preset.colorFillRate &&
            options.foregroundColor == preset.foregroundColor &&
            options.backgroundColor == preset.backgroundColor &&
            options.keepImageColor == preset.keepImageColor &&
            options.useMonospaceFont == preset.useMonospaceFont &&
            options.pc98Palette == preset.pc98Palette &&
            options.dither == preset.dither
        // 故意不比 blocksOnly：那是个正交的显示模式，
        // 手动开关它不该让预设标签跳掉（1.3.0 定的规矩）
    }

    companion object {

        /** 可以在界面上点选的真实预设（不含 [Custom]）。 */
        val selectablePresets: List<Preset> =
            entries.filter { it != Custom }

        /** 默认预设。 */
        val default: Preset = Terminal

        /** @return [options] 当前对应哪个预设，都对不上就是 [Custom]。 */
        fun detect(options: AsciiArtOptions): Preset =
            selectablePresets.firstOrNull { it.matches(options) } ?: Custom
    }
}
