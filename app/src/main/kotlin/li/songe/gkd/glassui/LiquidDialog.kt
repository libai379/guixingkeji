/*
 * Adapted from com.kyant.backdrop.catalog.destinations.DialogContent in
 * https://github.com/Kyant0/AndroidLiquidGlass (Apache License 2.0,
 * https://github.com/Kyant0/AndroidLiquidGlass/blob/main/LICENSE), turned from a one-off demo
 * screen into a reusable dialog composable.
 *
 * 2026-08-28 - 卡片已改为**主题色实心**，不再是液态玻璃。原因写在下面 background() 那处注释里，
 * 一句话：Dialog 是独立 window，采样不到背后 Activity 的内容，玻璃在这里折射的只是弹窗自己画的
 * 那层纯色遮罩，等于没折射，白付渲染成本。
 *
 * 仍然保留 [Backdrop] 参数传给 content：调用方的自定义内容里可能还有别的用途，且改签名会波及
 * 全部调用点；但**不要再往这个弹窗里放 LiquidButton**，实心卡片上的玻璃按钮会采样到被卡片挡住的
 * 遮罩，画出来是一块跟卡片颜色对不上的灰斑。
 */
package li.songe.gkd.glassui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import li.songe.gkd.glasstuning.GlassParams
import li.songe.gkd.glasstuning.GlassTarget
import li.songe.gkd.glasstuning.liveGlassParamsFor

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
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = dismissOnClickOutside,
            // 2026-08-28 - 关掉平台默认宽度约束，否则 Dialog 的窗口只有内容那么大，下面那层
            // fillMaxSize 的遮罩铺不满屏幕，点击弹窗外围也不在本窗口的命中范围内。
            usePlatformDefaultWidth = false,
        ),
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
                    .align(Alignment.Center)
                    .padding(32.dp)
                    .graphicsLayer {
                        alpha = entrance.value
                        val s = 0.85f + 0.15f * entrance.value
                        scaleX = s
                        scaleY = s
                    }
                    // 2026-08-28 - 从 tunedGlass 改成纯色卡片。
                    //
                    // 原因是液态玻璃在这里**原理上就没法生效**：Dialog 是 Android 独立 window，
                    // 采样不到它背后 Activity 的内容（本文件头部原本就写明了这一点）。原实现只好
                    // 退而求其次去采样"弹窗自己画的那层遮罩"——折射一块纯色遮罩，等于什么都没折射，
                    // 只剩一层灰蒙蒙的底，还白白付了 blur+lens 的渲染成本和窄面板色散越界的风险。
                    //
                    // 所以这里用主题色实心卡片，视觉确定、成本为零。要真正的液态弹窗必须改成
                    // "同一棵组合树里的覆盖层"（提词器设置面板就是那么做的，见
                    // TeleprompterSettingsWindow 的注释），那是另一件事，不在本次范围。
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(28.dp))
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
            // 2026-08-28 - 从 LiquidButton 改成主题色按钮。卡片已经是不透明实心的，玻璃按钮在它
            // 上面采样到的是被卡片挡住的那层遮罩，画出来是一块跟卡片颜色对不上的灰斑。
            if (dismissLabel != null) {
                OutlinedButton(
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f),
                ) { Text(dismissLabel) }
            }
            Button(
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = confirmTint),
            ) { Text(confirmLabel) }
        }
    }
}
