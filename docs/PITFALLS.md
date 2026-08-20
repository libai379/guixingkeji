# 踩过的坑

只追加，不删改。这份文件对项目最重要——下次遇到同类问题，方法比结论有用，所以每条都尽量写清楚
"当时怎么查出来的"，不只是写"改了哪一行"。

格式：现象 / 根因 / 怎么定位出来的 / 修法 / 教训。

补录说明：这批历史坑是从现有代码注释、git log（fork 自己的提交只有两条，大部分历史信息不在提交
记录里，在代码注释里）和本轮会话记录里回填的。凡是我没找到具体复现过程、只能从代码里的防御性设计
反推出"这类问题曾经被考虑过"的条目，会在"怎么定位出来的"里如实标注，不编造没有依据的诊断过程。

---

## Compose 手势/状态冲突类

用户对这个项目的印象是这类问题反复出现过七次，是同一类根因（两套手势/状态机制在同一个节点上抢
同一个输入，而不是好好分工）。下面是能从代码注释和本轮会话记录里坐实、每条都有具体证据的几次；
如果还记得其余几次的具体场景，补充告诉我，我再往这加。

### 1. 长按 3 秒入口，短按就误触发

**现象**：启动页任意卡片，正常单击（不是长按）就直接打开了玻璃调参面板，而不是卡片自己的目的地。

**根因**：最初实现是裸 `pointerInput` 手动检测长按：
`withTimeoutOrNull(3000L) { waitForUpOrCancellation() } == null`。这句话在两种情况下都成立——
真的等了 3 秒超时，**或者** `waitForUpOrCancellation()` 因为同一个节点上的 `.clickable` 提前消费/
取消了手势而立即返回 null。代码没法区分这两种"null"，于是每次单击都被当成长按处理。

**怎么定位出来的**：真机上简单点一下卡片，观察到直接跳转到了调参面板，不是点开卡片本身的功能——
现象本身就是最直接的证据，不需要额外工具。

**修法**：不再用裸 `pointerInput` 跟 `.clickable` 抢同一组按下/抬起事件，改成用 `.clickable` 自己
`interactionSource.collectIsPressedAsState()` 暴露的 `pressed` 状态，外面套一个
`LaunchedEffect(pressed) { if (pressed) { delay(3000); onLongPress() } }`——`pressed` 变 false
时`LaunchedEffect` 自动取消，不需要跟 `.clickable` 竞争同一个手势。

**教训**：两套机制不要盯着同一组原始指针事件各自判断，让其中一套（这里是 `.clickable`）当权威源，
另一套只读它暴露出来的状态。（这个入口本身在 2026-08-11 已经整个删掉，见 DECISIONS.md，但这个
教训对以后任何类似场景都适用。）

### 2. LiquidSlider 拖动被 ModalBottomSheet 的下滑关闭手势抢走（未解决）

**现象**：设置面板（`ModalBottomSheet`）里拖动 `LiquidSlider` 的滑块，整个面板被当成"用户在下滑
关闭面板"，直接收起，而不是调整滑块数值。点一下滑块设置数值是正常的，只有拖拽会触发这个问题。

**根因**：`ModalBottomSheet` 自己监听垂直方向的拖拽来做下滑关闭，跟滑块的水平拖拽手势在同一棵
手势树上竞争，Compose 的手势消费协议没有明确判给滑块。

**怎么定位出来的**：真机上直接拖滑块复现，不需要额外工具。

**已经试过、没用的修法**：
1. `Modifier.draggable(Orientation.Horizontal, ...)` —— 没用，`draggable` 不参与跟
   `detectHorizontalDragGestures` 一样的手势消费协议。
2. 手动 `detectHorizontalDragGestures { change, _ -> change.consume(); ... }`，显式 consume 每个
   delta —— 真机复测，同样的下滑关闭复现，没解决。
3. 移植官方 catalog 组件本身自带的手势代码（`DragGestureInspector.inspectDragGestures`）——读了
   源码确认它自己也**从不调用** `change.consume()`，跟前两次尝试的方向正好相反，移植它本身预期也
   不会解决这个问题（catalog 自己的例子没有把滑块塞进一个会抢手势的 BottomSheet 里，没被这种场景
   测试过）。

**当前状态**：未解决。真正的修法大概率不是"让两套手势协调"，而是拿掉设置面板的 `ModalBottomSheet`，
换成项目自己控制、没有下滑关闭手势的全屏玻璃层 + 明确的关闭按钮——这条改动量较大，单独排期，没有
跟别的改动混在一起做。

**教训**：手势冲突类问题，"改自己这边的 consume 方式"这条路已经试了三个变体都没用——如果连着两次
修不好，应该往"是不是压根不该有两套机制在竞争"这个方向想，而不是继续换 consume 的写法。

### 3. `LaunchedEffect` 的 key 包含了会被自己改动的状态 → 自我取消/重置

**现象类**（这是设计阶段就被预见并防住的一类问题，不是"線上真的炸過一次"的事故记录——如实标注：
下面写的是代码里体现出的防御设计，不是复现过的崩溃）：如果一个章节的富文本编辑器状态只用章节
`id` 做 hydration 的 key，那么正常打字触发的防抖自动保存（写回 `contentHtml`）本身，看起来会跟
"内容被外部结构性操作改写了"没有区别——如果 hydration 逻辑因此重新触发，光标位置会在每次自动保存
时被重置，编辑体验完全不可用。

**根因**：`LaunchedEffect`/状态 hydration 的 key 如果包含了这个 effect 自己（或者它间接触发的动作）
会修改的状态，就会出现"改一下自己，把自己重新触发一遍"的自我循环或者错误重置。

**怎么定位出来的**：从 `teleprompter/TeleprompterModel.kt` 里 `TeleprompterChapter.structureVersion`
字段的文档注释反推——注释直接写明了"只用 id 做 key 会导致什么后果"，说明这是设计时就想清楚、主动
避开的坑，不是踩了之后才修的。

**修法**：加一个独立字段 `structureVersion`，只在合并/拆分这类结构性操作时手动 +1，正常打字触发的
防抖保存不碰它。Hydration 逻辑 key 在 `(id, structureVersion)` 上，而不是单纯 `id`。

**教训**：设计任何 `LaunchedEffect(key) { ... }` 或者依赖 `snapshotFlow` 重新触发的逻辑之前，先问
"这个 effect 自己的动作，会不会改到它的 key"——会的话，要么把 key 拆细（像这里一样分离出一个专门的
版本号），要么换成不依赖自动重触发的显式调用。

---

## 液态玻璃：背景捕获必须是单个 `drawBehind`，不能拆子 Composable

**现象**：启动页玻璃卡片效果不出图——玻璃控件后面是空白，看不到本该被采样的渐变+光斑背景。

**根因**：最早的实现把渐变背景和几个光斑分别用嵌套的子 `Box` 画，`layerBackdrop` 挂在最外层容器上，
但 backdrop 捕获拿到的内容是空的。

**怎么定位出来的**：LauncherActivity.kt 里 `LauncherBackground` 的文档注释直接记录了这个结论
（"a prior version spread the gradient + glow blobs across nested child Boxes, and the capture
came back blank"），具体是怎么一步步排查到"必须是单个 drawBehind"这个结论的过程没有留下更细的
记录。

**修法**：整个背景（渐变 + 4 个光斑）收进同一个 `drawBehind {}` 闭包，画在挂着 `layerBackdrop` 的
那个节点自己身上，不拆成子 Composable。

**教训**：`layerBackdrop` 捕获的是这个节点自己的绘制内容，子 Composable 各自的绘制不一定会被
认为是"这个节点的内容"——背景越复杂，越有拆分的冲动，但对这个库来说反而更容易踩这个坑。

## 液态玻璃：`layerBackdrop` 挂在祖先节点上 → 原生 SIGSEGV，`adb logcat` 看不到

**现象**：打开提词器设置面板，app 直接卡退。`adb logcat -d | grep "FATAL EXCEPTION"` 什么都搜不到，
表面上看起来"没有崩溃日志"。

**根因**：`layerBackdrop(sheetBackdrop)` 挂在了包含多个 `LiquidSlider` 的同一个 `Column` 上，而这些
`LiquidSlider` 又用 `backdrop = sheetBackdrop` 采样——捕获层的子树里包含了正在采样它的控件自己，
RenderNode 树形成循环引用，`RenderNode::prepareTreeImpl` 递归到栈溢出。

**怎么定位出来的**：`adb logcat` 默认过滤不到原生信号——**必须** `grep -iE "fatal signal|tombstone|SIGSEGV"`
才能看到 `Fatal signal 11 (SIGSEGV) ... in tid ... (RenderThread)`，tombstone 里写着
"Cause: stack pointer is not in a rw map; likely due to stack overflow"，backtrace 显示
`RenderNode::prepareTreeImpl` ↔ `SkiaDisplayList::prepareListAndChildren` 互相递归。第一次怀疑错了
方向（以为是折射参数相对小控件过大导致越界读取，加了 `maxRefractionPx` 钳制——这个钳制是合理的
防御但没解决这次崩溃，真机重测同一个 fault address 反复复现）。回头把 `layerBackdrop` 节点和采样它
的控件的父子关系画出来，才看出是循环捕获。

**修法**：背景捕获节点必须是采样它的玻璃控件的**同级 sibling**，不能是其祖先。正确结构是单独一个
`Box(Modifier.matchParentSize().layerBackdrop(x).background(color))`，跟玻璃控件并列，不包裹它们。

**教训**（这条最贵，值得反复读）：
1. Android 原生 SIGSEGV **不是** Kotlin 异常，`adb logcat` 默认搜不到，必须专门 grep
   "fatal signal\|tombstone\|SIGSEGV"，别的关键词会误判成"没有崩溃"。
2. 自动化真机测试时，每一步模拟操作之后都用 `pidof <package>` 确认进程还活着，比等 UI 反馈更快
   定位是哪一步操作导致的崩溃。
3. 第一个看起来合理的假设（这里是"折射参数过大"）验证失败之后，要老老实实回到证据本身（同一个
   fault address 反复出现）重新想，而不是在同一个假设上继续小修小补。

## 液态玻璃：色散着色器采样坐标无 clamp，窄长面板花屏

**现象**：细长的玻璃面板（提词器大纲抽屉约 1:6.2、播放页控制栏约 1:6.9）开着 `chromaticAberration`
时，边角区域出现彩色雪花状画面损坏，不是崩溃。

**根因**：库的色散 shader（fork 和官方 2.0.0 逐字节相同）用
`dispersionIntensity = chromaticAberration * (centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y)`
算色散强度——这是像素**原始位置**的鞍形函数，跟法线无关，面板越窄长，角落越容易把这个值推到接近
±1。7 次 `content.eval()` 采样（red/orange/yellow/green/cyan/blue/purple）的坐标全部没做边界
clamp，越界采样读到了捕获缓冲区之外的数据。

