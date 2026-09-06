# 架构决策记录

只追加。旧决定被推翻时，在新条目里写清楚推翻了哪条、为什么；旧条目保留——这份文件就是
"为什么现在长这样"的历史。

格式：日期 / 决定 / 为什么 / 否决了哪些方案及原因。

2026-09-06 精简过一轮：合并了同一件事的连续订正，删掉重复陈述，决定和否决理由一条没删。

---

## 2026-08-04　fork 策略：只加不改

新功能全部放新增文件/新增包，尽量不动 GKD 上游已有文件。包名 `li.songe.gkd` → `li.songe.gkd.taiclear`，
可跟官方 GKD 同时安装。
**为什么**：GKD 主仓库还在持续更新（1300+ 提交），大改上游文件会让每次同步都要解冲突。
只加不改，冲突面就只剩"上游删了我们用到的东西"一种。
**否决**：hard fork 后自由重构——长期维护成本更高。

## 2026-08-04　原生 Compose vs WebView 各管一段

提词器（`teleprompter/`）全原生 Kotlin + Compose；GPS 元数据修改（`gps-modifier.html`）、
虚拟定位地图选点（`mock-location.html`）用 WebView。
**为什么**（推断，代码里无明确注释）：提词器要跟液态玻璃、拖拽手势、富文本紧密集成且需顺滑滚动动画，
原生控制力更强；后两者本质是包一层现成 JS 生态（EXIF、Leaflet），造轮子收益不大。
**推翻代价**：地图选点原生化需自己实现高德瓦片 + GCJ02 纠偏，目前是 Leaflet 插件现成的。

---

## 2026-08-08　液态玻璃背景捕获：sibling 模式，不用 ancestor

`layerBackdrop(x)` 的捕获节点必须是采样 `backdrop=x` 的玻璃控件的**同级**节点，不能是祖先
（哪怕是"共同挂在同一个 Column 上"这种间接祖先）。
**为什么**：ancestor 模式让捕获树包含自己，循环捕获 → 原生栈溢出 SIGSEGV（真机 tombstone 证实）。
**否决**：曾怀疑折射参数过大导致越界读取，加了 `maxRefractionPx` 钳制——钳制本身是合理防御但不是根因。

## 2026-08-09　不迁移到官方 `io.github.kyant0:backdrop:2.0.0`

继续用 `io.github.kashif-mehmood-km:backdrop:0.0.1-alpha02` fork。
**为什么**：逐行核对过官方 2.0.0 的色散 shader，跟 fork 逐字节相同、同样没有 clamp——
迁移不解决花屏。官方还缺 fork 独有的 `progressiveBlur()`（设置面板在用），迁移反而要先补。
**否决**：迁移后再自己修——迁移成本换不来任何收益，色散 bug 迁不迁都要自己修。

## 2026-08-09　色散花屏：本地 `patchedLens()` 重实现，不 vendor 整个库

在 `glasstuning/PatchedLens.kt` 用库公开的 `obtainRuntimeShader`/`effects.effect()` 原语重新实现
一份带 clamp 的色散 shader，只在 `chromaticAberration=true` 时用；非色散路径继续委托库的 `lens()`。
**为什么**：库的 bug shader 是 `internal`，摸不到。只有两条路——vendor 整个库（要跟上游手动 diff 合并，
且这库大半 KMP 多平台代码用不上），或用公开原语在自己包里重实现（改动小、跟库版本解耦）。
**残留风险**：如果有人绕开 `tunedGlass()`/`patchedLens()` 直接调库的 `lens()` 会绕过防护——
目前所有玻璃效果都收口在这一处，可控。

## 2026-08-11　三个 Liquid 组件不引入 `io.github.kyant0:shapes`

catalog 原版用的 `Capsule()`/`RoundedRectangle()` 全换成 `RoundedCornerShape(50)`/`RoundedCornerShape(dp)`。
**为什么**：读 fork 的 `effects/Lens.kt` 源码，圆角识别只认
`androidx.compose.foundation.shape.CornerBasedShape`，官方那份多出的 `RoundedRectangularShape` 分支
被 fork 丢掉了。照抄传 `Capsule()` 进 `drawBackdrop`，`lens()` 运行时会走到 `throwUnsupportedSDFException()` 崩溃。
`RoundedCornerShape` 全项目已验证可用，视觉一致（胶囊靠 shader 自带的 `size.minDimension/2` 钳制）。
**否决**：加依赖再另外修崩溃——等价替代方案零成本，没必要冒险。

