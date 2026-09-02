package li.songe.gkd.teleprompter

import kotlinx.serialization.Serializable
import li.songe.gkd.glassui.GlassBackgroundConfig
import java.util.UUID

// 正文默认字号/整体缩放/行距/对齐方式/内容宽度这五项，**每篇文档各存一份自己的**。
//
// 2026-08-26 - 去掉了"全局值 + 每篇可选覆盖"这个两层模型。用户判断："一篇文档就该有一篇文档的
// 设置，所有文档共用一套全局值这个概念本身就多余"，于是设置面板里那五个"跟随全局/这篇文档单独
// 设置"开关一并删掉了——它们本来就只是为了弥补这个多余概念而存在的。
//
// 字段为什么还是可空（不是必填）：**只为了老数据能原样解码**。2026-08-25~26 之间存过一批
// displayOverrides 缺字段的库文件（那时 null 的含义是"跟随全局"），改成必填会让 kotlinx 在缺
// 字段时直接落到数据类默认值，把用户当时的实际显示效果冲掉。所以保留可空 + 一次性迁移
// （migrateToPerDocumentDisplaySettings）：迁移把每个 null 填成当时全局设置里的对应值，此后
// **正常运行期间这六个字段恒不为 null**。新建/导入文档一律用 newDocumentDisplaySettings() 种进
// 完整的一份，不会再产生 null。
//
// 也就是说：读取侧不该再把 null 理解成"跟随全局"，它只是"这份数据比迁移还老"的残留信号，
// effectiveSettings() 里那层 ?: 现在纯粹是兜底，正常路径上取不到。
@Serializable
data class TeleprompterDisplayOverrides(
    val fontSizeSp: Float? = null,
    val overallScale: Float? = null,
    val lineHeightMultiplier: Float? = null,
    val textAlignMode: TeleprompterTextAlignMode? = null,
    val contentWidthPortrait: Float? = null,
    val contentWidthLandscape: Float? = null,
)

@Serializable
data class TeleprompterChapter(
    val id: String = UUID.randomUUID().toString(),
    val subtitle: String = "新章节",
    val contentHtml: String = "",
    // Bumped only by structural ops (merge/split - see TeleprompterVm) that overwrite
    // contentHtml for an EXISTING id from outside the editor's own typing flow. ChapterItem's
    // RichTextState is hydrated once per (id, structureVersion) - keying on id alone would never
    // re-hydrate when a merge/split rewrites this same chapter's content, since normal typing
    // deliberately does NOT bump this (it would reset the cursor on every debounced autosave).
    val structureVersion: Int = 0,
)

@Serializable
enum class TeleprompterFontFamily {
    // Monospace was tried and dropped: on-device comparison showed it renders pixel-identical
    // to SansSerif for both CJK and Latin glyphs (fake choice), while Serif is genuinely distinct.
    SansSerif, Serif,
}

@Serializable
enum class TeleprompterOrientation {
    System, Portrait, Landscape,
}

@Serializable
enum class TeleprompterTextAlignMode {
    // Uses whatever alignment each paragraph carries from the source document (or left/start for
    // paragraphs with none) - the default, since forcing an alignment is a deliberate override.
    FollowDocument, Left, Center, Right, Justify,
}

// Subtitles are a plain BasicTextField (never RichTextState), so the format toolbar's
// toggleSpanStyle literally has nothing to act on there - the only way to control how a
// subtitle looks is a document-level style applied uniformly at render time, same mechanism
// as overallScale/lineHeightMultiplier in TeleprompterTextScaling.kt (never written into the
// subtitle's own stored text).
@Serializable
data class TeleprompterSubtitleStyle(
    val fontSizeSp: Float = 24f,
    val bold: Boolean = true,
    // null = inherit whatever the surrounding context's normal text color is (editor: default
    // onSurface/black; player: settings.textColorArgb) - deliberately NOT a hardcoded default
    // color. A fixed default here would look fine in the editor's always-white background but
    // could go invisible against a dark player background (this project already hit exactly that
    // "white-on-white" class of bug once with the player's own background/text color pair).
    val colorArgb: Long? = null,
)

