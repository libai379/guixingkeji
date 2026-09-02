/*
 * Adapted from this project's own LiquidButton.kt (itself ported from
 * com.kyant.backdrop.catalog.components.LiquidButton, Apache License 2.0,
 * https://github.com/Kyant0/AndroidLiquidGlass/blob/main/LICENSE) for compact/dense contexts
 * LiquidButton wasn't sized for: a toolbar row with a dozen 32-40dp icon-only toggles, not a
 * handful of pill-shaped text buttons.
 *
 * Two differences from LiquidButton, both there to keep a toolbar full of these cheap:
 *  - No hardcoded height/padding - callers size this via the trailing modifier (`.size(40.dp)`
 *    etc.), same convention LiquidSlider/LiquidToggle already use, since the right size varies a
 *    lot across call sites here (a 32dp stepper button vs. a 40dp toggle vs. a text+icon pill).
 *  - Lens refraction ramps from 0 at rest up to a peak on press (driven by the same
 *    InteractiveHighlight.pressProgress that already drives the scale deformation), instead of
 *    LiquidButton's constant refraction. The library's own lens()/patchedLens() no-ops entirely
 *    when refractionHeight<=0 (see effects/Lens.kt), so an idle button costs only blur+vibrancy -
 *    no shader pass at all - and only the one actively being pressed pays for lens+dispersion.
 *    This is the same "0 at rest, ramp on press" shape LiquidSlider/LiquidToggle's thumbs already
 *    use for their press feedback, just applied to a plain button instead of a drag thumb.
 *
 * [sampleBackdrop] (2026-08-13, see docs/PITFALLS.md): the blur+lens recipe above is unconditional
 * - it runs regardless of [active], which only ever toggled the extra tint overlay on top. Fine
 * for a button sitting directly over real screen content, but every call site inside the
 * teleprompter settings window is already inside THAT window's own tunedGlass() card - sampling
 * and re-blurring the same backdrop a second time there was pure redundant cost with a visible
 * side effect (the real document text behind the panel bleeding through as a smudge on every
 * unselected/untinted button). sampleBackdrop=false skips drawBackdrop() entirely and falls back
 * to a plain tinted fill - activeTint at the same strength as the existing active-state overlay,
 * a lighter neutral fill otherwise, so the button shape/boundary stays legible either way.
 */
package li.songe.gkd.glassui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kashif_e.backdrop.Backdrop
import com.kashif_e.backdrop.drawBackdrop
import com.kashif_e.backdrop.effects.blur
import com.kashif_e.backdrop.effects.vibrancy
import li.songe.gkd.glasstuning.patchedLens

@Composable
fun LiquidCompactButton(
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    // Themed highlight for a toggled-on state (bold/italic/underline/align active, format tab
    // selected) - the glass equivalent of ToggleIconButton's old primaryContainer fill.
    active: Boolean = false,
    enabled: Boolean = true,
    // False for buttons that sit next to something else holding focus/selection (the editor's
    // format toolbar, next to a RichTextEditor mid-selection - see that call site's own pitfall
    // comment). Modifier.clickable is focusable by default; merely being tapped shifts focus onto
    // the button and away from whatever had it, which for a format command means the text
    // selection collapses before the command reaches it. Raw pointer tap detection instead has no
    // focus semantics at all, same fix this project already used once for that exact bug
    // (TeleprompterEditorScreen's noFocusClickable).
    focusable: Boolean = true,
    shape: Shape = RoundedCornerShape(50),
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp),
    // See this file's header. False for buttons already inside another glass surface (e.g. every
    // call site inside TeleprompterSettingsWindow's own tunedGlass() card).
    sampleBackdrop: Boolean = true,
    // The unselected-state fill for sampleBackdrop=false buttons (see header). Default is a
    // generic neutral tint meant for a dense list of small buttons sitting on the app's normal
    // background (TeleprompterSettingsWindow's own alignment/font/color rows). For buttons that
    // live inside ANOTHER already-tinted glass bar (top bars, the library row menu) that neutral
    // fill reads as a mismatched box around each icon instead of blending with the bar - those
    // call sites pass Color.Transparent (2026-08-15, user flagged this on-device).
    inactiveTint: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: @Composable RowScope.() -> Unit,
) {
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(animationScope = animationScope)
    }
    val activeTint = MaterialTheme.colorScheme.primaryContainer

    Row(
        modifier
            .then(
                if (sampleBackdrop) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { shape },
                        effects = {
                            val progress = interactiveHighlight.pressProgress
                            vibrancy()
                            blur(1f.dp.toPx())
                            patchedLens(
                                16f.dp.toPx() * progress,
                                28f.dp.toPx() * progress,
                                // 2026-09-02 - 默认外观整体改成"朴素白底、不透明、色散全关"，这三个
                            // 液态组件（按钮/滑块/开关）把色散写死在自己实现里、不走 GlassParams，
                            // 所以要在这里单独关，否则"全部关掉"是漏的。
                            chromaticAberration = false,
                            )
                        },
                        layerBlock = {
                            val progress = interactiveHighlight.pressProgress
                            val s = 1f - 0.06f * progress
                            scaleX = s
                            scaleY = s
                        },
                        onDrawSurface = if (active) {
                            { drawRect(activeTint.copy(alpha = 0.55f)) }
                        } else {
                            null
                        },
                    )
                } else {
                    Modifier
                        .graphicsLayer {
                            val progress = interactiveHighlight.pressProgress
                            val s = 1f - 0.06f * progress
                            scaleX = s
                            scaleY = s
                        }
                        .clip(shape)
                        .background(
                            if (active) {
                                activeTint.copy(alpha = 0.55f)
                            } else {
                                // Scale inactiveTint's OWN alpha rather than overwriting it
                                // (2026-08-15 real bug, confirmed on-device via screenshot):
                                // Color.copy(alpha=X) sets the alpha channel outright regardless
                                // of the original value, so Color.Transparent.copy(alpha=0.5f)
                                // is NOT transparent - it's black at 50% opacity (Transparent's
                                // R/G/B channels are all 0). Every call site that passes
                                // Color.Transparent to blend into its parent's own glass surface
                                // was rendering a solid dark pill instead.
                                inactiveTint.copy(alpha = inactiveTint.alpha * 0.5f)
                            },
                        )
                },
            )
            .then(
                if (focusable) {
                    Modifier.clickable(
                        interactionSource = null,
                        indication = null,
                        enabled = enabled,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier.pointerInput(enabled, onClick) {
                        if (enabled) {
                            detectTapGestures(onTap = { onClick() })
                        }
                    }
                },
            )
            .then(interactiveHighlight.modifier)
            .then(interactiveHighlight.gestureModifier)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** [LiquidCompactButton] pre-sized/padded for a single centered icon - the common case
 * (ToggleIconButton/StepperButton's old shape) so call sites don't repeat the size+padding+
 * contentAlignment boilerplate. */
@Composable
fun LiquidCompactIconButton(
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    focusable: Boolean = true,
    size: Dp = 40.dp,
    sampleBackdrop: Boolean = true,
    inactiveTint: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: @Composable RowScope.() -> Unit,
) {
    LiquidCompactButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier.size(size),
        active = active,
        enabled = enabled,
        focusable = focusable,
        contentPadding = PaddingValues(0.dp),
        sampleBackdrop = sampleBackdrop,
        inactiveTint = inactiveTint,
        content = content,
    )
}