## 2026-08-11　删除 `AdaptiveLuminanceGlass.kt`

catalog 移植时建的"自动采样背后亮度切换黑白文字色"，未接入任何真实界面，判定死代码删除。
**为什么**：项目已有 `TeleprompterColorPickerPopup` 的对比度警告系统（`contrastRatio`，
为防播放页背景/文字选同色那次真实事故而写），是**手动选色 + 警告**模式，跟自动改色路数冲突，
接上会架空用户手动选色的意义。且它的设计场景是"玻璃后面内容一直在变"，提词器配色是固定的。
**否决**：接入播放页当保险——会跟已有系统打架。

## 2026-08-11　启动页入口：删掉长按 3 秒，改齿轮图标 + 外观设置页

**为什么**：隐藏入口本意是防同事误触，代价是团队里其他人根本发现不了，而且长按 3 秒这个手势
本身出过真实 bug（跟 `.clickable` 抢事件，短按被误判成长按）。最坏情况只是有人把玻璃调丑，
点"恢复默认"就能救。`AppearanceActivity` 搭成列表壳是为以后放主题等其它项。
**否决**：两个入口共存——用户明确要求"一个功能一个入口，旧的不要留着"。

---

## 2026-08-13　滑块状态架构：本地立即更新 + 独立节流通知，而不是手势消费

`LiquidSlider` 的 `onDrag` 直接同步调 `DampedDragAnimation.updateValue()`，视觉不再等调用方回写；
对调用方的 `onValueChange` 拆成独立的 ~200ms 节流旁路（`pendingPushValue` + 常驻 ticker），松手补发；
"外部值变化时回同步"的 `LaunchedEffect` 加 `isDragging` 挡板，拖拽期间完全跳过。
**为什么**：详见 PITFALLS 同名条目——真机日志证明 `onDragCancel` 11 次拖拽零触发，
手势从没被抢过，根因是 `targetValue` 只在调用方回写时才前进，而设置面板那条链路往返延迟远大于 `onDrag` 间隔。
**否决**：
1. `change.consume()`（两版）——方向错了，已撤销，恢复上游 catalog 原始写法。
2. `onDrag` 每帧 `snapTo()` 代替 `updateValue()`（spring `animateTo`）——曾怀疑弹簧每帧重启是卡顿根因，
   装机验证无改善（真正根因是节流推送触发的正文重排开销），已撤销。
3. 照抄播放页"落地本地 state、拖拽结束才提交"——用户否决，会让拖拽过程中正文不再实时跟着变，
   砍掉了设置面板"边调边看"的设计初衷。

## 2026-08-13　`LiquidCompact*` 加 `sampleBackdrop` 开关，不建新变体组件

加 `sampleBackdrop: Boolean = true`。`false` 时完全跳过 `drawBackdrop()`，改用
`graphicsLayer{按压缩放}` + `clip(shape)` + `background(纯色/半透明)` 的轻量填充。
设置面板内 8 处调用改 `false`，面板外（顶栏、格式工具栏）保持默认。
**为什么**：这些小控件即便已身处设置面板这个大玻璃容器内部，`drawBackdrop()` 仍无条件执行——
效果配方写死在组件内部，不接 `GlassTarget`/`GlassParams`、不受 `active` 影响，
结果未选中态会把背后正文再采样再模糊一遍，糊出可见的背景字残留。
**否决**：新建 `PlainCompactButton` 变体——用户明确要求"加开关，不新建变体组件"。

## 2026-08-13　设置面板玻璃：自建实时生效的透明度/模糊度滑块，不复用调参面板

`TeleprompterSettings` 新增 `settingsPanelBlurRadiusDp`/`settingsPanelOpacity`，
面板内直接放两个 `LiquidSlider`，拖动即时生效、走面板已有的持久化链路，**不经过 `GlassTuningStore`**。
**为什么**：排查发现整套 `glassTuningStoreFlow` 当时**只被 `GlassTuningActivity` 自己读取**
（用于面板自己的预览框）。grep 全项目确认真实屏幕全部读编译期常量 `defaultGlassParamsFor()`，
调参面板里拖的值从来没影响过任何真实界面，得手动导出成代码再重新编译——跟用户要的"拖了就生效"是两回事。
**否决**：把调参面板整体接通为实时生效——用户没选，理由是它暴露的十几项参数对日常自定义太多太杂。
（这条后来在 2026-08-15 被反过来做了，见下。）

## 2026-08-14　设置面板染色：从"顺手清的 bug"重新定位成主力可读性机制

