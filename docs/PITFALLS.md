# 踩过的坑

本项目最重要的一份。**方法比结论有用**——每条都记"怎么查出来的"，不只是"改了哪一行"。

格式：现象 / 根因 / 修法 / 教训。**只追加新条目，不要给老条目加水**；
待验收事项写 `STATE.md`，不写这里（会过期）。

2026-09-06 全文精简过一轮：删掉叙事铺陈和过期的待验收 APK 文件名，技术细节一条没删。

---

## Compose 手势/状态冲突类

同一类根因反复出现过多次：**两套机制在同一个节点上抢同一个输入，而不是分工**。

**1. 长按 3 秒入口，短按就误触发。** 裸 `pointerInput` 手动判长按：
`withTimeoutOrNull(3000L){ waitForUpOrCancellation() } == null`——这句在"真等够 3 秒"和
"`.clickable` 提前消费了手势"两种情况下都成立，代码区分不了。
修法：让 `.clickable` 当权威源，外面读它的 `interactionSource.collectIsPressedAsState()`，
配 `LaunchedEffect(pressed){ if(pressed){ delay(3000); onLongPress() } }`，不抢原始事件。
（该入口 2026-08-11 已整个删除，教训仍适用。）

**2. LiquidSlider 拖动被 `ModalBottomSheet` 下滑关闭手势抢走（未解决）。**
点一下设值正常，只有拖拽会把面板整个收起。试过三个变体全部无效：`Modifier.draggable`、
手动 `detectHorizontalDragGestures` + 显式 `change.consume()`、移植官方 catalog 的
`DragGestureInspector.inspectDragGestures`（读源码确认它自己**从不调用** `consume()`）。
真正的修法大概率是拿掉 `ModalBottomSheet`、换成自己控制的全屏玻璃层 + 明确关闭按钮。
**教训：同一个方向连试三次都不行，就该怀疑"压根不该有两套机制在竞争"，而不是继续换 consume 写法。**

**3. `LaunchedEffect` 的 key 包含了它自己会改的状态 → 自我取消/重置。**
（设计阶段预见并防住的，非事故记录。）章节富文本 hydration 若只用章节 `id` 当 key，
打字触发的防抖自动保存写回 `contentHtml` 会跟"内容被结构性改写"无法区分，光标每次自动保存都被重置。
修法：加独立字段 `structureVersion`，只在合并/拆分时手动 +1，打字不碰；key 用 `(id, structureVersion)`。

---

## 液态玻璃：背景捕获必须是单个 `drawBehind`，不能拆子 Composable

**现象**：启动页玻璃卡片后面一片空白，采样不到渐变+光斑背景。
**根因**：渐变和光斑分散在嵌套子 `Box` 里画，`layerBackdrop` 挂最外层容器，捕获回来是空的。
**修法**：整个背景（渐变 + 4 个光斑）收进同一个 `drawBehind{}`，画在挂 `layerBackdrop` 的节点自己身上。
**教训**：`layerBackdrop` 捕获的是这个节点**自己的**绘制内容，子 Composable 各自的绘制不一定算数。
背景越复杂越想拆，而拆了就越容易踩。

## 液态玻璃：`layerBackdrop` 挂在祖先节点上 → 原生 SIGSEGV，`adb logcat` 默认看不到

**现象**：打开提词器设置面板直接卡退，`grep "FATAL EXCEPTION"` 什么都搜不到。
**根因**：`layerBackdrop(sheetBackdrop)` 挂在了包含多个 `LiquidSlider` 的 `Column` 上，
而这些 slider 又 `backdrop = sheetBackdrop` 采样它——捕获层子树里包含正在采样它的控件，
RenderNode 树循环引用，`RenderNode::prepareTreeImpl` 递归到栈溢出。
**怎么查的**：必须 `grep -iE "fatal signal|tombstone|SIGSEGV"` 才看得到
`Fatal signal 11 (SIGSEGV) ... (RenderThread)`，tombstone 写着 "stack pointer is not in a rw map;
likely due to stack overflow"，backtrace 是 `RenderNode::prepareTreeImpl` ↔
`SkiaDisplayList::prepareListAndChildren` 互相递归。第一个假设（折射参数过大越界）错了，
加的 `maxRefractionPx` 钳制是合理防御但没解决；同一 fault address 反复复现后回头画父子关系图才看出循环。
**修法**：捕获节点必须是玻璃控件的**同级 sibling**，不能是祖先：
`Box(Modifier.matchParentSize().layerBackdrop(x).background(color))` 跟玻璃控件并列。
**教训**（这条最贵）：
1. 原生 SIGSEGV **不是** Kotlin 异常，默认关键词搜不到，会误判成"没崩溃"。
2. 自动化真机测试每步操作后用 `pidof <package>` 确认进程还活着，比等 UI 反馈快得多。
3. 第一个合理假设验证失败后，回到证据本身重想，别在同一假设上加码。

## 液态玻璃：色散着色器采样坐标无 clamp，窄长面板花屏

**现象**：细长玻璃面板（大纲抽屉约 1:6.2、播放控制栏约 1:6.9）开 `chromaticAberration` 时边角彩色雪花。
**根因**：库的色散 shader（fork 与官方 2.0.0 逐字节相同）用
`dispersionIntensity = chromaticAberration * (centeredCoord.x*centeredCoord.y) / (halfSize.x*halfSize.y)`
——像素**原始位置**的鞍形函数，跟法线无关，面板越窄长角落越接近 ±1。7 次 `content.eval()` 采样
（red/orange/yellow/green/cyan/blue/purple）坐标全无 clamp，越界读到捕获缓冲区外的数据。
**怎么查的**：先怀疑圆角=0 导致 SDF 法线退化（`normalize(max(cornerCoord,0))` 零向量未定义），
改 2dp 最小半径后花屏原样复现，排除。再做真正的 A/B：只关这一处 `chromaticAberration`，花屏消失。
又对着官方 2.0.0 本地克隆逐行核对，确认逐字节相同且从未被改过，而库自己另一个
`ReflectiveGlassShaderString` 对色散坐标**是有 clamp 的**——这个不一致就是"遗漏而非设计"的强证据。
**修法**：自己实现带 clamp 的色散 shader（`glasstuning/PatchedLens.kt`），7 处
`content.eval()` 全部 `clamp(coord, float2(0.0), size)`。详见 `GLASS.md`。
**教训**：视觉损坏类问题，先做能**推翻**假设的对照实验（"关掉这一个开关，问题消失吗"）。
跟第三方库比源码时，同一文件里姊妹函数有防御而当前这个没有，这种不一致本身就是强证据。

## 液态玻璃：`patchedLens()` 的 clamp 只挡住越界读取，没挡住"合法范围内的花屏"

**现象**（2026-08-12）：设置面板和播放页控制栏在**浅色背景 + 真实粗体黑字**下复现同样的彩虹花屏，
而补丁明明打过了。
**根因**：clamp 只解决"坐标被推到缓冲区外读到未定义数据"这一种故障。色散还有第二条致病路径：
只要偏移量 `d * grad * dispersionIntensity` 相对元素尺寸足够大，红/蓝通道往相反方向偏移后，
**即使坐标都在合法范围内**，也可能分别读到背后两块视觉上毫不相关的区域（一个读到粗体"骨"字、
另一个读到旁边空白），拼出来的颜色分离本身就是花屏。
**怎么查的**：A/B 关掉色散→消失，排除新代码。真正的意外是顺手回归了这轮**完全没碰过**的播放页，
控制栏在浅色背景下同样复现——这才推翻上一轮"补丁已验证修好"的结论：
上次验证用浅灰 E0E0E0 + 干净文档，恰好没触发足够剧烈的颜色分离，是测试条件不够刁钻。
**修法**：这两处各自 `.copy(chromaticAberration = false)`，跟大纲抽屉当年一致。
**教训**：
1. 一个 bug 有多种触发路径时，用一种路径验证过的修法，换条路径可能照样复现。
   "补丁生效"的测试条件必须刁钻，不能拿第一个凑手的场景验一下就下定论。
