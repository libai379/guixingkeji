# 架构

**这份只讲"整体是怎么搭起来的"**：分层、边界、数据流向、以及那些违反了就会出 bug 的不变量。

跟其它文档的分工（**内容刻意不重叠，不要互相复制**）：
- 改哪个文件、数据存在哪 → `MAP.md`
- 为什么这么设计、否决过什么 → `DECISIONS.md`
- 踩过什么坑 → `PITFALLS.md`
- 液态玻璃的效果链/参数细节 → `GLASS.md`

---

## 一句话

这是 GKD 的 fork，在**完全不改动上游内核**的前提下，往同一个 APK 里塞了四个自有功能。
上游内核和自有功能**几乎不互相调用**，只共用最底下那层基础设施（Application、存储原语、主题、工具函数）。

## 一、两个世界

理解这个项目的第一件事：包里其实住着**两套互不往来的代码**。

```
                    ┌─────────────────────────────────────┐
                    │  LauncherActivity（桌面图标入口）    │
                    │  五张卡片，纯分发，不含业务逻辑       │
                    └──────┬───────────────────┬──────────┘
                           │                   │
          ┌────────────────┘                   └────────────────┐
          ▼                                                     ▼
┌───────────────────────┐                     ┌────────────────────────────────┐
│  世界 A：GKD 上游内核  │                     │  世界 B：本项目自有功能         │
│  （去广告，仅私人版）   │                     │                                │
│                       │                     │  提词器   TeleprompterActivity  │
│  MainActivity         │                     │  定位修改 WebToolActivity       │
│  a11y/ 无障碍规则引擎  │                     │  电话拦截 CallGuardActivity     │
│  service/ 后台服务     │                     │  虚拟定位 MockLocationActivity  │
│  data/ db/ 订阅与规则  │                     │           （仅私人版）          │
│  shizuku/ notif/      │                     │                                │
│  ui/ 40+ 个界面        │                     │  各自独立成包，互不引用          │
└───────────────────────┘                     └────────────────────────────────┘
                           │                   │
                           └────────┬──────────┘
                                    ▼
                    ┌─────────────────────────────────────┐
                    │  共用基础设施（唯一的交汇点）         │
                    │  App.kt / store 存储原语 / ui.style  │
                    │  主题 / util 工具 / glassui 视觉层    │
                    └─────────────────────────────────────┘
```

**这个切分是本项目最重要的结构特征**，它解释了很多事：

- **为什么"只加不改"能守住**：世界 B 全是新增文件，跟世界 A 没有调用关系，同步上游时不会冲突。
  实测截至 2026-08-20：新增 85 个文件，只修改了 7 个（清单见 `MAP.md` 第一节）。
- **为什么公开版能把世界 A 整个物理删除**：删掉它不会让世界 B 编译不过——除了那几个"混着内核代码"
  的共用文件需要就地裁剪（`App.kt`、`SettingsStore`、`Toast.kt` 等，清单见 `DECISIONS.md` 2026-08-20）。
- **为什么 GKD 误点启动页那个 bug 修起来别扭**：世界 A 的规则引擎会对**任何应用**生效，
  包括本应用自己的世界 B 界面。两个世界在运行时是能撞上的，虽然代码上不互相调用。

## 二、进程与入口拓扑

单进程、多 Activity，**没有用 Navigation 组件**。

- **`LauncherActivity` 是桌面图标的落点**（`MAIN`/`LAUNCHER`），不是上游的 `MainActivity`。
  它只做分发：五张卡片各自 `startActivity`，加一个齿轮进 `AppearanceActivity`。
- **每个功能一个 Activity**，功能之间不跳转，只能退回启动页再进另一个。
- **Activity 内部的多屏切换用布尔/枚举状态，不是导航栈**（提词器的 库/编辑/播放/日志 四屏、
  电话拦截的 主屏/规则/日志 都是这个模式）。
  ⚠️ **这个模式的代价是系统返回键不认识它**——每加一个子屏幕必须同时加 `BackHandler`，
  否则返回键直接退出整个 Activity。已经踩过（见 `PITFALLS.md`）。
- `MainActivity`（世界 A 的界面）只从"去广告"这张卡片进，公开版整个删掉。
- `OpenScheme/OpenFile/OpenTileActivity` 是世界 A 的外部入口（scheme、文件关联、快捷磁贴），
  公开版一并删除。
- `TeleprompterActivity` 额外注册了 docx 的 `VIEW`/`SEND` intent-filter，
  所以微信"用其他应用打开"能直接把文档送进提词器。

**`App.onCreate()` 的启动序列**决定了世界 A 有没有被激活：

```
initToast() → initStore() →          ← 两个世界都需要，公开版只剩这两个
initChannel() → initAppState() → initA11yFeat() → initShizuku() →
initSubsState() → initA11yWhiteAppList() → clearHttpSubs() → syncFixState()
                                     ← 以上全属世界 A，公开版已删
```

