package com.guixing.callscreen

import androidx.compose.runtime.mutableStateListOf
import com.guixing.util.LogUtils
import com.guixing.util.sharedDir
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "CallGuard"
private const val MAX_ENTRIES = 200
private const val LOG_FILE_NAME = "callguard-observe.log"

/** adb 不方便连接时，用户自己也要能在手机上看到/复制诊断日志——同
 * `teleprompter/TeleprompterObserveLog.kt` 一样的模式（in-app 查看器 + 落一份 sharedDir 文件，
 * 截图或者用 QQ 把 callguard-observe.log 发过来都行，不需要数据线）。故意在这个包里单独写一份，
 * 不改 teleprompter 那份、不提取共享工具类，跟这个新功能"新建独立包，不碰其他模块任何一行代码"
 * 的要求保持一致。 */
object CallGuardObserveLog {
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
