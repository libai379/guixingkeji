package com.guixing.teleprompter

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.sp
import com.mohamedrejeb.richeditor.model.RichTextState
import com.guixing.util.LogUtils
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

private const val TAG = "Prompter"
private const val MAX_PARAGRAPHS_PER_CHAPTER = 500

data class DocxImportSummary(
    val title: String,
    val chapters: List<TeleprompterChapter>,
    val skippedCount: Int,
    val fontFamily: TeleprompterFontFamily,
    val dominantFontName: String?,
    // null = no outlineLvl-bearing paragraph found anywhere in the document (single fallback
    // chapter). Otherwise the OOXML level (0-based, Heading1=0) that was used as the sole
    // chapter-split signal - see parseDocxDocument's two-pass scan.
    val headingLevelUsed: Int?,
    // Seeded from the source doc's own heading paragraphs (see the isHeading branch in
    // parseDocxDocument) instead of a hardcoded default - a Word doc that styled its headings at
    // 18pt bold red should keep looking like that after import, not silently reset to whatever
    // this app's own default happens to be.
    val subtitleStyle: TeleprompterSubtitleStyle,
    // 2026-08-25 - 只有本 app 自己导出的 docx（带 customXml 自定义部件）才会非默认，纯 Word 来源
    // 的文档这一项保持默认（全 null），createDocumentFromImport 那边用"新文档默认值"把缺的补齐。
    val displayOverrides: TeleprompterDisplayOverrides = TeleprompterDisplayOverrides(),
)

class DocxParseException(message: String) : Exception(message)

// --- small DOM helpers -------------------------------------------------

private fun parseXml(text: String): Document =
    DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
    }.newDocumentBuilder().parse(InputSource(StringReader(text)))

private fun Element.directChildren(tag: String? = null): List<Element> {
    val result = mutableListOf<Element>()
    val nodes = childNodes
    for (i in 0 until nodes.length) {
        val node = nodes.item(i)
        if (node is Element && (tag == null || node.tagName == tag)) result.add(node)
    }
    return result
}

private fun Element.firstChild(tag: String): Element? = directChildren(tag).firstOrNull()

private fun Element.attrOrNull(name: String): String? = getAttribute(name).ifBlank { null }

/** OOXML boolean toggle properties (w:b, w:i, w:u's presence, w:strike, ...): the element being
 * ABSENT means "not specified at this layer" (null - inherit from the layer above), while the
 * element being PRESENT means "specified", resolved per spec: no w:val attribute at all = true;
 * w:val="0"/"false"/"off" = explicitly false; anything else (including "1"/"true"/"on") = true.
 * Getting this wrong (treating "element merely present" as automatically true, ignoring w:val)
 * is exactly the kind of thing that silently breaks a style that turns a cascaded bold back off. */
private fun Element.readBooleanToggle(tag: String): Boolean? {
    val el = firstChild(tag) ?: return null
    val v = el.attrOrNull("w:val") ?: return true
    return v !in setOf("0", "false", "off")
}

// --- style cascade layers ---------------------------------------------------
//
// Full OOXML cascade, six layers, each overriding only the properties it explicitly specifies
// (per-property override, not whole-layer replacement):
//   1. docDefaults (rPrDefault / pPrDefault)
//   2. the default paragraph style (w:type="paragraph" w:default="1")
//   3. the paragraph's pStyle-referenced style, with its own w:basedOn chain resolved recursively
//   4. the paragraph's own <w:pPr> (its <w:pPr><w:rPr> counts as a run-props layer too)
//   5. the run's rStyle-referenced character style, with its own w:basedOn chain resolved recursively
//   6. the run's own <w:rPr>
//
// Every field is nullable = "not specified at this layer"; `overriding` folds a more-specific
// layer over a less-specific one, letting each property fall through independently instead of
// one layer replacing the whole set (a paragraph style can turn bold on while still inheriting
// font size from three layers further up, etc).

