/*
 * 液态玻璃调参面板。**一套实现，四个入口**——区别只在于传进来的 `restrictToTargets` 不同：
 * 启动页/外观、提词器文档库、提词器编辑页、提词器播放页各自只看得到自己那几个 `GlassTarget`。
 *
 * 用 `restrictedIntent(context, targets)` 打开。`restrictToTargets != null` 同时也是"显示预设区"
 * 的开关，所以四个入口都自带预设功能。
 *
 * 预设按 `paramsByTarget.keys` 与传入 targets 集合**精确匹配**过滤（见 GlassPresetStore.kt）：
 * 底层是一份不分场景的扁平列表，不过滤的话，在提词器存的预设会出现在启动页的列表里，点"应用"
 * 还会因为找不到对应 target 而静默不生效。
 */
package com.guixing.glasstuning

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kashif_e.backdrop.Backdrop
import com.kashif_e.backdrop.backdrops.layerBackdrop
import com.kashif_e.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.guixing.LauncherBackground
import com.guixing.teleprompter.TeleprompterColorPickerPopup
import com.guixing.ui.style.AppTheme
import com.guixing.util.toast
import kotlin.math.roundToInt

class GlassTuningActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val restrictToTargets = intent.getStringArrayListExtra(EXTRA_RESTRICT_TARGET_NAMES)
            ?.mapNotNull { name -> GlassTarget.entries.find { it.name == name } }
            ?.takeIf { it.isNotEmpty() }
        setContent {
            AppTheme {
                GlassTuningScreen(onClose = { finish() }, restrictToTargets = restrictToTargets)
            }
        }
    }

    companion object {
        private const val EXTRA_RESTRICT_TARGET_NAMES = "restrict_target_names"

        /** Global entry (齿轮→外观→玻璃效果调节) - now just [restrictedIntent] with
         * [GeneralGlassTargets] (2026-08-16, "设置里边的玻璃效果调节，但凡有关于提词器的设置都要
         * 删掉" split): this screen no longer shows GlassTarget.Other/TeleprompterSettingsWindow
         * at all - those are teleprompter's own tunable surfaces, reachable only from
         * teleprompter's own settings panel via [restrictedIntent] + [TeleprompterGlassTargets],
         * never from here, so there's exactly one place to tune anything teleprompter-related.
         * Same preset section as the teleprompter entry, same reason: "都要加入应用和预设，按照
         * 提词器里面的去做". */
        fun globalIntent(context: Context): Intent = restrictedIntent(context, GeneralGlassTargets)

        /** Teleprompter's own entry - same Activity/screen/storage, just pre-filtered to
         * [targets] and with the preset save/apply/delete/rename section shown (see
         * GlassTuningScreen's own restrictToTargets doc) - "两个入口共用同一套面板实现和同一份
         * 存储，不要分叉成两套代码". */
        fun restrictedIntent(context: Context, targets: List<GlassTarget>): Intent =
            Intent(context, GlassTuningActivity::class.java).apply {
                putStringArrayListExtra(EXTRA_RESTRICT_TARGET_NAMES, ArrayList(targets.map { it.name }))
            }
    }
}

/** Three separate restricted lists (2026-08-16 split, replacing the single shared
 * TeleprompterGlassTargets = [Other, TeleprompterSettingsWindow]) - 文档库/编辑页/播放页 no longer
 * share one tunable value, each has its own entry point:
 * - Library: new gear icon on the library screen's own top bar (see TeleprompterLibraryScreen.kt).
 * - Editor: unchanged position, the settings window's own "更多液态效果" button.
 * - Player: also inside the settings window (it has no top-bar entry of its own), under that
 *   panel's "播放页" section - a second button next to the editor's own, not a new screen. */
val TeleprompterLibraryGlassTargets = listOf(GlassTarget.TeleprompterLibrary)
val TeleprompterEditorGlassTargets = listOf(GlassTarget.TeleprompterEditor, GlassTarget.TeleprompterSettingsWindow)
val TeleprompterPlayerGlassTargets = listOf(GlassTarget.TeleprompterPlayer)

/** The [GlassTarget]s everything OUTSIDE teleprompter actually uses (2026-08-16, split out
 * alongside TeleprompterGlassTargets once the launcher/appearance settings needed their own glass
 * surfaces that must NOT ride on teleprompter's tunable params - see GlassBackgroundEditor's own
 * `target` parameter and its two callers). LauncherCard = the launcher's capsule rows;
 * SettingsPanel (previously dead, zero real call sites anywhere) now backs the appearance
 * settings page's own "玻璃效果调节" row and its "背景" section's color-picker popups. */
