package li.songe.gkd

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import li.songe.gkd.appearance.AppearanceActivity
import li.songe.gkd.callscreen.CallGuardActivity
import li.songe.gkd.glasstuning.GlassTarget
import li.songe.gkd.glasstuning.liveGlassParamsFor
import li.songe.gkd.glasstuning.tunedGlass
import li.songe.gkd.glassui.GlassBackgroundLayer
import li.songe.gkd.glassui.GLASS_BG_BASE
import li.songe.gkd.glassui.approximateBackgroundColor
import li.songe.gkd.glassui.compositeOver
import li.songe.gkd.glassui.prefersLightForegroundOnTop
import li.songe.gkd.glassui.prefersLightForeground
import li.songe.gkd.glassui.launcherBackgroundFlow
import com.kashif_e.backdrop.Backdrop
import com.kashif_e.backdrop.backdrops.layerBackdrop
import com.kashif_e.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LauncherScreen(
                onOpenWebTool = { htmlFile ->
                    startActivity(
                        Intent(this, WebToolActivity::class.java).putExtra(
                            WebToolActivity.EXTRA_HTML_FILE, htmlFile
                        )
                    )
                },
                onOpenTeleprompter = {
                    startActivity(Intent(this, TeleprompterActivity::class.java))
                },
                onOpenAppearance = {
                    startActivity(Intent(this, AppearanceActivity::class.java))
                },
                onOpenCallGuard = {
                    startActivity(Intent(this, CallGuardActivity::class.java))
                },
            )
        }
    }
}

private data class LauncherCardData(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val dimmed: Boolean = false,
    val onClick: () -> Unit,
)

/** 启动页标题的金色。选固定色而不是跟随背景自适应：金色在深浅两种底上都有足够对比，
 *  而自适应黑/白遇到中间调背景时两头都不讨好（用户原话："金色字体不容易被背景吞掉"）。 */
private val LauncherTitleGold = Color(0xFFD4A017)

/** 深色前景基色。不用纯黑：纯黑压在白卡上过硬，这个近黑带一点冷调，跟金色标题也更协调。 */
private val LauncherDarkForeground = Color(0xFF14121A)

// Ambient-only palette for the animated default background. These colours never carry text,
// icon, or interaction meaning on the launchpad.
private val AccentPurple = Color(0xFF8B7CFF)
private val AccentTeal = Color(0xFF3CE8D8)
private val AccentMagenta = Color(0xFFFF5FC9)
private val AccentAmber = Color(0xFFFFB74D)

// 近似全圆角长条胶囊 (2026-08-16, "方块卡片→长条胶囊" 改版) - height fixed, width fills the
// column, cornerRadius passed into tunedGlass() below is clamped to min(width,height)/2 by
// RoundedCornerShape itself, so a value far bigger than this height (999.dp) always resolves to a
// true capsule regardless of the row's actual on-screen width.
// 80dp -> 40dp（缩一半）-> 53dp（2026-08-16，"高度再增加 1/3"，40 * 4/3 约等于 53）。
private val LauncherCapsuleHeight = 68.dp
private val LauncherCapsuleGap = 8.dp

