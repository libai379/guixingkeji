/*
 * 提词器编辑页 —— 全项目最大的一个文件，也是 bug 最集中的地方，改之前先读这段。
 *
 * 它同时装着三样东西：①编辑/查看页本体（章节列表、正文富文本、副标题）；②设置面板
 * `TeleprompterSettingsWindow`（正文/副标题/播放页/当前面板液态效果/更多液态效果 五个分区）；
 * ③大纲触发器 `OutlineScrubTrigger`（编辑页和播放页共用同一份实现，播放页从这里引用）。
 *
 * 几个**反直觉但有意为之**的设计，改动前务必知道：
 * - 章节用普通 `Column` + `verticalScroll` 渲染，**故意不用 `LazyColumn`**：Compose 的文字选中
 *   手柄拖动跟 LazyColumn 的滚动没有联动（见 JetBrains/compose-multiplatform#3333），手柄拖出
 *   可视区不会自动滚动，章节被回收后手柄直接消失。代价是全部章节一次性组合，这是接受的取舍。
 * - 因为上一条，任何影响正文排版的设置（字号/缩放/行距/宽度）一变就是**全文重排**。设置面板里
 *   那几个滑块因此接了 `onBodyDragStart`/`onBodyDragEnd` 做视口裁剪：拖动期间只有屏幕内章节实时
 *   重排，屏幕外的冻结到松手再补。
 * - 只有当前聚焦的那一个章节用可编辑的 `BasicRichTextEditor`，其余渲染成只读 `Text` + 一层
 *   `scaledForDisplay()` 显示级缩放（见 TeleprompterTextScaling.kt，那份结果**绝不能回灌**给
 *   可编辑状态，会把放大后的字号当成真实字号存下去）。
 * - 自动保存的防抖 `LaunchedEffect` key 在 `state.annotatedString.text`（纯文本）上，**不是**整个
 *   AnnotatedString——后者在纯移动光标时也会变，曾导致每拖一下选区手柄就触发一次全量序列化+持久化。
 * - 设置面板是同一棵组合树里的覆盖层，不是 Dialog（Dialog 采样不到 backdrop，玻璃会变空白板）。
 *
 * 相关坑的完整记录见 docs/PITFALLS.md。
 */
package li.songe.gkd.teleprompter

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mohamedrejeb.richeditor.model.RichTextState
import com.kashif_e.backdrop.Backdrop
import com.kashif_e.backdrop.backdrops.layerBackdrop
import com.kashif_e.backdrop.backdrops.rememberLayerBackdrop
import com.kashif_e.backdrop.drawBackdrop
import com.kashif_e.backdrop.effects.progressiveBlur
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import li.songe.gkd.glassui.LiquidCompactButton
import li.songe.gkd.glassui.LiquidCompactIconButton
import li.songe.gkd.glassui.LiquidDialog
import li.songe.gkd.glassui.LiquidSlider
import li.songe.gkd.glassui.LiquidToggle
import li.songe.gkd.glasstuning.GlassTarget
import li.songe.gkd.glasstuning.GlassTintParams
import li.songe.gkd.glasstuning.GlassTuningActivity
import li.songe.gkd.glasstuning.TeleprompterEditorGlassTargets
import li.songe.gkd.glasstuning.TeleprompterPlayerGlassTargets
import li.songe.gkd.glasstuning.liveGlassParamsFor
import li.songe.gkd.ui.share.LocalDarkTheme
import li.songe.gkd.glasstuning.tunedGlass
import li.songe.gkd.util.LogUtils

private const val TAG = "Prompter"

// High-saturation contrast colors, 14 total (custom picker covers everything else).
private val TextColorPresets = listOf(
    Color.Black, Color(0xFF424242), Color.White,
    Color(0xFFE53935), Color(0xFFFB8C00), Color(0xFFFDD835),
    Color(0xFF43A047), Color(0xFF00ACC1), Color(0xFF1E88E5),
    Color(0xFF3949AB), Color(0xFF8E24AA), Color(0xFFD81B60),
    Color(0xFF6D4C41), Color(0xFF546E7A),
)

// Highlighter tones (light, so text drawn on top stays legible); "清除高亮" is a separate tile.
private val HighlightPresets = listOf(
    Color(0xFFFFF59D), Color(0xFFB9F6CA), Color(0xFF80D8FF),
    Color(0xFFFF80AB), Color(0xFFFFD180), Color(0xFFEA80FC),
    Color(0xFFCCFF90), Color(0xFFFF8A80),
)

// Player background: dark-first, since most teleprompter use is dark-room/rig reading, plus a
// couple of light options.
private val PlayerBackgroundPresets = listOf(
    Color(0xFF000000), Color(0xFF1A1A1A), Color(0xFF0D1B2A),
    Color(0xFF001F3F), Color(0xFF0B3D2E), Color(0xFF3D0C0C),
    Color(0xFF2E1A47), Color(0xFF2B1B0E), Color(0xFF37474F),
    Color(0xFFFFFFFF), Color(0xFFF5E6CA), Color(0xFFE0E0E0),
)

// Player text: high-legibility tones meant to sit on top of PlayerBackgroundPresets.
private val PlayerTextPresets = listOf(
    Color(0xFFFFFFFF), Color(0xFFFFF8E7), Color(0xFFFFF59D),
    Color(0xFFC8F7C5), Color(0xFFB3E5FC), Color(0xFFFFD54F),
    Color(0xFFB2EBF2), Color(0xFFD9D9D9), Color(0xFF000000),
)

private val LineHeightPresets = listOf(1.0f, 1.2f, 1.5f, 2.0f, 2.5f)