val GeneralGlassTargets = listOf(GlassTarget.LauncherCard, GlassTarget.SettingsPanel)

private val ColorPresets = listOf(
    Color.White, Color.Black,
    Color(0xFF0088FF), Color(0xFFFF8D28), Color(0xFF34C759), Color(0xFFFF5FC9),
)

private enum class BgMode(val label: String) {
    Solid("纯色"), Gradient("渐变"), Image("图片"), Real("真实内容"),
}

/** [restrictToTargets] narrows the target selector to just that list and shows the 预设 section
 * below the regular per-target sliders - every one of GlassTuningActivity's real entry points now
 * always passes one: [TeleprompterLibraryGlassTargets]/[TeleprompterEditorGlassTargets]/
 * [TeleprompterPlayerGlassTargets] for teleprompter's three screens, [GeneralGlassTargets] for the
 * global 齿轮→外观→玻璃效果调节 entry - see each constant's own doc for why they don't overlap.
 * The null default is only a defensive fallback (every target, no presets) with no real caller
 * left as of 2026-08-16 - same screen either way, just this one flag differing at the call site. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlassTuningScreen(onClose: () -> Unit, restrictToTargets: List<GlassTarget>? = null) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Scaffold(topBar = { TopAppBar(title = { Text("液态调参") }) }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("需要 Android 13+（AGSL 运行时着色器）才能预览液态效果")
            }
        }
        return
    }

    val availableTargets = restrictToTargets ?: GlassTarget.entries.toList()
    val store by glassTuningStoreFlow.collectAsState()
    var target by rememberSaveable { mutableStateOf(availableTargets.first()) }
    val params = store.paramsFor(target)
    fun update(mutate: (GlassParams) -> GlassParams) = updateGlassParams(target, mutate)

    val fps = rememberFps()
    val clipboard = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("液态调参") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "关闭") }
                },
                actions = {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            "FPS $fps",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // ═══ 目标选择器 ═══
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                availableTargets.forEach { t ->
                    FilterChip(
                        selected = t == target,
                        onClick = { target = t },
                        label = { Text(t.label) },
                    )
                }
            }

            // ═══ 背景 + 实时预览 ═══
            PreviewArea(target = target, params = params)

            HorizontalDivider()

            // ═══ 参数面板（可滚动）═══
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                ShapeSection(params, ::update)
                BlurLensSection(params, ::update)
                HighlightSection(params, ::update)
                ShadowSection(params, ::update)
                InnerShadowSection(params, ::update)
                TintSection(params, ::update)

                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = {
                        resetGlassParams(target)
                        toast("已恢复默认值")
                    }) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("恢复默认")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        clipboard.setText(AnnotatedString(params.toExportJson()))
                        toast("已复制 JSON")
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("导出 JSON")
                    }
                    Button(onClick = {
                        clipboard.setText(AnnotatedString(params.toExportKotlin(target)))
                        toast("已复制 Kotlin 代码")
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("导出 Kotlin")
                    }
                }
                Spacer(Modifier.height(8.dp))

                // ═══ 预设（仅提词器入口）═══
                // "叠加的新一层，不是替换" - 恢复默认/导出 above are per-target, unchanged. This
                // is per-preset, covering every target in restrictToTargets at once.
                if (restrictToTargets != null) {
                    HorizontalDivider()
                    PresetSection(targets = restrictToTargets)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun PresetSection(targets: List<GlassTarget>) {
    val presetStore by glassPresetStoreFlow.collectAsState()
    // Real bug fixed 2026-08-15 (see builtInPreset's own doc comment): this screen must collect
    // glassTuningStoreFlow itself so Compose actually re-runs allPresetsFor() after every slider
    // drag - a bare glassTuningStoreFlow.value read inside builtInPreset() doesn't subscribe to
    // anything, so without this collectAsState() here the "默认" row kept showing/applying a
    // stale snapshot no matter what the sliders above were doing.
    val tuningStore by glassTuningStoreFlow.collectAsState()
    val presets = presetStore.allPresetsFor(tuningStore, targets)
    var saveDialogOpen by remember { mutableStateOf(false) }
    var renamePreset by remember { mutableStateOf<GlassPreset?>(null) }
    var deletePreset by remember { mutableStateOf<GlassPreset?>(null) }
    val activity = LocalActivity.current!!
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("预设")
        Text(
            "一个预设 = 上面 ${targets.size} 个目标各自当前生效参数的一份快照，跟单目标的" +
                "「恢复默认/导出」是叠加的两层，互不影响。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        presets.forEach { preset ->
            val isBuiltIn = preset.id == BUILT_IN_PRESET_ID
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(preset.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = {
                    applyGlassPreset(preset, targets)
                    toast("已应用「${preset.name}」")
                }) { Text("应用") }
                if (!isBuiltIn) {
                    // 2026-08-28 - 预设的文件导出已整体删除（导入侧此前已删，导出的文件没有任何
                    // 入口能读回来，属于死功能）。预设本身仍然可以保存/应用/改名/删除，只是不再能
                    // 导出成文件跨设备搬运。
                    IconButton(onClick = { renamePreset = preset }) {
                        Icon(Icons.Filled.Edit, contentDescription = "改名「${preset.name}」")
                    }
                    IconButton(onClick = { deletePreset = preset }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除「${preset.name}」")
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { saveDialogOpen = true }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("保存当前为新预设")
            }
        }
    }

    if (saveDialogOpen) {
        PresetNameDialog(
            title = "保存预设",
            initialName = "",
            confirmLabel = "保存",
            onDismiss = { saveDialogOpen = false },
            onConfirm = { name ->
                saveGlassPreset(name, targets)
                saveDialogOpen = false
                toast("已保存「$name」")
            },
        )
    }
    renamePreset?.let { preset ->
        PresetNameDialog(
            title = "预设改名",
            initialName = preset.name,
            confirmLabel = "确定",
            onDismiss = { renamePreset = null },
            onConfirm = { name ->
                renameGlassPreset(preset.id, name)
                renamePreset = null
            },
        )
    }
    deletePreset?.let { preset ->
        AlertDialog(
            onDismissRequest = { deletePreset = null },
            title = { Text("删除「${preset.name}」？") },
            text = { Text("此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteGlassPreset(preset.id)
                    deletePreset = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deletePreset = null }) { Text("取消") }
            },
        )
    }
}

@Composable
// 2026-08-28 - 改回 private：唯一复用它的 GlassPresetImportActivity（"打开方式"接收入口）已随
// 预设 JSON 导入功能一起删除。
private fun PresetNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("预设名称") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name) },
                enabled = name.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** Global compositor-clock FPS - counts actual drawn frames per second. A subtle continuous
 * animation drives the preview (see [PreviewArea]'s pulse) so this reads real cost instead of
 * settling at 0 the moment nothing else is animating. */