2. **色散的安全边界不是"元素多窄多小"，而是"背后内容有多高对比度"**。
   以后新加的 `tunedGlass()` 调用点，只要背后可能是真实文字/图像，色散就该默认关掉——
   `TunedGlassParams` 里 `chromaticAberration=true` 这个基线是针对纯色/渐变卡片调的，
   从没针对"透真实内容"验证过。

## GlassParams.tint：第一遍 drawRect 没吃 alpha，`tint.enabled` 一开就摘不掉

**现象**：透明度滑块调到 100% 不是纯白，调回 0% 没反应，面板卡在半透明雾蒙蒙状态。
**根因**：`GlassEffects.kt` 的 `combinedOnDrawSurface` 里两条 drawRect：
```kotlin
drawRect(c, blendMode = BlendMode.Hue)   // 没传 alpha，用 c 自己的（通常 0xFF），没被 tint.alpha 调制
drawRect(c.copy(alpha = tint.alpha))     // 只有这条真按 tint.alpha 缩放
```
第一条只要 `tint.enabled == true` 就以**固定满强度**画一次；而 `tint.enabled = alpha > 0f`，
所以只要不精确停在 0，这条去饱和就甩不掉。100% 时第二条虽满，但 Hue 混合是"去饱和"不是"覆盖"，
单独糊不出纯白。
**为什么一直没人发现**：`grep` 全项目，在此之前 `tint.enabled` 在所有真实调用点永远是 `false`——
这条分支从写出来那天起就没在真机上跑过。
**修法**：第一条也传 `alpha = tint.alpha`，两条同步缩放。
**教训**：
1. **一个字段/分支只要没被真实数据触发过，就跟没写过一样不可信。** `GlassParams` 里
   `highlight`/`shadow`/`innerShadow`/`tint` 当时全是 `enabled=false`，接新用例前都要当未验证代码看。
2. "有底噪、跟滑块位置脱钩"这种现象，往"某个效果没被参数调制、被写死成固定强度"查；
   跟"该更新的没更新"（状态回写延迟）是两类。

## `Color.copy(alpha=X)` 是覆盖 alpha 通道，不是相对缩放

**现象**：给 `LiquidCompactButton` 传 `inactiveTint = Color.Transparent` 想融进背景，
真机上却是明显的黑色圆角块（编辑页顶栏几个按钮周围一圈黑影）。
**根因**：组件内部写死 `inactiveTint.copy(alpha = 0.5f)`。`Color.Transparent` 是 `Color(0,0,0,0)`
（RGB 是黑的，只是 alpha=0），`.copy(alpha=0.5f)` 变成黑色 50% 不透明。
**修法**：`inactiveTint.copy(alpha = inactiveTint.alpha * 0.5f)`，相对缩放。默认值（alpha=1）效果不变，
`Color.Transparent` 缩放后仍是 0。
**教训**：任何"外部传颜色进来、内部再 `.copy(alpha=...)`"的写法，先想清楚要覆盖还是相对缩放。

## `Modifier.blur()` 叠在 `layerBackdrop()` 捕获节点上，会糊出节点自己的边界

**现象**：文档库背景图片"模糊度"滑块调高，整个页面（列表文字、图标）都被影响，不只是玻璃控件的折射。
**根因**（推断，无库源码）：`layerBackdrop` 和 `Modifier.blur()` 各自往同一节点叠
`graphicsLayer`/`RenderEffect`。项目里其它模糊都走 `com.kashif_e.backdrop.effects.blur()`（AGSL 路径），
这是第一次把 Android 原生 `Modifier.blur()` 叠在捕获源节点上。
**修法**：背景图渲染拆两层——`layerBackdrop` 只标在渲染"原始未处理图片"那层（玻璃控件真正采样的对象），
模糊/亮度/饱和度画在另一个**不带** `layerBackdrop` 的图层上，完全覆盖在第一层上面。

## `layerBackdrop()` 捕获的是所在节点"子树最终合成结果"，打在外层 Box 上会连带捕获兄弟内容

**现象**：上一条的第一版修法——把 `layerBackdrop` 挪到包住"原图 + 滤镜图"两者的外层 `Box`，
指望只捕获最下面那份原图——真机后问题依旧，顶栏和面板的折射看到的还是模糊变色后的版本，
跟滑块数值精确对应。
**根因**：`layerBackdrop` 打在哪个节点，捕获的就是**那个节点整棵子树的最终渲染结果**，
不是"它画的第一笔"。后画的滤镜层也在这棵子树里，一起被捕获。
**修法**：`layerBackdrop` 必须直接打在"原图" `Image` 自己的 Modifier 链上；滤镜层作为**不带**标记的
兄弟节点画在后面（z-order 更上，用户只看得到它）。`LibraryBackgroundLayer` 因此从"接收拼好的 Modifier"
改成"接收 `backdrop: LayerBackdrop`，函数内部精确控制哪个节点带标记"。
**教训**：第一版修法只凭代码结构推理就报告"已修"，真机截图一眼推翻。
**"捕获点该打在哪个具体节点上"本身就是最容易错的地方，改完必须真机截图验证。**

## `tunedGlass()`/`drawBackdrop()` 只在"手势进行中"重绘，静止后冻结在最后一帧

**现象**：透明度滑块数字标签正确变化（16%→97%→100%），面板玻璃画面几乎不变。
截图逐像素比对：0% 和 100% 时同一位置像素**逐字节相同**，不是不明显，是压根没重绘。
**怎么查的**（真机日志实测）：在 `combinedOnDrawSurface`（真正画 tint 处）和
`TeleprompterSettingsWindow`（读 opacity 处）各打一行 `Log.d` 对照时间戳：
重组每次都正确触发、数值一直是新的；但"实际画 tint"只在手势进行中连续触发，
一旦手势/动画静止就再也不出现。佐证：拖**另一个**滑块也会让 tint 用最新透明度重绘一次——
说明重绘触发跟"任意手势正在进行"绑定，不跟它依赖的 state 绑定。
**根因（已确认，非库的问题）**：是我们自己把 state 在**组合阶段**拍扁成了普通值。
**修法**：`tunedGlass()` 改成 `@Composable` + `rememberUpdatedState`，绘制闭包内部读 `live*.value`，
让读取真正发生在**绘制阶段**，Compose 快照系统才有东西可观察。见 `DECISIONS.md` 2026-08-15 条目。
**教训**：先怀疑第三方库（alpha 阶段）很自然，但根因在自己代码里。
"读 state 的时机在组合阶段还是绘制阶段"决定了它会不会被追踪。

## `StateFlow.value` 在 Composable 里直接读，Compose 不会追踪它