`tint.enabled` 改成无条件 `true`（`alpha=0` 本身已等价完全透明）。颜色跟随 `LocalDarkTheme.current`
（浅色 `0xFFF5F5F5`，深色复用 `LiquidDialog` 的 `0xFF15151A`），**不用 `isSystemInDarkTheme()`**。
`settingsPanelOpacity` 默认 `0f` → `0.18f`。
**为什么**：参照苹果 Liquid Glass 的材质分层（内容层→玻璃材质层→控件层），
**染色浓度才是控制"挡不挡得住背景"的主力旋钮，模糊只是辅助**。此前一直靠加大 `blurRadiusDp` 解决可读性。
动手前先按用户要求做了三项验证（构建时间戳晚于源码改动、写入与读取字段名一致、
`GlassEffects.kt` 内只有一处 tint 绘制且已修），确认链路是通的再改，不是没查清就调数值。
**否决**：继续用固定白色——深色模式下违和。用户要求跟**应用内**主题联动，
不是跟系统深浅色（本项目深浅色是应用内可独立覆盖的三态开关，见 `ui/style/Theme.kt`；
`LiquidDialog`/`LiquidToggle`/`LiquidSlider` 至今仍在用 `isSystemInDarkTheme()`，是已知技术债）。

## 2026-08-15　真实调用点改读 `glassTuningStoreFlow`，不再直连 `defaultGlassParamsFor`

`TeleprompterEditorScreen`/`PlayerScreen`/`ColorPicker`/`LiquidDialog` 里全部 8+2 处调用改成新增的
`@Composable liveGlassParamsFor(target)`，内部 `collectAsState()`，没存过覆盖值时 fallback 回
`defaultGlassParamsFor(target)`，行为不变。`LauncherActivity` 没动（超出本次范围）。
**为什么**：做"提词器专属预设"时用户要求"预设应用后真实界面立刻生效"。
不改真实调用点的话，预设/调参面板拖了**预览框会变、真实界面纹丝不动**。
**否决**：
1. 预设另建一条独立存储不碰 `glassTuningStoreFlow`——那样"调参面板"和"预设"两套机制管同一个视觉效果
   却只有一套真有用，长期还会让人踩坑（不知道该在哪调）。
2. 先只做保存/列表/删除/改名，应用只写 store 不管真实界面——用户明确要求这次把缺口堵上。

## 2026-08-15　提词器专属玻璃预设：建在现有 store 之上，多入口共用同一屏幕

新增 `GlassPresetStore.kt`：`GlassPreset`（id/name/`Map<GlassTarget.name, GlassParams>` 快照）
+ 只存用户自建的列表 + save/apply/delete/rename，全部复用 `glassTuningStoreFlow` 现有读写路径。
`GlassTuningScreen` 加 `restrictToTargets: List<GlassTarget>?`：`null` = 原样（全部 target、无预设区）；
非 null = 只列指定 target + 多一块预设列表。**同一个 Activity、同一个 Composable、同一份 store，
靠一个参数分叉行为，不是两份代码。**
**为什么**：grep 数过 `GlassTarget.entries`，提词器只用到 `Other` 和 `TeleprompterSettingsWindow`
（`LauncherCard` 是启动页的，`SettingsPanel` 全项目零调用，是死值）。入口放设置面板两个滑块下面，
不放顶栏——顶栏已有五个图标了。
**否决**：预设单独建一个 Activity——用户明确要求"共用同一套面板实现，不要分叉成两套代码"。

## 2026-08-15　预设列表第一行：三次订正的完整经过

这一行反复改了三轮，把过程留下，因为**教训在过程里**。

**第一版**：`PresetSection` 把内置"默认"预设整个删掉，理由是它的"应用"效果 = `resetGlassParams`，
跟上面的"恢复默认"按钮重复。
→ **用户否决**：概念要保留，"把下面应用这个按钮改成目前设置配置，而不是把整个删掉"。

**第二版（订正一）**：恢复内置预设，只改 `applyGlassPreset`——去掉"内置预设就调 `resetGlassParams`"
的特判，让它跟用户自建预设走同一条 `withParams` 写入路径。
→ **仍不对**：这一行的**内容**没变，还是 `defaultGlassParamsFor`，点"应用"结果还是变回出厂默认，
跟"恢复默认"按钮依然重复。用户真机截图："一模一样的功能，两个不一样的按钮，非常反人类"。