// TeleprompterSettingsWindow's 5 section headers (正文/副标题/播放页/当前面板液态效果/更多液态
// 效果, 2026-08-16) - "不够显眼，加粗，改成蓝色". Same blue already used elsewhere in this file's
// own color presets (ColorPresets/PlayerBackgroundPresets etc. all start with this exact 0xFF0088FF),
// not a new arbitrary accent.
private val SectionTitleColor = Color(0xFF0088FF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeleprompterEditorScreen(
    vm: TeleprompterVm,
    docId: String,
    onOpenLibrary: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenObserveLog: () -> Unit,
) {
    val library by vm.library.collectAsState()
    val doc = library.documents.find { it.id == docId }
    if (doc == null) {
        LaunchedEffect(Unit) { onOpenLibrary() }
        return
    }
    val focusedChapterId by vm.focusedChapterId.collectAsState()
    // Default view mode: nothing in the document responds to taps, so a mistouch during a live
    // broadcast can't edit the script. Entering edit mode is an explicit, deliberate action.
    var isEditMode by remember { mutableStateOf(false) }
    var outlineExpanded by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var titleEditing by remember { mutableStateOf(false) }
    var titleText by remember(doc.id) { mutableStateOf(doc.title) }
    val activeState = remember { mutableStateOf<RichTextState?>(null) }
    // Subtitles are a plain BasicTextField, never a RichTextState - the format toolbar's
    // toggleSpanStyle calls have nothing to act on there. Tracked separately from focusedChapterId
    // (which only says WHICH chapter, not WHICH field within it) so the toolbar knows to show its
    // disabled/hint state instead of silently doing nothing when tapped.
    var subtitleFocused by remember { mutableStateOf(false) }
    // "横屏文字铺满整行，眼睛横扫距离过大" - landscape defaults to a narrower centered column;
    // portrait keeps the full width it always had. Recomputed on every recomposition (not
    // remembered) since it must react immediately to a live rotation, not just to reopening the
    // screen - configuration.orientation is exactly the LocalConfiguration state that changes
    // when the device actually rotates.
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val contentWidthFraction = library.settings.contentWidthFraction(isLandscape)
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    // Outline "jump" needs a target scroll offset, but positionInParent() is relative to the
    // already-scrolled viewport (unstable while scrolling). Chapter heights aren't, so track
    // those instead and sum the preceding ones to get the jump target - reliable because
    // switching this list to Column+verticalScroll (see below) means every chapter is composed
    // and measured up front, unlike LazyColumn where off-screen items wouldn't have a height yet.
    // A Compose-observable map (not plain mutableMapOf): the scroll-anchor LaunchedEffect below
    // polls it across frames to detect when post-transition relayout has settled.
    val chapterHeights = remember(doc.id) { mutableStateMapOf<String, Int>() }

    // 视口裁剪跟手修复共享状态 (2026-08-16) - 声明在这个共同的外层作用域，因为下面既要被章节
    // 循环读（判断哪些章节该冻结），又要被 TeleprompterSettingsWindow 的 onBodyDragStart/
    // onBodyDragEnd 回调写（它俩是这个 Column 的兄弟节点，不是子节点，写不到声明在 Column 内部
    // 的状态）。详见 docs/PITFALLS.md「提词器正文滑块拖动卡顿」的更新记录。
    var bodyDisplayDragActive by remember { mutableStateOf(false) }

    // Scroll-position anchoring across edit-mode toggles and structural ops (merge/split/
    // demote): storing a raw pixel offset doesn't work because edit mode and view mode render
    // chapters at different heights (different zoom, different editor chrome), so the same
    // pixel value points at a different place after the height layout changes. Anchor to a
    // chapter id + fractional offset within it instead, and re-resolve to a pixel offset once
    // the new layout's heights have settled (see the LaunchedEffect below).
    var pendingAnchor by remember(doc.id) { mutableStateOf<Pair<String, Float>?>(null) }
    fun captureAnchor(overrideChapterId: String? = null): Pair<String, Float>? {
        if (overrideChapterId != null && doc.chapters.any { it.id == overrideChapterId }) {
            return overrideChapterId to 0f
        }
        val scrollValue = scrollState.value
        var cumulative = 0
        for (ch in doc.chapters) {
            val h = chapterHeights[ch.id] ?: 0
            if (scrollValue < cumulative + h) {
                val relative = if (h > 0) (scrollValue - cumulative).toFloat() / h else 0f
                return ch.id to relative.coerceIn(0f, 1f)
            }
            cumulative += h
        }
        return doc.chapters.lastOrNull()?.id?.let { it to 0f }
    }
    LaunchedEffect(pendingAnchor) {
        val anchor = pendingAnchor ?: return@LaunchedEffect
        // Every chapter's measured height can change size after a mode switch or structural
        // edit, all at once - wait for the target chapter's own height to stop changing across
        // consecutive frames instead of guessing a fixed frame count, since relayout can take a
        // variable number of frames depending on how much content is on screen.
        var lastHeight = -1
        var stableFrames = 0
        while (stableFrames < 3) {
            withFrameNanos {}
            val h = chapterHeights[anchor.first] ?: -1
            if (h == lastHeight && h > 0) {
                stableFrames++
            } else {
                stableFrames = 0
                lastHeight = h
            }
        }
        val idx = doc.chapters.indexOfFirst { it.id == anchor.first }
        if (idx >= 0) {
            val top = doc.chapters.take(idx).sumOf { chapterHeights[it.id] ?: 0 }
            val height = chapterHeights[anchor.first] ?: 0
            val target = (top + anchor.second * height).roundToInt().coerceIn(0, scrollState.maxValue)
            scrollState.scrollTo(target)
        }
        pendingAnchor = null
    }
    val mergeIntoPrevious: (String) -> Unit = { chapterId ->
        pendingAnchor = captureAnchor()
        vm.mergeChapterIntoPreviousChapter(doc.id, chapterId)
    }
    val promoteParagraph: (String, Int) -> Unit = { chapterId, paragraphIndex ->
        pendingAnchor = captureAnchor()
        vm.promoteParagraphToChapter(doc.id, chapterId, paragraphIndex)
    }

    // Centralized so it doesn't depend on onFocusChanged(false) firing when a focused
    // BasicRichTextEditor is torn down by recomposition (switching straight to another
    // chapter's body) - Compose doesn't guarantee that callback runs on disposal, so the old
    // chapter's un-debounced keystrokes would otherwise silently vanish.
    val switchToChapter: (String?) -> Unit = switch@{ newChapterId ->
        val prevChapterId = focusedChapterId
        val prevState = activeState.value
        if (prevChapterId != null && prevChapterId != newChapterId && prevState != null) {
            vm.onChapterContentChanged(
                doc.id,
                prevChapterId,
                prevState.toHtml(),
                prevState.annotatedString.text,
            )
        }
        vm.setFocusedChapter(newChapterId)
    }

    // Capture source for every glass surface on this screen (outline drawer, top toolbar, format
    // toolbar, settings window) - a SIBLING of all of them within this same root Box, never their
    // ancestor (circular-capture stack overflow, same class of crash already fixed once this
    // session in TeleprompterSettingsSheet). Scoped to just the scrollable document Column below
    // (not the whole screen) so the toolbar itself - a sibling of that Column, not a descendant -
    // can safely sample it too: it refracts the real chapter text scrolling underneath it, which
    // is also the whole point (a flat single-color background has nothing to refract, see
    // DECISIONS.md/PITFALLS.md on the launcher's original blank-glass bug for the same lesson).
    val editorBackdrop = rememberLayerBackdrop()

    // 刘海/挖孔避让 - landscape is where a notch/punch-hole is most likely to sit on a side edge
    // instead of just the top, and this screen now supports landscape properly (content-width).
    Box(Modifier.fillMaxSize().displayCutoutPadding()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            // Top toolbar moved out to a floating overlay sibling of this Column (see below, near
            // the outline panel/format toolbar/settings window) - 2026-08-14, "顶栏是浮在内容上的
            // 悬浮条，不是占专属一行的实心卡片". No divider here anymore either; the scrollable
            // content below now runs full-height, underneath the floating bar.
            // Fix B undo affordance: "每个操作都要能撤销，至少提供一次撤销上一步" - shown only
            // right after a merge/split, cleared once used (see canUndoStructuralChange's doc).
            val canUndoStructuralChange by vm.canUndoStructuralChange.collectAsState()
            if (isEditMode && canUndoStructuralChange) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "章节结构已调整",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.undoLastStructuralChange() }) { Text("撤销") }
                    }
                }
            }
            // View-mode pinch zoom: multiplies fontSize (like edit mode's slider) so text
            // rewraps within the same width, instead of a graphicsLayer canvas scale - a visual
            // scale doesn't reflow, so zoomed-in text just ran off the edge of the screen.
            var viewZoom by remember(doc.id) { mutableFloatStateOf(doc.viewZoomScale) }
            LaunchedEffect(viewZoom, isEditMode) {
                if (isEditMode) return@LaunchedEffect
                delay(500)
                vm.updateViewZoomScale(doc.id, viewZoom)
            }
            val zoomScale = if (isEditMode) doc.editZoomScale else viewZoom
            // 视口裁剪跟手修复 (2026-08-16，见 docs/PITFALLS.md「提词器正文滑块拖动卡顿」的更新
            // 记录) - 拖动正文相关滑块（字号/整体缩放/编辑缩放/行距/副标题字号）时，只有屏幕内看
            // 得见的章节需要用最新值实时重排；屏幕外的章节冻结在拖动开始那一刻的值，直到松手
            // （onBodyDragEnd）才一次性补上，这样每次节流推送要重排的章节数从"全文"降到"一屏"。
            // bodyDisplayDragActive（声明见函数顶部，跟 TeleprompterSettingsWindow 调用是兄弟
            // 作用域共享）由那 5 个滑块的 onBodyDragStart/onBodyDragEnd 回调驱动 -
            // remember(bodyDisplayDragActive) 在它刚变 true 的那一刻捕获一次当前值，此后这个 key
            // 不变，remember 就不会再刷新，天然冻结到松手为止。
            val frozenBodyDisplay = remember(bodyDisplayDragActive) {
                if (bodyDisplayDragActive) Triple(library.settings, zoomScale, doc.subtitleStyle) else null
            }
            var viewportHeightPx by remember { mutableIntStateOf(0) }
            // 上下各留一屏缓冲，避免拖动中间稍微滚动一下就露出"还没重排"的旧文字。只在真的有滑块
            // 在拖时才需要算这个集合 - 空闲时每个章节都直接用 live 值，不用查表。
            val visibleChapterIds = if (!bodyDisplayDragActive) {
                emptySet()
            } else {
                val top = scrollState.value - viewportHeightPx
                val bottom = scrollState.value + viewportHeightPx * 2
                val ids = mutableSetOf<String>()
                var cumulative = 0
                for (ch in doc.chapters) {
                    val h = chapterHeights[ch.id] ?: 0
                    if (cumulative + h >= top && cumulative <= bottom) ids.add(ch.id)
                    cumulative += h
                }
                ids
            }
            // Was LazyColumn - switched to Column+verticalScroll after confirming (research,
            // JetBrains/compose-multiplatform#3333 aka YouTrack CMP-3333) that Compose's text
            // selection handle-drag has no wiring into LazyColumn's own scroll: dragging a
            // handle past the visible edge never triggers an auto-scroll, and if the item the
            // selection started in gets recycled off-screen the handle vanishes outright. A
            // plain scrollable Column doesn't have this gap - it goes through the same
            // bringIntoView/scroll coordination that selection dragging already expects.
            // Trade-off: all chapters compose up front instead of on-demand, which is fine for
            // this document-editor use case (chapters are already capped via
            // MAX_PARAGRAPHS_PER_CHAPTER, and this is a 6-person internal tool, not a feed).
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // Viewport height in px for the visibleChapterIds calc above - this Column's
                    // own size is bounded by weight(1f)/fillMaxWidth() regardless of scroll offset
                    // (verticalScroll only affects child placement, not this Column's own size).
                    .onSizeChanged { viewportHeightPx = it.height }
                    // The one backdrop capture on this screen - see editorBackdrop's own comment
                    // above for why it's scoped to just this Column (not the whole screen).
                    .layerBackdrop(editorBackdrop)
                    .background(MaterialTheme.colorScheme.background)
                    // #599: RichEditText's own imePadding doesn't work - pad the scrolling
                    // content ourselves instead of relying on the library for this.
                    .windowInsetsPadding(WindowInsets.ime)
                    .let {
                        if (isEditMode) {
                            it
                        } else {
                            it.pointerInput(doc.id) {
                                detectTransformGestures { _, _, zoom, _ ->
                                    viewZoom = (viewZoom * zoom).coerceIn(0.5f, 3f)
                                }
                            }
                        }
                    }
                    .verticalScroll(scrollState)
                    // top raised from 12dp: the toolbar is now a floating overlay (see above), not
                    // a normal-flow sibling reserving its own row - same reasoning as the existing
                    // bottom=220dp reserve for the format toolbar floating at the other edge.
                    .padding(start = 20.dp, top = 90.dp, end = 20.dp, bottom = 220.dp),
            ) {
                // "内容宽度" - the outer Column above stays full-width so the scroll/pinch
                // gesture area doesn't shrink with it (a drag can start from either edge even
                // when the reading column itself is narrowed in landscape); only this inner
                // wrapper narrows and centers the actual chapters. contentWidthFraction=1f
                // (portrait default) makes this a no-op vs. the old unwrapped layout.
                Column(
                    modifier = Modifier
                        .fillMaxWidth(contentWidthFraction)
                        .align(Alignment.CenterHorizontally),
                ) {
                    doc.chapters.forEachIndexed { index, chapter ->
                        // Stable identity across structural mutations - without this, Compose
                        // falls back to positional identity: removing/inserting a chapter shifts
                        // every later chapter's slot, and each shifted slot gets treated as "the
                        // same composable, different content" instead of "a different chapter".
                        // That's what caused the erratic re-hydration cascade after merge/split
                        // (confirmed via the [OBSERVE] logs - unrelated chapters re-hydrating when
                        // a totally different chapter was merged elsewhere in the list) and, from
                        // there, wrong/stale scroll-height bookkeeping.
                        key(chapter.id) {
                            Box(
                                Modifier.onGloballyPositioned { coords ->
                                    chapterHeights[chapter.id] = coords.size.height
                                },
                            ) {
                                val isThisChapterFocused = isEditMode && focusedChapterId == chapter.id
                                // 正在编辑的章节永远用 live 值（WYSIWYG，不能冻结），其余章节按视口
                                // 裁剪：屏幕内跟手，屏幕外冻结到松手。
                                val useLiveBodyDisplay = !bodyDisplayDragActive ||
                                    isThisChapterFocused ||
                                    chapter.id in visibleChapterIds
                                ChapterItem(
                                    docId = doc.id,
                                    chapter = chapter,
                                    isEditMode = isEditMode,
                                    isFirstChapter = index == 0,
                                    isFocused = isThisChapterFocused,
                                    settings = if (useLiveBodyDisplay) library.settings else frozenBodyDisplay!!.first,
                                    fontFamily = doc.fontFamily,
                                    subtitleStyle = if (useLiveBodyDisplay) doc.subtitleStyle else frozenBodyDisplay!!.third,
                                    zoomScale = if (useLiveBodyDisplay) zoomScale else frozenBodyDisplay!!.second,
                                    vm = vm,
                                    onBecomeActive = { state ->
                                        activeState.value = state
                                        // Defensive reset, same reasoning as switchToChapter's own
                                        // comment above: a body field gaining focus is unambiguous
                                        // proof the subtitle no longer has it, even if the
                                        // subtitle's own onFocusChanged(false) never got a chance
                                        // to fire because its composable was torn down first by a
                                        // direct chapter switch.
                                        subtitleFocused = false
                                    },
                                    onSubtitleFocusChanged = { focused -> subtitleFocused = focused },
                                    onRequestFocus = switchToChapter,
                                    onDelete = { vm.deleteChapter(doc.id, chapter.id) },
                                    onMoveUp = { vm.moveChapter(doc.id, chapter.id, -1) },
                                    onMoveDown = { vm.moveChapter(doc.id, chapter.id, 1) },
                                    onMergeIntoPrevious = mergeIntoPrevious,
                                    onPromoteParagraph = promoteParagraph,
                                )
                            }
                        }
                    }
                    if (isEditMode) {
                        TextButton(
                            onClick = { vm.addChapter(doc.id, afterChapterId = focusedChapterId) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("新增章节")
                        }
                    }
                }
            }
        }

        // Floating top toolbar - sibling of the scrollable content Column above, not its ancestor
        // (same layerBackdrop sibling rule as everywhere else on this screen), so real chapter
        // text keeps scrolling underneath it instead of stopping at a hard divider. Own
        // chromaticAberration=false: it now genuinely overlaps real scrolling content (it didn't
        // when it was still a normal-flow Column child, which is why it kept the shared baseline's
        // chromaticAberration=true until now) - see docs/PITFALLS.md for why that combination
        // reads as rainbow corruption. sampleBackdrop=false on every button inside: this Row is
        // already its own tunedGlass() surface, so a child re-sampling+re-blurring the same
        // backdrop again is the same redundant-cost/smudge bug fixed in the settings window.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .tunedGlass(
                    backdrop = editorBackdrop,
                    params = liveGlassParamsFor(GlassTarget.TeleprompterEditor).copy(chromaticAberration = false),
                    cornerRadius = 20.dp,
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (titleEditing) {
                val focusRequester = remember { FocusRequester() }
                OutlinedTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f).focusRequester(focusRequester).onFocusChanged {
                        if (!it.isFocused && titleEditing) {
                            titleEditing = false
                            vm.renameDocument(doc.id, titleText.ifBlank { doc.title })
                        }
                    },
                )
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
            } else {
                Text(
                    text = doc.title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).let {
                        if (isEditMode) {
                            it.clickable { titleText = doc.title; titleEditing = true }
                        } else {
                            it
                        }
                    },
                )
            }
            if (isEditMode) {
                LiquidCompactButton(
                    onClick = {
                        // Anchor to whatever chapter was being edited (not the viewport-top
                        // chapter) - exiting edit mode should land back on the chapter you were
                        // just working on, even if it had scrolled out of the viewport's top.
                        pendingAnchor = captureAnchor(overrideChapterId = focusedChapterId)
                        switchToChapter(null)
                        activeState.value = null
                        isEditMode = false
                    },
                    backdrop = editorBackdrop,
                    sampleBackdrop = false,
                    inactiveTint = Color.Transparent,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("完成")
                }
            } else {
                LiquidCompactButton(
                    onClick = {
                        pendingAnchor = captureAnchor()
                        isEditMode = true
                    },
                    backdrop = editorBackdrop,
                    sampleBackdrop = false,
                    inactiveTint = Color.Transparent,
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("编辑")
                }
            }
            Spacer(Modifier.width(4.dp))
            LiquidCompactIconButton(onClick = onOpenPlayer, backdrop = editorBackdrop, sampleBackdrop = false, inactiveTint = Color.Transparent) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "播放")
            }
            LiquidCompactIconButton(onClick = { settingsOpen = true }, backdrop = editorBackdrop, sampleBackdrop = false, inactiveTint = Color.Transparent) {
                Icon(Icons.Filled.Settings, contentDescription = "设置")
            }
            LiquidCompactIconButton(onClick = onOpenLibrary, backdrop = editorBackdrop, sampleBackdrop = false, inactiveTint = Color.Transparent) {
                Icon(Icons.Filled.LibraryBooks, contentDescription = "文档库")
            }
        }

        TeleprompterOutlinePanel(
            backdrop = editorBackdrop,
            expanded = outlineExpanded,
            onExpandedChange = { outlineExpanded = it },
            chapters = doc.chapters,
            onJump = { chapterId ->
                val idx = doc.chapters.indexOfFirst { it.id == chapterId }
                if (idx >= 0) {
                    val targetOffset = doc.chapters.take(idx).sumOf { chapterHeights[it.id] ?: 0 }
                    scope.launch { scrollState.animateScrollTo(targetOffset) }
                }
                outlineExpanded = false
            },
            // Editing-only affordance (problem 1's manual correction UI) - null in the player
            // screen's read-only outline, where merging chapters wouldn't make sense mid-broadcast.
            onMergeUp = if (isEditMode) mergeIntoPrevious else null,
            // Trigger replaced below by OutlineScrubTrigger (2026-08-16, "预览面板大纲抽屉，也做跟
            // 播放页面一样的效果") - the panel itself (drawer + rows) is unchanged either way.
            showTrigger = false,
            target = GlassTarget.TeleprompterEditor,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
        )

        // 2026-08-16 (reverted the same day's earlier toggle experiment): trigger hides while the
        // drawer is open - not present, not overlapping the drawer's own content near the same
        // edge - dismissal is outside-tap-to-close (TeleprompterOutlinePanel's own scrim) only.
        if (!outlineExpanded) {
            OutlineScrubTrigger(
                backdrop = editorBackdrop,
                chapters = doc.chapters,
                currentIndex = {
                    val anchorChapterId = captureAnchor()?.first
                    doc.chapters.indexOfFirst { it.id == anchorChapterId }.coerceAtLeast(0)
                },
                onOpenFull = { outlineExpanded = true },
                onJumpToIndex = { index ->
                    val chapterId = doc.chapters.getOrNull(index)?.id
                    if (chapterId != null) {
                        val targetOffset = doc.chapters.take(index).sumOf { chapterHeights[it.id] ?: 0 }
                        scope.launch { scrollState.animateScrollTo(targetOffset) }
                    }
                },
                target = GlassTarget.TeleprompterEditor,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
            )
        }

        if (isEditMode && focusedChapterId != null) {
            EditorFormatToolbar(
                backdrop = editorBackdrop,
                state = activeState.value,
                disabled = subtitleFocused,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.ime),
            )
        }

        // Last child of this Box = highest z-order = drawn on top of the outline drawer/format
        // toolbar too, not just the document. Inside the same composition (not a Dialog/Popup, see
        // TeleprompterSettingsWindow's own doc comment for why) so it can sample editorBackdrop
        // and refract the real chapter text - a Popup/Dialog opens its own window and physically
        // cannot see this tree's content.
        if (settingsOpen) {
            TeleprompterSettingsWindow(
                backdrop = editorBackdrop,
                settings = library.settings,
                onUpdate = vm::updateSettings,
                fontFamily = doc.fontFamily,
                onFontFamilyChange = { vm.updateDocumentFontFamily(doc.id, it) },
                editZoomScale = doc.editZoomScale,
                onEditZoomScaleChange = { vm.updateEditZoomScale(doc.id, it) },
                subtitleStyle = doc.subtitleStyle,
                onSubtitleStyleChange = { mutate -> vm.updateSubtitleStyle(doc.id, mutate) },
                onOpenObserveLog = { settingsOpen = false; onOpenObserveLog() },
                onDismiss = { settingsOpen = false },
                onBodyDragStart = { bodyDisplayDragActive = true },
                onBodyDragEnd = { bodyDisplayDragActive = false },
            )
        }
    }
}

