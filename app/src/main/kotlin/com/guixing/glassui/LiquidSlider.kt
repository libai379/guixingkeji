/*
 * Ported from com.kyant.backdrop.catalog.components.LiquidSlider in
 * https://github.com/Kyant0/AndroidLiquidGlass (Apache License 2.0,
 * https://github.com/Kyant0/AndroidLiquidGlass/blob/main/LICENSE).
 *
 * Full port this time, driven by the real DampedDragAnimation (spring-physics drag + velocity,
 * separate track/thumb backdrops combined via rememberCombinedBackdrop) instead of the project's
 * earlier plain-Compose simplification. `value`/`selected` become supplier lambdas (`() -> Float`)
 * here, not plain values - that's load-bearing, not cosmetic: DampedDragAnimation's internal
 * LaunchedEffect does `snapshotFlow { value() }` to only re-sync from the caller when it actually
 * differs from what the drag itself is driving, which is what lets self-driven drag updates and
 * external state changes coexist without fighting each other.
 *
 * Same two fork adaptations as LiquidButton.kt: com.kyant.shapes.Capsule() -> RoundedCornerShape
 * (50) (the fork's lens() doesn't resolve corner radii for com.kyant.shapes types - see that
 * file's header for the full reasoning), and lens(..., chromaticAberration = true) -> this
 * project's patchedLens() (adds the missing dispersion-shader coordinate clamp from this
 * session's investigation).
 *
 * One addition beyond the literal port: [onValueChangeFinished], which the catalog original
 * doesn't have. TeleprompterPlayerScreen's speed slider uses it to reset an auto-hide timer on
 * release - wired into DampedDragAnimation's onDragStopped and the tap-to-set path.
 *
 * On the settings-panel-sliders-snap-back-on-release bug (see docs/PITFALLS.md for the full
 * writeup): two rounds (2026-08-13) tried consuming the drag in DragGestureInspector so an
 * ancestor verticalScroll couldn't steal it via NestedScroll pre-scroll - wrong theory, disproved
 * by on-device logging (onDragCancel fired zero times across 11 real drags; every gesture ended
 * via onDragEnd). Reverted. The real cause: `targetValue` above only ever advances via
 * `updateValue()`, which was only ever called from the LaunchedEffect below reacting to the
 * caller's `value()` - and for the settings panel specifically, that round-trips through
 * `vm.updateSettings` replacing the whole `TeleprompterLibrary` in a StateFlow, several
 * recompositions up. That's far slower than onDrag's ~10-100ms callback rate (the player's speed
 * slider has none of this - its `value`/`onValueChange` are a bare local `remember`), so
 * `targetValue` stayed frozen mid-drag and got committed back on release, undoing the whole
 * gesture. Fix: `onDrag` now calls `updateValue()` itself, synchronously, every frame - the thumb
 * and the next frame's delta math no longer wait on the caller's round trip at all. Pushing the
 * new value out to the caller is now a separate ~100ms-throttled side channel (`pendingPushValue`
 * below) instead of an unthrottled call on every frame, so the real document text still updates
 * live during the drag without hammering the settings panel's heavy recomposition on every pixel.
 */
package com.guixing.glassui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kashif_e.backdrop.Backdrop
import com.kashif_e.backdrop.backdrops.layerBackdrop
import com.kashif_e.backdrop.backdrops.rememberBackdrop
import com.kashif_e.backdrop.backdrops.rememberCombinedBackdrop
import com.kashif_e.backdrop.backdrops.rememberLayerBackdrop
import com.kashif_e.backdrop.drawBackdrop
import com.kashif_e.backdrop.effects.blur
import com.kashif_e.backdrop.highlight.Highlight
import com.kashif_e.backdrop.shadow.InnerShadow
import com.kashif_e.backdrop.shadow.Shadow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import com.guixing.glasstuning.patchedLens