**怎么定位出来的**：先怀疑是圆角=0 导致 SDF 法线退化（`normalize(max(cornerCoord,0))` 在零向量上
未定义），把尖角改成 2dp 最小半径重新构建测试——真机复测，花屏原样复现，排除这个假设。第二次做了
真正的 A/B 实验：只把这一处面板的 `chromaticAberration` 关掉，其他参数不变——花屏完全消失。这个
对照结果才是找到真正机制的关键证据，后续又对着 `F:\liquidglass-ref`（官方 2.0.0 本地克隆）的
shader 源码逐行核对，确认 fork 和官方在这个 shader 上逐字节相同、`git log` 显示从 KMP 重写以来
从未被改过，且库自己另一个 shader（`ReflectiveGlassShaderString`）对色散坐标是有 clamp 的——这个
对比证明官方的遗漏是疏忽不是设计。

**修法**：项目自己包里重新实现一份带 clamp 的色散 shader（`glasstuning/PatchedLens.kt`），7 处
`content.eval()` 全部 `clamp(coord, float2(0.0), size)`。详见 `GLASS.md`。

**教训**：视觉损坏类问题，先做能推翻假设的对照实验（这里是"关掉这一个开关，问题是否消失"），比
死磕第一个看起来合理的理论更快找到真因。跟第三方库对比源码时，同一个文件里如果有姊妹函数用了
某种防御手段而当前这个没有，这种不一致本身就是强证据，说明是遗漏不是有意为之。

---

## 虚拟定位：早期 Capacitor 实现在 Android 14/15 反复崩溃，重写成原生 Service

**现象**：项目更早期用 Capacitor 做过一版虚拟定位，在 Android 14/15 上反复崩溃。

**根因**：每次崩溃都能追到几条前台服务（foreground service）相关规则里的某一条，Capacitor 那层
没有精确控制这些时序/权限检查。

**怎么定位出来的**：`mockloc/MockLocationService.kt` 类注释直接写明"每次崩溃都能追到下面这几条
规则中的一条，所以每条都单独写注释，不折叠进通用的启动代码里"——具体的历次崩溃调试过程本身没有
留下更细的记录，能确认的是"最终结论是这几条规则"，不是"怎么一步步试出来的"。

**现在踩中的具体规则**（改写成原生 `Service` 后，代码里逐条防住的）：
1. `startForeground()` 必须在 `Service` 创建后约 5 秒内调用，Android 14+ 严格执行，超时抛
   `ForegroundServiceDidNotStartInTimeException`，这个异常**会崩掉整个进程**，不只是这个 service。
   所以这行代码放在 `onCreate()` 最前面，早于任何可能抛异常的 `LocationManager`/测试 provider
   设置。
2. 调用 `LocationManager.addTestProvider`/`setTestProviderLocation` 之前必须已经拿到
   `ACCESS_FINE_LOCATION`，没有的话是必现 `SecurityException`——虽然 Activity 那边的前置检查
   列表理论上已经防住了这个场景，`Service` 自己在 `onStartCommand` 里又重新查了一遍权限，不信任
   调用方。
3. 定位注入失败的每条路径都包在 `try/catch` 里，`stopSelf()` 收场，不让它把整个 app 进程带崩。

**教训**：涉及 Android 前台服务的时序类系统限制（尤其是 API 34+ 新增的强制规则），出问题时大概率
不是业务逻辑错，而是生命周期时序踩了系统红线——排查方向要先想"是不是有个我没意识到的系统时限/权限
检查点"，而不是先怀疑定位逻辑本身。

---

## 提词器：单个 `TextField`（富文本编辑器）超两万字符会崩，改成按章节独立编辑器

**现象/约束**：单个富文本编辑状态如果文本量超过大约两万字符会导致崩溃。

**根因/怎么定位出来的**：`teleprompter/TeleprompterEditorScreen.kt` 里 `ChapterItem` 的文档注释
直接点名"2万字符 TextField-crash constraint"作为按章节拆分编辑器这个架构的存在理由——具体的崩溃
表现（OOM？富文本库内部索引溢出？)和复现步骤本身没有留在注释里，只留下了"必须按章节拆"这个结论和
它驱动的架构约束，如实标注为结论已确认、诊断过程未保留。

**修法**：文档按章节拆分，每章节各自独立的富文本编辑器状态，而不是整个文档一个大 `TextField`。
`ChapterItem` 在编辑态/展示态之间切换必须像素级一致（标题+正文的静态展示版本要跟可编辑版本长得
一模一样），这样按章节拆分对用户是完全不可见的实现细节，不会因为焦点切换/滚动导致内容在"编辑器"
和"静态文本"之间跳动。

**教训**：这类"某个组件超过某个规模就崩"的约束一旦确认，处理办法通常不是"想办法撑大这个组件"，
而是从架构上把大问题拆成本来就不会碰到这个上限的小单元——同时要让拆分本身对用户不可见，不能因为
内部实现拆了，用户就看到编辑体验的接缝。

## 提词器：WPS 导出的 Word 文档，`pStyle` 是数字 ID，标题判定要靠 `outlineLvl`

**现象**：从 WPS 导出的 `.docx` 文件，标题识别失效或者不准。

**根因**：微软 Word 导出的文档，段落的 `w:pStyle` 引用的是具名样式（如 `"Heading1"`），可以直接按
名字识别标题层级；WPS 导出的文档里，`w:pStyle` 引用的是纯数字 ID（如 `"2"`），只有解析
`styles.xml` 里这个 ID 对应的样式定义才能知道它是不是标题、第几级。

**怎么定位出来的**：`teleprompter/TeleprompterWordParser.kt` 多处注释明确记录了这个区分（"WPS emits
numeric styleId ("2") instead of named ones ("Heading1")"），以及最终选定的判定优先级。

**修法**：标题层级判定优先看段落自己直接带的 `<w:outlineLvl>`；没有的话才去解析 `w:pStyle` 引用的
样式、沿着 `w:basedOn` 继承链递归解析出 `outlineLvl`；具名样式的名字匹配（如识别"Heading"字样）
只作为两者都缺失时的最后兜底，不作为主判据——因为 WPS 的数字 ID 会让"按名字识别"直接失效。

**教训**：解析别的软件生成的 Office 文档格式，不能只按最常见的生产者（这里是 Word）的输出习惯写
解析逻辑——同一个 OOXML 字段，不同生产者填的值语义可能完全不同（具名 vs 数字 ID），优先信任更底层、
更不容易被生产者差异影响的字段（这里是直接的 `outlineLvl` 而不是需要按名字猜的 `pStyle`）。

## 虚拟定位/GPS 工具：`ACCESS_MOCK_LOCATION` 不声明，开发者选项里就找不到这个 app

**现象**：不声明 `ACCESS_MOCK_LOCATION` 权限，就算其他前提条件都满足，"开发者选项 → 选择模拟位置
信息应用"这个系统列表里也不会出现 GKD-T。

**根因**：这个权限从 API 23 开始已经不是真正生效的运行时权限（系统不会真的用它做权限检查），但
系统扫描"哪些已安装应用声明了这个权限"来决定填充这个列表——不声明，应用本身就不在候选名单里，
不管其他三个前提条件（`ACCESS_FINE_LOCATION`、前台服务时序、`LocationManager` 调用）做得多对。

**怎么定位出来的**：`AndroidManifest.xml` 里这条权限声明自带的注释直接记录了这个结论。

**修法**：`AndroidManifest.xml` 声明
`<uses-permission android:name="android.permission.ACCESS_MOCK_LOCATION" tools:ignore="MockLocation,ProtectedPermissions" />`。

**教训**：Android 权限系统里有一类"名义上的运行时权限"，实际只在系统扫描/UI 展示阶段起作用——遇到
"功能该有的前提都满足了，但系统入口就是不出现"这种症状时，要想到"是不是缺了一个纯粹用来注册候选
资格、不做真正权限检查的声明"。

## 图片/视频选择：Photo Picker（`ACTION_PICK_IMAGES`）vs `ACTION_GET_CONTENT` 的差异

**现象/决策**：`WebToolActivity` 的文件选择没有用 `fileChooserParams.createIntent()` 这个默认写法。

**根因**：`createIntent()` 默认走 `ACTION_GET_CONTENT` 的 Photo Picker 兼容 shim
（`PhotopickerGetContentActivity`），这个兼容层功能阉割——不支持 HEIC、排序不同、缺项，跟
API 33+ 真正的 Photo Picker（`MediaStore.ACTION_PICK_IMAGES`）体验有明显差距。

**怎么定位出来的**：`WebToolActivity.kt` 对应位置的注释直接记录了这个对比结论。

**修法**：API 33+（Tiramisu）直接用 `Intent(MediaStore.ACTION_PICK_IMAGES)`（多选时带上
`EXTRA_PICK_IMAGES_MAX`），低于这个版本才退回 `ACTION_OPEN_DOCUMENT` + 显式 MIME 类型 + 多选标记，
不用 `ACTION_GET_CONTENT`。

**教训**：WebView 里 `WebChromeClient.onShowFileChooser` 提供的 `createIntent()` 默认实现只是能用，
不是最好用——同一个功能，系统 API 有专门为新用例（这里是图片/视频选择）设计的新入口时，用默认兼容
路径可能会悄悄阉割功能，值得对着实际用到的意图专门查一遍有没有更合适的直接入口。

## 虚拟定位：OSM 瓦片国内不通，换高德瓦片 + 坐标纠偏插件

**现象/决策**：地图选点页（`mock-location.html`）没有用 Leaflet 默认的 OpenStreetMap 瓦片源。

**根因**：OpenStreetMap 官方瓦片服务器在国内网络环境下不可达。高德地图瓦片可达，但坐标系是
GCJ02（国测局加密坐标），跟地图交互全程使用的 WGS84（GPS 原始坐标）不一致，直接叠加瓦片会有
偏移。

**怎么定位出来的**：`mock-location.html` 里瓦片图层初始化代码上方的注释记录了完整方案和取舍。

**修法**：瓦片源换成高德（`webrd0{s}.is.autonavi.com`，必须 https，因为 Android 9+ 默认禁止明文
http），配合 `Leaflet.InternetMapCorrection` 插件设置 `corrdType: 'gcj02'`——这个插件的做法是**只
重算每张瓦片的绘制位置**，地图的 `setView`/marker/点击事件坐标全程仍然是 WGS84，不需要在业务逻辑
里来回转换坐标系。跟"直接把整个地图的坐标系（CRS）改成 GCJ02"这个方案相比，纠偏插件方案不侵入
业务逻辑代码。

