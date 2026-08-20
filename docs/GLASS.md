# 液态玻璃专项

内容太多不适合塞进 CLAUDE.md，单独一份。这里记的是"为什么长这样"，不是"怎么用"（怎么用直接看
`glasstuning/GlassEffects.kt` 里 `tunedGlass()` 的调用方就是范例）。

## 用的库、版本、为什么没迁移到官方

依赖声明在 `app/build.gradle.kts`：

```
implementation("io.github.kashif-mehmood-km:backdrop:0.0.1-alpha02")
```

这是 `Kashif-E/KMPLiquidGlass`，`com.kyant.backdrop`（`Kyant0/AndroidLiquidGlass`）的一个 fork，
包名换成了 `com.kashif_e.backdrop`，核心 shader 代码跟官方逐字节相同，多出
`exposureAdjustment`/`gammaAdjustment`/`reflectiveGlass`/`progressiveBlur` 几个官方没有的效果
（本项目只用了 `progressiveBlur`，在提词器设置面板滚动边缘淡出那里）。

官方最新是 `io.github.kyant0:backdrop:2.0.0`（本地克隆在 `F:\liquidglass-ref`，2026-08-08 调研时确认
的版本号 —— 版本号必须从本地 clone 读，见 CLAUDE.md 准则 2）。

**没有迁移**，原因：
1. 迁移不解决任何问题——色散花屏 bug 官方也没修（见下面"色散 clamp 补丁"），迁移过去原样带过去。
2. `progressiveBlur` 官方没有，迁移会丢功能，得自己用 `runtimeShaderEffect` 重写。
3. fork 已经在用、稳定，没有必须迁移的驱动力。

如果以后真要迁移，已知的坑：fork 的 `effects/Lens.kt` 圆角识别只认
`androidx.compose.foundation.shape.CornerBasedShape`，官方多一个 `com.kyant.shapes.RoundedRectangularShape`
分支——这也是为什么这个项目的组件永远不用 `com.kyant.shapes.Capsule()`/`RoundedRectangle()`，全部用
`RoundedCornerShape` 等价物（`RoundedCornerShape(50)` 代替胶囊，圆角半径的 dp 值代替
`RoundedRectangle(dp)`）。

## 效果链的正确顺序

`Modifier.tunedGlass()`（`glasstuning/GlassEffects.kt`）内部固定顺序：

```
vibrancy()（可选）→ blur() → patchedLens()（含可选色散）→ extraEffects（可选钩子）
```

加在外面的 `highlight`/`shadow`/`innerShadow`/`onDrawSurface` 是 `drawBackdrop()` 自己的独立参数，
不在这条 effects 链里。`onDrawSurface` 里如果启用了 tint，画法是
`drawRect(color, BlendMode.Hue) + drawRect(color.copy(alpha))`——库里没有专门的 `tint()` 效果，这是
catalog 自己的 `LiquidButton` 用的手法，照抄的。

**背景捕获必须是 sibling，不能是 ancestor**：`layerBackdrop(x)` 挂的节点，不能是采样
`backdrop=x` 的玻璃控件的祖先节点，哪怕只是"挂在同一个 Column 上，Column 又包含玻璃控件"这种间接
祖先关系也不行——会形成 RenderNode 循环捕获，原生栈溢出 SIGSEGV，不是 Kotlin 异常，`adb logcat`
默认看不到，要 `grep -iE "fatal signal|tombstone"` 才能看到（详细踩坑过程见 PITFALLS.md）。正确模式
是背景单独一个 `Box(Modifier.matchParentSize().layerBackdrop(x).background(color))` 作为玻�璃控件的
**同级 sibling**，不是父节点。项目里所有玻璃面（设置面板、颜色选择器、大纲抽屉、Dialog、播放控制栏、
虚拟定位前置检查页）都是这个结构。

启动页背景还有一条更窄的约束：`LauncherBackground` 必须整体是挂在 `layerBackdrop` 节点上的**单个**
`drawBehind`，不能拆成多个子 Composable 分别画——拆开后捕获会返回空白（本项目最早踩的一个坑，见
`LauncherActivity.kt:LauncherBackground` 的文档注释）。