/**
 * Renders one chapter as an uninterrupted slice of a continuous document (heading directly
 * above its body, no card/border/background around either) - the per-chapter editor split is
 * an internal implementation detail (see the 2万字符 TextField-crash constraint) that must stay
 * invisible: a static heading + static rich text look pixel-identical to their editable
 * counterparts below, so swapping between them on tap/focus causes no visible jump.
 */
@Composable
private fun ChapterItem(
    docId: String,
    chapter: TeleprompterChapter,
    isEditMode: Boolean,
    isFirstChapter: Boolean,
    isFocused: Boolean,
    settings: TeleprompterSettings,
    fontFamily: TeleprompterFontFamily,
    subtitleStyle: TeleprompterSubtitleStyle,
    zoomScale: Float,
    vm: TeleprompterVm,
    onBecomeActive: (RichTextState?) -> Unit,
    onSubtitleFocusChanged: (Boolean) -> Unit,
    onRequestFocus: (String?) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMergeIntoPrevious: (String) -> Unit,
    onPromoteParagraph: (String, Int) -> Unit,
) {
    val state = rememberRichTextState()
    var hydrated by remember { mutableStateOf(false) }
    // What the state held right after the most recent hydration - used to tell "the debounce/
    // blur autosave fired because the user actually typed" apart from "it fired because setHtml
    // just ran" (hydration itself changes state.annotatedString, which is what those two save
    // paths key off). Compared by full AnnotatedString equality, not just plain text, so a
    // formatting-only edit (e.g. toggling bold with no character change) still counts as real.
    var lastHydratedSnapshot by remember { mutableStateOf<AnnotatedString?>(null) }
    // Keyed on structureVersion too (not just chapter.id): merge/split rewrite this same
    // chapter's contentHtml for an EXISTING id, and without this the already-hydrated state
    // would never re-read it - normal typing deliberately does NOT bump structureVersion, so
    // this doesn't fire (and reset the cursor) on every 800ms debounced autosave.
    LaunchedEffect(chapter.id, chapter.structureVersion) {
        // Step 6: about to hydrate from contentHtml.
        TeleprompterObserveLog.log(
            "step6 UI re-hydrating, chapterId=${chapter.id}, structureVersion=${chapter.structureVersion}, " +
                "contentHtml len=${chapter.contentHtml.length}, head=\"${chapter.contentHtml.take(50)}\"",
        )
        state.setHtml(chapter.contentHtml)
        lastHydratedSnapshot = state.annotatedString
        hydrated = true
        // Step 7: RichTextState after setHtml - what actually ended up in the live editor state.
        TeleprompterObserveLog.log(
            "step7 hydration complete, chapterId=${chapter.id}, state.annotatedString.text len=${state.annotatedString.text.length}",
        )
        LogUtils.d(
            "chapter hydrated, chapterId=${chapter.id}, structureVersion=${chapter.structureVersion}, htmlLength=${chapter.contentHtml.length}",
            tag = TAG,
        )
    }

    val composeFontFamily = fontFamily.toComposeFontFamily()
    // zoomScale re-lays-out text at a different size (fits more/less on screen) by feeding a
    // scaled value into the TextStyle - never touches settings.fontSizeSp itself, so the
    // stored/exported font size is always the real configured one regardless of display zoom.
    // settings.overallScale ("整体缩放") is a separate, persistent multiplier that also reaches
    // explicit per-run sizes cascaded in from Word import (see the read-only branch below,
    // where those get their own scaledForDisplay pass) - fontSizeSp alone can no longer reach
    // those now that imports carry explicit sizes.
    val displayFontSizeSp = settings.fontSizeSp * settings.overallScale * zoomScale
    val forcedAlign = settings.textAlignMode.toComposeAlignOrNull()
    // 2026-08-22 行距解耦（方案 A，详见 TeleprompterTextScaling.kt 头部）：
    // 倍数 == 1.0 时 lineHeight 留空，让系统按每行实际字形逐行排版——这是唯一能做到逐行精确的
    // 状态，而且是默认状态。倍数 != 1.0 时这里给的是**兜底值**：只有"既没有段落级行距、段内也
    // 一个显式字号都没有"的段落才会落到它头上（也就是原生新建、走正文默认字号的文稿）。Word
    // 导入那些自带字号的段落，由 scaledForDisplay 按"段内最大字号 × 倍数"逐段覆盖掉这个值。
    val bodyLineHeight = if (settings.lineHeightMultiplier == 1f) {
        TextUnit.Unspecified
    } else {
        (displayFontSizeSp * settings.lineHeightMultiplier).sp
    }
    val bodyStyle = TextStyle(
        fontSize = displayFontSizeSp.sp,
        lineHeight = bodyLineHeight,
        fontFamily = composeFontFamily,
        textAlign = forcedAlign ?: TextAlign.Unspecified,
    )
    // Independent of body font size - subtitleStyle.fontSizeSp is its own absolute value (a
    // slider in settings, 12-72), not a multiplier on the body's default like it used to be, so a
    // heading keeps whatever size the source Word doc actually used (or whatever the user set)
    // regardless of what "默认字号" is doing to plain body text.
    //
    // 2026-08-21 - 这里原本有一个 `.coerceAtMost(displayFontSizeSp * 1.5f)`，即"副标题最大不超过
    // 正文字号的 1.5 倍"。**那是个 bug，已删除。** 用"正文字号的倍数"当上限没有道理：正文默认字号
    // 往小调，副标题上限跟着缩水——用户把正文设成 16sp 时上限只有 24sp，于是副标题滑块从 24 拖到
    // 72sp 屏幕上纹丝不动，整个滑块看起来是坏的（真机复现确认）。
    //
    // 当初加封顶是为了绕开另一个问题：编辑态的副标题输入框是 singleLine，字号一大就溢出屏幕，而
    // 且试过的三种容纳办法（horizontalScroll、外层 Box、clipToBounds）都没能在真机上生效。现在
    // 改成从根上解决——让那个输入框可以换行（见 ChapterHeading 里的 softWrap 说明），溢出问题
    // 消失，就不需要用限制可选范围的方式去回避它了。滑块自己的 12..72sp 就是唯一上限。
    val subtitleDisplaySizeSp = subtitleStyle.fontSizeSp * settings.overallScale * zoomScale
    val headingStyle = TextStyle(
        fontSize = subtitleDisplaySizeSp.sp,
        lineHeight = (subtitleDisplaySizeSp * settings.lineHeightMultiplier).sp,
        fontWeight = if (subtitleStyle.bold) FontWeight.Bold else FontWeight.Normal,
        fontFamily = composeFontFamily,
    )
    val headingExplicitColor = subtitleStyle.colorArgb?.let { Color(it.toInt()) }

    Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
        ChapterHeading(
            docId = docId,
            chapter = chapter,
            isEditMode = isEditMode,
            isFirstChapter = isFirstChapter,
            style = headingStyle,
            explicitColor = headingExplicitColor,
            onFocusChanged = onSubtitleFocusChanged,
            onRequestFocus = { onRequestFocus(chapter.id) },
            vm = vm,
            onDelete = onDelete,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onMergeIntoPrevious = onMergeIntoPrevious,
        )
        Spacer(Modifier.height(2.dp))

        if (isFocused) {
            val focusRequester = remember { FocusRequester() }
            var wasFocused by remember { mutableStateOf(false) }
            BasicRichTextEditor(
                state = state,
                textStyle = bodyStyle,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            wasFocused = true
                            onRequestFocus(chapter.id)
                            onBecomeActive(state)
                        } else if (wasFocused) {
                            wasFocused = false
                            // Only flush if something actually changed since hydration - merely
                            // focusing and un-focusing without typing (e.g. tapping in to look,
                            // then tapping away) used to still round-trip state through toHtml()
                            // and save that back, which is a lossy operation (verified via the
                            // [OBSERVE] logs: re-serializing untouched hydrated content shrank it
                            // by dozens of chars from style-attribute normalization). Skip the
                            // save entirely when nothing changed instead of relying on the
                            // round-trip being harmless.
                            if (state.annotatedString != lastHydratedSnapshot) {
                                vm.onChapterContentChanged(docId, chapter.id, state.toHtml(), state.annotatedString.text)
                            }
                            // deliberately NOT clearing onBecomeActive(null) here: the format
                            // toolbar's numeric font-size input briefly steals focus to accept
                            // typed digits, which fires this same blur path - nulling the active
                            // RichTextState here would break "type a number, apply it" (state
                            // becomes null right when the toolbar needs it). Only the screen-level
                            // 完成 button and switching to a different chapter actually retarget it.
                        }
                    },
            )
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            // 2026-08-16 - 诊断"拖选中光标手柄不跟手、乱跳"发现的真实根因（见 docs/PITFALLS.md
            // 同名条目）：这个 LaunchedEffect 原来直接 key 在 state.annotatedString 上，但真机日志
            // 证明单纯拖动手柄改选区（文字内容完全没变，textLen 全程不变）也会让 state.annotatedString
            // 被判定为"变了"（应该是 richeditor-compose 库内部按光标位置拆分/合并 span 的副作用，
            // 不是文档能改的第三方库代码）——于是每次手柄移动都被误判成"真的编辑了"，800ms 后触发一次
            // 完整的 state.toHtml() 序列化 + vm.onChapterContentChanged 持久化写入（内容大的章节这一下
            // 不便宜），而这个写入会替换整个 TeleprompterLibrary StateFlow、引发大范围重组，跟手柄
            // 自己的位置重绘抢帧——真机录屏逐帧看，正是这个模式：选区文字在正确扩大，但手柄那个圆圈
            // 卡在原地不动，隔了十几帧才突然跳到新位置。改成只 key 在 state.annotatedString.text
            // （纯文本）上：单纯移动选区/光标不再触发这一串重组；真正的文字编辑（text 会变）依旧
            // 正常触发。代价：纯样式改动（比如选中后点粗体，一个字都没打）不再被这个"打字防抖"实时
            // 捕获，但失焦时的兜底判断（见上面 wasFocused 分支）仍然比较完整的 annotatedString，
            // 会在切走这个章节时把样式变化补存下来，不会真的丢。
            LaunchedEffect(state.annotatedString.text) {
                if (!hydrated) return@LaunchedEffect
                // Only arm the debounce on a REAL edit, not on hydration (setHtml also changes
                // state.annotatedString, which is what this effect is keyed on, so it used to
                // fire - and 800ms later save a round-tripped copy of content nobody touched -
                // on every focus/re-hydration, not just on typing). A merge/split rewriting this
                // chapter's content bumps structureVersion, which re-hydrates and therefore
                // changes state.annotatedString too - that cancels+restarts this exact
                // LaunchedEffect (new key value), so any debounce that was already in flight for
                // the OLD pre-merge content is implicitly thrown away here for free, without a
                // separate explicit-cancel step.
                if (state.annotatedString.text == lastHydratedSnapshot?.text) return@LaunchedEffect
                TeleprompterObserveLog.log(
                    "debounce armed, chapterId=${chapter.id}, textLen=${state.annotatedString.text.length}",
                )
                delay(800)
                vm.onChapterContentChanged(docId, chapter.id, state.toHtml(), state.annotatedString.text)
            }
        } else {
            // Tapping the read-only render must land the cursor where the user actually tapped,
            // not wherever state.selection last happened to be (e.g. end-of-text from the
            // original setHtml hydration) - a plain onClick with no position awareness is
            // exactly what produced "tap anywhere, cursor jumps to the last character".
            var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
            // Fix B / "提升为副标题": long-pressing a body paragraph resolves the tap position to
            // a paragraph index (same getOffsetForPosition mechanism as the tap-to-cursor fix)
            // and offers to split a new chapter starting at that paragraph.
            var promoteParagraphIndex by remember(chapter.id) { mutableStateOf<Int?>(null) }
            Box(
                Modifier
                    .fillMaxWidth()
                    .let {
                        if (isEditMode) {
                            it.pointerInput(chapter.id) {
                                detectTapGestures(
                                    onTap = { offset ->
                                        textLayoutResult?.let { layout ->
                                            val charIndex = layout.getOffsetForPosition(offset)
                                                .coerceIn(0, state.annotatedString.text.length)
                                            state.selection = TextRange(charIndex, charIndex)
                                        }
                                        onRequestFocus(chapter.id)
                                    },
                                    onLongPress = { offset ->
                                        textLayoutResult?.let { layout ->
                                            val charIndex = layout.getOffsetForPosition(offset)
                                                .coerceIn(0, state.annotatedString.text.length)
                                            promoteParagraphIndex = paragraphIndexForCharIndex(chapter.contentHtml, charIndex)
                                        }
                                    },
                                )
                            }
                        } else {
                            it
                        }
                    },
            ) {
                if (chapter.contentHtml.isBlank()) {
                    Text(
                        "正文…",
                        style = bodyStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // Read-only (not the focused/editing chapter): render a display-only rescale
                    // of state's AnnotatedString (settings.overallScale/lineHeightMultiplier onto
                    // explicit per-run sizes and per-paragraph spacing cascaded in from Word
                    // import, plus a forced alignment if set) instead of feeding `state` itself
                    // into BasicRichText - see TeleprompterTextScaling.kt's own doc for why this
                    // must never be fed back into the live editable state. The focused/editing
                    // chapter (BasicRichTextEditor above) intentionally does NOT get this pass -
                    // WYSIWYG while actively typing, matching what's actually stored.
                    val displayText = remember(
                        state.annotatedString, settings.overallScale, settings.lineHeightMultiplier,
                        forcedAlign, displayFontSizeSp,
                    ) {
                        state.annotatedString.scaledForDisplay(
                            sizeScale = settings.overallScale,
                            lineHeightScale = settings.lineHeightMultiplier,
                            forcedAlign = forcedAlign,
                            // 段内一个显式字号都没有时（原生新建的文稿）用它兜底，保证这类文稿的
                            // 行距依然跟着"正文默认字号"走。
                            fallbackFontSizeSp = displayFontSizeSp,
                        )
                    }
                    Text(
                        text = displayText,
                        style = bodyStyle,
                        onTextLayout = { textLayoutResult = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                DropdownMenu(
                    expanded = promoteParagraphIndex != null,
                    onDismissRequest = { promoteParagraphIndex = null },
                ) {
                    DropdownMenuItem(
                        text = { Text("提升为副标题") },
                        onClick = {
                            promoteParagraphIndex?.let { onPromoteParagraph(chapter.id, it) }
                            promoteParagraphIndex = null
                        },
                    )
                }
            }
        }
    }
}

/**
 * Edit mode always renders a live BasicTextField for the subtitle - no Text/BasicTextField
 * swap, no local "editing" state, no requestFocus() dance. Diagnostic logging confirmed that
 * swap-on-tap chain was the actual cause of "点击副标题没反应": a real gap between the tap
 * landing on the Text and the BasicTextField existing to receive focus, one recomposition late.
 * Tapping now behaves exactly like the body: land wherever you tapped, same as any other field.
 * View mode still renders a plain read-only Text.
 * The long-press-to-open-menu affordance moved off the text itself (edit mode has no Text to
 * long-press anymore, and it collided with the system's own text-selection long-press anyway)
 * to a small icon button to the left, edit-mode only.
 */
@Composable
private fun ChapterHeading(
    docId: String,
    chapter: TeleprompterChapter,
    isEditMode: Boolean,
    isFirstChapter: Boolean,
    style: TextStyle,
    explicitColor: Color?,
    onFocusChanged: (Boolean) -> Unit,
    onRequestFocus: () -> Unit,
    vm: TeleprompterVm,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMergeIntoPrevious: (String) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var deleteConfirming by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (isEditMode) {
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "章节菜单", modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    if (!isFirstChapter) {
                        // Fix B / "降级为正文": this subtitle becomes a body line merged into
                        // the previous chapter - same operation the outline panel's "并入上一
                        // 章" triggers.
                        DropdownMenuItem(
                            text = { Text("降级为正文") },
                            leadingIcon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onMergeIntoPrevious(chapter.id)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("上移") },
                        leadingIcon = { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null) },
                        onClick = { menuExpanded = false; onMoveUp() },
                    )
                    DropdownMenuItem(
                        text = { Text("下移") },
                        leadingIcon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) },
                        onClick = { menuExpanded = false; onMoveDown() },
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = { menuExpanded = false; deleteConfirming = true },
                    )
                }
            }
            Spacer(Modifier.width(2.dp))
            // 2026-08-21 - `singleLine` 从 true 改成 false，让副标题可以折行。
            //
            // 为什么要改：singleLine 的输入框不会换行，副标题字号一大就横着溢出屏幕。以前是靠
            // "把字号封顶在正文字号的 1.5 倍"去回避这个溢出，代价是副标题字号滑块大部分行程失效
            // （见 ChapterItem 里 subtitleDisplaySizeSp 的说明）。现在让它自然折行，溢出问题从根
            // 上没有了，封顶也就删掉了。查看态那一支本来就是普通 Text，一直是会折行的，这次改完
            // 编辑态和查看态的行为终于一致。
            //
            // 数据上仍然保持"副标题是一行文本"：下面 onValueChange 里把换行符过滤掉，所以用户按
            // 回车不会真的在标题里插入换行，只是显示上允许折行。
            BasicTextField(
                value = chapter.subtitle,
                onValueChange = { raw ->
                    val newValue = raw.replace("\n", "").replace("\r", "")
                    TeleprompterObserveLog.log(
                        "subtitle onValueChange, chapterId=${chapter.id}, newValue=\"$newValue\" (len=${newValue.length})",
                    )
                    vm.renameChapterSubtitle(docId, chapter.id, newValue)
                },
                textStyle = style.copy(color = explicitColor ?: MaterialTheme.colorScheme.onSurface),
                singleLine = false,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focusState ->
                        onFocusChanged(focusState.isFocused)
                        if (focusState.isFocused) onRequestFocus()
                    },
            )
        } else {
            Text(
                text = chapter.subtitle.ifBlank { "未命名章节" },
                style = style,
                color = explicitColor ?: if (chapter.subtitle.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (deleteConfirming) {
        LiquidDialog(
            onDismissRequest = { deleteConfirming = false },
            title = "删除章节",
            text = "将删除「${chapter.subtitle.ifBlank { "未命名章节" }}」及其全部正文内容，此操作不可撤销。",
            confirmLabel = "删除",
            onConfirm = { deleteConfirming = false; onDelete() },
        )
    }
}

/**
 * Pitfall #1 (most important one): a tap on any button/swatch here must never move Compose/Android
 * focus away from the RichTextEditor, or the user's selection collapses before the format command
 * reaches it. Every interactive element below is a [li.songe.gkd.glassui.LiquidCompactButton] (or
 * [li.songe.gkd.glassui.LiquidCompactIconButton]) with `focusable = false`, which drives its click
 * off a raw pointer gesture instead of `Modifier.clickable`'s focusable-by-default machinery - see
 * that parameter's own doc comment. The one exception is the font-size number field, which
 * legitimately needs IME focus to accept typed digits - ChapterItem's blur handler was adjusted
 * (see its comment) so that temporary blur doesn't null out [state] out from under it.
 */
@Composable
private fun EditorFormatToolbar(
    backdrop: Backdrop,
    state: RichTextState?,
    disabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(FormatTab.Character) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .tunedGlass(
                backdrop = backdrop,
                // chromaticAberration off pre-emptively, not reactively - this bar floats over
                // the real scrolling chapter text (unlike the top toolbar, which sits in normal
                // layout flow above it and never overlaps real content), and this session found
                // the same rainbow-corruption bug on two OTHER real-content-backed surfaces
                // (settings window, player control bar) that only showed up under a light
                // background + bold text - see PITFALLS.md. Not waiting to hit it a third time.
                params = liveGlassParamsFor(GlassTarget.TeleprompterEditor).copy(chromaticAberration = false),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            ),
    ) {
        if (disabled) {
            // Cursor is in the subtitle (a plain BasicTextField, not a RichTextState) - every
            // control below acts via toggleSpanStyle on `state`, which would either no-op or
            // (worse) silently act on the wrong/stale chapter body's state. Say why instead of
            // rendering controls that quietly do nothing when tapped.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "副标题样式请在设置中调整",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@Box
        }
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FormatTabChip(backdrop, "文字", tab == FormatTab.Character) { tab = FormatTab.Character }
                FormatTabChip(backdrop, "段落", tab == FormatTab.Paragraph) { tab = FormatTab.Paragraph }
                Spacer(Modifier.weight(1f))
                // Workaround for a compose-rich-editor bug (upstream issues #76/#747): dragging
                // the selection handles to extend a selection gets stuck after a few characters.
                // No clean fix exists yet, so this sidesteps handle-dragging entirely for the
                // common "format the whole chapter" case.
                LiquidCompactButton(
                    onClick = {
                        state?.let { it.selection = TextRange(0, it.annotatedString.text.length) }
                    },
                    backdrop = backdrop,
                    focusable = false,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    sampleBackdrop = false,
                    inactiveTint = Color.Transparent,
                ) {
                    Text("全选本章节", style = MaterialTheme.typography.labelMedium)
                }
            }
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (tab) {
                    FormatTab.Character -> CharacterFormatControls(backdrop, state)
                    FormatTab.Paragraph -> ParagraphFormatControls(backdrop, state)
                }
            }
        }
    }
}

