package com.guixing.teleprompter

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import com.mohamedrejeb.richeditor.model.RichTextState
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

// Namespace block + sectPr copied verbatim from a real WPS-generated document.xml (see step 3's
// inspection dump) rather than typed from the OOXML spec from memory - "照着真实文件对".
private const val DOCUMENT_XML_HEADER = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:wpc="http://schemas.microsoft.com/office/word/2010/wordprocessingCanvas" xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math" xmlns:v="urn:schemas-microsoft-com:vml" xmlns:wp14="http://schemas.microsoft.com/office/word/2010/wordprocessingDrawing" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:w14="http://schemas.microsoft.com/office/word/2010/wordml" xmlns:w10="urn:schemas-microsoft-com:office:word" xmlns:w15="http://schemas.microsoft.com/office/word/2012/wordml" xmlns:wpg="http://schemas.microsoft.com/office/word/2010/wordprocessingGroup" xmlns:wpi="http://schemas.microsoft.com/office/word/2010/wordprocessingInk" xmlns:wne="http://schemas.microsoft.com/office/word/2006/wordml" xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape" mc:Ignorable="w14 w15 wp14"><w:body>"""

private const val DOCUMENT_XML_FOOTER = """<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1800" w:bottom="1440" w:left="1800" w:header="851" w:footer="992" w:gutter="0"/><w:cols w:space="425" w:num="1"/><w:docGrid w:type="lines" w:linePitch="312" w:charSpace="0"/></w:sectPr></w:body></w:document>"""

private const val CONTENT_TYPES_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""

private const val ROOT_RELS_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""

