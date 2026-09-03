# 当前状态

只保留现状，历史进度不往这堆——完成过程中的细节在 `DECISIONS.md`/`PITFALLS.md` 里，这里只回答
"现在到哪一步了"。每次会话结束更新，不用保留旧版本内容。

更新时间：2026-08-28。

## ⚠️ 未完成事项（下次会话第一件事看这里）

1. **两个仓库都还没 commit**。私人版和公开版全部改动都在工作区，没有任何提交。硬盘挂了就全没
   （只有本地 git）。提交前按规矩先问用户。
2. ~~参数归中性脚本 `neutralize.js`~~ —— **2026-09-03 决定跳过，不再是待办**。
   连上真机后干跑发现库里情况已完全变化：原本要清的是我测试时留下的残留值（如 fontSizeSp
   51.699078），但用户中途重新导入过文稿，**原始文档 `7759c727` 已不存在**（章节数也从 9 变成
   11），残留值随之消失。现存 2 篇文档的 fontSizeSp 都已是中性的 16，只剩 3 处 3% 以内的偏差
   （0.9739 / 1.0017 / 0.9904，像是拖滑块的手抖余量），肉眼不可见。用户判断"跳过"。
   脚本保留在 scratchpad，将来若真需要批量归中性可直接复用。
   **这条本身是个教训**：数据修复脚本从写好到执行之间隔了几天，期间数据被用户改动，
   脚本预设的前提（文档 id、字段取值）已经失效。**离线写好的数据修复脚本，执行前必须重新干跑
   一遍看前提还成不成立，不能凭几天前的干跑结果直接 --apply。**

3. **验收 A（三页断行逐字符一致）从未执行**。需要真机取三个模式的 `TextLayoutResult`，
   `getLineStart`/`getLineEnd` 全量 dump 做 diff。JVM 侧做不了（项目没有 Robolectric，
   `ui-test-junit4` 只在 androidTest），已确认不是"偷懒不做"而是构不出 TextLayoutResult。
4. **首帧渲染耗时没测**（同样要设备）。这是三页统一渲染唯一的性能风险点：现在编辑页每一章都是活的
   `BasicRichTextEditor`（以前只有焦点那一章是），9 章全活可能变慢。
5. **正文长按「提升为副标题」没有触发入口**。后端 `promoteParagraphToChapter` /
   `paragraphIndexForCharIndex` 都在、单元测试仍覆盖，只是长按手势和系统文本选择冲突所以摘掉了。
   建议改成格式工具栏一个按钮，对光标所在段落生效。
6. **Word 段落修复未在真机验证**。必须**重新导出一次** docx 再导入才验得出来——旧的那份
   `/sdcard/Download/WeiXin/沉香话术.docx` 本身就是被压平过的导出件（19 段），拿它验证不出效果。

## 2026-09-03 工程目录改名 + 启动页主题交接给 codex

**目录改名**：`F:\gkd-fork` → **`F:\guixing`**（癸星，私人版），
`F:\gkd-public` → **`F:\guixingkeji`**（癸星科技，公开版）。
同步更新了 `sync-to-public.sh` 的 SRC/DST、`CLAUDE.md`、以及 docs 下全部 md 里的硬编码路径，
两个仓库各自更新。改名后已验证：同步脚本 `--check` 一致性通过、两版 `assembleGkdDebug` 均成功。
改名前先 `gradlew --stop` 停掉守护进程释放文件锁。
⚠️ 我的记忆里和历史提交信息里仍会出现旧路径，以本条为准。

**启动页主题任务交接给 codex**，交接单在 `docs/HANDOFF-LAUNCHER-THEME.md`（一次性文件，
任务完成后可删）。里面写死了目录结构、启动页组合树、前景色策略、5 条真机踩出来的坑、
构建/同步/模拟器验证的完整命令，目的是让对方不用自己翻仓库摸索。

**日志分工（重要，别混）**：
- Claude 维护 `STATE.md` / `PITFALLS.md` / `DECISIONS.md` / `MAP.md`。
- codex 写 `docs/LOG-CODEX.md`，**不写上面四份**；它若发现值得进 PITFALLS 的坑，写在自己的
  日志里并注明"建议提升到 PITFALLS"，由人决定合并。
- git commit 各自署名，不互相冒用。

## 2026-09-02 这一轮（背景调节修正 + 启动页标题 + 液态默认改朴素白底）

**背景五项调节的语义修正**（真机/模拟器实测驱动，改了三轮）：
1. 第一版把纯色/渐变也接上了五项调节，但**透明度**引出一个真 bug：半透明背景要跟"背后的东西"
   混合，而启动页背后是窗口底色、设置页背后是 Scaffold 容器色 → **同一份配置两个页面显示成两个
   颜色**（用户最初报的"这两个主题差距太大"就是这个）。
2. 第二版加了一层固定近黑基底让两边一致——**方向错了**：两边是一致了，但一起变成浑浊褐色。
   用户一句"这个底能不能直接删掉"点破。
3. 最终版：**纯色/渐变一律不透明**。背景是全屏最底层，它没有"背后"，半透明只能跟人为指定的基底
   混合，取哪个都是拍脑袋。不透明之后颜色 = 用户选的颜色，两个页面结构上不可能不同。
   透明度滑块只在图片模式显示，模糊度不在纯色模式显示（对均匀色是数学空操作）——**滑块留着却不
   生效比没有更糟，用户会以为是坏的**。

**五项调节的正确性验证**：新增 `GlassBackgroundAdjustTest`，把纯色/渐变走的手算变换和图片走的
`ColorFilter` 色彩矩阵在 **875 组参数组合**上逐通道比对（7 色 × 5 亮度 × 5 对比度 × 5 饱和度），
只有 3 组差约 1/255（8 位色量化，非算法差异）；另断言"全中性值原样返回""饱和度 0 出灰度"。
为此把纯数学部分拆到 `GlassBackgroundAdjust.kt`——原文件有需要 Android Context 的顶层 val，
JVM 测试一加载整个文件的类就 `ExceptionInInitializerError`。

**启动页前景色不再写死白色**。背景可配置之后白字在浅底上整屏消失（见 PITFALLS 同名条目）。
改成按背景感知亮度（Rec.601）选前景基色，8 处硬编码 `Color.White` 全部改为从它派生。

**启动页标题**：金色 `#D4A017` + 宋体 + 左上角 + 40sp→20sp。用固定金色而非自适应黑白——中间调
背景上黑白两头都不讨好，金色深浅底都立得住。

**液态默认预设全部改成朴素白底**（用户要求："最普通的白色底，不透明，所有的散光都关掉，需要液态
效果后期让用户自己调"）。新增 `PlainGlassParams`：不透明白底 + 圆角 24dp，折射/模糊/高光/内阴影/
深度/饱和度提升/色散**全关**；`defaultGlassParamsFor()` 对所有 target 返回同一份，不再按 target
分叉。
⚠️ **差点漏掉的地方**：`LiquidCompactButton`/`LiquidSlider`/`LiquidToggle` 把
`chromaticAberration = true` **写死在自己实现里、不走 GlassParams**，只改默认值的话按钮/滑块/开关
的色散还开着。已单独关掉。旧的液态基线 `TunedGlassParams` 保留并改 `internal`，注释写明它是"唯一
一份调好过的液态配方"，将来要恢复液态时照它来。
白底用 `tint`(alpha=1) 画是有意的：**用户把 tint 的 alpha 拖下来，液态效果就自然透出来**，默认值
和可调值是同一条路径上的两个点，不需要另做"开关液态"的按钮。

