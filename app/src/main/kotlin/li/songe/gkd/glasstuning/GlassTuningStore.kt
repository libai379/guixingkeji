package li.songe.gkd.glasstuning

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.serialization.json.Json
import li.songe.gkd.store.createAnyFlow
import li.songe.gkd.util.json

val glassTuningStoreFlow = createAnyFlow(
    key = "glass_tuning",
    default = { GlassTuningStore() },
)

fun updateGlassParams(target: GlassTarget, mutate: (GlassParams) -> GlassParams) {
    val store = glassTuningStoreFlow.value
    val next = mutate(store.paramsFor(target))
    glassTuningStoreFlow.value = store.withParams(target, next)
}

fun resetGlassParams(target: GlassTarget) {
    glassTuningStoreFlow.value = glassTuningStoreFlow.value.withReset(target)
}

/** The one place every REAL screen should read [target]'s params from (2026-08-15) - previously
 * every real call site read [defaultGlassParamsFor] directly, a compile-time constant, so nothing
 * saved in [glassTuningStoreFlow] (via the tuning panel, or now via a glass preset) ever reached
 * a real screen, only the tuning panel's own preview. Falls through to [defaultGlassParamsFor]
 * automatically via [GlassTuningStore.paramsFor] when nothing's been saved for [target] yet, so
 * this is a behavior-preserving swap for every call site until something actually gets saved. */
@Composable
fun liveGlassParamsFor(target: GlassTarget): GlassParams {
    val store by glassTuningStoreFlow.collectAsState()
    return store.paramsFor(target)
}

private val prettyJson by lazy { Json(from = json) { prettyPrint = true } }

fun GlassParams.toExportJson(): String = prettyJson.encodeToString(this)

/** A ready-to-paste `GlassParams(...)` call, defaults omitted (matches [defaultGlassParamsFor]'s
 * job of seeding shipped constants) so the exported snippet only shows what was actually tuned
 * away from the target's starting point. */
fun GlassParams.toExportKotlin(target: GlassTarget): String {
    val base = defaultGlassParamsFor(target)
    val lines = mutableListOf<String>()

    if (cornerRadiusDp != base.cornerRadiusDp) lines += "cornerRadiusDp = ${cornerRadiusDp}f"
    if (blurRadiusDp != base.blurRadiusDp) lines += "blurRadiusDp = ${blurRadiusDp}f"
    if (refractionHeightDp != base.refractionHeightDp) lines += "refractionHeightDp = ${refractionHeightDp}f"
    if (refractionAmountDp != base.refractionAmountDp) lines += "refractionAmountDp = ${refractionAmountDp}f"
    if (depthEffect != base.depthEffect) lines += "depthEffect = $depthEffect"
    if (chromaticAberration != base.chromaticAberration) lines += "chromaticAberration = $chromaticAberration"
    if (vibrancy != base.vibrancy) lines += "vibrancy = $vibrancy"

    if (highlight != base.highlight) {
        val h = highlight
        lines += """highlight = GlassHighlightParams(
        |    enabled = ${h.enabled},
        |    style = HighlightStyleKind.${h.style},
        |    widthDp = ${h.widthDp}f,
        |    blurRadiusDp = ${h.blurRadiusDp}f,
        |    alpha = ${h.alpha}f,
        |    colorArgb = 0x${h.colorArgb.toString(16).uppercase()}L,
        |    angleDeg = ${h.angleDeg}f,
        |    falloff = ${h.falloff}f,
        |    ambientIntensity = ${h.ambientIntensity}f,
        |)""".trimMargin()
    }
    if (shadow != base.shadow) {
        val s = shadow
        lines += """shadow = GlassShadowParams(
        |    enabled = ${s.enabled},
        |    radiusDp = ${s.radiusDp}f,
        |    offsetXDp = ${s.offsetXDp}f,
        |    offsetYDp = ${s.offsetYDp}f,
        |    colorArgb = 0x${s.colorArgb.toString(16).uppercase()}L,
        |    alpha = ${s.alpha}f,
        |)""".trimMargin()
    }
    if (innerShadow != base.innerShadow) {
        val s = innerShadow
        lines += """innerShadow = GlassInnerShadowParams(
        |    enabled = ${s.enabled},
        |    radiusDp = ${s.radiusDp}f,
        |    offsetXDp = ${s.offsetXDp}f,
        |    offsetYDp = ${s.offsetYDp}f,
        |    colorArgb = 0x${s.colorArgb.toString(16).uppercase()}L,
        |    alpha = ${s.alpha}f,
        |)""".trimMargin()
    }
    if (tint != base.tint) {
        val t = tint
        lines += """tint = GlassTintParams(
        |    enabled = ${t.enabled},
        |    colorArgb = 0x${t.colorArgb.toString(16).uppercase()}L,
        |    alpha = ${t.alpha}f,
        |)""".trimMargin()
    }

    if (lines.isEmpty()) return "GlassParams() // 跟 ${target.name} 的当前默认值完全一样，没有改动"
    return "GlassParams(\n    " + lines.joinToString(",\n    ") + ",\n)"
}
