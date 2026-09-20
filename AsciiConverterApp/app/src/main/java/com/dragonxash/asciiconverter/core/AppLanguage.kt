package com.dragonxash.asciiconverter.core

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.dragonxash.asciiconverter.R
import java.util.Locale

/**
 * App 的界面语言。
 *
 * 资源本来是中英双份的，默认跟随系统——手机是英文系统，界面就全是英文。
 * 这里做成"可以手动指定"，并且**默认简体中文**，不管系统语言是什么。
 *
 * 实现走**两条路**，缺一条都会出现"选了中文还是英文"：
 *
 * 1. [apply] → `AppCompatDelegate.setApplicationLocales`，Android 13+ 交给系统
 *    （需要在清单里声明 `android:localeConfig`，好处是应用会出现在系统设置的语言列表里）。
 * 2. [wrap] → 在 Activity 的 `attachBaseContext` 里自己包一层带 locale 的 Context。
 *
 * **第 2 条是保底**：第 1 条在部分系统版本 / ROM 上会静默失效——不报错、不生效，
 * 界面语言就一直是系统语言。只改 Configuration 这条跟系统版本无关，一定能生效。
 */
enum class AppLanguage(
    /** 语言标签，空串表示跟随系统。 */
    val tag: String,
    @param:StringRes val labelRes: Int
) {

    System("", R.string.lang_system),
    Chinese("zh", R.string.lang_chinese),
    English("en", R.string.lang_english);

    companion object {

        /** 没设置过时的语言：固定简体中文。 */
        val default: AppLanguage = Chinese

        private const val KEY = "app_language"

        /** @return 当前生效的语言设置。 */
        fun load(context: Context): AppLanguage {
            val stored = context.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
                .getString(KEY, null)
            return entries.firstOrNull { it.name == stored } ?: default
        }

        /**
         * 应用语言。
         *
         * 必须在 Activity 创建前调用（[com.dragonxash.asciiconverter.App.onCreate] 里），
         * 否则当前界面不会立刻切过去。
         *
         * 这一路（系统 per-app language）在部分 ROM 上会**静默失效**，也可能直接抛异常，
         * 所以包一层 runCatching——它挂掉不能把 App 掀了，界面语言由 [wrap] 兜底。
         */
        fun apply(language: AppLanguage) {
            val locales = if (language == System) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(language.tag)
            }
            runCatching { AppCompatDelegate.setApplicationLocales(locales) }
        }

        /** 从持久化设置里读出来并应用，App 启动时调一次。 */
        fun applyStored(context: Context) {
            apply(load(context))
        }

        /**
         * 给 [base] 套上当前选定的语言，交给 Activity 的 `attachBaseContext` 用。
         *
         * 这是不依赖系统 per-app language 的保底实现：直接改 Configuration 再
         * `createConfigurationContext`。选「跟随系统」时原样返回。
         */
        fun wrap(base: Context): Context {
            val language = load(base)
            if (language == System) {
                return base
            }
            val locale = Locale.forLanguageTag(language.tag)
            Locale.setDefault(locale)
            val configuration = Configuration(base.resources.configuration)
            configuration.setLocale(locale)
            configuration.setLayoutDirection(locale)
            return base.createConfigurationContext(configuration)
        }

        /**
         * @return [activity] 当前**实际生效**的界面语言是不是 [language]。
         *
         * 用来判断切换语言后有没有真的生效——只看存储值是不够的，
         * 存储值是对的、界面还是旧语言，正是这次要修的 bug。
         */
        fun isApplied(activity: Activity, language: AppLanguage = load(activity)): Boolean {
            if (language == System) {
                return true
            }
            val current = activity.resources.configuration.locales[0]
            return current.language.equals(language.tag, ignoreCase = true)
        }

        /** 保存并立即应用。界面会重建。 */
        fun save(context: Context, language: AppLanguage) {
            context.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, language.name)
                .apply()
            apply(language)
        }

        /** 可以选择的语言列表。 */
        val selectable: List<AppLanguage> = listOf(Chinese, English, System)
    }
}
