/*
 * 背景五项调节（亮度/对比度/饱和度/模糊度/透明度）的**纯数学部分**。
 *
 * 2026-09-02 从 GlassBackground.kt 拆出来：那个文件有 `launcherBackgroundFlow` 这类需要 Android
 * Context 的顶层 val，JVM 单元测试一加载整个文件的类就 ExceptionInInitializerError，导致这几个
 * 明明是纯函数的东西没法被测。拆开之后 GlassBackgroundAdjustTest 才能直接调它们做数值对照。
 */
package com.guixing.glassui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix

/**
 * 半透明背景的**合成基底色**。
 *
 * 2026-09-02 - 修"设置页里的背景预览和真实启动页颜色不一样"：这两处渲染的是同一份
 * `launcherBackgroundFlow`，但透明度 < 100% 时颜色要跟**背后的东西**混合，而两边背后不是一个东西
 * ——启动页背后是窗口底色（近黑），设置页背后是 Scaffold 的主题容器色。同配置、不同底，合出来当然
 * 两个颜色，而且主题一切换差得更远。
 *
 * 所以这里显式铺一层固定的不透明基底再画背景，**让合成结果不再取决于调用方背后是什么**。取近黑是
 * 因为启动页本来就是这个底，改完等于"设置页去对齐启动页"，而不是反过来把启动页也改掉。
 *
 * 透明度 = 100% 时这一层被完全盖住，没有任何视觉变化，也不会多一次可见的过绘（同一个节点上的两次
 * background 是绘制指令，不是额外图层）。
 */
internal val GLASS_BG_BASE = Color(0xFF07050D)

