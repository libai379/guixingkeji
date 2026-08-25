package li.songe.gkd.teleprompter

import kotlinx.serialization.Serializable
import li.songe.gkd.glassui.GlassBackgroundConfig
import java.util.UUID

// 2026-08-25 - 补齐架构缺口：正文默认字号/整体缩放/行距/对齐方式/内容宽度这五项，此前只存在
// TeleprompterSettings（库级、全部文档共用一份）里，没有"这一篇文档自己的样子"这个概念。这在
// docx 导出携带文档设置这个需求下会直接造成数据污染——如果不做这层，导入一份别人发的、字号行距
// 不一样的 docx，改的会是全局设置，库里其余所有文档会跟着一起变样，而不是只有这一篇文档变。
//
// 全部字段可空：null = 没有覆盖，跟随全局 TeleprompterSettings 里对应的值。有值 = 这篇文档自己的
// 设定，忽略全局值。
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
    // Display-only multipliers on top of settings.fontSizeSp - never written into contentHtml or
    // read by the exporter, so the stored/exported font size is always the real configured one.
    // Edit mode actually re-lays-out text at the scaled size (so zooming out fits more on
    // screen); view mode's is a pure visual transform driven by pinch (see TeleprompterEditorScreen).
    val editZoomScale: Float = 0.7f,
    val viewZoomScale: Float = 1f,
    // Player-screen-only, restored to System (follow-device) the moment the player screen is
    // left - see TeleprompterPlayerScreen's DisposableEffect. Per-document per spec ("记在文档
    // 设置里"), since different scripts may be read in different physical rig orientations.
    val orientation: TeleprompterOrientation = TeleprompterOrientation.System,
    // Document-level, not global - a Word import seeds this from whatever the source doc's own
    // heading paragraphs actually looked like (see TeleprompterWordParser), so different
    // documents keep their own original heading look instead of all sharing one global default.
    val subtitleStyle: TeleprompterSubtitleStyle = TeleprompterSubtitleStyle(),
    // 2026-08-25 - 这篇文档自己覆盖的显示设置，默认全部 null（不覆盖，跟随全局）。见
    // TeleprompterDisplayOverrides 自己的文档注释。
    val displayOverrides: TeleprompterDisplayOverrides = TeleprompterDisplayOverrides(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class TeleprompterSettings(
    // "默认字号" - player + editor-preview DEFAULT font size, sp. Only affects text with no
    // explicit fontSize of its own (freshly typed text, or an import with no cascaded size) -
    // see overallScale below for the multiplier that actually reaches Word-imported text, which
    // after the OOXML cascade rewrite virtually always carries an explicit per-run fontSize
    // (confirmed via a real-document coverage check: 94% of characters). Kept as the real
    // stored/exported size regardless of overallScale - see that field's own doc.
    val fontSizeSp: Float = 32f,
    // "整体缩放" - multiplies EVERY rendered font size, including explicit per-run sizes cascaded
    // in from Word import, so the big/small contrast within a document (title vs body) is
    // preserved while the whole thing scales up or down together. Display-only: applied as a
    // rescale of a throwaway copy of the AnnotatedString at render time (see
    // TeleprompterTextScaling.kt's scaledForDisplay) - never written into contentHtml, so Word
    // export always uses the real cascaded size regardless of this setting.
    val overallScale: Float = 1f,
    // Multiplies line height. For text with no explicit per-paragraph line spacing this is the
    // whole story (baked directly into the base TextStyle.lineHeight). For an imported paragraph
    // that DOES carry its own explicit spacing (w:line, cascaded to an Em-relative ParagraphStyle
    // - see TeleprompterWordParser), this is applied as a further multiplier on top of that
    // paragraph's own ratio (same "multiply, don't override" model as overallScale above) rather
    // than being silently ignored.
    val lineHeightMultiplier: Float = 1.6f,
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

// 2026-08-25 - 五项"文档级可覆盖、未设置跟随全局"字段的**唯一**回退合并点。编辑页/播放页两处
// 绑定 settings 的地方都只调用这一个函数，不在各自的读取路径上各写一遍"文档级有值用文档级，
// 没有就用全局"的判断——重复写两遍，以后改一处漏了另一处，就是这个项目已经因为类似疏漏真实
// 出过 bug 的那种情况（见 docs/PITFALLS.md）。
//
// 返回的还是一个完整的 TeleprompterSettings：其余不参与这次改造的字段（scrollSpeed、
// backgroundColorArgb、settingsPanelOpacity 等）原样从 global 透传，调用方原有的读取代码
// （ChapterItem 内部一大串 settings.xxx）不用感知这层合并，也就不用逐处修改。
fun TeleprompterDocument.effectiveSettings(global: TeleprompterSettings): TeleprompterSettings = global.copy(
    fontSizeSp = displayOverrides.fontSizeSp ?: global.fontSizeSp,
    overallScale = displayOverrides.overallScale ?: global.overallScale,
    lineHeightMultiplier = displayOverrides.lineHeightMultiplier ?: global.lineHeightMultiplier,
    textAlignMode = displayOverrides.textAlignMode ?: global.textAlignMode,
    contentWidthPortrait = displayOverrides.contentWidthPortrait ?: global.contentWidthPortrait,
    contentWidthLandscape = displayOverrides.contentWidthLandscape ?: global.contentWidthLandscape,
)

@Serializable
data class TeleprompterLibrary(
    val documents: List<TeleprompterDocument> = emptyList(),
    val settings: TeleprompterSettings = TeleprompterSettings(),
    // GlassBgMode/GlassBackgroundConfig (glassui/GlassBackground.kt) - promoted out of this file
    // 2026-08-16 once the launcher screen needed the identical "4 modes + image adjustment" system
    // this originally was (TeleprompterLibraryBgMode/TeleprompterLibraryBackground); the on-disk
    // field name here is unchanged, so existing saved libraries keep decoding fine.
    val libraryBackground: GlassBackgroundConfig = GlassBackgroundConfig(),
)
