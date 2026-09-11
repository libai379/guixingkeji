package com.guixing.glasstuning

import android.graphics.RenderEffect
import android.os.Build
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.util.fastCoerceAtMost
import com.kashif_e.backdrop.BackdropEffectScope
import com.kashif_e.backdrop.effects.effect
import com.kashif_e.backdrop.effects.lens
import org.intellij.lang.annotations.Language

/**
 * Root-caused this session: com.kashif_e.backdrop 0.0.1-alpha02's dispersion shader (and the
 * upstream com.kyant.backdrop 2.0.0 it was forked from - confirmed byte-identical, never patched,
 * see F:\liquidglass-ref backdrop/src/commonMain/kotlin/com/kyant/backdrop/internal/Shaders.kt)
 * samples `content.eval()` at `refractedCoord +/- dispersedCoord * k` with NO clamp on any of the
 * 7 sample coordinates (red/orange/yellow/green/cyan/blue/purple - earlier in this investigation
 * this was miscounted as 6, it's actually 7). `dispersionIntensity` is a saddle function of raw
 * pixel position - `(centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y)` - not of the
 * surface normal, so it grows without bound as an element gets narrower relative to its length,
 * reaching +-1 near the far corners of any panel whose short dimension is small. On this
 * project's outline drawer (~1:6.2) and player control bar (~1:6.9) that pushes sample
 * coordinates outside the captured backdrop buffer, which reads as the rainbow-static corruption
 * seen on-device with chromaticAberration on. The library's OWN ReflectiveGlassShaderString (same
 * file) already clamps its chromatic-aberration sample coordinates to `[0, size]` - proving the
 * omission here is an inconsistency, not a deliberate choice.
 *
 * This can't be fixed by patching the library: RoundedRectRefractionWithDispersionShaderString is
 * `internal`, and `internal` is module-scoped - inaccessible from this app module whether the
 * dependency is the fork or upstream. So this reimplements just the dispersion path locally, with
 * the same 7 samples but each clamped to the backdrop's own bounds (the same [0, size] clamp
 * ReflectiveGlassShaderString already uses), via the fork's PUBLIC primitives
 * (BackdropEffectScope.obtainRuntimeShader, BackdropEffectScope.renderEffect,
 * com.kashif_e.backdrop.effects.effect) - no vendoring or forking the library needed. The
 * non-dispersion path is untouched by this bug and still delegates to the library's own lens().
 *
 * Every glass surface in this app must call this (not the library's raw lens()) when chromatic
 * aberration is possible - tunedGlass() in GlassEffects.kt is the single call site, so every
 * screen picks this up automatically. Grepped to confirm no other call site invokes
 * `.lens(` directly.
 */
fun BackdropEffectScope.patchedLens(
    refractionHeight: Float,
    refractionAmount: Float,
    depthEffect: Boolean = false,
    chromaticAberration: Boolean = false,
) {
    if (!chromaticAberration) {
        lens(refractionHeight, refractionAmount, depthEffect, chromaticAberration = false)
        return
    }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    if (refractionHeight <= 0f || refractionAmount <= 0f) return

    if (padding > 0f) {
        padding = (padding - refractionHeight).fastCoerceAtLeast(0f)
    }

    val cornerRadii = resolvedCornerRadii ?: return

    val shader = obtainRuntimeShader("PatchedRefractionWithDispersion", PatchedDispersionShaderString)
    shader.apply {
        setFloatUniform("size", size.width, size.height)
        setFloatUniform("offset", -padding, -padding)
        setFloatUniform("cornerRadii", cornerRadii)
        setFloatUniform("refractionHeight", refractionHeight)
        setFloatUniform("refractionAmount", -refractionAmount)
        setFloatUniform("depthEffect", if (depthEffect) 1f else 0f)
        setFloatUniform("chromaticAberration", 1f)
    }
    effect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
}

/** Same corner-radius resolution as the library's own private BackdropEffectScope.cornerRadii
 * (effects/Lens.kt) - duplicated here because that one isn't accessible from outside the library
 * module. Kept to the two shape types this project ever actually passes into tunedGlass()
 * (CornerBasedShape via RoundedCornerShape, AbsoluteRoundedCornerShape isn't used but handled for
 * parity with the library). */
