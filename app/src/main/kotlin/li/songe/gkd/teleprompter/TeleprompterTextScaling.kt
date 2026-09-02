/*
 * 2026-08-28 - 本文件原来的主体是 `AnnotatedString.scaledForDisplay(...)`：一个"只用于显示"的
 * 文本变换，把整体缩放乘到 Word 导入自带的逐 run 显式字号上、把行距倍数乘到段落自带的显式行距
 * 上、并强制对齐。**已整体删除。**
 *
 * 删除原因（不是嫌它复杂，是它和"三页断行必须一致"这个目标不可兼得）：
 * 编辑态必须渲染可编辑的那份 `RichTextState`——所见即所存，否则保存的就不是用户看到的东西。而
 * `RichTextState` 的公开写入口只有 `setHtml` / `setText` / `setMarkdown`，唯一的 AnnotatedString
 * 写入口是 internal 的，**没有任何受支持的办法把变换后的 AnnotatedString 塞回去**（用 javap 核对
 * 过 richeditor-compose 1.0.0 的字节码）。于是只要保留这个变换，阅读/播放两页喂给排版引擎的输入
 * 就必然和编辑页不是同一个对象，断行不可能对齐。
 *
 * 代价（已知并已接受，见 docs/DECISIONS.md 2026-08-28）：整体缩放/行距/对齐现在只作用于基础
 * TextStyle，也就是**只对没有自带样式的文字生效**；Word 导入自带字号/行距/对齐的部分不再受这三项
 * 影响。设置面板四条文案已同步改成如实描述。后续计划是在**导入时**做字号归一化（把 Word 的绝对
 * 磅值折算成相对基础字号的倍数），做完之后这三项就能重新覆盖全部文字，文案再改回去。
 *
 * 否决过的两个替代方案也记在 DECISIONS 里：保存时做逆变换（有损往返，本项目已因 toHtml() 归一化
 * 吃过亏）、用 LocalDensity.fontScale 整体缩放（更干净但未验证，不在灭 bug 的同一轮引入）。
 */
package li.songe.gkd.teleprompter

import androidx.compose.ui.text.style.TextAlign

fun TeleprompterTextAlignMode.toComposeAlignOrNull(): TextAlign? = when (this) {
    TeleprompterTextAlignMode.FollowDocument -> null
    TeleprompterTextAlignMode.Left -> TextAlign.Left
    TeleprompterTextAlignMode.Center -> TextAlign.Center
    TeleprompterTextAlignMode.Right -> TextAlign.Right
    TeleprompterTextAlignMode.Justify -> TextAlign.Justify
}

/** Single source of truth for "which of the two stored content-width values applies right now" -
 * shared by the editor (both view and edit mode) and the player so switching orientation can
 * never leave one of the three screens on the wrong value. */
fun TeleprompterSettings.contentWidthFraction(isLandscape: Boolean): Float =
    if (isLandscape) contentWidthLandscape else contentWidthPortrait
