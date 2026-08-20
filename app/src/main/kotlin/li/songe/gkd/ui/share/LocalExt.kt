package li.songe.gkd.ui.share

import androidx.compose.runtime.staticCompositionLocalOf

// 公开版：原本这里还有一个 LocalMainViewModel，指向 GKD 内核的 MainViewModel。内核已整个删除，
// 这个 CompositionLocal 也一并去掉——保留的三个功能（提词器/电话拦截/定位修改）都不用它。

val LocalDarkTheme = staticCompositionLocalOf { false }

val LocalIsTalkbackEnabled = staticCompositionLocalOf {
    false
}