**现象**：调完 Tint 颜色/透明度，点预设列表"默认"行的"应用"，画面跳回旧值（用户录屏证实，反复复现）。
**根因**：`builtInPreset(targets)` 内部直接写 `glassTuningStoreFlow.value.paramsFor(it)`——
`StateFlow.value` 是普通属性读取，不是 `collectAsState()`/`State.value`，快照系统看不见。
而调用它的 `PresetSection` 自己也没 `collectAsState`，它能重组全靠父级带下来；
但 Compose 的智能跳过发现 `PresetSection(targets)` 唯一参数没变，很可能直接跳过函数体。
两层叠加，这一行的值冻结在某次早期快照上。
**修法**：`PresetSection` 自己 `val tuningStore by glassTuningStoreFlow.collectAsState()`，
把真正被追踪的值显式传给 `allPresetsFor(store, targets)`，函数内部只用参数，不再自己读 `.value`。
**教训**：**"看起来会重组"跟"确实会重组"不是一回事**——父级重组不保证子级函数体重新执行，
尤其子级参数没变时。任何"预设/缓存/快照"类读取，检查它是不是发生在被追踪的范围内。

## 预设列表是不分入口的扁平存储，新增第二个 restricted 入口时互相串扰

（写代码时主动想到、抢在真机复现前改掉的。）
**根因**：`glassPresetStoreFlow` 是**一份全局扁平列表**，不区分预设从哪个 `restrictedIntent` 入口保存。
只有提词器一个入口时天然没问题；一旦启动页/外观也变成入口，`allPresetsFor` 原实现"内置行 + 全部用户预设"
不看 `targets`，提词器存的预设会出现在启动页列表里，点"应用"还**静默什么都不做**
（`applyGlassPreset` 内部 `preset.paramsByTarget[target.name] ?: return@forEach`，不报错不提示）。
**修法**：`allPresetsFor` 加过滤
`presets.filter { it.paramsByTarget.keys == targets.map{it.name}.toSet() }`——
每个预设的 key 集合本来就等于保存那一刻的 targets，天然能按入口分开，不用额外加标记字段。
**教训**：凡是"共享底层存储、靠调用方传 scope 限定可见范围"的模式，新增 scope 前主动检查
"读取方有没有隐含假设'反正只有一个调用方'"。单入口时代看不出问题，入口一多立刻炸，
而且这种炸法（静默不生效）比编译错误更难发现。

---

## 虚拟定位：早期 Capacitor 实现在 Android 14/15 反复崩溃，重写成原生 Service

**根因**：每次崩溃都能追到前台服务相关规则里的某一条，Capacitor 那层没法精确控制时序/权限检查。
**改写成原生 Service 后逐条防住的规则**：
1. `startForeground()` 必须在 Service 创建后约 5 秒内调用，Android 14+ 严格执行，超时抛
   `ForegroundServiceDidNotStartInTimeException`，**会崩掉整个进程**。所以这行放在 `onCreate()` 最前面，
   早于任何可能抛异常的 `LocationManager`/测试 provider 设置。
2. 调 `addTestProvider`/`setTestProviderLocation` 前必须已拿到 `ACCESS_FINE_LOCATION`，
   否则必现 `SecurityException`。Service 在 `onStartCommand` 里自己又查一遍，不信任调用方。
3. 注入失败的每条路径都 `try/catch` + `stopSelf()` 收场，不把进程带崩。
**教训**：涉及前台服务的时序类系统限制（尤其 API 34+ 新规），出问题时大概率不是业务逻辑错，
而是生命周期踩了系统红线——先想"是不是有个我没意识到的系统时限/权限检查点"。

## 提词器：单个富文本 `TextField` 超两万字符会崩，改成按章节独立编辑器

**约束**：单个富文本编辑状态超约两万字符会崩（具体崩法未留记录，结论已确认）。
**修法**：文档按章节拆，每章节独立编辑器状态。**关键**：`ChapterItem` 在编辑态/展示态之间必须
**像素级一致**，这样拆分对用户完全不可见，不会因焦点切换/滚动导致内容在"编辑器"和"静态文本"之间跳动。
**教训**：这类"超过某规模就崩"的约束，办法不是想办法撑大组件，而是从架构上拆成碰不到上限的小单元
——同时让拆分本身对用户不可见。

## 提词器：WPS 导出的 Word，`pStyle` 是数字 ID，标题判定要靠 `outlineLvl`

**根因**：Word 导出的 `w:pStyle` 引用具名样式（`"Heading1"`），可以按名字识别；
WPS 导出的是纯数字 ID（`"2"`），必须解析 `styles.xml` 里该 ID 的定义才知道是不是标题、第几级。
**修法**：判定优先级——①段落自带的 `<w:outlineLvl>`；②沿 `w:basedOn` 继承链递归解析 `pStyle` 的
`outlineLvl`；③具名样式名字匹配只作最后兜底，**不作主判据**。
**教训**：解析别的软件生成的 Office 格式，不能只按最常见生产者的习惯写。同一个 OOXML 字段，
不同生产者填的值语义可能完全不同——优先信更底层、更不受生产者差异影响的字段。

## `ACCESS_MOCK_LOCATION` 不声明，开发者选项里就找不到这个 app

**根因**：这权限从 API 23 起已不是真正生效的运行时权限，但系统靠扫描"谁声明了它"来填充
"开发者选项 → 选择模拟位置信息应用"这个列表。不声明就不在候选名单里，
其它三个前提（`ACCESS_FINE_LOCATION`、前台服务时序、`LocationManager` 调用）做得再对也没用。
**修法**：清单声明
`<uses-permission android:name="android.permission.ACCESS_MOCK_LOCATION" tools:ignore="MockLocation,ProtectedPermissions" />`。
**教训**：Android 里有一类"名义上的运行时权限"，只在系统扫描/UI 展示阶段起作用。
遇到"前提都满足但系统入口就是不出现"，要想到缺了这类纯注册资格的声明。

## Photo Picker（`ACTION_PICK_IMAGES`）vs `ACTION_GET_CONTENT`

**根因**：`fileChooserParams.createIntent()` 默认走 `ACTION_GET_CONTENT` 的 Photo Picker 兼容 shim
（`PhotopickerGetContentActivity`），功能阉割——不支持 HEIC、排序不同、缺项。
**修法**：API 33+ 直接 `Intent(MediaStore.ACTION_PICK_IMAGES)`（多选带 `EXTRA_PICK_IMAGES_MAX`），
更低版本才退回 `ACTION_OPEN_DOCUMENT` + 显式 MIME + 多选标记，**不用** `ACTION_GET_CONTENT`。
**教训**：`onShowFileChooser` 的 `createIntent()` 只是能用，不是最好用。系统给新用例设计了专门入口时，
走默认兼容路径可能悄悄阉割功能。

## OSM 瓦片国内不通，换高德瓦片 + 坐标纠偏插件

**根因**：OSM 官方瓦片国内不可达。高德瓦片可达，但坐标系是 GCJ02，跟地图交互全程用的 WGS84 不一致，
直接叠加会偏移。
**修法**：瓦片源换 `webrd0{s}.is.autonavi.com`（**必须 https**，Android 9+ 默认禁明文），
配 `Leaflet.InternetMapCorrection` 插件 `corrdType: 'gcj02'`——它**只重算每张瓦片的绘制位置**，
`setView`/marker/点击事件坐标全程仍是 WGS84，业务逻辑不用来回转换。
**教训**：优先选"只修正瓦片绘制位置、业务坐标不变"的纠偏方案，而不是把整个地图 CRS 改掉——
后者会让所有碰坐标的业务代码都要考虑转换，长期维护成本高得多。

---

## LiquidSlider 拖动数字乱跳后弹回 —— 错怪了手势消费，真因是状态回写链路太重