**第三版（订正二）**：内容改成从 `glassTuningStoreFlow` 实时现算，点"应用"写回的值等于当前已生效的值
（正常情况下是纯无操作，不会再把用户刚调好的参数抹掉）。
→ 但这次**顺手**把 UI 标签"默认"改成"当前"、把 `builtInPreset`/`BUILT_IN_PRESET_ID`/`allPresetsFor`
删掉重命名成 `CURRENT_PRESET_ID`。
→ **用户再次否决**："只让你改功能，没让你改任何一个地方"。全部改名/重构撤回，
只保留唯一被要求的那处：`builtInPreset()` 内部取值来源从 `defaultGlassParamsFor(it)` 换成
`glassTuningStoreFlow.value.paramsFor(it)`。

**第四版（补最后一刀）**：上面那个改动编译通过、逻辑看着对，但用户录屏证实**没生效**——
`StateFlow.value` 在 Composable 里直接读不会被 Compose 追踪（详见 PITFALLS 同名条目）。
最终修法：`builtInPreset`/`allPresetsFor` 加 `store: GlassTuningStore` 参数，
`PresetSection` 自己 `collectAsState()` 后传进去。

**教训**（两条，都很贵）：
1. **用户说"只改功能"时要按最字面的意思执行**——只改被点击后触发的那段逻辑，
   不要顺手做看起来"更一致"的命名/结构调整，即使那些调整本身没错。
2. **"编译通过 + 逻辑看着对"不等于生效。** 第三版就是这么报告"已修"的，被录屏一眼推翻。

## 2026-08-15　文档库页液态化 + 背景自定义（纯色/渐变/图片）

`TeleprompterLibraryScreen` 从 `Scaffold`+`TopAppBar`+`DropdownMenu` 改成跟编辑页/播放页同一套：
`Box` 根布局 + 一份 `rememberLayerBackdrop()`；底层 `LibraryBackgroundLayer` 作为被折射的内容；
悬浮顶栏/FAB/行菜单改成**同棵树里的 Box 浮层**（不用 `DropdownMenu`——`Popup` 开独立窗口看不到真实背景）。
背景数据模型挂在 `TeleprompterLibrary` 上走现有 DataStore，没引入新存储。
**图片模式落盘存本地拷贝**（`teleprompterBgFolder`），不存 `content://` URI——
Photo Picker 的读权限不保证跨进程/跨重启存活。
**否决**：
1. 背景模型用 sealed class——没选，改成扁平字段结构，避免给"几种模式之一"引入第二套约定。
2. 行菜单保留原生 `DropdownMenu`——同一理由（Popup 看不到真实背景），不做"这个屏幕单独例外"。

## 2026-08-15　`tunedGlass()` 改 `@Composable` + `rememberUpdatedState`

`Modifier.tunedGlass()` 从普通函数改成 `@Composable`，内部用 `rememberUpdatedState` 包住
`params`/`shape`/`cornerRadius`/`onDrawSurface`/`extraEffects`；绘制阶段执行的闭包
（`effects`/`onDrawSurface`/`highlight`/`shadow`/`innerShadow`）从"读组合期传进来的普通值"
改成"读 `live*.value`"，即**在绘制闭包内部真正做一次 Snapshot State 读取**。
**为什么**：真机双埋点 + 录屏坐实——玻璃画面只在手势/动画进行时重绘，静止后参数怎么变都不再重绘。
根因不是三方库，是这份代码自己把 `params.tint.alpha` 在组合阶段就 `.copy(...)` 拍扁成普通 `Float`，
绘制闭包读到的是冻结值的闭包捕获，快照系统没有"活的"读取可观察。
改成绘制阶段读 `State.value`，跟 `Modifier.drawBehind { someState.value }` 会自动重绘是同一套原理。
**影响范围**：全项目所有玻璃界面共用这一个函数，一次改动全覆盖；
同时也修复"应用预设/恢复默认"这类**完全没有手势可借力**的程序赋值场景。
**未做**：`highlight`/`shadow`/`innerShadow` 的 `enabled` 开关本身仍在组合阶段决定，
只有开启后的数值是活的——当前这两个目标不会动态切换这几个开关。
**否决**：
1. "借用动画通道"（给值包一层 `Animatable`，程序赋值时 `animateTo` 同一目标值借重绘）——
   更绕的兜底方案，先验证本方案可行就不需要它。
2. 在每个调用点各自 `rememberUpdatedState`——`tunedGlass()` 是唯一入口，
   改一处比十几个调用点各自加更不容易漏，也不怕以后新调用点忘记加。

## 2026-08-16　背景自定义提升为跨屏共享组件（`glassui/GlassBackground.kt`）

