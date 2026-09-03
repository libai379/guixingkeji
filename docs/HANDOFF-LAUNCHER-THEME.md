# 交接：启动页主题（给 codex）

本文件是**一次性任务交接单**，不是长期文档。任务做完后由 codex 在 `docs/LOG-CODEX.md` 写完记录，
这份就可以删。

写这份的原因：不要让你自己去翻仓库摸索结构——下面把目录、文件、调用关系、已知坑、验证方式全部
写死。**凡是这里没写到、你觉得需要改的地方，先问，不要自己扩范围。**

---

## 0. 最重要的三条

1. **这是两个独立工程，不是一个工程的两个变体。**
   - 私人版：`F:\guixing`（应用名「癸星」，applicationId `li.songe.gkd.taiclear`）
   - 公开版：`F:\guixingkeji`（应用名「癸星科技」，applicationId `li.songe.gkd.guiyue`）
   - 启动页代码是**两边共用**的。**只在私人版 `F:\guixing` 改**，然后跑同步脚本推到公开版。
     在公开版直接改共用文件，下次同步会被无声覆盖。

2. **日志署名不能混。**
   - 你（codex）写 `docs/LOG-CODEX.md`，git commit 署名用你自己的标识。
   - 不要写入 `docs/STATE.md`、`docs/PITFALLS.md`、`docs/DECISIONS.md` —— 那三份是 Claude 在维护的，
     两边同时写会冲突且分不清责任。
   - 如果你发现了值得记进 PITFALLS 的坑，**写在你自己的 LOG-CODEX.md 里并注明"建议提升到
     PITFALLS"**，由人来决定合并。

3. **改完必须自己验证，不要交回一个没跑过的改动。** 验证方式见第 6 节，模拟器已经配好可以直接用。

---

## 1. 任务范围

**启动页的主题（配色/明暗/前景色策略）。** 只动启动页这一屏，不要顺手改提词器、电话拦截、
设置页——那些屏幕有各自的历史包袱，越界会踩到别人正在改的地方。

具体要解决什么由需求方（用户）直接告诉你。下面只交代"你要动的代码长什么样"。

---

## 2. 目录结构（只列你需要的）

```
F:\guixing\                                  ← 只在这里改
├─ app\src\main\kotlin\li\songe\gkd\
│   ├─ LauncherActivity.kt                   ★ 启动页主文件，你的主战场
│   ├─ LauncherAbout.kt                      启动页底部"关于/使用说明"浮层
│   ├─ Edition.kt                            版本标签/开发者署名常量（两边故意不同，别同步）
│   ├─ glassui\
│   │   ├─ GlassBackground.kt                ★ 背景系统：4 种模式的渲染 + 编辑 UI
│   │   ├─ GlassBackgroundAdjust.kt          ★ 纯色彩数学 + 前景明暗判断（可被单元测试）
│   │   ├─ LiquidButton.kt / LiquidCompactButton.kt / LiquidSlider.kt / LiquidToggle.kt
│   │   └─ LiquidDialog.kt                   已改成主题色实心弹窗，不是玻璃
│   └─ glasstuning\
│       ├─ GlassTuningModel.kt               ★ GlassParams 数据结构 + 默认预设
│       ├─ GlassEffects.kt                   Modifier.tunedGlass()，玻璃的唯一入口
│       └─ GlassTuningActivity.kt            调参面板
├─ app\src\main\res\values\strings.xml       app_name（两边不同：癸星 / 癸星科技）
├─ app\src\test\kotlin\li\songe\gkd\
│   └─ glassui\GlassBackgroundAdjustTest.kt  ★ 色彩数学的对照测试，改数学必须让它继续过
├─ docs\
│   ├─ LOG-CODEX.md                          ★ 你的日志写这里（没有就新建）
│   └─ （其余 md 是 Claude 维护的，只读别写）
└─ sync-to-public.sh                          同步到公开版

F:\guixingkeji\                              ← 不要直接改，用同步脚本
```

---

## 3. 启动页现在是怎么组织的

`LauncherActivity.kt` 里 `LauncherScreen()` 的结构，从外到内：

