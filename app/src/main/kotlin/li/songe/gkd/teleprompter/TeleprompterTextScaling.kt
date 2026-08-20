package li.songe.gkd.teleprompter

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified

/**
 * Read-only display transform for a chapter's parsed AnnotatedString: rescales explicit per-run
 * fontSize and per-paragraph lineHeight (both cascaded in from Word import - see
 * TeleprompterWordParser's OOXML style cascade) by the given multipliers, and optionally forces
 * every paragraph's alignment. This is display-only - it never touches contentHtml/storage, and
 * callers must feed the result to a plain Text(), never back into a RichTextState used for
 * editing (setHtml/toHtml would round-trip the scaled-up values as if they were the real ones).
 */
fun AnnotatedString.scaledForDisplay(
    sizeScale: Float,
    lineHeightScale: Float,
    forcedAlign: TextAlign?,
): AnnotatedString {
    if (sizeScale == 1f && lineHeightScale == 1f && forcedAlign == null) return this
    return AnnotatedString(
        text = text,
        spanStyles = if (sizeScale == 1f) {
            spanStyles
        } else {
            spanStyles.map { range ->
                val style = range.item
                if (style.fontSize.isSpecified) {
                    range.copy(item = style.copy(fontSize = TextUnit(style.fontSize.value * sizeScale, style.fontSize.type)))
                } else {
                    range
                }
            }
        },
        paragraphStyles = paragraphStyles.map { range ->
            var style = range.item
            if (lineHeightScale != 1f && style.lineHeight.isSpecified) {
                style = style.copy(lineHeight = TextUnit(style.lineHeight.value * lineHeightScale, style.lineHeight.type))
            }
            if (forcedAlign != null) {
                style = style.copy(textAlign = forcedAlign)
            }
            range.copy(item = style)
        },
    )
}

fun TeleprompterTextAlignMode.toComposeAlignOrNull(): TextAlign? = when (this) {
    TeleprompterTextAlignMode.FollowDocument -> null
    TeleprompterTextAlignMode.Left -> TextAlign.Left
    TeleprompterTextAlignMode.Center -> TextAlign.Center
    TeleprompterTextAlignMode.Right -> TextAlign.Right
    TeleprompterTextAlignMode.Justify -> TextAlign.Justify
}

/** Single source of truth for "which of the two stored content-width values applies right now" -
 * shared by the editor (both view and edit mode) and the player so switching orientation can
 * never leave one of the three screens on the wrong value. */
fun TeleprompterSettings.contentWidthFraction(isLandscape: Boolean): Float =
    if (isLandscape) contentWidthLandscape else contentWidthPortrait