private data class RunPropsLayer(
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    val colorHex: String? = null,
    val highlightHex: String? = null,
    val fontSizeSp: Float? = null,
    val letterSpacingSp: Float? = null,
    val eastAsiaFont: String? = null,
    val eastAsiaThemeRef: String? = null,
) {
    fun overriding(base: RunPropsLayer): RunPropsLayer = RunPropsLayer(
        bold = bold ?: base.bold,
        italic = italic ?: base.italic,
        underline = underline ?: base.underline,
        strike = strike ?: base.strike,
        colorHex = colorHex ?: base.colorHex,
        highlightHex = highlightHex ?: base.highlightHex,
        fontSizeSp = fontSizeSp ?: base.fontSizeSp,
        letterSpacingSp = letterSpacingSp ?: base.letterSpacingSp,
        eastAsiaFont = eastAsiaFont ?: base.eastAsiaFont,
        eastAsiaThemeRef = eastAsiaThemeRef ?: base.eastAsiaThemeRef,
    )
}

private data class ParagraphPropsLayer(
    val jc: String? = null,
    val lineHeightMultiplier: Float? = null,
) {
    fun overriding(base: ParagraphPropsLayer): ParagraphPropsLayer = ParagraphPropsLayer(
        jc = jc ?: base.jc,
        lineHeightMultiplier = lineHeightMultiplier ?: base.lineHeightMultiplier,
    )
}

private fun extractRunPropsLayer(rPr: Element?): RunPropsLayer {
    if (rPr == null) return RunPropsLayer()
    val highlightHex = rPr.firstChild("w:highlight")?.attrOrNull("w:val")?.let(::wordHighlightNameToHex)
        ?: rPr.firstChild("w:shd")?.attrOrNull("w:fill")?.takeIf { it != "auto" }
    // w:sz is half-points (28 -> 14pt); w:spacing (character) is twentieths-of-a-point. We treat
    // our own sp values and OOXML points as numerically equal, same as the exporter - no scaling
    // factor, the resolved value is written through as-is (see TeleprompterEditorScreen's
    // separate display-only zoom for readability instead).
    val rFonts = rPr.firstChild("w:rFonts")
    return RunPropsLayer(
        bold = rPr.readBooleanToggle("w:b"),
        italic = rPr.readBooleanToggle("w:i"),
        underline = rPr.firstChild("w:u")?.let { it.attrOrNull("w:val") != "none" },
        strike = rPr.readBooleanToggle("w:strike"),
        colorHex = rPr.firstChild("w:color")?.attrOrNull("w:val")?.takeIf { it != "auto" },
        highlightHex = highlightHex,
        fontSizeSp = rPr.firstChild("w:sz")?.attrOrNull("w:val")?.toFloatOrNull()?.div(2f),
        letterSpacingSp = rPr.firstChild("w:spacing")?.attrOrNull("w:val")?.toFloatOrNull()?.div(20f),
        eastAsiaFont = rFonts?.attrOrNull("w:eastAsia"),
        eastAsiaThemeRef = rFonts?.attrOrNull("w:eastAsiaTheme"),
    )
}

private fun extractParagraphPropsLayer(pPr: Element?): ParagraphPropsLayer {
    if (pPr == null) return ParagraphPropsLayer()
    val spacing = pPr.firstChild("w:spacing")
    val lineHeightMultiplier = spacing?.attrOrNull("w:line")?.toFloatOrNull()?.let { line ->
        val rule = spacing.attrOrNull("w:lineRule")
        if (rule == "auto" || rule == null) line / 240f else null
    }
    return ParagraphPropsLayer(
        jc = pPr.firstChild("w:jc")?.attrOrNull("w:val"),
        lineHeightMultiplier = lineHeightMultiplier,
    )
}

// --- styles.xml ----------------------------------------------------------

private data class StyleDef(
    val basedOn: String?,
    // 0-based OOXML outline level (Heading1/标题1 = 0), or null if this style isn't a heading at
    // all. Resolved from an explicit <w:outlineLvl>, falling back to the trailing digit in an
    // English/Chinese "heading N" style name for documents that name the style but omit
    // outlineLvl - WPS emits numeric styleId ("2") instead of named ones ("Heading1"), so the
    // name fallback only kicks in when outlineLvl itself is genuinely absent.
    val outlineLvl: Int?,
    val runProps: RunPropsLayer,
    // Empty (all-null) for character styles, which have no <w:pPr> at all.
    val paragraphProps: ParagraphPropsLayer,
)

/** "heading3"/"Heading 3"/"标题3" -> 2 (OOXML levels are 0-based). Null if the name doesn't
 * follow this convention at all. */
