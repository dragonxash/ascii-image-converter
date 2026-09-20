package com.dragonxash.asciiconverter

import android.app.Application
import com.dragonxash.asciiconverter.core.AppLanguage
import com.dragonxash.asciiconverter.core.Diagnostics
import com.dragonxash.asciiconverter.core.SourceCache

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // 必须在任何 Activity 创建之前定好语言，否则界面会先按系统语言渲染一遍
        AppLanguage.applyStored(this)
        installCrashLogger()
        // 上次被系统杀掉/崩掉时留下的源图中转文件，顺手清掉（按年龄筛，不动刚存的那份）
        runCatching { SourceCache.sweepStale(this) }
    }

    /**
     * 把没被捕获的崩溃也落一份完整堆栈到 cache 目录。
     *
     * 界面直接挂掉的时候用户看不到任何提示，只能靠这个文件事后定位。
     * 处理完仍然交回给系统默认处理器（该崩还是崩，只是多留一份证据）。
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                Diagnostics.record(applicationContext, throwable, "未捕获崩溃 @ ${thread.name}")
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