private fun escapeXml(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

private fun Color.toHexRgb(): String = String.format("%06X", toArgb() and 0xFFFFFF)

private fun fontAttrs(fontFamily: TeleprompterFontFamily): String = when (fontFamily) {
    TeleprompterFontFamily.SansSerif -> """w:ascii="Arial" w:hAnsi="Arial" w:eastAsia="黑体""""
    TeleprompterFontFamily.Serif -> """w:ascii="Times New Roman" w:hAnsi="Times New Roman" w:eastAsia="宋体""""
}

private fun runXml(text: String, style: SpanStyle, fontFamily: TeleprompterFontFamily): String {
    if (text.isEmpty()) return ""
    val rPr = buildString {
        append("<w:rPr>")
        append("<w:rFonts ${fontAttrs(fontFamily)}/>")
        if (style.fontWeight == FontWeight.Bold) append("<w:b/>")
        if (style.fontStyle == FontStyle.Italic) append("<w:i/>")
        if (style.textDecoration?.contains(TextDecoration.Underline) == true) append("""<w:u w:val="single"/>""")
        if (style.color != Color.Unspecified) append("""<w:color w:val="${style.color.toHexRgb()}"/>""")
        if (style.background != Color.Unspecified && style.background != Color.Transparent) {
            append("""<w:shd w:val="clear" w:fill="${style.background.toHexRgb()}"/>""")
        }
        if (style.fontSize.type == TextUnitType.Sp) {
            append("""<w:sz w:val="${(style.fontSize.value * 2).roundToInt()}"/>""")
        }
        if (style.letterSpacing.type == TextUnitType.Sp) {
            append("""<w:spacing w:val="${(style.letterSpacing.value * 20).roundToInt()}"/>""")
        }
        append("</w:rPr>")
    }
    return "<w:r>$rPr<w:t xml:space=\"preserve\">${escapeXml(text)}</w:t></w:r>"
}

private fun paragraphPrXml(style: ParagraphStyle, isHeading: Boolean): String = buildString {
    append("<w:pPr>")
    val jc = when (style.textAlign) {
        TextAlign.Center -> "center"
        TextAlign.Right, TextAlign.End -> "right"
        TextAlign.Justify -> "both"
        else -> null
    }
    jc?.let { append("""<w:jc w:val="$it"/>""") }
    append("""<w:spacing w:before="120" w:after="120"""")
    if (style.lineHeight.type == TextUnitType.Em) {
        val lineVal = (style.lineHeight.value * 240).roundToInt()
        append(""" w:line="$lineVal" w:lineRule="auto"""")
    }
    append("/>")
    if (isHeading) append("""<w:outlineLvl w:val="0"/>""")
    append("</w:pPr>")
}

private fun mergedSpanStyleAt(annotated: AnnotatedString, index: Int): SpanStyle {
    var result = SpanStyle()
    for (range in annotated.spanStyles) {
        if (index >= range.start && index < range.end) result = result.merge(range.item)
    }
    return result
}

/**
 * 段落边界的**唯一**来源：`annotatedString.paragraphStyles` 的 range，不是文本里的 '\n'。
 *
 * 2026-08-28 - 修真实 bug：原来按 `text[i] == '\n'` 切段。但 `RichTextState.setHtml()` **不会**在
 * HTML 的 `<p>` 之间插入换行符，段落边界只体现在 paragraphStyles 里。结果是每章正文整体没有一个
 * '\n'，被当成一个段落导出——沉香话术实测：应用里 330 个 `<p>`，导出的 docx 只有 19 个 `<w:p>`
 * （= 1 标题 + 9 副标题 + 9 章正文各 1 段），而且 `<w:br/>` 也是 0，段落边界既没变成段落也没变成
 * 换行，直接丢了。再把这份 docx 导回来自然"跟原来不一样"。
 *
 * 兜底：没有任何 paragraphStyles 时（纯文本内容）退回按 '\n' 切，行为跟以前一致。
 */
private fun paragraphRangesOf(annotated: AnnotatedString): List<Pair<Int, Int>> {
    val text = annotated.text
    val bounds = sortedSetOf(0, text.length)
    for (range in annotated.paragraphStyles) {
        if (range.start in 0..text.length) bounds.add(range.start)
        if (range.end in 0..text.length) bounds.add(range.end)
    }
    // '\n' 仍然算边界：导入的内容里若真有换行符，它同样是段落分隔。
    text.forEachIndexed { i, c -> if (c == '\n') { bounds.add(i); bounds.add(i + 1) } }
    val points = bounds.toList()
    val result = mutableListOf<Pair<Int, Int>>()
    for (i in 0 until points.size - 1) {
        val s = points[i]
        val e = points[i + 1]
        if (s >= e) continue
        // 跳过纯换行符构成的空段，避免每个 '\n' 额外产出一个空 <w:p>
        if (e - s == 1 && text[s] == '\n') continue
        result.add(s to e)
    }
    return result.ifEmpty { listOf(0 to text.length) }
}

private fun mergedParagraphStyleAt(annotated: AnnotatedString, index: Int): ParagraphStyle {
    var result = ParagraphStyle()
    for (range in annotated.paragraphStyles) {
        if (index >= range.start && index < range.end) result = result.merge(range.item)
    }
    return result
}

/** Splits [start, end) at every span-style boundary inside it, so each emitted run has one
 * single, unambiguous merged SpanStyle instead of guessing how overlapping ranges combine. */
private fun spanBoundariesWithin(annotated: AnnotatedString, start: Int, end: Int): List<Int> {
    val points = sortedSetOf(start, end)
    for (range in annotated.spanStyles) {
        if (range.start in start until end) points.add(range.start)
        if (range.end in (start + 1)..end) points.add(range.end)
    }
    return points.toList()
}

private fun bodyParagraphXml(
    annotated: AnnotatedString,
    paraStart: Int,
    paraEnd: Int,
    fontFamily: TeleprompterFontFamily,
): String {
    val pPr = paragraphPrXml(mergedParagraphStyleAt(annotated, paraStart), isHeading = false)
    val boundaries = spanBoundariesWithin(annotated, paraStart, paraEnd)
    val runs = buildString {
        for (i in 0 until boundaries.size - 1) {
            val segStart = boundaries[i]
            val segEnd = boundaries[i + 1]
            if (segStart >= segEnd) continue
            val text = annotated.text.substring(segStart, segEnd)
            append(runXml(text, mergedSpanStyleAt(annotated, segStart), fontFamily))
        }
    }
    return "<w:p>$pPr$runs</w:p>"
}

private fun headingParagraphXml(text: String, fontFamily: TeleprompterFontFamily): String {
    val rPr = "<w:rPr><w:rFonts ${fontAttrs(fontFamily)}/><w:b/><w:sz w:val=\"44\"/></w:rPr>"
    val pPr = """<w:pPr><w:spacing w:before="120" w:after="120"/><w:outlineLvl w:val="0"/></w:pPr>"""
    return "<w:p>$pPr<w:r>$rPr<w:t xml:space=\"preserve\">${escapeXml(text)}</w:t></w:r></w:p>"
}

/**
 * Builds a minimal-but-real docx: [Content_Types].xml + _rels/.rels + word/document.xml, plus
 * (2026-08-25) word/_rels/document.xml.rels + customXml/item1.xml carrying this app's own display
 * settings (see TeleprompterDocxSettings.kt for why/what) - no styles.xml/theme/fontTable, matching
 * the "don't use pStyle, just bold+big+outlineLvl" instruction, which sidesteps needing to
 * generate a styles.xml at all.
 *
 * [effectiveSettings] must be the CALLER's already-merged doc.effectiveSettings(library.settings)
 * - this function doesn't have access to the library's global settings on its own, only to
 * [document]. Exported as concrete resolved values (never null), unlike the live in-app
 * TeleprompterDisplayOverrides which is mostly null - see buildDocxSettingsXml's own doc comment.
 */
fun buildDocxBytes(document: TeleprompterDocument, effectiveSettings: TeleprompterSettings): ByteArray {
    val bodyXml = buildString {
        append(headingParagraphXml(document.title, document.fontFamily))
        for (chapter in document.chapters) {
            append(headingParagraphXml(chapter.subtitle, document.fontFamily))
            val state = RichTextState()
            state.setHtml(chapter.contentHtml)
            val annotated = state.annotatedString
            val text = annotated.text
            if (text.isEmpty()) {
                append("<w:p/>")
            } else {
                for ((paraStart, paraEnd) in paragraphRangesOf(annotated)) {
                    append(bodyParagraphXml(annotated, paraStart, paraEnd, document.fontFamily))
                }
            }
        }
    }
    val documentXml = DOCUMENT_XML_HEADER + bodyXml + DOCUMENT_XML_FOOTER
    // 导出的是"这篇文档当前实际生效的样子"，不是"这篇文档自己覆盖了哪几项"——不管这五项是文档
    // 自己覆盖的还是从全局继承来的，导出时一律写具体数值，effectiveSettings 已经把这层判断做完
    // 了，这里直接拿结果。
    val settingsXml = buildDocxSettingsXml(
        overrides = TeleprompterDisplayOverrides(
            fontSizeSp = effectiveSettings.fontSizeSp,
            overallScale = effectiveSettings.overallScale,
            lineHeightMultiplier = effectiveSettings.lineHeightMultiplier,
            textAlignMode = effectiveSettings.textAlignMode,
            contentWidthPortrait = effectiveSettings.contentWidthPortrait,
            contentWidthLandscape = effectiveSettings.contentWidthLandscape,
        ),
        subtitleStyle = document.subtitleStyle,
    )

    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        fun writeEntry(name: String, content: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        // [Content_Types].xml must be the first entry in the archive.
        writeEntry("[Content_Types].xml", CONTENT_TYPES_XML)
        writeEntry("_rels/.rels", ROOT_RELS_XML)
        writeEntry("word/document.xml", documentXml)
        writeEntry(DOCX_DOCUMENT_RELS_PATH, DOCUMENT_RELS_XML)
        writeEntry(DOCX_SETTINGS_CUSTOM_XML_PATH, settingsXml)
    }
    return out.toByteArray()
}

fun buildMinimalTestDocx(): ByteArray {
    val doc = TeleprompterDocument(
        title = "测试文档",
        chapters = listOf(TeleprompterChapter(subtitle = "测试章节", contentHtml = "<p>测试</p>")),
    )
    return buildDocxBytes(doc, TeleprompterSettings())
}
