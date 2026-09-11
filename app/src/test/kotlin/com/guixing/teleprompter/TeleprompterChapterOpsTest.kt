package com.guixing.teleprompter

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.mohamedrejeb.richeditor.model.RichTextState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fix B (manual chapter restructuring) regression coverage: merge/split must preserve rich
 * formatting through RichTextState's real API, not just plain text. */
class TeleprompterChapterOpsTest {

    // Mirrors buildChapterHtml's real approach: assemble the full text with embedded '\n' in one
    // addTextAfterSelection call, then apply spans by absolute range - calling
    // addTextAfterSelection separately per paragraph (rather than once with embedded '\n')
    // doesn't reflect how production code builds chapter HTML and was observed (throwaway probe)
    // to produce extra/duplicate paragraph splits.
    private fun htmlOf(vararg paragraphs: Pair<String, SpanStyle?>): String {
        val state = RichTextState()
        val fullText = StringBuilder()
        val spans = mutableListOf<Pair<IntRange, SpanStyle>>()
        for ((text, style) in paragraphs) {
            val start = fullText.length
            fullText.append(text)
            if (style != null) spans.add(start until fullText.length to style)
            fullText.append('\n')
        }
        state.addTextAfterSelection(fullText.toString())
        for ((range, style) in spans) {
            state.addSpanStyle(style, TextRange(range.first, range.last + 1))
        }
        return state.toHtml()
    }

    @Test
    fun mergePreservesFormattingAndOrder() {
        val prevHtml = htmlOf("Hello" to SpanStyle(fontWeight = FontWeight.Bold))
        val current = TeleprompterChapter(subtitle = "MidTitle", contentHtml = htmlOf("World" to SpanStyle(fontStyle = FontStyle.Italic)))
        val prev = TeleprompterChapter(subtitle = "Prev", contentHtml = prevHtml)

        val mergedHtml = mergeChapterIntoPrevious(prev, current)
        val merged = RichTextState().apply { setHtml(mergedHtml) }

        val text = merged.annotatedString.text
        val helloIdx = text.indexOf("Hello")
        val midIdx = text.indexOf("MidTitle")
        val worldIdx = text.indexOf("World")
        assertTrue("Hello should come before MidTitle", helloIdx < midIdx)
        assertTrue("MidTitle should come before World", midIdx < worldIdx)

        val helloSpan = merged.annotatedString.spanStyles.firstOrNull {
            it.start <= helloIdx && helloIdx < it.end && it.item.fontWeight == FontWeight.Bold
        }
        assertTrue("bold formatting on 'Hello' must survive the merge", helloSpan != null)

        val worldSpan = merged.annotatedString.spanStyles.firstOrNull {
            it.start <= worldIdx && worldIdx < it.end && it.item.fontStyle == FontStyle.Italic
        }
        assertTrue("italic formatting on 'World' must survive the merge", worldSpan != null)
    }

    @Test
    fun mergeWithMultiParagraphPrevKeepsAllParagraphs() {
        // Regression: appendRich's paragraph-style loop used to leave `selection` parked on
        // whatever paragraph range it last applied a ParagraphStyle to, instead of the true end
        // of the appended text. The next addTextAfterSelection call (for the demoted subtitle)
        // then inserted relative to that stale selection instead of the real end - on a
        // single-paragraph prev this accidentally still landed at the end (last paragraph's end
        // IS the text's end), which is why it slipped past the original single-paragraph test.
        // A real chapter almost always has more than one paragraph, and that's exactly where a
        // real user hit it: reported as "整段内容都消失了" (a whole paragraph vanished).
        val prevHtml = htmlOf(
            "ParaOne" to SpanStyle(fontWeight = FontWeight.Bold),
            "ParaTwo" to SpanStyle(fontStyle = FontStyle.Italic),
        )
        val prev = TeleprompterChapter(subtitle = "Prev", contentHtml = prevHtml)
        val current = TeleprompterChapter(subtitle = "MidTitle", contentHtml = htmlOf("World" to null))

        val mergedHtml = mergeChapterIntoPrevious(prev, current)
        val merged = RichTextState().apply { setHtml(mergedHtml) }
        val text = merged.annotatedString.text

        assertTrue("ParaOne must survive", text.contains("ParaOne"))
        assertTrue("ParaTwo must survive", text.contains("ParaTwo"))
        assertTrue("MidTitle must survive", text.contains("MidTitle"))
        assertTrue("World must survive", text.contains("World"))
        val order = listOf("ParaOne", "ParaTwo", "MidTitle", "World").map { text.indexOf(it) }
        assertEquals("all four fragments must appear in this order", order.sorted(), order)
        // The actual bug: everything silently fused into ONE <p> instead of staying as four
        // separate paragraphs - checking substring presence/order alone (as the very similar
        // mergePreservesFormattingAndOrder test above does) does NOT catch this.
        assertEquals(4, countParagraphs(mergedHtml))
    }