**模拟器成为常规验证手段**。用户提议"不能模拟安卓手机然后截图对比吗"——这一轮的三个视觉问题
（浑浊背景、白字消失、液态默认值）全部是靠模拟器截图发现/确认的，纯靠单元测试和推理都发现不了。
以后视觉类改动默认走模拟器自验，不必每次等用户传包。
可复用的做法：`adb -s emulator-5554 push` 一份配置 JSON 到
`/storage/emulated/0/Android/data/<pkg>/files/store/`，`am force-stop` 后重启即可生效；
非 exported 的 Activity（如 `AppearanceActivity`）`am start` 起不来，只能从界面点进去。

## 2026-08-28 下午这一轮（应用改名 + 电话拦截改版 + 背景调节 + 换图标）

**应用改名：癸月 → 癸星，癸月科技 → 癸星科技。**
改的地方**只有一处**：`app/src/main/res/values/strings.xml` 的 `<string name="app_name">`，两个仓库
各改各的（内容本来就不同）。全项目核实过：清单 `android:label`、启动页大标题、「关于」浮层、通知里
的应用名**全部读 `@string/app_name`，没有一处写死**，所以改这一行全都跟着变。改名不动
`applicationId`，覆盖安装数据不丢。
⚠️ **产物文件名同步改成 `癸星.apk` / `癸星科技.apk`**（旧的 `癸月*.apk` 已删），文档里其它地方若还
写着旧名字，以这条为准。

**电话拦截界面改版。**「申请来电屏蔽角色」改名**「申请权限」**，和**「日志」**一起收进右上角图标位
（钥匙 + 列表），不再占正文。「规则管理」按钮和 `CallGuardRulesScreen.kt` **整个删除**，启用开关和
黑白名单直接摊在主屏。视觉按提词器那套液态玻璃重做：浮层顶栏（`chromaticAberration` 关掉，它盖在
滚动内容上开着会露彩边）、分区玻璃卡片、`LiquidToggle`、`LiquidCompactButton`（选中态 `active=true`）。
背景复用启动页的 `launcherBackgroundFlow`——**这一步是必要的不是好看**：玻璃底下若是一块纯色就折射
不出任何东西，等于白付渲染成本（`LiquidDialog` 正是栽在这个上才改成实心）。`backdrop` 是所有玻璃
元素的**兄弟节点**不是祖先（祖先捕获后代再被后代采样 = 循环引用 → RenderThread 原生栈溢出）。

**启动页背景：五项调节扩展到纯色/渐变。** 亮度/对比度/饱和度/模糊度/透明度从"只有图片模式有"提升
为纯色/渐变/图片三种模式共用（默认模式不给，那一档没有可调对象）。实现路径跟图片模式不同：图片是给
`Image` 挂 `ColorFilter`，纯色/渐变没有可挂 filter 的节点，改成把同一套色彩矩阵**直接算进颜色本身**
（新增 `Color.adjustedForGlassBg()`，公式与 `glassBgColorMatrix` 一致）——对均匀色/渐变来说逐色号
变换与逐像素滤镜等价，还省一层离屏渲染。
**已知且合理的表现**：模糊度对纯色**没有可见效果**（均匀色糊完还是它自己，是数学事实不是没实现），
渐变会被轻微柔化。字段名仍用 `imageXxx`（改名会丢用户已有配置），语义已扩展到全部模式。

**应用图标更换**（两个仓库）。源图 1179×1334 非正方形，先居中裁成 1179×1179，再按 5 个密度生成
（48/72/96/144/192）；圆形版加了真正的圆形裁剪蒙版，不是把方图复制一份。清单引用不用改。

**同步坑第二次出现**：`CallGuardRulesScreen.kt` 在私人版删掉后，公开版仍然存在（同步脚本只复制
不删除），编译前必须手工删。加上上一轮的 `GlassPresetImportActivity/Portability`，这个坑本日已撞
两次——**以后凡是在私人版删文件，同步后第一件事就是去公开版把同名文件删掉**，脚本的"只存在于公开版"
那一节就是给这件事看的。

## 2026-08-27/28 这一轮做了什么

**提词器三页统一文本渲染**（编辑页/阅读态/播放页）。新增 `TeleprompterBodyRenderer.kt`，三处共用唯一的
`TeleprompterBody`，一律 `BasicRichTextEditor`，只读模式靠 `readOnly=true` + 透明 `cursorBrush` +
`focusProperties{canFocus=false}`。`scaledForDisplay` 及其全部调用点**已整体删除**（原因和代价见
`DECISIONS.md` 同日条目）。内边距和内容宽度归渲染器所有（横向 8dp，竖屏 1.0 / 横屏 0.65）。

**删除 `editZoomScale`**（schemaVersion v2 迁移）。它是唯一只在编辑模式生效的排版参数，必然导致
编辑页和阅读页断行不一致。真机验收 B/C 已过：磁盘文件里该键 5 处 → 0 处，`schemaVersion` 落盘为 2，
5 篇文档冷启动逐一打开无反序列化异常、进程 pid 全程未变。

**显示参数语义修正**（真机反馈"三个滑块效果完全错位"后改的）：`整体缩放` 改用
`LocalDensity.fontScale` 实现（原来乘进 `TextStyle.fontSize`，够不着 Word 自带字号的 72% 的 run，
表现为"只有副标题在变"）；`行距` 改用 Em 相对单位（原来是 `正文默认字号 × 倍数` 的绝对值公式，
表现为"拖正文字号却在改行距"）；双指缩放全部删除。

**液态预设导入 + 导出全部删除**（两个仓库）。`GlassPresetImportActivity.kt` /
`GlassPresetPortability.kt` 两个文件删除、面板导入导出按钮删除、清单里那条 `intent-filter` 删除。
根因记在清单注释里：它为兜住自定义后缀的杂乱 MIME 用了通配 mimeType，而 `content://` 常常没有稳定
path 导致 `pathSuffix` 匹配不上，于是**打开任意文件（含 .docx）都会弹"打开方式"选择框**。
预设功能本身（保存/应用/改名/删除）保留。

**`LiquidDialog` 改成主题色实心弹窗**。它是真 `Dialog{}`（独立 window），采样不到背后 Activity 的
内容，玻璃在这里折射的只是弹窗自己画的遮罩，等于没折射还白付渲染成本。同时 `usePlatformDefaultWidth
= false` 让遮罩铺满全屏，确认/取消按钮换成主题色按钮。

**docx 导出段落结构修复**（真 bug）。导出器按 `annotated.text` 里的换行符切段落，但
`RichTextState.setHtml()` **不在 `<p>` 之间插换行符**，段落边界只存在 `paragraphStyles` 里。结果：
应用里 330 个 `<p>` 导出成 **19 个 `<w:p>`**，`<w:br/>` 为 0，段落结构整个丢失，再导回来自然不一样。
已改为 `paragraphRangesOf()` 从 `paragraphStyles` 取边界，加回归测试
`exportKeepsOneWordParagraphPerHtmlParagraph`。

**光标行不被顶栏遮挡**。顶栏浮层用 `onGloballyPositioned` 量真实底边，光标变化时用
`getLineForOffset` + `getLineTop` 算出光标行实际排版位置，滚到顶栏正下方。没有写死像素偏移，
字号/行距一变自己就跟着变。

**电话拦截加全局总开关**（`CallGuardStore.enabled`，**默认关闭**）。另加失败保护：规则列表为空时
两个模式都一律放行——黑名单空本来就不该拦；白名单空按原 `!matched` 逻辑会拦截**所有**来电，刚切
模式还没填号码的瞬间就全屏蔽了，漏接不可逆，所以选择失败即放行。

**Word 样式继承：排查后确认问题不在这里，未改代码**。解析器早就实现了完整六层 OOXML 级联
（docDefaults → 默认段落样式 → `pStyle` 链 → 段落 `pPr` → `rStyle` 链 → run `rPr`，逐属性覆盖、
`basedOn` 递归带循环保护），导入侧也确实读取并传入了 `styles.xml`。

## 公开版同步的两个坑（2026-08-28 实际撞上）