private fun headingNameLevel(name: String): Int? {
    val trimmedDigits = when {
        name.lowercase().startsWith("heading") -> name.substring(7)
        name.startsWith("标题") -> name.substring(2)
        else -> return null
    }.trim()
    return trimmedDigits.toIntOrNull()?.let { it - 1 }
}

private fun parseStyles(stylesXml: String): Map<String, StyleDef> {
    val doc = parseXml(stylesXml)
    val result = mutableMapOf<String, StyleDef>()
    val styleNodes = doc.getElementsByTagName("w:style")
    for (i in 0 until styleNodes.length) {
        val styleEl = styleNodes.item(i) as? Element ?: continue
        val type = styleEl.getAttribute("w:type")
        // w:styleId is unique across the whole styles part regardless of type (verified against
        // two real WPS documents: paragraph/character/table styles all share one numeric-id
        // space), so paragraph and character styles can live in the same map.
        if (type != "paragraph" && type != "character") continue
        val id = styleEl.attrOrNull("w:styleId") ?: continue
        val name = styleEl.firstChild("w:name")?.attrOrNull("w:val") ?: ""
        val basedOn = styleEl.firstChild("w:basedOn")?.attrOrNull("w:val")
        val pPr = styleEl.firstChild("w:pPr")
        val explicitOutlineLvl = pPr?.firstChild("w:outlineLvl")?.attrOrNull("w:val")?.toIntOrNull()
        result[id] = StyleDef(
            basedOn = basedOn,
            outlineLvl = explicitOutlineLvl ?: headingNameLevel(name),
            runProps = extractRunPropsLayer(styleEl.firstChild("w:rPr")),
            paragraphProps = if (type == "paragraph") extractParagraphPropsLayer(pPr) else ParagraphPropsLayer(),
        )
    }
    return result
}

private fun findDefaultParagraphStyleId(stylesXml: String): String? {
    val doc = parseXml(stylesXml)
    val styleNodes = doc.getElementsByTagName("w:style")
    for (i in 0 until styleNodes.length) {
        val el = styleNodes.item(i) as? Element ?: continue
        if (el.getAttribute("w:type") == "paragraph" && el.getAttribute("w:default") == "1") {
            return el.attrOrNull("w:styleId")
        }
    }
    return null
}

private fun parseDocDefaults(stylesXml: String): Pair<RunPropsLayer, ParagraphPropsLayer> {
    val doc = parseXml(stylesXml)
    val docDefaultsEl = doc.getElementsByTagName("w:docDefaults").item(0) as? Element
        ?: return RunPropsLayer() to ParagraphPropsLayer()
    val rPr = docDefaultsEl.firstChild("w:rPrDefault")?.firstChild("w:rPr")
    val pPr = docDefaultsEl.firstChild("w:pPrDefault")?.firstChild("w:pPr")
    return extractRunPropsLayer(rPr) to extractParagraphPropsLayer(pPr)
}

/** w:basedOn chains recursively, guarded against cycles (A basedOn B, B basedOn A) via a
 * visited-id set - two real WPS documents both only went one level deep (heading N -> Normal),
 * but the guard is unconditional since the spec allows arbitrary depth. */
private fun resolveRunProps(
    styleId: String?,
    styles: Map<String, StyleDef>,
    visited: MutableSet<String> = mutableSetOf(),
): RunPropsLayer {
    if (styleId == null || !visited.add(styleId)) return RunPropsLayer()
    val style = styles[styleId] ?: return RunPropsLayer()
    return style.runProps.overriding(resolveRunProps(style.basedOn, styles, visited))
}

private fun resolveParagraphProps(
    styleId: String?,
    styles: Map<String, StyleDef>,
    visited: MutableSet<String> = mutableSetOf(),
): ParagraphPropsLayer {
    if (styleId == null || !visited.add(styleId)) return ParagraphPropsLayer()
    val style = styles[styleId] ?: return ParagraphPropsLayer()
    return style.paragraphProps.overriding(resolveParagraphProps(style.basedOn, styles, visited))
}

private fun resolveOutlineLvl(
    styleId: String?,
    styles: Map<String, StyleDef>,
    visited: MutableSet<String> = mutableSetOf(),
): Int? {
    if (styleId == null || !visited.add(styleId)) return null
    val style = styles[styleId] ?: return null
    return style.outlineLvl ?: resolveOutlineLvl(style.basedOn, styles, visited)
}

