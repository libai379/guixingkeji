/*
 * 启动页的「关于 / 使用说明」浮层 —— 2026-08-20。
 *
 * 跟提词器的设置面板一样，这是一个"同一棵组合树里的覆盖层"，不是 Dialog/Popup：Dialog 会另开一个
 * 系统窗口，物理上看不见本页面的内容，也就采样不到 backdrop，液态玻璃会变成一块空白板子（这个坑
 * 在 docs/PITFALLS.md 里已经记过一次）。所以这里跟 TeleprompterSettingsWindow 用同一套写法：作为
 * 根 Box 的最后一个子节点渲染，既压在所有内容之上，又能正常折射背后的真实画面。
 *
 * 内容按版本自动裁剪：公开版（独立工程 F:\gkd-public）没有"去广告"和"虚拟定位"，对应的使用说明
 * 条目也不会出现——说明文档和功能卡片读的是同一组 Edition 常量，不会出现"说明书里写了但应用里
 * 找不到"的尴尬。
 */
package com.guixing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import com.kashif_e.backdrop.Backdrop
import com.guixing.glasstuning.GlassTarget
import com.guixing.glasstuning.liveGlassParamsFor
import com.guixing.glasstuning.tunedGlass

/*
 * 面板文字颜色 —— 2026-09-05 由白改黑（用户要求"关于里面字体改为黑色"）。
 *
 * 改得动的前提是这块面板本来就是浅色底：默认液态参数是 PlainGlassParams（不透明白底，见
 * glasstuning/GlassTuningModel.kt），白字其实一直是靠"深色背景"这个假设撑着的，用户把背景调浅
 * 就看不见了——跟启动页卡片那次"白卡片上写白字"是同一个根因。改成黑字之后，下面 API<33 的降级
 * 分支也必须跟着从深色底改成浅色底，否则那条路径上就轮到黑字配深底看不见了。
 *
 * ⚠️ 本文件在 sync-to-public.sh 的 NEVER_COPY 里（两版说明条目数量不同），这段改动是**两个工程
 * 各改一遍**的，改这里记得同步看一眼 F:\guixing 那份。
 */
private val AboutTextPrimary = Color.Black
private val AboutTextSecondary = Color.Black.copy(alpha = 0.72f)
private val AboutTextLabel = Color.Black.copy(alpha = 0.55f)
private val AboutDivider = Color.Black.copy(alpha = 0.14f)

private data class UsageEntry(
    val title: String,
    val body: String,
)

/**
 * 各功能的使用说明。本工程是公开版，"去广告""虚拟定位"的源码已经整个删掉，所以这里就是一份普通的
 * 三项列表，没有按开关裁剪这回事。
 */
private fun usageEntries(): List<UsageEntry> = listOf(
    UsageEntry(
        title = "定位修改",
        body = "修改照片、视频文件里记录的 GPS 位置信息。\n" +
            "选择文件后在地图上选点，确认保存即可写入新的位置。建议先备份原文件。",
    ),
    UsageEntry(
        title = "提词器",
        body = "拍摄、直播时的滚动提词。\n" +
            "支持新建文稿或导入 Word 文档，按章节编辑；播放页全屏滚动，可随时调整字号、行距、" +
            "滚动速度和配色，横竖屏都能用。\n" +
            "编辑页右上角齿轮进设置面板，长按大纲可拖动调整章节顺序。",
    ),
    UsageEntry(
        title = "电话拦截",
        body = "按号码前缀拦截来电，支持黑名单和白名单两种模式。\n" +
            "黑名单：命中前缀的号码被拦截，其余正常接通。白名单：只有命中前缀的号码能接通，其余全部拦截。\n" +
            "两种模式各自维护独立的前缀列表，互不影响。首次使用需要授予「来电筛选」权限。\n" +
            "被拦截的来电仍会留在系统通话记录里，这是系统行为，属于正常现象。",
    ),
)

@Composable
fun LauncherAboutOverlay(
    backdrop: Backdrop?,
    onDismiss: () -> Unit,
) {
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        entrance.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
    }
    val entries = remember { usageEntries() }
    val scrollState = rememberScrollState()

    Box(Modifier.fillMaxSize()) {
        // 点击遮罩关闭
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f * entrance.value))
                .clickable(interactionSource = null, indication = null, onClick = onDismiss),
        )

        Column(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.72f)
                .graphicsLayer {
                    alpha = entrance.value
                    val s = 0.92f + 0.08f * entrance.value
                    scaleX = s
                    scaleY = s
                }
                .let { base ->
                    if (backdrop != null) {
                        base.tunedGlass(
                            backdrop = backdrop,
                            // 这块面板上全是正文小字，色散会让文字边缘发彩边、明显影响可读性，
                            // 所以单独关掉——跟提词器顶栏、设置面板的处理一致。
                            params = liveGlassParamsFor(GlassTarget.SettingsPanel)
                                .copy(chromaticAberration = false),
                            cornerRadius = 28.dp,
                        )
                    } else {
                        // API < 33：没有 AGSL 运行时着色器，退化成不透明面板，保证可读。跟着上面
                        // 文字改黑一起从深色底换成浅色底——这条分支和液态那条必须同为浅底。
                        base
                            .clip(RoundedCornerShape(28.dp))
                            .background(Color(0xFFF7F7F7).copy(alpha = 0.98f))
                    }
                }
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "关于",
                    color = AboutTextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = AboutTextSecondary,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                InfoRow("应用名称", META.appName)
                InfoRow("版本类型", Edition.LABEL)
                InfoRow("版本号", "${META.versionName}（${META.versionCode}）")
                InfoRow("开发者", Edition.DEVELOPER)

                Spacer(Modifier.height(18.dp))
                HorizontalDivider(color = AboutDivider)
                Spacer(Modifier.height(18.dp))

                Text(
                    "使用说明",
                    color = AboutTextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))

                entries.forEachIndexed { index, entry ->
                    if (index > 0) Spacer(Modifier.height(16.dp))
                    Text(
                        entry.title,
                        color = AboutTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        entry.body,
                        color = AboutTextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                    )
                }

                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = AboutTextLabel,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            color = AboutTextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
