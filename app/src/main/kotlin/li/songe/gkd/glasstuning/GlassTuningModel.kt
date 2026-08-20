package li.songe.gkd.glasstuning

import kotlinx.serialization.Serializable

/**
 * Which real screen a parameter set belongs to. Each target is tuned and persisted
 * independently - see GlassTuningStore. Add new entries here as more screens get a glass
 * treatment; nothing else needs to change (paramsFor() falls back to GlassParams() defaults
 * for any target not yet present in a saved store file).
 */
enum class GlassTarget(val label: String) {
    LauncherCard("启动页卡片"),
    SettingsPanel("设置面板"),
    Other("其他"),
    // Added 2026-08-13 once the teleprompter settings window (a full-screen floating glass card
    // over real document text, not a solid/gradient background like every other target) needed
    // its own baseline - see defaultGlassParamsFor's per-target override below.
    TeleprompterSettingsWindow("提词器设置窗"),
    // 2026-08-16 split: 文档库/编辑页/播放页 previously all shared GlassTarget.Other - one slider
    // drag affected all three screens at once. User asked each screen get its own independently
    // tunable set, library gaining its own top-bar settings entry, editor/player keeping their
    // existing entry points but no longer sharing a value. See GlassTuningActivity.kt's
    // TeleprompterLibraryGlassTargets/TeleprompterEditorGlassTargets/TeleprompterPlayerGlassTargets.
    TeleprompterLibrary("提词器目录"),
    TeleprompterEditor("提词器编辑页"),
    TeleprompterPlayer("提词器播放页"),
}

/** Mirrors com.kashif_e.backdrop.highlight.HighlightStyle's three variants (Ambient/Default/Plain). */
enum class HighlightStyleKind { Ambient, Default, Plain }

@Serializable
data class GlassHighlightParams(
    val enabled: Boolean = true,
    val style: HighlightStyleKind = HighlightStyleKind.Default,
    // Highlight(width, blurRadius, alpha, style) - the outer wrapper, applies to all three styles.
    val widthDp: Float = 1.3f,
    val blurRadiusDp: Float = 0.65f,
    val alpha: Float = 1f,
    // HighlightStyle.Default(color, blendMode, angle, falloff) - style-specific.
    val colorArgb: Long = 0xFFFFFFFFL,
    val angleDeg: Float = 45f,
    val falloff: Float = 1f,
    // HighlightStyle.Ambient(intensity) - style-specific, unused for Default/Plain.
    val ambientIntensity: Float = 0.38f,
)

@Serializable
data class GlassShadowParams(
    val enabled: Boolean = false,
    // Shadow(radius, offset, color, alpha) - real lib defaults: radius=24dp, offset=(0, radius/6).
    val radiusDp: Float = 24f,
    val offsetXDp: Float = 0f,
    val offsetYDp: Float = 4f,
    val colorArgb: Long = 0x1A000000L,
    val alpha: Float = 1f,
)

@Serializable
data class GlassInnerShadowParams(
    val enabled: Boolean = false,
    // InnerShadow(radius, offset, color, alpha) - real lib defaults: radius=24dp, offset=(0, radius).
    val radiusDp: Float = 24f,
    val offsetXDp: Float = 0f,
    val offsetYDp: Float = 24f,
    val colorArgb: Long = 0x26000000L,
    val alpha: Float = 1f,
)

@Serializable
data class GlassTintParams(
    val enabled: Boolean = false,
    // Matches LiquidButton's onDrawSurface pattern: drawRect(color, BlendMode.Hue) +
    // drawRect(color.copy(alpha)) - there's no dedicated tint() effect in the library, this IS
    // how the catalog does it, see F:\liquidglass-ref LiquidButton.kt:92-95.
    val colorArgb: Long = 0xFF0088FFL,
    val alpha: Float = 0.75f,
)