**现象**（2026-08-13）：设置面板 7 个滑块拖动时数字跳几下弹回原值，把手基本不动；
同一个 `LiquidSlider` 在播放页速度滑块上完全正常。
**错误假设**：以为父级 `verticalScroll` 在 NestedScroll pre-scroll 阶段抢走了增量。
据此做了两轮 consume() 修复，编译、装机、真机测试，**问题依旧**。
**怎么查出真因的**：在 `onDragStarted`/`onDrag`/`onDragCancel`/`onDragEnd`/外部值回同步各打一行日志，
用户真机连拖 11 次后 `adb logcat` 回看。结果：**`onDragCancel` 一次都没触发，11 次全是正常
`onDragEnd`**——手势从没被抢走，两轮 consume 方向从一开始就错。真正反常的是：
同一次拖拽内每次 `onDrag` 打印的 `targetValue` **全是同一个值**，直到松手用这个冻结的旧值覆盖整段进度。
**根因**：`onDrag` 算增量用的 `targetValue` 只有 `updateValue()` 被调用才前进，
而 `updateValue()` 只在组件内部一个 `LaunchedEffect` 监听调用方 `value()` 变化时触发。
播放页滑块读写纯本地 `remember{mutableStateOf}`，同帧可见；设置面板滑块的 `onValueChange` 却要走
`vm.updateSettings` → 整个 `TeleprompterLibrary`（含全部章节）作为新对象塞进 StateFlow →
`collectAsState()` 重组 → 一路传参回 `LiquidSlider`——这条链路往返延迟远长于 `onDrag` 间隔（约 10~100ms）。
**修法**：`onDrag` 里直接同步调 `updateValue(newValue)`，视觉立刻跟手，不依赖回写往返；
`onValueChange` 改成独立的、节流约 100ms 的旁路（`pendingPushValue` + 常驻 ticker），松手补发最终值；
加 `isDragging` 让"外部值变化时回同步"在拖拽期间跳过，避免迟到的旧值把拖拽弹回去。
改动全收在 `LiquidSlider.kt` 内部，8 个调用方自动受益。两轮 consume 相关代码已撤销。
**教训**：
1. "手势被抢走"和"状态回写跟不上"症状几乎一样。**"代码改了、装上了、现象没变"本身就是强信号：
   换假设，而不是在同一假设里加码。**
2. 这类"手感"bug 靠关键节点打日志 + 真手指连续操作 + 事后看时间线，比 adb 模拟或代码审查快得多。
3. **把"跟手的视觉"和"通知调用方"拆成两条独立速度的通道**（本地立即 + 节流异步）是通用解法。
   播放页滑块没中招纯属它凑巧绑了个轻量本地状态，不是设计上没这个坑。

## 提词器正文滑块卡顿：节流不是旋钮，减少每次的工作量才是

**现象**：拖"正文默认字号/整体缩放/行距/内容宽度/副标题字号"明显掉帧，
而同一面板的"滚动速度/透明度/模糊度"一直很跟手。
**根因**：编辑页正文是普通 `Column` + `verticalScroll`，**不是 LazyColumn**，全部章节永远同时组合布局。
这几个参数一变会触发全部在场章节一起重排，单章重排 80-120ms（量过）。
`LiquidSlider` 的视觉位置走 `DampedDragAnimation` 每帧更新（从不卡），
但下发 `onValueChange` 走 `pendingPushValue` 节流侧通道，间隔是全局硬编码 200ms——
对"只重画自己"的滑块够用，对牵连全文重排的不够。

**第一次修复（错的，已撤回）**：给这 6 个滑块传 `pushThrottleMs = 2000L`。用户真机反馈
**"拖动过程中正文完全不动，只有松手才跳一下"**，明确纠正"应该随时跟着动，频率越高越对"。
教训：这个节流本质是"每隔 N 毫秒采样一次最新值"，**不是平滑消抖**。拖动通常 1-2 秒完成，
一旦节流间隔跟拖动总时长同量级，采样窗口可能一次都不落在拖动中，观感就是"冻结-跳变"，
跟"卡顿但跟手"是两种完全不同的体验。用户明确要后者：**宁可掉帧也要跟手**。

**第二次修复（视口裁剪）**：不动"多久推一次"（维持 200ms），改"每次推送重排哪些章节"——
拖动这 5 个滑块期间，只有当前视口内（上下各留一屏缓冲）的章节用最新值实时重排，
视口外冻结在拖动开始那一刻，松手一次性补齐。实现要点：
- `bodyDisplayDragActive` 必须声明在章节循环和 `TeleprompterSettingsWindow` 的**共同外层作用域**
  （两者是兄弟节点不是父子）。
- `TeleprompterSettingsWindow` 加 `onBodyDragStart`/`onBodyDragEnd` 可选回调（默认空实现）。
- `frozenBodyDisplay = remember(bodyDisplayDragActive){ if(...) Triple(...) else null }`——
  利用 `remember` 的 key 机制在变 true 那一刻捕获一次，天然冻结到松手，不用手写 `LaunchedEffect` 同步。
- 视口判定复用已有 `chapterHeights`（`onGloballyPositioned` 累积）+ `scrollState.value` +
  新增 `viewportHeightPx`（`onSizeChanged`），范围
  `[scrollState.value - viewportHeightPx, scrollState.value + viewportHeightPx*2]`。
- **正在编辑（`isFocused`）的章节永远用 live 值**，不参与冻结——WYSIWYG 不能冻结。
- `contentWidthFraction` 没接入：它是用一个共享外层 `.fillMaxWidth(f)` 包住整个列表，
  没法只给部分章节应用新宽度。
**教训**：遇到"响应要及时"和"处理要便宜"的矛盾，不要在"多久处理一次"上反复横跳，
去看"**每次处理的工作量能不能本身更小**"。这次可行是因为可见章节数有上限而总章节数没有。
"总量不可控、可见范围可控"的场景（长列表/长文档/大表格）大概率都能套这个思路。

**最终状态**：用户验收为"能用，不再深究"，本项停止投入。

## 拖选中光标手柄不跟手、乱跳 —— 防抖误判引发的重组风暴

**现象**：长按选中后拖选区手柄，手柄不跟手，隔一会儿突然跳。
**怎么查的**（第三方库内部够不到，改用两路证据交叉）：
1. 在 `state.selection` 上打日志，显示选区数值本身**平滑递增**
   （`end=159→160→162→166→...→216`），没乱跳——数据和手势采样都是对的。
2. 真机录屏逐帧比对（`ffmpeg` 不可用，改用 Python + OpenCV `cv2.VideoCapture` 按 0.4s 抽帧）：
   11.0s 和 12.2s 两帧里高亮范围确实在扩大，但手柄圆圈**1.2 秒内一个像素没挪**——
   坐实"数据在更新、视觉没跟上"。
3. 回看同一份日志发现：几乎每次 "selection changed" 后都紧跟 "debounce armed"，
   800ms 后又 "onChapterContentChanged fired"——**纯拖手柄改选区（textLen 全程没变）也在不断触发
   "这是一次真实编辑"**。
**根因**：`LaunchedEffect(state.annotatedString)`（打字防抖计时用）把整个对象当 key，
而 `state.annotatedString` 在**纯移动选区/光标、完全没编辑文字**时也会被判定为"变了"。
于是每拖一次就误判成真实编辑，800ms 后执行完整 `state.toHtml()` + `vm.onChapterContentChanged`，
后者整个替换 `TeleprompterLibrary` StateFlow，引发大范围重组，跟手柄自己的重绘抢帧。
**修法**：key 从 `state.annotatedString` 改成 `state.annotatedString.text`（只有真插入/删除才变），
内部"是否等于刚 hydrate 那份快照"的判断也改成文本比较。
代价：纯样式改动（选中点粗体、一个字没打）不再被打字防抖实时捕获，
但失焦兜底（`onFocusChanged(false)`）仍比较完整对象，切走时会补存，不会丢。