@Composable
private fun rememberFps(): Int {
    var fps by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        var frames = 0
        var windowStartNanos = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { now ->
                frames++
                val elapsed = now - windowStartNanos
                if (elapsed >= 1_000_000_000L) {
                    fps = (frames * 1_000_000_000.0 / elapsed).roundToInt()
                    frames = 0
                    windowStartNanos = now
                }
            }
        }
    }
    return fps
}

@Composable
private fun PreviewArea(target: GlassTarget, params: GlassParams) {
    var bgMode by rememberSaveable { mutableStateOf(BgMode.Gradient) }
    var solidColorArgb by rememberSaveable { mutableStateOf(0xFF3A3A3CL) }
    var solidPickerOpen by remember { mutableStateOf(false) }
    var pickedPainter by remember { mutableStateOf<Painter?>(null) }

    val context = LocalContext.current
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input)?.asImageBitmap()?.let { pickedPainter = BitmapPainter(it) }
                }
            } catch (_: Exception) {
                toast("图片读取失败")
            }
        }
    }

    // Drives a small continuous scale pulse so drawBackdrop keeps re-rendering while this screen
    // is open - both a livelier preview and what makes the FPS counter above mean something.
    val infiniteTransition = rememberInfiniteTransition(label = "previewPulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    // Shrunk from 360dp (2026-08-15) - user wants more room given to the parameter sliders below,
    // which just take whatever's left over via the outer Scaffold's weight(1f) column. See
    // GlassPreviewBox's own (w,h) table below, scaled down to match so it still fits without
    // clipping worse than it already did at the old size.
    Column(Modifier.height(220.dp).fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BgMode.entries.forEach { m ->
                FilterChip(selected = m == bgMode, onClick = { bgMode = m }, label = { Text(m.label) })
            }
            if (bgMode == BgMode.Solid) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(solidColorArgb.toInt()))
                        .clickable { solidPickerOpen = true },
                )
            }
            if (bgMode == BgMode.Image) {
                OutlinedButton(onClick = {
                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("选图片") }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val backdrop = rememberLayerBackdrop()

            when (bgMode) {
                BgMode.Solid -> Box(
                    Modifier.layerBackdrop(backdrop).fillMaxSize().background(Color(solidColorArgb.toInt())),
                )

                BgMode.Gradient -> Box(
                    Modifier.layerBackdrop(backdrop).fillMaxSize().background(
                        Brush.linearGradient(
                            listOf(Color(0xFF8B7CFF), Color(0xFF3CE8D8), Color(0xFFFF5FC9)),
                        ),
                    ),
                )

                BgMode.Image -> {
                    val painter = pickedPainter
                    if (painter != null) {
                        Image(
                            painter, null,
                            Modifier.layerBackdrop(backdrop).fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Box(
                            Modifier.layerBackdrop(backdrop).fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) { Text("点上面「选图片」") }
                    }
                }

                BgMode.Real -> if (target == GlassTarget.LauncherCard) {
                    LauncherBackground(Modifier.layerBackdrop(backdrop))
                } else {
                    RealContentMockup(Modifier.layerBackdrop(backdrop))
                }
            }

            GlassPreviewBox(
                target = target,
                params = params,
                pulseProgress = pulse,
                backdrop = backdrop,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }

    if (solidPickerOpen) {
        TeleprompterColorPickerPopup(
            title = "背景颜色",
            presets = ColorPresets,
            current = Color(solidColorArgb.toInt()),
            onDismiss = { solidPickerOpen = false },
            onPick = { c ->
                if (c != null) solidColorArgb = c.toArgbLong()
                solidPickerOpen = false
            },
        )
    }
}

/** Plain-content stand-in for targets that don't have one single real background (the settings
 * sheet, for instance, sits on top of whatever screen opened it) - approximate, not a literal
 * capture of any real screen. */
@Composable
private fun RealContentMockup(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(6) { i ->
            Box(
                Modifier
                    .fillMaxWidth(if (i % 2 == 0) 0.9f else 0.6f)
                    .height(if (i == 0) 28.dp else 16.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
            )
        }
    }
}

@Composable
private fun GlassPreviewBox(
    target: GlassTarget,
    params: GlassParams,
    pulseProgress: Float,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val (w, h) = when (target) {
        GlassTarget.LauncherCard -> 110.dp to 128.dp
        GlassTarget.SettingsPanel -> 180.dp to 140.dp
        GlassTarget.Other -> 160.dp to 108.dp
        // Tall, not square - the real window is fillMaxWidth(0.92f).fillMaxHeight(0.85f), a
        // full-screen card, not a compact one like the other targets. Still capped to fit inside
        // PreviewArea's now-220dp container (was 320x460 against a 360dp container).
        GlassTarget.TeleprompterSettingsWindow -> 130.dp to 170.dp
        // 2026-08-16 split targets - same compact size as Other, which is what these three
        // replaced (top bars/toolbars/menus/control bars, no single "the real shape" to match).
        GlassTarget.TeleprompterLibrary -> 160.dp to 108.dp
        GlassTarget.TeleprompterEditor -> 160.dp to 108.dp
        GlassTarget.TeleprompterPlayer -> 160.dp to 108.dp
    }
    Box(
        modifier
            .size(w, h)
            .tunedGlass(
                backdrop = backdrop,
                params = params,
                layerBlock = {
                    val s = 1f - 0.03f * pulseProgress
                    scaleX = s
                    scaleY = s
                },
            ),
    )
}

// ═══════════════════════════ 参数分组 UI ═══════════════════════════

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String = "%.2f".format(value),
) {
    Column {
        Text("$label $valueText", style = MaterialTheme.typography.labelMedium)
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ColorAlphaRow(
    label: String,
    colorArgb: Long,
    alpha: Float,
    onColorChange: (Long) -> Unit,
    onAlphaChange: (Float) -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color(colorArgb.toInt()))
                .clickable { pickerOpen = true },
        )
        Column(Modifier.weight(1f)) {
            LabeledSlider("$label 透明度", alpha, onAlphaChange, 0f..1f)
        }
    }
    if (pickerOpen) {
        TeleprompterColorPickerPopup(
            title = label,
            presets = ColorPresets,
            current = Color(colorArgb.toInt()),
            onDismiss = { pickerOpen = false },
            onPick = { c ->
                if (c != null) onColorChange(c.toArgbLong())
                pickerOpen = false
            },
        )
    }
}

