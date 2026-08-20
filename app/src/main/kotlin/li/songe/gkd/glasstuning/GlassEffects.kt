package li.songe.gkd.glasstuning

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import com.kashif_e.backdrop.Backdrop
import com.kashif_e.backdrop.BackdropEffectScope
import com.kashif_e.backdrop.backdrops.LayerBackdrop
import com.kashif_e.backdrop.drawBackdrop
import com.kashif_e.backdrop.effects.blur
import com.kashif_e.backdrop.effects.vibrancy
import com.kashif_e.backdrop.highlight.Highlight
import com.kashif_e.backdrop.highlight.HighlightStyle
import com.kashif_e.backdrop.shadow.InnerShadow
import com.kashif_e.backdrop.shadow.Shadow

/**
 * Single conversion path from a tuned [GlassParams] to the real backdrop library calls - shared
 * by the tuning playground's own preview (GlassTuningActivity) and every real glass surface in
 * the app, so they can never quietly drift apart from what was actually tuned/exported.
 */
fun GlassHighlightParams.toHighlight(): Highlight {
    val color = Color(colorArgb.toInt())
    val resolvedStyle: HighlightStyle = when (this.style) {
        HighlightStyleKind.Ambient -> HighlightStyle.Ambient(intensity = ambientIntensity)
        HighlightStyleKind.Default -> HighlightStyle.Default(color = color, angle = angleDeg, falloff = falloff)
        HighlightStyleKind.Plain -> HighlightStyle.Plain(color = color)
    }
    return Highlight(width = widthDp.dp, blurRadius = blurRadiusDp.dp, alpha = alpha, style = resolvedStyle)
}

fun GlassShadowParams.toShadow(): Shadow = Shadow(
    radius = radiusDp.dp,
    offset = DpOffset(offsetXDp.dp, offsetYDp.dp),
    color = Color(colorArgb.toInt()),
    alpha = alpha,
)

fun GlassInnerShadowParams.toInnerShadow(): InnerShadow = InnerShadow(
    radius = radiusDp.dp,
    offset = DpOffset(offsetXDp.dp, offsetYDp.dp),
    color = Color(colorArgb.toInt()),
    alpha = alpha,
)

/** Draws the live [liveParams] value's tint (there's no dedicated tint() effect in the library -
 * this is the same onDrawSurface + BlendMode.Hue pattern the catalog's LiquidButton uses)
 * followed by whatever the caller itself wants to paint on the surface (e.g. LauncherCard's
 * accent gradient). Reads [liveParams]/[liveOnDrawSurface] INSIDE the returned draw-phase lambda
 * (not a plain value closed over from composition) - see [tunedGlass]'s own doc comment for why
 * this specific shape matters, not just style. */
private fun liveCombinedOnDrawSurface(
    liveParams: State<GlassParams>,
    liveOnDrawSurface: State<(DrawScope.() -> Unit)?>,
): DrawScope.() -> Unit = {
    val tint = liveParams.value.tint
    if (tint.enabled) {
        val c = Color(tint.colorArgb.toInt())
        // Bug fixed 2026-08-13: this first drawRect had no alpha of its own, so it drew at
        // colorArgb's own (usually fully opaque) alpha unconditionally whenever tint.enabled
        // was true - completely ignoring tint.alpha. Every real GlassParams shipped so far had
        // tint.enabled=false, so this path had never actually been exercised on-device until
        // the teleprompter settings window's live opacity slider hit it: dragging toward 0%
        // never fully cleared the tint (this full-strength Hue pass kept firing for any
        // nonzero alpha, since it isn't gated on alpha at all, only on enabled), and 100%
        // never looked fully solid (the Hue pass desaturates rather than covers, so alone it
        // can't produce full white even before the second draw's alpha is considered).
        drawRect(c, alpha = tint.alpha, blendMode = BlendMode.Hue)
        drawRect(c.copy(alpha = tint.alpha))
    }
    liveOnDrawSurface.value?.invoke(this)
}

