# 架构决策记录

只追加，不删改。旧决定被推翻时，在新条目里写清楚推翻了哪条、为什么，旧条目原样留着——这份文件本身
就是"为什么现在长这样"的历史。

格式：日期 / 决定了什么 / 为什么 / 否决了哪些方案及原因。

---

## 2026-08-04　fork 策略：只加不改，尽量不动 GKD 原有源码

**决定**：新功能（提词器、定位修改、虚拟定位）全部放在新增文件/新增包里，尽量不改 GKD 上游已有文件。
包名从 `li.songe.gkd` 改成 `li.songe.gkd.taiclear`，显示名 GKD-T，跟官方 GKD 可以同时装在同一台设备上。

**为什么**：GKD 本身还在持续更新（这次写文档时看 `git log` 主仓库已经 1300+ 提交），如果大改上游文件，
以后同步上游改动会变成每次都要解冲突。只加不改，冲突面就只剩"上游删掉了我们用到的东西"这一种，比双向
冲突好处理得多。

**否决方案**：直接 hard fork 后自由重构——放弃了，理由同上，长期维护成本更高。

---

## 2026-08-04　提词器/虚拟定位/GPS 修改的实现方式：原生 Compose vs WebView 各管一段

**决定**：提词器（`teleprompter/` 包）整个是原生 Kotlin + Compose；GPS 照片视频修改
（`gps-modifier.html`）、虚拟定位的地图选点（`mock-location.html`）用 WebView 承载 HTML+JS。

**为什么**（推断，代码里没有留下明确写死这条理由的注释，这是从两边现有实现的性质反推出来的，标注为
推断而非确凿记录）：提词器需要跟液态玻璃效果、拖拽手势、富文本编辑紧密集成，且要求顺滑的自动滚动
动画，原生 Compose 控制力更强。GPS 元数据编辑、地图选点这类功能本质是包一层现成 JS 生态
（EXIF 处理、Leaflet 地图库），重新用 Kotlin 造轮子收益不大，WebView 直接复用更快。

**如果以后要推翻这条**：把地图选点也原生化，工作量不小（详见虚拟定位待办：需要换高德瓦片 tileLayer
+ 坐标纠偏逻辑，这套目前是 Leaflet 插件现成的，原生要么找对应库要么自己实现纠偏算法）。

---

## 2026-08-08　液态玻璃背景捕获：sibling 模式，不用 ancestor

**决定**：任何 `layerBackdrop(x)` 的捕获节点，必须是采样 `backdrop=x` 的玻璃控件的**同级**节点，
不能是其祖先（哪怕是"共同挂在同一个 Column 上"这种间接祖先关系）。

**为什么**：ancestor 模式会让 RenderNode 的捕获树里包含它自己（背景节点的子树覆盖了采样它的控件），
形成循环捕获，原生栈溢出 SIGSEGV。这次真机崩溃调查证实的（tombstone: "stack pointer is not in a
rw map"，见 PITFALLS.md）。

**否决方案**：曾怀疑是折射参数相对控件尺寸过大导致越界读取（先加了 `maxRefractionPx` 钳制），这个
钳制本身是合理的防御但没解决真正的崩溃——真正原因是上面这条，钳制留着但不是这次崩溃的根因。

---

## 2026-08-09　液态玻璃不迁移到官方 `io.github.kyant0:backdrop:2.0.0`

**决定**：继续用 `io.github.kashif-mehmood-km:backdrop:0.0.1-alpha02` fork，不迁移到官方最新版。

**为什么**：逐行核对过官方 2.0.0 的色散 shader，跟 fork 逐字节相同，同样没有 clamp——迁移不解决
花屏问题。官方还缺 fork 独有的 `progressiveBlur()`（提词器设置面板在用），迁移反而要先补这个功能。
详见 `GLASS.md`。

**否决方案**：迁移后再自己修 —— 否决，因为迁移成本（补 `progressiveBlur`、验证十几个文件的 API
差异）换不来任何收益，色散 bug 不管迁不迁移都要自己修。

---

## 2026-08-09　色散花屏：本地 `patchedLens()` 重实现，不 vendor 整个库

**决定**：不把 backdrop 库整个拷进项目改源码，而是在项目自己的包（`glasstuning/PatchedLens.kt`）里
用库公开的 `obtainRuntimeShader`/`effects.effect()` 原语重新实现一份带 clamp 的色散 shader，只有
`chromaticAberration=true` 时才会用到；非色散路径继续委托库自己的 `lens()`。

**为什么**：库的 bug shader 是 `internal`，模块级隔离摸不到，只有两条路——vendor 整个库（改一处，
维护代价是要跟着上游手动 diff 合并，且这个库对本项目来说用不上大半的 KMP 多平台代码）或者用公开
原语在自己包里重实现（改动小、跟库版本解耦，风险是如果以后有人绕开 `tunedGlass()`/`patchedLens()`
直接调库的 `lens()`，会绕开这个防护——目前项目里所有玻璃效果都收口在这一处，风险可控）。

**否决方案**：vendor 整个库本地构建——否决，见上，维护代价换不回对应的好处。

---

## 2026-08-11　`LiquidButton`/`LiquidSlider`/`LiquidToggle` 不用 `io.github.kyant0:shapes`

**决定**：把这三个组件从"自己简化版"换成官方 catalog 的完整移植时，catalog 原版用的
`com.kyant.shapes.Capsule()`/`RoundedRectangle()` 全部换成 `RoundedCornerShape(50)`/
`RoundedCornerShape(dp)`，没有引入 `io.github.kyant0:shapes` 依赖。

**为什么**：读了 fork 的 `effects/Lens.kt` 源码，圆角识别只认
`androidx.compose.foundation.shape.CornerBasedShape`，官方那份多出的
`RoundedRectangularShape` 分支（`com.kyant.shapes` 类型实现的接口）被 fork 丢掉了。如果照抄传
`Capsule()`/`RoundedRectangle()` 进 `drawBackdrop`，`lens()` 运行时会走到
`throwUnsupportedSDFException()` 崩溃。`RoundedCornerShape` 是已经在整个项目里验证过能用的类型，
视觉效果一致（胶囊靠 shader 自带的 `size.minDimension/2` 钳制实现），不需要新依赖。

**否决方案**：加这个依赖、接受运行时崩溃再另外修——否决，没有必要冒这个险，等价替代方案零成本。

---

## 2026-08-11　删除 `AdaptiveLuminanceGlass.kt`

**决定**：catalog 移植过程中建的自适应亮度玻璃（采样背后内容算平均亮度，自动切换黑白文字色）没有
接入任何真实界面，判定为死代码，删除。

**为什么**：项目里已经有 `TeleprompterColorPickerPopup` 的对比度警告系统（`contrastRatio`，专门为了
防止播放页背景/文字选同色这次真实事故写的），是手动选色 + 警告的模式。`AdaptiveLuminanceGlass` 是
自动改文字颜色，两者路数冲突——接上会架空用户手动选色的意义。而且它的设计场景是"玻璃后面内容一直在
变"（比如 catalog 示例里的控制中心），提词器的背景/文字是固定配色，场景不匹配。

**否决方案**：接入播放页当保险——否决，见上，会跟已有系统打架，不是真的需要。

---

## 2026-08-11　启动页入口：长按 3 秒 → 齿轮图标 + 外观设置页

**决定**：删除"长按启动页卡片 3 秒进入调参面板"的隐藏入口，改成右上角齿轮图标 →
`AppearanceActivity`（外观设置列表）→ 玻璃效果调节。