@Composable
private fun ShapeSection(params: GlassParams, update: ((GlassParams) -> GlassParams) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("形状")
        LabeledSlider(
            "圆角半径", params.cornerRadiusDp, { v -> update { it.copy(cornerRadiusDp = v) } },
            0f..64f, "${params.cornerRadiusDp.roundToInt()}dp",
        )
    }
}

@Composable
private fun BlurLensSection(params: GlassParams, update: ((GlassParams) -> GlassParams) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("模糊 / 折射")
        ToggleRow("Vibrancy（饱和度提升）", params.vibrancy) { v -> update { it.copy(vibrancy = v) } }
        LabeledSlider(
            "Blur 半径", params.blurRadiusDp, { v -> update { it.copy(blurRadiusDp = v) } },
            0f..32f, "${params.blurRadiusDp.roundToInt()}dp",
        )
        LabeledSlider(
            "Lens 折射高度", params.refractionHeightDp, { v -> update { it.copy(refractionHeightDp = v) } },
            0f..48f, "${params.refractionHeightDp.roundToInt()}dp",
        )
        LabeledSlider(
            "Lens 折射强度", params.refractionAmountDp, { v -> update { it.copy(refractionAmountDp = v) } },
            0f..64f, "${params.refractionAmountDp.roundToInt()}dp",
        )
        ToggleRow("Depth effect（深度阴影）", params.depthEffect) { v -> update { it.copy(depthEffect = v) } }
        ToggleRow("色散（chromaticAberration）", params.chromaticAberration) { v ->
            update { it.copy(chromaticAberration = v) }
        }
        Text(
            "注：库里色散是开关量，没有连续强度参数（催化剂 catalog 自己的 playground 也是拿一个 0~1 滑块在内部判断 >0 来当开关用）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HighlightSection(params: GlassParams, update: ((GlassParams) -> GlassParams) -> Unit) {
    val h = params.highlight
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Highlight 高光")
        ToggleRow("启用", h.enabled) { v -> update { it.copy(highlight = h.copy(enabled = v)) } }
        if (h.enabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HighlightStyleKind.entries.forEach { kind ->
                    FilterChip(
                        selected = h.style == kind,
                        onClick = { update { it.copy(highlight = h.copy(style = kind)) } },
                        label = { Text(kind.name) },
                    )
                }
            }
            LabeledSlider(
                "宽度", h.widthDp, { v -> update { it.copy(highlight = h.copy(widthDp = v)) } },
                0f..8f, "${"%.1f".format(h.widthDp)}dp",
            )
            LabeledSlider(
                "模糊半径", h.blurRadiusDp, { v -> update { it.copy(highlight = h.copy(blurRadiusDp = v)) } },
                0f..8f, "${"%.1f".format(h.blurRadiusDp)}dp",
            )
            LabeledSlider("整体透明度", h.alpha, { v -> update { it.copy(highlight = h.copy(alpha = v)) } }, 0f..1f)
            when (h.style) {
                HighlightStyleKind.Default -> {
                    LabeledSlider(
                        "角度 angle", h.angleDeg, { v -> update { it.copy(highlight = h.copy(angleDeg = v)) } },
                        0f..360f, "${h.angleDeg.roundToInt()}°",
                    )
                    LabeledSlider(
                        "falloff", h.falloff, { v -> update { it.copy(highlight = h.copy(falloff = v)) } },
                        0.1f..4f,
                    )
                    ColorAlphaRowColorOnly("颜色", h.colorArgb) { c -> update { it.copy(highlight = h.copy(colorArgb = c)) } }
                }

                HighlightStyleKind.Plain -> {
                    ColorAlphaRowColorOnly("颜色", h.colorArgb) { c -> update { it.copy(highlight = h.copy(colorArgb = c)) } }
                }

                HighlightStyleKind.Ambient -> {
                    LabeledSlider(
                        "intensity", h.ambientIntensity,
                        { v -> update { it.copy(highlight = h.copy(ambientIntensity = v)) } }, 0f..1f,
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorAlphaRowColorOnly(label: String, colorArgb: Long, onColorChange: (Long) -> Unit) {
    var pickerOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color(colorArgb.toInt()))
                .clickable { pickerOpen = true },
        )
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
    if (pickerOpen) {
        TeleprompterColorPickerPopup(
            title = label,
            presets = ColorPresets,
            current = Color(colorArgb.toInt()),
            onDismiss = { pickerOpen = false },
            onPick = { c ->
                if (c != null) onColorChange(c.toArgbLong())
                pickerOpen = false
            },
        )
    }
}

@Composable
private fun ShadowSection(params: GlassParams, update: ((GlassParams) -> GlassParams) -> Unit) {
    val s = params.shadow
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Shadow 外阴影")
        ToggleRow("启用", s.enabled) { v -> update { it.copy(shadow = s.copy(enabled = v)) } }
        if (s.enabled) {
            LabeledSlider(
                "半径", s.radiusDp, { v -> update { it.copy(shadow = s.copy(radiusDp = v)) } },
                0f..48f, "${s.radiusDp.roundToInt()}dp",
            )
            LabeledSlider(
                "偏移 X", s.offsetXDp, { v -> update { it.copy(shadow = s.copy(offsetXDp = v)) } },
                -24f..24f, "${s.offsetXDp.roundToInt()}dp",
            )
            LabeledSlider(
                "偏移 Y", s.offsetYDp, { v -> update { it.copy(shadow = s.copy(offsetYDp = v)) } },
                -24f..24f, "${s.offsetYDp.roundToInt()}dp",
            )
            ColorAlphaRow(
                "颜色", s.colorArgb, s.alpha,
                onColorChange = { c -> update { it.copy(shadow = s.copy(colorArgb = c)) } },
                onAlphaChange = { a -> update { it.copy(shadow = s.copy(alpha = a)) } },
            )
        }
    }
}

@Composable
private fun InnerShadowSection(params: GlassParams, update: ((GlassParams) -> GlassParams) -> Unit) {
    val s = params.innerShadow
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("InnerShadow 内阴影")
        ToggleRow("启用", s.enabled) { v -> update { it.copy(innerShadow = s.copy(enabled = v)) } }
        if (s.enabled) {
            LabeledSlider(
                "半径", s.radiusDp, { v -> update { it.copy(innerShadow = s.copy(radiusDp = v)) } },
                0f..48f, "${s.radiusDp.roundToInt()}dp",
            )
            LabeledSlider(
                "偏移 X", s.offsetXDp, { v -> update { it.copy(innerShadow = s.copy(offsetXDp = v)) } },
                -24f..24f, "${s.offsetXDp.roundToInt()}dp",
            )
            LabeledSlider(
                "偏移 Y", s.offsetYDp, { v -> update { it.copy(innerShadow = s.copy(offsetYDp = v)) } },
                -24f..24f, "${s.offsetYDp.roundToInt()}dp",
            )
            ColorAlphaRow(
                "颜色", s.colorArgb, s.alpha,
                onColorChange = { c -> update { it.copy(innerShadow = s.copy(colorArgb = c)) } },
                onAlphaChange = { a -> update { it.copy(innerShadow = s.copy(alpha = a)) } },
            )
        }
    }
}

@Composable
private fun TintSection(params: GlassParams, update: ((GlassParams) -> GlassParams) -> Unit) {
    val t = params.tint
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Tint 染色")
        Text(
            "库里没有专门的 tint() 效果，这里跟 catalog 的 LiquidButton 一样用 onDrawSurface 手绘：" +
                "drawRect(color, BlendMode.Hue) + drawRect(color.copy(alpha))",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ToggleRow("启用", t.enabled) { v -> update { it.copy(tint = t.copy(enabled = v)) } }
        if (t.enabled) {
            ColorAlphaRow(
                "颜色", t.colorArgb, t.alpha,
                onColorChange = { c -> update { it.copy(tint = t.copy(colorArgb = c)) } },
                onAlphaChange = { a -> update { it.copy(tint = t.copy(alpha = a)) } },
            )
        }
    }
}

private fun Color.toArgbLong(): Long = this.toArgb().toLong() and 0xFFFFFFFFL
