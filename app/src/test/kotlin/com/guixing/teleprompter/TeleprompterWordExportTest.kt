package com.guixing.teleprompter

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mohamedrejeb.richeditor.model.RichTextState
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

class TeleprompterWordExportTest {

    @Test
    fun minimalDocxIsWellFormed() {
        val bytes = buildMinimalTestDocx()
        val file = File("build/test-minimal.docx")
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)

        // ZipFile open confirms the archive structure is intact (not just "bytes exist").
        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            check(names.firstOrNull() == "[Content_Types].xml") {
                "[Content_Types].xml must be the first zip entry, got: $names"
            }
            check("word/document.xml" in names) { "word/document.xml missing, got: $names" }

            val documentXml = zip.getInputStream(zip.getEntry("word/document.xml")).readBytes().toString(Charsets.UTF_8)
            // A DOM parse failing here means we produced malformed XML - the single most likely
            // way to silently hand someone a corrupt .docx.
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(documentXml.byteInputStream())
        }
        println("wrote ${file.absolutePath} (${bytes.size} bytes)")
    }

    @Test
    fun exportThenReimportRoundTrip() {
        val original = buildSampleDocument()
        val docxBytes = buildDocxBytes(original)

        val file = File("build/test-roundtrip.docx")
        file.parentFile?.mkdirs()
        file.writeBytes(docxBytes)

        val documentXml = ZipFile(file).use { zip ->
            zip.getInputStream(zip.getEntry("word/document.xml")).readBytes().toString(Charsets.UTF_8)
        }
        // No styles.xml/theme1.xml on purpose (see buildDocxBytes doc comment) - parser must
        // cope with both being absent (font detection just won't find anything, that's expected).
        val reimported = parseDocxDocument(documentXml, stylesXml = null, themeXml = null, fallbackTitle = original.title)

        val report = StringBuilder()
        fun log(line: String) = report.appendLine(line)

        log("=== chapter count ===")
        log("original=${original.chapters.size} reimported=${reimported.chapters.size}")

        log("")
        log("=== per-chapter subtitle ===")
        original.chapters.forEachIndexed { i, ch ->
            val back = reimported.chapters.getOrNull(i)
            log("[$i] original=\"${ch.subtitle}\" reimported=\"${back?.subtitle}\" match=${ch.subtitle == back?.subtitle}")
        }

        log("")
        log("=== per-chapter formatting presence (original html vs reimported html) ===")
        original.chapters.forEachIndexed { i, ch ->
            val back = reimported.chapters.getOrNull(i) ?: return@forEachIndexed
            val originalState = RichTextState().apply { setHtml(ch.contentHtml) }
            val reimportedState = RichTextState().apply { setHtml(back.contentHtml) }
            log("--- chapter $i (\"${ch.subtitle}\") ---")
            log("  text before: ${originalState.annotatedString.text.replace("\n", "\\n")}")
            log("  text after : ${reimportedState.annotatedString.text.replace("\n", "\\n")}")

            val checks = listOf(
                "bold" to (SpanStyle(fontWeight = FontWeight.Bold)),
                "italic" to (SpanStyle(fontStyle = FontStyle.Italic)),
                "underline" to (SpanStyle(textDecoration = TextDecoration.Underline)),
            )
            for ((name, probe) in checks) {
                val beforeHas = hasSpanStyleField(originalState, probe)
                val afterHas = hasSpanStyleField(reimportedState, probe)
                log("  $name: before=$beforeHas after=$afterHas ${if (beforeHas != afterHas) "<<< MISMATCH" else ""}")
            }
            val beforeColors = distinctColors(originalState)
            val afterColors = distinctColors(reimportedState)
            log("  colors: before=$beforeColors after=$afterColors ${if (beforeColors != afterColors) "<<< MISMATCH" else ""}")
            val beforeHighlights = distinctBackgrounds(originalState)
            val afterHighlights = distinctBackgrounds(reimportedState)
            log("  highlights: before=$beforeHighlights after=$afterHighlights ${if (beforeHighlights != afterHighlights) "<<< MISMATCH" else ""}")
            val beforeSizes = distinctFontSizes(originalState)
            val afterSizes = distinctFontSizes(reimportedState)
            log("  fontSizes: before=$beforeSizes after=$afterSizes ${if (beforeSizes != afterSizes) "<<< MISMATCH" else ""}")
            val beforeSpacing = distinctLetterSpacings(originalState)
            val afterSpacing = distinctLetterSpacings(reimportedState)
            log("  letterSpacings: before=$beforeSpacing after=$afterSpacing ${if (beforeSpacing != afterSpacing) "<<< MISMATCH" else ""}")
            val beforeAligns = distinctAligns(originalState)
            val afterAligns = distinctAligns(reimportedState)
            log("  aligns: before=$beforeAligns after=$afterAligns ${if (beforeAligns != afterAligns) "<<< MISMATCH" else ""}")
            val beforeLineHeights = distinctLineHeights(originalState)
            val afterLineHeights = distinctLineHeights(reimportedState)
            log("  lineHeights: before=$beforeLineHeights after=$afterLineHeights ${if (beforeLineHeights != afterLineHeights) "<<< MISMATCH" else ""}")
        }

        val reportText = report.toString()
        println(reportText)
        File("build/roundtrip_report.txt").writeText(reportText)
    }
}