**插曲：诊断日志自己造成了新 bug。** 修完后用户反馈"拖几个字就拖不动了"——
为诊断加的 `LaunchedEffect(state.selection)` 每次变化就调 `TeleprompterObserveLog.log()`，
而它内部 `appendText` 是**同步磁盘写入、没切 IO 线程**，跑在 Compose 主线程 dispatcher 上。
一次拖动手势内选区高频变化 = 每次都在主线程同步 I/O，直接打断安卓底层的触摸手势识别。
删掉该日志块解决。**教训：诊断日志不是免费的。**要在拖动/滑动这类高频状态上加日志前，
先确认它自身开销（尤其有没有同步 I/O）。

**最终定位：根因很可能在库本身。** 删日志后症状变得明确：
**"我往后拖他往前跳，往前拖他往后跳，有时还随机跳"**——是方向反了，不是滞后。
结合已确认的"数据层正确、渲染层滞后"，而渲染层是库私有实现，判断继续在应用层修补是无用功。
升级 `richeditor-compose` `1.0.0-rc14` → 正式版 `1.0.0`，其 release notes 有两条直接对应：
`prevent drag selection from jumping to the next line at line edges`（#748，跟用户描述几乎逐字对应）、
`diff-based edit pipeline, selection stability, parser round-trips`（#746）。
零源码改动，编译直接通过（1.0.0 唯一 breaking change 是移除 iosX64，本项目只有 Android）。
**升级后的首要验收不是手柄，是老文档没跑样**——1.0.0 有两条会改变 HTML 导入行为的修复
（`drop white/transparent backgrounds on HTML import` #770、
`resolve relative text-indent units to sp on HTML import` #724），
外加 `combine nested text decorations instead of replacing it`（#767）改嵌套样式合并，
而本项目文档正是 Word→OOXML→HTML 导入、带两端对齐和逐 run 字号的。
**教训**：第三方库内部够不到时，把"这层能看到的数据"和"用户能看到的现象"分别记录、交叉比对，
往往能把"库的 bug"和"我们自己引发的连锁反应"区分开——这次表面像库的手柄 bug，
真实根因完全在自己的防抖逻辑里。真机录屏在"数值对、视觉不对"这类 bug 上不可替代。

## `LaunchedEffect` 用调用方传进来的 lambda 当 key —— 液态开关拖动被拽回去

**现象**：`LiquidToggle`（"加粗"开关）拖到一半自己弹回起点。
**根因**（两个，第一个是主因）：
1. 调用点写 `selected = { subtitleStyle.bold }`——一个捕获了 `subtitleStyle` 的 lambda，
   对象一被替换 Compose 就给出**新的 lambda 实例**。而这个面板替换极频繁（正上方就是副标题字号滑块，
   每次节流推送都整体替换 style 对象）。key 一变 `LaunchedEffect` 重启，
   重启后 `snapshotFlow` **一定会立刻重新首发一次**——于是读到还没变的 `selected()`（target=0f），
   看见 `fraction` 停在 0.4，就 `animateToValue(0f)`，在用户手指底下把滑块弹回起点。
2. 没有 `isDragging` 保护。
**怎么查的**：先确认 `onSelect` **已经**只在 `onDragStopped` 调一次，所以问题不在提交时机，
而在"有别的东西在拖动过程中改滑块位置"。看同文件紧挨着的两个 effect：一个用
`dampedDragAnimation`（稳定）、另一个用 `selected`（lambda，不稳定）——一眼就对不上。
再对照兄弟组件 `LiquidSlider.kt`：它正是 `LaunchedEffect(dampedDragAnimation)` + `isDragging` 双保护，
文件头还记着它当年踩过的同一类坑。**同一个坑在滑块上修过、在开关上从没修过，这个不一致就是最强线索。**
**修法**：key 换成 `dampedDragAnimation`，补 `isDragging`（`onDragStarted` 置真、
`onDragStopped` 在 `onSelect` **之后**置假），外部同步分支加 `if (isDragging) return@collectLatest`。
**教训**：
1. **`LaunchedEffect`/`remember` 的 key 绝对不要用调用方传进来的 lambda**（`() -> T` 尤其危险）。
   lambda 的实例身份取决于它捕获了什么、外层重组了几次，跟"我关心的值变没变"是两回事；
   而"重启 + snapshotFlow 重新首发"会把重启直接变成**一次对状态的实际写入**。
   要么用稳定对象当 key，要么用 `Unit` 让 `snapshotFlow` 自己负责监听。
   跟上一条「拿整个 `annotatedString` 当 key」是同一类错误的两个变种：
   **key 的变化粒度必须等于"我关心的事情变了"，不能更粗也不能更细。**
2. 一个组件出问题，先看它的兄弟组件怎么写的。`grep` 两个文件里 `LaunchedEffect(` 的 key
   比读一遍渲染逻辑快得多。

---

## 单 Activity 内用布尔状态切屏，不接 `BackHandler` 会直接退出整个 Activity

**现象**：电话拦截主屏用 `showRules`/`showLog` 两个布尔控制整屏切换，
在规则管理页按系统返回键**直接退出整个 Activity 回到启动页**，而不是退回主屏。
**根因**：布尔值不是 `NavController`/`Fragment` 那种系统认识的导航栈条目，
没有任何东西告诉系统"现在逻辑上在子页面"。**隐蔽之处**：应用内自己画的返回箭头完全正常
（那是显式 `onClick`），只有系统返回键/手势这条路径会露馅——
习惯性只点应用内按钮就完全测不出来。
**修法**：`BackHandler(enabled = showLog || showRules) { showLog = false; showRules = false }`。
只在子屏打开时启用，回主屏后自动禁用，返回键交还 Activity 默认行为。
**教训**：项目里"用布尔/枚举在同一 Activity 内切整屏"已是主流模式，
**新增子屏幕的同一次改动里就必须顺手加 `BackHandler`**。自查方法：
`grep -rn "mutableStateOf(false)"` 找 `if (xxx) { ...; return }` 这种整屏切换，
配合 `grep -rn "BackHandler"` 交叉检查。

---

## XML 注释里不能有连续两个减号 `--`，AGP 只报一句没有行号的 "Error parsing"

**现象**：`ManifestMerger2$MergeFailureException: Error parsing ...AndroidManifest.xml`，
**没有行号、没有列号**。
**根因**：写了 `<!-- ---- 虚拟定位 ---- -->`。XML 规范里注释内容**不允许连续两个减号**。
注意 `<!-- ==== X ==== -->`（等号）合法，中文破折号 `——`（U+2014）也合法，只有 ASCII 减号连着两个才炸。
**怎么查的**：别盯着 AGP 那条看，直接拿标准解析器问：
```bash
python -c "import xml.dom.minidom; xml.dom.minidom.parse(r'app/src/pub/AndroidManifest.xml')"
# PARSE ERROR: not well-formed (invalid token): line 47, column 15
```
一秒拿到精确行列号。
**教训**：构建工具链对第三方文件的报错质量普遍很差。凡是报"XML 解析失败"却不给位置的，
绕过它、用专门的解析器去问。**画分隔线用 `=` 不要用 `-`。**

## 想让 git 历史"干净"，删文件必须在 `git init` 之前