/** A paragraph's effective outline level: its own direct <w:outlineLvl> if present, else its
 * pStyle chain's level (recursing through w:basedOn), else null (not a heading at all). */
private fun paragraphOutlineLvl(p: Element, styles: Map<String, StyleDef>): Int? {
    val pPr = p.firstChild("w:pPr")
    val direct = pPr?.firstChild("w:outlineLvl")?.attrOrNull("w:val")?.toIntOrNull()
    if (direct != null) return direct
    val pStyleId = pPr?.firstChild("w:pStyle")?.attrOrNull("w:val")
    return resolveOutlineLvl(pStyleId, styles)
}

/** Full 6-layer run-properties cascade - see the block comment above RunPropsLayer for the order. */
private fun resolveEffectiveRunProps(
    runRPr: Element?,
    pStyleId: String?,
    paragraphOwnRPr: Element?,
    styles: Map<String, StyleDef>,
    defaultParagraphStyleId: String?,
    docDefaultsRunProps: RunPropsLayer,
): RunPropsLayer {
    var acc = docDefaultsRunProps // 1
    acc = resolveRunProps(defaultParagraphStyleId, styles).overriding(acc) // 2
    acc = resolveRunProps(pStyleId, styles).overriding(acc) // 3 (basedOn resolved inside)
    acc = extractRunPropsLayer(paragraphOwnRPr).overriding(acc) // 4 (pPr's own rPr)
    val rStyleId = runRPr?.firstChild("w:rStyle")?.attrOrNull("w:val")
    acc = resolveRunProps(rStyleId, styles).overriding(acc) // 5 (basedOn resolved inside)
    acc = extractRunPropsLayer(runRPr).overriding(acc) // 6
    return acc
}

/** The effective run props of a heading paragraph's first run - same 6-layer cascade as any other
 * run, just entered from a paragraph element instead of an already-split-out run/pPr pair. Falls
 * back to the paragraph-level layers alone (no run-level 5/6) for a heading with no runs at all
 * (an empty heading, or one that's pure w:tab/w:br). Used only to seed subtitleStyle from what the
 * source document's own headings actually looked like - never touched for the cascade that
 * renders chapter body content. */
private fun resolveHeadingRunProps(
    p: Element,
    styles: Map<String, StyleDef>,
    defaultParagraphStyleId: String?,
    docDefaultsRunProps: RunPropsLayer,
): RunPropsLayer {
    val pPr = p.firstChild("w:pPr")
    val pStyleId = pPr?.firstChild("w:pStyle")?.attrOrNull("w:val")
    val paragraphOwnRPr = pPr?.firstChild("w:rPr")
    val firstRunRPr = p.directChildren("w:r").firstOrNull()?.firstChild("w:rPr")
    return resolveEffectiveRunProps(
        firstRunRPr, pStyleId, paragraphOwnRPr, styles, defaultParagraphStyleId, docDefaultsRunProps,
    )
}

/** Paragraph-properties cascade - character styles have no pPr, so this only has 4 of the 6
 * layers (docDefaults, default paragraph style, pStyle chain, paragraph's own pPr). */
private fun resolveEffectiveParagraphProps(
    paragraphOwnPPr: Element?,
    pStyleId: String?,
    styles: Map<String, StyleDef>,
    defaultParagraphStyleId: String?,
    docDefaultsParagraphProps: ParagraphPropsLayer,
): ParagraphPropsLayer {
    var acc = docDefaultsParagraphProps
    acc = resolveParagraphProps(defaultParagraphStyleId, styles).overriding(acc)
    acc = resolveParagraphProps(pStyleId, styles).overriding(acc)
    acc = extractParagraphPropsLayer(paragraphOwnPPr).overriding(acc)
    return acc
}

// --- theme1.xml ------------------------------------------------------------

/** Resolves theme font refs like "minorEastAsia" to a real typeface name - most documents never
 * touch font settings, so almost everything falls back to this rather than an explicit w:eastAsia. */