private fun hasSpanStyleField(state: RichTextState, probe: SpanStyle): Boolean {
    val text = state.annotatedString.text
    for (i in text.indices) {
        val merged = state.getSpanStyle(TextRange(i, i + 1))
        when {
            probe.fontWeight != null && merged.fontWeight == probe.fontWeight -> return true
            probe.fontStyle != null && merged.fontStyle == probe.fontStyle -> return true
            probe.textDecoration != null && merged.textDecoration?.contains(TextDecoration.Underline) == true -> return true
        }
    }
    return false
}

private fun distinctColors(state: RichTextState): Set<Color> {
    val text = state.annotatedString.text
    return text.indices.map { state.getSpanStyle(TextRange(it, it + 1)).color }
        .filter { it != Color.Unspecified }
        .toSet()
}

private fun distinctBackgrounds(state: RichTextState): Set<Color> {
    val text = state.annotatedString.text
    return text.indices.map { state.getSpanStyle(TextRange(it, it + 1)).background }
        .filter { it != Color.Unspecified && it != Color.Transparent }
        .toSet()
}

private fun distinctFontSizes(state: RichTextState): Set<Float> {
    val text = state.annotatedString.text
    return text.indices.map { state.getSpanStyle(TextRange(it, it + 1)).fontSize }
        .filter { it.isSp }
        .map { it.value }
        .toSet()
}

private fun distinctLetterSpacings(state: RichTextState): Set<Float> {
    val text = state.annotatedString.text
    return text.indices.map { state.getSpanStyle(TextRange(it, it + 1)).letterSpacing }
        .filter { it.isSp }
        .map { it.value }
        .toSet()
}

private val androidx.compose.ui.unit.TextUnit.isSp: Boolean
    get() = type == androidx.compose.ui.unit.TextUnitType.Sp

private fun distinctAligns(state: RichTextState): Set<TextAlign> {
    val text = state.annotatedString.text
    if (text.isEmpty()) return emptySet()
    val paraStarts = listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }
    return paraStarts.filter { it < text.length }
        .map { state.getParagraphStyle(TextRange(it, it + 1)).textAlign }
        .filter { it != TextAlign.Unspecified }
        .toSet()
}

private fun distinctLineHeights(state: RichTextState): Set<Float> {
    val text = state.annotatedString.text
    if (text.isEmpty()) return emptySet()
    val paraStarts = listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }
    return paraStarts.filter { it < text.length }
        .map { state.getParagraphStyle(TextRange(it, it + 1)).lineHeight }
        .filter { it.type == androidx.compose.ui.unit.TextUnitType.Em }
        .map { it.value }
        .toSet()
}

private fun buildSampleDocument(): TeleprompterDocument {
    fun chapter(subtitle: String, block: RichTextState.() -> Unit): TeleprompterChapter {
        val state = RichTextState()
        state.block()
        return TeleprompterChapter(subtitle = subtitle, contentHtml = state.toHtml())
    }

    val ch1 = chapter("开场白") {
        addTextAfterSelection("欢迎大家收看直播\n今天讲讲空调费的事")
        addSpanStyle(SpanStyle(fontWeight = FontWeight.Bold), TextRange(0, 4))
        addSpanStyle(SpanStyle(color = Color(0xFF0000FF)), TextRange(5, 9))
        addSpanStyle(SpanStyle(background = Color(0xFFFFFF00)), TextRange(10, 13))
        addSpanStyle(SpanStyle(fontSize = 28.sp), TextRange(0, 4))
        addSpanStyle(SpanStyle(letterSpacing = 2.sp), TextRange(0, 4))
        selection = TextRange(0, 4)
        addParagraphStyle(ParagraphStyle(textAlign = TextAlign.Center))
        selection = TextRange(6, 10)
        addParagraphStyle(ParagraphStyle(lineHeight = 1.8f.em))
    }

    val ch2 = chapter("正文") {
        addTextAfterSelection("斜体和下划线测试\n第二段落")
        addSpanStyle(SpanStyle(fontStyle = FontStyle.Italic), TextRange(0, 2))
        addSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline), TextRange(2, 4))
        selection = TextRange(9, 12)
        addParagraphStyle(ParagraphStyle(textAlign = TextAlign.Right))
    }

    return TeleprompterDocument(
        title = "往返测试文档",
        chapters = listOf(ch1, ch2),
        fontFamily = TeleprompterFontFamily.Serif,
    )
}