**为什么**：隐藏入口最初是为了不让同事误触，但代价是团队里其他人根本发现不了这个功能，而且长按 3 秒
这个手势本身出过一次真实 bug（跟 `.clickable` 的按下/抬起事件判定冲突，短按被误判成长按，见
PITFALLS.md）。用户明确要求"一个功能一个入口"——一个正经的设置入口，最坏情况只是有人把玻璃调丑，
点"恢复默认"就能救回来，不值得为了防误触加隐藏手势的复杂度。`AppearanceActivity` 搭成列表壳而不是
直接跳转，是因为以后还要放主题设置之类的其他项。

**否决方案**：保留长按入口 + 加新的正经入口（两个入口共存）——否决，用户明确要求"一个功能一个入口，
旧的不要留着"。

---

## 2026-08-13　滑块状态架构：本地立即更新 + 独立节流通知调用方，而不是手势消费

**决定**：`LiquidSlider` 的 `onDrag` 直接同步调用 `DampedDragAnimation.updateValue()`，让动画自己的
`targetValue`/视觉位置不再等待 `onValueChange` 的调用方回写；对调用方的通知（`onValueChange`）拆成
一条独立的 ~200ms 节流旁路（`pendingPushValue` + 常驻 ticker），松手时补发最终值。同时给原有的"外部
值变化时回同步"`LaunchedEffect` 加了 `isDragging` 挡板，拖拽期间完全跳过，避免调用方那条慢链路迟到
的旧值把正在进行的拖拽弹回去。

**为什么**：设置面板滑块拖动时数字乱跳后弹回原值，最初两轮怀疑并修复的方向是"手势被 `verticalScroll`
的 NestedScroll pre-scroll 抢走"，加了 `consume()`（两个版本：先是移动后消费，再升级成 down 落地立刻
消费），编译、装机、真机测试都做了，问题依旧。真机日志（`onDragStarted`/`onDrag`/`onDragCancel`/
`onDragEnd`/回同步几个关键点打点）显示 `onDragCancel` 11 次拖拽全程零触发——手势从没被抢过，两轮
consume() 方向从一开始就错了。真正原因：`targetValue` 只有 `updateValue()` 被调用时才前进，而
`updateValue()` 只在监听调用方 `value()` 变化的 `LaunchedEffect` 里触发；设置面板的 `onValueChange`
走 `vm.updateSettings` → 整个 `TeleprompterLibrary` 塞进 StateFlow → 上层重组，这条链路的往返延迟
（实测）明显长于 `onDrag` 的调用间隔（10~100ms 一次），导致 `targetValue` 在整段拖拽期间基本冻结，
松手时用冻结的旧值覆盖掉了整段拖拽进度。播放页速度滑块没这个问题，是因为它的 `value`/`onValueChange`
绑定的是纯本地 `remember { mutableStateOf }`，同一帧内就能被 `snapshotFlow` 观察到，压根没走这条重
链路。详见 `PITFALLS.md`。

**否决方案**：
1. `change.consume()`（两版）——见上，方向错了，已撤销，`DragGestureInspector.kt`/
   `DampedDragAnimation.kt` 恢复成上游 catalog 原始写法。
2. 让 `onDrag` 每帧调 `Animatable.snapTo()`（不走 spring）而不是 `updateValue()`（spring
   `animateTo()`）——中途怀疑过 `animateTo()` 每帧重启弹簧物理是卡顿根因，试过换成 `snapTo()`，装机
   验证没有改善（真正的卡顿根因是节流推送本身触发的正文重排版开销，不是动画本身），已撤销改回
   `updateValue()`。
3. 照抄播放页"落地本地 state，拖拽结束才提交"——用户明确否决，会导致拖拽过程中正文不再实时跟着变，
   砍掉了设置面板做成 Box 浮窗"边调边看"的设计初衷。改成本地立即更新 + 独立节流通知两条通道，而不是
   合并成一条。

---

## 2026-08-13　`LiquidCompactButton`/`LiquidCompactIconButton` 加 `sampleBackdrop` 开关，不建新变体组件

**决定**：给这两个组件加 `sampleBackdrop: Boolean = true` 参数。`false` 时完全跳过 `drawBackdrop()`
（不采样、不模糊、不跑色散 shader），改用 `graphicsLayer{按压缩放}` + `clip(shape)` +
`background(纯色/半透明)` 的轻量填充；`active` 参数继续控制填充色深浅（沿用原来的 `activeTint`）。
提词器设置面板内全部 8 处调用改成 `sampleBackdrop = false`，面板外的调用点（顶栏、格式工具栏）保持
默认 `true` 不变。

**为什么**：设置面板里"对齐方式"按钮、颜色选择圆点等小控件，即便已经身处设置面板这个大玻璃容器
（`TeleprompterSettingsWindow` 自己的 `tunedGlass()`）内部，`drawBackdrop()` 仍然无条件执行——效果
配方（`blur(1dp)` + `patchedLens(16/28dp, chromaticAberration=true)`）写死在组件内部，完全不接
`GlassTarget`/`GlassParams`，不受 `active` 影响。结果是这些小控件在未选中态会把背后真实正文再采样、
再模糊一遍，糊出可见的背景字残留，且是组件级问题，项目里所有调用点表现一致，不局限于设置面板。

**否决方案**：新建一个不带背景采样的变体组件（例如 `PlainCompactButton`）——否决，用户明确要求"加开关，
不新建变体组件"，避免维护两份几乎相同的按钮实现。

---

## 2026-08-13　提词器设置面板玻璃效果：自建实时生效的透明度/模糊度滑块，不复用调参面板

**决定**：`TeleprompterSettings` 新增 `settingsPanelBlurRadiusDp`/`settingsPanelOpacity` 两个字段，
设置面板内直接放两个 `LiquidSlider` 让用户调，拖动即时生效、走面板本身已有的持久化链路
（`vm.updateSettings` → `teleprompterLibraryFlow`）。`TeleprompterSettingsWindow` 的 `tunedGlass()`
调用从这两个字段读值合成 `blurRadiusDp`/`tint`，不经过 `GlassTuningStore`。

**为什么**：用户要"随时自定义"这个面板的玻璃效果。排查发现齿轮→外观→玻璃效果调节那个调参面板
（`GlassTuningActivity`）虽然已经给"提词器设置窗"这个目标接了入口，但整套 `glassTuningStoreFlow`/
`GlassTuningStore.paramsFor()` 只被 `GlassTuningActivity` 自己读取，用来在面板自己的预览框里看效果——
grep 全项目确认，真实屏幕（`LauncherActivity`、`TeleprompterSettingsWindow` 等）全部读的是编译期常量
`defaultGlassParamsFor()`，从来没有任何真实界面读取过用户在调参面板里拖的值，调了不会真的影响真实
界面，得手动导出成代码再重新编译。这跟用户想要的"随时自定义、拖了就生效"完全是两回事。

**否决方案**：
1. 把调参面板接通为实时生效（真实屏幕改成读 `GlassTuningStore.paramsFor(target)`）——用户明确没选，
   选了范围更小的"面板里直接加两个滑块"，理由是调参面板暴露的参数（模糊/折射/深度/色散/高光/阴影/
   染色十几项）对日常自定义来说太多太杂，不只是"透明度和模糊度"两个概念。
2. "透明度"最初实现成基于 `GlassTintParams`（`BlendMode.Hue` + 主题色 `surface`）——中途因为看起来
   没效果换成固定白色叠加找 bug，中间态是固定白色、纯 alpha 语义（0%=完全透明，100%=纯白不透明）。
   这个中间态被 2026-08-14 那条决定推翻了，颜色改回了主题色分支，不是固定白色——见下一条。

---

## 2026-08-14　设置面板染色：从"顺手清的 bug"重新定位成主力可读性机制，跟随应用内主题