**教训**：国内网络环境下集成境外地图瓦片服务，默认假设不可达。换成国内可达的瓦片源时，优先选
"只修正瓦片绘制位置、业务坐标不变"的纠偏方案，而不是把整个地图坐标系统一改掉——后者会让所有跟
坐标打交道的业务代码都要考虑坐标系转换,持续维护成本高得多。

---

## 液态玻璃：`patchedLens()` 的 clamp 只挡住了越界读取，没挡住"合法范围内的花屏"

**现象**：提词器全面液态化那轮（2026-08-12），设置面板（新做的，透正文）和播放页控制栏（没动过，
`patchedLens()` 补丁上一轮"已确认修好"）都在**浅色背景 + 真实粗体黑字**的组合下复现了彩虹花屏——
跟最早那次大纲抽屉的花屏视觉上一模一样，但补丁明明是打过的。

**根因**：`patchedLens()` 的 `clamp(coord, float2(0.0), size)` 只解决了"色散把采样坐标推到捕获
缓冲区外，读到未定义数据"这一种故障——这是最早在大纲抽屉上诊断出来、也是唯一验证过的故障模式。
但色散还有第二种致病路径，clamp 完全挡不住：只要偏移量 `d * grad * dispersionIntensity` 相对元素
尺寸足够大，红/蓝通道各自往相反方向偏移后，即使**偏移后的坐标依然在合法范围内**，也可能分别读到
背后内容里两块视觉上完全不相关的区域——比如一个偏移读到粗体黑字的"骨"字，另一个偏移读到旁边的
空白——拼出来的颜色分离本身就是花屏，跟越界读垃圾数据是两种不同的故障，clamp 只堵住了后者。

**怎么定位出来的**：设置面板第一次装机就带着彩虹漩涡花屏（截图证据），第一反应怀疑是新代码引入的
新 bug；A/B 测试（跟大纲抽屉当年同一个方法）——关掉这一处的 `chromaticAberration`，花屏消失——
排除了新代码本身的问题，坐实还是色散。但真正意外的是：紧接着顺手回归测试播放页（`TeleprompterPlayerScreen.kt`
这轮完全没碰过的文件），控制栏在浅色背景下**同样复现了花屏**——这才推翻了上一轮"补丁已经验证修好"
的结论：上一轮验证用的浅灰背景（E0E0E0）+ 相对干净的文档内容，恰好没有触发到足够剧烈的颜色分离，
被判定为"通过"，其实只是测试条件不够刁钻，不是真的没问题。

**修法**：设置面板和播放页控制栏都单独 `.copy(chromaticAberration = false)`，跟大纲抽屉当年的
处理方式一致——不是重新调整 clamp 的算法（clamp 本身没错，只是不足以覆盖这第二种故障），而是对
"背后是真实高对比度内容"的场景直接关掉色散这个效果。

**教训**（这条比结论更重要）：
1. "补丁生效"这个结论，测试条件必须尽量刁钻，不能只用手头第一个凑手的场景（这里是一个不太深的
   灰色）验证一下就下定论——一个 bug 有多种触发路径时，用一种路径验证过的修法，换一种路径可能
   照样复现，因为压根没堵住那条路。
2. 色散这个效果的真正安全边界不是"元素多窄多小"（最早的假设），而是**背后的内容有多高对比度**——
   窄长的大纲抽屉、浅色背景的播放控制栏、透正文的设置面板，触发条件表面上各不相同（形状、颜色、
   容器类型都不一样），但共同点只有一条：背后是真实的、细节丰富的、对比度高的内容。以后新加的
   任何 `tunedGlass()` 调用点，只要背后可能是真实文字/图像内容而不是纯色或渐变，色散就该默认关掉，
   而不是默认继承 `TunedGlassParams` 里 `chromaticAberration=true` 这个基线——这个基线本身是当初
   针对纯色/渐变卡片调出来的，从来没有针对"透真实内容"这个场景验证过。

---

## LiquidSlider：设置面板滑块拖动数字乱跳后弹回——错怪了手势消费，真正原因是状态回写链路太重

**现象**（2026-08-13，"提词器全面液态化"那轮的后续）：设置面板里的滑块（字号/整体缩放/编辑显示
缩放/行距/内容宽度/副标题字号/语速）拖动时数字跳几下又弹回原值，把手视觉基本不动。同一份
`LiquidSlider` 组件在播放页速度滑块上完全正常。

**最初的错误假设**：以为是设置面板从 `ModalBottomSheet` 换成 `Box` + `verticalScroll` 之后，
父级 `verticalScroll` 在 NestedScroll 的 pre-scroll 阶段抢走了非纯水平的拖拽增量——`DragGestureInspector.
inspectDragGestures` 确实从不调用 `change.consume()`，这个假设看起来合理，也符合"子节点没收干净
输入，父级自然抢得到"的直觉。据此做了两轮修复（先是移动第一次采样后 consume，然后升级成 down
落地立刻 consume），编译、装机、真机测试，问题依旧。

**怎么查出真正原因的**：在 `LiquidSlider`/`DampedDragAnimation` 的 `onDragStarted`/`onDrag`/
`onDragCancel`/`onDragEnd`/外部值回同步这几个关键点各打一行 `LogUtils.d`，装机后用户真机连续拖了
11 次，用 `adb logcat` 抓日志回看。结果：**`onDragCancel` 一次都没触发过，11 次全部是
`onDragEnd`（正常抬手）**——手势从头到尾没有被谁抢走，两轮 consume() 改动的方向从一开始就错了。
真正反常的是：同一次拖拽内，每一次 `onDrag` 回调打印出的 `targetValue` 全部是同一个值，完全没有
随拖拽推进而更新，直到松手时用这个冻结的旧值把整段拖拽进度覆盖掉。

**根因**：`onDrag` 计算增量时用的 `targetValue`（`DampedDragAnimation.valueAnimation.targetValue`），
只有 `updateValue()` 被调用时才会前进；而 `updateValue()` 只在组件内部一个 `LaunchedEffect` 里，
监听调用方传入的 `value()` 发生变化时才触发。播放页速度滑块的 `value`/`onValueChange` 读写的是
纯本地 `remember { mutableStateOf(...) }`，同一帧内就能被 `snapshotFlow` 观察到；但设置面板的滑块
`onValueChange` 走 `vm.updateSettings` → 把整个 `TeleprompterLibrary`（含全部文档章节）作为新对象
塞进 `teleprompterLibraryFlow`（StateFlow）→ 上层 `collectAsState()` 重组 → 一路传参传到
`LiquidSlider` 的 `value` 闭包——这条链路的往返延迟明显长于 `onDrag` 的调用间隔（实测约
10~100ms 一次），导致 `targetValue` 在整段拖拽期间基本冻结。

**修法**：`onDrag` 里直接同步调用 `updateValue(newValue)`，让动画自己的 `targetValue`/视觉位置立刻
跟手，不再依赖调用方的回写往返；`onValueChange` 改成一条独立的、节流到约 100ms 一次的旁路
（`pendingPushValue` + 一个常驻的 100ms ticker `LaunchedEffect`），松手时保证补发一次最终值。另外
加了 `isDragging` 标志，让原有的"外部值变化时回同步"逻辑在拖拽期间直接跳过，避免调用方那条慢链路
迟到的旧值把正在进行的拖拽再次弹回去。改动全部收在 `LiquidSlider.kt` 内部，所有调用方（7 个设置面板
滑块 + 播放页速度滑块）自动一起受益，不用逐个改调用点。两轮 consume() 相关代码
（`DragGestureInspector.kt` 的 `consumeDrag` 参数、`DampedDragAnimation.kt` 的
`consumeDragForNestedScroll`）已撤销，恢复成上游 catalog 的原始写法。

**教训**：
1. "手势被父容器抢走"和"状态回写跟不上"这两类故障，表面症状（拖不动/弹回原值）几乎一样，仅凭
   现象猜根因很容易点错方向——这次两轮 consume() 修复都编译通过、也确实装到了真机上（用两个
   Samsung 安全文件夹 profile 共享同一个 APK 这一点交叉验证过，不是没生效或测了旧包），但『代码改
   了、装上了、现象没变』本身就该是一个强烈信号：换个假设，而不是在同一个假设里加码。
2. 真正定位到根因靠的是把内部状态在关键节点打日志、让用户用真手指连续操作、事后用 adb logcat 回看，
   而不是继续在 adb 模拟或纯代码审查里猜——这类"手感"类 bug，尤其是牵扯 Compose 状态往返延迟的，
   日志时间线比任何理论推导都直接。
3. 把"跟手的视觉状态"和"要不要通知调用方"拆成两条独立速度的通道（本地立即 + 节流异步）是这类
   问题的通用解法：播放页滑块之所以从来没中招，纯粹是因为它凑巧绑定了一个足够轻量、同步生效的
   本地状态，不是这个组件设计上就没有这个坑。以后任何新的可拖拽组件，只要 `onValueChange` 可能
   接到一条不轻的状态更新链路（比如替换一个大对象、触发大范围重组），都要默认假设"这条链路追不上
   拖拽频率"，而不是等真机测出乱跳了才发现。

---

## GlassParams.tint：第一遍 drawRect 没吃 alpha，`tint.enabled` 一开就摘不掉

**现象**（2026-08-13，提词器设置面板新加的"透明度"滑块）：滑块调到 100% 不是纯白，调回 0% 没反应，
面板一直卡在一个半透明雾蒙蒙的状态，怎么拖都甩不掉。

**根因**：`GlassEffects.kt` 的 `combinedOnDrawSurface`（`tunedGlass()` 内部转换 `GlassParams.tint`
的唯一入口）里两条 `drawRect`：
```kotlin
drawRect(c, blendMode = BlendMode.Hue)       // 没传 alpha —— 用的是 c 自己的 alpha（colorArgb
                                              // 通常是 0xFF，即完全不透明），根本没被 tint.alpha
                                              // 调制过
drawRect(c.copy(alpha = tint.alpha))         // 只有这一条真的按 tint.alpha 缩放
```
第一条 Hue 混合只要 `tint.enabled == true` 就以**固定满强度**画一次，跟 `tint.alpha` 完全无关——只要
透明度滑块不是精确停在 0（`tint.enabled = alpha > 0f`），这条固定的去饱和效果就一直在，甩不掉；
100% 时虽然第二条纯色叠加是满的，但第一条 Hue 混合本身不是"覆盖"而是"去饱和"，单独也糊不出纯白。

**为什么这条路径从来没人发现**：`grep` 了一遍全项目，在这次之前，`tint.enabled` 在所有真实调用点
（`TunedGlassParams`、LauncherCard、播放控制栏等）永远是 `false`——这条 `combinedOnDrawSurface` 的
tint 分支从写出来那天起就没在真机上真正跑过一次，是这次提词器设置面板的"透明度"滑块第一次把
`tint.enabled=true` 送进真实渲染管线，才把这个潜伏的 bug 炸出来。