```
Box(fillMaxSize)
├─ GlassBackgroundLayer(bg = launcherBg, backdrop, defaultContent = { LauncherBackground(...) })
│     背景层。4 种模式：Default(内置流光动效) / Solid(纯色) / Gradient(渐变) / Image(图片)
│     用户在「外观」设置页里选，配置存在 launcherBackgroundFlow
├─ Column(TopCenter)
│   ├─ Text(标题)          ← 金色 LauncherTitleGold + 宋体 + 左上角 + 20sp
│   ├─ LauncherCard × N    ← 功能入口胶囊（私人版 5 个，公开版 3 个）
│   └─ Text(版本行)        ← 点击打开 LauncherAbout 浮层
└─ IconButton(TopEnd)      ← 齿轮，进「外观」设置页
```

**前景色策略（这是最近刚改的，你八成要动这里）：**

```kotlin
val fg = if (launcherBg.prefersLightForeground()) Color.White else Color(0xFF14121A)
```

`prefersLightForeground()` 在 `GlassBackgroundAdjust.kt`，按背景的 Rec.601 感知亮度选浅色或深色
前景，标题/齿轮/副标题/箭头/胶囊底色全部从 `fg` 派生。**唯一的例外是标题**——它用固定金色
`LauncherTitleGold = Color(0xFFD4A017)`，不跟随明暗，理由写在那行注释里（中间调背景上黑白两头
都不讨好）。

`LauncherCard` / `LauncherCardContent` 接收 `fg: Color` 参数，别把它改回写死颜色。

---

## 4. 必须知道的坑（都是真机踩出来的，不是理论）

1. **不要写死前景色。** 背景是用户可配置的，写死 `Color.White` 会在浅色背景上让整屏文字消失。
   这个 bug 刚修过一次，别改回去。详见 `docs/PITFALLS.md` 里「写死前景色」那条。

2. **玻璃只能走 `Modifier.tunedGlass()`**（`glasstuning/GlassEffects.kt`），它内部统一用
   `patchedLens()` 绕开窄面板色散越界导致的**原生崩溃**（不是 Kotlin 异常，是 RenderThread 挂掉）。
   不要直接调库的 `lens()`。

3. **`layerBackdrop` 捕获源必须是使用者的兄弟节点，不能是祖先。** 祖先捕获后代、后代又采样它，
   是循环引用 → RenderThread 原生栈溢出崩溃。启动页的 `backdrop` 已经按这个规则摆好了，
   加新的玻璃元素时照抄现有写法。

4. **浮层不要用 Dialog/Popup。** Dialog 是独立系统窗口，采样不到 Activity 的内容，玻璃会变成
   一块灰板。要浮层就用同一棵组合树里的覆盖层（`LauncherAbout.kt` 就是这么做的）。

5. **背景的五项调节（亮度/对比度/饱和度/模糊度/透明度）**：
   - 纯色/渐变**一律不透明**，透明度滑块只在图片模式出现；模糊度不在纯色模式出现。
     原因：背景是全屏最底层，没有"背后"，半透明只能跟人为指定的基底混合，且会让启动页和设置页
     显示成两个颜色。这个坑刚填过，别再引入透明度。
   - 色彩数学在 `GlassBackgroundAdjust.kt` 的 `adjustedForGlassBg()`，**改它必须让
     `GlassBackgroundAdjustTest` 继续通过**（那个测试拿 875 组参数比对纯色路径和图片路径是否一致）。

6. **液态效果的默认值现在是"朴素白底"**（`GlassTuningModel.kt` 的 `PlainGlassParams`）：
   不透明白底、折射/模糊/高光/内阴影/色散全关。这是用户明确要求的默认外观，**不要为了好看
   把默认值改回液态**。旧的液态配方保留在同文件的 `TunedGlassParams` 里，用户想要时自己调。

---

## 5. 构建与安装

```bash
# 私人版
cd /f/guixing && ./gradlew.bat :app:assembleGkdDebug
# 产物：app/build/outputs/apk/gkd/debug/app-gkd-debug.apk

# 同步到公开版（单向，私人版 → 公开版）
cd /f/guixing && bash sync-to-public.sh            # 先预览
cd /f/guixing && bash sync-to-public.sh --apply    # 确认后执行
cd /f/guixingkeji && ./gradlew.bat :app:assembleGkdDebug   # 必须编译验证

# 交付：手动 cp 到 F:\apk-out\癸星.apk / 癸星科技.apk
```