private fun parseThemeFonts(themeXml: String): Map<String, String> {
    val doc = parseXml(themeXml)
    val result = mutableMapOf<String, String>()
    val schemeEl = doc.getElementsByTagName("a:fontScheme").item(0) as? Element ?: return result
    schemeEl.firstChild("a:majorFont")?.let { major ->
        major.firstChild("a:latin")?.attrOrNull("typeface")?.let { result["majorHAnsi"] = it }
        major.firstChild("a:ea")?.attrOrNull("typeface")?.let { result["majorEastAsia"] = it }
    }
    schemeEl.firstChild("a:minorFont")?.let { minor ->
        minor.firstChild("a:latin")?.attrOrNull("typeface")?.let { result["minorHAnsi"] = it }
        minor.firstChild("a:ea")?.attrOrNull("typeface")?.let { result["minorEastAsia"] = it }
    }
    return result
}

/** Our app only offers two buckets (SansSerif/Serif per the step-0 device test that showed
 * Monospace was a fake option) - classify by keyword since we can't enumerate every CJK font. */
private fun classifyFontFamily(fontName: String): TeleprompterFontFamily {
    val serifKeywords = listOf("宋", "楷", "仿宋", "明", "Serif", "Song", "Ming")
    return if (serifKeywords.any { fontName.contains(it, ignoreCase = true) }) {
        TeleprompterFontFamily.Serif
    } else {
        TeleprompterFontFamily.SansSerif
    }
}

private fun wordHighlightNameToHex(name: String): String? = when (name) {
    "yellow" -> "FFFF00"
    "green" -> "00FF00"
    "cyan" -> "00FFFF"
    "magenta" -> "FF00FF"
    "blue" -> "0000FF"
    "red" -> "FF0000"
    "darkBlue" -> "000080"
    "darkCyan" -> "008080"
    "darkGreen" -> "008000"
    "darkMagenta" -> "800080"
    "darkRed" -> "800000"
    "darkYellow" -> "808000"
    "darkGray" -> "808080"
    "lightGray" -> "C0C0C0"
    "black" -> "000000"
    else -> null
}

// --- run/paragraph extraction ----------------------------------------------

/** Skippable per spec: images/drawings, footnote/endnote refs, comments, tracked changes.
 * Tables are handled one level up (they're w:body children, not w:p children). */
private val SKIP_TAGS_IN_RUN = setOf("w:drawing", "w:pict", "w:footnoteReference", "w:endnoteReference")
private val SKIP_TAGS_IN_PARAGRAPH = setOf(
    "w:commentRangeStart", "w:commentRangeEnd", "w:commentReference", "w:ins", "w:del",
)

private fun extractParagraphPlainText(p: Element): String {
    val sb = StringBuilder()
    for (run in p.directChildren("w:r")) {
        for (child in run.directChildren()) {
            when (child.tagName) {
                "w:t" -> sb.append(child.textContent)
                "w:tab" -> sb.append('\t')
                "w:br" -> sb.append(' ')
            }
        }
    }
    return sb.toString()
}

private class ParagraphExtraction(
    val text: String,
    val runs: List<Pair<IntRange, RunPropsLayer>>,
    val alignLeft: Boolean,
    val alignCenter: Boolean,
    val alignRight: Boolean,
    val alignJustify: Boolean,
    val lineHeightMultiplier: Float?,
)

private fun extractParagraph(
    p: Element,
    styles: Map<String, StyleDef>,
    defaultParagraphStyleId: String?,
    docDefaultsRunProps: RunPropsLayer,
    docDefaultsParagraphProps: ParagraphPropsLayer,
    onSkip: () -> Unit,
): ParagraphExtraction {
    val pPr = p.firstChild("w:pPr")
    val pStyleId = pPr?.firstChild("w:pStyle")?.attrOrNull("w:val")
    val paragraphOwnRPr = pPr?.firstChild("w:rPr")
    val resolvedParaProps = resolveEffectiveParagraphProps(
        pPr, pStyleId, styles, defaultParagraphStyleId, docDefaultsParagraphProps,
    )
    val jc = resolvedParaProps.jc

    val text = StringBuilder()
    val runs = mutableListOf<Pair<IntRange, RunPropsLayer>>()
    for (child in p.directChildren()) {
        if (child.tagName in SKIP_TAGS_IN_PARAGRAPH) {
            onSkip()
            continue
        }
        if (child.tagName != "w:r") continue
        if (child.directChildren().any { it.tagName in SKIP_TAGS_IN_RUN }) {
            onSkip()
            continue
        }
        val runText = StringBuilder()
        for (piece in child.directChildren()) {
            when (piece.tagName) {
                "w:t" -> runText.append(piece.textContent)
                "w:tab" -> runText.append('\t')
                "w:br" -> runText.append(' ')
            }
        }
        if (runText.isEmpty()) continue
        val start = text.length
        text.append(runText)
        val effectiveRunProps = resolveEffectiveRunProps(
            child.firstChild("w:rPr"), pStyleId, paragraphOwnRPr, styles, defaultParagraphStyleId, docDefaultsRunProps,
        )
        runs.add(start until text.length to effectiveRunProps)
    }

    return ParagraphExtraction(
        text = text.toString(),
        runs = runs,
        alignLeft = jc == "left" || jc == "start",
        alignCenter = jc == "center",
        alignRight = jc == "right" || jc == "end",
        alignJustify = jc == "both",
        lineHeightMultiplier = resolvedParaProps.lineHeightMultiplier,
    )
}