/** The one place every real glass surface in the app should route through - keeps them in sync
 * with whatever GlassTuningActivity has tuned/exported for [params]. [cornerRadius] defaults to
 * [GlassParams.cornerRadiusDp] but can be overridden (e.g. a capsule-shaped control still wants
 * the tuned blur/lens/shadow, just not this corner radius); pass [shape] instead when the corner
 * radius needs to be asymmetric (e.g. a screen-edge-docked drawer) - it wins over [cornerRadius]
 * when non-null.
 *
 * `@Composable` + [rememberUpdatedState] (2026-08-15, see docs/PITFALLS.md's "tunedGlass only
 * redraws during gesture/animation" entry): every value [params] carries used to be closed over
 * as a plain, frozen Kotlin value - correct at the moment this Modifier chain was (re)built
 * during composition, but never read again after that inside the actual draw-phase closures
 * `drawBackdrop()` invokes. Compose's snapshot system has nothing to observe in a plain frozen
 * value, so nothing told the draw phase to run again when e.g. a glass preset got applied or a
 * settings slider committed its final value with no gesture actively in flight - real-device
 * logging confirmed the draw closure only re-ran while some OTHER animation (a slider's own drag/
 * press animation) happened to also be forcing a redraw, then froze on whatever it last drew.
 * Reading `live*.value` INSIDE the draw-phase closures instead makes each of those reads a real
 * Snapshot State read made during the draw call, which is what actually gives Compose something
 * to subscribe to and correctly re-trigger just that draw the normal way - not a gesture-borrowing
 * workaround. */
@Composable
fun Modifier.tunedGlass(
    backdrop: Backdrop,
    params: GlassParams,
    cornerRadius: Dp = params.cornerRadiusDp.dp,
    shape: Shape? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
    exportedBackdrop: LayerBackdrop? = null,
    // Additive hook for callers that need one more BackdropEffectScope step beyond what GlassParams
    // models (e.g. LiquidDialog's colorControls brightness boost) - runs after vibrancy/blur/lens,
    // defaults to null so every other call site is unaffected.
    extraEffects: (BackdropEffectScope.() -> Unit)? = null,
): Modifier {
    val liveParams = rememberUpdatedState(params)
    val liveShape = rememberUpdatedState(shape)
    val liveCornerRadius = rememberUpdatedState(cornerRadius)
    val liveOnDrawSurface = rememberUpdatedState(onDrawSurface)
    val liveExtraEffects = rememberUpdatedState(extraEffects)
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { liveShape.value ?: RoundedCornerShape(liveCornerRadius.value) },
        exportedBackdrop = exportedBackdrop,
        effects = {
            val p = liveParams.value
            if (p.vibrancy) vibrancy()
            blur(p.blurRadiusDp.dp.toPx())
            // Crash guard, confirmed on-device: the tuned params were dialed in against a ~170dp
            // card. Fed unscaled into a 24dp slider/toggle thumb, refractionHeight/Amount ends up
            // larger than the element itself, and the lens shader reads outside its own captured
            // backdrop buffer - native SIGSEGV in RenderThread, not a catchable Kotlin exception.
            // Clamping to the element's own on-screen size (available here, unlike at the call
            // site) protects every current and future tunedGlass() caller, not just the two that
            // hit it.
            val maxRefractionPx = size.minDimension * 0.45f
            // patchedLens(), not the library's raw lens() - see PatchedLens.kt for why: the
            // library's dispersion shader (fork and upstream 2.0.0 alike) has no bounds clamp on
            // its chromatic-aberration sample coordinates, which reads as rainbow-static
            // corruption on this app's narrow panels (outline drawer ~1:6.2, player control bar
            // ~1:6.9) when chromaticAberration is on. Routing every glass surface through
            // tunedGlass() means this one fix covers all of them - see the doc comment on
            // patchedLens() for the full root-cause writeup.
            patchedLens(
                refractionHeight = p.refractionHeightDp.dp.toPx().fastCoerceAtMost(maxRefractionPx),
                refractionAmount = p.refractionAmountDp.dp.toPx().fastCoerceAtMost(maxRefractionPx),
                depthEffect = p.depthEffect,
                chromaticAberration = p.chromaticAberration,
            )
            liveExtraEffects.value?.invoke(this)
        },
        // highlight/shadow/innerShadow keep whether-to-draw-at-all gated on the CURRENT params at
        // the time this Modifier is (re)built (same as before) - only their own live-tunable
        // numeric fields are read inside the closure. None of this app's real live-tunable
        // controls (teleprompter settings window's opacity/blur) toggle these on/off dynamically
        // today; if a future preset needs to, this enabled gate would need the same live treatment.
        highlight = if (params.highlight.enabled) {
            { liveParams.value.highlight.toHighlight() }
        } else null,
        shadow = if (params.shadow.enabled) {
            { liveParams.value.shadow.toShadow() }
        } else null,
        innerShadow = if (params.innerShadow.enabled) {
            { liveParams.value.innerShadow.toInnerShadow() }
        } else null,
        onDrawSurface = liveCombinedOnDrawSurface(liveParams, liveOnDrawSurface),
        layerBlock = layerBlock,
    )
}