@Composable
private fun LauncherScreen(
    onOpenWebTool: (String) -> Unit,
    onOpenTeleprompter: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenCallGuard: () -> Unit,
) {
    // The one runtime version gate the whole screen depends on: AGSL runtime shaders
    // (vibrancy/lens) that the glass effect needs require API 33+. SDK_INT is invariant
    // for the process, so branching a @Composable call on it is safe.
    val backdrop = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberLayerBackdrop()
    } else {
        null
    }
    val launcherBg by launcherBackgroundFlow.collectAsState()
    var aboutOpen by remember { mutableStateOf(false) }

    // 2026-08-20 - 本工程是公开版："去广告"和"虚拟定位"两张卡片连同它们的源码已经整个删掉，
    // 不是靠开关隐藏，所以这里就是一份普通的三项列表。私人版在 F:\guixing。
    val cards = remember {
        listOf(
            LauncherCardData(
                icon = Icons.Filled.LocationOn,
                title = "定位修改",
                subtitle = "修改照片视频 GPS 信息",
                onClick = { onOpenWebTool("gps-modifier.html") },
            ),
            LauncherCardData(
                icon = Icons.AutoMirrored.Filled.Notes,
                title = "提词器",
                subtitle = "拍摄直播滚动提词",
                onClick = onOpenTeleprompter,
            ),
            // 第三张卡片 (2026-08-16) - 雏形阶段，用户明确要求先接入启动页方便自己直接测试，不用
            // 再靠 adb 拉起 CallGuardActivity。
            LauncherCardData(
                icon = Icons.Filled.PhoneDisabled,
                title = "电话拦截",
                subtitle = "设置黑白名单拦截来电",
                onClick = onOpenCallGuard,
            ),
        )
    }

    // 2026-09-02 - 前景色不再写死白色。背景已经可以被用户调成任意颜色（含浅色），写死白字会让
    // 标题/副标题/齿轮/版本行在浅色背景上整片消失（真机截图确认）。这里按背景的感知亮度选一次
    // 基色，下面所有前景都从它派生。图片模式判断不了背景亮度，仍然按深色处理（保持白字）。
    val fg = if (launcherBg.prefersLightForeground()) Color.White else LauncherDarkForeground
    // 背景的近似实色，用来推算卡片表面颜色。图片模式判断不了（要解码采样整张图），退回深色基底
    // ——跟 prefersLightForeground() 对图片模式的处理保持一致，不各写一套。
    val bgApproxColor = launcherBg.approximateBackgroundColor() ?: GLASS_BG_BASE

    Box(Modifier.fillMaxSize()) {
        if (backdrop != null) {
            // Same shared 4-mode background system as the teleprompter library screen and this
            // screen's own settings page (AppearanceActivity's "背景" section edits the SAME
            // launcherBackgroundFlow) - GlassBgMode.Default falls back to the original animated
            // 4-blob gradient via defaultContent, so an un-customized launcher looks exactly like
            // it always did.
            GlassBackgroundLayer(
                bg = launcherBg,
                backdrop = backdrop,
                modifier = Modifier.fillMaxSize(),
                defaultContent = { mod -> LauncherBackground(modifier = mod) },
            )
        } else {
            // API < 33: no glass, no user-customizable modes either (GlassBackgroundLayer's
            // Solid/Gradient/Image modes all need a real backdrop to sample) - always the default
            // animated background, same degraded behavior this screen already had before today.
            LauncherBackground(modifier = Modifier.fillMaxSize())
        }

        // Top-anchored, not center (2026-08-16, "癸月两个字放大提到顶部，四个长条功能键也往上提，
        // 给下边儿留下空间，以后放更多功能") - deliberately leaves open space below the 4 cards
        // for future entries, rather than the old vertically-centered layout that had no natural
        // room to grow into.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                // 读 R.string.app_name，不写死——私人版是"癸星"，公开版是"癸星科技"，
                // 靠各自的 strings.xml 区分，这里两边代码保持一致，少一处要人工同步的差异。
                text = META.appName,
                // 2026-09-02 - 固定金色，不跟随背景明暗自适应。金色在深浅两种底上都还立得住，
                // 而自适应黑/白在中间调背景（比如那张淡薄荷）上两边都不讨好。左上角 + 宋体 + 字号
                // 减半也是同一批要求。
                color = LauncherTitleGold,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
                modifier = Modifier.align(Alignment.Start).padding(bottom = 16.dp),
            )

            cards.forEachIndexed { index, card ->
                LauncherCard(
                    data = card,
                    backdrop = backdrop,
                    fg = fg,
                    bgApproxColor = bgApproxColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (index == 0) 0.dp else LauncherCapsuleGap)
                        .then(rememberEntranceModifier(index)),
                )
            }

            // 版本号 + 开发者，常驻显示（2026-08-20 用户要求"加版本号，加关于以及开发者，使用
            // 说明等，在启动页面"）。点一下展开完整的关于/使用说明浮层——这样版本信息一眼可见，
            // 又不占用卡片下方那片"以后放更多功能"的空间。
        }

        Text(
            text = "${Edition.LABEL} v${META.versionName} · 开发者：${Edition.DEVELOPER}",
            color = fg.copy(alpha = 0.68f),
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
                .clip(RoundedCornerShape(999.dp))
                .clickable { aboutOpen = true }
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .then(rememberEntranceModifier(cards.size)),
        )

        IconButton(
            onClick = onOpenAppearance,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 8.dp, end = 8.dp),
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = "外观设置",
                tint = fg.copy(alpha = 0.72f),
            )
        }

        // 根 Box 的最后一个子节点 = 层级最高，压在卡片和设置按钮之上。放在同一棵组合树里（而不是
        // Dialog/Popup）才能采样到上面那个 backdrop，见 LauncherAbout.kt 头部注释。
        if (aboutOpen) {
            BackHandler { aboutOpen = false }
            LauncherAboutOverlay(
                backdrop = backdrop,
                onDismiss = { aboutOpen = false },
            )
        }
    }
}

