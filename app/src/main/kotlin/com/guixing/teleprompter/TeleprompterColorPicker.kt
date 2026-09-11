package com.guixing.teleprompter

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import com.github.skydoves.colorpicker.compose.rememberColorPickerController
import com.kashif_e.backdrop.backdrops.layerBackdrop
import com.kashif_e.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import com.guixing.glassui.LiquidButton
import com.guixing.glasstuning.GlassTarget
import com.guixing.glasstuning.liveGlassParamsFor
import com.guixing.glasstuning.tunedGlass

// Shared by all four color pickers in the app (editor toolbar text/highlight, player background/
// text) instead of four separate hand-rolled RGB-slider popups - see the "统一颜色选择器" spec.
// Uses skydoves/colorpicker-compose for the HSV wheel instead of hand-rolling one.

private fun relativeLuminance(c: Color): Double {
    fun channel(v: Float): Double {
        val cs = v.toDouble()
        return if (cs <= 0.03928) cs / 12.92 else ((cs + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
}

/** WCAG-style contrast ratio (1.0 = identical, 21.0 = black/white). Used only as a soft nudge
 * here - teleprompter text is always large, so this isn't an accessibility-compliance gate - but
 * this exact bug (background and text silently landing on the same color with no way back) is
 * what caused the player-screen-blank incident this is guarding against. */
private fun contrastRatio(a: Color, b: Color): Double {
    val l1 = relativeLuminance(a) + 0.05
    val l2 = relativeLuminance(b) + 0.05
    return max(l1, l2) / min(l1, l2)
}

private fun Color.toHex6(): String = "%06X".format(this.toArgb() and 0xFFFFFF)

private fun parseHex(input: String): Color? {
    val clean = input.removePrefix("#").trim()
    if (clean.length != 6) return null
    return try {
        Color((0xFF000000 or clean.toLong(16)).toInt())
    } catch (e: NumberFormatException) {
        null
    }
}

@Composable
private fun ColorDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

/**
 * A popup with preset swatches + an HSV wheel + a hex input, all driving the same selected color.
 * [contrastAgainst] (e.g. the current background color when picking a text color) turns on a live
 * "text on background" preview and a low-contrast warning - the guard against this session's
 * white-on-white player screen incident, where background and text silently ended up the same
 * color with no UI to recover from it.
 */
@Composable
fun TeleprompterColorPickerPopup(
    title: String,
    presets: List<Color>,
    current: Color?,
    contrastAgainst: Color? = null,
    contrastAgainstLabel: String = "背景",
    allowClear: Boolean = false,
    // Default preserves every pre-existing call site's behavior (this popup started life
    // teleprompter-only). Callers outside the teleprompter package (GlassBackground.kt's shared
    // editor, reused by the non-teleprompter launcher/appearance settings) pass their own target
    // so their glass card doesn't quietly ride on teleprompter's tunable params - see 2026-08-16
    // "提词器设置只在提词器里改" split in GlassTuningActivity.kt.
    target: GlassTarget = GlassTarget.Other,
    onDismiss: () -> Unit,
    onPick: (Color?) -> Unit,
) {
    val controller = rememberColorPickerController()
    var pickedColor by remember { mutableStateOf(current?.takeIf { it != Color.Unspecified } ?: Color.Red) }
    var hexText by remember { mutableStateOf(pickedColor.toHex6()) }

    Popup(
        alignment = Alignment.Center,
        // Needs real focus (unlike the swatch-only popups elsewhere in the editor toolbar) since
        // it hosts a real hex TextField - same accepted exception already established by
        // FontSizeStepper's numeric input in EditorFormatToolbar (briefly steals focus to accept
        // typed digits without breaking the pending format command).
        properties = PopupProperties(focusable = true),
        onDismissRequest = onDismiss,
    ) {
        // Self-contained backdrop (same reasoning as LiquidDialog): a Popup gets its own window,
        // it can't see the real screen behind it, so the glass card frosts a solid sibling fill
        // instead - background Box and Column MUST stay siblings, never ancestor/descendant, or
        // it's the same circular-capture stack overflow already hit once this session.
        val backdrop = rememberLayerBackdrop()
        Box {
            Box(
                Modifier
                    .matchParentSize()
                    .layerBackdrop(backdrop)
                    .background(MaterialTheme.colorScheme.surface),
            )
            Column(
                Modifier
                    .tunedGlass(backdrop = backdrop, params = liveGlassParamsFor(target), cornerRadius = 20.dp)
                    .padding(20.dp)
                    .width(300.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { color ->
                        ColorDot(color, selected = pickedColor == color) {
                            pickedColor = color
                            hexText = color.toHex6()
                            controller.selectByColor(color, fromUser = false)
                        }
                    }
                    if (allowClear) {
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                .clickable { onPick(null) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "清除", modifier = Modifier.size(16.dp))
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("自定义", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(8.dp))
                HsvColorPicker(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    controller = controller,
                    initialColor = pickedColor,
                    onColorChanged = { envelope ->
                        // fromUser=false also fires for the wheel's own initialization and for
                        // our selectByColor() sync calls below - accepting those unconditionally
                        // let the wheel's initial default (observed on-device: landed on white,
                        // not the seeded initialColor) silently overwrite the correct starting
                        // color while the hex field kept showing the right value, a split-brain
                        // state that made "black" preview as an indistinguishable-from-white
                        // contrast warning. Only real drags should drive state.
                        if (envelope.fromUser) {
                            pickedColor = envelope.color
                            hexText = envelope.color.toHex6()
                        }
                    },
                )

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("#", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.width(4.dp))
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { text ->
                            val filtered = text.filter { it.isDigit() || it.uppercaseChar() in 'A'..'F' }
                                .take(6).uppercase()
                            hexText = filtered
                            parseHex(filtered)?.let { color ->
                                pickedColor = color
                                controller.selectByColor(color, fromUser = false)
                            }
                        },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(pickedColor)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    )
                }

                if (contrastAgainst != null) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = contrastAgainst,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            "预览 Aa 文字示例",
                            color = pickedColor,
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                    val ratio = contrastRatio(pickedColor, contrastAgainst)
                    if (ratio < 3.0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (ratio < 1.5) {
                                "⚠ 与$contrastAgainstLabel 几乎无法区分，建议换一个颜色"
                            } else {
                                "⚠ 与$contrastAgainstLabel 对比度偏低，可能不易辨认"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    LiquidButton(onClick = onDismiss, backdrop = backdrop) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    LiquidButton(
                        onClick = { onPick(pickedColor) },
                        backdrop = backdrop,
                        tint = Color(0xFF0088FF),
                    ) { Text("确定", color = Color.White) }
                }
            }
        }
    }
}