**修法**：给第一条 `drawRect` 也传 `alpha = tint.alpha`，两条完全同步缩放。

**教训**：
1. 一个字段/分支只要从没被真实数据触发过，就跟没写过一样不可信——`GlassParams` 里这类"暴露了
   但从没人真正启用过"的字段（`highlight`/`shadow`/`innerShadow`/`tint` 当时全是 `enabled=false`
   的默认值），真正接一个新用例进去之前，不能假设它是对的，得先当作未验证代码看。
2. 现象描述本身就是线索——"调到头不是纯色""怎么调都甩不掉一个固定的雾"，这种"有底噪、跟滑块位置
   脱钩"的模式，比"完全没反应"更该往"某个效果没被参数正确调制、被写死成固定强度"这个方向查，不是
   状态回写延迟那一类问题（那类是"该更新的没更新"，这类是"不该固定的被写死了"）。

## `Color.copy(alpha=X)` 在已经是全透明的颜色上不会保持透明——它是覆盖 alpha 通道，不是相对缩放

**现象**：给 `LiquidCompactButton`/`LiquidCompactIconButton` 传 `inactiveTint = Color.Transparent`
想让按钮完全融进父容器背景，结果真机上是一个明显的黑色圆角背景块（编辑页顶栏"编辑/播放/设置/
文档库"几个按钮周围一圈黑影，用户截图发现）。

**根因**：`LiquidCompactButton.kt` 内部固定写的是 `inactiveTint.copy(alpha = 0.5f)`——`Color.copy`
是直接把 alpha 通道设成传入值，不管原来的 alpha 是多少。`Color.Transparent` 本身是
`Color(0,0,0,0)`（RGB 是黑色，只是 alpha=0），`.copy(alpha=0.5f)` 之后变成 `Color(0,0,0,0.5)`——
黑色 50% 不透明，而不是"透明度打对折还是透明"。

**修法**：改成 `inactiveTint.copy(alpha = inactiveTint.alpha * 0.5f)`，对原本的 alpha 做相对缩放
而不是覆盖。这样默认值（`MaterialTheme.colorScheme.surfaceVariant`，alpha=1）效果不变，
`Color.Transparent`（alpha=0）缩放后还是 0，真正透明。

**怎么定位出来的**：用户截图直接看到黑色背景块，不是靠推理——一眼就能看出"这个颜色参数没生效"，
对着 `LiquidCompactButton.kt` 的 `.background(...)` 那一行代码读一遍就能看出 `.copy(alpha=X)` 的
语义陷阱。教训：任何"传一个颜色进来、内部再 `.copy(alpha=...)`"的写法，先想清楚是要覆盖还是要
相对缩放，不能想当然按"覆盖"处理就默认外部传的颜色本身的 alpha 无所谓。

---

## `Modifier.blur()`（纯 Compose 效果）叠加在 `layerBackdrop()` 捕获节点上，会让模糊糊出这个节点自己的边界

**现象**：文档库页背景图片模式新增的"模糊度"滑块，调高之后不止图片本身变糊——整个文档库页面
（包括列表文字、图标）看起来都被影响了，不是只有采样这层背景的玻璃控件（顶栏/FAB/行菜单）的
折射效果跟着变糊（那部分是预期内的，因为它们采样的就是这层被捕获的背景）。用户截图确认。

**根因**（推断，没有拿到库源码逐行确认，标注为推断）：`Modifier.layerBackdrop(backdrop)` 和
`Modifier.blur()` 都是各自往同一个节点上叠 `graphicsLayer`/`RenderEffect`。项目里所有其它模糊效果
都走 `com.kashif_e.backdrop.effects.blur()`（配合 `drawBackdrop`/`tunedGlass` 使用的 AGSL shader
路径），从来没有人把 Android 原生 `Modifier.blur()` 直接叠在一个同时被 `layerBackdrop` 标记为
"捕获源"的节点上——这个库是 `0.0.1-alpha02`，这次是本项目第一次尝试这种组合，出现了糊出节点自身
边界的效果，符合这个库已经暴露过多次的"叠加非它自己设计好的效果组合会出岔子"的模式（色散花屏、
tint 不吃 alpha 等，见本文档其它条目）。

**修法**：给背景图片渲染拆成两层——`layerBackdrop(backdrop)` 只标记在渲染"原始未处理图片"的那层
（这层才是玻璃控件真正采样的对象），"模糊度/亮度/饱和度"这几个滑块的效果画在另一个不带
`layerBackdrop` 标记的图层上，完全覆盖在第一层上面（用户眼睛只看得到这层，但它跟共享背景捕获
机制完全没有关系）。这样滑块只影响用户直接看到的背景，不会影响玻璃控件的折射效果，也不会糊出
这个区域本身的边界。

**怎么定位出来的**：用户反馈"我只让你调背景的，不是让你调整个软件的整个界面的"，通过
AskUserQuestion 确认严重程度是"整个屏幕都被影响"而不只是"玻璃按钮的折射效果跟着变"，据此推断
是 `Modifier.blur()`+`layerBackdrop()` 组合的问题，还没有在真机上针对这个具体修法本身重新验证过
（诊断和修法都基于代码架构推理，不是加日志或对照实验实测出来的，比这份文档其它条目的确定性低，
留在这里也是提醒自己这条如果以后还复现，需要用真机 debug 覆盖层实测，别只信这次的推理）。

## `Modifier.layerBackdrop()` 捕获的是它自己所在节点"最终合成结果"，打在外层 Box 上会连带捕获到画在它上面的兄弟内容

**现象**：上一条 pitfall（`Modifier.blur()` 叠加在 `layerBackdrop()` 节点上）第一次的修法——把
`layerBackdrop` 挪到包住"原图 Image + 滤镜后 Image"两者的外层 `Box` 上，指望只有最下面那份原图
被捕获——真机测试后问题依旧：顶栏、"设置背景"半屏面板自己的玻璃折射，看到的还是模糊变色后的
版本，跟滑块数值完全对应（用户截图确认：亮度 0.3/饱和度 0/模糊度 8dp 时，顶栏缝隙、面板本身的
折射都是暗的糊的，不只是面板里的小预览图）。

**根因**：`layerBackdrop(backdrop)` 打在哪个节点上，捕获的就是**那个节点自己的子树最终渲染
结果**——不是"这个节点当时画的第一笔"。把它打在外层 `Box` 上时，`Box` 的子树里后画的滤镜层
（盖在原图上面、真正显示给用户看的那层）也是这个 `Box` 渲染结果的一部分，所以被一起捕获进去了。
两个 `Image` 都在同一个 `layerBackdrop` 标记的子树里，跟只放一个东西没有区别。

**修法**：`layerBackdrop(backdrop)` 必须直接打在"原图" `Image` 自己的 `Modifier` 链上，不能打在
包住它和滤镜层的外层容器上；滤镜层作为不带 `layerBackdrop` 标记的兄弟节点画在后面（z-order 更
上层，用户只看得到它），这样它的渲染完全不会进入原图节点自己捕获的那份缓冲区。改动细节：
`LibraryBackgroundLayer` 从接收一个已经拼好 `layerBackdrop(...).fillMaxSize()` 的 `Modifier`
参数，改成直接接收 `backdrop: LayerBackdrop`，在函数内部精确控制哪个具体节点带这个标记。

**怎么定位出来的**：第一次的推理修法没有实测就报告"已修"，真机验证时用户又截了一次图，颜色/
亮度跟滑块数值精确对应，一眼就能看出还是同一个问题。教训：`layerBackdrop`/`drawBackdrop` 这类
"捕获-消费"模式的修法，"捕获点该打在哪个具体节点上"这件事本身就是最容易出错的地方，改完必须
真机截图验证，不能只凭代码结构推理就报告修复。

## `tunedGlass()`/`drawBackdrop()` 的实际重绘只在"手势正在进行"期间发生，松手/结算后就冻结在最后一次重绘的画面上

**现象**：设置面板"透明度"滑块拖动后数字标签正确变化（16%→97%→100%→52%...），但面板本身的玻璃
画面看起来几乎没变。用截图逐像素比对确认：同一位置像素在透明度=0%和100%时**完全一致，逐字节
相同**，不是视觉上不明显——渲染压根没变。

**根因（真机日志实测确认，不是推测）**：在 `combinedOnDrawSurface`（真正画 tint 的地方）和
`TeleprompterSettingsWindow`（读 `settings.settingsPanelOpacity` 的地方）各加一行
`Log.d`，对照时间戳发现：
- Compose 的**重组**（读取最新 state、更新 `Text` 标签等）每次拖动都正确触发，数值一直是新的。
- 但**实际画 tint 的那次调用**只在"手势正在进行"的这段时间里连续触发（拖动中每帧都画，松手后
  惯性动画的最后几帧也画），一旦手势/动画完全静止，`drawing tint` 这行日志就再也不出现——即使
  之后 `settings.settingsPanelOpacity` 又变了很多次（重组日志持续更新），画面也不会再重绘，
  永远停在"最后一次真正重绘时"的那个值上。
- 佐证：拖动模糊度滑块（另一个滑块）也会让 tint 用当前最新的透明度值重绘一次——说明"任意手势
  正在进行"都能触发重绘，不是 tint 专属的，是这整个 `drawBackdrop()` 的重绘触发机制本身跟手势/
  动画绑定，不跟它自己依赖的 state（`tint.alpha`/`blurRadiusDp` 等）绑定。

**怀疑但没有源码验证的更深层原因**：`com.kashif_e.backdrop`（0.0.1-alpha02，alpha 阶段）很可能
是用 `Modifier.Node`/`DrawModifierNode` 实现的，`effects`/`onDrawSurface` 这些 lambda 的
`update()` 阶段可能没有正确标记"需要重绘"（`invalidateDraw()`），只有别的东西（比如滑块自己拖动
时的 `graphicsLayer` 动画）顺带触发的重绘周期，才会让这些 lambda 被重新调用一次。没有库源码，
这条没法进一步坐实，是根据现象反推的最可能解释。

**影响范围**：不只是这次的"透明度"，`TeleprompterSettingsWindow` 用同一个 `tunedGlass()` 的
`blurRadiusDp`（模糊度滑块）大概率是同一个问题——面板打开后，只要不是正在拖某个滑块，这个窗口的
玻璃画面理论上就是"死"的，不会跟着任何后续的参数变化重绘，直到下次触发某种手势/动画。