/**
 * Everything the glass samples from is painted in one [drawBehind] pass on the same node
 * that carries [layerBackdrop] — a prior version spread the gradient + glow blobs across
 * nested child `Box`es, and the capture came back blank. Keeping capture and content in a
 * single DrawScope removes that ambiguity.
 */
@Composable
internal fun LauncherBackground(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "ambientBlobs")
    val drift1 by infiniteTransition.animateFloat(
        initialValue = -30f,
        targetValue = 30f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift1",
    )
    val drift2 by infiniteTransition.animateFloat(
        initialValue = 24f,
        targetValue = -24f,
        animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift2",
    )

    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF10081F), Color(0xFF07050D), Color(0xFF020203))
                    )
                )
                val w = size.width
                val h = size.height
                val radiusPx = 190.dp.toPx()
                fun blob(cx: Float, cy: Float, color: Color) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(color, color.copy(alpha = 0f)),
                            center = Offset(cx, cy),
                            radius = radiusPx,
                        ),
                        radius = radiusPx,
                        center = Offset(cx, cy),
                    )
                }
                blob(w * 0.24f + drift1, h * 0.30f + drift1 * 0.5f, AccentPurple.copy(alpha = 0.85f))
                blob(w * 0.76f - drift2, h * 0.30f + drift2 * 0.4f, AccentTeal.copy(alpha = 0.8f))
                blob(w * 0.24f - drift2, h * 0.72f - drift1 * 0.3f, AccentMagenta.copy(alpha = 0.85f))
                blob(w * 0.76f + drift1, h * 0.72f - drift2 * 0.4f, AccentAmber.copy(alpha = 0.75f))
            }
    )
}

/** Staggered fade-in + slight upward move, delayed by [index] * 90ms. */
@Composable
private fun rememberEntranceModifier(index: Int): Modifier {
    val density = LocalDensity.current
    val startOffsetPx = remember { with(density) { 24.dp.toPx() } }
    val alphaAnim = remember { Animatable(0f) }
    val offsetAnim = remember { Animatable(startOffsetPx) }
    LaunchedEffect(Unit) {
        delay(index * 90L)
        launch { alphaAnim.animateTo(1f, animationSpec = tween(420, easing = FastOutSlowInEasing)) }
        launch {
            offsetAnim.animateTo(
                0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessLow,
                ),
            )
        }
    }
    return Modifier.graphicsLayer {
        alpha = alphaAnim.value
        translationY = offsetAnim.value
    }
}

