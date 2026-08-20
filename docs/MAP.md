# 代码地图 / 数据落盘 / 上游改动清单

> ⚠️ **本文件是从私人版同步过来的，以私人版视角写的。** 公开版已物理删除去广告（整个 GKD 上游
> 内核：`a11y/`、`service/`、`data/`、`db/`、`ui/` 下的界面、`shizuku/`、`notif/`、`permission/`、
> `MainActivity` 等）和虚拟定位（`mockloc/`、`mock-location.html`），下文提到这些的地方在本仓库
> 都不存在。第一节"上游改动清单"讲的是私人版跟上游 GKD 的关系，本仓库不参与上游同步。

回答三个"翻代码之前想先知道"的问题：**改哪个文件**、**数据存在哪**、**动过上游什么**。
内容可用 `git`/`grep` 重新算出来（每节都写了算法），改完大改记得重算一遍。

---

## 一、动过上游哪些文件（fork 长期维护的唯一冲突点）

**这一节最重要**：将来要同步上游 GKD 的新版本，冲突只会发生在这几个文件里，其余都是新增文件，
不参与合并。

重算方法：
```bash
BASE=$(git merge-base HEAD origin/main)
git diff --diff-filter=M --name-only $BASE HEAD   # 被修改的（会冲突）
git diff --diff-filter=A --name-only $BASE HEAD   # 新增的（不会冲突）
```

截至 2026-08-20：**新增 85 个文件，只修改了 7 个**——"只加不改"这条准则守住了。

| 上游文件 | 改了什么 | 同步上游时怎么处理 |
|---|---|---|
| `app/build.gradle.kts` | `applicationId` 改 `li.songe.gkd.taiclear`；加了 backdrop（液态玻璃）、richeditor（富文本）、colorpicker 三个依赖 | 保留我们的 applicationId 和依赖，其余接受上游 |
| `gradle/libs.versions.toml` | 加 `richeditorCompose`、`colorpickerCompose` 两条版本声明 | 保留这两行，其余接受上游 |
| `app/src/main/AndroidManifest.xml` | +79 行：新功能的 Activity/Service 声明、相关权限 | 保留我们新增的块，上游改动照收 |
| `app/src/main/res/values/strings.xml` | `app_name` 改 `癸月`，并**去掉了 `debug_suffix="true"`** | 保留（去掉那个属性是有意的，见下） |
| `app/src/main/kotlin/li/songe/gkd/util/FolderExt.kt` | +9 行：加了 `teleprompterBgFolder` | 保留这一段 |
| `.gitignore` | +6 行 | 合并即可 |
| `app/src/androidTest/.../ExampleInstrumentedTest.kt` | 包名断言跟着 applicationId 改 | 保留 |

`strings.xml` 那个 `debug_suffix` 值得单独说：`app/build.gradle.kts` 里有个机制，会把带
`debug_suffix="true"` 的字符串在 debug 构建时自动加 `-debug` 后缀。`app_name` 原本带这个属性，
导致应用名显示成"癸月-debug"。**只从 `app_name` 上摘掉了这个属性**，其它字符串（磁贴名等）仍保留，
因为那些确实需要区分 debug 版。

---

## 二、代码地图：改哪个文件

上游 GKD 原有的包（`a11y/`、`service/`、`data/`、`db/`、`ui/`、`shizuku/`、`notif/`、`permission/`）
**没动过，也尽量别动**。下面只列本项目新增/自有的部分。

### 提词器 `teleprompter/`（改动最多、bug 最集中）
| 文件 | 职责 |
|---|---|
| `TeleprompterEditorScreen.kt` | 编辑页**和设置面板**（`TeleprompterSettingsWindow`）、章节渲染、大纲触发器。全项目最大的文件，滑块/选区相关的 bug 基本都在这 |
| `TeleprompterPlayerScreen.kt` | 播放页，全屏滚动、控制栏 |
| `TeleprompterLibraryScreen.kt` | 文档库列表页、背景设置入口 |
| `TeleprompterVm.kt` / `TeleprompterModel.kt` / `TeleprompterStore.kt` | 状态、数据模型、持久化 |
| `TeleprompterWord{Import,Export,Parser}.kt` | Word 文档导入导出，`Parser` 负责 OOXML 样式层叠 |
| `TeleprompterChapterOps.kt` | 章节合并/拆分/升降级 |
| `TeleprompterTextScaling.kt` | 显示用的字号/行距缩放（只影响显示，不改存储） |
| `TeleprompterObserveLog*.kt` | 应用内诊断日志（**同步写盘，勿绑高频事件**，见 PITFALLS） |

