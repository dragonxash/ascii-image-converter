package com.dragonxash.asciiconverter.core

import android.content.Context

/**
 * 全局设置的存取入口。
 *
 * 键名集中放这里，免得各处散着写字面量、改一个漏一个。
 */
object AppPrefs {

    /** 设置文件的唯一名字，[AppLanguage] 也用这个。 */
    const val FILE = "asciiconverter_settings"

    private const val KEY_TEXT_FORMAT = "text_format"

    /** @return 上次选的文本输出格式，默认纯字符。 */
    fun textFormat(context: Context): AnsiTextFormat {
        val stored = prefs(context).getString(KEY_TEXT_FORMAT, null)
        return AnsiTextFormat.entries.firstOrNull { it.name == stored } ?: AnsiTextFormat.Plain
    }

    fun setTextFormat(context: Context, format: AnsiTextFormat) {
        prefs(context).edit().putString(KEY_TEXT_FORMAT, format.name).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
