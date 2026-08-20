package li.songe.gkd.glassui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kashif_e.backdrop.Backdrop
import com.kashif_e.backdrop.backdrops.LayerBackdrop
import com.kashif_e.backdrop.backdrops.layerBackdrop
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import li.songe.gkd.glasstuning.GlassTarget
import li.songe.gkd.store.createAnyFlow
import li.songe.gkd.teleprompter.TeleprompterColorPickerPopup
import li.songe.gkd.util.LogUtils
import li.songe.gkd.util.teleprompterBgFolder
import li.songe.gkd.util.toast
import java.io.File

private const val TAG = "GlassBg"

/** Shared by every screen that needs a user-customizable backdrop-glass background (2026-08-16) -
 * originally the teleprompter library screen's own TeleprompterLibraryBackground/BgMode, promoted
 * here and renamed once the launcher screen needed the identical "4 modes + image adjustment"
 * system: same data shape, same rendering, same editor UI, not two parallel definitions. Field
 * names of the pre-existing properties are unchanged from that original so on-disk JSON already
 * saved under the old type name keeps decoding fine (kotlinx.serialization keys on property names,
 * never the enclosing class name, for a plain non-polymorphic data class). */
@Serializable
enum class GlassBgMode { Default, Solid, Gradient, Image }

@Serializable
data class GlassBackgroundConfig(
    val mode: GlassBgMode = GlassBgMode.Default,
    val solidColorArgb: Long = 0xFF15151AL,
    val gradientColorsArgb: List<Long> = listOf(0xFF8B7CFFL, 0xFF3CE8D8L, 0xFFFF5FC9L),
    // 2026-08-16, launcher's "自由选两个颜色+渐变角度" ask - the library's own gradient mode never
    // had a real custom entry before this (presets only), so this is a genuinely new field, not a
    // rename. Brush.linearGradient(colors) with no explicit start/end (the old render path) draws
    // roughly a top-left-to-bottom-right diagonal, which is why every existing preset below defaults
    // to 45f - keeps their look identical to before this field existed.
    val gradientAngleDeg: Float = 45f,
    val imageFileName: String? = null,
    val imageBrightness: Float = 1f,
    // 2026-08-16, launcher ask - new, additive to the library's original brightness/saturation/blur
    // trio (contrast wasn't part of that set; kept blur too rather than dropping it, since it's an
    // already-shipped, already-accepted library control this change has no reason to regress).
    val imageContrast: Float = 1f,
    val imageSaturation: Float = 1f,
    val imageBlurDp: Float = 0f,
    val imageAlpha: Float = 1f,
)

/** Persisted separately from the teleprompter library's own background (TeleprompterLibrary.
 * libraryBackground) - different screen, different preference, same config shape. */
val launcherBackgroundFlow = createAnyFlow(
    key = "launcher_background",
    default = { GlassBackgroundConfig() },
)

fun updateLauncherBackground(mutate: (GlassBackgroundConfig) -> GlassBackgroundConfig) {
    val prev = launcherBackgroundFlow.value
    val next = try {
        mutate(prev)
    } catch (e: Exception) {
        LogUtils.d("update launcher background failed", e, tag = TAG)
        return
    }
    launcherBackgroundFlow.value = next
}

// ═══════════════════════════ Rendering ═══════════════════════════

/** Renders [bg] as a full-size background AND tags the right node with [backdrop]'s
 * [layerBackdrop] so every tunedGlass() surface drawn on top of this (buttons/sliders/cards) can
 * actually refract/blur it - see this project's own PITFALLS.md "layerBackdrop captures the node's
 * FINAL rendered subtree, not first draw" entry for why the tag must sit on the one raw drawing
 * node with nothing else drawn after it in the same subtree, never a wrapping Box.
 *
 * [defaultContent] lets a caller substitute its own look for [GlassBgMode.Default] (e.g. the
 * launcher's animated 4-blob gradient) instead of the flat theme-color fill every other caller
 * (the teleprompter library screen) is happy with when it's null - same shared modes, different
 * "nothing customized yet" look per screen. The callback receives the exact modifier (already
 * carrying [layerBackdrop] + fillMaxSize) that must land on the caller's own single raw drawing
 * node, same constraint as above. */