1. **同步脚本只复制不删除**。私人版删掉的两个预设文件在公开版仍然存在，脚本主动报了"只存在于
   公开版"这一项（这正是 PITFALLS 里记过的"删除表现为缺失"）。必须手工删，清单里对应的
   `intent-filter` 也要手工删（清单在 `NEVER_COPY` 里）。
2. **`store/StorageExt.kt` 在 `NEVER_COPY` 里**，所以私人版给 `MutableStoreStateFlow` 加的
   `persistNow()` 没有被同步过去，公开版编译失败 `Unresolved reference 'persistNow'`。已手工补齐
   （加 `file` 参数 + `persistNow()`）。**以后凡是动 `NEVER_COPY` 里的文件，都要记得公开版手工同改。**

## 最新产物（2026-08-28）

`F:pk-out\癸月.apk`（私人版 90.1 MB）、`F:pk-out\癸月科技.apk`（公开版 83.5 MB）。
用户已自测通过前述提词器/弹窗/预设删除各项；电话拦截开关和公开版包**尚未装机验证**。

## 最近一轮：提词器五项显示设置改成"每篇文档独立"（2026-08-26/27，**已真机验收通过**）

正文默认字号 / 整体缩放 / 行距 / 对齐方式 / 内容宽度这五项，从"全局值 + 每篇可选覆盖 + 五个
跟随/自定义开关"改成**每篇文档各存一份自己的完整值**，开关全部删除。`TeleprompterSettings` 里
那五个字段身份变成**"新文档默认值"**（只在新建/导入那一刻读一次），其余字段仍是真全局。
新文档默认值取中性基线：倍数 1、字号 16sp（滑块下限）、竖屏宽度 100%、横屏保留 0.65。
设计取舍与否决方案见 `DECISIONS.md` 同日条目。

**真机验收全过**（三星 S25 Ultra，装机 + 逐字段拉文件比对，不是只看日志）：
- 迁移：13 篇文档 × 6 字段，**显示效果不一致 0 处、迁移后残留 null 0 个**，`schemaVersion=1` 已落盘；
  真全局字段（滚动速度/配色/面板玻璃参数）未被误动。
- 改 A 不影响 B/C：沉香话术字号 16.85 → 51.70，其余 12 篇 0 处改动。
- 新建文档、纯 Word 导入：都拿到完整一份初始值，等于新文档默认值，已有文档 0 处改动。
- docx 往返：六项 + `editZoomScale` + 副标题样式 + 9 章节全部精确还原，其余文档 0 处改动。

**过程中修掉一个基础设施 bug**：迁移第一版"日志说迁了、磁盘没迁"（`createTextFlow` 的写盘协程
`drop(1)` 撞上 `scope.launch` 还没开始收集）。已给 `MutableStoreStateFlow` 加 `persistNow()`。
见 `PITFALLS.md` 同名条目——**以后凡是迁移/删字段，验收必须拉文件看，不能看日志**。

**遗留**：
- 测试过程中在库里留下 3 篇测试文档（1 篇空「未命名文档」、1 篇导入生成的「沉香话术」副本、
  1 篇「plainword」），**等用户决定是否删除**。库现共 16 篇。
- **公开版 `F:\guixingkeji` 尚未同步**（提词器是共用代码，按规矩要跑 `sync-to-public.sh`）。
- **尚未 commit**。装机前的库备份在
  `F:\apk-out\teleprompter-backup\teleprompter_library_pre-migration_LIVE_20260827.json`。

## ⚠️ 最重要：本项目现在是两个独立工程

2026-08-20 起，癸月拆成**两个互不相干的文件夹**（不是一个工程的两个变体）：

| | 私人版 | 公开版 |
|---|---|---|
| 路径 | `F:\guixing`（本仓库） | `F:\guixingkeji` |
| applicationId | `li.songe.gkd.taiclear` | `li.songe.gkd.guiyue` |
| 功能 | 5 个全有 | 定位修改 / 提词器 / 电话拦截 |
| git | 原有历史 | 全新仓库，**故意不带私人版历史** |
| 构建 | `assembleGkdDebug` | 同左 |

公开版把"去广告"（整个 GKD 上游内核）和"虚拟定位"**物理删除**了，不是隐藏。产物已核对：dex 里
搜不到 `mockloc`/`a11y`/`shizuku`/`service`/`MainActivity`/`RawSubscription`，权限 20 → 7，
体积 34.0MB → 28.4MB。

**改完共用代码怎么同步**：不要手工 cp，用私人版根目录的 `sync-to-public.sh`：

```bash
cd /f/guixing
bash sync-to-public.sh            # 先预览会同步哪些文件
bash sync-to-public.sh --apply    # 确认无误后执行
cd /f/guixingkeji && ./gradlew.bat :app:assembleGkdDebug   # 必须编译验证
```

脚本的价值不在"复制"，而在于**它记住了 15 个绝对不能覆盖的文件**——那些两边是故意不同的
（`Edition.kt`、启动页、`App.kt`、被裁剪过的 `Toast/IntentExt/FolderExt/Others/StoreExt/
SettingsStore/CoroutineExt/LocalExt`、清单、构建脚本），以及**公开版已删除但躺在同步路径里的文件**
（`mock-location.html`）。盲目 cp 轻则公开版编译失败，重则把删掉的功能又装回去——2026-08-20 脚本
首次试运行就真的撞上了后一种（差点把虚拟定位的页面搬回公开版）。以后再删公开版任何功能，只要那个
文件在脚本 `SYNC_DIRS` 覆盖范围内，都要补进 `NEVER_COPY`。

共用范围：`teleprompter/`、`callscreen/`、`glassui/`、`glasstuning/`、`appearance/`、`ui/style/`、
`TeleprompterActivity.kt`、`WebToolActivity.kt`、`assets/web/`、`docs/`——目前两边字节完全一致。
完整记录见 `DECISIONS.md` 2026-08-20 两条。

## 真机验收结果（2026-08-20 晚，三星 S25 Ultra）

**装机前把关**：`verify-public-apk.sh` 退出码 0；`sync-to-public.sh --check` 一致性通过。

已验证通过：
- **公开版能正常启动**（此前最高风险项）。装机前刚修掉一个会导致启动即崩的悬空 provider
  声明（详见 PITFALLS 同名条目），修完实测进程存活、无 FATAL 日志。
- **两版共存**：先后安装均 Success，**没有 INSTALL_FAILED_CONFLICTING_PROVIDER**；
  `pm list packages` 两个包名同时在列。
- 公开版启动页：标题「癸月科技」、**3 张卡片**、底部「公开版 v1.12.1 · 开发者：癸星人」。
- 私人版启动页：标题「癸月」、**5 张卡片**、且**用户自定义的图片背景保留着**（说明覆盖安装
  没丢设置）。
- **「关于」浮层**（本轮新代码）：玻璃质感正常，四项信息正确，**使用说明里只有三项**——
  去广告和虚拟定位确实不出现。系统返回键关闭浮层后仍停在启动页、进程不退出（`BackHandler` 生效）。
- 公开版三个功能**全部能打开且不崩**：定位修改→WebToolActivity、提词器→TeleprompterActivity、
  电话拦截→CallGuardActivity。
- **数据互相独立**：公开版提词器是空文档库，私人版里的「沉香话术」等文稿全在。
  （⚠️ 此处原写"11 章"，2026-08-26 核对为**当时就是 9 章**——用户自己在 8/22 之后删掉了
  「知识拓展：」「味道：」两节。当时那条记录写的是没核实过的数字，不是数据丢失。）
- **richeditor 1.0.0 升级后旧文稿格式没跑样**（此前列为优先级最高的验收项）：副标题、加粗、
  逐段不同字号、两端对齐全部保留。

仍未验证（需要手动交互，adb 点击测不了）：
- 选区手柄拖动是否跟手/方向正确（升级到 1.0.0 的原始动机，**这条最该优先测**）
- 液态开关拖动是否不再乱跳
- 电话拦截真实来电、提词器播放页滚动、定位修改写入 GPS

