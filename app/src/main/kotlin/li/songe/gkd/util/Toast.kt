package li.songe.gkd.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import li.songe.gkd.app
import li.songe.loc.Loc

/**
 * 公开版：原来这里是一整套自绘的悬浮 Toast——它依赖无障碍服务和 OverlayWindowService 才能画在
 * 别的应用上面（去广告触发时要在别家应用界面上提示），还有 showActionToast(ResolvedRule) 这种
 * 直接绑定规则引擎的入口。GKD 内核已整个删除，那条悬浮通道本身就不存在了，因此退化成系统原生
 * Toast：公开版的三个功能都只在自己的界面里弹提示，系统 Toast 完全够用。
 *
 * 对外签名保持不变（text/forced/delayMillis/loc 四个参数），调用方无需改动。
 */

fun toast(
    text: CharSequence,
    @Suppress("UNUSED_PARAMETER") forced: Boolean = false,
    delayMillis: Long = 0L,
    @Loc loc: String = "",
) {
    if (delayMillis > 0) {
        runMainPost(delayMillis) { toast(text = text, loc = loc) }
        return
    }
    runMainPost { Toast.makeText(app, text, Toast.LENGTH_SHORT).show() }
    if (loc.isNotEmpty()) {
        LogUtils.d(text, loc = loc)
    }
}

fun copyText(text: String) {
    val manager = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText(null, text))
    toast("已复制")
}

/** 系统 Toast 不需要初始化，保留空实现是为了不改 App.onCreate 的调用顺序。 */
fun initToast() = Unit
