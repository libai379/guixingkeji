# codex 工作日志

本文件由 codex 负责。**Claude 不写这里**，同理 codex 不写 `STATE.md` / `PITFALLS.md` /
`DECISIONS.md` / `MAP.md`（交接单 HANDOFF-LAUNCHER-THEME.md 任务完成后已删除，见 git 历史）。

例外：下面第一条是 **Claude 代为补录**的——codex 在写日志之前就耗尽了额度，工作成果留在了
未提交的工作区里。为了不让这段工作丢失，由 Claude 如实记录，并明确标出哪部分是 codex 做的、
哪部分是 Claude 接手后改的。

---

## 2026-09-03/04 启动页视觉改版

> **署名说明**：设计方案与第一版实现 = **codex**；撤回、bug 修复、装机验证、本条日志 = **Claude**。

### codex 做了什么

**只读评估阶段**（未改代码）：在模拟器上看了真实画面，核对了交接单和 `LauncherActivity.kt`。
提出的核心判断是——问题不在玻璃效果本身，在**信息层级**：

- 金色品牌名、五种高饱和标题、五种彩色图标、深色齿轮同时占最高视觉权重，第一眼没有焦点。
- 标题和副标题挤在同一横行（"定位修改 修改照片视频 GPS 信息"），读起来像断句不自然的标签串。
- 53dp 卡高把副标题压到 10sp，版本行 11sp 且悬在卡片下方空白里，细碎、缺乏归属。
- 标题色 `lerp(fg, accent, 0.85f)` 在浅色卡面上实测对比度只有 **2.07:1 ~ 4.20:1**，青色和琥珀色
  明显不足。

**实现阶段**（用户说"ios液态你试试"之后）：

- `LauncherActivity.kt`：删除五个功能强调色（`AccentRed` 整个删除，其余四个降级为"仅供默认动画
  背景使用"并加注释）；卡片内容从 `Row` 改为 `Column` 两行（标题 16sp SemiBold / 副标题 12sp）；
  卡高 53→68dp、间距 12→8dp、图标 36→28dp、横向内边距 16→20dp；标题/图标/箭头统一从 `fg` 派生；
  版本行从卡片下方移到 `BottomCenter` + `navigationBarsPadding`，11→12sp；齿轮 0.85→0.72；
  按压反馈 1.5%→1.8% 缩放并加 4% 透明度变化。
- `GlassTuningModel.kt`：新增 `LauncherLiquidGlassParams`，只给 `GlassTarget.LauncherCard` 一份
  克制的液态默认预设。

它严格守住了交接单的边界：没碰背景系统、没碰 `layerBackdrop` 层级、没用 Dialog、没给纯色/渐变
加透明度、没把其它 target 的白色不透明默认值改掉。

### codex 没能完成的部分

**一次都没构建成功，因此没有任何装机验证。** 原因不是代码问题，是环境问题：

Gradle wrapper 指向 `gradle-9.5.1-bin.zip`，该发行版在本机是有缓存的，但缓存位置由环境变量
`GRADLE_USER_HOME=G:\DevCache\gradle-home` 指定。**codex 的 shell 里没有这个变量**，wrapper 于是
去默认的 `~/.gradle` 找，找不到就联网下载，全程 0 字节卡死。它反复重试、清理自己启动的残留
wrapper 进程（PID 45228/31488/33220/14196，未触碰 IDE 进程和仓库文件）、排查网络，最后耗尽额度。

它在最后如实声明了"不假称已验证"，这点是对的。

> **给后续接手者的操作提示**：在这台机器上跑 Gradle 前先确认
> `GRADLE_USER_HOME=G:\DevCache\gradle-home`。缺了它会触发一次完整的 Gradle 发行版下载。

### Claude 接手后做了什么

**1. 撤回 `LauncherCard` 的液态默认值**（用户指令："撤回吧"）。
用户此前明确定过"所有默认预设设为最普通的白色底"，默认值不该有例外。`defaultGlassParamsFor()`
回到所有 target 统一 `PlainGlassParams`，`LauncherLiquidGlassParams` 整个删除，并在注释里记下
这次撤回和原因，防止以后被再次加回。
用户仍可自行调节：齿轮 → 外观 → 液态效果调节 →「启动页卡片」，`GlassTuningStore.paramsFor()`
优先取用户存的值，出厂默认只是回落值。

