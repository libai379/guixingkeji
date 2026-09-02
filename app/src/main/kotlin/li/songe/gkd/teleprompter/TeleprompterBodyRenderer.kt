/*
 * 提词器正文的**唯一**渲染实现 —— 编辑页 / 阅读态 / 播放页三处共用这一个 Composable。
 *
 * ── 为什么必须只有一份 ──────────────────────────────────────────────────────
 *
 * 2026-08-28 之前是三份实现：编辑态焦点章节走 `BasicRichTextEditor(state)`，阅读态和播放页各自
 * 走 `Text(annotatedString.scaledForDisplay(...))`。三份喂给排版引擎的**输入对象都不一样**（前者
 * 是原始 annotatedString，后两者是变换过的副本），字号/行距/对齐还各带各的乘数，于是同一篇文档在
 * 三个页面的断行位置对不上——用户在编辑页看到的行尾，到播放页就换了个地方。
 *
 * 现在统一成：三处都把**同一个 RichTextState 的原始 annotatedString** 交给**同一个
 * BasicRichTextEditor**，用同一份 TextStyle、同一个宽度约束。断行一致不是靠事后对齐参数凑出来
 * 的，而是**结构上不可能不一致**——输入相同、控件相同、宽度相同。
 *
 * ── mode 允许影响什么 ───────────────────────────────────────────────────────
 *
 * [TeleprompterRenderMode] **只允许**影响：能不能编辑、光标可不可见、要不要接受交互。
 * **不允许**影响任何排版参数（字号、行距、字距、对齐、内边距、宽度）——那些一律由调用方传进来的
 * 同一份 [style] 和这里自己算的宽度决定。这条是本文件存在的意义，改的时候别破坏它。
 *
 * ── 内边距和宽度归这里所有 ──────────────────────────────────────────────────
 *
 * 三个页面都**不再**自己给正文加 padding 或宽度约束（以前播放页有 `contentPadding =
 * PaddingValues(horizontal = 24.dp)`，编辑页有自己的一套，两边数值不同 → 可用宽度不同 → 断行
 * 不同）。现在横向内边距和内容宽度比例都在这里算，页面只管把顶栏/工具栏做成**浮层覆盖**在正文
 * 上面，不许挤压正文的可用宽度。
 */
package li.songe.gkd.teleprompter

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import android.content.res.Configuration
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor

/** 正文左右各留的内边距。三页共用这一个数，改它就是三页一起改——这正是要的。 */
val TELEPROMPTER_BODY_HORIZONTAL_PADDING = 8.dp

enum class TeleprompterRenderMode {
    /** 编辑页：可输入、光标可见。 */
    Edit,

    /** 编辑页的查看态：只读，光标不可见，不接受点击/长按。 */
    Read,

    /** 播放页：同 [Read]，另外由调用方负责自动滚动和当前行高亮（那些不在本文件里）。 */
    Play,
}

private val TeleprompterRenderMode.isEditable: Boolean
    get() = this == TeleprompterRenderMode.Edit

/**
 * 三页共用的内容宽度比例。竖屏取 [TeleprompterSettings.contentWidthPortrait]、横屏取
 * [TeleprompterSettings.contentWidthLandscape]，读的是**实时朝向**（`LocalConfiguration`），不是
 * 记下来的快照——转屏必须立刻生效，否则转完屏三页里会有一页停在旧值上，断行又不一致了。
 */
@Composable
fun rememberBodyWidthFraction(settings: TeleprompterSettings): Float {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    return settings.contentWidthFraction(isLandscape)
}

/**
 * 正文渲染。三页唯一入口。
 *
 * [onTextLayout] 直接透传给 `BasicRichTextEditor`，拿到的是真实的 [TextLayoutResult]——验收
 * "三个模式断行完全一致" 就是从这里取 `getLineStart`/`getLineEnd` 做 diff 的，不是另外估算的。
 */
@Composable
fun TeleprompterBody(
    state: RichTextState,
    style: TextStyle,
    mode: TeleprompterRenderMode,
    settings: TeleprompterSettings,
    modifier: Modifier = Modifier,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    val widthFraction = rememberBodyWidthFraction(settings)
    // 2026-08-28 - 「整体缩放」改用 LocalDensity.fontScale 实现。
    //
    // 为什么不能像以前那样乘进 TextStyle.fontSize：正文里 72% 的 run 自带显式字号（Word 导入层叠
    // 出来的），基础 TextStyle 的 fontSize 根本够不着它们，滑块拖了正文纹丝不动——用户实测反馈
    // "整体缩放的效果是副标题大小"，就是因为只有没自带字号的副标题在跟着变。
    //
    // fontScale 作用在 sp -> px 的换算上，**所有** sp 尺寸（含显式 span 的字号）一起缩放，不需要
    // 改动文本对象，因此也不破坏"三页断行一致"（三页都走这个函数，缩放系数相同）。
    val base = LocalDensity.current
    val scaled = Density(density = base.density, fontScale = base.fontScale * settings.overallScale)
    CompositionLocalProvider(LocalDensity provides scaled) {
    Box(modifier.fillMaxWidth()) {
        BasicRichTextEditor(
            state = state,
            modifier = Modifier
                .fillMaxWidth(widthFraction)
                .align(Alignment.TopCenter)
                .padding(horizontal = TELEPROMPTER_BODY_HORIZONTAL_PADDING)
                // 只读的两个模式禁掉**取焦点**，不碰指针事件。
                //
                // 2026-08-28 - 这里第一版写成了 `pointerInput { ... event.changes.forEach { it.consume() } }`
                // 想"禁用交互"，结果把父级滚动容器要用的拖动事件也一起消费了，**阅读页和播放页直接
                // 滑不动**（真机实测）。消费指针事件是"连滚动一起禁掉"，不是"禁掉编辑"。
                //
                // 正确做法：readOnly 已经挡住改内容，再用 focusProperties 挡住取焦点，就不会弹输入法、
                // 不会出现光标。滚动、双指缩放这些父级手势完全不受影响。
                .then(
                    if (mode.isEditable) {
                        Modifier
                    } else {
                        Modifier.focusProperties { canFocus = false }
                    },
                ),
            readOnly = !mode.isEditable,
            textStyle = style,
            // 只读时光标画成透明而不是"不画"：库内部仍然维护光标位置，透明只是不让它出现在屏幕
            // 上，行为比试图关掉光标更可预测。
            //
            // 2026-08-28 - 编辑模式这里原来直接用 `style.color`，但编辑页传进来的 bodyStyle 只设了
            // fontSize/lineHeight/fontFamily/textAlign，**没设 color**，于是 style.color 是
            // Color.Unspecified，画出来不可见——编辑时光标整个消失（用户实测）。用 takeOrElse 兜到
            // 主题色。播放页那份 textStyle 自带 color，所以之前只有编辑页中招。
            cursorBrush = if (mode.isEditable) {
                SolidColor(style.color.takeOrElse { MaterialTheme.colorScheme.primary })
            } else {
                SolidColor(Color.Transparent)
            },
            onTextLayout = onTextLayout,
        )
    }
    }
}