**决定**：`TeleprompterSettingsWindow` 的 `tint.enabled` 改成无条件 `true`（不再靠
`settingsPanelOpacity > 0f` 判断开关，`alpha=0` 本身已经等价于完全透明）。染色颜色跟随
`LocalDarkTheme.current` 分支（浅色模式 `0xFFF5F5F5`，深色模式复用 `LiquidDialog` 已有的
`0xFF15151A`），不用 `isSystemInDarkTheme()`。`settingsPanelOpacity` 默认值从 `0f` 改成 `0.18f`，
作为"能感觉到但不遮字"的起始强度——这是这几条改动里唯一一个用户可感知的默认外观变化，其余改动
保持默认不变。

**为什么**：参照苹果 Liquid Glass 材质分层模型——内容层→玻璃材质层（模糊+染色+阴影）→控件层，
染色浓度才是控制"背景乱不乱、挡不挡得住"的主力旋钮，模糊只是辅助。这之前一直在靠加大
`blurRadiusDp` 解决可读性（14dp 那次，后来又改回 0），染色这条线路虽然 2026-08-13 已经接进真实
渲染管线、也修复了那个 `alpha` 未生效的 bug，但一直被当成"顺手清掉的一个 bug"，没有被当作真正
要用的机制去认真设计颜色来源。这次动手前先按用户要求做了三项验证（构建时间戳晚于源码改动、
`onValueChange` 写入字段名和 `tunedGlass()` 读取 `alpha` 用的字段名一致、`GlassEffects.kt` 内
只有一处 tint 绘制且已修），确认链路本身是通的，再往上做这次的重新定位，不是在没查清楚的情况下
直接调数值。`isSystemInDarkTheme()` 是调研阶段（本轮更早）点出的技术债（`LiquidDialog`/
`LiquidToggle`/`LiquidSlider` 至今仍在用），这次新代码路径不重复它。

**否决方案**：继续用固定白色（2026-08-13 那版的中间态）——否决，深色模式下固定白色的染色跟深色
主题违和，用户要求跟应用内主题联动，不是跟系统深浅色（区别见上面提到的技术债——本项目的深浅色
是应用内可独立于系统覆盖的三态开关，见 `ui/style/Theme.kt`）。

---

## 2026-08-15　`GlassTarget` 真实调用点改读 `glassTuningStoreFlow`，不再直连 `defaultGlassParamsFor`

**决定**：`TeleprompterEditorScreen.kt`/`TeleprompterPlayerScreen.kt`/`TeleprompterColorPicker.kt`/
`LiquidDialog.kt` 里全部 8+2 处 `defaultGlassParamsFor(GlassTarget.Other/TeleprompterSettingsWindow)`
调用，改成新加的 `@Composable liveGlassParamsFor(target)`（`GlassTuningStore.kt`），内部读
`glassTuningStoreFlow.collectAsState()`，没存过覆盖值时透明 fallback 回
`defaultGlassParamsFor(target)`，行为不变；一旦调参面板或预设写入过这个 target，真实界面立刻
跟着变。`LauncherActivity.kt`（`GlassTarget.LauncherCard`）没有动，超出这次范围。

**为什么**：做"提词器专属玻璃预设"那次，用户要求预设应用后"复用现有覆盖值写入路径，真实界面读取
的地方不用改，立刻生效"。grep 全项目确认：所有真实调用点当时都直接读编译期常量
`defaultGlassParamsFor()`，没有一处读 `glassTuningStoreFlow`——这条store 从最初做出来开始就只喂
给调参面板自己的预览框，跟"透明度/模糊度"两个滑块当初遇到的是同一个坑。如果不改真实调用点，
预设/调参面板拖了看起来会生效（预览框会变），但编辑页顶栏/格式工具栏/大纲抽屉/播放页这些真实
界面纹丝不动——用户在被告知这个冲突后明确选择"顺便把真实调用点改成读存储"，而不是回避这个坑另建
一条新链路（那样效果雷同"透明度/模糊度"，但会跟调参面板/预设完全脱节，两套互不联动）。

**否决方案**：
1. 预设系统另建一条真正生效的独立存储，不碰 `glassTuningStoreFlow`——否决，调参面板本身还是
   预览-不生效状态，逻辑上"调参面板"和"预设"两套机制都在管同一个视觉效果，却只有一套真的有用，
   长期容易让人再次踩坑（不知道该在哪调）。
2. 先只做保存/列表/删除/改名，应用先只写 `glassTuningStoreFlow`（不管真实界面）——用户没选，
   明确要求这次顺手把缺口堵上。

---

## 2026-08-15　提词器专属玻璃预设：建在 `GlassTuningStore`/`GlassTarget` 之上，两个入口共用同一屏幕

**决定**：新增 `GlassPresetStore.kt`——`GlassPreset`（id/name/`Map<GlassTarget.name, GlassParams>`
快照）+ `GlassPresetStore`（`List<GlassPreset>`，只存用户自建的，出厂"默认"预设从
`defaultGlassParamsFor` 实时合成、从不落盘）+ save/apply/delete/rename 四个函数，全部复用
`glassTuningStoreFlow` 现有的读写路径（`paramsFor`/`withParams`/`resetGlassParams`）。UI 层
`GlassTuningActivity`/`GlassTuningScreen` 加一个 `restrictToTargets: List<GlassTarget>?` 参数：
`null`（齿轮→外观→玻璃效果调节，`GlassTuningActivity.globalIntent`）＝原来的样子，全部 target、
无预设区；非 null（提词器内部新按钮，`GlassTuningActivity.restrictedIntent`，固定传
`TeleprompterGlassTargets = [Other, TeleprompterSettingsWindow]`）＝目标选择器只列这两个、多一块
预设列表 UI。同一个 Activity/同一个 Composable/同一份 `glassTuningStoreFlow`，靠这一个参数分叉
行为，不是两份代码。

**为什么**：先 grep 数了一遍 `GlassTarget.entries`（4 个值）的真实调用点，只有 `Other` 和
`TeleprompterSettingsWindow` 被提词器代码用到——`LauncherCard` 是启动页的，`SettingsPanel` 全项目
零调用，是从没被任何真实界面用过的死值。预设只需要覆盖这两个，不是全部四个。入口选在设置面板
"透明度/模糊度"两个滑块下面，不是顶栏新图标——顶栏已经有 完成/编辑/播放/设置/文档库 好几个图标，
再加一个不如放进设置面板里跟同一批玻璃相关的控件挨在一起。

**否决方案**：
1. 预设系统单独建一个 Activity/界面——否决，用户明确要求"两个入口共用同一套面板实现，不要分叉
   成两套代码"，复用现有 `GlassTuningActivity` 只是加一个可选参数，维护成本低得多。
2. 入口放顶栏新图标——没选，权衡后放设置面板里更顺手（见上），但两个方案技术上都可行，纯粹是
   顺手程度的选择，不是硬约束。

---

## 2026-08-15　提词器文档库页液态化 + 背景自定义（纯色/渐变/图片）

**决定**：`TeleprompterLibraryScreen.kt` 从 `Scaffold(topBar=TopAppBar, floatingActionButton=FAB)`
+ 原生 `DropdownMenu` 改成跟编辑页/播放页同一套模式：`Box` 根布局，`rememberLayerBackdrop()` 一份
`backdrop`；一层 `LibraryBackgroundLayer`（`TeleprompterLibraryBgMode`：默认跟主题色/纯色/渐变/
本地图片，捕获点 `Modifier.layerBackdrop(backdrop)`）在最底层，作为真正被玻璃元素折射的内容；
悬浮顶栏（返回/标题/设置背景/导入/备份）、悬浮 FAB、每行"更多"改成屏幕级共享的同棵树 `Box` 浮层
（不用 `DropdownMenu`，理由跟播放页设置菜单那条一样：`Popup` 开独立窗口看不到真实背景）——全部
`tunedGlass(liveGlassParamsFor(GlassTarget.Other).copy(chromaticAberration=false))`，跟本项目
其它屏幕同一份配方，不是另起一套参数。背景数据模型（`TeleprompterLibraryBackground`）挂在
`TeleprompterLibrary` 上，走现有 DataStore 持久化，没引入新存储机制。图片模式落盘存一份本地拷贝
（`util/FolderExt.kt` 的 `teleprompterBgFolder`），不存 `content://` URI——Photo Picker 的读权限
不保证跨进程/跨重启存活，除非显式 `takePersistableUriPermission` 且每个 provider 都遵守（不是本
项目已有的某条 PITFALLS 记录，是通用 Android 知识）。