**2. 修复一个 codex 未发现的真 bug：深色背景下五张卡片内容全部消失。**

模拟器实拍确认：深色纯色背景下，五张卡是纯白色块，图标/标题/副标题/箭头全部不可见。

根因是三处改动叠加，每一处单独看都对：
- 卡片被 `PlainGlassParams` 的白色 tint 画成**不透明白底**（Claude 之前的改动）；
- `fg` 按**页面背景**亮度计算（Claude 之前的改动）；
- codex 把卡片标题从 `lerp(fg, accent, 0.85f)` 改成纯 `fg`。

前两条组合起来就是"深色背景 → `fg` = 白 → 白卡片上写白字"。codex 那一步只是让潜伏的矛盾现形
——它改之前标题带彩色，在白卡上还看得见。

修法：卡片内容的前景色改为跟着**卡片自己的表面颜色**走，而不是页面背景。
新增 `Color.compositeOver(base, alpha)` 和 `Color.prefersLightForegroundOnTop()`
（在 `glassui/GlassBackgroundAdjust.kt`）；`LauncherCard` 新增 `bgApproxColor` 参数，内部算出
`cardFg = 前景色(tint 色 以 tint.alpha 叠在背景近似色上)`。
tint 不透明（默认）→ 表面恒为白 → 深色文字；用户把 tint 透明度拖低让玻璃透出来 → 表面趋近背景
→ 前景自动跟着背景走。两种情况都正确。

页面级元素（金色标题、齿轮、底部版本行）仍然用页面背景算的 `fg`，两套前景色各归各的。

> **建议提升到 `PITFALLS.md`**（由人决定）：
> "前景色的推导基准必须是**它实际压在哪块表面上**，不是它所在的那个页面。" 这已经是同一类问题
> 第二次出现了——上一次是背景可配置之后写死白字导致文字消失，这次是卡片有了自己的不透明底色
> 之后，沿用页面背景推导的前景色再次失效。凡是"某个元素有了自己的底色"，就要重新问一次它的
> 前景该跟谁走。

**3. 装机验证**（模拟器 `emulator-5554`，四种背景各一张截图）：

- 默认（内置流光动效，深色）：通过
- 纯色浅色 `#E8EFF5`：通过
- 纯色深色 `#1A1A1A`：修复前卡片全空白，**修复后通过**
- 渐变：通过

每张都逐项检查了：金色标题、齿轮、功能标题、副标题、箭头、底部版本行的可读性。
图片模式未测（`approximateBackgroundColor()` 对图片模式返回 null，按深色处理保持白字，
与 `prefersLightForeground()` 的既有处理一致，不是新逻辑）。

构建与单测：`:app:assembleGkdDebug` + `:app:testGkdDebugUnitTest` 全部通过。

### 未做

- `SplashScreen` 冷启动白屏（codex 观察到约 2.8 秒）：涉及启动主题，交接单要求未写明的范围先问，
  未纳入本轮。
- 图片背景模式的实拍验证。

### 补充：公开版启动页此前一直没跟上（Claude 2026-09-04 发现并修复）

`LauncherActivity.kt` 在 `sync-to-public.sh` 的 `NEVER_COPY` 里（两版卡片数量不同，故意不同步）。
结果是**公开版启动页积压了三批未同步的改动**：金色宋体标题、前景色自适应、以及本轮 codex 的排版
改版，一项都没有。9/2 交付的 `癸星科技.apk` 启动页仍是白色 40sp 居中标题的老样子。

已手工把私人版的启动页移植到公开版，并改回公开版故意不同的三处：不 import
`MainActivity`/`MockLocationActivity`、`LauncherScreen` 不接那两个跳转形参、卡片列表是普通的
`listOf` 三项（不用 `listOfNotNull` + `takeIf { Edition.HAS_* }`）。
已编译通过并在模拟器实拍确认：三张卡、金色标题、深色背景下内容可读。

这是"`NEVER_COPY` 里的文件必须手工同改"这条第三次被踩到（前两次是删文件、`persistNow()`）。
