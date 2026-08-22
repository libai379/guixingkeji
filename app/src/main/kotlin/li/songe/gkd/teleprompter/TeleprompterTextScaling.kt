package li.songe.gkd.teleprompter

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType

import androidx.compose.ui.unit.isSpecified

/**
 * Read-only display transform for a chapter's parsed AnnotatedString: rescales explicit per-run
 * fontSize and per-paragraph lineHeight (both cascaded in from Word import - see
 * TeleprompterWordParser's OOXML style cascade) by the given multipliers, and optionally forces
 * every paragraph's alignment. This is display-only - it never touches contentHtml/storage, and
 * callers must feed the result to a plain Text(), never back into a RichTextState used for
 * editing (setHtml/toHtml would round-trip the scaled-up values as if they were the real ones).
 *
 * ── 2026-08-22：行距不再挂在"正文默认字号"上（方案 A）──────────────────────────────
 *
 * 修的 bug：行距原本一律由 `正文默认字号 × 行距倍数` 算出。但"正文默认字号"只对**没有**显式字号
 * 的文字生效，Word 导入的内容每个 run 都自带字号、根本不用它——于是出现了"拖动正文字号滑块，字
 * 大小纹丝不动，行距却在变"这个现象（用户真机复现）。字号那一路被显式值正确覆盖了，行距那一路
 * 没有，跟着一个没人在用的字号在动。
 *
 * 为什么不能做到逐行精确：行距在 Compose 里只存在于 ParagraphStyle，是**整段一个值**；SpanStyle
 * 根本没有 lineHeight 字段（已用 javap 核对）。所以"这一行行距跟着这一行最大字号走"没有 API 能
 * 表达，换个富文本库也一样。Compose 也没有传统 View 那个 setLineSpacing 的乘数参数，即"自然行高
 * 再乘个倍数"这种能力。
 *
 * 方案 A 的取舍（用户 2026-08-22 拍板）：
 * - 倍数 == 1.0（默认值）：**完全不设 lineHeight**，交给系统按每行实际字形度量排版。这个状态下
 *   是逐行精确的，没有任何近似。绝大多数使用时间都停在这里。
 * - 倍数 != 1.0：拿**这一段内出现过的最大显式字号**乘以倍数，作为整段的 lineHeight。段内混排多种
 *   字号时这是个近似（小字那几行会比理论值空一点），但取最大值能保证大字那几行不会被压得重叠。
 *
 * 段落本身带显式行距的（Word 里设过行距的段落），仍然沿用"在原行距基础上乘倍数"，不受上面影响。
 * 完全没有显式字号的段落（原生新建的文稿）取 [fallbackFontSizeSp] 兜底，也就是"正文默认字号"——
 * 这条路径必须保住，否则原生文稿的行距就不跟设置走了。
 */
fun AnnotatedString.scaledForDisplay(
    sizeScale: Float,
    lineHeightScale: Float,
    forcedAlign: TextAlign?,
    /** 段内一个显式字号都没有时的兜底值，应传入"正文默认字号 × 各级缩放"后的最终显示字号。 */
    fallbackFontSizeSp: Float,
): AnnotatedString {
    if (sizeScale == 1f && lineHeightScale == 1f && forcedAlign == null) return this

    val scaledSpans = if (sizeScale == 1f) {
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
    }

    return AnnotatedString(
        text = text,
        spanStyles = scaledSpans,
        paragraphStyles = paragraphStyles.map { range ->
            var style = range.item
            if (lineHeightScale != 1f) {
                if (style.lineHeight.isSpecified) {
                    // 段落自己带行距（Word 里设过）：保持"在原行距基础上再乘倍数"的老行为。
                    style = style.copy(
                        lineHeight = TextUnit(style.lineHeight.value * lineHeightScale, style.lineHeight.type),
                    )
                } else {
                    // 段落没带行距：用这一段实际用到的最大字号算，而不是全局的"正文默认字号"。
                    // 注意 scaledSpans 已经乘过 sizeScale，所以这里取到的就是最终显示尺寸。
                    val maxSp = scaledSpans.maxExplicitFontSizeSpIn(range.start, range.end)
                        ?: fallbackFontSizeSp
                    style = style.copy(lineHeight = TextUnit(maxSp * lineHeightScale, TextUnitType.Sp))
                }
            }
            // 倍数 == 1.0 时这里一个字都不动：段落原本没行距就继续没有，交给系统逐行自然排版。
            if (forcedAlign != null) {
                style = style.copy(textAlign = forcedAlign)
            }
            range.copy(item = style)
        },
    )
}

/**
 * 取 [start, end) 这段范围内所有显式 sp 字号的最大值；一个都没有就返回 null（调用方用兜底值）。
 * 只认 sp：em 之类的相对单位没有绝对尺寸，拿来算行距没有意义。
 */
private fun List<AnnotatedString.Range<androidx.compose.ui.text.SpanStyle>>.maxExplicitFontSizeSpIn(
    start: Int,
    end: Int,
): Float? {
    var max: Float? = null
    forEach { r ->
        // 有交集就算数——一个 run 哪怕只有一部分落在这段里，它的字形也会撑高这一段的行。
        if (r.start < end && r.end > start) {
            val fs = r.item.fontSize
            if (fs.isSpecified && fs.type == TextUnitType.Sp) {
                val v = fs.value
                if (max == null || v > max!!) max = v
            }
        }
    }
    return max
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
