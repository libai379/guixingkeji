/*
 * Adapted from com.kyant.backdrop.catalog.destinations.DialogContent in
 * https://github.com/Kyant0/AndroidLiquidGlass (Apache License 2.0,
 * https://github.com/Kyant0/AndroidLiquidGlass/blob/main/LICENSE), turned from a one-off demo
 * screen into a reusable dialog composable.
 *
 * The original samples the real screen behind it as its backdrop (BackdropDemoScaffold's
 * wallpaper). A Dialog here uses Android's own separate window, which can't see the activity's
 * content behind it that way - so this dialog's "backdrop" is its own scrim layer instead
 * (rememberLayerBackdrop() capturing the dim overlay this composable paints itself). The glass
 * card still frosts/refracts something real, just the scrim it drew, not the app content further
 * back - a common enough look for system-style dialogs, not a literal see-through window.
 */
package li.songe.gkd.glassui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kashif_e.backdrop.Backdrop
import com.kashif_e.backdrop.backdrops.layerBackdrop
import com.kashif_e.backdrop.backdrops.rememberLayerBackdrop
import com.kashif_e.backdrop.effects.colorControls
import li.songe.gkd.glasstuning.GlassParams
import li.songe.gkd.glasstuning.GlassTarget
import li.songe.gkd.glasstuning.liveGlassParamsFor
import li.songe.gkd.glasstuning.tunedGlass

/** A frosted-glass dialog card over its own dim scrim, with a scale+fade entrance. Content sits
 * in a [ColumnScope] below an optional [title] and gets the dialog's own [Backdrop] handed to it
 * (so buttons inside can be [LiquidButton] too, sampling the same captured scrim) - use the
 * [title]/[text]/confirm-cancel overload below for the common two-button case instead of wiring
 * this by hand. */
@Composable
fun LiquidDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    target: GlassTarget = GlassTarget.Other,
    params: GlassParams = liveGlassParamsFor(target),
    title: String? = null,
    dismissOnClickOutside: Boolean = true,
    content: @Composable ColumnScope.(backdrop: Backdrop) -> Unit,
) {
    val isLightTheme = !isSystemInDarkTheme()

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(dismissOnClickOutside = dismissOnClickOutside),
    ) {
        val backdrop = rememberLayerBackdrop()

        // Scale+fade entrance - a static appear/disappear read as un-liquid next to everything
        // else this pass added motion to.
        val entrance = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            entrance.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
        }

        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .layerBackdrop(backdrop)
                    .fillMaxSize()
                    .graphicsLayer { alpha = entrance.value }
                    .background(Color(0xFF15151A).copy(alpha = 0.5f)),
            )
            Column(
                modifier
                    .padding(32.dp)
                    .graphicsLayer {
                        alpha = entrance.value
                        val s = 0.85f + 0.15f * entrance.value
                        scaleX = s
                        scaleY = s
                    }
                    .tunedGlass(
                        backdrop = backdrop,
                        params = params,
                        cornerRadius = 28.dp,
                        // Catalog's DialogContent recipe adds colorControls(brightness=0.2 on
                        // light theme, saturation=1.5) on top of blur+lens - it's compensating
                        // for the dim scrim it draws behind the card (same scrim this dialog
                        // draws, see the Box below). saturation stays at 1f here (the default,
                        // a no-op) rather than repeating 1.5 - TunedGlassParams.vibrancy=true
                        // already applies that same saturation boost (vibrancy() and
                        // colorControls(saturation=1.5f) are literally the same colorFilter in
                        // this fork, see effects/ColorFilter.kt), so stacking both would
                        // compound to ~2.25x saturation instead of the intended 1.5x.
                        extraEffects = {
                            colorControls(brightness = if (isLightTheme) 0.2f else 0f)
                        },
                    )
                    .fillMaxWidth(),
            ) {
                if (title != null) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(24.dp, 20.dp, 24.dp, 8.dp),
                    )
                }
                content(backdrop)
            }
        }
    }
}

/** Convenience overload for the common "title + message + confirm/cancel" shape - a near drop-in
 * for AlertDialog's most common call pattern. */
@Composable
fun LiquidDialog(
    onDismissRequest: () -> Unit,
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String? = "取消",
    confirmTint: Color = Color(0xFF0088FF),
    target: GlassTarget = GlassTarget.Other,
    params: GlassParams = liveGlassParamsFor(target),
) {
    LiquidDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        target = target,
        params = params,
        title = title,
    ) { backdrop ->
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(24.dp, 4.dp, 24.dp, 12.dp),
        )
        Row(
            Modifier.padding(16.dp, 8.dp, 16.dp, 16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dismissLabel != null) {
                LiquidButton(
                    onClick = onDismissRequest,
                    backdrop = backdrop,
                    modifier = Modifier.weight(1f),
                ) { Text(dismissLabel) }
            }
            LiquidButton(
                onClick = onConfirm,
                backdrop = backdrop,
                modifier = Modifier.weight(1f),
                tint = confirmTint,
            ) { Text(confirmLabel, color = Color.White) }
        }
    }
}