**为什么**：这个屏幕是这轮之前唯一还没液态化的原生 Material3 屏幕（`LiquidDialog` 弹窗除外），
用户要求"如果没做都做上液态效果"，同时背景自定义只有在文档库真的有可自定义的实际内容时才有意义，
所以两件事一起做而不是分两轮。

**否决方案**：
1. 背景模型用 sealed class（每种模式一个子类）——没选，改成跟 `GlassTuningActivity` 自己的
   `BgMode` 一样的扁平字段结构，避免给"几种背景之一"这个形状引入第二套约定。
2. 每行"更多"保留原生 `IconButton`+`DropdownMenu`——否决，同一理由（`Popup` 看不到真实背景），
   跟播放页设置菜单那次改动保持一致，不做"这个屏幕单独例外"。

---

## 2026-08-15　纠正：预设系统的内置"默认"预设不删除，只改"应用"按钮的写入机制

**推翻了哪条**：本文档同一天更早的"提词器专属玻璃预设"条目里，`PresetSection` 曾经把内置"默认"
预设整个删掉（理由是它的"应用"效果=`resetGlassParams`，跟上面的"恢复默认"按钮功能重复）。用户
指出理解错了：内置"默认"预设这个概念本身应该保留（"上面有恢复默认预设这个按钮，下面有应用的
按钮，功能是一样的，所以把下面应用这个按钮改成目前设置配置，而不是把整个删掉"）。

**决定**：`builtInPreset`/`BUILT_IN_PRESET_ID`/`GlassPresetStore.allPresetsFor` 全部恢复，
`PresetSection` 继续用 `allPresetsFor(targets)` 显示内置"默认"排最前、不可删改。真正改的只有
`applyGlassPreset`：去掉"如果是内置预设就调用 `resetGlassParams`"这个特判分支，内置预设现在跟
每一个用户自建预设走同一条 `withParams` 写入路径——`应用`按钮的语义统一成"把这个预设自己存的
`paramsByTarget` 写进 `glassTuningStoreFlow`"，不再区分"内置的应该是 reset，别的应该是 apply"
两套逻辑。

**为什么**：`resetGlassParams`（删掉覆盖 key，之后每次读参数落回编译期 `defaultGlassParamsFor`）
和"写入一份等于当前默认值的显式覆盖"这两种写法，点击的瞬间视觉效果完全一样，但是是两种不同的
底层机制——保留内置预设这个概念、但让它的按钮走跟别的预设完全一样的代码路径，比"这一个预设的
应用按钮要走另一条特殊逻辑"更容易理解，也是用户明确要求的最小改动（"你只需要改应用按钮的功能
就行"）。

**否决方案**：
1. 维持原样（内置预设应用=resetGlassParams）——否决，用户没有要求改这条，是我之前錯误理解成
   "删掉整个内置预设"来解决重复问题，这次订正。

---

## 2026-08-15　`tunedGlass()` 改成 `@Composable` + `rememberUpdatedState`，绘制闭包内部读活值，不再冻结成组合期快照

**决定**：`Modifier.tunedGlass()` 从普通函数改成 `@Composable` 函数，内部用 `rememberUpdatedState`
包住 `params`/`shape`/`cornerRadius`/`onDrawSurface`/`extraEffects`，`effects`/`onDrawSurface`/
`highlight`/`shadow`/`innerShadow` 这些真正在绘制阶段执行的闭包，从"读组合阶段传进来的普通值"
改成"读 `live*.value`"——即在绘制闭包内部真正做一次 Snapshot State 读取，而不是闭包外层已经
把值拍扁成普通 Kotlin 值再传进来。`liveCombinedOnDrawSurface`（原 `GlassParams.
combinedOnDrawSurface` 改名）同样改成接收 `State<GlassParams>`。

**为什么**：真机双埋点（组合阶段一个日志、真正画 tint 的绘制闭包一个日志）+ 录屏逐帧比对坐实：
拖动滑块时数字标签正确更新，但玻璃画面的实际重绘只在手势/动画正在进行的那段时间发生，一旦静止，
无论后续参数怎么变都不再重绘，永远停在最后一次真正重绘的画面上——这不是三方库本身重绘触发做得
差，是这份代码自己的问题：`params.tint.alpha` 这类值在 `TeleprompterSettingsWindow` 组合阶段
就已经通过 `.copy(...)` 拍扁成普通 `Float`，绘制闭包里读到的是一个冻结值的闭包捕获，Compose 的
快照系统没有任何"活的" State 读取可以观察，自然没有理由在这之后重绘。改成在绘制闭包内部真正读
`rememberUpdatedState` 包出来的 `State.value`，让这次读取本身发生在绘制阶段——这是 Compose 绘制
阶段自动响应 State 变化重绘的标准机制（跟 `Modifier.drawBehind { someState.value }` 会在
`someState` 变化时自动重绘是同一套原理），不是借用手势/动画顺带触发重绘的临时手段。

**影响范围**：这是 `tunedGlass()` 这一处改动，全项目所有真实玻璃界面（顶栏、格式工具栏、大纲
抽屉、颜色选择器、设置窗口、文档库、播放页控制栏等）共用同一个函数，一次改动全部覆盖，不是只修
了透明度这一个字段——同时也应该修复"应用预设/恢复默认"这类不经过手势的程序赋值场景（这类场景
理论上受影响更严重，因为完全没有手势/动画可以顺带触发重绘）。

**还没做**：`highlight`/`shadow`/`innerShadow` 是否绘制（`enabled` 开关本身）仍然在组合阶段决定，
只有开启后内部的数值是活的——这两个目标当前都不会动态切换这几个开关，暂不处理，如果以后需要
也要跟进。真机验收还没走完（拖动全程手感、松手后是否停在最终值、应用预设/恢复默认是否无手势下
立刻生效），过程中手机反复被切到其它 App 前台，干扰了自动化验证，需要用户自己确认，见
`docs/STATE.md`。

**否决方案**：
1. Part 二的"借用动画通道"方案（给依赖这套渲染的值包一层 `Animatable`，在 `onDragEnd`/程序赋值
   时刻对它 `animateTo` 同一个目标值，借用"动画进行中会触发重绘"这条已证实有效的路径）——按用户
   要求先验证 Part 一（这次实现的方案）是否可行，验证通过就不需要 Part 二这个更绕的兜底方案。
2. 在每个调用点各自处理"读活值"——否决，`tunedGlass()` 是全项目唯一入口，在这一处改一次，比
   十几个调用点各自加 `rememberUpdatedState` 更不容易漏改、也更不容易以后又有新调用点忘记加。

---

## 2026-08-15　再次订正：预设列表"默认"行改成"当前"行，应用它不再改任何东西

**推翻了哪条**：本文档同一天更早那条"纠正：预设系统的内置'默认'预设不删除，只改'应用'按钮的写入
机制"——那次只改了写入机制（`resetGlassParams`→`withParams`），但这一行的**内容**没变，还是
`defaultGlassParamsFor`（出厂默认值），点它的"应用"结果还是"把参数变回出厂默认"，跟上面的
"恢复默认"按钮功能依然重复。用户真机截图指出这两个按钮"一模一样的功能，两个不一样的按钮，非常
反人类"。