## 本次会话产出（2026-08-20）—— 代码改动清单

代码改动（都已提交，都**没在真机上验证过**，手机全程未连）：
- **富文本库升级**：`richeditor-compose` rc14 → 正式版 `1.0.0`，零源码改动。动机是选区手柄拖动
  方向错乱，上游 1.0.0 有两条直接对应的修复。**升级后的首要验收不是手柄，是老文档格式没跑样**
  （1.0.0 改了 HTML 导入行为，而本项目文档是 Word→OOXML→HTML 导入、带两端对齐和手动字号）。
- **`LiquidToggle` 拖动乱跳已修**：`LaunchedEffect` 的 key 用了调用方的 lambda 导致 effect 反复
  重启、把滑块弹回原位；已改为稳定 key + `isDragging` 保护。验收：拖动跟手不回弹、松手只提交
  一次、单击仍能切换。
- **启动页新增版本行 + 「关于/使用说明」浮层**，开发者署名癸星人。
- **提词器正文滑块卡顿**：两轮尝试后用户验收为"能用，不再深究"，本项停止投入。

工程结构：
- 拆成私人版/公开版两个独立文件夹（见上一节）。公开版从零建仓，且**历史重建过一次**——初版建仓时
  有三个文件（虚拟定位单元测试、Shizuku 两个 AIDL）是 `git init` 之后才删的，漏进了首次提交，
  已用孤儿分支清除，现在反查 0 次。
- 新增 `sync-to-public.sh` 单向同步脚本 + `docs/MAP.md`（代码地图/数据落盘/上游改动清单）。
- 清理了 `buildConfig = true`（废弃 flavor 方案的残留，两边 BuildConfig 引用数均为 0）。

备份（已完成并验证）：
```
F:ackup\guixing-20260820.bundle     5.5 MB
F:ackup\guixingkeji-20260820.bundle   0.7 MB
```
两个都做过真还原测试（clone 出来核对内容），不是只看文件生成。⚠️ 目前三份 bundle 都在同一块 F 盘，
**建议拷一份到网盘/移动硬盘**，否则硬盘挂了全没。

文档：本次会话末专门审计过一轮"教训有没有记全"，补了 4 条（同步脚本会把已删功能搬回去、Git Bash
路径差异、Python `re.sub` 二次转义、查依赖版本号的坑提为独立条目）。`PITFALLS.md` 现 30 条 / 1100+ 行。

## 五个功能各自进度

**去广告**（GKD 原生功能）：未改动，跟上游一致。原"官方 GKD 误把 GKD-T 启动页当广告自动点击"
已由用户确认修复。

**定位修改**（`gps-modifier.html`，WebView）：功能完整。阶段 1 遗留测试还没做：两个 GKD（官方 +
GKD-T）并存场景。

**提词器**（`teleprompter/` 包，原生 Compose）：核心功能完整。本轮改动集中在这个功能上，见下面
"最新一批改动"。返回键、横屏锁定两项阶段 1 遗留测试**用户已确认修复**。

**虚拟定位**（`mock-location.html` WebView 选点 + `mockloc/` 原生前台服务）：定位注入 Service 已
是原生实现，地图选点页高德瓦片+坐标纠偏插件正常显示（2026-08-11 实机确认）。摇杆浮窗（虚拟定位页
前台时显示，手动微调位置用）**没有实现**。阶段 1 遗留测试还没做：改大视频（50MB+）。

**2026-08-16 排查"高德还是显示真实位置"**：新写了一个纯诊断工具 `MockLocationDiagnosticActivity`
（注册标准 `LocationListener` 被动监听 gps/network 两个 provider，不做任何注入，跟
`MockLocationService` 完全独立，应用内查看器+复制导出，接了个入口图标在 `MockLocationActivity`
顶栏）。先用 `dumpsys location` 实机确认：系统层面注入本身是成功的，高德也确实注册成了监听者并
收到了模拟坐标——问题不在注入链路断没断。用这个诊断工具跟"影梭"做 A/B 对比，找到两处真实代码
差异：①刷新间隔，GKD-T 硬编码 1000ms，影梭约 100ms（差 10 倍）；②精度值，GKD-T 两个 provider
共用同一个硬编码 `accuracy=5f`，影梭 gps/network 给的是不同的、且 gps 更精确的值（更符合真实
GPS 场景）。已经改：`MockLocationTuning.kt` 新增 DataStore 持久化的
`refreshIntervalMs`/`gpsAccuracy`/`networkAccuracy`（默认 100ms/1.0/2.0，对齐这次 A/B 结果，
不是理论最优值），`MockLocationService.kt` 接入替代硬编码；诊断工具页面加了三个滑块可以不装机
直接调；顺手把地图选点页三个预设城市（沈阳/上海/广州）的写死低精度坐标也提到了完整精度。
**还没真机验收**：高德重新走一遍确认这次显示的是模拟城市而不是真实位置；拍摄场景下持续跑几分钟
观察电量/CPU 有没有因为刷新变快（10 倍）而明显增加。

**电话黑白名单拦截**（新功能，`callscreen/` 包）：平台可用性这一关**已经在真机（三星 S25 Ultra）上
过了**——角色申请弹窗正常、授权成功（`roleHeld=true`），另一台手机打进来 `onScreenCall` 真的被触发，
号码正确（真机日志实测：`onScreenCall, handle=tel:13188899049, number=13188899049`）。已接入启动页
第五张卡片"电话拦截"（用户中途改主意，不等规则引擎做完就先接入方便自己直接测试，不用再靠 adb 拉起）。

规则引擎已实现：`CallGuardStore.kt`——黑名单/白名单是互斥模式，但两个模式的前缀列表
（`blacklistPrefixes`/`whitelistPrefixes`）分开存不共用一份，切换模式不清空对方（故意避免重蹈
"预设列表不按 scope 隔离导致跨屏幕串扰"那个坑，见 PITFALLS.md）。号码标准化去空格/横杠/+86/86 前缀，
不假设一定是 11 位手机号（座机号区号场景也要匹配）。`CallGuardScreeningService.onScreenCall` 已接上
真实判断，命中规则 `setDisallowCall(true)+setRejectCall(true)+setSkipNotification(true)+
setSkipCallLog(true)`——后两项按 AOSP 文档提示，在非默认拨号应用上是否真的生效**还没专门验证过**。
规则管理 UI（`CallGuardRulesScreen.kt`）：模式切换 + 当前模式前缀列表增删，从简，没做拦截历史展示。
诊断日志走应用内查看器（`CallGuardObserveLog`/`CallGuardObserveLogScreen`，同提词器那份"查看诊断
日志"一样的模式，不需要 adb，"复制全部"按钮可以直接粘贴发出来）。

**真机验收结果（2026-08-16，三星 S25 Ultra）**：黑名单模式命中前缀（131 开头实测）拦截成功，
真的没有响铃；黑白名单切换后两边规则列表互不污染，数据结构上的隔离设计验证通过。装机后发现并修了
一个真 bug：规则管理页/诊断日志页是同一个 Activity 内的整屏切换（`showRules`/`showLog` 布尔状态），
不是真正的导航栈条目，系统返回键一开始没接 `BackHandler`，在子页面按返回会直接把整个"电话拦截"
Activity 关掉退回启动页，而不是先退回上一屏——已加 `BackHandler(enabled = showLog || showRules)`
修复，装机确认解决。

`setSkipCallLog(true)` 实测没有生效——命中黑名单被拦截的来电，系统通话记录里依然留下了记录。
**用户确认这是期望行为，不是 bug**：留一笔记录正好能事后知道"有一通电话被拦截过"，不需要改成默认
拨号应用去强制清掉这条记录。到这里这轮"雏形阶段"验收全部走完：角色申请、黑白名单互斥模式+规则
管理 UI、真实拦截生效、返回键导航 bug 修复，全部真机确认过。后续要不要加拦截历史展示/更复杂的号码
匹配规则/规则导入导出，等用户后续提出，这轮不主动扩。