文档库那套"四种背景模式 + 图片调节"改名提升为 `GlassBackgroundConfig`/`GlassBgMode`，
渲染（`GlassBackgroundLayer`）和编辑 UI（`GlassBackgroundEditor`）**只有一份实现**，
靠 `defaultContent` 插槽让"默认"模式在不同屏幕表现不同（文档库=纯色主题背景，启动页=动效光斑）。
落盘字段名不变，旧数据继续兼容。
顺带把文档库那边"没做实"的两处定型：渐变新增 `gradientAngleDeg` + 自定义两色（原来只有 5 个写死预设）；
图片新增对比度 `imageContrast` 和透明度 `imageAlpha`（走 `Image()` 自己的 alpha 参数，不进 `ColorMatrix`）。
**为什么**：用户原话"同一套背景组件（同一套数据结构、同一套调节 UI），不是重新定义一套不同的'四种模式'"。

## 2026-08-16　提词器三屏液态效果拆分，四个入口共用同一屏幕

新增 `GlassTarget.TeleprompterLibrary`/`TeleprompterEditor`/`TeleprompterPlayer`，
`TeleprompterGlassTargets` 一个常量拆成三个。四个入口（含启动页/外观的 `GeneralGlassTargets`）
全部走同一个 `GlassTuningActivity`，**只是传的 target 列表不同**。
共享的大纲组件（`TeleprompterOutlinePanel`/`OutlineScrubTrigger`）因此不能再内部写死 target，改成调用方传参。
入口位置：文档库顶栏新增齿轮；编辑页维持原位；**播放页没有独立顶栏，入口放在编辑页设置面板的
"播放页"分节里，并加小字说明**，避免用户搞混"在编辑页面板里调的其实是播放页"。
**为什么**：`Other` 被三个不相关屏幕共用，本质是没有真正分类。
**以后新增/修改调参面板的行为只需改 `GlassTuningScreen` 这一处，四个入口自动同步，
不要因为要加 target 就分叉出第二份屏幕实现。**
**否决**：每屏单独写精简版调参面板——`GlassTuningScreen` 没有任何屏幕专属逻辑，
唯一变化的只是"允许改哪些 target"。

## 2026-08-16　虚拟定位刷新间隔/精度改走 DataStore 可调，不再硬编码

**背景**：高德收到模拟定位仍显示真实位置。`dumpsys location` 确认注入链路没断
（高德确实注册成 gps provider 监听者、也确实收到模拟坐标），问题是高德在更细的特征上判断"这不像真实 GPS"。
新写纯被动监听的 `MockLocationDiagnosticActivity`，跟已知能骗过高德的第三方 App"影梭"做 A/B，
实测两处差异：刷新间隔（1000ms vs 约 100ms，差 10 倍）、精度值（两个 provider 共用硬编码 `accuracy=5f`
vs gps 比 network 精确、两者不同值）。
**决定**：新建 `MockLocationTuning.kt`，走项目已有的 `@Serializable` + `createAnyFlow` 模式，
`MockLocationService` **每次推送都读最新值**（不是启动时读一次缓存），诊断页配滑块直接调。
默认 100ms/gps=1.0/network=2.0。
**为什么**：用户原话"这两个数值做成可调，不要重新硬编码死……以后遇到别的 App 未必是同一组数字最合适"。
这次的取值是"跟影梭对齐后能验证生效"的一个具体值，不是穷举最优解。
**否决**：直接改成新硬编码值——下次换目标 App 结论可能完全不同，等于每次都要走一遍"改代码→编译→装机"。

## 2026-08-17　richeditor-compose `1.0.0-rc14` → 正式版 `1.0.0`

**为什么**：正在查的"选区手柄方向反了/乱跳"在 1.0.0 release notes 里有两条直接对应的修复
（#748 跟用户描述几乎逐字对应、#746），rc14 里都没有。已有诊断证明 bug 在渲染层而非数据层，
而渲染层是库私有实现，应用层够不到。
**版本号确认过程本身是个坑**（详见 PITFALLS 独立条目）：搜索接口返回 `1.0.0-rc13`（连在用的 rc14 都没有）；
`maven-metadata.xml` 的 `<latest>`/`<release>` 写着 `1.0.0-dev01`（实为 2024-04-24 的两年前 dev 构建）；
最终靠逐个 HEAD 请求 `.pom` 读 `Last-Modified` 才确定。
**否决**：
- 继续在应用层改选区逻辑——够不到，且上游已修。
- 升到 `1.0.0-cmp-1.12.0-beta03`（日期最新）——是给 Compose Multiplatform 1.12 的专用变体，
  会把 Compose 也拖到 beta（本项目用 1.11.2 稳定版）。