@Composable
fun LiquidSlider(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    visibilityThreshold: Float = (valueRange.endInclusive - valueRange.start) * 0.001f,
    onValueChangeFinished: (() -> Unit)? = null,
    // 2026-08-16 - was a hardcoded delay(200) (see below), default kept at 200ms so every existing
    // caller's feel is unchanged. Callers whose onValueChange triggers something much heavier than
    // "replace a small piece of local state" (the teleprompter settings panel's font size/line
    // height/etc, which relayouts every chapter's rich text editor - this screen keeps every
    // chapter composed at once, not lazily windowed) pass a larger value here instead of this
    // component eating a one-size-fits-all cost for every slider in the app. See
    // docs/PITFALLS.md's "提词器正文滑块拖动卡顿" entry for the real on-device jank this widens
    // past - the 200ms default itself was already one widening pass (see the ticker's own comment)
    // that turned out to still not be enough for that specific screen.
    pushThrottleMs: Long = 200L,
) {
    val isLightTheme = !isSystemInDarkTheme()
    val accentColor =
        if (isLightTheme) Color(0xFF0088FF)
        else Color(0xFF0091FF)
    val trackColor =
        if (isLightTheme) Color(0xFF787878).copy(0.2f)
        else Color(0xFF787880).copy(0.36f)

    val trackBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart,
    ) {
        val trackWidth = constraints.maxWidth

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var didDrag by remember { mutableStateOf(false) }
        // True for the duration of a drag - see the LaunchedEffect below: while true, it ignores
        // the caller's value() entirely, so a slow round trip back through it can't fight the
        // drag that's already in progress and snap the thumb back to a stale value.
        var isDragging by remember { mutableStateOf(false) }
        // The ~100ms-throttled side channel out to the caller - set every onDrag frame, drained
        // by the ticker below. Decouples "keep the thumb/targetValue responsive" (every frame,
        // local only) from "tell the caller so real content updates too" (throttled, since for
        // the settings panel that caller-side update is a full TeleprompterLibrary StateFlow
        // replace + recomposition cascade, too heavy to do on every pixel of drag).
        var pendingPushValue by remember { mutableStateOf<Float?>(null) }
        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = value(),
                valueRange = valueRange,
                visibilityThreshold = visibilityThreshold,
                initialScale = 1f,
                pressedScale = 1.5f,
                onDragStarted = {
                    isDragging = true
                },
                onDragStopped = {
                    if (didDrag) {
                        onValueChange(targetValue)
                    }
                    pendingPushValue = null
                    isDragging = false
                    onValueChangeFinished?.invoke()
                },
                onDrag = { _, dragAmount ->
                    if (!didDrag) {
                        didDrag = dragAmount.x != 0f
                    }
                    val delta = (valueRange.endInclusive - valueRange.start) * (dragAmount.x / trackWidth)
                    val newValue = if (isLtr) (targetValue + delta).coerceIn(valueRange)
                        else (targetValue - delta).coerceIn(valueRange)
                    // Advances the animation's own targetValue immediately, synchronously - the
                    // next onDrag call's delta math (and the thumb's rendered position, driven by
                    // this same Animatable) no longer waits on onValueChange's round trip back
                    // through the caller. See this file's header for why that round trip being
                    // slow was the actual bug.
                    updateValue(newValue)
                    pendingPushValue = newValue
                },
            )
        }
        // Throttled push to the caller - see pendingPushValue's doc comment above. Always-on
        // ticker rather than gating on isDragging: negligible cost while idle (pendingPushValue
        // stays null, the check below is a no-op), and this way a push started right before
        // release still has a chance to land instead of getting torn down mid-flight.
        LaunchedEffect(dampedDragAnimation) {
            while (isActive) {
                // 100ms measured as real, on-device jank (2026-08-13) when the pushed value
                // actually changes visible document rendering (any of the settings-panel sliders,
                // not just overallScale) - a single relayout pass for a long/richly-styled chapter
                // can itself land in the 80-120ms range, so pushing that often collides with it
                // often enough to show up as dropped frames. 200ms was a first widening; still not
                // enough for the teleprompter settings panel specifically (2026-08-16 - user
                // explicitly asked to keep widening this, order of magnitude at a time, "not
                // caring about battery/memory", until it's actually smooth) - see pushThrottleMs.
                delay(pushThrottleMs)
                val pending = pendingPushValue
                if (pending != null) {
                    pendingPushValue = null
                    // Temporary timestamp marker (2026-08-13) to line up framestats' vsync
                    // timeline against exactly when the throttled push fires - not app logic,
                    // safe to leave or delete once the framestats analysis is done.
                    android.util.Log.d("SliderPush", "push t=${System.currentTimeMillis()}")
                    onValueChange(pending)
                }
            }
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { value() }
                .collectLatest { value ->
                    if (!isDragging && dampedDragAnimation.targetValue != value) {
                        dampedDragAnimation.updateValue(value)
                    }
                }
        }

        Box(Modifier.layerBackdrop(trackBackdrop)) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(trackColor)
                    .pointerInput(animationScope) {
                        detectTapGestures { position ->
                            val delta = (valueRange.endInclusive - valueRange.start) * (position.x / trackWidth)
                            val targetValue =
                                (if (isLtr) valueRange.start + delta
                                else valueRange.endInclusive - delta)
                                    .coerceIn(valueRange)
                            dampedDragAnimation.animateToValue(targetValue)
                            onValueChange(targetValue)
                            onValueChangeFinished?.invoke()
                        }
                    }
                    .height(6f.dp)
                    .fillMaxWidth(),
            )

            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(accentColor)
                    .height(6f.dp)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val width = (constraints.maxWidth * dampedDragAnimation.progress).fastRoundToInt()
                        layout(width, placeable.height) {
                            placeable.place(0, 0)
                        }
                    },
            )
        }

        Box(
            Modifier
                .graphicsLayer {
                    translationX =
                        (-size.width / 2f + trackWidth * dampedDragAnimation.progress)
                            .fastCoerceIn(-size.width / 4f, trackWidth - size.width * 3f / 4f) * if (isLtr) 1f else -1f
                }
                .then(dampedDragAnimation.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(
                        backdrop,
                        rememberBackdrop(trackBackdrop) { drawBackdrop ->
                            val progress = dampedDragAnimation.pressProgress
                            val scaleX = lerp(2f / 3f, 1f, progress)
                            val scaleY = lerp(0f, 1f, progress)
                            scale(scaleX, scaleY) {
                                drawBackdrop()
                            }
                        },
                    ),
                    shape = { RoundedCornerShape(50) },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        blur(8f.dp.toPx() * (1f - progress))
                        patchedLens(
                            10f.dp.toPx() * progress,
                            14f.dp.toPx() * progress,
                            // 2026-09-02 - 默认外观整体改成"朴素白底、不透明、色散全关"，这三个
                            // 液态组件（按钮/滑块/开关）把色散写死在自己实现里、不走 GlassParams，
                            // 所以要在这里单独关，否则"全部关掉"是漏的。
                            chromaticAberration = false,
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = progress,
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 4f.dp,
                            color = Color.Black.copy(alpha = 0.05f),
                        )
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 4f.dp * progress,
                            alpha = progress,
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(Color.White.copy(alpha = 1f - progress))
                    },
                )
                .size(40f.dp, 24f.dp),
        )
    }
}