internal fun composeColorMatrices(outer: ColorMatrix, inner: ColorMatrix): ColorMatrix {
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

internal fun Color.adjustedForGlassBg(
    brightness: Float,
    contrast: Float,
    saturation: Float,
    alphaMul: Float,
): Color {
    val lum = 0.2125f * red + 0.7154f * green + 0.0721f * blue
    fun ch(v: Float): Float {
        val sat = lum + (v - lum) * saturation
        val con = (sat - 0.5f) * contrast + 0.5f
        return (con * brightness).coerceIn(0f, 1f)
    }
    return Color(ch(red), ch(green), ch(blue), (alpha * alphaMul).coerceIn(0f, 1f))
}

internal fun glassBgColorMatrix(brightness: Float, contrast: Float, saturation: Float): ColorMatrix {
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

/**
 * 这份背景配置**实际画出来的大致颜色**，用来决定压在它上面的文字该用浅色还是深色。
 *
 * 2026-09-02 - 启动页的标题/副标题/齿轮/版本行原本一律写死白色。在背景恒为深色渐变的年代这没问题，
 * 但背景改成用户可选任意颜色之后，选浅色就是白字配白底、整屏文字消失（真机截图确认）。写死前景色
 * 的前提已经不成立了，所以这里把"背景大致是什么颜色"算出来，让前景跟着走。
 *
 * 各模式的取值依据：
 * - Solid：就是那个颜色本身，精确。
 * - Gradient：所有色标的均值——渐变两端可能一深一浅，均值是最省事且不会大错的近似。
 * - Image：**无法廉价判断**（要解码采样整张图），返回 null，调用方按"深色底"处理并自己加衬底。
 * - Default：内置深色流光背景，固定当深色。
 *
 * 五项调节里只有亮度/对比度/饱和度会改颜色，所以这里跟渲染路径用同一个 [adjustedForGlassBg]，
 * 不会出现"背景被调亮了但前景还按原色判断"的偏差。
 */
fun GlassBackgroundConfig.approximateBackgroundColor(): Color? = when (mode) {
    GlassBgMode.Solid -> Color(solidColorArgb.toInt())
        .adjustedForGlassBg(imageBrightness, imageContrast, imageSaturation, alphaMul = 1f)

    GlassBgMode.Gradient -> {
        val cs = gradientColorsArgb.map {
            Color(it.toInt()).adjustedForGlassBg(imageBrightness, imageContrast, imageSaturation, alphaMul = 1f)
        }
        if (cs.isEmpty()) {
            null
        } else {
            Color(
                red = cs.map { it.red }.average().toFloat(),
                green = cs.map { it.green }.average().toFloat(),
                blue = cs.map { it.blue }.average().toFloat(),
            )
        }
    }

    GlassBgMode.Image -> null
    GlassBgMode.Default -> GLASS_BG_BASE
}

/**
 * 压在这份背景上的前景是否该用浅色（白字）。
 *
 * 阈值 0.5 用的是感知亮度（Rec.601 权重），不是 RGB 平均——纯绿和纯蓝的 RGB 均值一样，但人眼看到
 * 的明暗差得远，按均值判断会在绿色背景上选错。图片模式判断不了，返回 true（保持白字），调用方
 * 该自己加深色衬底。
 */
fun GlassBackgroundConfig.prefersLightForeground(): Boolean {
    val c = approximateBackgroundColor() ?: return true
    val luminance = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
    return luminance < 0.5f
}

/**
 * 把一个半透明色叠在 [base] 上得到的实色。用来回答"这块玻璃卡片最终看起来是什么颜色"。
 *
 * 2026-09-04 - 为修"深色背景下启动页卡片内容全部消失"而加。那个 bug 的成因是：卡片前景色跟着
 * **页面背景**的明暗走，而卡片本身被 tint 画成了不透明白底——深色背景 → 前景判定为白 → 白卡片
 * 上写白字。前景色必须跟着**卡片自己的表面颜色**走，而卡片表面 = tint 色以 tint.alpha 叠在背景上。
 */
fun Color.compositeOver(base: Color, alpha: Float): Color {
    val a = alpha.coerceIn(0f, 1f)
    return Color(
        red = red * a + base.red * (1f - a),
        green = green * a + base.green * (1f - a),
        blue = blue * a + base.blue * (1f - a),
    )
}

/** 这个颜色上面该压浅色前景（白字）还是深色前景。阈值用 Rec.601 感知亮度，理由同
 *  [prefersLightForeground]：RGB 均值会在绿色/蓝色上判断错。 */
fun Color.prefersLightForegroundOnTop(): Boolean =
    (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f

/** 深色前景基色。不用纯黑：纯黑压在白色玻璃面上过硬，这个近黑带一点冷调，跟金色标题也更协调。
 *  启动页卡片、关于浮层、提词器文档库、电话拦截、外观设置共用这一份。 */
val GLASS_DARK_FOREGROUND = Color(0xFF14121A)

/**
 * 一块玻璃表面最终看起来的实色。
 *
 * [tintColorArgb]/[tintAlpha] 是这块玻璃的 tint 参数（`tintEnabled=false` 表示没有 tint、
 * 玻璃直接透出背后的东西）；[scrimAlpha] 是这块玻璃**底下**额外压着的黑色遮罩不透明度
 * （浮层类面板才有，普通卡片传 0）。
 *
 * 图片模式判断不了背景亮度（[approximateBackgroundColor] 返回 null），退回深色基底
 * [GLASS_BG_BASE]，跟 [prefersLightForeground] 对图片模式的处理保持一致，不各写一套。
 */
fun glassSurfaceColorOver(
    base: Color,
    tintEnabled: Boolean,
    tintColorArgb: Long,
    tintAlpha: Float,
    scrimAlpha: Float = 0f,
): Color {
    val scrimmed = if (scrimAlpha > 0f) Color.Black.compositeOver(base, scrimAlpha) else base
    return if (tintEnabled) {
        Color(tintColorArgb.toInt()).compositeOver(scrimmed, tintAlpha)
    } else {
        scrimmed
    }
}

/** [glassSurfaceColorOver] 的便捷重载：从背景配置推近似实色。 */
fun GlassBackgroundConfig.glassSurfaceColor(
    tintEnabled: Boolean,
    tintColorArgb: Long,
    tintAlpha: Float,
    scrimAlpha: Float = 0f,
): Color = glassSurfaceColorOver(
    approximateBackgroundColor() ?: GLASS_BG_BASE,
    tintEnabled, tintColorArgb, tintAlpha, scrimAlpha,
)

/**
 * 压在一块玻璃表面上的前景色（白 或 [GLASS_DARK_FOREGROUND]）。**本项目所有"文字压在可配背景上"
 * 的地方都必须走这里，不要写死颜色，也不要用 MaterialTheme 的 onXxx。**
 *
 * 这个项目在同一个坑上摔过三次（详见 docs/PITFALLS.md「猜表面颜色」那条）：写死白字 → 浅背景下
 * 消失；改成写死黑字 → 深背景下消失；跟着**页面背景**走 → 白卡片上写白字。三次的共同点是都在
 * **猜**这块玻璃最终是什么颜色。唯一可靠的做法是按 [glassSurfaceColor] 真算一遍再判感知亮度，
 * 因为背景四种模式和 tint 的开关/颜色/透明度全都是用户可调的，任何"底色固定"的前提都不成立。
 */
fun glassForegroundOver(
    base: Color,
    tintEnabled: Boolean,
    tintColorArgb: Long,
    tintAlpha: Float,
    scrimAlpha: Float = 0f,
): Color {
    val surface = glassSurfaceColorOver(base, tintEnabled, tintColorArgb, tintAlpha, scrimAlpha)
    return if (surface.prefersLightForegroundOnTop()) Color.White else GLASS_DARK_FOREGROUND
}

/** [glassForegroundOver] 的便捷重载：从背景配置推近似实色。调用方手里已经有近似实色时
 *  （比如卡片组件只拿到 bgApproxColor 参数）直接用上面那个，不要为了凑参数把整份配置传下去。 */
fun GlassBackgroundConfig.glassForegroundOn(
    tintEnabled: Boolean,
    tintColorArgb: Long,
    tintAlpha: Float,
    scrimAlpha: Float = 0f,
): Color = glassForegroundOver(
    approximateBackgroundColor() ?: GLASS_BG_BASE,
    tintEnabled, tintColorArgb, tintAlpha, scrimAlpha,
)