**还没修**：这是个第三方库（alpha 阶段）层面的重绘失效问题，没有 vendor 库源码（见
`docs/DECISIONS.md` 2026-08-09 条目，不 vendor 整个库），修法需要找一种能在应用层强制触发重绘
的机制，还没设计/验证，等定方向。已经确认过"照抄之前滑块卡顿那次的教训"不适用——那次是节流推送
触发正文重排版开销的问题，这次是完全不重绘，两个不是一回事。

**怎么定位出来的**：先用视频录屏逐帧比对（发现像素完全相同），再加临时 `Log.d` 在两个关键点
（重组时机 vs 实际画 tint 的时机）分别打点，对照真机日志时间戳，才排除了"是不是效果太细微看不出
来"这类猜测，坐实是重绘没有被正确触发。诊断结束后临时日志已删除，不留在代码里。

## 追记（同一天）：上面"tunedGlass() 只在手势期间重绘"这条，已经在应用层修了

根因确认是我们自己代码把 state 在组合阶段拍扁成了普通值，不是三方库不可控——`tunedGlass()`
改成 `@Composable` + `rememberUpdatedState`，绘制阶段闭包内部读 `live*.value`，让这次读取真正
发生在绘制阶段，Compose 快照系统就有东西可以观察了。具体决定见 `docs/DECISIONS.md` 2026-08-15
"`tunedGlass()` 改成 `@Composable`..."条目。改完之后想拿真机日志确认，但手机验证过程中反复被
切到其它 App 前台，没拿到干净的验证数据——**这条目前只是"编译通过+代码逻辑上说得通"，还没有
真机证据证明真的修好了**，跟本文档其它条目"先定位、后修、修完实测确认"的标准不一样，特此注明，
别当成已经完全验证过的结论来用。

## `StateFlow.value` 在 Composable 里直接读，不会让 Compose 知道要在它变化时重新跑这段代码

**现象**：预设列表"默认"行（内容改成"当前实时值"那次修复之后）用户自己录屏证实：调整 Tint
颜色/透明度之后，点这一行的"应用"，画面还是跳回了旧值（不是刚调的值）。反复点了好几次，同样的
结果。

**根因**：`builtInPreset(targets)` 内部直接写 `glassTuningStoreFlow.value.paramsFor(it)`——
`StateFlow.value` 是一个普通的 Kotlin 属性读取，不是 `collectAsState()`/`State.value`，Compose
的快照系统完全看不到这次读取，不会因为 `glassTuningStoreFlow` 变化而让这段代码重新执行。而调用
这个函数的 `PresetSection` 自己也没有 `collectAsState(glassTuningStoreFlow)`——它能重组完全是
靠父级 `GlassTuningScreen`（那边真的 `collectAsState()` 了）重组之后顺带带下来的，但 Compose
的智能跳过机制发现 `PresetSection(targets)` 的唯一参数 `targets` 没变，很可能直接跳过重新执行
`PresetSection` 的函数体——两层加起来，"默认"这一行显示/应用的值，实际上冻结在某次早前的
（很可能是最初默认值附近的）快照上，用户怎么调滑块都不会更新，点"应用"自然是把这份旧快照写回去，
表现得像是"跳回默认"。

**修法**：`PresetSection` 自己也要 `val tuningStore by glassTuningStoreFlow.collectAsState()`，
把这个通过 `collectAsState()` 拿到的、真正被 Compose 追踪的 `State` 值显式传给
`allPresetsFor(store, targets)`/`builtInPreset(store, targets)`，函数内部只用传进来的 `store`
参数，不在函数体内部另外去读 `glassTuningStoreFlow.value`。

**怎么定位出来的**：用户自己录了一段真机操作视频发过来（这次没有让 Claude 自己摸手机，是用户直接
提供第一手证据），逐帧比对"调整 Tint 参数"→"点默认行应用"→"画面变化"这几步，结合代码里
`glassTuningStoreFlow.value` 这个写法直接定位。教训：Compose 里任何"预设/缓存/快照"类的读取，
只要发生在 `.value` 这种普通属性访问上而不是 `State` 对象上，都要检查一下这次读取本身是不是真的
在被追踪的组合范围内，"看起来会重组"跟"确实会重组"不是一回事，父级重组不保证子级函数体一定重新
执行，尤其是子级参数本身没变的时候。

## 预设列表是一份不分入口的扁平存储，新增第二个 restricted 入口时会互相串扰

**现象**：这是在给"提词器三屏各自独立调参"（2026-08-16，见 DECISIONS.md）加第二/第三个
`restrictedIntent` 入口时，写代码过程中主动想清楚、抢在真机复现之前改掉的，不是先出现故障再
定位——但记录下来是因为这类"看起来独立的功能，其实共享了同一份底层存储"的坑，本项目已经不是
第一次踩（参考上面 `glassTuningStoreFlow` 那条），值得单独立一条提醒自己下次加新入口时主动检查。

**根因**：`glassPresetStoreFlow`（`GlassPresetStore.kt`）是**一份全局扁平列表**，不区分"这个预设
是从哪个 `restrictedIntent` 入口保存的"。在只有提词器一个 `restrictedIntent` 入口（`Other`+
`TeleprompterSettingsWindow` 两个 target）的时候，这份列表天然只被一个场景读写，没有暴露问题。
一旦启动页/外观设置也变成 `restrictedIntent` 入口（`GeneralGlassTargets`：`LauncherCard`+
`SettingsPanel`），`allPresetsFor(store, targets)` 原来的实现是"内置默认行 + 全部用户预设"，
不看 `targets` 是否匹配——从提词器面板存的预设，会原样出现在启动页面板的预设列表里，而且因为
它的 `paramsByTarget` 里根本没有 `LauncherCard`/`SettingsPanel` 这两个 key，点"应用"会静默
什么都不做（`applyGlassPreset` 内部 `preset.paramsByTarget[target.name] ?: return@forEach`
直接跳过，不报错也不提示）。

**修法**：`allPresetsFor` 加一步过滤：`presets.filter { it.paramsByTarget.keys ==
targets.map { it.name }.toSet() }`——每个预设保存时，它的 `paramsByTarget` key 集合本来就是
保存那一刻传入的 `targets` 列表（`saveGlassPreset` 就是这么写的），所以用"key 集合是否精确等于
当前这份 targets"天然就能把预设按它诞生的那个 restricted 入口分开，不需要另外加一个"属于哪个
入口"的标记字段。

**教训**：这个项目里凡是"共享底层存储、靠调用方传的 scope 参数限定可见范围"的模式（`GlassTuningStore`
按 `GlassTarget` 分 key 是这样，`GlassPresetStore` 现在也是这样），新增一个新的 scope/入口之前，
要主动检查"读取这份存储的地方，有没有隐含假设'反正只有一个调用方会用到这份数据'"——单一入口时代
不做过滤看不出问题，入口一多立刻炸，而且这种炸法（静默不生效，不报错）比编译错误更难在测试里
发现，最好是加入口的同一次改动里就顺手把过滤加上，不要等真机复现了才回头补。

---

## 单 Activity 内用布尔状态切屏，不接 `BackHandler` 的话系统返回键会直接退出整个 Activity

**现象**：电话拦截功能（`callscreen/CallGuardActivity.kt`）雏形阶段，主屏幕上有"规则管理"
"查看诊断日志"两个按钮，点开后用 `showRules`/`showLog` 两个 `remember { mutableStateOf(false) }`
布尔值控制整屏切换到 `CallGuardRulesScreen`/`CallGuardObserveLogScreen`（`if (showRules) { ...;
return }` 这种写法，不是真正的导航栈）。用户真机实测：在规则管理页设置好一条前缀规则后按系统
返回键，没有回到电话拦截的主屏幕，而是直接退出了整个 Activity，回到了启动页。

**根因**：`showRules`/`showLog` 只是普通的 Compose 状态，不是 `NavController`/`Fragment` 那种
系统返回键原生认识的导航栈条目。这种"整屏切换靠一个 Boolean"的写法在 UI 上看起来跟真正的多页面
导航一样，但系统返回键/手势默认只会盲目按 Activity 的默认行为处理——没有任何东西告诉它"现在逻辑上
在规则管理页，应该先退回主屏幕"，所以直接触发了 Activity 自己的默认返回行为（`finish()`）。这类
bug 的隐蔽之处在于：应用内自己画的"返回"箭头按钮（`IconButton` 调 `onClose = { showRules = false
}`）是完全正常的，因为那是显式绑定的 `onClick`；只有系统返回键/手势这条路径会露出问题，如果测试时
习惯性只点应用内按钮、不测系统返回键，很容易完全测不出来。

**怎么定位出来的**：用户真机操作时随手一按系统返回键就复现了，不是刻意去测这条路径——但项目里
其它地方（`TeleprompterActivity.kt`、`ui/` 目录一堆 List/Config 页面）早就对这个模式有正确处理
（`BackHandler(enabled = ...) { screen = ... }`），说明这是本项目已经踩过、也已经建立了标准解法的
一类坑，这次只是新功能包（`callscreen/`）第一次接入多屏状态切换时忘了照抄这个模式。

**修法**：`CallGuardScreen` 顶部加 `BackHandler(enabled = showLog || showRules) { showLog = false;
showRules = false }`——只在子屏幕打开时启用这个拦截，回到主屏幕后这个 `BackHandler` 自动禁用
（`enabled = false`），系统返回键交还给 Activity 默认行为（此时才应该真的退出）。

**教训**：项目里任何"用布尔/枚举状态在同一个 Activity 内切换整屏内容，不用 `Navigation`/多
Activity"的写法（这个项目里已经是主流模式，教程/文档场景、提词器、电话拦截都这么干），只要多于
一个"层级"（主屏幕 + 至少一个子屏幕），新增子屏幕的同一次改动里就必须顺手加 `BackHandler`，不能
只测应用内的返回箭头按钮就当作导航测试完整——系统返回键/手势是完全独立的一条路径，必须单独验证。
`grep -rn "mutableStateOf(false)" 结合看有没有对应 if (xxx) { ...; return }` 这种整屏切换模式，
配合 `grep -rn "BackHandler"` 交叉检查，可以在写新功能时快速自查有没有漏。

---

## 提词器正文滑块拖动卡顿：`LiquidSlider` 的下发节流对"重排代价大的滑块"不够用

**现象**：编辑页设置面板里拖"正文默认字号""整体缩放""编辑时显示缩放""行距""内容宽度"这几个
滑块（都在 `TeleprompterEditorScreen.kt` 的"正文"分节里，副标题字号同理），文档长一点就明显掉帧、
手感发滞，跟同一个面板里"滚动速度""透明度""模糊度"这几个滑块（手感一直很跟手）形成鲜明对比。