@Serializable
data class TeleprompterDocument(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "未命名文档",
    val chapters: List<TeleprompterChapter> = listOf(TeleprompterChapter()),
    // per-document, not global: RichTextState can't round-trip fontFamily through toHtml/setHtml
    // (verified empty in the library's own CssEncoder/CssDecoder), so it lives here instead of
    // as a per-character SpanStyle and is applied as the editor/player base TextStyle. Also lets
    // Word-import set it per-document from the dominant font found in the source docx.
    val fontFamily: TeleprompterFontFamily = TeleprompterFontFamily.SansSerif,
    // 2026-08-28 - editZoomScale（"编辑时显示缩放"）已删除。它是一个**只在编辑模式生效的排版
    // 参数**，直接违反"三页走同一条文本布局路径"：同一篇文档在编辑页和阅读页会按不同字号重新
    // 断行，行首行尾对不上。删字段 + 迁移清键，见 migrateToPerDocumentDisplaySettings() 的 v2 步骤。
    // 想整体调大小请用 overallScale（"整体缩放"，三页统一生效）。
    //
    // viewZoomScale 是查看态双指捏合的临时缩放，保留。
    val viewZoomScale: Float = 1f,
    // Player-screen-only, restored to System (follow-device) the moment the player screen is
    // left - see TeleprompterPlayerScreen's DisposableEffect. Per-document per spec ("记在文档
    // 设置里"), since different scripts may be read in different physical rig orientations.
    val orientation: TeleprompterOrientation = TeleprompterOrientation.System,
    // Document-level, not global - a Word import seeds this from whatever the source doc's own
    // heading paragraphs actually looked like (see TeleprompterWordParser), so different
    // documents keep their own original heading look instead of all sharing one global default.
    val subtitleStyle: TeleprompterSubtitleStyle = TeleprompterSubtitleStyle(),
    // 这篇文档自己的显示设置（五项）。见 TeleprompterDisplayOverrides 自己的文档注释——正常运行
    // 期间它的字段恒不为 null；这里的无参默认值只在"迁移前的老数据"和单元测试里出现，真正新建
    // 文档走的是 TeleprompterVm.createDocument()，它会用 newDocumentDisplaySettings() 种满。
    val displayOverrides: TeleprompterDisplayOverrides = TeleprompterDisplayOverrides(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class TeleprompterSettings(
    // ── 下面这六个字段是「新文档默认值」，不是全局设置 ────────────────────────────────
    //
    // 2026-08-26 - 语义变更（用户拍板去掉全局层）。它们**只在新建文档/ 导入文档的那一刻被读取
    // 一次**，用来给那篇文档种一份属于它自己的初始值（见 newDocumentDisplaySettings()）。之后
    // 那篇文档完全独立：改这里不会影响任何已存在的文档，改文档也不会回写到这里。
    //
    // 所以千万不要在读取渲染值的路径上直接用这几个字段——渲染一律走
    // TeleprompterDocument.effectiveSettings()。本文件里的 migrateToPerDocumentDisplaySettings()
    // 就是把它们旧的"全局值"身份一次性转成每篇文档自己的值。
    //
    // 默认值取"中性基线"（用户 2026-08-26 指定：倍数一律 1、字号取最小），让新文档从不加任何
    // 修饰的原样开始，而不是继承上一篇文档碰巧调成什么样。
    //
    // 这个类里其余字段（scrollSpeed / 颜色 / mirrored / 面板玻璃参数等）**仍然是真正的全局设置**，
    // 全部文档共用，不参与这次改造。
    //
    // "默认字号" - 只影响没有自带显式字号的文字（手打的新内容，或没有层叠出字号的导入内容）；
    // Word 导入的内容几乎每个 run 都自带字号（真实文档覆盖率核查：94% 的字符），要整体放大缩小
    // 得用 overallScale。取滑块下限 16sp。
    val fontSizeSp: Float = 16f,
    // "整体缩放" - 乘在基础 TextStyle 的字号上，只影响**没有自带显式字号**的文字。纯显示，不写进
    // contentHtml，导出永远用真实的层叠字号。
    //
    // 2026-08-28 - 作用范围缩小了。改造前它还会通过 scaledForDisplay 把倍数乘到 Word 导入自带的
    // 逐 run 显式字号上（"对所有文字生效"）；那个变换为了三页断行一致已整体删除，见
    // TeleprompterTextScaling.kt 的文件头。所以现在导入内容里带 <w:sz> 的部分不受此项影响——
    // 沉香话术实测 1736 个 run 里 1262 个自带字号。设置面板的小字已如实改成"不影响 Word 导入时
    // 自带字号的文字"。计划中的导入侧字号归一化做完后可恢复全覆盖。
    val overallScale: Float = 1f,
    // 行距倍数，乘在基础 TextStyle.lineHeight 上，只影响**没有自带显式行距**的段落。
    //
    // 2026-08-28 - 同 overallScale，作用范围缩小了：改造前 scaledForDisplay 会把倍数再乘到 Word
    // 导入段落自带的 w:line 行距上，现在不会了，那些段落保持源文档的行距。沉香话术实测 330 段里
    // 221 段自带行距。
    //
    // 新文档默认 1.0 = 不加任何行距修饰，交给系统按每行实际字形度量排版——这是唯一逐行精确的状态
    // （倍数为 1 时根本不设 lineHeight）。
    val lineHeightMultiplier: Float = 1f,
    // Display-only alignment override for read-only rendering (player + editor preview) -
    // FollowDocument leaves each paragraph's own cascaded alignment (or the default) untouched;
    // any other value force-overrides every paragraph, same throwaway-copy mechanism as
    // overallScale/lineHeightMultiplier above.
    val textAlignMode: TeleprompterTextAlignMode = TeleprompterTextAlignMode.FollowDocument,
    // multiplier against a fixed baseline scroll px/frame
    val scrollSpeed: Float = 1f,
    // "内容宽度" - fraction of the available width the reading column actually uses (the rest is
    // empty margin, centered). Landscape defaults far narrower than portrait: a landscape screen
    // is short and wide, so a full-width line is long enough that the eye can't track it fast
    // enough to keep up with the scroll ("横屏文字铺满整行，眼睛横扫距离过大，提词器跟不上") -
    // narrowing the column is the fix, not slowing the scroll down. Kept as two independent
    // values (not one shared one) because the right width for each orientation is unrelated - a
    // portrait phone in one hand already reads fine at 100%.
    //
    // 2026-08-26 - 新文档默认值取"倍数一律 1"时，竖屏本来就是 1（100%），横屏这里**故意保留
    // 0.65 没有改成 1**：0.65 不是随手设的数，是上面那条"横屏铺满整行眼睛跟不上滚动"的结论，
    // 改成 1 等于把那个已验证的判断推翻。如果确实要横屏也从 100% 起步，改这一个数即可。
    val contentWidthPortrait: Float = 1f,
    val contentWidthLandscape: Float = 0.65f,
    // White-on-black flipped to black-on-white 2026-08-15 per user request - just the default,
    // both still fully user-adjustable via the color pickers.
    val backgroundColorArgb: Long = 0xFFFFFFFF,
    val textColorArgb: Long = 0xFF000000,
    val mirrored: Boolean = false,
    // One-time first-launch tutorial for the player screen's tap zones - "设置里留一个「再看一次
    // 操作说明」的入口" re-shows it without touching this flag.
    val hasSeenPlayerOnboarding: Boolean = false,
    // Settings window's own glass look (2026-08-13/14) - separate from the dev-only glass tuning
    // panel (齿轮→外观→玻璃效果调节), which only ever adjusts a preview and never applied to the
    // real screen. These two live-apply immediately and persist through the same store as every
    // other setting here. blurRadiusDp default matches the outline drawer's look (0, no extra
    // blur). settingsPanelOpacity's default is nonzero (2026-08-14: tint, not blur, is this
    // target's real readability knob now - see the tunedGlass() call site's own comment) - not
    // "no visible change on upgrade" like blurRadiusDp, an intentional new baseline look.
    val settingsPanelBlurRadiusDp: Float = 0f,
    // A neutral overlay's alpha on top of the panel's blur/lens - 0% = fully transparent (no
    // overlay), 100% = fully opaque (solid color panel). Default sits at "noticeable but doesn't
    // cover the panel's own text" per on-device judgment call, not a precise/final number - the
    // user tunes this live. See TeleprompterSettingsWindow's tunedGlass() call for the color/alpha
    // mapping.
    val settingsPanelOpacity: Float = 0.18f,
)

// 把"这篇文档自己的五项"和"其余真正全局的字段"拼成一个完整的 TeleprompterSettings，是渲染侧读这
// 五项的**唯一**入口。编辑页/播放页两处绑定 settings 的地方都只调用这一个函数，不在各自的读取
// 路径上各写一遍取值判断——重复写两遍、以后改一处漏了另一处，是这个项目真实出过 bug 的那类疏漏
// （见 docs/PITFALLS.md）。
//
// 返回完整对象的好处不变：scrollSpeed / backgroundColorArgb / settingsPanelOpacity 这些仍然全局
// 的字段原样从 [global] 透传，调用方那一大串 settings.xxx 读取代码不用感知这层拼装。
//
// 2026-08-26 - 那六个 ?: 现在是**兜底，不是回退语义**。去掉全局层之后每篇文档都有自己完整的一份
// 值（迁移 + 新建/导入种值双重保证），正常路径上左边永远非 null。留着 ?: 只是不想让一份没迁移到
// 的老数据直接 NPE 或显示成 0——真要走到右边，说明数据比 migrateToPerDocumentDisplaySettings()
// 还老，取新文档默认值是这时候最合理的表现。
fun TeleprompterDocument.effectiveSettings(global: TeleprompterSettings): TeleprompterSettings = global.copy(
    fontSizeSp = displayOverrides.fontSizeSp ?: global.fontSizeSp,
    overallScale = displayOverrides.overallScale ?: global.overallScale,
    lineHeightMultiplier = displayOverrides.lineHeightMultiplier ?: global.lineHeightMultiplier,
    textAlignMode = displayOverrides.textAlignMode ?: global.textAlignMode,
    contentWidthPortrait = displayOverrides.contentWidthPortrait ?: global.contentWidthPortrait,
    contentWidthLandscape = displayOverrides.contentWidthLandscape ?: global.contentWidthLandscape,
)

// 新建/导入文档时用来种一份完整的初始值。**只在创建那一刻读一次**——这是"新文档默认值"和被删掉
// 的"全局设置"最本质的区别：创建完成后这篇文档跟这里再无关系。
fun TeleprompterSettings.newDocumentDisplaySettings() = TeleprompterDisplayOverrides(
    fontSizeSp = fontSizeSp,
    overallScale = overallScale,
    lineHeightMultiplier = lineHeightMultiplier,
    textAlignMode = textAlignMode,
    contentWidthPortrait = contentWidthPortrait,
    contentWidthLandscape = contentWidthLandscape,
)

/** 逐字段取自己的值，缺的那些用 [base] 补齐。docx 导入用：自定义部件里通常只带了一部分字段，
 *  剩下的该落到新文档默认值上，而不是留成 null。 */
fun TeleprompterDisplayOverrides.withFallback(base: TeleprompterDisplayOverrides) = TeleprompterDisplayOverrides(
    fontSizeSp = fontSizeSp ?: base.fontSizeSp,
    overallScale = overallScale ?: base.overallScale,
    lineHeightMultiplier = lineHeightMultiplier ?: base.lineHeightMultiplier,
    textAlignMode = textAlignMode ?: base.textAlignMode,
    contentWidthPortrait = contentWidthPortrait ?: base.contentWidthPortrait,
    contentWidthLandscape = contentWidthLandscape ?: base.contentWidthLandscape,
)

/** 迁移到"五项每篇文档独立"之后的库结构版本号。 */
const val TELEPROMPTER_SCHEMA_PER_DOCUMENT_DISPLAY = 1

/** 删除 editZoomScale 之后的库结构版本号。 */
const val TELEPROMPTER_SCHEMA_NO_EDIT_ZOOM = 2

/** 当前库结构版本号。 */
const val TELEPROMPTER_SCHEMA_CURRENT = TELEPROMPTER_SCHEMA_NO_EDIT_ZOOM

/**
 * 库结构迁移总入口，按版本号顺序叠加执行。名字沿用第一次迁移时的叫法，没改——它已经被
 * TeleprompterStore 引用，改名只是给自己找事。
 *
 * 加新迁移步骤的规矩：新写一个 `migrateVn...()` 私有函数，自己判断 `schemaVersion >= n` 就原样
 * 返回，然后挂到这里的链尾；不要往已有步骤里塞东西（已经跑过的设备不会再跑那一步）。
 */
fun TeleprompterLibrary.migrateToPerDocumentDisplaySettings(): TeleprompterLibrary {
    if (schemaVersion >= TELEPROMPTER_SCHEMA_CURRENT) return this
    return migrateV1PerDocumentDisplay().migrateV2DropEditZoomScale()
}

/**
 * v2（2026-08-28）：清掉库文件里所有文档的 `editZoomScale` 键。
 *
 * **这一步没有逐字段的清除代码，是故意的**，而且必须理解它为什么仍然有效：`editZoomScale` 已经从
 * [TeleprompterDocument] 里删掉了，而 `Json { ignoreUnknownKeys = true }`（见 util/Singleton.kt）
 * 会在解码时直接忽略这个已不存在的键——也就是说**内存里的对象从一开始就不含它**。所以真正把键从
 * 磁盘上抹掉的动作是"重新编码并写盘"，这里要做的只是①让对象发生变化（改 schemaVersion）以便触发
 * 写盘，②在 store 里显式调 `persistNow()`。
 *
 * 换句话说：**`ignoreUnknownKeys` 保证了"不会因为老数据里有这个键而崩"（验收 C），而"键真的从
 * 文件里消失"（验收 B）靠的是重新落盘。这是两件事，C 过了不代表 B 过了**——2026-08-26 那次
 * "日志说迁了、磁盘没迁"就是栽在把这两件事当成一件（见 PITFALLS.md）。
 */
private fun TeleprompterLibrary.migrateV2DropEditZoomScale(): TeleprompterLibrary {
    if (schemaVersion >= TELEPROMPTER_SCHEMA_NO_EDIT_ZOOM) return this
    return copy(schemaVersion = TELEPROMPTER_SCHEMA_NO_EDIT_ZOOM)
}

/**
 * v1：把旧的全局五项值原样写进每一篇文档自己的设置里，使**迁移前后所有文档的显示效果完全一致**。
 *
 * 这一步做不对，用户此前调过的显示设置会全部丢失、回落到出厂默认，所以：
 * - 已经有自己的值的文档（旧模型里"打开了单独设置开关"的那些）保留它自己的值，不被全局值覆盖
 *   —— 逐字段判断，不是整份对象二选一（一篇文档完全可能只有 fontSizeSp 一项是自己的）。
 * - 不碰 updatedAt：迁移不是用户编辑，不该让文档在"最近修改"里跳位置。
 *
 * 迁移完成后顺手把 settings 里那五项重置成中性的新文档默认值：它们的身份已经从"全局设置"变成
 * "新文档默认值"，继续留着用户当初随手调出来的数（甚至是测试残留值）当新文档起点没有道理。
 * 其余真正全局的字段（scrollSpeed / 颜色 / 面板玻璃参数）原样保留。
 */
private fun TeleprompterLibrary.migrateV1PerDocumentDisplay(): TeleprompterLibrary {
    if (schemaVersion >= TELEPROMPTER_SCHEMA_PER_DOCUMENT_DISPLAY) return this
    val old = settings
    val migratedDocuments = documents.map { doc ->
        val own = doc.displayOverrides
        doc.copy(
            displayOverrides = TeleprompterDisplayOverrides(
                fontSizeSp = own.fontSizeSp ?: old.fontSizeSp,
                overallScale = own.overallScale ?: old.overallScale,
                lineHeightMultiplier = own.lineHeightMultiplier ?: old.lineHeightMultiplier,
                textAlignMode = own.textAlignMode ?: old.textAlignMode,
                contentWidthPortrait = own.contentWidthPortrait ?: old.contentWidthPortrait,
                contentWidthLandscape = own.contentWidthLandscape ?: old.contentWidthLandscape,
            ),
        )
    }
    val neutral = TeleprompterSettings()
    return copy(
        documents = migratedDocuments,
        settings = settings.copy(
            fontSizeSp = neutral.fontSizeSp,
            overallScale = neutral.overallScale,
            lineHeightMultiplier = neutral.lineHeightMultiplier,
            textAlignMode = neutral.textAlignMode,
            contentWidthPortrait = neutral.contentWidthPortrait,
            contentWidthLandscape = neutral.contentWidthLandscape,
        ),
        schemaVersion = TELEPROMPTER_SCHEMA_PER_DOCUMENT_DISPLAY,
    )
}

@Serializable
data class TeleprompterLibrary(
    val documents: List<TeleprompterDocument> = emptyList(),
    val settings: TeleprompterSettings = TeleprompterSettings(),
    // GlassBgMode/GlassBackgroundConfig (glassui/GlassBackground.kt) - promoted out of this file
    // 2026-08-16 once the launcher screen needed the identical "4 modes + image adjustment" system
    // this originally was (TeleprompterLibraryBgMode/TeleprompterLibraryBackground); the on-disk
    // field name here is unchanged, so existing saved libraries keep decoding fine.
    val libraryBackground: GlassBackgroundConfig = GlassBackgroundConfig(),
    // 库结构版本号。见 migrateToPerDocumentDisplaySettings()。
    //   0 = 2026-08-26 之前的"全局值 + 每篇可选覆盖"两层模型（老文件里没有这个键，解码落到 0）
    //   1 = 五项显示设置每篇文档独立
    //   2 = 删除 editZoomScale
    val schemaVersion: Int = 0,
)