private enum class FormatTab { Character, Paragraph }

@Composable
private fun FormatTabChip(backdrop: Backdrop, label: String, selected: Boolean, onClick: () -> Unit) {
    LiquidCompactButton(
        onClick = onClick,
        backdrop = backdrop,
        active = selected,
        focusable = false,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        sampleBackdrop = false,
        inactiveTint = Color.Transparent,
    ) {
        Text(
            label,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun ToggleIconButton(
    backdrop: Backdrop,
    icon: ImageVector,
    active: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    LiquidCompactIconButton(
        onClick = onClick,
        backdrop = backdrop,
        active = active,
        focusable = false,
        sampleBackdrop = false,
        inactiveTint = Color.Transparent,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else LocalContentColor.current,
        )
    }
}

@Composable
private fun StepperButton(backdrop: Backdrop, icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    LiquidCompactIconButton(
        onClick = onClick,
        backdrop = backdrop,
        focusable = false,
        size = 32.dp,
        sampleBackdrop = false,
        inactiveTint = Color.Transparent,
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ColorSwatchTrigger(backdrop: Backdrop, currentColor: Color?, contentDescription: String, onClick: () -> Unit) {
    LiquidCompactIconButton(
        onClick = onClick,
        backdrop = backdrop,
        focusable = false,
        sampleBackdrop = false,
        inactiveTint = Color.Transparent,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(currentColor?.takeIf { it != Color.Unspecified } ?: Color.Transparent)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
        if (currentColor == null || currentColor == Color.Unspecified) {
            Icon(
                Icons.Filled.Close,
                contentDescription = contentDescription,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun CharacterFormatControls(backdrop: Backdrop, state: RichTextState?) {
    val current = state?.currentSpanStyle
    var textColorPopup by remember { mutableStateOf(false) }
    var highlightPopup by remember { mutableStateOf(false) }

    ToggleIconButton(
        backdrop,
        Icons.Filled.FormatBold,
        active = current?.fontWeight == FontWeight.Bold,
        contentDescription = "加粗",
    ) { state?.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold)) }

    ToggleIconButton(
        backdrop,
        Icons.Filled.FormatItalic,
        active = current?.fontStyle == FontStyle.Italic,
        contentDescription = "斜体",
    ) { state?.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic)) }

    ToggleIconButton(
        backdrop,
        Icons.Filled.FormatUnderlined,
        active = current?.textDecoration?.contains(TextDecoration.Underline) == true,
        contentDescription = "下划线",
    ) { state?.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline)) }

    VerticalDivider(modifier = Modifier.height(24.dp), color = MaterialTheme.colorScheme.outlineVariant)

    ColorSwatchTrigger(backdrop, current?.color, "文字颜色") { textColorPopup = true }
    ColorSwatchTrigger(backdrop, current?.background, "高亮") { highlightPopup = true }

    VerticalDivider(modifier = Modifier.height(24.dp), color = MaterialTheme.colorScheme.outlineVariant)

    FontSizeStepper(backdrop, state)

    VerticalDivider(modifier = Modifier.height(24.dp), color = MaterialTheme.colorScheme.outlineVariant)

    LetterSpacingStepper(backdrop, state)

    if (textColorPopup) {
        TeleprompterColorPickerPopup(
            title = "文字颜色",
            presets = TextColorPresets,
            current = current?.color,
            target = GlassTarget.TeleprompterEditor,
            onDismiss = { textColorPopup = false },
            onPick = { c -> c?.let { state?.addSpanStyle(SpanStyle(color = it)) }; textColorPopup = false },
        )
    }
    if (highlightPopup) {
        TeleprompterColorPickerPopup(
            title = "高亮",
            presets = HighlightPresets,
            current = current?.background,
            allowClear = true,
            target = GlassTarget.TeleprompterEditor,
            onDismiss = { highlightPopup = false },
            onPick = { c ->
                if (c == null) {
                    state?.removeSpanStyle(SpanStyle(background = Color.Black))
                } else {
                    state?.addSpanStyle(SpanStyle(background = c))
                }
                highlightPopup = false
            },
        )
    }
}

@Composable
private fun FontSizeStepper(backdrop: Backdrop, state: RichTextState?) {
    val currentSp = state?.currentSpanStyle?.fontSize
        ?.takeIf { it != TextUnit.Unspecified }?.value?.roundToInt() ?: 32
    var editing by remember { mutableStateOf(false) }

    fun apply(v: Int) {
        state?.addSpanStyle(SpanStyle(fontSize = v.coerceIn(8, 72).sp))
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        StepperButton(backdrop, Icons.Filled.Remove, "减小字号") { apply(currentSp - 1) }
        if (editing) {
            var text by remember { mutableStateOf(currentSp.toString()) }
            val focusRequester = remember { FocusRequester() }
            BasicTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(2) },
                textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    text.toIntOrNull()?.let { apply(it) }
                    editing = false
                }),
                modifier = Modifier
                    .width(44.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        if (!it.isFocused) {
                            text.toIntOrNull()?.let { v -> apply(v) }
                            editing = false
                        }
                    },
            )
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        } else {
            LiquidCompactButton(
                onClick = { editing = true },
                backdrop = backdrop,
                focusable = false,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.width(44.dp),
                sampleBackdrop = false,
                inactiveTint = Color.Transparent,
            ) {
                Text("$currentSp", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
        StepperButton(backdrop, Icons.Filled.Add, "增大字号") { apply(currentSp + 1) }
    }
}

@Composable
private fun LetterSpacingStepper(backdrop: Backdrop, state: RichTextState?) {
    val current = state?.currentSpanStyle?.letterSpacing
        ?.takeIf { it != TextUnit.Unspecified }?.value ?: 0f

    fun apply(v: Float) {
        state?.addSpanStyle(SpanStyle(letterSpacing = v.coerceIn(-1f, 5f).sp))
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("字距", style = MaterialTheme.typography.labelSmall)
        StepperButton(backdrop, Icons.Filled.Remove, "缩小字距") { apply(current - 0.5f) }
        Text("%.1f".format(current), modifier = Modifier.width(36.dp), textAlign = TextAlign.Center)
        StepperButton(backdrop, Icons.Filled.Add, "放大字距") { apply(current + 0.5f) }
    }
}

@Composable
private fun ParagraphFormatControls(backdrop: Backdrop, state: RichTextState?) {
    val current = state?.currentParagraphStyle
    var customLineHeightEditing by remember { mutableStateOf(false) }

    Text("行距", style = MaterialTheme.typography.labelSmall)
    LineHeightPresets.forEach { mult ->
        val target = mult.em
        FormatTabChip(
            backdrop = backdrop,
            label = "${mult}x",
            selected = current?.lineHeight == target,
        ) { state?.toggleParagraphStyle(ParagraphStyle(lineHeight = target)) }
    }
    if (customLineHeightEditing) {
        var text by remember { mutableStateOf("") }
        val focusRequester = remember { FocusRequester() }
        BasicTextField(
            value = text,
            onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' }.take(4) },
            textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                text.toFloatOrNull()?.let { v ->
                    state?.toggleParagraphStyle(ParagraphStyle(lineHeight = v.coerceIn(0.5f, 4f).em))
                }
                customLineHeightEditing = false
            }),
            modifier = Modifier
                .width(48.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { if (!it.isFocused) customLineHeightEditing = false },
        )
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    } else {
        FormatTabChip(backdrop = backdrop, label = "自定义", selected = false) { customLineHeightEditing = true }
    }

    VerticalDivider(modifier = Modifier.height(24.dp), color = MaterialTheme.colorScheme.outlineVariant)

    Text("对齐", style = MaterialTheme.typography.labelSmall)
    listOf(
        TextAlign.Left to Icons.Filled.FormatAlignLeft,
        TextAlign.Center to Icons.Filled.FormatAlignCenter,
        TextAlign.Right to Icons.Filled.FormatAlignRight,
        TextAlign.Justify to Icons.Filled.FormatAlignJustify,
    ).forEach { (align, icon) ->
        ToggleIconButton(
            backdrop,
            icon,
            active = current?.textAlign == align,
            contentDescription = align.toString(),
        ) { state?.toggleParagraphStyle(ParagraphStyle(textAlign = align)) }
    }
}