**现象**：公开版刻意不带私人版 `.git`，就是为了历史里不存在被删功能的源码。
建仓时也确实先删源码再 `git init`。但验证 bundle 时顺手一查，历史里**仍躺着三个文件**：
`mockloc/MockLocationCoordTransformTest.kt`、`shizuku/CommandResult.aidl`、`IUserService.aidl`。
**根因**：删除分了两批，中间隔着 `git init`。第一批（主体源码）建仓前删的，干净；
第二批是**编译报错后才发现要删的**——单元测试跑测试才暴露，AIDL 是 javac 报"找不到 CommandResult"才暴露。
这两批之间已经 `git init` + 首次提交了。
**怎么查的**：不是肉眼 review，是**验证备份时顺手做的反向检查**
`git log --all --oneline -- "**/*mockloc*"`，发现出现 2 次而预期 0 次。
**只验证"bundle 能不能 clone 出来"永远发现不了。**
**修法**（仓库很新、无远程）：
```bash
git checkout --orphan clean-main && git add -A && git commit -m "..."
git branch -D master && git branch -m master
git gc --prune=now          # 否则悬空对象可能仍被打进 bundle
```
**教训**：
1. "物理删除 + 干净历史"这类需求，**先把项目完整编译通过（含测试），确认再没有要删的，最后才
   `git init`**——因为"一次性删干净"实际做不到，编译器要分好几轮才把依赖暴露完。
2. **备份验证不能只验"能不能还原"，要验"还原出来的东西对不对"。** 同理，验 APK 不能只看能不能装，
   要解包核对。

## 单向同步脚本会把"已经删掉的功能"原样搬回去

**现象**：`sync-to-public.sh` **第一次 dry-run** 就发现它准备复制
`assets/web/mock-location.html`——公开版刚物理删掉的虚拟定位选点页。直接 `--apply` 就装回去了。
**根因**：脚本按**目录**同步，而"已删除的文件"恰好躺在同步目录里。
同步逻辑只判断"两边是否不同"，而**"一边故意删了"和"一边改了另一边没跟上"长得一模一样**。
**修法**：`NEVER_COPY` 不能只放"两边故意不同的文件"，还必须放**"已删除但位于同步目录内的文件"**。
规则写死在脚本头部和两边 CLAUDE.md：**以后从公开版删任何功能，只要文件在 `SYNC_DIRS` 内，
必须同时补进 `NEVER_COPY`**。
**教训**：
1. **任何同步/复制脚本第一版必须先 dry-run。** 这次就是 dry-run 救的。
2. **"删除"在同步系统里是很难表达的状态**——它表现为"缺失"，跟"还没同步过来"无法区分。
   必须用显式排除清单记录删除意图。同类场景：`rsync --delete`、构建产物清理、配置下发。

## Windows 路径在 Git Bash 里：`git bundle create` 认，`git clone` 不认

**现象**：`git bundle create "F:\backup\x.bundle" --all` 成功；
`git clone "F:\backup\x.bundle" dst` 报 `fatal: Could not read from remote repository.`
**根因**：`git clone` 把参数当 **remote URL** 解析，`F:` 和反斜杠被当成协议/主机；
`git bundle create` 走的是普通文件路径。另外 `git bundle verify` **必须在一个 git 仓库目录内运行**，
在空目录跑会报 `need a repository to verify a bundle`，同样容易误读成 bundle 坏了。
**修法**：Git Bash 里一律用 POSIX 路径做 clone 源：`git clone "/f/backup/x.bundle" dst`。
验 bundle 最可靠的方式不是 `verify`，是**直接 clone 一份出来看内容**。
**教训**：报错方向（"仓库不存在/没权限"）跟真因（路径格式）完全不搭。
"命令 A 能用、命令 B 不能用"时，先怀疑**两个命令对参数的解析方式不同**，别先怀疑数据坏了。

## Git Bash 会改写 adb 的远端绝对路径

**现象**（2026-09-06）：`adb pull /storage/emulated/0/Android/data/.../gkd.db` 报
`failed to stat remote object 'F:/Git/storage/emulated/0/...'`。
**根因**：MSYS 路径转换把以 `/` 开头的参数当 Windows 路径翻译了，**设备上的路径根本没被请求过**。
报错里那个被改写后的路径（`F:/Git/` 前缀）就是最好的线索。
**修法**：用 PowerShell 调 adb，或 `MSYS_NO_PATHCONV=1 adb pull ...`。
**相关**：PowerShell 5.1 反过来在读 UTF-8 JSON、输出中文时会乱码（`ConvertFrom-Json` 撞上非 ASCII 直接抛错），
那种场景用 `PYTHONIOENCODING=utf-8 python`。
**两个 shell 各有各的坑，按任务选：拉设备文件用 PowerShell，处理 UTF-8 文本用 bash/python。**

## Python `re.sub` 的替换串里，`\n` 会被再解释一次