- fork 到本地改——上游正式版已修，没必要背维护负担。
**代价**：零源码改动，编译直接通过（唯一 breaking change 是移除 iosX64，本项目只有 Android）。

---

## 2026-08-20　拆分私人版 / 公开版（第一版：flavor 维度，**当天即被推翻**）

先做的是在 `channel` 之外新增 `edition` 维度（`priv`/`pub`），构建命令变成
`assembleGkdPrivDebug`/`assembleGkdPubDebug`。"删掉"落实到三层：启动页卡片用
`listOfNotNull`+`takeIf{BuildConfig.HAS_*}` 过滤；`app/src/pub/AndroidManifest.xml` 用
`tools:node="remove"` 摘组件；同一份清单摘权限（实测 20 → 14）。
**关键细节**：`MainActivity` 被 `Notif` 和 Open{Scheme,File,Tile} 三个 Activity 引用，
**必须一起摘，不能只摘它自己留下会跳向它的那些**。
**否决过**：运行时开关（一个 APK 里按设置隐藏）——权限还是会照常申请，公开分发时最关键的一层没解决；
复用 `channel` 维度加 flavor——语义冲突，且会跟 play 渠道的签名/applicationId 逻辑纠缠。

**为什么当天就被推翻**：核对产物后如实告知用户——功能确实完全失效（无入口、无组件、无权限），
但 **Kotlin 类仍在 APK 的 dex 里，解包能看到**。用户据此选择了下面这条。

## 2026-08-20（修订）　改为两个独立工程文件夹，公开版物理删除代码

撤销 `edition` 维度，恢复单一 `channel` 和 `assembleGkdDebug`。
- **私人版** `F:\guixing`，5 个功能，applicationId `li.songe.gkd.taiclear`（不变，用户手机原地升级不丢数据）
- **公开版** `F:\guixingkeji`，3 个功能，applicationId `li.songe.gkd.guiyue`，**独立 git 仓库**

**为什么不复制 .git**：历史里含被删功能的完整源码，带过去 `git log -p` 就能全还原，等于没删。
**代价（已明确告知并被接受）**：共用代码以后每次都要同步两遍，两版会逐渐产生差异。
**公开版实际删除**：虚拟定位（`mockloc/`、`mock-location.html`、单元测试）；
去广告整个 GKD 内核（`a11y/`、`shizuku/`、`notif/`、`service/`、`data/`、`permission/`、`db/`、
`ui/{home,component,icon}` 及 `ui/` 顶层 43 个界面文件、`MainActivity`/`MainViewModel`/
`Open{Scheme,File,Tile}Activity`、`SelectToSpeakService`、shizuku AIDL）；
清单上述组件 + 17 个后台/磁贴服务，权限 20 → 7；Shizuku 依赖（它会往清单合并 `moe.shizuku.*` 权限）。
**保留但需就地裁剪的共用文件**：`App.kt` 启动流程只剩 `initToast()`/`initStore()`；
`SettingsStore` 从 30+ 字段缩到 2 个；`StoreExt`/`FolderExt`/`IntentExt`/`Others`/`CoroutineExt`/`LocalExt`
各去掉内核部分；`Toast.kt` 从自绘悬浮 Toast 退化为系统 Toast（原实现依赖无障碍服务和
OverlayWindowService 才能画在别的应用上面，那条通道随内核一起没了）。
**产物核对方法（务必照做，不要只看界面）**：`aapt2 dump badging` 比权限，解包 dex 用 `zipfile` 搜符号。
结果：dex 中已无 `mockloc`/`a11y`/`shizuku`/`service`/`MainActivity`/`RawSubscription`；体积 34.0 → 28.4MB。

**同期新增**：启动页底部常驻「版本类型 + 版本号 + 开发者（癸星人）」，点击展开「关于/使用说明」浮层。
说明按版本自动裁剪——它和功能卡片读同一组开关，不会出现"说明书里写了但应用里找不到"。
浮层用**同一棵组合树里的覆盖层**，不是 Dialog/Popup（Dialog 另开系统窗口采样不到 backdrop，
玻璃会变空白板），并按项目惯例配了 `BackHandler`。

---

## 2026-08-26　提词器五项显示设置：去掉全局层，改成每篇文档独立

