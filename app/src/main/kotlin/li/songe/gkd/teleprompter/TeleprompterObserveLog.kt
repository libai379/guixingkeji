package li.songe.gkd.teleprompter

import androidx.compose.runtime.mutableStateListOf
import li.songe.gkd.util.LogUtils
import li.songe.gkd.util.sharedDir
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "Prompter"
private const val MAX_ENTRIES = 200
private const val LOG_FILE_NAME = "prompter-observe.log"

/**
 * adb wasn't reachable while chasing the "降级为正文之后内容消失" bug, so every diagnostic line
 * from that investigation goes through here instead of a bare LogUtils.d call - it still logs to
 * logcat (for whenever adb IS available again), but also mirrors into an in-app viewer (editor
 * settings -> 查看诊断日志) and appends to a sharedDir file, so a screenshot or a QQ file-share of
 * prompter-observe.log works without a cable. Not itself a fix - purely an observability aid.
 */
object TeleprompterObserveLog {
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
