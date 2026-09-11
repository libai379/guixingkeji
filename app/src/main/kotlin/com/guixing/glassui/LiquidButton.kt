/*
 * Ported from com.kyant.backdrop.catalog.components.LiquidButton in
 * https://github.com/Kyant0/AndroidLiquidGlass (Apache License 2.0,
 * https://github.com/Kyant0/AndroidLiquidGlass/blob/main/LICENSE).
 *
 * This is the full port this time (finger-position/velocity press physics via
 * InteractiveHighlight, not the project's earlier plain scale-on-press simplification) - see
 * DampedDragAnimation.kt/DragGestureInspector.kt/InteractiveHighlight.kt, ported alongside it.
 *
 * Two adaptations for this project's com.kashif_e.backdrop fork (0.0.1-alpha02):
 *  - com.kyant.shapes.Capsule() -> RoundedCornerShape(50). The fork's own lens() only resolves
 *    corner radii for androidx.compose.foundation.shape.CornerBasedShape (confirmed by reading
 *    backdrop/effects/Lens.kt in the fork's source - it dropped the upstream's extra
 *    RoundedRectangularShape branch that com.kyant.shapes types implement). Passing a
 *    io.github.kyant0:shapes Capsule/RoundedRectangle straight through would make lens() throw
 *    at runtime. RoundedCornerShape(50) is a real CornerBasedShape and renders the same capsule
 *    (radius already clamped to size.minDimension/2 by the shader), so this project never took
 *    on the io.github.kyant0:shapes dependency at all - see the extended report for details.
 *  - lens(...) -> patchedLens(...) from com.guixing.glasstuning - same signature, adds the
 *    missing sample-coordinate clamp this session's investigation found in the dispersion shader
 *    (both this fork and upstream). Kept even here (button presses are near-square, low risk)
 *    so "every chromaticAberration-capable lens call in this app goes through patchedLens()"
 *    stays true without exceptions - see GlassEffects.kt/PatchedLens.kt.
 */
package com.guixing.glassui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import com.kashif_e.backdrop.Backdrop
import com.kashif_e.backdrop.drawBackdrop
import com.kashif_e.backdrop.effects.blur
import com.kashif_e.backdrop.effects.vibrancy
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh
import com.guixing.glasstuning.patchedLens

@Composable
fun LiquidButton(
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    isInteractive: Boolean = true,
    tint: Color = Color.Unspecified,
    surfaceColor: Color = Color.Unspecified,
    content: @Composable RowScope.() -> Unit,
) {
    val animationScope = rememberCoroutineScope()

    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(animationScope = animationScope)
    }

    Row(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(50) },
                effects = {
                    vibrancy()
                    blur(2f.dp.toPx())
                    patchedLens(12f.dp.toPx(), 24f.dp.toPx())
                },
                layerBlock = if (isInteractive) {
                    {
                        val width = size.width
                        val height = size.height

                        val progress = interactiveHighlight.pressProgress
                        val scale = lerp(1f, 1f + 4f.dp.toPx() / size.height, progress)

                        val maxOffset = size.minDimension
                        val initialDerivative = 0.05f
                        val offset = interactiveHighlight.offset
                        translationX = maxOffset * tanh(initialDerivative * offset.x / maxOffset)
                        translationY = maxOffset * tanh(initialDerivative * offset.y / maxOffset)

                        val maxDragScale = 4f.dp.toPx() / size.height
                        val offsetAngle = atan2(offset.y, offset.x)
                        scaleX =
                            scale +
                                maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension) *
                                (width / height).fastCoerceAtMost(1f)
                        scaleY =
                            scale +
                                maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension) *
                                (height / width).fastCoerceAtMost(1f)
                    }
                } else {
                    null
                },
                onDrawSurface = {
                    if (tint.isSpecified) {
                        drawRect(tint, blendMode = BlendMode.Hue)
                        drawRect(tint.copy(alpha = 0.75f))
                    }
                    if (surfaceColor.isSpecified) {
                        drawRect(surfaceColor)
                    }
                },
            )
            .clickable(
                interactionSource = null,
                indication = if (isInteractive) null else LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .then(
                if (isInteractive) {
                    Modifier
                        .then(interactiveHighlight.modifier)
                        .then(interactiveHighlight.gestureModifier)
                } else {
                    Modifier
                },
            )
            .height(48.dp)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