@Composable
fun GlassBackgroundLayer(
    bg: GlassBackgroundConfig,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    defaultContent: (@Composable (Modifier) -> Unit)? = null,
) {
    when (bg.mode) {
        GlassBgMode.Default -> if (defaultContent != null) {
            defaultContent(modifier.layerBackdrop(backdrop).fillMaxSize())
        } else {
            Box(modifier.layerBackdrop(backdrop).background(MaterialTheme.colorScheme.background))
        }

        GlassBgMode.Solid -> Box(modifier.layerBackdrop(backdrop).background(Color(bg.solidColorArgb.toInt())))

        GlassBgMode.Gradient -> {
            val colors = bg.gradientColorsArgb.map { Color(it.toInt()) }
            Box(
                modifier
                    .layerBackdrop(backdrop)
                    .fillMaxSize()
                    .drawAngledGradientBehind(colors, bg.gradientAngleDeg),
            )
        }

        GlassBgMode.Image -> {
            val painter = rememberGlassBgImagePainter(bg.imageFileName)
            if (painter != null) {
                val matrix = glassBgColorMatrix(bg.imageBrightness, bg.imageContrast, bg.imageSaturation)
                Box(modifier) {
                    // Capture-tagged: same color adjustments as the visible layer below (colorFilter
                    // is a plain Paint-level op, safe to combine with layerBackdrop) but NO blur -
                    // stacking Modifier.blur() onto a layerBackdrop-tagged node bled the blur across
                    // the whole screen in an earlier version (this project's own graphicsLayer-
                    // stacking pitfall, see PITFALLS.md). Glass elements therefore refract the
                    // sharp-but-color-adjusted photo, not a literally raw one.
                    Image(
                        painter,
                        contentDescription = null,
                        modifier = Modifier.layerBackdrop(backdrop).fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        alpha = bg.imageAlpha,
                        colorFilter = ColorFilter.colorMatrix(matrix),
                    )
                    // Visible layer - same color adjustments + alpha as above, plus blur (which is
                    // why this can't be the same node as the capture above). Sharing one colorFilter/
                    // alpha pair with the capture layer means the alpha slider fades this down to
                    // reveal the SAME image underneath minus blur, not a stray unfiltered copy.
                    Image(
                        painter,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().blur(bg.imageBlurDp.dp),
                        contentScale = ContentScale.Crop,
                        alpha = bg.imageAlpha,
                        colorFilter = ColorFilter.colorMatrix(matrix),
                    )
                }
            } else {
                Box(modifier.layerBackdrop(backdrop).background(MaterialTheme.colorScheme.background))
            }
        }
    }
}

private fun Modifier.drawAngledGradientBehind(colors: List<Color>, angleDeg: Float): Modifier =
    this.drawBehind { drawAngledGradient(colors, angleDeg) }

private fun DrawScope.drawAngledGradient(colors: List<Color>, angleDeg: Float) {
    val angleRad = Math.toRadians(angleDeg.toDouble())
    val cx = size.width / 2f
    val cy = size.height / 2f
    val length = (abs(size.width * cos(angleRad)) + abs(size.height * sin(angleRad))).toFloat() / 2f
    val dx = (cos(angleRad) * length).toFloat()
    val dy = (sin(angleRad) * length).toFloat()
    drawRect(
        Brush.linearGradient(
            colors,
            start = Offset(cx - dx, cy - dy),
            end = Offset(cx + dx, cy + dy),
        ),
    )
}

private fun composeColorMatrices(outer: ColorMatrix, inner: ColorMatrix): ColorMatrix {
    val a = outer.values
    val b = inner.values
    val r = FloatArray(20)
    for (row in 0 until 4) {
        for (col in 0 until 5) {
            var sum = if (col == 4) a[row * 5 + 4] else 0f
            for (k in 0 until 4) sum += a[row * 5 + k] * b[k * 5 + col]
            r[row * 5 + col] = sum
        }
    }
    return ColorMatrix(r)
}

