package com.guixing.appearance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kashif_e.backdrop.backdrops.rememberLayerBackdrop
import com.guixing.LauncherBackground
import com.guixing.glasstuning.GlassTarget
import com.guixing.glasstuning.GlassTuningActivity
import com.guixing.glasstuning.liveGlassParamsFor
import com.guixing.glasstuning.tunedGlass
import com.guixing.glassui.GlassBackgroundEditor
import com.guixing.glassui.GlassBackgroundLayer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import com.guixing.glassui.glassForegroundOn
import com.guixing.glassui.SyncStatusBarToForeground
import com.guixing.glassui.launcherBackgroundFlow
import com.guixing.glassui.updateLauncherBackground
import com.guixing.ui.style.AppTheme

/**
 * The real, discoverable entry point this session replaced the launcher's hidden long-press-3s
 * gesture with (see LauncherActivity.kt's top-right settings icon). Built as a real settings list
 * (not e.g. jumping straight to GlassTuningActivity) so more appearance options have somewhere to
 * go without another screen restructure - the "背景" section below (2026-08-16) is the first of
 * those, editing the SAME launcherBackgroundFlow the launcher itself renders, so this page doubles
 * as a live preview of whatever's being tuned.
 */
class AppearanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                AppearanceScreen(
                    onBack = { finish() },
                    onOpenGlassTuning = {
                        startActivity(GlassTuningActivity.globalIntent(this))
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceScreen(onBack: () -> Unit, onOpenGlassTuning: () -> Unit) {
    // Unconditional, no API<33 null-guard - same convention every teleprompter glass screen in
    // this app already follows (editor/player/library never bothered with a fallback path either),
    // not a new inconsistency introduced here.
    val backdrop = rememberLayerBackdrop()
    val launcherBg by launcherBackgroundFlow.collectAsState()

    // 这一屏编辑的就是启动页那份可配背景，而且自己也用它当背景，所以文字颜色必须跟着算，
    // 不能用 Material 主题的 onXxx。算法收在 glassForegroundOn()，全项目共用一份，
    // 见 docs/PITFALLS.md「猜表面颜色」。共用的 GlassBackgroundEditor 也读这个 LocalContentColor。
    val panelParams = liveGlassParamsFor(GlassTarget.SettingsPanel)
    val fg = launcherBg.glassForegroundOn(
        tintEnabled = panelParams.tint.enabled,
        tintColorArgb = panelParams.tint.colorArgb,
        tintAlpha = panelParams.tint.alpha,
    )

    SyncStatusBarToForeground(fg)

    CompositionLocalProvider(LocalContentColor provides fg) {
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
        topBar = {
            TopAppBar(
                title = { Text("外观") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                // ⚠️ Material3 的 xxxDefaults.colors() **不继承 LocalContentColor**：没显式给的
                // 颜色槽一律从 MaterialTheme 取（onSurface 等），会盖掉外层 provide 的前景色。
                // 可配背景的屏幕上，凡是带 colors= 的 M3 组件都得把文字/图标槽一起给。
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = fg,
                    navigationIconContentColor = fg,
                    actionIconContentColor = fg,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            // Renders the SAME config the "背景" section below edits - Default falls back to the
            // launcher's own animated blob background so this screen looks like the launcher when
            // nothing's been customized yet, not a flat gray Scaffold background.
            GlassBackgroundLayer(
                bg = launcherBg,
                backdrop = backdrop,
                modifier = Modifier.fillMaxSize(),
                defaultContent = { mod -> LauncherBackground(modifier = mod) },
            )

            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                ListItem(
                    headlineContent = { Text("液态效果调节") },
                    supportingContent = { Text("液态参数调参面板") },
                    leadingContent = { Icon(Icons.Filled.BlurOn, contentDescription = null) },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    },
                    // 同上：ListItem 的文字/图标槽也不继承 LocalContentColor，必须显式给。
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                        headlineColor = fg,
                        supportingColor = fg.copy(alpha = 0.72f),
                        leadingIconColor = fg,
                        trailingIconColor = fg.copy(alpha = 0.72f),
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .tunedGlass(
                            backdrop = backdrop,
                            params = liveGlassParamsFor(GlassTarget.SettingsPanel).copy(chromaticAberration = false),
                            cornerRadius = 16.dp,
                        )
                        .clickable(onClick = onOpenGlassTuning),
                )

                Text(
                    "背景",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 24.dp, bottom = 12.dp),
                )
                GlassBackgroundEditor(
                    backdrop = backdrop,
                    current = launcherBg,
                    onUpdate = { mutate -> updateLauncherBackground(mutate) },
                    target = GlassTarget.SettingsPanel,
                    defaultDescription = "跟随内置流光动效背景",
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                        .padding(16.dp),
                )
            }
        }
    }
    } // CompositionLocalProvider(LocalContentColor)
}