    @Test
    fun mergeSkipsBlankSubtitle() {
        val prev = TeleprompterChapter(subtitle = "Prev", contentHtml = htmlOf("Hello" to null))
        val current = TeleprompterChapter(subtitle = "   ", contentHtml = htmlOf("World" to null))
        val mergedHtml = mergeChapterIntoPrevious(prev, current)
        val merged = RichTextState().apply { setHtml(mergedHtml) }
        // Each paragraph's own trailing glue space (invisible in the real UI, which renders
        // paragraph breaks structurally, not via this character) plus the new explicit
        // separator between fragments can produce a double space here - collapse before
        // comparing since only the actual words matter for this assertion.
        assertEquals("Hello World", merged.annotatedString.text.trim().replace(Regex(" +"), " "))
    }

    @Test
    fun splitPreservesFormattingOnBothSides() {
        val html = htmlOf(
            "First" to SpanStyle(fontWeight = FontWeight.Bold),
            "Second" to null,
            "Third" to SpanStyle(fontStyle = FontStyle.Italic),
        )
        val chapter = TeleprompterChapter(subtitle = "Original", contentHtml = html)

        val (before, after) = splitChapterAtParagraph(chapter, 1)!!

        assertEquals("Second", after.subtitle)

        val beforeState = RichTextState().apply { setHtml(before.contentHtml) }
        assertEquals("First", beforeState.annotatedString.text.trim())
        assertTrue(beforeState.annotatedString.spanStyles.any { it.item.fontWeight == FontWeight.Bold })

        val afterState = RichTextState().apply { setHtml(after.contentHtml) }
        assertEquals("Third", afterState.annotatedString.text.trim())
        assertTrue(afterState.annotatedString.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun splitKeepsMultipleParagraphsSeparateOnEachSide() {
        // Regression companion to mergeWithMultiParagraphPrevKeepsAllParagraphs: subSequence()
        // slices used for the "before"/"after" halves must retain real paragraph boundaries too,
        // not just correct text/spans.
        val html = htmlOf("A1" to null, "A2" to null, "Mid" to null, "B1" to null, "B2" to null)
        val chapter = TeleprompterChapter(subtitle = "Original", contentHtml = html)

        val (before, after) = splitChapterAtParagraph(chapter, 2)!!

        assertEquals("Mid", after.subtitle)
        assertEquals(2, countParagraphs(before.contentHtml))
        assertEquals(2, countParagraphs(after.contentHtml))

        val beforeState = RichTextState().apply { setHtml(before.contentHtml) }
        assertEquals("A1 A2", beforeState.annotatedString.text.trim().replace(Regex(" +"), " "))
        val afterState = RichTextState().apply { setHtml(after.contentHtml) }
        assertEquals("B1 B2", afterState.annotatedString.text.trim().replace(Regex(" +"), " "))
    }

    @Test
    fun splitAtFirstParagraphLeavesOriginalEmpty() {
        val html = htmlOf("Only" to null, "Rest" to null)
        val chapter = TeleprompterChapter(subtitle = "Original", contentHtml = html)

        val (before, after) = splitChapterAtParagraph(chapter, 0)!!

        assertEquals("", before.contentHtml)
        assertEquals("Only", after.subtitle)
        val afterState = RichTextState().apply { setHtml(after.contentHtml) }
        assertEquals("Rest", afterState.annotatedString.text.trim())
    }

    @Test
    fun splitOutOfRangeReturnsNull() {
        val chapter = TeleprompterChapter(subtitle = "Original", contentHtml = htmlOf("Only" to null))
        assertNull(splitChapterAtParagraph(chapter, 5))
    }

    @Test
    fun paragraphIndexForCharIndexMapsCorrectly() {
        val html = htmlOf("AAAA" to null, "BBBB" to null, "CCCC" to null)
        assertEquals(0, paragraphIndexForCharIndex(html, 0))
        assertEquals(1, paragraphIndexForCharIndex(html, 6))
        assertEquals(2, paragraphIndexForCharIndex(html, 13))
        assertEquals(3, countParagraphs(html))
    }
}