/** brightness/contrast/saturation composed into one ColorMatrix for a single colorFilter pass.
 * Order: saturation, then contrast (pivoted at 128 so it doesn't also shift brightness), then
 * brightness - the pre-existing post-scale-every-value trick (correct because scaling an affine
 * transform's linear+bias terms uniformly is equivalent to scaling its output by the same factor).
 * Transparency is handled separately via Image's own `alpha` param, not folded in here. */
private fun glassBgColorMatrix(brightness: Float, contrast: Float, saturation: Float): ColorMatrix {
    val saturationMatrix = ColorMatrix().apply { setToSaturation(saturation) }
    val contrastTranslate = 128f * (1f - contrast)
    val contrastMatrix = ColorMatrix(
        floatArrayOf(
            contrast, 0f, 0f, 0f, contrastTranslate,
            0f, contrast, 0f, 0f, contrastTranslate,
            0f, 0f, contrast, 0f, contrastTranslate,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
    val matrix = composeColorMatrices(outer = contrastMatrix, inner = saturationMatrix)
    for (i in matrix.values.indices) matrix.values[i] = matrix.values[i] * brightness
    return matrix
}

/** Decodes once per [fileName] (remember keys on it) and caches the result - not re-decoded on
 * every recomposition. Full-resolution decode, no downsampling: matches the library screen's
 * original implementation this was promoted from, which was already accepted on-device. */
@Composable
fun rememberGlassBgImagePainter(fileName: String?): BitmapPainter? {
    if (fileName == null) return null
    return remember(fileName) {
        val file = File(teleprompterBgFolder, fileName)
        if (!file.exists()) return@remember null
        try {
            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()?.let { BitmapPainter(it) }
        } catch (e: Exception) {
            LogUtils.d("glass bg decode failed, file=$fileName", e, tag = TAG)
            null
        }
    }
}

data class GlassGradientPreset(val colors: List<Long>, val angleDeg: Float = 45f)

val GlassGradientPresets = listOf(
    GlassGradientPreset(listOf(0xFF8B7CFFL, 0xFF3CE8D8L, 0xFFFF5FC9L)),
    GlassGradientPreset(listOf(0xFFFF9A8BL, 0xFFFF6A88L, 0xFFFF99ACL)),
    GlassGradientPreset(listOf(0xFF2E3192L, 0xFF1BFFFFL)),
    GlassGradientPreset(listOf(0xFF232526L, 0xFF414345L)),
    GlassGradientPreset(listOf(0xFFF7971EL, 0xFFFFD200L)),
)

// ═══════════════════════════ Editor ═══════════════════════════

/** Just the mode-switcher + per-mode controls, no popup/scrim/panel chrome - callers embed this
 * either inside their own popup (TeleprompterLibraryScreen's LibraryBackgroundPickerPopup) or
 * directly inline in a settings page (AppearanceActivity's "背景" section), per the "same shared
 * component" ask - one implementation, two mounting contexts, not two editors. */
@Composable
fun GlassBackgroundEditor(
    backdrop: Backdrop,
    current: GlassBackgroundConfig,
    onUpdate: ((GlassBackgroundConfig) -> GlassBackgroundConfig) -> Unit,
    // Which GlassTarget this editor's own color-picker popups tune (2026-08-16 split, see
    // GlassTuningActivity.kt's GeneralGlassTargets/TeleprompterLibraryGlassTargets) - the
    // teleprompter library screen passes GlassTarget.TeleprompterLibrary (its own dedicated
    // target, no longer shared with the editor/player screens either), the non-teleprompter
    // launcher/appearance settings pass GlassTarget.SettingsPanel so editing the launcher's
    // background never rides on any teleprompter screen's tunable params.
    target: GlassTarget,
    defaultDescription: String = "跟随当前主题的默认背景色",
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var solidPickerOpen by remember { mutableStateOf(false) }
    var gradientPickerTarget by remember { mutableIntStateOf(-1) }
    var importing by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch(Dispatchers.IO) {
            try {
                val fileName = "bg_${System.currentTimeMillis()}.jpg"
                context.contentResolver.openInputStream(uri)?.use { input ->
                    File(teleprompterBgFolder, fileName).outputStream().use { output -> input.copyTo(output) }
                }
                withContext(Dispatchers.Main) {
                    onUpdate { it.copy(mode = GlassBgMode.Image, imageFileName = fileName) }
                    importing = false
                }
            } catch (e: Exception) {
                LogUtils.d("glass bg import failed, uri=$uri", e, tag = TAG)
                withContext(Dispatchers.Main) {
                    toast("图片导入失败")
                    importing = false
                }
            }
        }
    }

    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassBgMode.entries.forEach { mode ->
                LiquidCompactButton(
                    onClick = { onUpdate { it.copy(mode = mode) } },
                    backdrop = backdrop,
                    sampleBackdrop = false,
                    active = current.mode == mode,
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        when (mode) {
                            GlassBgMode.Default -> "默认"
                            GlassBgMode.Solid -> "纯色"
                            GlassBgMode.Gradient -> "渐变"
                            GlassBgMode.Image -> "图片"
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        when (current.mode) {
            GlassBgMode.Default -> Text(
                defaultDescription,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            GlassBgMode.Solid -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // 2026-08-16 - 之前色块纯色块没有描边，当前颜色一旦接近面板自己的玻璃底色就跟
                // 背景融在一起分不清是个可点的东西。加一圈白色描边，不管选的是什么颜色都独立于
                // 背景之外，始终看得出这是个色块。
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(current.solidColorArgb.toInt()))
                        .border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                        .clickable { solidPickerOpen = true },
                )
                Text("点色块选颜色", style = MaterialTheme.typography.bodyMedium)
            }

            GlassBgMode.Gradient -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("预设", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                GlassGradientPresets.forEach { preset ->
                    val selected = current.gradientColorsArgb == preset.colors && current.gradientAngleDeg == preset.angleDeg
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Brush.linearGradient(preset.colors.map { Color(it.toInt()) }))
                            .border(
                                width = if (selected) 3.dp else 0.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                shape = RoundedCornerShape(10.dp),
                            )
                            .clickable {
                                onUpdate {
                                    it.copy(mode = GlassBgMode.Gradient, gradientColorsArgb = preset.colors, gradientAngleDeg = preset.angleDeg)
                                }
                            },
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text("自定义", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val colorA = current.gradientColorsArgb.getOrNull(0)?.let { Color(it.toInt()) } ?: Color(0xFF8B7CFF)
                val colorB = current.gradientColorsArgb.getOrNull(1)?.let { Color(it.toInt()) } ?: Color(0xFF3CE8D8)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 同下面纯色色块一样加白色描边，避免颜色跟面板背景融在一起。
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(colorA)
                            .border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                            .clickable { gradientPickerTarget = 0 },
                    )
                    Text("→", style = MaterialTheme.typography.bodyMedium)
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(colorB)
                            .border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                            .clickable { gradientPickerTarget = 1 },
                    )
                }
                Text("角度 ${current.gradientAngleDeg.toInt()}°", style = MaterialTheme.typography.labelMedium)
                LiquidSlider(
                    value = { current.gradientAngleDeg },
                    onValueChange = { v -> onUpdate { it.copy(mode = GlassBgMode.Gradient, gradientAngleDeg = v) } },
                    backdrop = backdrop,
                    valueRange = 0f..360f,
                )
            }

            GlassBgMode.Image -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val painter = rememberGlassBgImagePainter(current.imageFileName)
                if (painter != null) {
                    Image(
                        painter,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Crop,
                        alpha = current.imageAlpha,
                        colorFilter = ColorFilter.colorMatrix(
                            glassBgColorMatrix(current.imageBrightness, current.imageContrast, current.imageSaturation),
                        ),
                    )
                }
                LiquidCompactButton(
                    onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    backdrop = backdrop,
                    sampleBackdrop = false,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (importing) "导入中…" else "选择图片", modifier = Modifier.weight(1f))
                }

                Spacer(Modifier.height(4.dp))
                Text("亮度 ${"%.1f".format(current.imageBrightness)}", style = MaterialTheme.typography.labelMedium)
                LiquidSlider(
                    value = { current.imageBrightness },
                    onValueChange = { v -> onUpdate { it.copy(imageBrightness = v) } },
                    backdrop = backdrop,
                    valueRange = 0.3f..1.7f,
                )
                Text("对比度 ${"%.1f".format(current.imageContrast)}", style = MaterialTheme.typography.labelMedium)
                LiquidSlider(
                    value = { current.imageContrast },
                    onValueChange = { v -> onUpdate { it.copy(imageContrast = v) } },
                    backdrop = backdrop,
                    valueRange = 0.3f..1.7f,
                )
                Text("饱和度 ${"%.1f".format(current.imageSaturation)}", style = MaterialTheme.typography.labelMedium)
                LiquidSlider(
                    value = { current.imageSaturation },
                    onValueChange = { v -> onUpdate { it.copy(imageSaturation = v) } },
                    backdrop = backdrop,
                    valueRange = 0f..2f,
                )
                Text("模糊度 ${current.imageBlurDp.toInt()}dp", style = MaterialTheme.typography.labelMedium)
                LiquidSlider(
                    value = { current.imageBlurDp },
                    onValueChange = { v -> onUpdate { it.copy(imageBlurDp = v) } },
                    backdrop = backdrop,
                    valueRange = 0f..30f,
                )
                Text("透明度 ${(current.imageAlpha * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                LiquidSlider(
                    value = { current.imageAlpha },
                    onValueChange = { v -> onUpdate { it.copy(imageAlpha = v) } },
                    backdrop = backdrop,
                    valueRange = 0.1f..1f,
                )
            }
        }
    }

    if (solidPickerOpen) {
        TeleprompterColorPickerPopup(
            title = "背景颜色",
            presets = listOf(
                Color.White, Color.Black,
                Color(0xFF0088FF), Color(0xFFFF8D28), Color(0xFF34C759), Color(0xFFFF5FC9),
            ),
            current = Color(current.solidColorArgb.toInt()),
            target = target,
            onDismiss = { solidPickerOpen = false },
            onPick = { c ->
                if (c != null) {
                    onUpdate { it.copy(mode = GlassBgMode.Solid, solidColorArgb = c.toArgb().toLong() and 0xFFFFFFFFL) }
                }
                solidPickerOpen = false
            },
        )
    }

    if (gradientPickerTarget >= 0) {
        val colorSlot = gradientPickerTarget
        val colorA = current.gradientColorsArgb.getOrNull(0)?.let { Color(it.toInt()) } ?: Color(0xFF8B7CFF)
        val colorB = current.gradientColorsArgb.getOrNull(1)?.let { Color(it.toInt()) } ?: Color(0xFF3CE8D8)
        TeleprompterColorPickerPopup(
            title = "渐变颜色",
            presets = listOf(
                Color.White, Color.Black,
                Color(0xFF0088FF), Color(0xFFFF8D28), Color(0xFF34C759), Color(0xFFFF5FC9),
            ),
            current = if (colorSlot == 0) colorA else colorB,
            target = target,
            onDismiss = { gradientPickerTarget = -1 },
            onPick = { c ->
                if (c != null) {
                    val pickedArgb = c.toArgb().toLong() and 0xFFFFFFFFL
                    onUpdate {
                        val colors = mutableListOf(colorA.toArgb().toLong() and 0xFFFFFFFFL, colorB.toArgb().toLong() and 0xFFFFFFFFL)
                        colors[colorSlot] = pickedArgb
                        it.copy(mode = GlassBgMode.Gradient, gradientColorsArgb = colors)
                    }
                }
                gradientPickerTarget = -1
            },
        )
    }
}