**决定**：这一行不再代表"出厂默认"，改名"当前"，内容从 `glassTuningStoreFlow` 的**实时值**现算
（`remember(tuningStore, targets)`，`tuningStore` 来自 `glassTuningStoreFlow.collectAsState()`），
不再是 `defaultGlassParamsFor`。`GlassPresetStore.kt` 里 `builtInPreset`/`BUILT_IN_PRESET_ID`/
`allPresetsFor` 删掉，改成 `CURRENT_PRESET_ID` 常量（`PresetSection` 直接在 UI 层现算这一行，不
需要专门的构造函数）。这一行"应用"按钮保留（用户明确要求"保留应用这个按钮"），点击后走跟其它
预设完全一样的 `applyGlassPreset`，只是写回的值等于当前已经生效的值——在没有其它东西修改
`glassTuningStoreFlow` 的正常情况下是纯粹的无操作，不会再意外把用户刚调好的参数抹掉。

**为什么**：用户明确指出这一行不该"应用默认值"，应该"应用目前设定的参数"，并且明确要求不要把
这个按钮跟"保存新预设"绑在一起（那是"保存当前为新预设"单独的职责）。这样设计之后，就算用户
习惯性点了列表第一行（历来出问题的场景——它总是排最前，最容易被误触），也不会再丢失刚才的调整，
从根源上消除了"反人类"的那次真实反馈。

**否决方案**：
1. 删掉这一行——用户之前已经否决过一次（"默认预设要留着"），这次继续维持"保留但改内容"的方案。
2. 保留"默认"这个名字、只改底层写入机制——就是被推翻的那条，验证后发现没有解决真正的 UX 问题，
   这次订正为改变这一行代表的实际内容。

---

## 2026-08-15　自我纠正：上一条"再次订正"改动范围超出了用户要求，收窄成最小改动

**推翻了哪条**：本文档同一天更早那条"再次订正：预设列表'默认'行改成'当前'行"——那次改动除了
改这一行应用的内容之外，还顺手把 UI 标签从"默认"改成"当前"、把 `builtInPreset`/
`BUILT_IN_PRESET_ID`/`allPresetsFor` 删掉重命名成 `CURRENT_PRESET_ID` 换了个实现方式。用户指出
"只让你改功能，没让你改任何一个地方"——这些改名/重构都是没被要求的范围外改动。

**决定**：撤回改名和结构重构，只保留真正被要求的那一处改动——`builtInPreset(targets)` 函数名、
`BUILT_IN_PRESET_ID` 常量名、`allPresetsFor` 扩展函数、UI 上"默认"这个标签文字，全部原样恢复。
唯一实际改动：`builtInPreset()` 内部 `paramsByTarget` 的取值来源从 `defaultGlassParamsFor(it)`
换成 `glassTuningStoreFlow.value.paramsFor(it)`——只改"点这一行应用之后实际把什么值写回去"这一
件事，这一行显示的名字、代码里其它一切标识符都不动。

**教训**：用户明确说"只改功能"时，要按最字面的意思执行——只改被点击后触发的那段逻辑本身，不要
顺手做看起来"更一致"的命名/结构调整，即使那些调整本身没有错，只要没被要求就不要做。这条不需要
否决方案栏——是执行范围理解错误，不是技术方案的取舍。

---

## 2026-08-15　预设"默认"行改成真的响应式：`PresetSection` 自己订阅 `glassTuningStoreFlow`

**背景**：上一条"再次订正"把这一行的内容从 `defaultGlassParamsFor` 换成了
`glassTuningStoreFlow.value.paramsFor(it)`，编译通过、逻辑上看起来对，但用户自己录屏证实：调整
参数后点"应用"，画面还是跳回旧值——这个"修复"实际上没生效。

**决定**：`builtInPreset`/`allPresetsFor` 加一个 `store: GlassTuningStore` 参数，内部只用这个
传入的值，不再自己读 `glassTuningStoreFlow.value`；`PresetSection` 里加
`val tuningStore by glassTuningStoreFlow.collectAsState()`，把这个通过 `collectAsState()`
拿到的值传进去。函数名、常量名、UI 标签这些上一次已经按用户要求收窄成最小改动的地方，这次继续
保持不变。

**为什么**：根因是 `StateFlow.value` 在 Composable 里直接读取，不会让 Compose 的快照系统知道
"这段代码依赖这个值，它变了要重新跑"——`PresetSection` 自己又没有独立订阅 `glassTuningStoreFlow`，
只能指望父级重组时顺带带下来，但 Compose 的智能跳过机制在子级参数没变时很可能直接跳过，导致这一行
的内容冻结在某次早前快照上。详见 `docs/PITFALLS.md` 对应条目。

**否决方案**：无——这不是技术选型的取舍，是把"看起来对但没有实测证实"的修复，换成"有真机录屏证据
支撑"的修复，唯一要做的就是把遗漏的 `collectAsState()` 补上。

---

## 2026-08-16　启动页背景自定义提升为跨屏共享组件（`glassui/GlassBackground.kt`）

**决定**：文档库页原有的"四种背景模式（默认/纯色/渐变/图片）+ 图片亮度/饱和度/模糊度调节"
（`TeleprompterLibraryBackground`/`TeleprompterLibraryBgMode`），改名提升为 `GlassBackgroundConfig`/
`GlassBgMode`，移到 `glassui/GlassBackground.kt`，渲染逻辑（`GlassBackgroundLayer`）和编辑 UI
（`GlassBackgroundEditor`）只有一份实现，靠 `defaultContent` 插槽让"默认"模式在不同屏幕表现不同
（文档库=纯色主题背景，启动页=原有的动效光斑）。落盘字段名不变，旧文档库背景数据继续兼容。

同一次改动顺带把两处"文档库那边其实没做实"的地方按启动页的新需求定型：渐变模式新增
`gradientAngleDeg` + 自定义两色入口（原来只有 5 个写死预设，没有自由选色）；图片模式新增对比度
（`imageContrast`）和透明度（`imageAlpha`，走 `Image()` 自己的 `alpha` 参数，不进 `ColorMatrix`），
跟原有的亮度/饱和度/模糊度并列，不是替换。

**为什么**：启动页要接入跟文档库一样的背景自定义系统，与其新写一套，不如把文档库那份（已经验证过
在真机上工作正常）提升成两边共用——用户原话"同一套背景组件（同一套数据结构、同一套调节 UI），
不是重新定义一套不同的'四种模式'"。

**否决方案**：无——这次不是技术选型的取舍，是识别出"这两处需求本质是同一个组件，只是挂载位置不同"，
提炼公共逻辑。

---

## 2026-08-16　提词器三屏（文档库/编辑页/播放页）液态效果拆分，四个入口共用同一屏幕实现

**背景**：文档库/编辑页/播放页三个屏幕的液态玻璃元素此前全部共用 `GlassTarget.Other` 一份参数
（历史遗留——`Other` 最早是"给还没专门分类的界面用的通用桶"，三屏陆续接入液态玻璃时都直接用了它，
没人分过）。用户明确要求三屏互不干扰，且文档库此前完全没有自己的调节入口。

**决定**：新增 `GlassTarget.TeleprompterLibrary`/`TeleprompterEditor`/`TeleprompterPlayer` 三个
target，`GlassTuningActivity.kt` 里 `TeleprompterGlassTargets` 这一个常量拆成三个
（`TeleprompterLibraryGlassTargets`/`TeleprompterEditorGlassTargets`/`TeleprompterPlayerGlassTargets`），
`globalIntent()`/`restrictedIntent()` 这套"一个屏幕、按 target 列表限定显示范围"的机制不变，四个
入口（含启动页/外观设置那个 `GeneralGlassTargets`）全部走同一个 `GlassTuningActivity`，**只是传的
target 列表不同**，不是四份独立实现。共享的大纲抽屉组件（`TeleprompterOutlinePanel`/
`OutlineScrubTrigger`）因此也不能再内部写死 target，改成调用方传参。入口位置：文档库顶栏新增齿轮
图标（之前没有）；编辑页维持原位置不变；播放页因为没有独立顶栏/设置界面，用户明确要求把入口放进
编辑页设置面板"播放页"分节里，并加小字说明避免用户搞混"在编辑页面板里调的其实是播放页"。详细的
target/入口对照表见 `docs/GLASS.md`"调参面板"一节，**以后新增/修改调参面板的行为（滑块样式、
预设 UI、导出逻辑等）只需要改 `GlassTuningScreen` 这一处，四个入口自动同步，不要因为要新增
target 就分叉出第二份屏幕实现**。