## 最新一批改动 —— 2026-08-16，编译+出包全部通过，全部没有真机验证

**提词器三屏液态效果彻底拆分**：文档库/编辑页/播放页之前全部共用 `GlassTarget.Other` 一份参数，
拖任意一处滑块三屏同时变。新增 `GlassTarget.TeleprompterLibrary/TeleprompterEditor/
TeleprompterPlayer` 三个独立 target，`GlassTuningActivity.kt` 对应拆成
`TeleprompterLibraryGlassTargets`（只含目录自己）/`TeleprompterEditorGlassTargets`（编辑页+设置
窗口本身）/`TeleprompterPlayerGlassTargets`（只含播放页自己）三份"应用+预设"入口。共享的大纲抽屉
组件（`TeleprompterOutlinePanel`/`OutlineScrubTrigger`，编辑/播放两屏共用同一份实现）新增
`target: GlassTarget` 参数，不再内部写死 Other，由调用方各传各的。入口位置：目录页顶栏新加一个
齿轮图标（此前目录页完全没有调节入口）；编辑页维持原来"更多液态效果"按钮位置不变；播放页因为自己
没有独立顶栏/设置界面，用户明确要求把入口放进编辑页设置面板"播放页"分节里（播放页配色下面），并
加了一行小字说明"虽然在编辑页设置面板里，调的是播放页自己的液态效果"防止用户搞混。`GlassPresetStore
.allPresetsFor` 之前那个"按 target 集合精确匹配过滤"的修复（见更早一条记录）在这里再次验证了价值——
三份预设列表天然不会串。

**齿轮→外观→玻璃效果调节 跟提词器彻底分家**：这个入口之前显示全部 4 个 `GlassTarget`（含
`Other`/`TeleprompterSettingsWindow` 这两个提词器专属的），且没有预设区。用户要求"但凡有关于提词器
的设置都要删掉，提词器的设置只需要在提词器里面设置"+"不关于提词器的设置都要加入应用和预设，按照
提词器里面的去做"。`GlassTuningActivity.globalIntent()` 现在直接复用 `restrictedIntent` 机制（跟
提词器自己的入口是同一套实现，只是传的 `targets` 不同），传新常量 `GeneralGlassTargets =
[LauncherCard, SettingsPanel]`（不含 `Other`/`TeleprompterSettingsWindow`），自动带出预设区（这个
UI 本来就是"`restrictToTargets != null` 才显示预设"，不用另写）。原来长期是"零调用点死代码"的
`GlassTarget.SettingsPanel` 这次被真正启用，接给外观设置页自己的"玻璃效果调节"行 + "背景"分节里的
颜色选择弹窗（原来这两处偷用的是 `GlassTarget.Other`，混进了提词器的可调参数里，不符合"两边不要
共用同一个可调值"的要求）。顺带发现并修了一个真 bug：`glassPresetStoreFlow` 是一份不分场景的扁平
列表，`allPresetsFor` 之前不按 target 集合过滤，如果不修，从提词器面板存的预设会在启动页/外观面板
的预设列表里也冒出来，而且点"应用"因为找不到对应 target 的参数会静默不生效——加了按
`paramsByTarget.keys` 精确匹配 `targets` 集合的过滤，两边预设列表天然互不干扰。`GlassBackgroundEditor`
新增必填 `target: GlassTarget` 参数（文档库传 `Other`，外观页传 `SettingsPanel`），`Teleprompter
ColorPickerPopup` 新增 `target` 参数（默认 `Other`，不破坏其余提词器内部现有调用点）。

**启动页四宫格→长条胶囊改版**（`LauncherActivity.kt`）：2×2 方块卡片改成 4 行贯穿屏宽的胶囊
（`cornerRadius=999.dp`，`RoundedCornerShape` 自动按 min(width,height)/2 收敛成全圆角），纵向
堆叠；内部排版从"图标在上/标题居中/副标题在下"改成"图标居左，标题+副标题纵向堆叠居右"，加了个
半透明右箭头（跟外观设置页 `ListItem` 风格统一，提示可点）。胶囊玻璃配方从一次性 `remember {
defaultGlassParamsFor(...) }` 改成 `liveGlassParamsFor(GlassTarget.LauncherCard)`（响应式，之前
从没接到调参面板/预设系统，现在跟提词器三屏一致）；色散（`chromaticAberration`）保持
`liveGlassParamsFor` 的默认开启值，没有像播放控制栏/大纲抽屉那样关掉——这次没有另外做窄长面板的
色散越界处理，因为 `tunedGlass()` 内部本来就统一走 `patchedLens()`（`GlassEffects.kt` 唯一入口），
所有形状自动受益，不用每个新面板单独操心。去掉了每张卡片单独的品牌色染色（原来 `onDrawSurface`
画一层强调色渐变），改成背景本身可自定义之后天然有差异化。标题文案 "GKD-T" 改成 "癸月"（跟应用
名/图标改名同一批）；标题区加了一层黑色 28% 透明度的圆角衬底（scrim），防止用户以后选浅色图片
背景时白字看不清——沿用 `TeleprompterSettingsWindow` 那种"固定强度叠色，不做逐像素亮度自适应"的
思路，不是重新发明。

**背景自定义系统提升为跨屏共享组件**（新文件 `glassui/GlassBackground.kt`）：原来只有提词器
文档库页自己的 `TeleprompterLibraryBackground`/`TeleprompterLibraryBgMode`（默认/纯色/渐变/图片
四种模式，图片模式带亮度/饱和度/模糊度）——启动页这次也要接入同一套背景，改名提升为
`GlassBackgroundConfig`/`GlassBgMode`（字段名不变，落盘 JSON 兼容），渲染逻辑
（`GlassBackgroundLayer`）、编辑 UI（`GlassBackgroundEditor`）都只有一份实现，靠 `defaultContent`
插槽让"默认"模式的视觉可以按屏幕不同（文档库=纯色，启动页=原来的动效光斑背景）。趁这次机会把两处
之前没做实的点定型：①渐变模式原来只有 5 个写死的预设、没有自定义入口，现在加了
`gradientAngleDeg` 字段+自由选两色+角度滑块（复用 `TeleprompterColorPickerPopup`），预设依然保留；
②图片模式原来只有亮度/饱和度/模糊度，新增对比度（`imageContrast`，跟饱和度合成同一个
`ColorMatrix`，不是简单再乘一次亮度那种"名字不同、效果相同"的假滑块）和透明度（`imageAlpha`，走
`Image()` 自带的 `alpha` 参数，不进 `ColorMatrix`）——胶囊/图片双层结构做了小改动：原来只有可见层
做滤镜，`layerBackdrop` 捕获层是纯原图；现在两层用同一份 `colorFilter`+`alpha`，只有可见层多一个
`Modifier.blur()`，这样透明度滑块拖低时透出来的是"同一张调过色的图少了模糊"，不会露出一张没调过
色的原图。启动页自己的背景配置存在新 flow `launcherBackgroundFlow`（跟文档库的
`libraryBackground` 分开持久化，两块屏幕各自记自己的偏好）。设置入口按要求做成外观设置页
（`AppearanceActivity.kt`）里新增的"背景"分节，内嵌 `GlassBackgroundEditor`，不做弹窗/长按——
这个页面本身也顺手接了 `rememberLayerBackdrop()`+`GlassBackgroundLayer`（渲染同一份
`launcherBackgroundFlow`），编辑背景时页面自己就是实时预览，不用跳回启动页看效果；原来的"玻璃效果
调节"那一行套了一层 `tunedGlass` 胶囊，不然光秃秃 `ListItem` 直接摆在图片/渐变背景上会很突兀。