**根因**：`re.sub(pattern, repl, s)` 的 `repl` **不是普通字符串**，会再经一层反斜杠转义处理
（为支持 `\1`、`\g<name>`）。Python 源码里的 `"\n"` 先被解析成 `\`+`n` 两个字符，
`re.sub` 又把它解释成真换行。`str.replace()` 没有这个问题。
**修法**：优先 `str.replace()`；必须用正则时替换串里反斜杠翻倍（`"\\n"`），或把 `repl` 写成 lambda。
**教训**：这次靠编译器报语法错才发现——**如果改的是注释或字符串内容，编译器不会报错，
就是静默的数据损坏**。凡是脚本批量改源码，改完必须编译/跑测试，不能只看脚本打印了 "ok"。

## 查依赖"最新版本"：搜索索引滞后、metadata 的 `<latest>` 标签直接是错的

**现象**：升级 `richeditor-compose` 时三个来源给出三个答案，**两个是错的**：

- Maven Central 搜索接口 `search.maven.org/solrsearch` → `1.0.0-rc13`：索引严重滞后，
  **连当时正在用、能正常下载的 rc14 都没有**。
- `repo1.maven.org/.../maven-metadata.xml` 的 `<latest>`/`<release>` → `1.0.0-dev01`：
  实际发布于 **2024-04-24，两年前的 dev 构建**，只因版本字符串排序排在最后。
  照它升级会把项目**降级到两年前的 dev 快照**，而且版本号长得像"更新的 1.0.0 系列"，极其隐蔽。
- 逐个请求 `.pom` 读 `Last-Modified` → `1.0.0`（2026-07-20）：✅ 正确。

**正确做法**：①从 `maven-metadata.xml` 拿**完整 `<versions>` 列表**（这份是可信的），
**但忽略 `<latest>`/`<release>`**；②对候选版本逐个 `curl -sI .../<v>/<artifact>-<v>.pom`
读 `Last-Modified` 拿真实发布时间；③结合 GitHub release notes 判断——这一步还能识别"变体构建"，
这次就靠它发现日期最新的 `1.0.0-cmp-1.12.0-beta03` 其实是给 Compose Multiplatform 1.12 的专用变体
（本项目用 Compose 1.11.2，不能用）。
**教训**：**以"发布时间"而不是"版本字符串排序"作判据。** 版本号一旦混入 dev/rc/变体后缀，
字符串序就跟时间序脱钩了，而生成 `<latest>` 的是机器，它只会按字符串排。
连"权威源"也可能给出错误的**摘要字段**，要看原始数据。

## 移除依赖时忘了删清单里的组件声明 —— ContentProvider 会让应用启动即崩

**现象**：公开版移除 Shizuku 依赖，编译通过、出包成功、`aapt2 dump badging` 权限干净，
但清单里 `<provider android:name="rikka.shizuku.ShizukuProvider" .../>` 还留着——**类没了，声明还在**。
**为什么特别致命**：`ContentProvider` 不是"用到才加载"，系统在应用启动阶段就实例化清单里每一个 provider。
类找不到 → `ClassNotFoundException` → **进程直接崩，连第一个界面都到不了**。
换成 Activity/Service 只是"点了没反应"，provider 是"整个应用打不开"。
**为什么没早发现**：**全程不报错**——Gradle 不检查清单里的类是否存在（清单合并和代码编译是两条独立的路），
`assembleDebug` 一路绿灯，装机也能装，只有真正启动那一刻才炸，而当时手机没连。
**怎么查的**：不是靠试运行，是**回答另一个问题时顺带查出来的**——
用户问"两个版本怎么确认能同时装"，为严谨要逐条核对 provider authority 冲突，
把两边全部 `<provider>` 列出来才注意到。再解包 dex 搜 `rikka/shizuku` 确认不在包里。
**修法**：删掉那条声明。更一般地：**移除任何依赖时同步检查清单里有没有指向它的组件声明**。
**做成了自动检查**：`verify-public-apk.sh` 第 3 项遍历最终 APK 清单里每个
activity/service/provider/receiver，逐个确认类真在 dex 里，provider 标为"致命"。
写完做了反向测试（喂它刚删掉的类和编造的类，确认都能判出来），不是空转。
**教训**：
1. **"编译通过 + 能装上"离"能跑"还差很远。** 清单和代码是两条独立的验证路径。
2. 这次是运气好撞见的。**凡是"靠人记得"的把关，都应该在发现它的当天就工具化。**

---

## 数据迁移"日志说迁了、磁盘上没迁" —— `drop(1)` 撞上 `scope.launch` 还没开始收集

**现象**（2026-08-26）：迁移代码在 `TeleprompterStore.kt` 顶层 `val` 初始化块里，
logcat 明明白白打了 `migrated library ... docs=13, schemaVersion=1`，
把库文件 pull 下来一看**还是旧格式，连 `schemaVersion` 键都没有**。
**根因**：`createTextFlow` 的写盘管线是
`scope.launch { stateFlow.drop(1).conflate().debounce(...).collect { 写文件 } }`。
`drop(1)` 本意是跳过初始值，但 `scope.launch` 是**异步**的：顶层 `val` 的 `.also{}` 跑完时
收集协程**还没开始收集**；等它开始时，`MutableStateFlow` 把**当前值**（已是迁移后的）
作为第一个值发过去，正好被 `drop(1)` 丢掉。
**为什么特别容易上当**：内存里的值是**对的**——界面显示、后续读取全部正常，只有磁盘是旧的。
**踩了两次**：第一版想用 `createAnyFlow` 的 `initialize` 钩子，我否掉了它，
理由写在注释里"`initialize` 的返回值是初始值，而写盘协程 `drop(1)`，初始值不落盘"——
这个理由**完全正确**，然后我换成"迁完再给 `flow.value` 赋一次值"，
自以为绕开了 `drop(1)`，其实换个姿势掉进同一个 `drop(1)` 里。
**同一个坑，用"我已经知道这个坑了"的心态改，反而更容易再踩一次。**
**怎么查的**：**验收步骤救的**——用户明确要求"把文件从设备拉下来逐篇确认，不许只看日志"。
第一次 pull 就发现文件大小跟迁移前一模一样（104840 字节）。按"日志打了就算过"验收，这 bug 直接进生产。
**修法**：给 `MutableStoreStateFlow` 加显式 `persistNow()`，走跟收集器同一套原子写
（临时文件 + `fd.sync()` + `ATOMIC_MOVE`），迁移后立刻调一次。
**教训**：
1. **"我改了 state" ≠ "它被持久化了"。** 经过异步管线（`launch`/`debounce`/`drop`/`conflate`）的持久化，
   尤其在**进程启动最早期**赋值时，管线多半还没搭好。
2. **日志证明的是"这行代码跑过了"，不是"它想做的事成功了"。** 迁移/写盘/删除这类"效果在别处"的操作，
   验收必须去看那个"别处"。
3. 明知有坑还是踩进去时，先怀疑"我以为绕开的机制"和"实际生效的机制"是不是同一个。

## "默认值"只对文件里没有这个键的数据生效 —— 改默认值改不动老用户

**一轮之内同一个陷阱撞了两次（2026-09-05/06）。**

**实例一：提词器面板模糊度。** 把 `settingsPanelBlurRadiusDp` 默认值从 `0f` 改成满值，
**对已装机设备完全无效**——库文件用 `Json { encodeDefaults = true }`（`util/Singleton.kt`）编码，
旧的 `0.0` 早已写在 `teleprompter_library.json` 里，解码读到了这个键，轮不到数据类默认值。
真正生效靠新增的 schema v3 一次性迁移。

**实例二：GKD 的"自己不匹配自己"。** `AppListString.getDefaultBlockList()` 里确实有 `META.appId`，
看上去自排除是有的。但它只是 `block_match_app_list` 的**默认值**：
`decode = { it?.let(AppListString::decode) ?: getDefaultBlockList() }`——
这个键一旦被持久化过就再也不回退到默认值，自排除静默失效。
修法是改成不可配置的不变量（`checkAppBlockMatch()` 最前面硬判 `appId == META.appId`）。

**怎么一眼认出来**：看字段的持久化方式。
- `encodeDefaults = true` → 所有字段都落盘 → **改默认值永远只影响全新安装**，老数据必须迁移。
- `?: default()` 形式的解码兜底 → **键被写过一次，默认值就永久失效**。

**教训**：改默认值前先问"这个值在用户磁盘上已经有一份了吗"。答案是"有"的时候，改默认值是**装作改了**
——编译通过、代码读起来完全正确、新装的模拟器也对，只有真机老数据不动。
跟"日志说迁了、磁盘没迁"是同一家族：**验收必须拉真机文件看。**

---

## 我自己的测试手法制造出一个不存在的 bug —— adb 连续注入事件的时序竞态

**现象**（2026-08-26）：用 adb "点开关 → `sleep 1` → 拖滑块"验证，测出**行距这一项**开关打开后
拖滑块值写进了全局而不是文档级，而且开关再也关不掉。相邻两项用完全相同的序列都正常。
证据链（落盘 JSON 前后对比）完全自洽。
**实际上代码是对的**：五项写入分支逐字同构，全项目写全局行距只有一处 `else` 分支。
**真正原因**：`adb shell input tap` 和 `input swipe` 是两个独立进程注入的合成事件，
而 `LiquidToggle` 的 `onSelect` 提交时机跟 `DampedDragAnimation` 的手势结束回调绑定。
两条事件流之间**没有"上一次状态变更已走完一轮重组"的保证**：拖动开始时开关的写入可能还没落到
`displayOverrides` 上，滑块那次 `onValueChange` 读到的仍是上一轮组合里 `displayOverrides` 为 null 的闭包，
于是走了 `else` 写全局。种子值停在旧全局值 1.3577929 正是这个时序的指纹。
**怎么翻案的**：做了**隔离变量的对照实验**——应用重启、文档重开、设置面板全新组合、
**前面不点任何开关**，只拖一次滑块 → 写进文档级，全局纹丝不动。再单点开关一次 → 正常关闭、字段清空。
**教训**：
1. **UI 自动化注入的事件序列本身就是一个需要被排除的变量。** 真人两次触摸之间隔着几百毫秒的自然停顿
   和完整帧循环，`adb input` 没有这个保证。测"改了 A 再改 B"这类**跨状态依赖**时，
   中间必须重新取一次真实状态（重开界面/重新组合），**不能靠 `sleep`**。
2. **落盘数据对比是强证据，但它只能证明"发生了什么"，不能证明"为什么"。** 数据是真的，结论是错的。
3. 报 bug 前先问：**这个现象我能在"一次干净的、单一操作的"条件下复现吗？** 复现不了先别报。
   "诊断优先于推测"也包括诊断自己的诊断工具。

## 写死前景色 —— 背景一旦可配置，"白字"就会整屏消失

**现象**（2026-09-02）：启动页背景改成用户可选任意颜色后，用户选了浅色，
标题「癸星」、齿轮、卡片副标题、版本行**全部看不见**。
**根因**：`LauncherActivity` 里 8 处硬编码 `Color.White`。这在"背景恒为深色渐变"的年代没问题——
但那是个**没写下来的前提**，背景变成可配置的那一刻它就失效了，而代码里没有任何地方会因此报错。
**修法**：按背景**感知亮度**选前景基色（`GlassBackgroundConfig.prefersLightForeground()`）。三个细节：
1. 用 Rec.601 感知亮度（`0.299R+0.587G+0.114B`），**不是 RGB 均值**——纯绿和纯蓝均值相同但人眼差得远。
2. 亮度判断必须走**和渲染同一个** `adjustedForGlassBg`，否则"背景被亮度滑块调亮了、前景还按原色判断"。
3. 图片模式判断不了（要解码采样整张图），返回 null 按深色处理保持白字——
   **明确承认判断不了，而不是瞎猜一个值**。
**教训**：**"当前所有取值都满足某个条件"不等于"这个条件成立"。**
凡是"因为现在只可能是 X 所以写死 Y"的地方，在把 X 变成可配置项的那一次改动里必须一起找出 Y——
编译器不会帮你找，测试也不会，**只有真机截图会**。
（同类第二次：启动页卡片有了自己的不透明底色后，前景色仍按**页面**背景推导，
深色背景下白卡片上写白字全部消失。改为按**卡片表面颜色**推导：
`Color.compositeOver` + `prefersLightForegroundOnTop`。）

## PowerShell 5.1 的 `-Encoding utf8` 会写 BOM —— JSON 静默解析失败、回落默认值

**现象**（2026-09-02）：用 `Set-Content -Encoding utf8` 生成配置 JSON 推到设备，应用起来显示**默认背景**。
文件推送成功、`cat` 出来内容也对。
**根因**：PowerShell 5.1 的 `-Encoding utf8` 写的是**带 BOM 的 UTF-8**。
开头三字节 `EF BB BF` 不是合法 JSON，`kotlinx.serialization` 抛异常；
而 `createAnyFlow` 的解码是 `runCatching{...}.getOrNull() ?: default()`——
**解析失败静默回落默认值，不报错、不打日志**。表现成"配置没生效"而不是"配置文件坏了"。
**修法**：`[System.IO.File]::WriteAllText($path, $json, (New-Object System.Text.UTF8Encoding($false)))`。
Git Bash 的 heredoc 本来就不带 BOM。
**教训**：
1. 在 Windows 上生成给非 Windows 程序解析的文本文件，**永远显式指定"无 BOM"**，别信 `-Encoding utf8` 这名字。
2. 更重要：`getOrNull() ?: default()` 这种**静默兜底会把"数据坏了"伪装成"数据没设"**。
   发现"配置像是没读到"，先怀疑解析失败，而不是读取路径——加一行解析失败日志能省掉整轮排查。

---

## 改 applicationId 会让上游/第三方规则里写死包名的自排除全部失配

**现象**（2026-09-06）：进癸星启动页立刻被自动点进"去广告"页。
**这个 bug 以前出过、STATE.md 里还写着"已由用户确认修复"，改包名之后又回来了。**
**根因链**（每一环都取到证据）：
1. 点它的**不是癸星自己的内核**，是另外装的上游原版 GKD（`li.songe.gkd`）——
   `settings get secure enabled_accessibility_services` 显示启用的服务属于它，
   癸星自己的无障碍服务压根没启用。
2. 命中订阅 667「开屏广告-全局」rule key=0：
   `[text*="跳过"][text.length<10][width<500 && height<300][visibleToUser=true]`
3. 启动页「去广告」卡片副标题「自动跳过开屏广告」正好 8 字（<10）、含"跳过"、尺寸也在范围内。
   卡片用 `semantics(mergeDescendants = true)` 合并成一个 `childCount=0` 的叶子节点，
   正好满足这类规则的形状条件。
4. 该组 374 条应用排除清单里有 `li.songe.gkd` 和 `li.songe.gkd.debug`，
   **唯独没有 `li.songe.gkd.taiclear.debug`**。默认订阅同理，写的是 `{id:'li.songe.gkd',enable:false}`。
**教训**：
1. **改 applicationId 等于把自己变成一个陌生的第三方应用。** 所有"按包名字符串写死"的东西都会悄悄失配。
   改包名时要专门搜一遍 `li.songe.gkd` 这类字面量。
2. **自己的应用界面上不要出现"跳过""skip"这类词**，尤其不要出现在会被合并成叶子节点的
   `contentDescription` 里。装了任何广告跳过类应用的机器上，这就是持续的误触发源。

**"谁点了我"这类问题的通用查法**：
```bash
# 1. 先确认是谁在点 —— 不要想当然以为是自家的服务
adb shell settings get secure enabled_accessibility_services
# 2. GKD 的数据库在外部目录，可直接拉（不需要 root，不需要 debuggable）
adb pull /storage/emulated/0/Android/data/li.songe.gkd/files/db/gkd.db
sqlite3 gkd.db "SELECT app_id, COUNT(*) FROM action_log GROUP BY app_id ORDER BY 2 DESC;"
sqlite3 gkd.db "SELECT subs_id,group_key,rule_key,activity_id,COUNT(*) FROM action_log
                WHERE app_id='<你的包名>' GROUP BY 1,2,3,4;"