**为什么**：`Other` 被三个完全不相关的屏幕共用，本质上就是没有真正分类——拆分是把这个历史遗留
名副其实地纠正掉，而不是新增什么复杂机制；`restrictedIntent` 早在提词器 vs 启动页那次拆分
（2026-08-16 更早的一条决定）就已经证明了"一个屏幕 + target 列表参数"这个模式可以无痛扩展到更多
入口，这次只是照着同一个模式再加三个。

**否决方案**：给每个屏幕单独写一个精简版调参面板——否决，`GlassTuningScreen` 本身的滑块/预设/
FPS/导出这套 UI 没有任何屏幕专属逻辑，唯一变化的只是"允许改哪些 target"，用参数控制比复制四份
实现更省维护成本，也是这个项目从第一次拆分（提词器 vs 启动页）就已经验证过的路径。

---

## 2026-08-16　虚拟定位刷新间隔/精度值改走 DataStore 可调，不再硬编码

**背景**：高德地图收到 GKD-T 的模拟定位后仍显示真实位置。先用 `dumpsys location` 实机确认注入
链路本身没断（高德确实注册成了 gps provider 监听者，也确实收到了模拟坐标，见 PITFALLS.md 或
STATE.md 对应记录）——问题只能是高德自己在更细的特征上判断"这不像真实 GPS"。新写了一个纯被动
监听、不做任何注入的诊断工具（`MockLocationDiagnosticActivity`），跟已知能骗过高德的第三方 App
"影梭"做同格式 A/B 日志对比，实测出两处真实差异：刷新间隔（GKD-T 1000ms vs 影梭约 100ms，差 10
倍）、精度值（GKD-T 两个 provider 硬编码共用 `accuracy=5f`，影梭 gps 比 network 精确，两个
provider 给不同值）。

**决定**：这两个值不直接改成新的硬编码常量，而是新建 `MockLocationTuning.kt`，走项目里
`GlassTuningStore`/`CallGuardStore` 已经用过的同一套模式——`@Serializable` 数据类 +
`createAnyFlow` DataStore 持久化，`MockLocationService` 每次推送都读最新值（不是启动时读一次
缓存），配一个可以直接在诊断工具页面调的滑块 UI。默认值改成 100ms/gps=1.0/network=2.0，对齐这次
A/B 结果，但不当成理论最优值锁死。

**为什么**：用户原话"这两个数值做成可调，不要重新硬编码死……以后遇到别的 App 未必是同一组数字
最合适"——这次的 100ms/1.0/2.0 是"跟影梭对齐后能验证生效"的一个具体取值，不是穷举调参得出的
最优解，后面遇到别的目标 App（不止高德）大概率还要再调，做成可调比每次改代码重新装机成本低得多，
也是这个项目已经反复验证过的路径（不是新发明一套机制）。

**否决方案**：直接把 1000→100、accuracy 5f→(1f, 2f) 改成新的硬编码值——否决，理由同上，这只是
"对着一次 A/B 结果抄一遍数字"，下次换个目标 App 结论可能完全不同，硬编码等于每次都要重新走一遍
"改代码→编译→装机→测试"的循环，不如一次性做成可调。

## 2026-08-17：richeditor-compose 从 1.0.0-rc14 升级到正式版 1.0.0

**决定**：把 `gradle/libs.versions.toml` 里的 `richeditorCompose` 从 `1.0.0-rc14`（2026-04-26）
升到正式版 `1.0.0`（2026-07-20）。不选 `1.0.0-cmp-1.12.0-beta03`（2026-08-10，虽然日期更新，但
它是专门为 Compose Multiplatform 1.12.0-beta03 出的变体，本项目用的是 Compose 1.11.2 稳定版），
也不选 `1.0.0-dev01`。

**为什么**：正在查的"选区手柄拖动方向反了/乱跳"这个 bug，在 1.0.0 的 release notes 里有两条直接
对应的修复，rc14 里都没有：
- `fix: prevent drag selection from jumping to the next line at line edges`（PR #748）——跟用户
  描述的"我往后拖，他往前跳，我往前拖，他往后跳"几乎逐字对应。
- `fix: diff-based edit pipeline, selection stability, parser round-trips`（PR #746）。
已有诊断（见 PITFALLS.md 同名条目）证明这个 bug 在渲染层而不是数据层，而渲染层是库的私有实现，
应用层改不到——继续在一个落后好几个版本、且上游已经修过同类问题的基线上做应用层修补是无用功。

**版本号是怎么确认的（这一步本身就是个坑，见下）**：
- Maven Central 的 solr 搜索接口（`search.maven.org/solrsearch`）返回的最新版本是 `1.0.0-rc13`，
  **连本项目当时已经在用的 rc14 都没有**——这个索引是滞后的，不能作为版本依据。
- 权威来源是 `repo1.maven.org/.../maven-metadata.xml`。但它的 `<latest>`/`<release>` 两个标签都
  写着 `1.0.0-dev01`，而 `1.0.0-dev01` 实际发布日期是 **2024-04-24**（两年前的 dev 构建），只是
  因为版本字符串排序才排在最后。**这两个标签在这个库上是错的，不能直接信。**
- 最终靠逐个 HEAD 请求各候选版本的 `.pom` 文件、读 `Last-Modified` 头拿到真实发布时间，才确定
  `1.0.0`（2026-07-20）是要的那个。

**否决方案**：
- 继续在应用层改选区处理逻辑——已经证明是渲染层问题，够不到，而且上游已修。
- 升到 `1.0.0-cmp-1.12.0-beta03`——为不同 Compose 大版本出的变体，会把 Compose 也拖到 beta。
- 把库 fork 到本地改——上游正式版已经修了，没必要背这个维护负担。

**升级代价**：零源码改动，`:app:compileGkdDebugKotlin` 直接通过，没有 API 破坏（1.0.0 唯一的
breaking change 是移除 iosX64 target，本项目只有 Android，不受影响）。

## 2026-08-20：拆分私人版 / 公开版两个构建

**决定**：在原有的 `channel` 维度（gkd/play，上游 GKD 带来的）之外，新增第二个产品维度
`edition`，两个取值：`priv`（私人版，全部 5 个功能）和 `pub`（公开版，删掉"去广告"和"虚拟定位"，
保留"电话拦截""定位修改""提词器"）。日常构建命令因此从 `assembleGkdDebug` 变成
**`assembleGkdPrivDebug` / `assembleGkdPubDebug`**。

**applicationId**：
- `priv` 不加任何后缀，保持 `li.songe.gkd.taiclear`(+`.debug`) 跟拆分前完全一致——用户手机上已经
  装着的那份（含全部提词器文档、规则、玻璃预设）直接原地升级，不会被当成另一个应用而丢数据。
- `pub` 加 `.pub` 后缀，两个版本可以同时装在一台机器上对比测试，也不会互相覆盖数据。

**"删掉"落实到三层，不只是藏入口**：
1. **启动页卡片**：`LauncherActivity` 里改用 `listOfNotNull` + `takeIf { BuildConfig.HAS_AD_SKIP }`
   / `HAS_MOCK_LOCATION` 过滤。