**用户判断**："一篇文档就该有一篇文档的设置，所有文档共用一套全局值这个概念本身就多余，
开关是为了弥补这个多余概念而存在的，一并去掉。"
**决定**：五项（正文默认字号/整体缩放/行距/对齐/内容宽度）每篇文档各存完整一份，删掉全部五个开关。
`TeleprompterSettings` 里那五个字段**保留但改变身份**：从"全局设置"变成**"新文档默认值"**，
只在新建/导入那一刻读一次（`newDocumentDisplaySettings()`）。
该类其余字段（`scrollSpeed`/播放页配色/`mirrored`/面板玻璃参数）**仍是真正的全局设置**。
新文档默认值取中性基线（用户指定：倍数一律 1、字号取滑块下限 16sp）。
**否决 / 取舍**：
1. **没改成必填字段**（用户说可以简化）。保留可空 + 靠迁移保证恒不为空。
   理由：老库 13 篇里 12 篇 `displayOverrides` 是空对象，改必填会让 kotlinx 在缺字段时落到数据类默认值，
   **把用户当时的实际显示效果冲掉**；`@SerialName` 改名同样有丢数据风险。
   代价是"覆盖"味道的类名留了下来，靠注释说明它现在的真实含义。
2. **`contentWidthLandscape` 保留 0.65，没按"倍数一律 1"改成 1.0**——这个 0.65 是
   "横屏铺满整行、眼睛横扫距离过大跟不上滚动"的实测结论，改成 1.0 等于推翻一个已验证的判断。
3. **没给"新文档默认值"做编辑界面**。用户要求"界面措辞不能让人误以为改这个会影响所有文档"，
   是靠**根本不存在这个控件**满足的，而不是靠改文案。
**迁移（本次真正的风险所在）**：靠新增的 `schemaVersion`（老文件无此键→解码为 0→触发）保证只跑一次。
逐字段判断（文档已有自己的值就保留，没有的才从旧全局补——一篇文档完全可能只有一项是自己的）；
不碰 `updatedAt`（迁移不是用户编辑，不该让文档在"最近修改"里跳位置）；
迁移后把 `settings` 那五项重置为中性默认值。
**验收标准（以后同类迁移照此办理）**：断言的不是"迁移函数被调用过"，而是那个唯一重要的性质——
**每篇文档迁移前后 `effectiveSettings()` 六项逐字段完全相同**（渲染侧只认这个函数）。
8 条单元测试 + 拿用户真实 13 篇库文件离线跑一遍逐字段比对 + 真机装机复验。

## 2026-08-28　提词器三页统一文本渲染：拆除 `scaledForDisplay`，显示参数归中性

**目标**：编辑页/阅读态/播放页渲染同一篇文档时**断行位置逐字符一致**。
此前三页是三份实现，加上编辑页独有的 `editZoomScale` 和两边不同的横向内边距，断行不可能对齐。
**做法**：新增 `TeleprompterBodyRenderer.kt`，三页共用唯一的 `TeleprompterBody`，内部一律是
`BasicRichTextEditor`，只读模式靠 `readOnly=true` + 透明 `cursorBrush` + 吃掉指针事件实现。
**`mode` 只允许影响"能否编辑/光标是否可见/是否接受交互"，不允许影响任何排版参数。**
横向内边距和内容宽度比例都归渲染器所有，页面不再自己加。
**为什么必须整体删除 `scaledForDisplay` 而不是"保留但少用"**：编辑态必须渲染可编辑的那份
`RichTextState`。而它的公开写入口只有 `setHtml`/`setText`/`setMarkdown`，
唯一的 AnnotatedString 写入口 `updateAnnotatedString$..._release` 是 **internal**
（用 `javap` 核对过 1.0.0 的 `classes.jar`）。**没有任何受支持的办法把变换后的 AnnotatedString
塞回 `RichTextState`**——这是库的 API 边界，不是工作量问题。
**代价（已量化并被接受）**：整体缩放/行距/对齐现在只作用于基础 TextStyle，即只对"没有自带样式"的文字生效。
沉香话术实测：1736 个 run 里 **1262 个自带显式字号**（10.5pt 和 14pt 两档占 90%）；
330 段里 **221 段自带显式行距**。设置面板四条文案已同步改为如实描述。
**同时把显示参数归中性**（字号 16sp、缩放 1.0、行距 1.0）。理由不是图省事，而是
**旧值是在旧接线下靠肉眼凑出来的补偿值，喂给新接线会失真**：沉香话术旧值行距 1.724 + 默认字号 19.78，
而正文实际渲染字号只有 10.5/14px——旧接线按"段内最大字号 × 1.724"算（10.5px 段 → 18.11sp），
新接线变成"默认字号 × 1.724"统一值（34.12sp），**同一段行距放大到 1.88 倍**。
**否决一：保存时做逆变换**——`scaledForDisplay` 里段落行距被改写成 Em 相对值、对齐被强制覆盖，
这两项不可逆；且浮点每次存取都会漂移。本项目已因 `toHtml()` 归一化的有损往返吃过亏
（重新序列化未改动的内容会凭空缩掉几十个字符），不能再叠一层。
**否决二：用 `LocalDensity.fontScale` 做整体缩放**——技术上更干净（作用于 sp→px 换算，
天然覆盖显式字号，不改文本对象也不破坏断行一致性），但**没验证过**：
会同时影响子树内一切 sp 尺寸（含内边距语义、副标题、工具栏），副作用范围未知。
按项目准则"不在灭 bug 的同一轮引入未验证的优化"，留待独立一轮专门验证。
**待办：导入时字号归一化**——真正的根治方向是 docx 导入阶段就把 Word 绝对磅值折算成相对基础字号的倍数
（按实测分布：10.5pt → 继承，14pt → 1.33×），让文档内部只保留相对关系。做完后那四条文案应还原为
"对所有文字生效"。
**已知回退**：正文长按"提升为副标题"触发入口失效——原来挂在只读 `Text` 上靠 `getOffsetForPosition`
换算段落序号，正文统一成可编辑控件后长按是系统文本选择手势，两者抢事件。
后端 `promoteParagraphToChapter`/`paragraphIndexForCharIndex` 均未删、单元测试仍覆盖，
等定了新触发方式（备选：格式工具栏加按钮，对光标所在段落生效）再接回去。