# 3. 拿 subs_id 去 files/subscription/<id>.json 翻规则原文
```
第 1 步是这次真正的教训：**我先入为主认定是癸星自己的内核在误点，写了一整轮修改才发现服务归属不对。**
"谁在执行动作"必须先查清楚，再谈修哪一边。

## 必读文件过长本身是个 bug —— 674 行的 STATE.md 造成了一次真实的排查弯路

**现象**（2026-09-06）：排查上面那条误点 bug 时走了弯路，先假定是自家内核，做了一轮无效修改。
**根因**：需要的信息**早就写在 STATE.md 里了**——第 334 行写着这个 bug 以前就出过，
第 663 行写着"手机上同时装着两个包名的 GKD"。但那份文件当时 674 行，
而它的定位是"每次会话开始必读"。读了开头没读到 663 行，等于这两条记录不存在。
**根因的根因**：STATE.md 自己的规矩是"只保留现状不堆历史"，
实际累积了 13 个"## 2026-0X-XX 这一轮做了什么"章节。
**每条单独看都像有用的现状，合起来就没人读得完。**
**教训**：
1. **必读文件有效性 = 内容质量 × 被读完的概率。** 第二项在长度超过某阈值后急剧掉到 0，
   于是往里加内容反而让它更没用。这不是文风问题，是会造成真实排查成本的工程问题。
2. **区分"现在的状态"和"当时发生的事"。** 后者属于 PITFALLS/DECISIONS（按需查，可以长）
   或者干脆留给 git 历史。
3. 长度上限要当硬约束执行，不是建议。