**根因**：编辑页正文用的是普通 `Column` + `Modifier.verticalScroll`，不是 `LazyColumn`——不管
视口多大，全部章节永远同时被组合/布局，不做懒加载。`fontSizeSp`/`overallScale`/`lineHeightMultiplier`
这类参数一旦变化，会触发全部同时在场的章节富文本一起重新排版，单次重排一个内容多、样式复杂的
章节本身就能到 80-120ms（代码里量过）。`LiquidSlider` 自己早就有节流机制：拖动手势本身
（滑块的视觉位置/thumb）走 `DampedDragAnimation`，每帧都同步更新，从来不卡；但真正下发给调用方
的 `onValueChange`（也就是触发重排的那一下）走 `pendingPushValue` 侧通道，由一个常驻 `LaunchedEffect`
攒够 `delay(...)` 时间才 flush 一次——这个节流间隔原来是全局共用一个硬编码值（2026-08-13 时已经
从 100ms 调到 200ms 过一次，注释里当时就留了"不够再调"），对滚动速度/透明度这类"改一下只重画自己
这块面板"的滑块完全够用，但对会牵连全部章节重排的正文类滑块，200ms 还是不够，两次重排之间还是会
撞上，表现为掉帧。

**怎么定位出来的**：用户反馈"正文这几个滑块卡，其他滑块不卡"——先反查 `LiquidSlider.kt` 全文，
发现视觉位置和下发这两条路径本来就是分开节流的（说明卡顿不在"滑块本身响应慢"，而在"下发之后
调用方做的事情太贵"），再反查提词器编辑页正文的渲染方式，确认是非懒加载的 `Column` 而不是
`LazyColumn`，才确认瓶颈在"重排代价"而不是"滑块机制"本身。

**修法**：给 `LiquidSlider` 加一个可选参数 `pushThrottleMs: Long = 200L`（默认值不变，app 里
其它调用方——播放页滚动速度、面板透明度/模糊度、背景编辑器的图片调节等——一律不受影响），只在
`TeleprompterEditorScreen.kt` 里正文相关的几个滑块（`fontSizeSp`/`overallScale`/`editZoomScale`/
`lineHeightMultiplier`/`contentWidthFraction`/副标题 `fontSizeSp`）传 `pushThrottleMs = 2000L`
（200ms 的 10 倍，按用户"先加10倍，再加百倍"的要求分级加码，不够再往 20000ms 走）。滚动速度/
面板透明度/模糊度这三个不牵连正文重排的滑块保持默认 200ms 不变。

**教训**：像 `LiquidSlider` 这种被全 app 共用的底层组件，"下发节流间隔"不该是一个全局共用的
硬编码常量——不同调用方背后触发的代价可以差两个数量级（改一块本地状态 vs 重排一整篇富文本文档），
用同一个值要么伤了便宜场景的手感、要么不够贵场景用。正确做法是把节流间隔做成调用方可传的参数，
默认值照顾大多数"便宜"场景，只有明确知道自己贵的调用方主动加大——不要为了让"最贵的那个场景"
不卡，就把全 app 所有滑块都拖慢。

### 更新（2026-08-16，同一天）：加大节流间隔的方向是错的，用户真机反馈后撤回

上面这条写完、出包装机后，用户真机实测反馈：把这几个滑块的 `pushThrottleMs` 从 200ms 提到
2000ms 之后，**拖动过程中正文完全不跟着动了，只有松手那一下才跳一次**；而之前 200ms 默认值下，
拖动虽然卡卡顿顿，但正文是全程跟着手指走的。用户明确纠正："应该是随时跟着动才对，频率越高越对"。

**教训**：这里的 `LiquidSlider` 节流机制本质是"每隔 N 毫秒采样一次最新值"，不是"平滑消抖"——
拖动动作通常在 1-2 秒内完成，一旦节流间隔（2000ms）跟拖动总时长同一个量级，采样窗口内可能一次
都不落在拖动进行中，观感上就是"冻结到松手才跳一下"，跟真正的"卡顿但跟手"是两种完全不同的用户
体验，不能混为一谈去无脑加大节流间隔。"先做出流畅再说，大胆往上加"这句话说的"流畅"，指的是
拖动本身的手感连贯（跟手、无冻结），不是"减少重排次数换取整体帧率"——这两者在"全部章节非懒加载
同时重排"这个架构约束下是互斥的：调低节流间隔=更跟手但更容易掉帧碰撞，调高=帧率问题缓解但会
出现"冻结-跳变"，用户明确要前者。已把这 6 个滑块的 `pushThrottleMs` 覆盖值全部撤回，改回默认
200ms（宁可拖动过程掉帧也要保持跟手）。

**如果 200ms 的卡顿感仍然不满意，真正的根治方向**：不是继续调节流数字，而是让"重排代价"本身
变小——比如拖动过程中只对当前视口内可见的章节应用新值实时重排（`chapterHeights` 映射 + 
`scrollState.value` 已经在这个文件里现成可用，能算出哪些章节在视口内），不可见章节的重排推迟到
`onValueChangeFinished`（松手）时一次性补上。这个方向目前还没做，属于比调一个参数大得多的改动，
等用户确认 200ms 版本是否已经够用、要不要投入做这个再决定。

### 第二次修复（2026-08-16，同一天）：视口裁剪 —— 拖动时只重排屏幕内看得见的章节

撤回节流间隔改动、恢复跟手手感之后，用户又问"确实恢复了之前的手感，但是怎么才能让它不卡顿呢，
更加的跟手，帧率刷新的更快"——也就是要同时做到"跟手"和"不卡"，而这两者在"节流间隔"这一个旋钮上
是互斥的（调低=更跟手但更容易掉帧撞车，调高=帧率问题缓解但会冻结）。真正的杠杆不是"多久推一次"，
而是"每次推送要重排多少章节"——非懒加载的 `Column`+`verticalScroll` 意味着每次字号/整体缩放/
行距变化都会让全部同时铺开的章节一起重排，重排的章节数量是跟文档长度成正比的，不是常数。

**修法**：不改变"多久推一次"（维持默认 200ms），改变"每次推送时实际重排哪些章节"——正在拖动这
5 个正文相关滑块（正文默认字号/整体缩放/编辑时显示缩放/行距/副标题字号）期间，只有当前视口内
（含上下各一屏缓冲）看得见的章节才用最新值实时重排，视口外的章节冻结在拖动开始那一刻的值，直到
松手才一次性补齐。具体实现：
- `TeleprompterEditorScreen` 顶层新增 `bodyDisplayDragActive`（跨 Column 边界共享，因为章节循环
  和 `TeleprompterSettingsWindow` 调用是兄弟节点不是父子节点，状态必须声明在两者共同的外层作用域）。
- `TeleprompterSettingsWindow` 新增 `onBodyDragStart`/`onBodyDragEnd` 两个可选回调参数（默认空
  实现，不影响其它调用方），接到上述 5 个滑块的 `onValueChange`/`onValueChangeFinished` 上。
- `frozenBodyDisplay = remember(bodyDisplayDragActive) { if (bodyDisplayDragActive) Triple(settings,
  zoomScale, subtitleStyle) else null }`——利用 `remember` 的 key 机制，`bodyDisplayDragActive`
  刚变 true 的那一刻捕获一次当时的值，此后 key 不再变，天然冻结到松手（key 变回 false）为止，不用
  额外写 `LaunchedEffect` 手动同步。
- 视口内/外判定：复用已有的 `chapterHeights`（`onGloballyPositioned` 累积的每章节高度）+
  `scrollState.value`，新增 `viewportHeightPx`（`onSizeChanged` 测出来的可视区高度），算出
  `[scrollState.value - viewportHeightPx, scrollState.value + viewportHeightPx*2]` 这个范围
  （上下各留一屏缓冲，防止拖动过程中稍微滚动一下就露出没重排的旧文字），落在范围内的章节 id 收进
  一个 Set，只在 `bodyDisplayDragActive` 为真时才计算（空闲时每个章节直接用 live 值，不用查表）。
- 正在编辑（`isFocused`）的章节永远强制用 live 值，不参与冻结——它走的是 `BasicRichTextEditor`
  可编辑路径，WYSIWYG 不能冻结，冻结了会造成"看到的字号跟实际输入的不一致"。
- `contentWidthFraction`（内容宽度）滑块没有接入这套机制：它是用一个共享的外层
  `.fillMaxWidth(contentWidthFraction)` 包住整个章节列表，不是每章节独立套宽度约束，天然没法只给
  部分章节应用新宽度、部分维持旧宽度——这个滑块目前还是"节流 200ms、每次全文重排"的旧行为。

**教训**：遇到"响应要及时"和"处理要便宜"这对矛盾时，第一反应不该是在"多久处理一次"这个参数上
反复横跳（这正是第一次修复踩的坑），而应该去看"每次处理的工作量能不能本身就更小"——这次能做到，
是因为屏幕上任意时刻真正被用户看见的章节数量是有上限的（一屏内容），而文档总章节数没有上限，
两者之间的差距就是这次省下来的重排量。这类"总量不可控、可见范围可控"的场景（长列表、长文档、大
表格……）大概率都能套用这个"只处理可见部分，其余推迟到交互结束"的思路，即使受限于其它约束
（这里是文本选中手柄拖动，见上面已有的 LazyColumn 决策记录）用不了框架自带的懒加载机制，也可以
像这次一样手动做一层"可见性门控"。

**还没真机验收**：已编译出包装机（`app-gkd-debug-20260816-213237-viewport-cull-body-sliders.apk`），
需要用户重新测试拖动手感——重点看两条：①屏幕内文字是不是依然跟手；②卡顿感是否比只用默认 200ms
节流时明显减轻（如果长文档、屏幕外章节很多时改善明显，说明这个方向是对的；如果几乎没感觉，可能
还需要把视口裁剪也用到 `contentWidthFraction`，或者进一步把节流间隔本身也调低，两个杠杆一起用）。

---

## 拖选中光标手柄不跟手、乱跳 —— 根因是"防抖误判"引发的重组风暴，不是手势本身的问题

**现象**：编辑页正文，长按选中一段文字后拖动前/后两个选区手柄，手柄不跟手指走，隔一会儿突然跳到
新位置，跟手指实际位置对不上。用户反馈"有很多 bug，比如...他不跟着我手走，自己乱跳"。

**怎么定位出来的**：`BasicRichTextEditor` 来自第三方库 `com.mohamedrejeb.richeditor`（远程依赖，
手柄拖动手势本身在库内部实现，本项目源码够不到），没法直接在手势代码里加日志。改为两路证据交叉：
1. 在 `state.selection` 上加 `LaunchedEffect` 打日志，记录每次选区变化的 start/end/textLen，让
   用户复现后从"查看诊断日志"复制出来。日志显示选区数值本身是**平滑递增/递减**的（比如
   `end=159→160→162→166→167→169→176→177→187→216`），没有乱跳——说明选区这份数据本身是对的，
   拖动手势的采样也是对的。