/** Standalone trigger for [TeleprompterOutlinePanel]: short tap opens the full drawer via
 * [onOpenFull]; long-press then drag up/down scrubs a picker showing every chapter at once and
 * jumps straight to the highlighted one via [onJumpToIndex] on release, without ever opening the
 * drawer. Shared by the editor screen (its own small FAB before 2026-08-16) and the player screen
 * (its side trigger) - was two separate copies of this same gesture until this extraction, see
 * docs/DECISIONS.md 2026-08-16 entry for why ("一个功能一个实现" per this project's own rule).
 *
 * One raw pointerInput block (not detectDragGesturesAfterLongPress + a separate clickable) so
 * tap-vs-long-press resolution can't race the way TeleprompterPlayerScreen's edge-tap zone already
 * documented three real gesture-layering bugs from doing exactly that.
 *
 * [currentIndex] is read once, at the moment a long-press is recognized (not continuously) - it's
 * each caller's own idea of "which chapter is currently in view" (list scroll position for the
 * player, cumulative-height scroll anchor for the editor), used only to decide where the scrub
 * starts. [dimAlpha] lets a caller like the player fade this out when its own controls are hidden;
 * defaults to always-visible for callers (the editor) with no such concept. */
@Composable
internal fun BoxScope.OutlineScrubTrigger(
    backdrop: Backdrop,
    chapters: List<TeleprompterChapter>,
    currentIndex: () -> Int,
    onOpenFull: () -> Unit,
    onJumpToIndex: (Int) -> Unit,
    // 2026-08-16 split - editor/player no longer share GlassTarget.Other, so this shared
    // composable can't hardcode either one; each caller passes its own.
    target: GlassTarget,
    modifier: Modifier = Modifier,
    dimAlpha: Float = 1f,
    textColor: Color = Color.Unspecified,
    onInteraction: () -> Unit = {},
) {
    if (chapters.isEmpty()) return
    var scrubbing by remember { mutableStateOf(false) }
    var previewIndex by remember { mutableIntStateOf(0) }
    // Continuous, sub-row drag position in px, not quantized to whole chapter-steps - an earlier
    // version drove the picker's scroll via a LaunchedEffect reacting to previewIndex changes,
    // which added a recomposition+coroutine hop between the finger moving and the list visually
    // catching up (felt as "不跟手" on-device). This tracks the raw pointer delta 1:1 in the SAME
    // gesture loop that updates it, no indirection.
    var dragPosPx by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val rowHeight = 36.dp
    val panelHeight = 216.dp
    val rowHeightPx = with(density) { rowHeight.toPx() }
    val panelHeightPx = with(density) { panelHeight.toPx() }
    val centerOffsetPx = panelHeightPx / 2f - rowHeightPx / 2f
    val resolvedTextColor = textColor.takeOrElse { LocalContentColor.current }

    Box(
        modifier = modifier
            .size(40.dp)
            .graphicsLayer { alpha = dimAlpha }
            .tunedGlass(
                backdrop = backdrop,
                params = liveGlassParamsFor(target).copy(chromaticAberration = false),
                cornerRadius = 999.dp,
            )
            .pointerInput(chapters) {
                val stepPx = rowHeight.toPx()
                val maxPosPx = chapters.lastIndex * stepPx
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onInteraction()
                    val tapUp = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        waitForUpOrCancellation()
                    }
                    if (tapUp != null) {
                        onOpenFull()
                        onInteraction()
                    } else {
                        val startIndex = currentIndex().coerceIn(0, chapters.lastIndex)
                        dragPosPx = startIndex * stepPx
                        previewIndex = startIndex
                        scrubbing = true
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            dragPosPx = (dragPosPx + change.positionChange().y).coerceIn(0f, maxPosPx)
                            change.consume()
                            previewIndex = (dragPosPx / stepPx).roundToInt().coerceIn(0, chapters.lastIndex)
                        }
                        scrubbing = false
                        onJumpToIndex(previewIndex)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "大纲（长按拖动快速翻章）", modifier = Modifier.size(20.dp))
    }

    if (scrubbing) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 56.dp)
                .width(160.dp)
                .height(panelHeight)
                .tunedGlass(
                    backdrop = backdrop,
                    params = liveGlassParamsFor(target).copy(chromaticAberration = false),
                    cornerRadius = 16.dp,
                )
                .clip(RoundedCornerShape(16.dp)),
        ) {
            // Every chapter, always rendered (documents here run at most a few dozen chapters) -
            // offset the whole column so the row at dragPosPx sits under the center marker,
            // updating every frame the drag loop above writes a new value.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, (centerOffsetPx - dragPosPx).roundToInt()) },
            ) {
                chapters.forEachIndexed { index, chapter ->
                    val selected = index == previewIndex
                    Text(
                        "${index + 1}. ${chapter.subtitle.ifBlank { "(无副标题)" }}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = resolvedTextColor.copy(alpha = if (selected) 1f else 0.6f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(rowHeight)
                            .wrapContentHeight(Alignment.CenterVertically)
                            .padding(horizontal = 10.dp),
                    )
                }
            }
            // Center marker band - the row landing here is the one that'll be jumped to.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(rowHeight)
                    .background(resolvedTextColor.copy(alpha = 0.12f)),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BoxScope.TeleprompterOutlinePanel(
    backdrop: Backdrop,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    chapters: List<TeleprompterChapter>,
    onJump: (String) -> Unit,
    // Fix B / outline面板 "并入上一章" - null (e.g. from the read-only player screen) disables
    // long-press on every row instead of just hiding the option per-row.
    onMergeUp: ((String) -> Unit)? = null,
    // false lets a caller supply its own trigger button elsewhere (the player screen puts one
    // inside its control bar instead of a separately-floating FAB) - the panel itself, when
    // expanded, is unaffected either way.
    showTrigger: Boolean = true,
    // 2026-08-16 split - editor/player no longer share GlassTarget.Other, so this shared
    // composable can't hardcode either one; each caller passes its own.
    target: GlassTarget,
    modifier: Modifier = Modifier,
) {
    // Outside-tap-to-close scrim (2026-08-16, "除副标题大纲区域，其他任何区域再次点击可以关闭") -
    // transparent (no dimming was asked for, just tap capture), sits behind the drawer so it only
    // catches taps that land outside it; the drawer's own Box below swallows its own taps with a
    // no-op clickable so they don't fall through to this and immediately re-close what was just
    // tapped inside - same scrim+swallow pairing this file's other overlays (settings window,
    // background picker popup) already use for the identical "tap outside to dismiss" need.
    if (expanded) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = null, indication = null) { onExpandedChange(false) },
        )
    }

    // "横屏屏幕矮，现在的弹出面板挡住大半屏" - a right-docked, full-height sliding drawer instead
    // of a floating centered popup. Always docks to the trailing edge regardless of what
    // `modifier` the caller passes - that's only meaningful for the collapsed trigger FAB below,
    // a drawer's own position isn't something callers should be customizing per-screen.
    AnimatedVisibility(
        visible = expanded,
        modifier = Modifier.align(Alignment.CenterEnd),
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it }),
    ) {
        Box(
            // 35% cap keeps it from eating the whole screen on a narrow landscape phone; full
            // height (not the old 0.7f) is the actual point of switching to a drawer at all.
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.35f)
                .tunedGlass(
                    backdrop = backdrop,
                    // chromaticAberration off - confirmed on-device (not a guess, tested both
                    // ways): with it on, this specific panel (extreme 1:6 aspect ratio, edge-to-
                    // edge full height, no padding) rendered rainbow-static corruption around the
                    // header icons instead of the intended subtle color fringing. A corner-radius
                    // theory was tried first and ruled out - the shape's asymmetric corners
                    // (topEnd/bottomEnd kept slightly non-zero below, from that dead end) turned
                    // out unrelated. Every other panel this session (dialogs, color picker) uses
                    // full tuned params including chromaticAberration without this artifact.
                    params = liveGlassParamsFor(target).copy(chromaticAberration = false),
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 2.dp, bottomEnd = 2.dp, bottomStart = 16.dp),
                )
                // No-op - swallows taps anywhere in the drawer (including gaps between rows,
                // which a plain Box with no pointer input of its own would otherwise let fall
                // through to the outside-tap-to-close scrim behind it) so tapping inside the
                // drawer never immediately re-closes it.
                .clickable(interactionSource = null, indication = null) {},
        ) {
            Column(
                Modifier
                    .padding(8.dp)
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                // Close button removed (2026-08-16) - the trigger toggle + outside-tap-to-close
                // scrim (both above) cover dismissal now, no in-panel affordance needed.
                Text(
                    "大纲",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                LazyColumn {
                    itemsIndexed(chapters) { index, ch ->
                        var menuExpanded by remember(ch.id) { mutableStateOf(false) }
                        Box {
                            Text(
                                "${index + 1}. ${ch.subtitle.ifBlank { "(无副标题)" }}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { onJump(ch.id) },
                                        onLongClick = if (onMergeUp != null && index > 0) {
                                            { menuExpanded = true }
                                        } else {
                                            null
                                        },
                                    )
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                            )
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("并入上一章") },
                                    onClick = { menuExpanded = false; onMergeUp?.invoke(ch.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (!expanded && showTrigger) {
        SmallFloatingActionButton(onClick = { onExpandedChange(true) }, modifier = modifier) {
            Icon(Icons.Filled.List, contentDescription = "大纲")
        }
    }
}

/**
 * Floating glass window, not a Dialog/Popup/ModalBottomSheet - all three open a separate window
 * that physically cannot see this screen's real content (the capture mechanism only works within
 * one composition tree), so none of them could refract the document text behind while it's being
 * adjusted, which is the entire point (a flat single-color background has nothing to refract -
 * the same lesson as the launcher's original blank-glass bug). This is a plain Box overlay in the
 * SAME tree as [backdrop]'s capture source instead.
 *
 * Also the fix for the other half of this rewrite: no ModalBottomSheet means no built-in vertical
 * swipe-to-dismiss gesture competing with LiquidSlider's own drag - that conflict (confirmed by
 * A/B testing: the identical slider works fine outside a BottomSheet, e.g. the player's speed
 * slider) is now structurally gone, not "fixed" by making two gesture recognizers cooperate.
 *
 * Unlike a real Popup, a plain Box doesn't swallow touches outside its own bounds by itself - the
 * full-screen scrim below is what makes the document behind unreachable while this is open (and
 * closes the window on an outside tap); closing removes this composable and taps reach the
 * document again on their own, no extra bookkeeping needed.
 */
@Composable
private fun TeleprompterSettingsWindow(
    backdrop: Backdrop,
    settings: TeleprompterSettings,
    onUpdate: ((TeleprompterSettings) -> TeleprompterSettings) -> Unit,
    fontFamily: TeleprompterFontFamily,
    onFontFamilyChange: (TeleprompterFontFamily) -> Unit,
    editZoomScale: Float,
    onEditZoomScaleChange: (Float) -> Unit,
    subtitleStyle: TeleprompterSubtitleStyle,
    onSubtitleStyleChange: ((TeleprompterSubtitleStyle) -> TeleprompterSubtitleStyle) -> Unit,
    onOpenObserveLog: () -> Unit,
    onDismiss: () -> Unit,
    // 2026-08-16 - "视口裁剪"跟手修复（见 docs/PITFALLS.md「提词器正文滑块拖动卡顿」）：拖动这几个
    // 正文相关滑块时，屏幕外的章节不用跟着每次节流推送都重排，只有屏幕内看得见的才需要跟手。
    // 只有 fontSizeSp/overallScale/editZoomScale/lineHeightMultiplier/副标题字号这 5 个会触发全文
    // 重排的滑块需要接这两个回调；滚动速度/面板透明度/模糊度/内容宽度不接（内容宽度是共享一个
    // 外层 fillMaxWidth，本来就没法只裁剪部分章节，维持原样）。
    onBodyDragStart: () -> Unit = {},
    onBodyDragEnd: () -> Unit = {},
) {
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium)) }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f * entrance.value))
                .clickable(interactionSource = null, indication = null, onClick = onDismiss),
        )

        // contentBackdrop captures just this window's own scrollable content - separate from
        // [backdrop] (the real document behind the whole window) - so the progressive-blur fade
        // at the scroll edges fades THIS content scrolling under it, not the document further
        // back. Sibling of that scrollable Column, never its ancestor - same rule as everywhere
        // else on this screen.
        val contentBackdrop = rememberLayerBackdrop()
        val sheetScrollState = rememberScrollState()

        Column(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.92f)
                // 半屏悬浮 (2026-08-16 user request) - was 0.85f (near full-screen).
                .fillMaxHeight(0.5f)
                .graphicsLayer {
                    alpha = entrance.value
                    val s = 0.9f + 0.1f * entrance.value
                    scaleX = s
                    scaleY = s
                }
                // Swallows taps so they don't fall through to the dismiss-scrim behind it.
                .clickable(interactionSource = null, indication = null, onClick = {})
                .tunedGlass(
                    backdrop = backdrop,
                    // GlassTarget.TeleprompterSettingsWindow - its own tuning-panel target as of
                    // 2026-08-13 (齿轮→外观→玻璃效果调节), not the shared SettingsPanel baseline.
                    // See defaultGlassParamsFor's doc comment for why this target needs its own
                    // chromaticAberration value (confirmed via on-device A/B testing, not
                    // guessed): this window sits over real, high-contrast document text, not the
                    // solid/gradient backgrounds every other target was tuned against.
                    //
                    // blurRadiusDp/tint below come from settings.settingsPanelBlurRadiusDp/
                    // settingsPanelOpacity, NOT the tuning-panel's store - unlike the tuning
                    // panel (preview-only, see its own header comment), this pair is this
                    // session's real, live-apply, persisted control, wired directly into
                    // TeleprompterSettings alongside every other slider in this window.
                    //
                    // 2026-08-14: tint is now this target's primary readability mechanism, not
                    // blur - Apple-style layering (content -> glass material [blur+tint+shadow]
                    // -> controls), where tint STRENGTH is the knob that actually controls how
                    // busy/legible the background reads, blur is secondary. enabled=true
                    // unconditionally (not gated on opacity>0) since alpha=0 already reads as
                    // fully transparent on its own - no need for a second on/off switch. Follows
                    // LocalDarkTheme (the app's own dark-mode override, see AppTheme/SettingsPage),
                    // not isSystemInDarkTheme() - LiquidDialog/LiquidToggle/LiquidSlider still use
                    // the latter (a known gap flagged this session, not fixed here, out of scope -
                    // don't repeat it in this new path). No particular accent color to draw on, so
                    // a plain neutral: near-white in light theme, LiquidDialog's existing dark
                    // scrim tone in dark theme - not meant to be final, the user tunes it live via
                    // the slider below.
                    params = liveGlassParamsFor(GlassTarget.TeleprompterSettingsWindow).copy(
                        blurRadiusDp = settings.settingsPanelBlurRadiusDp,
                        tint = GlassTintParams(
                            enabled = true,
                            colorArgb = if (LocalDarkTheme.current) 0xFF15151AL else 0xFFF5F5F5L,
                            alpha = settings.settingsPanelOpacity,
                        ),
                    ),
                    cornerRadius = 28.dp,
                ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp, 16.dp, 12.dp, 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("设置", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                LiquidCompactIconButton(onClick = onDismiss, backdrop = backdrop, sampleBackdrop = false) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }

            Box(Modifier.weight(1f)) {
            Column(
                Modifier
                    .layerBackdrop(contentBackdrop)
                    .verticalScroll(sheetScrollState)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 20.dp),
            ) {
            // 面板整理成 5 个区域 (2026-08-16, "太乱了...整理得清晰明了") - 正文/副标题/播放页/
            // 当前面板液态效果/更多液态效果，用 HorizontalDivider 隔开。纯排版调整：每个控件
            // 绑定的 state/回调都原样保留，唯一的内容位移是"字体（整篇统一）"从原来夹在副标题和
            // 播放页之间，挪成正文区块的收尾项（它影响全文，跟正文这块最贴，不是新增了个"全局"
            // 区域）；另外把"玻璃"这个字眼全部换成"液态"（不改功能，只改这几处文案用词）。
            Text("正文", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = SectionTitleColor)
            Spacer(Modifier.height(8.dp))
            Text("正文默认字号 ${settings.fontSizeSp.toInt()}sp（只对没有明确字号的正文生效。从 Word 导入的内容通常自带字号，不受此项影响，请用『整体缩放』调整）")
            LiquidSlider(
                value = { settings.fontSizeSp },
                onValueChange = { v -> onBodyDragStart(); onUpdate { it.copy(fontSizeSp = v) } },
                onValueChangeFinished = onBodyDragEnd,
                backdrop = backdrop,
                valueRange = 16f..72f,
                // 2026-08-16 - 试过拖大 pushThrottleMs（10倍=2000ms）想减少重排碰撞，结果是拖动
                // 途中正文完全不跟着动、只有松手才跳一下 - 用户明确反馈这是错的方向："应该是随时
                // 跟着动才对，频率越高越对"。改回默认 200ms；真正的根治是配合调用方（
                // TeleprompterEditorScreen）的视口裁剪 - 屏幕外的章节推迟到松手再重排，只有屏幕内
                // 的实时跟手，见 onBodyDragStart/onBodyDragEnd 和 docs/PITFALLS.md「提词器正文滑块
                // 拖动卡顿」的更新记录。
            )

            // "导入文档偏小时整体放大，同时保留原文的大小对比关系" - unlike 默认字号 above, this
            // multiplies onto EVERY文字的字号, including Word 导入 run 自带的明确字号 (层叠解析后
            // 几乎全文都带明确字号，默认字号那个滑块单独对它们完全不生效 - 已用真实文档验证过)。
            // 只影响显示，不改存储/导出的字号。
            Text("整体缩放 x${"%.1f".format(settings.overallScale)}（对所有文字生效，含导入文档自带字号；只影响显示，不改导出）")
            LiquidSlider(
                value = { settings.overallScale },
                onValueChange = { v -> onBodyDragStart(); onUpdate { it.copy(overallScale = v) } },
                onValueChangeFinished = onBodyDragEnd,
                backdrop = backdrop,
                valueRange = 0.5f..3f,
            )

            Text("编辑时显示缩放 x${"%.1f".format(editZoomScale)}（只影响编辑页看着多大，不改真实字号）")
            LiquidSlider(
                value = { editZoomScale },
                onValueChange = { v -> onBodyDragStart(); onEditZoomScaleChange(v) },
                onValueChangeFinished = onBodyDragEnd,
                backdrop = backdrop,
                valueRange = 0.5f..2f,
            )

            Text("行距 x${"%.1f".format(settings.lineHeightMultiplier)}（对有明确行距的段落也生效，在原行距基础上再乘这个倍数）")
            LiquidSlider(
                value = { settings.lineHeightMultiplier },
                onValueChange = { v -> onBodyDragStart(); onUpdate { it.copy(lineHeightMultiplier = v) } },
                onValueChangeFinished = onBodyDragEnd,
                backdrop = backdrop,
                valueRange = 1f..2.5f,
            )

            // Portrait/landscape store separate values (横屏文字铺满整行时眼睛跟不上，需要单独收
            // 窄) - this slider only ever edits whichever one applies to how the phone is held
            // RIGHT NOW, so it must read live orientation, not a remembered snapshot.
            run {
                val configuration = LocalConfiguration.current
                val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                val widthFraction = settings.contentWidthFraction(isLandscape)
                Text(
                    "内容宽度（${if (isLandscape) "横屏" else "竖屏"}）${(widthFraction * 100).toInt()}%" +
                        "（查看/播放/编辑三处通用，横屏窄一点更跟得上滚动）",
                )
                LiquidSlider(
                    value = { widthFraction },
                    onValueChange = { v ->
                        onUpdate {
                            if (isLandscape) it.copy(contentWidthLandscape = v) else it.copy(contentWidthPortrait = v)
                        }
                    },
                    backdrop = backdrop,
                    valueRange = 0.4f..1f,
                )
            }

            Text("对齐方式")
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    TeleprompterTextAlignMode.FollowDocument to "跟随原文档",
                    TeleprompterTextAlignMode.Left to "左对齐",
                    TeleprompterTextAlignMode.Center to "居中",
                    TeleprompterTextAlignMode.Right to "右对齐",
                    TeleprompterTextAlignMode.Justify to "两端对齐",
                ).forEach { (mode, label) ->
                    val selected = settings.textAlignMode == mode
                    LiquidCompactButton(
                        onClick = { onUpdate { it.copy(textAlignMode = mode) } },
                        backdrop = backdrop,
                        active = selected,
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        sampleBackdrop = false,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("字体（整篇统一）")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                listOf(
                    TeleprompterFontFamily.SansSerif to "无衬线",
                    TeleprompterFontFamily.Serif to "衬线",
                ).forEach { (family, label) ->
                    val selected = fontFamily == family
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LiquidCompactIconButton(
                            onClick = { onFontFamilyChange(family) },
                            backdrop = backdrop,
                            size = 44.dp,
                            active = selected,
                            sampleBackdrop = false,
                        ) {
                            Text("文", fontFamily = family.toComposeFontFamily(), fontSize = 20.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))

            // 路线B：副标题是纯文本 BasicTextField，工具栏的逐字格式命令对它没有作用对象 - 唯一
            // 能控制它长相的方式就是这组文档级样式，渲染时整体套用，不做逐字格式。
            Text("副标题样式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = SectionTitleColor)
            Spacer(Modifier.height(8.dp))
            Text("字号 ${subtitleStyle.fontSizeSp.toInt()}sp", style = MaterialTheme.typography.labelMedium)
            LiquidSlider(
                value = { subtitleStyle.fontSizeSp },
                onValueChange = { v -> onBodyDragStart(); onSubtitleStyleChange { it.copy(fontSizeSp = v) } },
                onValueChangeFinished = onBodyDragEnd,
                backdrop = backdrop,
                valueRange = 12f..72f,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("加粗", modifier = Modifier.weight(1f))
                LiquidToggle(
                    selected = { subtitleStyle.bold },
                    onSelect = { checked -> onSubtitleStyleChange { it.copy(bold = checked) } },
                    backdrop = backdrop,
                )
            }
            Spacer(Modifier.height(8.dp))
            var subtitleColorPickerOpen by remember { mutableStateOf(false) }
            val subtitleExplicitColor = subtitleStyle.colorArgb?.let { Color(it.toInt()) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LiquidCompactIconButton(
                    onClick = { subtitleColorPickerOpen = true },
                    backdrop = backdrop,
                    size = 36.dp,
                    sampleBackdrop = false,
                ) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .let {
                                if (subtitleExplicitColor != null) {
                                    it.background(subtitleExplicitColor)
                                } else {
                                    // No explicit color set - shown as an empty ring instead of a
                                    // solid swatch so "跟随正文颜色" doesn't look identical to "黑色".
                                    it.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                }
                            },
                    )
                }
                Text(
                    if (subtitleExplicitColor != null) "颜色" else "颜色（跟随正文）",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (subtitleColorPickerOpen) {
                TeleprompterColorPickerPopup(
                    title = "副标题颜色",
                    presets = PlayerTextPresets,
                    current = subtitleExplicitColor,
                    allowClear = true,
                    target = GlassTarget.TeleprompterEditor,
                    onDismiss = { subtitleColorPickerOpen = false },
                    onPick = { c ->
                        onSubtitleStyleChange {
                            it.copy(colorArgb = c?.let { color -> color.toArgb().toLong() and 0xFFFFFFFFL })
                        }
                        subtitleColorPickerOpen = false
                    },
                )
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))

            Text("播放页", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = SectionTitleColor)
            Spacer(Modifier.height(8.dp))
            Text("滚动速度 x${"%.1f".format(settings.scrollSpeed)}")
            LiquidSlider(
                value = { settings.scrollSpeed },
                onValueChange = { v -> onUpdate { it.copy(scrollSpeed = v) } },
                backdrop = backdrop,
                valueRange = 0.2f..3f,
            )

            Spacer(Modifier.height(12.dp))
            Text("播放页配色")
            Spacer(Modifier.height(8.dp))
            var bgPickerOpen by remember { mutableStateOf(false) }
            var playerTextPickerOpen by remember { mutableStateOf(false) }
            val playerBgColor = Color(settings.backgroundColorArgb.toInt())
            val playerTextColor = Color(settings.textColorArgb.toInt())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LiquidCompactIconButton(onClick = { bgPickerOpen = true }, backdrop = backdrop, size = 44.dp, sampleBackdrop = false) {
                        Box(Modifier.size(34.dp).clip(CircleShape).background(playerBgColor))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("背景", style = MaterialTheme.typography.labelSmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LiquidCompactIconButton(onClick = { playerTextPickerOpen = true }, backdrop = backdrop, size = 44.dp, sampleBackdrop = false) {
                        Box(Modifier.size(34.dp).clip(CircleShape).background(playerTextColor))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("文字", style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.weight(1f))
                //撞色保护：不管背景/文字被设成什么样（包括这次事故的白底白字），点这个总能救回来。
                LiquidCompactButton(
                    onClick = { onUpdate { it.copy(backgroundColorArgb = 0xFF000000, textColorArgb = 0xFFFFFFFF) } },
                    backdrop = backdrop,
                    sampleBackdrop = false,
                ) { Text("恢复默认配色") }
            }
            if (bgPickerOpen) {
                TeleprompterColorPickerPopup(
                    title = "播放页背景色",
                    presets = PlayerBackgroundPresets,
                    current = playerBgColor,
                    contrastAgainst = playerTextColor,
                    contrastAgainstLabel = "文字颜色",
                    target = GlassTarget.TeleprompterPlayer,
                    onDismiss = { bgPickerOpen = false },
                    onPick = { c ->
                        c?.let { color -> onUpdate { it.copy(backgroundColorArgb = color.toArgb().toLong() and 0xFFFFFFFFL) } }
                        bgPickerOpen = false
                    },
                )
            }
            if (playerTextPickerOpen) {
                TeleprompterColorPickerPopup(
                    title = "播放页文字色",
                    presets = PlayerTextPresets,
                    current = playerTextColor,
                    contrastAgainst = playerBgColor,
                    contrastAgainstLabel = "背景色",
                    target = GlassTarget.TeleprompterPlayer,
                    onDismiss = { playerTextPickerOpen = false },
                    onPick = { c ->
                        c?.let { color -> onUpdate { it.copy(textColorArgb = color.toArgb().toLong() and 0xFFFFFFFFL) } }
                        playerTextPickerOpen = false
                    },
                )
            }

            // 2026-08-16 - 播放页自己没有独立的顶栏/设置入口，它的液态效果调节因此放在这里
            // （编辑页设置面板的"播放页"分节里），而不是新开一个屏幕。说明文字是用户明确要求的，
            // 避免"在编辑页设置面板里改的东西，实际是播放页的"造成困惑。
            Spacer(Modifier.height(12.dp))
            Text(
                "下面这个入口虽然在编辑页设置面板里，调的是播放页自己的液态效果，跟上面编辑页的独立开",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            val playerGlassTuningContext = LocalContext.current
            LiquidCompactButton(
                onClick = {
                    playerGlassTuningContext.startActivity(
                        GlassTuningActivity.restrictedIntent(playerGlassTuningContext, TeleprompterPlayerGlassTargets),
                    )
                },
                backdrop = backdrop,
                sampleBackdrop = false,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("更多液态效果参数 / 预设（播放页）", modifier = Modifier.weight(1f))
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))

            Text("当前面板液态效果", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = SectionTitleColor)
            Spacer(Modifier.height(8.dp))
            Text(
                "透明度 ${(settings.settingsPanelOpacity * 100).toInt()}%（白底叠加强度，越高越不透明）",
                style = MaterialTheme.typography.labelMedium,
            )
            LiquidSlider(
                value = { settings.settingsPanelOpacity },
                onValueChange = { v -> onUpdate { it.copy(settingsPanelOpacity = v) } },
                backdrop = backdrop,
                valueRange = 0f..1f,
            )
            Text(
                "模糊度 ${settings.settingsPanelBlurRadiusDp.toInt()}dp（越高背后正文越糊，但跟面板自己文字重叠时更好认）",
                style = MaterialTheme.typography.labelMedium,
            )
            LiquidSlider(
                value = { settings.settingsPanelBlurRadiusDp },
                onValueChange = { v -> onUpdate { it.copy(settingsPanelBlurRadiusDp = v) } },
                backdrop = backdrop,
                valueRange = 0f..30f,
            )

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))

            Text("更多液态效果", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = SectionTitleColor)
            Spacer(Modifier.height(8.dp))
            // Full tuning panel (模糊/折射/高光/阴影/染色 等) + 预设 save/apply, restricted to
            // this screen's own targets (TeleprompterEditorGlassTargets) - "透明度/模糊度"这两个
            // 滑块是最常用的两个参数的快捷方式，不是这个面板的替代品。2026-08-16: no longer covers
            // 播放页 (see the separate button under "播放页" above) - 目录/编辑页/播放页 each have
            // their own independent target now, this one is editor-only.
            val glassTuningContext = LocalContext.current
            LiquidCompactButton(
                onClick = {
                    glassTuningContext.startActivity(
                        GlassTuningActivity.restrictedIntent(glassTuningContext, TeleprompterEditorGlassTargets),
                    )
                },
                backdrop = backdrop,
                sampleBackdrop = false,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("更多液态效果参数 / 预设", modifier = Modifier.weight(1f))
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            // adb 不可用时的排查手段 - 应用内查看 TeleprompterObserveLog 的实时诊断日志。
            LiquidCompactButton(
                onClick = onOpenObserveLog,
                backdrop = backdrop,
                contentPadding = PaddingValues(vertical = 12.dp),
                modifier = Modifier.fillMaxWidth(),
                sampleBackdrop = false,
            ) {
                Text("查看诊断日志", modifier = Modifier.weight(1f))
                Text(
                    "${TeleprompterObserveLog.entries.size} 条",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            }

            // Progressive-blur fade at the scroll edges - only shown once there's actually
            // something to scroll under it, so a short settings sheet doesn't get a pointless
            // permanent blur strip.
            if (sheetScrollState.canScrollBackward) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(24.dp)
                        .drawBackdrop(
                            backdrop = contentBackdrop,
                            shape = { RectangleShape },
                            effects = { progressiveBlur(12.dp.toPx(), fadeStart = 0f, fadeEnd = 1f) },
                        ),
                )
            }
            if (sheetScrollState.canScrollForward) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(24.dp)
                        .drawBackdrop(
                            backdrop = contentBackdrop,
                            shape = { RectangleShape },
                            effects = { progressiveBlur(12.dp.toPx(), fadeStart = 1f, fadeEnd = 0f) },
                        ),
                )
            }
            }
        }
    }
}