@Serializable
data class GlassParams(
    val cornerRadiusDp: Float = 32f,
    val blurRadiusDp: Float = 8f,
    // lens(refractionHeight, refractionAmount, depthEffect, chromaticAberration).
    val refractionHeightDp: Float = 16f,
    val refractionAmountDp: Float = 30f,
    val depthEffect: Boolean = false,
    // Real API is a plain Boolean (no continuous "strength") - see backdrop/effects/Lens.kt.
    val chromaticAberration: Boolean = false,
    val vibrancy: Boolean = true,
    val highlight: GlassHighlightParams = GlassHighlightParams(),
    val shadow: GlassShadowParams = GlassShadowParams(),
    val innerShadow: GlassInnerShadowParams = GlassInnerShadowParams(),
    val tint: GlassTintParams = GlassTintParams(),
)

/** Tuned in the on-device playground (2026-08-08) and exported as one shared baseline for all
 * three targets - see glasstuning playground session, GlassTuningActivity. Update this constant
 * (not the individual call sites) when a new tuning round gets exported. */
private val TunedGlassParams = GlassParams(
    cornerRadiusDp = 36.200985f,
    blurRadiusDp = 0f,
    refractionHeightDp = 48f,
    refractionAmountDp = 64f,
    depthEffect = true,
    chromaticAberration = true,
    vibrancy = true,
    highlight = GlassHighlightParams(
        enabled = false,
        style = HighlightStyleKind.Default,
        widthDp = 0.47776306f,
        blurRadiusDp = 4.526637f,
        alpha = 1f,
        colorArgb = 4294967295L,
        angleDeg = 185.30353f,
        falloff = 0.34610263f,
        ambientIntensity = 0.38f,
    ),
    shadow = GlassShadowParams(
        enabled = true,
        radiusDp = 6.273656f,
        offsetXDp = -0.41332245f,
        offsetYDp = 4f,
        colorArgb = 1291845632L,
        alpha = 0.64483225f,
    ),
    innerShadow = GlassInnerShadowParams(
        enabled = false,
        radiusDp = 24f,
        offsetXDp = 0f,
        offsetYDp = 24f,
        colorArgb = 637534208L,
        alpha = 1f,
    ),
    tint = GlassTintParams(
        enabled = false,
        colorArgb = 4278225151L,
        alpha = 0.75f,
    ),
)

/** Every target shares the one tuned-in-playground baseline, except where a real screen found
 * (on-device, not guessed) that the shared baseline doesn't work for what's actually behind it.
 * TeleprompterSettingsWindow is the first such case: it's a full-screen glass card over real,
 * high-contrast document text, not the solid/gradient backgrounds every other target sits on -
 * chromaticAberration=true read as visible rainbow corruption there (and, separately, on the
 * player control bar - see docs/PITFALLS.md).
 *
 * blurRadiusDp went 0 -> 14 -> back to 0 (2026-08-13): 14 was added earlier this session to fix a
 * readability regression (real body text bleeding through unblurred, double-exposed against the
 * panel's own labels). Reverted to 0 to match the outline drawer's look (also Other's baseline,
 * also chromaticAberration=false) per an explicit "make it look like the drawer" request - the
 * readability risk that 14 was fixing is real and this panel is denser text than the drawer, so
 * if it resurfaces on-device this needs to go back to a nonzero value, not stay at 0 by default. */
fun defaultGlassParamsFor(target: GlassTarget): GlassParams = when (target) {
    GlassTarget.TeleprompterSettingsWindow -> TunedGlassParams.copy(chromaticAberration = false)
    else -> TunedGlassParams
}

@Serializable
data class GlassTuningStore(
    val paramsByTarget: Map<String, GlassParams> = emptyMap(),
) {
    fun paramsFor(target: GlassTarget): GlassParams =
        paramsByTarget[target.name] ?: defaultGlassParamsFor(target)

    fun withParams(target: GlassTarget, params: GlassParams): GlassTuningStore =
        copy(paramsByTarget = paramsByTarget + (target.name to params))

    fun withReset(target: GlassTarget): GlassTuningStore =
        copy(paramsByTarget = paramsByTarget - target.name)
}