// --- chapter body building --------------------------------------------------

private fun resolveFontName(resolved: RunPropsLayer, themeFonts: Map<String, String>): String? {
    resolved.eastAsiaFont?.let { return it }
    resolved.eastAsiaThemeRef?.let { ref -> themeFonts[ref]?.let { return it } }
    return null
}

private fun buildChapterHtml(
    paragraphs: List<Element>,
    styles: Map<String, StyleDef>,
    themeFonts: Map<String, String>,
    defaultParagraphStyleId: String?,
    docDefaultsRunProps: RunPropsLayer,
    docDefaultsParagraphProps: ParagraphPropsLayer,
    fontCharCount: MutableMap<String, Int>,
    onSkip: () -> Unit,
): String {
    val state = RichTextState()
    val fullText = StringBuilder()
    data class PendingSpan(val range: IntRange, val format: RunPropsLayer)
    data class PendingParagraph(val range: IntRange, val extraction: ParagraphExtraction)
    val pendingSpans = mutableListOf<PendingSpan>()
    val pendingParagraphs = mutableListOf<PendingParagraph>()

    for (p in paragraphs) {
        val extraction = extractParagraph(p, styles, defaultParagraphStyleId, docDefaultsRunProps, docDefaultsParagraphProps, onSkip)
        val paraStart = fullText.length
        for ((range, format) in extraction.runs) {
            val absRange = (paraStart + range.first)..(paraStart + range.last)
            pendingSpans.add(PendingSpan(absRange, format))
            val fontName = resolveFontName(format, themeFonts)
            if (fontName != null) {
                fontCharCount[fontName] = (fontCharCount[fontName] ?: 0) + (range.last - range.first + 1)
            }
        }
        fullText.append(extraction.text)
        val paraEnd = fullText.length
        pendingParagraphs.add(PendingParagraph(paraStart until maxOf(paraEnd, paraStart + 1), extraction))
        fullText.append('\n')
    }

    if (fullText.isEmpty()) return ""

    state.addTextAfterSelection(fullText.toString())

    for (span in pendingSpans) {
        if (span.range.isEmpty()) continue
        val range = TextRange(span.range.first, span.range.last + 1)
        val f = span.format
        if (f.bold == true) state.addSpanStyle(SpanStyle(fontWeight = FontWeight.Bold), range)
        if (f.italic == true) state.addSpanStyle(SpanStyle(fontStyle = FontStyle.Italic), range)
        val decorations = buildList {
            if (f.underline == true) add(TextDecoration.Underline)
            if (f.strike == true) add(TextDecoration.LineThrough)
        }
        if (decorations.isNotEmpty()) state.addSpanStyle(SpanStyle(textDecoration = TextDecoration.combine(decorations)), range)
        f.colorHex?.let { hex -> parseHexColorOrNull(hex)?.let { state.addSpanStyle(SpanStyle(color = it), range) } }
        f.highlightHex?.let { hex -> parseHexColorOrNull(hex)?.let { state.addSpanStyle(SpanStyle(background = it), range) } }
        // Cascaded absolute value, written through as-is - no readability up-scaling here (that
        // would drift from the real value and break Word re-export fidelity); display-size
        // readability is handled entirely by the editor/player's separate zoom controls.
        f.fontSizeSp?.let { state.addSpanStyle(SpanStyle(fontSize = it.sp), range) }
        f.letterSpacingSp?.let { state.addSpanStyle(SpanStyle(letterSpacing = it.sp), range) }
    }

    // addParagraphStyle/toggleParagraphStyle have no TextRange overload - they act on the
    // ambient state.selection, so it must be set explicitly per paragraph before each call
    // (unlike addSpanStyle, which does take an explicit range).
    for (para in pendingParagraphs) {
        val e = para.extraction
        val align = when {
            e.alignCenter -> TextAlign.Center
            e.alignRight -> TextAlign.Right
            e.alignJustify -> TextAlign.Justify
            e.alignLeft -> TextAlign.Left
            else -> null
        }
        if (align == null && e.lineHeightMultiplier == null) continue
        state.selection = TextRange(para.range.first, para.range.last + 1)
        if (align != null) {
            state.addParagraphStyle(ParagraphStyle(textAlign = align))
        }
        e.lineHeightMultiplier?.let { mult ->
            state.addParagraphStyle(ParagraphStyle(lineHeight = TextUnit(mult, TextUnitType.Em)))
        }
    }

    return state.toHtml()
}