private val BackdropEffectScope.resolvedCornerRadii: FloatArray?
    get() {
        val maxRadius = size.minDimension / 2f
        val isLtr = layoutDirection == LayoutDirection.Ltr
        return when (val s = shape) {
            is AbsoluteRoundedCornerShape -> {
                floatArrayOf(
                    s.topStart.toPx(size, this).fastCoerceAtMost(maxRadius),
                    s.topEnd.toPx(size, this).fastCoerceAtMost(maxRadius),
                    s.bottomEnd.toPx(size, this).fastCoerceAtMost(maxRadius),
                    s.bottomStart.toPx(size, this).fastCoerceAtMost(maxRadius),
                )
            }

            is CornerBasedShape -> {
                val topLeft = if (isLtr) s.topStart.toPx(size, this) else s.topEnd.toPx(size, this)
                val topRight = if (isLtr) s.topEnd.toPx(size, this) else s.topStart.toPx(size, this)
                val bottomRight = if (isLtr) s.bottomEnd.toPx(size, this) else s.bottomStart.toPx(size, this)
                val bottomLeft = if (isLtr) s.bottomStart.toPx(size, this) else s.bottomEnd.toPx(size, this)
                floatArrayOf(
                    topLeft.fastCoerceAtMost(maxRadius),
                    topRight.fastCoerceAtMost(maxRadius),
                    bottomRight.fastCoerceAtMost(maxRadius),
                    bottomLeft.fastCoerceAtMost(maxRadius),
                )
            }

            else -> null
        }
    }

@Language("AGSL")
private const val PatchedDispersionShaderString = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float chromaticAberration;

float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(coord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));

    float2 refractedCoord = coord + d * grad;
    float dispersionIntensity = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
    float2 dispersedCoord = d * grad * dispersionIntensity;

    // The only change from the library's RoundedRectRefractionWithDispersionShaderString: every
    // content.eval() sample coordinate below is clamped to [0, size] (same clamp the library's
    // own ReflectiveGlassShaderString already applies to its chromatic-aberration coordinates).
    // Without it, dispersionIntensity - a saddle function of raw pixel position, unbounded as a
    // panel gets narrower - pushes these coordinates outside the captured backdrop buffer,
    // reading garbage data that shows up as rainbow-static corruption on narrow panels.

    half4 color = half4(0.0);

    half4 red = content.eval(clamp(refractedCoord + dispersedCoord, float2(0.0), size));
    color.r += red.r / 3.5;
    color.a += red.a / 7.0;

    half4 orange = content.eval(clamp(refractedCoord + dispersedCoord * (2.0 / 3.0), float2(0.0), size));
    color.r += orange.r / 3.5;
    color.g += orange.g / 7.0;
    color.a += orange.a / 7.0;

    half4 yellow = content.eval(clamp(refractedCoord + dispersedCoord * (1.0 / 3.0), float2(0.0), size));
    color.r += yellow.r / 3.5;
    color.g += yellow.g / 3.5;
    color.a += yellow.a / 7.0;

    half4 green = content.eval(clamp(refractedCoord, float2(0.0), size));
    color.g += green.g / 3.5;
    color.a += green.a / 7.0;

    half4 cyan = content.eval(clamp(refractedCoord - dispersedCoord * (1.0 / 3.0), float2(0.0), size));
    color.g += cyan.g / 3.5;
    color.b += cyan.b / 3.0;
    color.a += cyan.a / 7.0;

    half4 blue = content.eval(clamp(refractedCoord - dispersedCoord * (2.0 / 3.0), float2(0.0), size));
    color.b += blue.b / 3.0;
    color.a += blue.a / 7.0;

    half4 purple = content.eval(clamp(refractedCoord - dispersedCoord, float2(0.0), size));
    color.r += purple.r / 7.0;
    color.b += purple.b / 3.0;
    color.a += purple.a / 7.0;

    return color;
}"""