2. 用户同时发来一段真机录屏，逐帧截取比对（`ffmpeg` 不可用，改用 Python + OpenCV 按 0.4s 间隔
   抽帧）：11.0s 和 12.2s 两帧里，高亮的选中文字范围确实在扩大（跟日志吻合），但手柄那个圆圈
   图标在这 1.2 秒内完全没挪地方，卡在同一个像素位置——**选区数据在实时更新，手柄的视觉位置却没
   跟上**，直接坐实"这不是手势采样的问题，是手柄自己的重绘被什么东西卡住了"。
3. 顺着"重绘被卡住"这条线回看同一份日志，发现一个此前没意识到的现象：几乎每次"selection
   changed"后面都紧跟一次"debounce armed"，800ms 后又跟一次"onChapterContentChanged fired"——
   也就是说，**单纯拖动手柄改选区（没有输入任何文字，textLen 全程没变过），也在不断触发"这是一次
   真实编辑"的判断，进而反复触发完整的 `state.toHtml()` 序列化 + `vm.onChapterContentChanged`
   持久化写入**。

**根因**：`LaunchedEffect(state.annotatedString) { ... }`（用来给"打字防抖后自动保存"计时）原来
直接把 `state.annotatedString` 整个对象当 key。但 `state.annotatedString`（`RichTextState` 内部，
应该是按光标位置拆分/合并 span 一类的实现细节，具体机制在库内部，够不到源码确认）在**纯粹移动
选区/光标、完全没有编辑文字内容**的情况下也会被判定为"变了"。于是每次拖手柄改一次选区，都被这个
本该只在"真的编辑了文字"时触发的 `LaunchedEffect` 误判成一次真实编辑，800ms 后老老实实执行一次
完整的 HTML 序列化 + `vm.onChapterContentChanged`——而这个调用会整个替换 `TeleprompterLibrary`
这个 StateFlow（`updateTeleprompterLibrary` 内部整体 `.copy()`），引发大范围重组。这个重组跟手柄
自己的位置重绘抢帧，就是真机录屏里"选区在变、手柄图标却没跟上"的直接原因。

**修法**：把这个 `LaunchedEffect` 的 key 从 `state.annotatedString`（整个对象，选区一动就可能变）
改成 `state.annotatedString.text`（纯文本字符串，只有真的插入/删除文字才会变）。同时把内部那句
"是不是等于刚 hydrate 完那份快照"的判断也从整体对象比较改成文本比较
（`state.annotatedString.text == lastHydratedSnapshot?.text`）。这样单纯移动选区/光标不再触发这
一整串"防抖→序列化→持久化→大范围重组"，真正的文字编辑（text 会变）依旧正常触发，行为不变。
代价：纯样式改动（选中一段文字点粗体，一个字都没打）不再被这个"打字防抖"实时捕获——但失焦时的
兜底判断（`onFocusChanged(false)` 分支）仍然比较完整的 `state.annotatedString`（不是只比文本），
切走这个章节或退出编辑时会把样式变化正常补存下来，不会真的丢，只是不再"随手一点就立刻存"。

**教训**：
1. 第三方库的手势/交互内部实现够不到时，不代表没法定位问题——把"这一层能看到的数据"（这里是
   `state.selection` 的值序列）和"用户能看到的现象"（录屏里手柄的视觉位置）分别记录下来，交叉
   比对，往往能把"库本身的手势 bug"和"我们自己代码引发的连锁反应"区分开——这次现象表面看像是
   "库的手柄拖动实现有问题"，但真实根因其实完全在本项目自己的防抖逻辑里，不该找错方向去怀疑一个
   够不到源码、没法直接验证的第三方库。
2. `LaunchedEffect`/`remember` 拿一个复杂对象（尤其是来自第三方库、内部到底怎么实现"相等"不完全
   透明的对象）当 key 时，要留意它的"变化"粒度是不是真的等于"我们关心的那件事变了"——这次
   `state.annotatedString` 的变化粒度比"文字内容变了"更细（连纯选区移动都算），如果只关心文字
   内容本身，应该直接 key 在更窄、语义更明确的字段（`.text`）上，而不是整个对象。
3. 真机录屏在这类"数值对、视觉不对"的 bug 上是不可替代的证据——纯看日志会得出"选区数据完全正常"
   的（不完整）结论，只有把日志和录屏逐帧对照，才能确认问题出在"数据更新了但视觉没跟上"这个环节，
   而不是数据本身。`ffmpeg` 在这台机器上不可用时，Python + OpenCV（`cv2.VideoCapture` 按秒/毫秒
   定位帧 + `imwrite` 导出 PNG）是现成的替代抽帧方案。

**还没真机验收**：已编译出包装机
（`app-gkd-debug-20260816-215341-fix-selection-drag-autosave-storm.apk`）。需要用户重新拖手柄
测试：①手柄是否跟手了；②诊断日志里"debounce armed"是否只在真正打字/删字时出现，纯拖选区不再
触发。选区变化的诊断日志（`LaunchedEffect(state.selection)`）暂时保留，方便这次复测直接对照，
后续确认没问题了可以删掉。

### 补充（2026-08-16，同一天）：诊断用的日志本身也在拖手柄时卡主线程，已删

上面那条修完、装机后，用户反馈"还是只能往下拖动几个字，再拖就拖不动了，每次只能拖动几个，并不
跟手丝滑"——不是"跳"了，是拖到一半直接失去响应，隔一下才能再拖一点。这跟"防抖误判"那条不是同一个
症状（那条是"数据对、视觉跳"，这条是"拖着拖着彻底没反应"），得往别处找。

**根因**：为诊断上一条 bug，在 `state.selection` 上加了一个 `LaunchedEffect` 每次选区变化就调
`TeleprompterObserveLog.log(...)`——而这个 `log()` 函数内部 `sharedDir.resolve(LOG_FILE_NAME)
.appendText(...)` 是同步磁盘写入，没有切到 IO 线程，跑在调用方所在的协程（`LaunchedEffect` 默认
挂在 Compose 的主线程 dispatcher 上）。拖手柄一次连续手势里选区会高频变化，等于**每次选区变化都在
主线程上做一次同步磁盘 I/O**——这个 I/O 造成的卡顿/阻塞，恰好会打断安卓底层正在进行中的触摸手势
识别，表现出来就是"拖一点就没反应了，得松手重新来"。这是这次诊断本身（为了修上一条 bug 加的日志）
反过来造成的新问题，不是本来就有的。

**修法**：诊断目的已经达到（上一条 bug 已经靠这份日志 + 录屏定位到了），直接删掉这个
`LaunchedEffect(state.selection)` 日志块，不再逐 tick 记录选区变化。

**教训**：诊断用的日志本身不是免费的，尤其是"挂在高频触发的状态变化上、又做了同步 I/O"这种组合——
`TeleprompterObserveLog.log()` 这个工具函数从设计上就是同步写盘（其它调用点如 hydration/debounce
armed/onChapterContentChanged 都是低频事件，之前没暴露出这个问题），第一次被绑到"一次手势内可能
触发几十次"的信号源上，问题立刻放大到肉眼可见。以后凡是要在拖动/滑动这类高频手势相关的状态上加
诊断日志，先确认这份日志本身的开销（尤其是有没有同步 I/O），不要默认"打个日志而已，没什么成本"。

**还没真机验收**：已编译出包装机（`app-gkd-debug-20260816-220326-remove-per-tick-selection-log.apk`）。
需要用户重新拖手柄测试：这次拖动是否能连续跟手，不再"拖几个字就断"。

### 后续（2026-08-17）：根因很可能在库本身，已升级到正式版 1.0.0；附"查最新版本号"这一步自己的坑

删掉诊断日志后用户重测，症状变得更具体也更明确了：**"我往后拖，他往前跳，我往前拖，他往后跳，
有时候还随机跳"**——不是延迟/滞后，是方向反了。

查上游 `compose-rich-editor` 的 release notes，`1.0.0` 正式版（2026-07-20）里有两条直接对应的修复，
本项目当时钉的 `1.0.0-rc14`（2026-04-26）里都没有：
- `fix: prevent drag selection from jumping to the next line at line edges`（PR #748）——跟用户
  的描述几乎逐字对应。
- `fix: diff-based edit pipeline, selection stability, parser round-trips`（PR #746）。

结合前面已经确认的"数据层正确、渲染层滞后"（选区偏移值平滑正确，手柄绘制位置对不上），而渲染层是
库的私有实现、应用层够不到——判断继续在应用层修补是无用功，直接升级到 `1.0.0`。零源码改动，编译
直接通过（1.0.0 唯一的 breaking change 是移除 iosX64 target，本项目只有 Android，无影响）。

**顺带踩到的坑：查"这个库的最新版本号"这件事本身就不可靠，三个来源两个是错的。**
1. Maven Central 的 solr 搜索接口（`search.maven.org/solrsearch/select?...`）返回的最新版本是
   `1.0.0-rc13`——**连本项目当时正在用、且能正常解析下载的 rc14 都没有**。这个索引滞后得很厉害，
   完全不能作为版本依据。
2. 权威的 `repo1.maven.org/maven2/.../maven-metadata.xml` 里，版本列表本身是全的（rc14、1.0.0
   都在），但它的 `<latest>` 和 `<release>` 两个标签都写着 `1.0.0-dev01`——而 `1.0.0-dev01` 的实际
   发布时间是 **2024-04-24，两年前的 dev 构建**，只是因为版本字符串排序才排在列表最后。如果照着
   这两个标签升级，会把项目降级到一个两年前的 dev 快照上，而且这个错误相当隐蔽（版本号长得像"更新的
   1.0.0 系列"）。
3. 真正可靠的做法：拿到完整版本列表后，**逐个 HEAD 请求候选版本的 `.pom` 文件，读 `Last-Modified`
   响应头拿真实发布时间**，再结合 GitHub release notes 判断哪个是要的。这次就是靠这一步才发现
   `1.0.0-dev01` 是 2024 年的、`1.0.0-cmp-1.12.0-beta03`（2026-08-10，日期最新）其实是为 Compose
   Multiplatform 1.12 出的变体（本项目用 Compose 1.11.2，不能用），最终锁定 `1.0.0`（2026-07-20）。

**教训**：确认第三方依赖"最新版本是多少"时，不要只看一个来源，尤其不要直接信 `maven-metadata.xml`
的 `<latest>`/`<release>` 标签，也不要信搜索接口的索引结果——前者可能被非常规版本号（dev/变体后缀）
带偏，后者可能严重滞后。以"发布时间"而不是"版本字符串排序"作为判断依据，用 `Last-Modified` 交叉
验证，成本很低但能挡住这类错误。