2. **清单组件**：新增 `app/src/pub/AndroidManifest.xml`，用 `tools:node="remove"` 摘掉
   MockLocationActivity/MockLocationDiagnosticActivity/MockLocationService、无障碍服务
   （`SelectToSpeakService`，"去广告"的功能本体）、以及 MainActivity + OpenScheme/OpenFile/
   OpenTile 这三个只为 GKD 内核服务的外部入口。**MainActivity 被 Notif 和那三个 Activity 引用，
   所以必须一起摘，不能只摘它自己留下会跳向它的那些。**
3. **权限**：同一份清单里一并摘掉 ACCESS_MOCK_LOCATION、FOREGROUND_SERVICE_LOCATION、
   WRITE_SECURE_SETTINGS、GET_APP_OPS_STATS、QUERY_ALL_PACKAGES、GET_INSTALLED_APPS。
   这一层最重要：公开分发时，别人在"应用信息 → 权限"里看到"修改位置信息""读取已安装应用列表"
   这类声明，观感和信任度是完全不同的。实测 aapt2 dump badging：权限数 20 → 14。

**为什么不连 Kotlin 类一起删**：两个版本共用一份源码（不分 sourceSet），被摘掉的类还在包里但没有
任何一处会去启动它们。真要连类一起删，等于把整个 GKD 上游内核从工程里摘出去，代价和回归风险都
远大于收益。清单层面摘干净已经达成"用户看不到、外部拉不起、权限不申请、服务不运行"这四件事。

**否决方案**：
- 用运行时开关（一个 APK 里根据设置隐藏功能）——权限还是会照常申请，公开分发时最关键的一层没解决。
- 直接复用 `channel` 维度加两个新 flavor——语义冲突（channel 是发布渠道），而且会跟 play 渠道的
  签名/applicationId 逻辑纠缠在一起。

**运行时安全性核对**（公开版摘掉 MainActivity 之后）：`App.startLaunchActivity()` 走的是
`getLaunchIntentForPackage`，解析到的是 LauncherActivity（保留），不受影响；`Notif.kt` 里那处
`MainActivity::class.componentName` 只是构造 ComponentName，不会因为组件不存在而抛异常，而且发通知
的那些 GKD 内核服务在没有无障碍服务的情况下根本不会跑起来。

**同时新增**：启动页底部常驻一行「版本类型 + 版本号 + 开发者」，点击展开「关于 / 使用说明」浮层
（`LauncherAbout.kt`）。开发者署名：**癸星人**。使用说明按版本自动裁剪——它和功能卡片读的是同一组
BuildConfig 开关，不会出现"说明书里写了但应用里找不到"。浮层用的是"同一棵组合树里的覆盖层"写法，
不是 Dialog/Popup（Dialog 另开系统窗口、采样不到 backdrop，玻璃会变成空白板子，这个坑 PITFALLS.md
记过），并且按项目惯例配了 `BackHandler`。

## 2026-08-20（修订）：改为两个独立工程文件夹，公开版物理删除代码

**背景**：当天早些时候先做的是"单一工程 + 两个 edition 构建变体"（见上一条）。做完后核对产物，
如实告知用户：功能确实完全失效（无入口、无组件、无权限），但**Kotlin 类仍然留在 APK 的 dex 里**，
解包能看到。用户据此明确选择："公开版直接做备份，重新开一个文件夹，直接复制过去"。

**决定**：撤销 edition 维度，恢复单一 `channel` 维度和原来的 `assembleGkdDebug`。改为：
- **私人版** `F:\gkd-fork` —— 全部 5 个功能，applicationId `li.songe.gkd.taiclear`（不变）
- **公开版** `F:\gkd-public` —— 3 个功能，applicationId `li.songe.gkd.guiyue`，**独立 git 仓库**

**为什么不复制 .git**：版本历史里含有被删功能的完整源码，一起带过去等于没删（`git log -p` 就能
全部还原）。公开版是 `git init` 从零开始的新仓库。

**代价（已明确告知用户并被接受）**：提词器/电话拦截/定位修改/液态玻璃组件是两边共用的，以后每次
修复都要手工同步两遍，两个版本会逐渐产生差异。用户在被告知这一点后仍选择此方案。

**两边唯一"故意不一样"的文件**：`app/src/main/kotlin/li/songe/gkd/Edition.kt`（版本名称、两个功能
开关、开发者署名）。其余源码应保持一致。

**公开版实际删除的内容**：
- 虚拟定位：`mockloc/` 包、`mock-location.html`、单元测试
- 去广告（GKD 内核）：`a11y/`、`shizuku/`、`notif/`、`service/`、`data/`、`permission/`、`db/`、
  `ui/{home,component,icon}` 及 `ui/` 顶层 43 个界面文件、`MainActivity`/`MainViewModel`/
  `Open{Scheme,File,Tile}Activity`、`SelectToSpeakService`、shizuku 的 AIDL
- 清单：上述全部组件 + 17 个后台/磁贴服务；权限 20 → 7
- 依赖：Shizuku（它会往清单合并 `moe.shizuku.*` 权限）

**保留文件里需要就地裁剪的（这些是"共用但混着内核代码"的文件）**：`App.kt` 启动流程只剩
`initToast()`/`initStore()`；`SettingsStore` 从 30+ 字段缩到 2 个（深浅色、动态取色，由主题读取）；
`StoreExt`/`FolderExt`/`IntentExt`/`Others`/`CoroutineExt`/`LocalExt` 各自去掉内核部分；
`Toast.kt` 从"自绘悬浮 Toast"退化为系统 Toast——原实现要靠无障碍服务和 OverlayWindowService 才能
画在别的应用上面，那条通道随内核一起没了。

**产物核对**（务必按这个方法验收，不要只看界面）：`aapt2 dump badging` 比权限，解包 dex 用
`zipfile` 搜符号。结果：dex 中已无 `mockloc`/`a11y`/`shizuku`/`service`/`MainActivity`/
`RawSubscription`，提词器/电话拦截/定位修改齐全；体积 34.0MB → 28.4MB。

---

## 2026-08-26 提词器五项显示设置：去掉全局层，改成每篇文档独立

**背景**：2026-08-25 刚做完"全局值 + 每篇文档可选覆盖"的两层模型（正文默认字号/整体缩放/行距/
对齐方式/内容宽度），设置面板里每项配一个"跟随全局设置 / 这篇文档单独设置"开关。

**用户判断（原话要点）**："一篇文档就该有一篇文档的设置，所有文档共用一套全局值这个概念本身就
多余，开关是为了弥补这个多余概念而存在的，一并去掉。"

**决定**：
- 五项改成**每篇文档各存一份自己的完整值**，删掉全部五个开关，滑块直接读写当前文档。
- `TeleprompterSettings` 里那五个字段**保留但改变身份**：从"全局设置"变成**"新文档默认值"**，
  只在新建/导入文档的那一刻被读一次（`newDocumentDisplaySettings()`），此后那篇文档完全独立。
  该类里其余字段（`scrollSpeed`/播放页配色/`mirrored`/面板玻璃参数）**仍然是真正的全局设置**，
  不参与本次改造。
- 新文档默认值取"中性基线"（用户指定：倍数一律 1、字号取滑块下限 16sp）。

**否决过的方案 / 明确的取舍**：

1. **数据结构没有改成必填字段**（用户说"可以简化成普通必填字段，改动幅度你们评估"）。保留可空 +
   靠迁移保证恒不为空。理由：老库文件里 `displayOverrides` 缺字段的情况占绝大多数（13 篇里 12 篇
   是空对象），改成必填会让 kotlinx 在缺字段时落到数据类默认值，**把用户当时的实际显示效果冲掉**；
   而 `@SerialName` 改名同样有丢数据风险。代价是 `TeleprompterDisplayOverrides` 这个"覆盖"味道的
   名字留了下来，靠注释说明它现在的真实含义（`effectiveSettings()` 里那六个 `?:` 已降级为纯兜底，
   不再是"回退到全局"的语义）。