## patchedLens() 补丁：为什么、在哪

**现象**：细长玻璃面板（大纲抽屉 ~1:6.2、播放控制栏 ~1:6.9）开着 `chromaticAberration` 时，边角区域
出现彩色雪花状花屏。

**根因**（2026-08-08~09 深入调查确认）：库的色散 shader（`RoundedRectRefractionWithDispersionShaderString`，
fork 和官方 2.0.0 逐字节相同，从 KMP 重写以来没被碰过）用
`dispersionIntensity = chromaticAberration * (centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y)`
算色散强度——这是像素**原始位置**的鞍形函数，不是法线，面板越窄长这个值越容易在角落逼近 ±1。7 次
`content.eval()` 采样坐标（red/orange/yellow/green/cyan/blue/purple——最早口头统计成 6 次是数错了）全部
没有 clamp，采样越界读到垃圾数据，就是看到的彩色雪花。库自己的 `ReflectiveGlassShaderString`（同文件）
对色散坐标有 `clamp()`，证明这是遗漏不是有意为之。

**改不了库本身**：`RoundedRectRefractionWithDispersionShaderString` 是 `internal`，`internal` 在 Kotlin
里模块级隔离，app 模块摸不到，不管依赖的是 fork 还是官方。

**修法**：`glasstuning/PatchedLens.kt`——AGSL 原样抄一份，7 处 `content.eval()` 全部加
`clamp(coord, float2(0.0), size)`，通过库公开的 `BackdropEffectScope.obtainRuntimeShader` +
`effects.effect()` 组装，不需要 vendor 或 fork 整个库。非色散路径没有这个 bug，直接委托给库自己的
`lens()`。

`tunedGlass()` 内部已经从 `lens()` 换成 `patchedLens()`；后来搬进来的 `LiquidButton`/`LiquidSlider`/
`LiquidToggle`（见下）虽然不走 `tunedGlass()`，内部也手动把 `lens(chromaticAberration=true)` 换成了
`patchedLens(...)`，保证项目里没有绕开补丁的 `chromaticAberration=true` 调用点。核对方式：

```
grep -rn "\.lens(" app/src/main/kotlin/li/songe/gkd
```

应该只有 `PatchedLens.kt` 自己 import 了库原始的 `lens()`（用于非色散委托）。

## 调参面板

**只有一个 Activity/一个屏幕实现**（`glasstuning/GlassTuningActivity.kt` 的 `GlassTuningScreen`），
**四个入口全部指向它**，靠 `restrictedIntent(context, targets: List<GlassTarget>)` 传不同的
target 列表来区分"这次只让改哪几个"。**改这个屏幕本身（滑块样式、FPS 计数、导出逻辑、预设区
UI）只需要改一处，四个入口自动同步**——这是 2026-08-16 从"提词器三屏共用一份参数"拆分成"三屏
各自独立"时特意保留的结构，不要因为新增了几个 target 就分叉出第二份实现。

四个入口（每个都调用 `restrictedIntent`，传各自的 target 列表常量）：

| 入口 | 触发位置 | target 列表常量 | 覆盖的 GlassTarget |
|---|---|---|---|
| 启动页/外观设置 | 齿轮 → 外观 → 液态效果调节 | `GeneralGlassTargets` | `LauncherCard`, `SettingsPanel` |
| 提词器文档库 | 目录页顶栏齿轮图标 | `TeleprompterLibraryGlassTargets` | `TeleprompterLibrary` |
| 提词器编辑页 | 编辑页设置面板"更多液态效果" | `TeleprompterEditorGlassTargets` | `TeleprompterEditor`, `TeleprompterSettingsWindow` |
| 提词器播放页 | 同一个编辑页设置面板，"播放页"分节里单独一个按钮（播放页自己没有顶栏/设置入口，借用这里，按钮下方有一行小字说明这是调播放页不是调编辑页） | `TeleprompterPlayerGlassTargets` | `TeleprompterPlayer` |