同时装了一个全局 `UncaughtExceptionHandler`：落一条 `CrashData`，延迟 1.5 秒，
如果界面还在前台就重新拉起启动页，然后杀掉进程。

## 三、状态与持久化

**全项目只有一个存储原语**：`store/StorageExt.kt` 的 `createTextFlow()`
（`createAnyFlow()` 是它套一层 JSON 编解码）。所有功能模块的持久化都走它，没有第二套机制。

```
       磁盘文件                    内存                        界面
  storeFolder/xxx.json  ──启动时同步读──▶ MutableStoreStateFlow ──collectAsState()──▶ Composable
                        ◀─异步写盘管线──   (MutableStateFlow 委托)
                          drop(1)
                          .conflate()
                          .debounce(n)
                          Dispatchers.IO
                          原子写：临时文件 → fd.sync() → ATOMIC_MOVE
```

三个必须知道的性质：

1. **初始值是同步读的**（`readStoreText` 在构造时就跑完），所以顶层 `val` 一被访问就拿得到真实数据，
   不存在"先返回默认值、稍后异步填充"的中间态。
2. **写盘是异步的，而且 `drop(1)` 会丢掉第一个值**。这条管线由 `scope.launch` 启动，
   **在顶层 `val` 的初始化块执行时它还没开始收集**——所以在初始化块里改 `flow.value` 不会落盘。
   数据迁移必须显式调 `persistNow()`（同步写，绕开整条管线）。这是真实踩过的坑。
3. **`encodeDefaults = true`**（`util/Singleton.kt` 的全局 `json`），
   所以每个字段都会落盘 → **改数据类的默认值只影响全新安装，老数据必须写迁移**。

**谁是真相源**：全局 store flow，**不是 ViewModel**。
`TeleprompterVm.library` 直接就是 `teleprompterLibraryFlow` 本身，VM 没有自己的数据副本。
VM 里只放**纯临时的 UI 状态**：当前打开哪篇（`currentDocId`）、哪一章有焦点（`focusedChapterId`）、
结构性撤销的快照。所以：

- 任何界面读同一份数据都是同一个对象，不存在多份副本要同步。
- 但**每次修改都是整个 `TeleprompterLibrary` 对象 `.copy()` 后整体替换**——
  这条链路很重，是"滑块拖动跟不上手"那类 bug 的结构性来源（见 `PITFALLS.md`）。
  凡是高频事件驱动的写入，都要在写入侧节流，不能指望这条链路快。

## 四、视觉层：液态玻璃是横切的

玻璃不是某个模块的功能，是一层**横切所有界面**的视觉机制，自成一套。

```
业务代码  ──只调用──▶  Modifier.tunedGlass(backdrop, params, ...)   ← 全项目唯一入口
                              │
                              ├── 参数：liveGlassParamsFor(GlassTarget.X)
                              │      读 glassTuningStoreFlow（用户存过就用用户的）
                              │      没存过 → fallback 到 defaultGlassParamsFor(X)
                              │
                              └── 效果链：内部统一走 patchedLens()
                                     （带 clamp 的自实现色散 shader，绕开库的越界 bug）
```

**两条硬性拓扑约束**，违反会崩或者画不出来：

1. **捕获源必须是使用者的兄弟节点，不能是祖先。**
   `layerBackdrop(x)` 挂的节点若包含了采样 `backdrop=x` 的控件，RenderNode 树形成循环引用，
   直接原生栈溢出 SIGSEGV（不是 Kotlin 异常，`logcat` 默认搜不到）。
2. **`layerBackdrop` 捕获的是所在节点整棵子树的最终合成结果**，不是"它画的第一笔"。
   所以它必须精确打在"要被折射的那层内容"自己身上，打在外层容器上会连带捕获到画在上面的兄弟节点。

**再加两条约定**：
- **浮层一律用同一棵组合树里的覆盖层，不用 Dialog/Popup**——后者另开系统窗口，采样不到 backdrop，
  玻璃会变成空白板。设置面板、关于浮层、行菜单全部遵循这条。
- **`GlassTarget` 是参数的作用域键**。四个调参入口共用同一个 `GlassTuningActivity`，
  只是传的 target 列表不同，不是四份实现。

背景系统（`glassui/GlassBackground.kt`）也是横切的：同一套 `GlassBackgroundConfig`（四种模式）
+ 同一套渲染和编辑 UI，靠 `defaultContent` 插槽让"默认"模式在启动页和文档库表现不同。

## 五、提词器内部分层

自有功能里只有提词器复杂到需要单独说。它是三层：