**同步脚本的两个已知陷阱**（本周各撞过一次）：
- 它**只复制不删除**。你在私人版删掉的文件，公开版仍然存在，脚本会在"只存在于公开版"一节报出来，
  必须手工删。
- `store/StorageExt.kt` 等 17 个文件在 `NEVER_COPY` 里（两边故意不同），改了它们**不会**被同步，
  公开版要手工同改。

SDK 路径 `F:\android_sdk`，adb 在 `F:\android_sdk\platform-tools\adb.exe`。

---

## 6. 怎么验证（不要跳过）

**模拟器已经配好可以直接用**，设备号 `emulator-5554`。真机是 `R5CY2195QED`（可能不在线）。
命令都要带 `-s` 指定设备，别让它自己挑。

```bash
ADB="F:/android_sdk/platform-tools/adb.exe"
$ADB devices -l
$ADB -s emulator-5554 install -r "F:/guixing/app/build/outputs/apk/gkd/debug/app-gkd-debug.apk"
$ADB -s emulator-5554 shell am start -n li.songe.gkd.taiclear.debug/li.songe.gkd.LauncherActivity
$ADB -s emulator-5554 shell screencap -p /sdcard/s.png
$ADB -s emulator-5554 pull /sdcard/s.png ./s.png
```

**换背景配置来测不同主题**（不用点界面）：把一份 JSON 推到 store 目录，force-stop 后重启即可。

```bash
$ADB -s emulator-5554 shell am force-stop li.songe.gkd.taiclear.debug
$ADB -s emulator-5554 push bg.json \
  "/storage/emulated/0/Android/data/li.songe.gkd.taiclear.debug/files/store/launcher_background.json"
$ADB -s emulator-5554 shell am start -n li.songe.gkd.taiclear.debug/li.songe.gkd.LauncherActivity
```

`bg.json` 的形状（字段名以 `GlassBackground.kt` 的 `GlassBackgroundConfig` 为准）：

```
{"mode":"Solid","solidColorArgb":4293325045,"gradientColorsArgb":[4287331583,4282181848,4294926281],
 "gradientAngleDeg":45.0,"imageFileName":null,"imageBrightness":1.0,"imageContrast":1.0,
 "imageSaturation":1.0,"imageBlurDp":0.0,"imageAlpha":1.0}
```

⚠️ **这个文件必须是无 BOM 的 UTF-8。** 用 PowerShell 的 `-Encoding utf8` 会写 BOM，
`kotlinx.serialization` 解析失败后**静默回落到默认值**，表现成"配置没生效"而不是报错，
非常容易误判。用 `[System.IO.File]::WriteAllText($p,$json,(New-Object System.Text.UTF8Encoding($false)))`
或者直接用 bash 的 heredoc。

**至少覆盖这几种背景各截一张图**：Default（内置流光，深色）、Solid 浅色、Solid 深色、Gradient。
重点看标题/齿轮/副标题/版本行在每种背景下是否都清晰可读。

`AppearanceActivity`（外观设置页）**没有 exported，`am start` 起不来**，要测它只能从启动页右上角
齿轮点进去。

**单元测试**：`cd /f/guixing && ./gradlew.bat :app:testGkdDebugUnitTest`，当前 33 个全过，
改动后不能变红。

---

## 7. 你要写的日志

新建/追加 `F:\guixing\docs\LOG-CODEX.md`，每次任务一节，建议包含：

- 日期、任务是什么、需求方原话（如果有）
- 改了哪些文件、为什么这么改（**尤其是否决过的方案和理由**，这个项目很看重这个）
- 验证了什么：跑了哪些测试、在什么设备上截了哪些图、结果如何
- **没做/没验证的部分要如实写**，不要把"编译通过"说成"做好了"
- 发现的坑（注明是否建议提升到 `PITFALLS.md`）

写完记得 `bash sync-to-public.sh --apply` 把 `docs/` 同步到公开版（docs 在共用范围内）。

commit 前先问用户。署名用你自己的，不要用 Claude 的。