**播放页/编辑页大纲长按拖动选择器合并成共享组件**：之前播放页刚做完"长按拖动翻章"手势（改了两轮，
面板从 240dp 收窄到 160dp、渲染方式从 `LazyColumn`+`scrollToItem` 换成同一手势循环里连续算
`dragPosPx` 直接 `Modifier.offset`），编辑页的大纲触发器当时还是老的纯点击 FAB——用户要求编辑页
也做成一样的效果。抽成 `internal fun BoxScope.OutlineScrubTrigger(...)`（放在
`TeleprompterEditorScreen.kt`，两个屏幕同包可见），播放页/编辑页各自只传自己的
`currentIndex`/`onJumpToIndex`/`onOpenFull` 回调，手势/渲染逻辑只有一份，不是两边分别维护。

**应用图标+名称改版**：图标换成用户提供的自定义图（原来是矢量 `drawable/ic_launcher.xml` 自绘的
"人形+波浪"图标），改成用户素材在 5 个密度下生成的 `mipmap-*dpi/ic_launcher(_round).png`，
manifest 的 `android:icon`/`android:roundIcon` 从 `@drawable/ic_launcher` 改指到
`@mipmap/ic_launcher(_round)`；应用名 `app_name` 从 "GKD-T" 改成 "癸月"。旧的矢量图标文件没删，
只是不再被引用。

最新出包 `F:\apk-out\app-gkd-debug-20260815-*-multifix-round.apk`（含以下所有改动）。手机反复
断连，从提词器顶栏液态化那批（2026-08-14，已验证通过）之后的一切都还没在真机上确认过。

**提词器玻璃预设系统**（新功能）：`GlassPresetStore.kt`，预设=名字+`Other`/
`TeleprompterSettingsWindow` 两个 target 的参数快照，保存/应用/改名/删除，复用
`glassTuningStoreFlow` 现有读写路径。入口：提词器设置面板"透明度/模糊度"滑块下面的"更多玻璃效果
参数/预设"按钮（进去只列这两个 target）；齿轮→外观→玻璃效果调节还是老样子（全部 target，无预设
区）。顺带把这两个 target 的全部真实调用点从编译期常量 `defaultGlassParamsFor` 改成读
`glassTuningStoreFlow` 的 `liveGlassParamsFor`——调参面板此前只喂自己的预览框，从没接到过真实
界面，这是第一次接通。预设列表第一行（排最前、不可删改，标签仍然是"默认"）**改了四轮**：第一轮
从"内置默认预设"误删又恢复，只改了"应用"按钮内部写入机制（不再特判调用 `resetGlassParams`），
但这行的内容还是 `defaultGlassParamsFor`（出厂默认），点应用结果还是"变回默认"，用户真机测试后
指出这跟上面的"恢复默认"按钮功能重复、"非常反人类"。第二轮把这行改名"当前"、顺带重构了
`builtInPreset`/`BUILT_IN_PRESET_ID`/`allPresetsFor` 这几个标识符——用户指出这超出了"只改功能"
的要求，撤回改名和重构，只留内容来源从 `defaultGlassParamsFor(it)` 换成
`glassTuningStoreFlow.value.paramsFor(it)`。第三轮：这个"只换取值来源"的改法编译通过、逻辑上
看似对，但**用户自己录屏发过来**，证实点"应用"画面还是跳回旧值——根因是 `glassTuningStoreFlow
.value` 在 Composable 里是普通属性读取，不会让 Compose 快照系统知道要在它变化时重新执行，
`PresetSection` 自己也没有独立订阅这个 flow，父级重组不保证一定带下来。第四轮（本轮最终版）：
`builtInPreset`/`allPresetsFor` 加 `store: GlassTuningStore` 参数，`PresetSection` 里补上
`glassTuningStoreFlow.collectAsState()`，把这个真正被追踪的值传进去，函数内部不再自己读
`.value`。函数名、常量名、UI 标签这些不变。见 `DECISIONS.md`/`PITFALLS.md` 2026-08-15 相关记录。
**2026-08-16 用户真机确认修复。**

**提词器文档库页**（`TeleprompterLibraryScreen.kt`，此前 100% 原生 Material3）：改成悬浮玻璃
顶栏+FAB+屏幕级共享行菜单浮层，跟编辑页/播放页同一套 `tunedGlass`/`liveGlassParamsFor` 配方。
新增"设置背景"：默认/纯色/渐变/本地图片四种模式（`TeleprompterLibraryBackground`），图片模式带
亮度/饱和度/模糊度三个滑块（普通 `ColorFilter`/`Modifier.blur` 滤镜，跟玻璃管线无关），图片选取
后拷贝一份到 `teleprompterBgFolder` 落盘（不存 URI）。背景设置弹窗是跟编辑页"设置"窗口同尺寸的
半屏面板（`fillMaxWidth(0.92f)/fillMaxHeight(0.85f)`），不是小弹窗。行"更多"菜单锚定在触发它的
按钮下方（右对齐），不是屏幕正中间。**2026-08-16 新增**：每一条文档行（`DocumentRow`）之前是
裸 `Row`，没有自己的背景/轮廓，直接浮在页面背景上，用户要求"每一条目录都要有一个液态的轮廓"——
改成每行自己套一层 `tunedGlass()`（`Other` target，圆角 16dp，`chromaticAberration=false`），
跟顶栏/FAB 同一份配方；行内"更多"按钮相应改成 `sampleBackdrop=false` + 透明底色，跟已经在另一层
玻璃容器里的按钮统一处理。编译+出包通过，**装机时手机断连，还没确认真机效果**。

**其余零散改动**：
- 编辑页设置面板字体选择器从最底部一行一个字体，挪到"副标题样式"下面，改成圆圈+标签横排。
- 播放默认配色从黑底白字改成白底黑字。
- `LiquidCompactButton`/`LiquidCompactIconButton` 新增 `inactiveTint` 参数，编辑页顶栏、播放页
  顶栏、播放页方向菜单、文档库顶栏、文档库行菜单这些"按钮本身已经在另一层玻璃容器里"的调用点传
  `Color.Transparent`，不再各自带一块跟父容器颜色不一致的方块。`TeleprompterSettingsWindow` 内部
  密集控件（对齐/字体/颜色圆点）保留原来的浅色底，没有反馈问题，没动。
- 玻璃调参面板（`GlassTuningActivity`）预览区固定高度 360dp→220dp，每个 target 的预览框尺寸表
  同比例缩小，给下面滑块区域腾地方。
- 播放页大纲按钮从顶栏移出，单独放在屏幕右侧、垂直居中；顶栏隐藏时半透明常驻可点；短按开抽屉；
  长按上下拖动翻章，松手直接跳转，不开完整抽屉。纯手写 `pointerInput`/`awaitEachGesture` 手势
  （没用 `detectDragGesturesAfterLongPress`+`clickable` 组合，规避这个文件的边缘点击区域已经
  踩过的"两个手势探测器抢事件"坑）。**2026-08-16 改了两轮**：第一轮长按只显示当前一个标题，
  用户要求弹出完整大纲再滑动选择，改成 `LazyColumn` 全部章节列表，靠 `previewIndex` 变化触发
  `LaunchedEffect`→`scrollToItem` 把高亮行带进视野——用户反馈"太宽"+"不跟手"（这个方案隔了一次
  重组和协程调度才更新画面，追不上手指）。第二轮：面板从 240dp 收窄到 160dp；渲染方式换成
  "指针风格"选择器——全部章节一次性铺进一个普通 `Column`（不是 LazyColumn），用同一个手势循环里
  连续算出的 `dragPosPx`（像素级，不是按章步进）直接算出 `Column` 的 `Modifier.offset`，跟手指
  同一帧移动，中间没有额外的重组/协程跳转；面板中央画一条固定高亮条，当前停在这条上的章节就是
  松手要跳转的目标。编译+出包通过，**还没真机验证这一版**。

