package li.songe.gkd.teleprompter

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.mohamedrejeb.richeditor.model.RichTextState
import org.junit.Test
import java.io.File

/**
 * Step 0 of the teleprompter formatting-expansion spec: verify which SpanStyle/ParagraphStyle
 * attributes survive a toHtml() -> setHtml() round trip *before* building any UI on top of them.
 * Writes a plain-text report to build/round_trip_report.txt (Gradle swallows test stdout by
 * default, a file survives regardless of --info/-q).
 */
class RichTextRoundTripTest {

    @Test
    fun checkAttributeRoundTrip() {
        val report = StringBuilder()
        fun log(line: String) {
            report.appendLine(line)
        }

        val text = "Hello World"
        val range = TextRange(0, text.length)

        val original = RichTextState()
        original.addTextAfterSelection(text)
        original.selection = range
        original.addSpanStyle(
            SpanStyle(
                letterSpacing = 3.sp,
                background = Color(0xFFFFFF00),
                fontFamily = FontFamily.Serif,
            )
        )
        original.addParagraphStyle(
            ParagraphStyle(
                lineHeight = 30.sp,
                textAlign = TextAlign.Center,
            )
        )

        val beforeSpan = original.getSpanStyle(range)
        val beforeParagraph = original.getParagraphStyle(range)
        log("=== before round trip ===")
        log("span: letterSpacing=${beforeSpan.letterSpacing}, background=${beforeSpan.background}, fontFamily=${beforeSpan.fontFamily}")
        log("paragraph: lineHeight=${beforeParagraph.lineHeight}, textAlign=${beforeParagraph.textAlign}")

        val html = original.toHtml()
        log("")
        log("=== html ===")
        log(html)

        val restored = RichTextState()
        restored.setHtml(html)
        val restoredRange = TextRange(0, minOf(text.length, restored.annotatedString.text.length))
        val afterSpan = restored.getSpanStyle(restoredRange)
        val afterParagraph = restored.getParagraphStyle(restoredRange)
        log("")
        log("=== after round trip (restored text=\"${restored.annotatedString.text}\") ===")
        log("span: letterSpacing=${afterSpan.letterSpacing}, background=${afterSpan.background}, fontFamily=${afterSpan.fontFamily}")
        log("paragraph: lineHeight=${afterParagraph.lineHeight}, textAlign=${afterParagraph.textAlign}")

        log("")
        log("=== verdict ===")
        val results = linkedMapOf(
            "letterSpacing" to (beforeSpan.letterSpacing == afterSpan.letterSpacing),
            "background(highlight)" to (beforeSpan.background == afterSpan.background),
            "fontFamily" to (beforeSpan.fontFamily == afterSpan.fontFamily),
            "lineHeight" to (beforeParagraph.lineHeight == afterParagraph.lineHeight),
            "textAlign" to (beforeParagraph.textAlign == afterParagraph.textAlign),
        )
        results.forEach { (name, survived) ->
            log("$name: ${if (survived) "SURVIVED" else "LOST"}")
        }

        val reportText = report.toString()
        println(reportText)
        File("build/round_trip_report.txt").apply {
            parentFile?.mkdirs()
        }.writeText(reportText)
    }
}
