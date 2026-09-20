package com.dragonxash.asciiconverter

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.dragonxash.asciiconverter.core.AppLanguage

/**
 * 所有 Activity 的基类，只做一件事：保证界面语言是用户选的那个。
 *
 * 语言有两条生效路径，这里管的是**保底那条**（见 [AppLanguage] 的说明）：
 * 在 [attachBaseContext] 里把 Context 换成带 locale 的，跟系统版本无关。
 *
 * 只走 `AppCompatDelegate.setApplicationLocales` 是不够的——它在部分系统版本上
 * 会**静默失败**：不抛异常、不报错，界面语言就一直是系统语言。
 */
open class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    /**
     * 切完语言调一次：等系统自己重建；没重建就补一刀。
     *
     * 不直接 `recreate()` 是因为走系统 per-app language 那条路时系统**也会**重建，
     * 两边都建会闪两次。这里延后一点再看实际生效的语言，没生效才重建。
     */
    protected fun ensureLanguageApplied() {
        window.decorView.postDelayed(
            {
                if (!isFinishing && !isChangingConfigurations && !AppLanguage.isApplied(this)) {
                    recreate()
                }
            },
            LANGUAGE_RECHECK_DELAY_MS
        )
    }

    private companion object {
        /** 留够时间让系统那条路先重建，避免重复重建。 */
        const val LANGUAGE_RECHECK_DELAY_MS = 250L
    }
}