## 真机反馈的两个真实 bug —— 2026-08-15，已修，改动本身还没重新验证

用户装机后截图反馈：
1. **顶栏按钮一圈黑色背景**：`inactiveTint = Color.Transparent` 那批改动（上面"其余零散改动"里的
   按钮底色统一）传的透明色实际渲染成了黑色半透明块。根因：`LiquidCompactButton.kt` 内部
   `inactiveTint.copy(alpha = 0.5f)` 是覆盖 alpha 通道不是相对缩放，`Color.Transparent.copy
   (alpha=0.5f)` = 黑色 50% 不透明。改成 `inactiveTint.copy(alpha = inactiveTint.alpha * 0.5f)`。
   见 `PITFALLS.md`。
2. **背景图片调节影响了整个文档库页面**（不止玻璃控件折射效果跟着变，连顶栏/设置背景面板自己的
   玻璃折射都跟着变暗变糊）：用户明确"只让调背景，没让调整个界面"。**改了两轮**：第一轮把
   `layerBackdrop` 挪到包住"原图+滤镜层"的外层 `Box` 上，指望只捕获原图——真机截图验证后问题
   依旧（顶栏缝隙、设置面板自己的折射还是跟着滑块数值变暗变糊）。根因：`layerBackdrop` 捕获的是
   它所在节点子树的**最终渲染结果**，打在外层 `Box` 上会连带捕获到画在原图上面、真正显示给用户
   的滤镜层，等于没拆开。第二轮把 `layerBackdrop` 直接打在"原图" `Image` 节点自己身上（不是外层
   容器），滤镜层作为不带这个标记的兄弟节点画在后面——这样滤镜层的渲染完全不进入原图节点自己的
   捕获缓冲区。`LibraryBackgroundLayer` 签名从接收现成拼好的 `Modifier` 改成直接接收
   `backdrop: LayerBackdrop`，在函数内部精确控制标记打在哪个节点上。见 `PITFALLS.md` 两条相关
   记录（第一轮的推理、第二轮真机截图验证后的真正根因）。
3. **编辑页格式工具栏（B/I/U/文字颜色/高亮/字号/字距/行距/对齐）没有走跟顶栏一样的按钮底色统一
   处理**：这批按钮从建这个工具栏时就一直是 `sampleBackdrop` 默认值（`true`，真实采样+模糊+
   折射），既没有 2026-08-13 设置面板那轮的 `sampleBackdrop=false`，也没有这次顶栏那轮的
   `inactiveTint=Color.Transparent`——用户截图对比顶栏后指出。涉及 `FormatTabChip`/
   `ToggleIconButton`/`StepperButton`/`ColorSwatchTrigger`/"全选本章节"按钮/字号数字按钮六处，
   全部加上这两个参数，真机截图确认（对比改动前后同一处截图）按钮周围的灰底/描边消失了，跟顶栏
   视觉一致。

新包 `F:\apk-out\app-gkd-debug-20260815-*-format-toolbar-tint-fix.apk`，已装机启动确认不崩。第 3
条已用前后对比截图确认视觉效果符合预期；**第 1、2 条还没等到用户重新测试的明确结果**，不能当作
已解决。

## 提词器设置面板遗留验证项 —— 2026-08-13/14 改动，还没在真机上确认

设置面板模糊度改回 0（对齐大纲抽屉观感）、染色变成主力可读性机制（`settingsPanelOpacity` 默认
0.18）之后：模糊度=0 有没有可读性回归、深浅色模式切换染色对不对，这两项还没测。**"染色强度是否
真的跟滑块联动"这一项已经真机确认过了，答案是"不"**，见下一节，根因已找到，还没修。

## 设置面板"透明度"滑块不生效 —— 2026-08-15，根因已确认，已修，还没真机验收

用户反馈"透明度滑块拖了看不出效果"。真机诊断（screenrecord 录屏逐帧比对 + 临时 `Log.d` 分别打在
"Compose 重组"和"实际画 tint"两个点，对照真机日志时间戳）确认：拖动滑块时数字标签正确变化，
但玻璃面板的**实际重绘只在手势/动画正在进行的那段时间里发生**，一旦静止，无论 state 后续怎么变
都不再重绘，永远停在"最后一次真正重绘"时的画面上（截图逐像素比对证实 0%/100% 时面板同一位置
像素完全相同）。拖动模糊度滑块也能让 tint 用当前最新值重绘一次，说明是 `tunedGlass()` 这套渲染
机制的通病，不是 tint 专属。

**已修**：根因确认是我们自己代码里的问题，不是库不可控——`TeleprompterSettingsWindow` 在组合
阶段就把 `settings.settingsPanelOpacity` 拍扁成普通 `Float` 塞进 `GlassParams.copy(...)`，绘制
闭包读到的是这个冻结值的闭包捕获，Compose 快照系统没有任何"活的" State 读取可以观察，自然没有
理由在静止后重绘。`Modifier.tunedGlass()` 改成 `@Composable` 函数，内部用
`rememberUpdatedState` 包住 `params`/`shape`/`onDrawSurface`/`extraEffects`，`effects`/
`onDrawSurface`/`highlight`/`shadow`/`innerShadow` 这些绘制阶段闭包内部改成读 `live*.value`——
这是 Compose 绘制阶段自动响应 State 变化重绘的标准机制，不是借用手势/动画的临时手段。全项目
只有 `tunedGlass()` 这一处入口，一次改动覆盖所有真实玻璃界面，也覆盖"应用预设/恢复默认"这类不
经过手势的程序赋值场景。详见 `PITFALLS.md`/`DECISIONS.md` 2026-08-15 对应条目。

**还没真机验收**：改完之后想用真机日志确认修复效果，但手机在验证过程中反复被切到其它 App
前台（地震预警设置、抖音、以及这个对话本身的手机端界面），干扰了自动化验证——拿到的日志只能
确认"拖动手势进行中，数值和重绘全程一一对应"（这点之前修复前也是这样，不能算新证据），没能拿到
一次干净的"松手静止后""或"程序赋值（应用预设/恢复默认）不经过任何手势"场景的验证结果。代码
编译通过、已出包、已装机，**需要用户自己按验收标准测一遍**：
1. 拖动任意滑块到任意值，松手后画面停留在最终值对应的样子，不是停在松手前最后一帧动画的样子。
2. 应用预设、点恢复默认——全程没有任何拖动手势，画面必须立刻反映新值，这条最关键，之前没人测过。
3. 正常拖动手感跟改之前一致，没有因为这次改动重新变卡（`rememberUpdatedState` 本身不产生额外的
   State 写入/StateFlow 替换，理论上不会引入早前那次"拖动卡顿"的根因，但没有实测帧耗时确认）。
诊断用的临时 `Log.d` 已删除，不留在代码里。

## 待办（按优先级）

0. **两个版本的真机验收，完全没测过（2026-08-20，手机全程未连）**：产物在
   `F:pk-out\癸月-{私人版,公开版}-20260820-110206.apk`。
   ①**公开版能否正常启动**（内核删得很深，`App.kt` 启动流程被裁剪过，这条风险最高）；
   ②公开版启动页只有 3 张卡片，进提词器/电话拦截/定位修改都正常；
   ③私人版升级后原有提词器文稿/规则/玻璃预设都还在（applicationId 没变，应当原地升级）；
   ④两版启动页底部版本行 + 点开的「关于/使用说明」浮层，排版和玻璃效果是否正常；
   ⑤公开版的使用说明里不应出现去广告和虚拟定位。
   注意：`F:pk-out` 里 10:10 那两个同名 APK 是中途废弃方案的产物（包名带 `.pub`），**不要装**。