`restrictedIntent` 传的 target 列表非空时，`GlassTuningScreen` 会额外显示"预设"区
（`PresetSection`：应用/保存当前为新预设/改名/删除）——四个入口传的都是非空列表，所以**四处都有
预设功能**，不需要单独给某个入口加。`globalIntent()`（启动页/外观设置那个入口的工厂方法）现在也只是
`restrictedIntent(context, GeneralGlassTargets)` 的包装，不再是"不限定 target、无预设区"的另一套
行为。

`GlassTarget` 枚举（`glasstuning/GlassTuningModel.kt`）目前 7 个值：`LauncherCard`/
`SettingsPanel`/`Other`/`TeleprompterSettingsWindow`/`TeleprompterLibrary`/`TeleprompterEditor`/
`TeleprompterPlayer`。`Other` 是历史遗留，2026-08-16 之前提词器三屏（文档库/编辑页/播放页）全部
共用它，导致拖任意一屏的滑块，另外两屏跟着变——拆分后 `Other` 已经没有真实调用点了（保留这个
枚举值只是不想动 `GlassParams`/存储格式，没有删除的必要）。提词器共用的大纲抽屉组件
（`TeleprompterOutlinePanel`/`OutlineScrubTrigger`，定义在 `TeleprompterEditorScreen.kt`，编辑
页/播放页两边调用）因此也不能再内部写死某个 target，改成了 `target: GlassTarget` 参数，由调用方
各传各的。

参数按作用对象（`GlassTarget`）分别存，存储在
`li.songe.gkd.store.createAnyFlow(key="glass_tuning", ...)`（JSON 文件，防抖写入），实现在
`glasstuning/GlassTuningStore.kt`。预设存储（`GlassPresetStore.kt`）是**一份不分入口的扁平列表**
——`allPresetsFor(store, targets)` 靠 `paramsByTarget.keys == targets.map{it.name}.toSet()`
精确匹配来过滤，不会把编辑页存的预设显示在播放页的预设列表里（每个预设的 key 集合天生就是它保存
时那份 targets 列表，用这个做分区键不需要额外加"属于哪个入口"的字段）。改 target 列表的分组（比如
以后再拆更细）时，这个过滤逻辑不用动，天然跟着新的分组走。

面板本身有 FPS 计数、背景切换（纯色/渐变/图片/真实内容）、一键导出 JSON/Kotlin 到剪贴板、
"恢复默认"按钮（清空该 target 的覆盖值，回落到下面的基线）。

## 当前基线参数值

`glasstuning/GlassTuningModel.kt` 里的 `TunedGlassParams`，2026-08-08 在调参面板里手动调出来、导出、
固化进代码的，三个 target 目前共用同一组值：

```
cornerRadiusDp = 36.2, blurRadiusDp = 0, refractionHeightDp = 48, refractionAmountDp = 64,
depthEffect = true, chromaticAberration = true, vibrancy = true,
highlight.enabled = false,
shadow.enabled = true, shadow.radiusDp ≈ 6.27, shadow.alpha ≈ 0.645,
innerShadow.enabled = false, tint.enabled = false
```

改这组值只应该通过调参面板调、导出、贴回这个常量——不要手改这几个数字本身（会跟"最后一次导出"的
说法对不上）。

## 组件现状（2026-08-11 后）

`LiquidButton`/`LiquidSlider`/`LiquidToggle`（`glassui/`）是官方 catalog 组件的完整移植（含
`DampedDragAnimation`/`DragGestureInspector`/`InteractiveHighlight` 三个支撑文件），走的是 catalog
自己写死的效果参数，**不受** `TunedGlassParams` 影响——这三个组件之外的所有玻璃面（Dialog 外壳、
启动页卡片、设置面板背景、大纲抽屉、播放控制栏、颜色选择器）才是 `TunedGlassParams` 管的范围。

`LiquidDialog` 额外叠了 catalog Dialog recipe 里的 `colorControls(brightness=...)`（只加亮度，
没重复加饱和度——`vibrancy()` 本身就是 `colorControls(saturation=1.5f)`，两个都加会变成
约 2.25 倍饱和度）。

`AdaptiveLuminanceGlass.kt` 已删除——建了但从没接到任何真实界面，且跟已有的
`TeleprompterColorPickerPopup` 对比度警告系统场景冲突（见 DECISIONS.md）。