### 液态玻璃 `glassui/` + `glasstuning/`
| 文件 | 职责 |
|---|---|
| `glasstuning/GlassEffects.kt` | **`Modifier.tunedGlass()` 的唯一定义处**——全项目玻璃效果的收口入口 |
| `glasstuning/PatchedLens.kt` | 修过采样越界的 `patchedLens()`，`tunedGlass` 内部统一走它 |
| `glasstuning/GlassTuning{Activity,Model,Store}.kt` | 调参面板、参数模型、持久化；`GlassTarget` 枚举定义在 Model 里 |
| `glasstuning/GlassPresetStore.kt` | 预设，按 target 集合精确匹配隔离（踩过串扰的坑） |
| `glassui/Liquid{Slider,Toggle,Button,CompactButton,Dialog}.kt` | 玻璃质感控件。**Slider 和 Toggle 是同源移植、结构对应——一个出问题先去看另一个怎么写的** |
| `glassui/DampedDragAnimation.kt` | 上面两个控件共用的弹簧拖动物理 |
| `glassui/GlassBackground.kt` | 跨屏共享的背景系统（默认/纯色/渐变/图片四模式）+ `launcherBackgroundFlow` |

### 其它
| 位置 | 职责 |
|---|---|
| `LauncherActivity.kt` | 启动页胶囊列表 |
| `LauncherAbout.kt` | 关于/使用说明浮层 |
| `Edition.kt` | **两个工程唯一故意不同的文件**（版本名/功能开关/开发者署名） |
| `callscreen/` | 电话拦截，自成一套（含自己的一份诊断日志实现，故意不与提词器共用） |
| `mockloc/` | 虚拟定位服务+诊断工具（**仅私人版**） |
| `assets/web/gps-modifier.html` | 定位修改（改照片/视频 GPS 元数据） |
| `assets/web/mock-location.html` | 虚拟定位选点页（**仅私人版**） |
| `appearance/AppearanceActivity.kt` | 外观设置（背景、玻璃调节入口） |

---

## 三、数据落盘在哪（备份 / 丢数据排查用）

重算：`grep -rn "createAnyFlow(\|createTextFlow(" --include=*.kt app/src/main/kotlin/`

### 键值数据（JSON，落在 `filesDir/store/`）
| key | 内容 | 定义处 |
|---|---|---|
| `teleprompter_library` | **全部提词器文稿**（正文 HTML、章节、每篇的设置） | `teleprompter/TeleprompterStore.kt` |
| `callguard_store` | 电话拦截的模式和黑/白名单前缀 | `callscreen/CallGuardStore.kt` |
| `glass_tuning` | 各 `GlassTarget` 的玻璃参数 | `glasstuning/GlassTuningStore.kt` |
| `glass_preset` | 玻璃预设列表 | `glasstuning/GlassPresetStore.kt` |
| `launcher_background` | 启动页背景配置 | `glassui/GlassBackground.kt` |
| `mockloc_tuning` | 虚拟定位刷新间隔/精度（仅私人版） | `mockloc/MockLocationTuning.kt` |
| `store` | 深浅色、动态取色等（上游 `SettingsStore`） | `store/StoreExt.kt` |

### 目录（定义见 `util/FolderExt.kt`）
```
filesDir/store/            上面那些 JSON
filesDir/teleprompter_bg/  用户选的文档库背景图（复制进来的真实字节，不是 content:// URI）
filesDir/log/ crash/       日志与崩溃记录
filesDir/db/ subscription/ snapshot/    上游 GKD 的订阅/快照（公开版已无）
cacheDir/shared/           对外分享用的临时文件（诊断日志导出等）
```

⚠️ **提词器文稿只存在手机本地**，清除应用数据就没了，且 `applicationId` 一变就是另一个应用（数据
不互通）。提醒用户定期用导出功能备份。

---

## 四、两个工程的关系

见 `CLAUDE.md` 开头。一句话：私人版 `F:\gkd-fork` 是改动源头，公开版 `F:\gkd-public` 靠
`sync-to-public.sh` 单向同步，`Edition.kt` 等 15 个文件永不覆盖。