1. **本轮（2026-08-16 末）改动的真机验收，完全没测过**：启动页长条胶囊布局+新版背景系统（纯色/
   渐变预设+自定义两色角度/图片四项滑块）、外观设置页新的"背景"分节、编辑页大纲长按拖动是否跟
   播放页手感一致、新应用图标/名称在桌面上的实际显示效果。验收重点对应用户原话的第五节：①纯色
   渐变/图片背景下色散折射是否清晰不糊；②白字标题配一张浅色测试图片背景是否还看得清；③图片模式
   亮度/对比度/饱和度/透明度四个滑块要逐个验证真的在起效，不能假设接上了就行——`tunedGlass()`
   曾经有"接上了但松手不重绘"的坑，这次的对比度/透明度是全新代码路径，没有历史验证记录；④图片
   解码是否只做一次有缓存（复用了文档库那份已验证过的 `remember(fileName)` 逻辑，理论上没问题，
   但没有专门跑一遍确认）。
2. **播放页大纲第二轮改动 + 文档库行液态轮廓的真机验收**（见上）——大纲面板变窄+跟手手感，
   文档库每行新加的玻璃轮廓，都还没测，装机时手机就断了。
2. **设置面板透明度修复的真机验收**（见上）——预设应用这个场景已经在验证"默认"行 bug 时顺带
   确认过（用户 2026-08-16 确认修复）；拖动滑块松手静止后是否停在最终值、正常拖动手感有没有
   变卡这两条，还没有专门测过。
3. **真机验证两个刚修的 bug**（见上）——按钮黑影是否真的消失；背景图片调节是否真的不再影响
   整个页面。第二条修法是推理出来的，没实测过，如果复现要老实上 debug 覆盖层，不要继续猜。
4. 真机验证提词器设置面板遗留项（见上，模糊度=0 可读性、深浅色染色）。
5. **提词器正文滑块拖动卡顿——两轮修复，用户已验收为"能用，不再深究"，暂停投入**：第一轮（加大
   节流间隔到 2000ms）被真机反馈否定并撤回（"拖动过程中正文完全不跟着动，只有松手才跳一下"）；
   第二轮改用"视口裁剪"（只有屏幕内看得见的章节实时重排，屏幕外的冻结到松手才补上）。用户真机测试
   反馈"那个还是不跟手，但是也能用，再去纠结这个问题没有什么用了，暂时先用着吧"——按用户原话，这项
   到此为止，不再继续投入。已装机（`app-gkd-debug-20260816-213237-viewport-cull-body-sliders.apk`）。
   详见 `docs/PITFALLS.md`「提词器正文滑块拖动卡顿」两轮修复的完整记录。
5.5. **编辑页拖选中光标手柄不跟手/乱跳——已定位真实根因并修复，还没真机验收**：真机录屏逐帧比对+
   `state.selection` 诊断日志交叉验证，发现根因是"防抖误判"：`LaunchedEffect(state.annotatedString)`
   把选区/光标的纯移动（文字内容完全没变）也误判成"真的编辑了"，800ms 后触发一次完整的
   `state.toHtml()` 序列化 + `vm.onChapterContentChanged` 持久化写入（替换整个 `TeleprompterLibrary`
   StateFlow，引发大范围重组），这个重组跟手柄自己的位置重绘抢帧，就是"选区在变、手柄图标却卡在
   原地不动"的直接原因。修法：把这个 `LaunchedEffect` 的 key 从整个 `state.annotatedString` 改成
   `state.annotatedString.text`（纯文本，只有真的插入/删除文字才变），纯样式改动（选中点粗体不打字）
   不再被这个"打字防抖"实时捕获，改由失焦时的兜底判断补存，不会真的丢。已编译出包装机
   装机后用户反馈"还是只能往下拖动几个字，再拖就拖不动了"——排查发现是诊断用的
   `LaunchedEffect(state.selection)` 日志本身在拖手柄时同步写盘（`TeleprompterObserveLog.log()`
   内部 `appendText` 没切 IO 线程），高频选区变化=高频主线程同步 I/O，卡住了安卓底层的触摸手势
   识别——诊断日志自己造成的新问题，已删除。已编译出包装机
   （`app-gkd-debug-20260816-220326-remove-per-tick-selection-log.apk`）。删日志后用户再测，症状
   变得更具体：**"我往后拖，他往前跳，我往前拖，他往后跳，有时候还随机跳"**——方向反了，不是滞后。
   **2026-08-17 处理方式：不再在应用层修补，改为升级第三方库**（结合前面已确认的"数据层正确、
   渲染层滞后"，而渲染层是库私有实现，应用层够不到）。`richeditor-compose` 从 `1.0.0-rc14`
   （2026-04-26）升到正式版 `1.0.0`（2026-07-20），其 release notes 有两条直接对应的修复：
   `prevent drag selection from jumping to the next line at line edges`（#748，跟用户描述几乎
   逐字对应）、`diff-based edit pipeline, selection stability, parser round-trips`（#746）。
   零源码改动、编译直接通过。已出包（`app-gkd-debug-20260817-081033-richeditor-1.0.0-upgrade.apk`），
   **装机时手机已断连，需要用户自己传装**。详见 `docs/DECISIONS.md` 同日条目和 `docs/PITFALLS.md`
   「拖选中光标手柄不跟手、乱跳」全部四段（含"查最新版本号"本身的坑）。
   **验收顺序（按重要性，不要颠倒）**：
   ①**先验证老文档没炸，这条比手柄优先级高**——1.0.0 有两条会直接改变 HTML 导入行为的修复
   （`drop white/transparent backgrounds on HTML import` #770、`resolve relative text-indent
   units to sp on HTML import` #724），外加 `combine nested text decorations instead of
   replacing it`（#767）会改嵌套样式合并行为，而本项目文档正是 Word→OOXML→HTML 导入、带
   `text-align: justify` 和逐 run 字号/行距的，风险点明确：拿现有真实文档（尤其有两端对齐和手动
   字号的）走一遍加载，确认字号/行距/对齐没跑样；再编辑保存、重开，确认内容和格式没丢。
   ②老文档没问题后，再测手柄拖动是否跟手、方向是否正确。
   ③若手柄仍有问题：重做"选区值 vs 手柄绘制位置"对比诊断，但**绝不能再用
   `TeleprompterObserveLog.log()`**（同步写盘，自己就会阻塞 UI 线程打断手势），要用非阻塞方式。
   另：用户提到"编辑页面里边，有很多 bug"，这只是其中一个，其余的还没描述/复现，需要用户继续反馈。
6. 早前定下的"五项验收"（滑块手感、无方向划走关闭面板、玻璃质感对比、大纲抽屉/播放控制栏回归、
   FPS 对比）一直没走完整一轮，需要重新走一遍。
7. 虚拟定位摇杆浮窗——完全没做。
8. 阶段 1 遗留实机测试剩两项：改大视频(50MB+，虚拟定位)、两个 GKD 并存（定位修改）。
9. 高光方向可能需要微调（当前用库默认光源角度，不紧急）。

## 已知但未修的问题

- **手机上同时装着两个包名的 GKD-T**：`li.songe.gkd`（陈旧，2026-07-26 后没更新，不可调试）和
  `li.songe.gkd.taiclear.debug`（当前实际开发/测试的那份）。用户明确表示暂不处理，装机测试时务必
  确认操作的是 `li.songe.gkd.taiclear.debug`，`adb` 命令里包名不能简写。

## 环境

SDK: `F:\android_sdk`　平台工具: `F:\android_sdk\platform-tools\adb.exe`
构建: `cd F:\guixing && ./gradlew.bat :app:assembleGkdDebug`，产物在
`app/build/outputs/apk/gkd/debug/app-gkd-debug.apk`，习惯性 `cp` 一份带时间戳/改动摘要的文件名到
`F:\apk-out\`（手动步骤，不是 Gradle 任务自动做的）。
真实包名：`li.songe.gkd.taiclear.debug`（debug 构建，gkd 渠道）——见上面"已知但未修的问题"，`adb`
操作前务必确认包名完整，不要简写成 `li.songe.gkd`。
