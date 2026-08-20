package li.songe.gkd.teleprompter

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
 * Builds a minimal-but-real docx: [Content_Types].xml + _rels/.rels + word/document.xml only -
 * no styles.xml/theme/fontTable, matching the "don't use pStyle, just bold+big+outlineLvl"
 * instruction, which sidesteps needing to generate a styles.xml at all.
 */
fun buildDocxBytes(document: TeleprompterDocument): ByteArray {
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
                var paraStart = 0
                var i = 0
                while (i <= text.length) {
                    if (i == text.length || text[i] == '\n') {
                        append(bodyParagraphXml(annotated, paraStart, i, document.fontFamily))
                        paraStart = i + 1
                    }
                    i++
                }
            }
        }
    }
    val documentXml = DOCUMENT_XML_HEADER + bodyXml + DOCUMENT_XML_FOOTER

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
    }
    return out.toByteArray()
}

fun buildMinimalTestDocx(): ByteArray {
    val doc = TeleprompterDocument(
        title = "测试文档",
        chapters = listOf(TeleprompterChapter(subtitle = "测试章节", contentHtml = "<p>测试</p>")),
    )
    return buildDocxBytes(doc)
}
