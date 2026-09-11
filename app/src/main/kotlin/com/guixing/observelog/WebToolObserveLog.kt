/*
 * 定位修改（WebToolActivity + assets/web 那个页面）的诊断日志 —— 2026-09-05。
 *
 * 结构照抄 `teleprompter/TeleprompterObserveLog.kt` 和 `callscreen/CallGuardObserveLog.kt`：同样是
 * logcat + 应用内查看器 + 落一份 sharedDir 文件三处都写。之所以还是各写一份而不是把三个合并成一个
 * 带 tag 的公共对象：三个模块的日志是**分开看的**（用户排查的是某一个功能，不是全应用），合并之后
 * 每次看都要先按 tag 过滤，反而更麻烦；共用的只有导出（见 ObserveLogExport.kt）。
 *
 * 这个模块跟另外两个不同的一点：它的界面是 WebView 里的 HTML，Kotlin 侧本来就只有一层壳。所以日志
 * 的主要来源是**把 WebView 的 console 消息接进来**（onConsoleMessage）+ 文件保存流程的关键节点，
 * 而不是像提词器那样在业务代码里逐处埋点——页面里的 `console.log` 直接就是这个日志的内容。
 */
package com.guixing.observelog

import androidx.compose.runtime.mutableStateListOf
import com.guixing.util.LogUtils
import com.guixing.util.sharedDir
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "WebTool"
private const val MAX_ENTRIES = 300
private const val LOG_FILE_NAME = "webtool-observe.log"

object WebToolObserveLog {
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    val entries = mutableStateListOf<String>()

    @Synchronized
    fun log(message: String) {
        val line = "${timeFormat.format(Date())} $message"
        LogUtils.d("[OBSERVE] $message", tag = TAG)
        entries.add(line)
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        runCatching {
            sharedDir.resolve(LOG_FILE_NAME).appendText(line + "\n")
        }
    }

    fun clear() {
        entries.clear()
        runCatching { sharedDir.resolve(LOG_FILE_NAME).writeText("") }
    }
}
