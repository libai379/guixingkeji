package li.songe.gkd.teleprompter

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import com.mohamedrejeb.richeditor.model.RichTextState

// Manual chapter restructuring (Word-import heading detection can't ever be 100% right, and
// this is needed for everyday document organization regardless - see the two long-press menus
// this backs in TeleprompterEditorScreen/TeleprompterOutlinePanel). All merging/splitting goes
// through RichTextState's real span/paragraph-style API instead of concatenating raw HTML
// strings, per JVM probes (not shipped) that confirmed several non-obvious behaviors:
//  - AnnotatedString.paragraphStyles always fully partitions the text into contiguous,
//    gap-free paragraph ranges, even when no explicit style was ever applied - true for both
//    typed text and setHtml-hydrated text, so it's a reliable way to find paragraph boundaries.
//  - a literal '\n' passed to addTextAfterSelection is stored as a plain space in
//    annotatedString.text afterwards (paragraph breaks are tracked structurally, not via the
//    character) - BUT the '\n' character is still what *creates* the paragraph break at
//    insertion time. A first version of this file extracted `annotatedString.text` (already
//    flattened, no '\n' left in it) and inserted it as one addTextAfterSelection call: with no
//    '\n' to signal a break, every paragraph silently fused into a single one on merge/split -
//    this is the bug a real user hit ("整段内容都消失了"). The fix below re-inserts one '\n'
//    between each source paragraph so real breaks are recreated, not just carried over.
//  - addTextAfterSelection inserts at `selection`'s current position, not at the text's true
//    end - a second, compounding bug in the same original version: the paragraph-style loop
//    left `selection` parked on whatever range it last styled, and the next
//    addTextAfterSelection call (e.g. for the demoted subtitle) spliced into THAT spot instead
//    of the real end. Selection is now explicitly collapsed to the end after every append.
//  - AnnotatedString.subSequence() correctly clips+reoffsets both spanStyles and paragraphStyles.

private fun htmlToAnnotated(html: String): AnnotatedString =
    if (html.isBlank()) AnnotatedString("") else RichTextState().apply { setHtml(html) }.annotatedString

private class ParagraphFragment(
    val text: String,
    val spans: List<Pair<IntRange, SpanStyle>>,
    val paragraphStyle: ParagraphStyle?,
)

/** Breaks [this] into one fragment per real paragraph (see [paragraphRanges]), each carrying its
 * own text, the spans that overlap it (re-offset to start at 0), and its ParagraphStyle if any. */
private fun AnnotatedString.toParagraphFragments(): List<ParagraphFragment> =
    paragraphRanges().map { range ->
        val fragmentText = text.substring(range.first, range.last + 1)
        val spans = spanStyles.mapNotNull { span ->
            val s = maxOf(span.start, range.first)
            val e = minOf(span.end, range.last + 1)
            if (s < e) (s - range.first until e - range.first) to span.item else null
        }
        val paragraphStyle = paragraphStyles.firstOrNull { it.start <= range.first && range.last < it.end }?.item
        ParagraphFragment(fragmentText, spans, paragraphStyle)
    }

/** Appends [fragments] onto the end of [this] as real, separate paragraphs (an explicit '\n' is
 * inserted between fragments to actually create each break, not just to carry styling across a
 * fused run of text), applying each fragment's spans/paragraph style by explicit range.
 * addParagraphStyle has no range overload, hence the selection dance - same pattern
 * buildChapterHtml uses when building a chapter from scratch. */
private fun RichTextState.appendParagraphs(fragments: List<ParagraphFragment>) {
    for (fragment in fragments) {
        if (annotatedString.text.isNotEmpty()) addTextAfterSelection("\n")
        val insertStart = annotatedString.text.length
        addTextAfterSelection(fragment.text)
        for ((range, style) in fragment.spans) {
            addSpanStyle(style, TextRange(insertStart + range.first, insertStart + range.last + 1))
        }
        fragment.paragraphStyle?.let { style ->
            selection = TextRange(insertStart, insertStart + fragment.text.length)
            addParagraphStyle(style)
        }
    }
    val end = annotatedString.text.length
    selection = TextRange(end, end)
}

private fun annotatedToHtml(annotated: AnnotatedString): String {
    val fragments = annotated.toParagraphFragments()
    if (fragments.isEmpty()) return ""
    return RichTextState().apply { appendParagraphs(fragments) }.toHtml()
}