2. **`contentWidthLandscape` 的新文档默认值保留 0.65，没有按"倍数一律 1"改成 1.0**。这个 0.65
   不是随手设的数，是更早那条"横屏文字铺满整行、眼睛横扫距离过大跟不上滚动"的实测结论；改成 1.0
   等于推翻一个已验证的判断。已向用户明确说明并留待其决定。

3. **没有给"新文档默认值"做编辑界面**。它只在创建那一刻被读一次，面板里的滑块全部改成直接读写
   当前文档——用户要求的"界面措辞不能让人误以为改这个会影响所有文档"，是靠**根本不存在这个控件**
   满足的，而不是靠改文案。要改新文档默认值需另加入口。

**迁移（本次改造真正的风险所在）**：`migrateToPerDocumentDisplaySettings()`，靠新增的
`TeleprompterLibrary.schemaVersion`（老文件无此键 → 解码为 0 → 触发）保证只跑一次。
- **逐字段**判断：文档已有自己的值就保留，没有的才从旧全局值补。不是整份对象二选一——一篇文档
  完全可能只有 `fontSizeSp` 一项是自己的。
- 不碰 `updatedAt`：迁移不是用户编辑，不该让文档在"最近修改"里跳位置。
- 迁移后把 `settings` 里那五项重置为中性默认值（身份已变，继续拿用户当初随手调的数当新文档起点
  没有道理），其余全局字段原样保留。

**验收方式（这次定下的标准，以后同类迁移照此办理）**：断言的不是"迁移函数被调用过"，而是那个
唯一重要的性质——**每篇文档迁移前后 `effectiveSettings()` 六项逐字段完全相同**（渲染侧只认这个
函数，它不变就等价于屏幕上看着不变）。单元测试 8 条覆盖该性质 + 已有值不被冲掉 + 无 null 残留 +
真全局字段不被误动 + 重复跑不做事 + 空库不崩 + 导入缺字段补默认值。另外拿用户真实的 13 篇文档
库文件离线跑了一遍同样逻辑逐字段比对（0 处不一致），再在真机上装机复验（0 处不一致、0 个 null）。

**顺带修掉的基础设施缺陷**：迁移第一版"日志说迁了、磁盘没迁"，根因是 `createTextFlow` 的写盘
协程 `drop(1)` 撞上 `scope.launch` 还没开始收集。给 `MutableStoreStateFlow` 加了 `persistNow()`
（同一套原子写），这是本次唯一改到共享存储层的地方。详见 `PITFALLS.md` 同名条目。

---

## 2026-08-28 提词器三页统一文本渲染：拆除 scaledForDisplay，显示参数全部归中性

**目标**：编辑页 / 阅读态 / 播放页三处渲染同一篇文档时，**断行位置必须逐字符一致**。此前三页是
三份实现（编辑态焦点章节走 `BasicRichTextEditor(state)` 喂原始 `annotatedString`；阅读态和播放页
各走 `Text(annotatedString.scaledForDisplay(...))` 喂变换过的副本），加上编辑页独有的
`editZoomScale` 缩放和两边不同的横向内边距，断行不可能对齐。

**做法**：新增 `TeleprompterBodyRenderer.kt`，三页共用唯一的 `TeleprompterBody`，内部一律是
`BasicRichTextEditor`，只读模式靠 `readOnly=true` + 透明 `cursorBrush` + 吃掉指针事件实现。
`mode` 只允许影响"能否编辑/光标是否可见/是否接受交互"，**不允许影响任何排版参数**。横向内边距
（`TELEPROMPTER_BODY_HORIZONTAL_PADDING`）和内容宽度比例都归渲染器所有，页面不再自己加。

**为什么 scaledForDisplay 必须整体删除，而不是"保留但少用"**：编辑态必须渲染可编辑的那份
`RichTextState`（所见即所存）。而 `RichTextState` 的公开写入口只有 `setHtml`/`setText`/
`setMarkdown`，唯一的 AnnotatedString 写入口 `updateAnnotatedString$..._release` 是 **internal**
（用 `javap` 核对过 richeditor-compose 1.0.0 的 `classes.jar`）。**没有任何受支持的办法把变换后的
AnnotatedString 塞回 `RichTextState`**，所以只要这个变换还在，阅读/播放喂给排版引擎的输入就必然
和编辑页不是同一个对象。这是库的 API 边界，不是工作量问题。

**代价（已量化并被用户接受）**：整体缩放 / 行距 / 对齐现在只作用于基础 TextStyle，即只对"没有
自带样式"的文字生效。沉香话术实测：1736 个 run 里 **1262 个自带显式字号**（5 个取值，10.5pt 和
14pt 两档占 90%）；330 段里 **221 段自带显式行距**。设置面板四条文案已同步改为如实描述，并删掉了
原来"请用『整体缩放』调整"这类改造后会变成错误引导的指引。

**决策：拆除的同时把所有显示参数归中性**（正文默认字号 16sp、整体缩放 1.0、行距 1.0）。
理由不是"图省事"，而是**旧值是在旧接线下靠肉眼凑出来的补偿值，喂给新接线会产生失真**。实测例子：
沉香话术旧值行距 1.724 + 正文默认字号 19.78，而正文实际渲染字号只有 10.5/14px。旧接线下行距按
"段内最大字号 × 1.724"算（10.5px 段 → 18.11sp）；新接线下变成"正文默认字号 × 1.724"统一值
（34.12sp），**同一段行距直接放大到 1.88 倍**。归中性之后这个失真源消失，用户在新接线下重新调
出来的值才是对新行为有意义的。

**否决方案一：保存时做逆变换**（三页都渲染变换后的文本，`toHtml()` 前除回去）。表面上鱼与熊掌
兼得，实际不可行：`scaledForDisplay` 里段落行距被改写成 Em 相对值、对齐被强制覆盖，这两项不可逆；
且浮点每次存取都会漂移。本项目已经因为 `toHtml()` 归一化的有损往返吃过亏（见
`TeleprompterEditorScreen.kt` 失焦保存分支的注释：重新序列化未改动的内容会凭空缩掉几十个字符），
不能再叠一层有损变换。

**否决方案二：用 `LocalDensity.fontScale` 做整体缩放**。技术上更干净——它作用于 sp→px 的换算，
天然覆盖显式字号，不需要改文本对象，也不破坏断行一致性。但**没有验证过**：它会同时影响该 Composable
子树内的一切 sp 尺寸（含内边距语义、副标题、工具栏），副作用范围未知。按项目准则"不在灭 bug 的
同一轮引入未验证的优化"，本轮不做，**留待独立一轮专门验证**。

**待办：导入时字号归一化**。真正的根治方向是在 docx 导入阶段就把 Word 的绝对磅值折算成相对基础
字号的倍数（按实测分布：10.5pt → 继承基础字号，14pt → 1.33×），让文档内部只保留相对关系。做完
之后整体缩放/行距/对齐就能重新覆盖全部文字，上面那四条文案应还原为"对所有文字生效"。

**已知回退**：正文长按"提升为副标题"的**触发入口暂时失效**——它原来挂在只读 `Text` 上靠
`getOffsetForPosition` 换算段落序号，正文统一成可编辑控件后长按是系统文本选择手势，两者抢事件
（本项目已因"两个手势探测器抢事件"踩过坑）。后端 `promoteParagraphToChapter` /
`paragraphIndexForCharIndex` 均未删除、单元测试仍覆盖，等定了新触发方式（备选：格式工具栏加按钮，
对光标所在段落生效，光标位置本来就有）再接回去。