**还没真机验收**：已出包（`app-gkd-debug-20260817-081033-richeditor-1.0.0-upgrade.apk`），装机时
手机已断连，需要用户自己传装。验收顺序（按重要性，不要颠倒）：
1. **先验证老文档没炸**（比手柄优先级高）：拿现有真实文档、尤其是有两端对齐和手动字号的那些，
   走一遍加载，确认字号/行距/对齐都没跑样；再编辑保存、重新打开，确认内容和格式没丢。
   1.0.0 有两条会直接影响 HTML 导入行为的修复，本项目的文档正是 Word→OOXML→HTML 导入的，风险点
   明确：`fix: drop white/transparent backgrounds on HTML import`（#770）、
   `fix: resolve relative text-indent units to sp on HTML import`（#724），另外
   `fix: combine nested text decorations instead of replacing it`（#767）会改变嵌套样式的合并行为。
2. 老文档确认没问题后，再测手柄拖动是否跟手、方向是否正确。
3. 如果升级后手柄仍然有问题：重新做一次"选区值 vs 手柄实际绘制位置"的对比诊断，但
   **绝对不要再用 `TeleprompterObserveLog.log()` 那个同步写文件的 logger**（见上一条补充，它自己
   就会阻塞 UI 线程、打断触摸手势），要用不阻塞的方式输出。

---

## `LaunchedEffect` 用调用方传进来的 lambda 当 key —— 液态开关拖动时被外部状态拽回去、来回乱跳

**现象**：提词器设置面板里"加粗"那个液态开关（`LiquidToggle`），用手指拖动滑块时会乱跳——拖到
一半自己弹回起点，跟手指对不上。用户要的行为很明确："完整过程只触发一次"，即整个拖动手势期间不该
被任何东西打断，松手才提交一次。

**根因（两个，第一个是主因）**：
1. **`LaunchedEffect(selected)` 把调用方传进来的 lambda 当成了 key。** 调用点写的是
   `selected = { subtitleStyle.bold }`——一个捕获了 `subtitleStyle` 对象的 lambda，那个对象一被替换，
   Compose 就会给出一个**新的 lambda 实例**。而这个设置面板替换得非常频繁：副标题字号滑块就在这个
   开关正上方，每次节流推送都会整体替换一次 style 对象。key 一变 `LaunchedEffect` 就重启，而重启后
   的 `snapshotFlow` **一定会立刻把当前值重新发一次**（首次发射）——于是拖到一半时，它读到还没变的
   `selected()`（target=0f），看见 `fraction` 正停在 0.4 这种中间值，就调 `animateToValue(0f)`，
   在用户手指底下把滑块弹回了起点。这就是"乱跳"。
2. **没有 `isDragging` 保护。** 就算 key 修对了，拖动过程中任何一次外部状态变化传回来，依然会跟
   正在进行的手势打架。

**怎么定位出来的**：先确认 `onSelect` 其实**已经**只在 `onDragStopped` 里调用一次了（不存在"每移动
触发一次"的提交），所以问题不在提交时机，而在"有别的东西在拖动过程中改滑块位置"。顺着这个方向看
两个 `LaunchedEffect`，发现同文件里紧挨着的两个效果，一个用 `dampedDragAnimation` 当 key（稳定），
另一个用 `selected` 当 key（lambda，不稳定）——一眼就对不上。再对照兄弟组件 `LiquidSlider.kt`：它
用的正是 `LaunchedEffect(dampedDragAnimation)` + `isDragging` 双重保护，而且文件头里明确记着它当年
踩过的同一类坑（"settings-panel-sliders-snap-back-on-release"）。**同一个坑在滑块上修过、在开关上
从来没修过**，这个不一致本身就是最强的线索。

**修法**：`LaunchedEffect(selected)` → `LaunchedEffect(dampedDragAnimation)`（组件生命周期内稳定），
并补上 `isDragging`（`onDragStarted` 置真、`onDragStopped` 在 `onSelect` 之后置假，保证回调执行期间
保护还在），外部同步分支加 `if (isDragging) return@collectLatest`。改完后语义就是用户要的："整个
拖动过程中滑块只跟手指走，松手时 `onSelect` 提交一次"。

**教训**：
1. **`LaunchedEffect`/`remember` 的 key 绝对不要用调用方传进来的 lambda**（`() -> T` 这种 supplier
   参数尤其危险）。lambda 的实例身份取决于它捕获了什么、外层重组了几次，跟"我关心的那个值变没变"
   完全是两回事；而 `LaunchedEffect` 重启 + `snapshotFlow` 重新首发这个组合，会把"重启"直接变成一次
   **对状态的实际写入**，后果远不止多跑一遍。要么用稳定对象当 key（这里是 `dampedDragAnimation`），
   要么用 `Unit`，让 `snapshotFlow` 自己负责监听变化——它本来就是干这个的，不需要靠 key 来触发。
   这跟同一天前面那条「拿整个 `state.annotatedString` 当 key」是同一类错误的两个变种：**key 的变化
   粒度必须等于"我关心的事情变了"，不能更粗也不能更细。**
2. 一个组件出了问题，先去看它的兄弟组件是怎么写的。这个项目里 `LiquidSlider`/`LiquidToggle` 是同源
   移植、结构几乎一一对应，两边写法不一致的地方，基本就是 bug 所在——`grep` 一下两个文件里
   `LaunchedEffect(` 各自的 key 就能看出来，比读一遍渲染逻辑快得多。

**还没真机验收**：已出包（`app-gkd-debug-20260818-162826-fix-liquidtoggle-jump.apk`），装机时手机
断连，需要用户自己传装。验收：拖动"加粗"开关，确认①全程跟手不回弹；②松手后状态正确、只提交一次；
③单击（不拖）依然能正常切换。

---

## XML 注释里不能出现连续两个减号 `--`，AGP 只会报一句没有行号的 "Error parsing"

**现象**：新建 `app/src/pub/AndroidManifest.xml` 后构建失败：
```
Execution failed for task ':app:processGkdPubDebugMainManifest'
> ManifestMerger2$MergeFailureException: Error parsing F:\gkd-fork\app\src\pub\AndroidManifest.xml
```
只说"解析失败"，**没有行号、没有列号、没有说到底哪里不对**，光看这条信息完全无从下手。

**根因**：文件里写了这样的分隔注释：
```xml
<!-- ---- 虚拟定位 ---- -->
```
XML 规范里注释内容**不允许出现连续两个减号 `--`**（因为 `-->` 是结束标记，`--` 会让解析器歧义）。
用一串减号画分隔线是很自然的写法，但在 XML 里非法。注意 `<!-- ==== X ==== -->`（等号）是合法的，
中文破折号 `——`（U+2014）也是合法的——只有 ASCII 减号连着两个才炸。

**怎么定位出来的**：AGP 那条报错没有任何位置信息，与其盯着看，不如直接拿一个标准 XML 解析器去问：
```bash
python -c "import xml.dom.minidom; xml.dom.minidom.parse(r'app/src/pub/AndroidManifest.xml')"
# PARSE ERROR: not well-formed (invalid token): line 47, column 15
```
一秒钟就拿到了精确行列号。

**教训**：凡是 AGP/Gradle 报"某个 XML 文件解析失败"但不给位置的，第一反应不该是逐行肉眼看，而是
直接用 Python 的 `xml.dom.minidom`（这台机器上现成可用，`ffmpeg` 那次也是同样思路找的替代工具）
跑一遍拿行列号。构建工具链对第三方文件的报错质量普遍很差，绕过它、用专门的解析器去问，比在它给的
信息里硬猜快得多。顺带记住 XML 注释的这条限制：**画分隔线用 `=` 不要用 `-`**。

---

## 想让 git 历史"干净"，删文件必须在 `git init` 之前——晚一步就永远留在历史里

**现象**：公开版（`F:\gkd-public`）刻意不复制私人版的 `.git`，就是为了让历史里不存在"去广告""虚拟
定位"的任何源码（否则 `git log -p` 一把就能全还原，等于没删）。建仓时也确实先删源码再
`git init`。但验证备份 bundle 能不能还原时顺手查了一下历史，发现里面**仍然躺着三个被删功能的
文件**：`app/src/test/.../mockloc/MockLocationCoordTransformTest.kt`（坐标纠偏算法）、
`app/src/main/aidl/.../shizuku/CommandResult.aidl`、`IUserService.aidl`。

**根因**：删除动作分了两批，中间隔着 `git init`。第一批（`mockloc/` 包、`a11y/`、`MainActivity` 等
主体源码）在建仓前删的，干净；第二批是**编译报错之后才发现需要删的**——单元测试是跑测试时才暴露，
AIDL 是 `assembleGkdDebug` 阶段 javac 报"找不到 CommandResult"才暴露。这两批之间已经
`git init` + 首次提交了，于是第二批文件"先被提交进历史、再被删除"，`git log` 里永久留档。

**怎么定位出来的**：不是靠肉眼 review，是**验证备份时顺手做的反向检查**——
`git log --all --oneline -- "**/*mockloc*"`。当时的目的只是确认 bundle 能还原，结果发现"公开版历史里
mockloc 出现 2 次、shizuku 出现 2 次"，跟预期的 0 次对不上，再用
`git log --diff-filter=A --name-only` 才定位到具体是哪三个文件。**如果只验证"bundle 能不能 clone
出来"，这个问题永远发现不了。**

**修法**：仓库很新（7 条提交）且没有远程，直接用孤儿分支重建成单条干净历史：
```bash
git checkout --orphan clean-main && git add -A && git commit -m "..."
git branch -D master && git branch -m master
git gc --prune=now          # 让旧对象真正落盘消失，否则 bundle 里可能还带着
```
原来 7 条提交的说明内容并入新的单条提交信息，实质信息没丢（而且 `docs/DECISIONS.md` 本来就有完整
记录）。重建后重新生成 bundle 并再次反向核对，确认 0 次。

**教训**：
1. **"物理删除 + 干净历史"这类需求，删除必须一次性删干净再建仓。** 而"一次性删干净"实际上做不到
   ——编译器会分好几轮才把依赖暴露完（Kotlin 编译一轮、javac/AIDL 又一轮、单元测试再一轮）。所以
   正确顺序是：**先把项目完整编译通过（含测试），确认再没有要删的，最后才 `git init`**。
2. **备份验证不能只验"能不能还原"，还要验"还原出来的东西对不对"。** 这次就是靠"还原后反查历史里
   有没有不该有的东西"才发现问题的。同理，以后验 APK 也不能只看"能不能装"，要解包核对。
3. 重建历史后记得 `git gc --prune=now`，否则悬空对象可能仍被打进 bundle。