```
存储层   TeleprompterLibrary { documents[], settings, libraryBackground, schemaVersion }
           │  每篇 TeleprompterDocument 自带完整的 displayOverrides（五项显示设置）
           │  settings 里那五个同名字段身份是「新文档默认值」，只在新建/导入时读一次
           ▼
有效值层  doc.effectiveSettings(library.settings)     ← 渲染侧读这五项的唯一入口
           │  把「文档自己的五项」和「其余真全局字段」拼成一个完整对象
           ▼
渲染层    TeleprompterBody（TeleprompterBodyRenderer.kt）  ← 三页唯一的正文渲染实现
           编辑页 / 阅读态 / 播放页全部走它，内部一律 BasicRichTextEditor
           只读靠 readOnly=true + 透明 cursorBrush + 吃掉指针事件
```

**核心不变量：`mode` 只允许影响"能否编辑/光标是否可见/是否接受交互"，不允许影响任何排版参数。**
横向内边距和内容宽度比例都归渲染器所有，页面不再自己加——这是为了让三页**断行位置逐字符一致**。

**结构上的两个后果**：
- 编辑页正文是普通 `Column` + `verticalScroll`，**不是 `LazyColumn`**（选区手柄跨屏拖动的需要），
  所以全部章节永远同时组合布局，改一个排版参数会触发全文重排。这是性能上的结构性约束，
  优化方向是"减少每次重排的章节数"（视口裁剪），不是"降低更新频率"。
- 文档按章节拆成独立的富文本编辑器状态（单个编辑器超两万字符会崩），
  而编辑态/展示态必须像素级一致，让这个拆分对用户完全不可见。

Word 导入导出是独立的一条链：`TeleprompterWordParser` 做 OOXML 六层样式层叠
（docDefaults → 默认段落样式 → `pStyle` 链 → 段落 `pPr` → `rStyle` 链 → run `rPr`），
产出 HTML 喂给 `RichTextState`。

## 六、横切设施

**诊断日志**（`observelog/` + 各模块自己的日志对象）：
三个模块各有一份独立的日志对象（故意不合并——排查时是分开看的），
但**导出**这件事共用 `ObserveLogExport`。每份日志同时写三处：logcat、应用内查看器、`sharedDir` 文件。
⚠️ **日志是同步写盘的**，不能绑到高频手势上（自己制造过一次"拖不动"的 bug）。

**版本差异**（`Edition.kt`）：私人版/公开版是**两个独立工程目录**，不是构建变体。
共用代码单向同步（私人版 → 公开版），`Edition.kt` 等 17 个文件永不覆盖。
功能开关只有一处定义，启动页卡片和"关于"里的使用说明读同一组常量，
不会出现"说明书里写了但应用里找不到"。

## 七、架构不变量清单

违反下面任何一条，基本就是 bug：

1. 玻璃只走 `Modifier.tunedGlass()`，不直接调库的 `lens()`。
2. `layerBackdrop` 捕获源是使用者的**兄弟**节点，且精确打在要被折射的那层内容上。
3. 浮层用同一棵组合树里的覆盖层，不用 Dialog/Popup。
4. 同一 Activity 内每加一个子屏幕，同时加 `BackHandler`。
5. 提词器排版参数的读取只走 `effectiveSettings()`，渲染只走 `TeleprompterBody`。
6. 持久化只用 `createTextFlow`/`createAnyFlow`；启动早期的赋值要 `persistNow()`。
7. 改数据类默认值 = 只影响新安装；要影响老数据必须加 schema 迁移。
8. `LaunchedEffect`/`remember` 的 key 变化粒度必须等于"我关心的事情变了"，
   **绝不用调用方传进来的 lambda**。
9. 共用代码一律在私人版改，再同步；改了 `NEVER_COPY` 里的文件要手工同改公开版。
10. 自己的界面文案里不要出现"跳过""skip"（会被广告跳过类应用误点）。

## 八、已知的架构性弱点

不是待办 bug（那些在 `STATE.md`），是结构本身的欠账：

- **每次修改整体替换 `TeleprompterLibrary`**。文档越多、章节越长，这条写入链路越重，
  且所有高频交互都得自己在写入侧节流。
- **编辑页正文非懒加载**。章节数没有上限，重排代价跟文档长度成正比。
- **世界 A 的规则引擎会对世界 B 的界面生效**。两个世界代码上不互调，运行时却会撞上。
- **`isActivityVisible` 只由 `MainActivity` 维护**，世界 B 的那些 Activity 全都不计入。
  依赖这个标志的逻辑（世界 A 里有几处）在自有功能界面上判断是不准的。
- **上游 `isSystemInDarkTheme()` 技术债**：`LiquidDialog`/`LiquidToggle`/`LiquidSlider` 仍在用它，
  而本项目的深浅色是应用内可独立于系统覆盖的三态开关，两者会不一致。