/** buildChapterHtml unconditionally appends a trailing '\n' after every paragraph including the
 * last one, which RichTextState turns into a phantom empty trailing paragraph - and that phantom
 * paragraph does NOT round-trip cleanly through toHtml()/setHtml() (verified via a throwaway
 * probe: it grows into extra stray-whitespace paragraphs on each cycle instead of staying put).
 * Every real docx-imported chapter carries this artifact, and merge/split below round-trip
 * through HTML themselves, so left unfiltered it would compound with each edit and pollute the
 * long-press-to-promote paragraph list with junk entries. Trim trailing blank paragraphs (down to
 * a minimum of one) so callers only ever see real content.
 */
private fun AnnotatedString.paragraphRanges(): List<IntRange> {
    val raw = when {
        paragraphStyles.isNotEmpty() -> paragraphStyles.sortedBy { it.start }.map { it.start until it.end }
        text.isNotEmpty() -> listOf(0 until text.length)
        else -> emptyList()
    }
    val result = raw.toMutableList()
    while (result.size > 1 && text.substring(result.last().first, result.last().last + 1).isBlank()) {
        result.removeAt(result.lastIndex)
    }
    return result
}

/** How many paragraphs [html] has - the long-press-to-promote UI uses this range to map a tap
 * offset to a paragraph index (see [paragraphIndexForCharIndex]). */
fun countParagraphs(html: String): Int = htmlToAnnotated(html).paragraphRanges().size

/** Which paragraph (0-based) a plain-text character offset falls into, for turning a tap
 * position into a [splitChapterAtParagraph] argument. Clamped to the last paragraph if [charIndex]
 * is past the end. */
fun paragraphIndexForCharIndex(html: String, charIndex: Int): Int {
    val ranges = htmlToAnnotated(html).paragraphRanges()
    val idx = ranges.indexOfFirst { charIndex < it.last + 1 }
    return if (idx >= 0) idx else (ranges.size - 1).coerceAtLeast(0)
}

/**
 * "降级为正文" (heading long-press) and the outline panel's "并入上一章" are the same operation
 * from two entry points: [current]'s subtitle becomes a plain-text paragraph at the top of its
 * own body, and that whole block is appended after [prev]'s existing body. Returns the new
 * content HTML for [prev]; the caller is responsible for dropping [current] from the chapter list.
 */
fun mergeChapterIntoPrevious(prev: TeleprompterChapter, current: TeleprompterChapter): String {
    val fragments = mutableListOf<ParagraphFragment>()
    fragments += htmlToAnnotated(prev.contentHtml).toParagraphFragments()
    val demotedSubtitle = current.subtitle.trim()
    if (demotedSubtitle.isNotEmpty()) {
        fragments += ParagraphFragment(demotedSubtitle, emptyList(), null)
    }
    fragments += htmlToAnnotated(current.contentHtml).toParagraphFragments()
    if (fragments.isEmpty()) return ""
    return RichTextState().apply { appendParagraphs(fragments) }.toHtml()
}

/**
 * "提升为副标题" (body-paragraph long-press): splits [chapter] at paragraph [paragraphIndex]
 * (0-based). That paragraph's plain text becomes the new chapter's subtitle - necessarily losing
 * any rich formatting it had as body text, since subtitle is a plain String field with no
 * formatting of its own, same as every other subtitle in this app. Everything before the
 * paragraph stays in [chapter]; everything after becomes the new chapter's body.
 * Returns null if [paragraphIndex] is out of range (e.g. the chapter is empty).
 */
fun splitChapterAtParagraph(
    chapter: TeleprompterChapter,
    paragraphIndex: Int,
): Pair<TeleprompterChapter, TeleprompterChapter>? {
    val annotated = htmlToAnnotated(chapter.contentHtml)
    val ranges = annotated.paragraphRanges()
    if (paragraphIndex !in ranges.indices) return null

    val target = ranges[paragraphIndex]
    val beforeHtml = if (target.first > 0) annotatedToHtml(annotated.subSequence(0, target.first)) else ""
    val newSubtitle = annotated.text.substring(target.first, target.last + 1).trim().ifBlank { "新章节" }
    val afterHtml = if (target.last + 1 < annotated.text.length) {
        annotatedToHtml(annotated.subSequence(target.last + 1, annotated.text.length))
    } else {
        ""
    }

    val updatedOriginal = chapter.copy(contentHtml = beforeHtml)
    val newChapter = TeleprompterChapter(subtitle = newSubtitle, contentHtml = afterHtml)
    return updatedOriginal to newChapter
}
