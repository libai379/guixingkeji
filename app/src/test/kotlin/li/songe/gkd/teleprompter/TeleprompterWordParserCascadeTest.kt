package li.songe.gkd.teleprompter

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import com.mohamedrejeb.richeditor.model.RichTextState
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the OOXML style-cascade rewrite, built from a REAL WPS-generated document
 * ("多圈沉香话术.docx", captured via the app's own diagnostic XML dump - see
 * TeleprompterWordImport.kt's always-on docx-import-*.xml dump). This document's formatting is
 * carried entirely through style references (pStyle/rStyle), not direct run-level rPr - the exact
 * gap the previous single-layer parser silently dropped everything into. See
 * app/src/test/resources/docx-samples/duoquan-*.xml for the untouched source.
 */
class TeleprompterWordParserCascadeTest {

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("docx-samples/$name")) {
            "missing test resource: $name"
        }.bufferedReader(Charsets.UTF_8).readText()

    @Test
    fun cascadeResolvesRealWpsDocumentFormatting() {
        val documentXml = resource("duoquan-document.xml")
        val stylesXml = resource("duoquan-styles.xml")

        val summary = parseDocxDocument(documentXml, stylesXml, null, "多圈沉香话术")
        val fullHtml = summary.chapters.joinToString("") { it.contentHtml }
        val state = RichTextState().apply { setHtml(fullHtml) }
        val text = state.annotatedString.text

        // getSpanStyle() returns the library's own MERGED style at a position (folding every
        // overlapping SpanStyle entry together) - reading raw spanStyles entries directly is
        // unreliable here since addSpanStyle is called once per property, so a single character
        // can be covered by several separate single-property entries (plus the library's own
        // auto-generated anchor entries) rather than one pre-merged SpanStyle per range.
        fun styleAt(needle: String) = text.indexOf(needle).let { idx ->
            check(idx >= 0) { "'$needle' not found in parsed text: $text" }
            state.getSpanStyle(TextRange(idx, idx + 1))
        }

        // Criterion 1: "传统典籍" and "不聊玄学，单说审美和原理：" both carry rStyle="8" in the
        // real XML (标题4 Char: <w:b/> + sz=28) - must come through bold.
        assertTrue("'传统典籍' must be bold (rStyle=8 in the real XML)", styleAt("传统典籍").fontWeight == FontWeight.Bold)
        assertTrue("'不聊玄学' must be bold (rStyle=8 in the real XML)", styleAt("不聊玄学").fontWeight == FontWeight.Bold)

        // Criterion 2: on the "男士佩戴：儒雅低调 沉稳内敛有底蕴" line, "男士" and "儒雅低调" carry
        // rStyle=8 (bold, 14pt) while "佩戴：" and " 沉稳内敛有底蕴" have no rStyle at all (plain
        // body text, 10.5pt via the Normal/default paragraph style) - a real big/small contrast
        // within one line, not a uniform style.
        val boldStyle = styleAt("男士")
        // "佩戴" alone also matches inside an unrelated earlier heading ("佩戴效果：", real 16px
        // bold text elsewhere in this document) - "佩戴：" with the colon is unique to this line.
        val plainStyle = styleAt("佩戴：")
        assertTrue("'男士' must be bold", boldStyle.fontWeight == FontWeight.Bold)
        assertTrue(
            "'男士' (rStyle=8, sz=28 -> 14sp) must be a larger font than '佩戴' (Normal, sz=21 -> 10.5sp): " +
                "got bold=${boldStyle.fontSize} plain=${plainStyle.fontSize}",
            boldStyle.fontSize.value > plainStyle.fontSize.value,
        )

        // Criterion 3: "108寓意：" uses pStyle=2 (heading 1, outlineLvl=0) in the real XML - same
        // level as the document's one true top-level heading, so it becomes its own chapter
        // subtitle rather than inline text. Sanity-check it exists as a subtitle somewhere.
        assertTrue(
            "'108寓意：' should surface as a chapter subtitle (pStyle=2, same outline level as the doc's heading)",
            summary.chapters.any { it.subtitle.contains("108寓意") },
        )
    }
}