private fun parseHexColorOrNull(hex: String): Color? {
    val clean = hex.removePrefix("#").filter { it.isDigit() || it.uppercaseChar() in 'A'..'F' }
    if (clean.length != 6) return null
    return try {
        val value = clean.toLong(16)
        Color(
            red = ((value shr 16) and 0xFF) / 255f,
            green = ((value shr 8) and 0xFF) / 255f,
            blue = (value and 0xFF) / 255f,
            alpha = 1f,
        )
    } catch (e: NumberFormatException) {
        null
    }
}

// --- entry point -------------------------------------------------------------

fun parseDocxDocument(
    documentXml: String,
    stylesXml: String?,
    themeXml: String?,
    fallbackTitle: String,
    // 2026-08-25 - 见 DocxImportSummary 的同名字段。默认 null，两个既有单元测试调用点（只传前
    // 4 个参数）不用改。
    customSettingsXml: String? = null,
): DocxImportSummary {
    val docxSettings = customSettingsXml?.let(::parseDocxSettingsXml)
    val styles = stylesXml?.let(::parseStyles) ?: emptyMap()
    val themeFonts = themeXml?.let(::parseThemeFonts) ?: emptyMap()
    val defaultParagraphStyleId = stylesXml?.let(::findDefaultParagraphStyleId)
    val (docDefaultsRunProps, docDefaultsParagraphProps) = stylesXml?.let(::parseDocDefaults)
        ?: (RunPropsLayer() to ParagraphPropsLayer())

    val doc = parseXml(documentXml)
    val bodyEl = doc.getElementsByTagName("w:body").item(0) as? Element
        ?: throw DocxParseException("word/document.xml 里没有 <w:body>，不是有效的 docx 内容")

    var skippedCount = 0
    val onSkip: () -> Unit = { skippedCount++ }
    val fontCharCount = mutableMapOf<String, Int>()
    val chapters = mutableListOf<TeleprompterChapter>()

    // Two-pass, per the retracted "1和2都当同级" rule: a single document can mix a real
    // top-level heading (e.g. product-name titles when several source docs got merged into one)
    // with lower-level emphasis labels that only LOOK like headings (bold/named style, but not
    // meant to split chapters). Pass 1 scans every paragraph's effective outline level and takes
    // only the highest one (smallest number) as the chapter-split signal; anything at a lower
    // level keeps its own formatting (handled automatically - it just flows through
    // buildChapterHtml as a normal paragraph, same as any other run-formatted text) but no
    // longer creates a chapter boundary.
    val paragraphEls = bodyEl.directChildren("w:p")
    val splitLevel = paragraphEls.mapNotNull { paragraphOutlineLvl(it, styles) }.minOrNull()

    var sawHeading = false
    val leading = mutableListOf<Element>()
    var currentSubtitle = ""
    var current = mutableListOf<Element>()
    // One sample per heading paragraph encountered - "多个标题样式不一致时取出现最多的那个" (see
    // below the loop), so this deliberately keeps every sample rather than folding them as it goes.
    val headingRunPropSamples = mutableListOf<RunPropsLayer>()

    fun flush(subtitle: String, paragraphs: List<Element>) {
        if (paragraphs.isEmpty()) return
        val html = buildChapterHtml(
            paragraphs, styles, themeFonts, defaultParagraphStyleId,
            docDefaultsRunProps, docDefaultsParagraphProps, fontCharCount, onSkip,
        )
        chapters.add(TeleprompterChapter(subtitle = subtitle, contentHtml = html))
    }

    for (child in bodyEl.directChildren()) {
        when (child.tagName) {
            "w:p" -> {
                val isHeading = splitLevel != null && paragraphOutlineLvl(child, styles) == splitLevel
                if (isHeading) {
                    if (!sawHeading) {
                        flush(fallbackTitle, leading)
                        sawHeading = true
                    } else {
                        flush(currentSubtitle, current)
                        current = mutableListOf()
                    }
                    currentSubtitle = extractParagraphPlainText(child).ifBlank { "未命名章节" }
                    headingRunPropSamples.add(
                        resolveHeadingRunProps(child, styles, defaultParagraphStyleId, docDefaultsRunProps),
                    )
                } else if (!sawHeading) {
                    leading.add(child)
                } else {
                    current.add(child)
                    if (current.size >= MAX_PARAGRAPHS_PER_CHAPTER) {
                        flush("$currentSubtitle（续）", current)
                        current = mutableListOf()
                        currentSubtitle = "$currentSubtitle（续）"
                    }
                }
            }
            "w:tbl" -> skippedCount++
            "w:sectPr" -> Unit
            else -> Unit
        }
    }
    if (!sawHeading) {
        flush(fallbackTitle, leading)
    } else {
        flush(currentSubtitle, current)
    }

    if (chapters.isEmpty()) {
        chapters.add(TeleprompterChapter(subtitle = fallbackTitle, contentHtml = ""))
    }

    val dominantFont = fontCharCount.maxByOrNull { it.value }?.key
    val appFontFamily = dominantFont?.let(::classifyFontFamily) ?: TeleprompterFontFamily.SansSerif

    // "多个标题样式不一致时取出现最多的那个" - group by the exact (size, bold, color) combo rather
    // than picking each property's own most-common value independently (which could Frankenstein
    // together a combo that never actually appeared in the document).
    val subtitleStyle = headingRunPropSamples
        .groupingBy { Triple(it.fontSizeSp, it.bold, it.colorHex) }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key
        ?.let { (fontSizeSp, bold, colorHex) ->
            TeleprompterSubtitleStyle(
                fontSizeSp = fontSizeSp ?: TeleprompterSubtitleStyle().fontSizeSp,
                bold = bold ?: TeleprompterSubtitleStyle().bold,
                colorArgb = colorHex?.let(::parseHexColorOrNull)?.let { it.toArgb().toLong() and 0xFFFFFFFFL },
            )
        }
        ?: TeleprompterSubtitleStyle()

    // Swallowed on purpose: LogUtils.d touches the real Android Application singleton, which
    // doesn't exist when this pure-parsing function is exercised from a plain JVM unit test
    // (see TeleprompterWordExportTest) - a logging failure must never invalidate a parse that
    // already succeeded, in tests or in the app.
    runCatching {
        LogUtils.d(
            "docx parse: chapters=${chapters.size}, skipped=$skippedCount, " +
                "dominantFont=$dominantFont -> $appFontFamily, splitLevel=$splitLevel " +
                "(all levels seen=${paragraphEls.mapNotNull { paragraphOutlineLvl(it, styles) }.toSortedSet()})",
            tag = TAG,
        )
    }

    return DocxImportSummary(
        title = fallbackTitle,
        chapters = chapters,
        skippedCount = skippedCount,
        fontFamily = appFontFamily,
        dominantFontName = dominantFont,
        headingLevelUsed = splitLevel,
        // 自定义部件里存的副标题样式优先于从标题段落扫描出来的那个——只有重新导入本 app 自己
        // 导出的文件才会两者都有，这时候自定义部件是更准确的来源（扫描是启发式的，这个是原样
        // 存的数值）。纯 Word 来源没有自定义部件，docxSettings 为 null，走原来的扫描结果不变。
        subtitleStyle = docxSettings?.subtitleStyle ?: subtitleStyle,
        displayOverrides = docxSettings?.displayOverrides ?: TeleprompterDisplayOverrides(),
    )
}