// 长条胶囊 (2026-08-16, was a square 2x2 card) - tunedGlass() always routes through patchedLens()
// internally (GlassEffects.kt), so this extreme an aspect ratio is automatically safe from the
// dispersion-shader out-of-bounds bug documented on the player control bar/outline drawer, with no
// special-casing needed here. chromaticAberration stays ON (liveGlassParamsFor's baseline default)
// - explicit call, not an accident: this card's bold title text was judged able to tolerate the
// fringing that forced it off on the narrower/denser panels elsewhere in this app.
@Composable
private fun LauncherCard(
    data: LauncherCardData,
    backdrop: Backdrop?,
    fg: Color,
    bgApproxColor: Color,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressProgress by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "pressProgress",
    )
    // Live/reactive (2026-08-16, was a one-shot `remember { defaultGlassParamsFor(...) } }`) so
    // this card picks up GlassTuningActivity playground edits and glass presets the same way
    // every other real glass surface in the app already does - matches "配方跟提词器现有组件保持
    // 一致" instead of quietly staying frozen at whatever was true on first composition.
    val glassParams = liveGlassParamsFor(GlassTarget.LauncherCard)

    // 2026-09-04 - 卡片内容的前景色必须跟着**卡片自己的表面颜色**走，不能跟页面背景走。
    //
    // 修的是一个真 bug（模拟器实拍确认）：卡片默认被 tint 画成不透明白底，而 fg 是按页面背景
    // 亮度算的——深色背景下 fg = 白色，于是白卡片上写白字，五张卡整个变成空白色块。此前标题用的
    // 是 lerp(fg, accent, 0.85f)，彩色在白卡上还看得见，所以这个矛盾一直潜伏着；标题改成纯 fg
    // 之后才现形。
    //
    // 卡片表面 = tint 色以 tint.alpha 叠在背景上。tint 不透明（默认）→ 恒为白 → 深色前景；
    // 用户把 tint.alpha 拖低让玻璃透出来 → 表面趋近背景 → 前景自动跟着背景走。两种情况都对。
    val cardFg = if (backdrop != null && glassParams.tint.enabled) {
        val surface = Color(glassParams.tint.colorArgb.toInt())
            .compositeOver(bgApproxColor, glassParams.tint.alpha)
        if (surface.prefersLightForegroundOnTop()) Color.White else LauncherDarkForeground
    } else {
        fg
    }

    if (backdrop != null) {
        Box(
            modifier
                .height(LauncherCapsuleHeight)
                .tunedGlass(
                    backdrop = backdrop,
                    params = glassParams,
                    cornerRadius = 999.dp,
                    // No per-card accent tint on the glass surface itself (2026-08-16, explicit
                    // "这次先不做每张卡片单独的品牌色染色" call) - once the background is a shared
                    // customizable gradient/image, each capsule already refracts a different slice
                    // of it, which is differentiation enough without also painting the glass body.
                    layerBlock = {
                        val s = 1f - 0.018f * pressProgress
                        scaleX = s
                        scaleY = s
                        alpha = 1f - 0.04f * pressProgress
                    },
                )
                .clickable(interactionSource = interactionSource, indication = null, onClick = data.onClick)
                .alpha(if (data.dimmed) 0.55f else 1f)
                .semantics(mergeDescendants = true) {
                    contentDescription = "${data.title}，${data.subtitle}"
                },
        ) {
            LauncherCardContent(data, cardFg)
        }
    } else {
        // API < 33: no AGSL runtime shaders available, fall back to a plain
        // semi-transparent capsule instead of relying on silent effect degrade.
        Box(
            modifier
                .height(LauncherCapsuleHeight)
                .graphicsLayer {
                    val s = 1f - 0.018f * pressProgress
                    scaleX = s
                    scaleY = s
                    alpha = 1f - 0.04f * pressProgress
                }
                .clip(RoundedCornerShape(999.dp))
                .background(fg.copy(alpha = 0.10f))
                .clickable(interactionSource = interactionSource, indication = null, onClick = data.onClick)
                .alpha(if (data.dimmed) 0.55f else 1f)
                .semantics(mergeDescendants = true) {
                    contentDescription = "${data.title}，${data.subtitle}"
                },
        ) {
            LauncherCardContent(data, cardFg)
        }
    }
}

// 图标居左，标题+副标题纵向堆叠居右 (2026-08-16, was icon-on-top/title-centered/subtitle-below
// for the old square card) - trailing chevron is a small new affordance (matches this app's own
// AppearanceScreen ListItem rows), not scope creep: a full-width tappable row reads like a list
// item, and a list item with no "this is tappable" hint is the odd one out here, not the chevron.
@Composable
private fun BoxScope.LauncherCardContent(data: LauncherCardData, fg: Color) {
    Row(
        modifier = Modifier
            .matchParentSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = data.icon,
            contentDescription = null,
            tint = fg.copy(alpha = 0.82f),
            modifier = Modifier.size(28.dp),
        )
        // 2026-08-16 - 胶囊高度砍半后（80dp->40dp），图标/标题字号不变的前提下，标题+副标题两行
        // 堆叠肯定放不下 36dp 高的图标 - 副标题挪到跟标题同一行（不再是 Column 纵向堆叠），整体
        // 只占一行的高度。maxLines=1+ellipsis 兜底，防止任何一侧文字把行高撑爆。
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // 2026-08-16 - 标题从统一白字改成每个功能自己的强调色（跟图标同一份 accent，但混得更
            // 深/更饱和，不是照抄图标那个偏浅的 0.5 混合），加粗从 SemiBold 提到 Bold。
            Text(
                text = data.title,
                color = fg,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = data.subtitle,
                color = fg.copy(alpha = 0.68f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = fg.copy(alpha = 0.48f),
            modifier = Modifier.size(20.dp),
        )
    }
}