---

## 2026-09-05　三模块日志导出：只提取"导出"，不合并日志采集

新增 `observelog/` 包：共用的 `ObserveLogExport`（转发/保存到下载目录）+ `WebToolObserveLog` 及其查看器。
**为什么只提取导出**：`TeleprompterObserveLog`/`CallGuardObserveLog` 当初是**故意各写一份、不提取公共类**的
（新功能不碰其他模块任何一行代码），那个决定仍然有效——这次没动那两个对象。
但"导出成 txt、能转发微信、能存本地"是用户明确要求三处都要有的**同一个**能力，
三份各写一遍必然漂移。所以只把**导出这件事**提出来，日志的采集/存储仍各归各的。
**为什么不合并成一个带 tag 的公共日志对象**：三个模块的日志是**分开看的**（用户排查的是某一个功能），
合并后每次看都要先按 tag 过滤，反而更麻烦。
**定位修改的日志界面用 ComposeView 叠层，不新开 Activity**：新 Activity 要改 `AndroidManifest.xml`，
而清单在 `sync-to-public.sh` 的 `NEVER_COPY` 里，得两个工程各改一遍、很容易只改一边。
叠层写法两边靠同步脚本自动一致。
**叠层的坑**：日志界面单独一个铺满的 ComposeView 且关闭时设为 `GONE`——
**不能**用一个铺满的 ComposeView 同时承载按钮和界面，那层即使画的是空白也会挡在 WebView 前面参与触摸分发，
地图选点会时灵时不灵。按钮那层用 `WRAP_CONTENT` + `gravity=BOTTOM|END`，其余区域不在这层里。

## 2026-09-06　自排除做成不可配置的不变量

`checkAppBlockMatch()` 最前面硬判 `appId == META.appId` 直接返回 true。
**为什么选这个位置**：它是唯一收口点——`A11yState.kt` 的 `ActivityRule.blockMatch` 为真时，
app 规则和全局规则**同时**清空，一处堵死全部。
**为什么不靠现有机制**：上游本来有两道防线，改包名后都失效了——
①默认订阅里的自排除写死 `{id:'li.songe.gkd',enable:false}`，认不出 `li.songe.gkd.taiclear`；
②`getDefaultBlockList()` 里确实有 `META.appId`，但那**只是默认值**，
`block_match_app_list` 一旦被持久化过就永久失效（详见 PITFALLS「默认值只对文件里没有这个键的数据生效」）。
**自排除是不变量，不该是可配置项**，以后再改包名也不会重犯。
⚠️ **这条不解决 2026-09-06 那次误点的症状**——点它的是另外装的上游原版 GKD，不是癸星自己的内核。
它堵的是"哪天启用癸星自己的无障碍服务"这个潜在坑。
