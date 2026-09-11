# 当前状态

**只回答"现在到哪一步了"，不堆历史。** 做过什么、为什么这么做，在 `DECISIONS.md`；踩过什么坑，
在 `PITFALLS.md`；更早的进度在 git 历史里。每次会话结束更新本文件，**旧内容该删就删**。

> 2026-09-06 从 674 行压回到现在这个长度。它是"每次会话开始必读"的文件，长到没人读完就失去意义
> ——那次膨胀直接造成过一次真实的排查弯路（详见 `PITFALLS.md`「必读文件过长本身是个 bug」）。
> **写进来之前先问：这句话描述的是"现在的状态"，还是"当时发生的事"？后者不属于这里。**

更新时间：2026-09-11。

## 下次开发第一件事

**两个工程的包名重命名已全部完成并装机**，代码已提交、已备份，两边工作区干净。接手时按这个顺序：

1. 读这份 + `CLAUDE.md`（两份加起来约 270 行，读得完）。要动代码再看 `ARCHITECTURE.md`。
2. 要加新功能，先看 `ARCHITECTURE.md` 第七节那 10 条架构不变量。
3. 改完照 `docs/CHECKLIST.md` 走真机验收——本项目没有 UI 自动化测试，这份清单是唯一的网。

**私人版没有已知未修的 bug。** 公开版只验了编译和清单，三个功能一次都没在真机上点过（见待办）。

## 本轮改动（2026-09-10/11）—— 包名重命名 + 配色系统性修复

两个工程都改了包名，私人版七屏配色已真机验收，公开版只过了编译。

**包名**：私人版 `li.songe.gkd`/`li.songe.gkd.taiclear` → **`com.guixing`**；
公开版 applicationId → **`com.guixingkeji`**。⚠️ **两边的源码包名故意都是 `com.guixing`**，
只有 applicationId 不同——这样共用代码逐字节相同，`sync-to-public.sh` 不必在复制时改写
package/import 行。**谁要动任何一边的 namespace，先看该脚本开头的前置检查注释。**

applicationId 变了意味着系统当成全新应用，数据目录跟着变。私人版的旧数据已用 adb 整目录
备份到 `F:\backup\taiclear-data-20260910\` 并推回新目录（提词器文稿/订阅/数据库/玻璃预设全部还原）。

**配色**：同一个"猜表面颜色"的根因在这个项目里摔了四次（详见 PITFALLS 同名条目）。已收敛成
`glassui/GlassBackgroundAdjust.kt` 里唯一一份 `glassForegroundOver()`，外加
`glassContentColorOn(base, target)` 供玻璃面直接用，七屏全部接上，补了 6 个单元测试。
关键区分：**同一屏里「页面背景上的前景色」和「玻璃面上的前景色」是两回事**，不能整屏统一提供一个。

同批还修了：背景编辑器五个滑块跑到面板底部（Column 闭合括号位置错了）、色块描边和系统状态栏
图标同样跟着前景色走。

**验收状态**：私人版七屏已真机验收（纯黑和纯白两种背景各测一遍）；公开版已装机、能启动、
三张卡片正确，但功能未测。

## GKD 误点癸星启动页（2026-09-06 ✅ 已解决）

**用户已在原版 GKD 里把癸星加进排除**（触发记录 → 该条 → 「在此应用禁用」），症状消除。
留下这一节是因为**这个 bug 以前出过、被标成"已修复"、改包名之后又回来了**——
它的根因不在癸星代码里，所以**换手机、重装 GKD、或者再改 applicationId，它都会再来一次**。

现象：进癸星启动页立刻被自动点进"去广告"页。

根因（有完整证据链，不是推测）：
- 点它的是**另外装的上游原版 GKD**（`li.songe.gkd`），不是癸星自己的内核——癸星自己的无障碍
  服务根本没启用。查 `settings get secure enabled_accessibility_services` 确认。
- 命中的是**订阅 667**（第三方，非默认订阅）「开屏广告-全局」rule key=0：
  `[text*="跳过"][text.length<10][width<500 && height<300][visibleToUser=true]`
- 启动页「去广告」卡片副标题「自动跳过开屏广告」正好 8 字（<10）、含"跳过"、尺寸也在范围内。
- 该组 374 条应用排除清单里有 `li.songe.gkd` 和 `li.songe.gkd.debug`，**唯独没有癸星自己的
  包名**（当时是 `li.songe.gkd.taiclear.debug`，2026-09-10 起是 `com.guixing.debug`）——
  改包名让上游写死包名的自排除失配了；改成 com.guixing 之后依然失配，性质不变。
- GKD 自己的触发记录坐实：癸星名下 22 次触发，其中 15 次 activity 就是 `LauncherActivity`。

**已做**：给癸星自己的 `checkAppBlockMatch()` 加了"自排除"硬兜底（`appId == META.appId` 直接
返回 true）。⚠️ **这不治当前症状**（点它的不是它自己），只是堵住一个真实的潜在坑：哪天启用癸星
自己的无障碍服务，同样的误点会在它身上重演。

**复发了怎么办**：还是那两条路。①在原版 GKD 里重新排除一次（这次用的就是这条）；
②改掉启动页卡片副标题里的"跳过"二字，对任何广告跳过类应用都免疫（用户这次没选）。
先查 `enabled_accessibility_services` 确认是谁在点，别默认是癸星自己。

## 五个功能各自进度

**去广告**（GKD 上游，仅私人版）：代码未改动。上面那条误点已解决，且根因不在本应用。

**定位修改**（`assets/web/gps-modifier.html` + `WebToolActivity`）：功能完整。本轮新增诊断日志。
遗留测试：两个 GKD 并存场景、改大视频（50MB+）。

**提词器**（`teleprompter/`）：核心完整，改动和 bug 最集中的模块。遗留见下面待办。

**虚拟定位**（`mock-location.html` + `mockloc/`，仅私人版）：注入服务已是原生实现。
2026-08-16 做过 A/B 调参（刷新间隔 1000→100ms、gps/network 精度分开），**还没真机验收**
（高德是否显示模拟位置、持续跑几分钟的电量/CPU）。摇杆浮窗**没有实现**。

**电话拦截**（`callscreen/`）：雏形阶段验收已全部走完（角色申请、黑白名单互斥、真实拦截生效、
返回键导航）。`setSkipCallLog(true)` 实测不生效，**用户确认这是期望行为，不是 bug**。
全局总开关默认关闭；规则列表为空时两个模式都放行（白名单空会拦截全部来电，漏接不可逆）。

## 待办（按优先级）

1. **公开版三个功能的真机验收**：已装机、能启动、三张卡片正确，但功能一次都没点过。
   只验了编译、合并清单（`package="com.guixingkeji.debug"`、authority 跟随）、APK 符号（旧包名 0 命中）。
2. **Shizuku 链路改包名后未验证**：AIDL 描述符（`com.guixing.shizuku.IUserService`）和
   user service 绑定没测到——2026-09-10 和 09-11 两次尝试时设备上 Shizuku 都没运行
   （应用装着，`moe.shizuku.privileged.api`，但进程没起）。下次 Shizuku 可用时补验。
3. **验收 A：三页断行逐字符一致，从未执行**。需要真机取三个模式的 `TextLayoutResult`，
   `getLineStart`/`getLineEnd` 全量 dump 做 diff。JVM 侧做不了（没有 Robolectric，
   `ui-test-junit4` 只在 androidTest）——是构不出 TextLayoutResult，不是偷懒。
4. **首帧渲染耗时没测**。三页统一渲染唯一的性能风险点：编辑页现在每一章都是活的
   `BasicRichTextEditor`（以前只有焦点那章），9 章全活可能变慢。
5. **正文长按「提升为副标题」没有触发入口**。后端 `promoteParagraphToChapter` /
   `paragraphIndexForCharIndex` 都在、单元测试仍覆盖，只是长按手势和系统文本选择冲突所以摘掉了。
   建议改成格式工具栏一个按钮，对光标所在段落生效。
6. **Word 段落修复未在真机验证**。必须**重新导出一次** docx 再导入才验得出来——
   `/sdcard/Download/WeiXin/沉香话术.docx` 本身就是被压平过的导出件（19 段），拿它验不出效果。
7. 虚拟定位相关：A/B 调参真机验收、摇杆浮窗（完全没做）。
8. 提词器编辑页"还有很多 bug"（用户原话），其余的还没描述/复现，需要用户继续反馈。
9. 高光方向可能需要微调（当前用库默认光源角度，不紧急）。

**已停止投入**：提词器正文滑块拖动卡顿——两轮修复后用户验收为"能用，不再深究"，不要再碰。

## 已知但未修的问题

- **上游原版 GKD 已从手机上卸载**（2026-09-11 用户自行清理，旧的 `li.songe.gkd.taiclear.debug`
  和 `li.songe.gkd.guiyue.debug` 也一并清了）。上面那条「GKD 误点癸星启动页」的触发条件因此
  暂时不存在，但**根因不在癸星代码里**，哪天再装回上游 GKD 就会复发，所以那一节保留。
  现在机上只有 `com.guixing.debug`（私人版）和 `com.guixingkeji.debug`（公开版）。
  ⚠️ **`com.guixing` 是 `com.guixingkeji` 的字符串前缀**，所以
  `pm list packages | grep com.guixing` 会**同时命中两个包**，`pidof`、`am force-stop` 之类
  也一样容易误伤。筛选时用完整包名或加 `.debug` 后缀，别用前缀匹配。
- **装机必须带 `--user 0`**：这台机器有三星分身空间（`pm list users` 里的 `95:DUAL_APP`），
  `adb install` 默认装进所有用户空间，会在分身里多出一个应用。详见 PITFALLS 同名条目。

## 环境

```
SDK        F:\android_sdk        adb  F:\android_sdk\platform-tools\adb.exe
代理       127.0.0.1:10809
构建       cd F:\guixing && ./gradlew.bat :app:assembleGkdDebug   （公开版同名任务）
产物       app/build/outputs/apk/gkd/debug/app-gkd-debug.apk
交付       cp 到 F:\apk-out\癸星.apk（私人版）/ 癸星科技.apk（公开版）
           文件名不带版本号、不带日期——版本在应用「关于」页里看
真机       R5CY2195QED（三星 S25 Ultra），私人版 com.guixing.debug / 公开版 com.guixingkeji.debug
装机       adb install -r --user 0 <apk>   —— --user 0 不能省，见「已知但未修的问题」
```

⚠️ **`adb` 默认是连不上的，这是正常状态，不是故障。** 用户平时要正常使用这台手机，所以**数据线
是拔着的**。每次需要真机操作时，流程固定是：用户插线 → 手机弹「允许 USB 调试吗？」→ 用户点允许。
因此 `adb devices` 显示空列表或 `unauthorized`，**唯一正确的反应是告诉用户"请插线并在手机上点允许"
然后等**——不要反复 `kill-server`/`start-server` 空转，也不要写成"USB 连接不稳定/接触不良"去排查
硬件，那是误判（2026-09-10 已纠正过一次）。`unauthorized` 尤其明确：线是通的，只差手机上点确认。

两个工程的区别、同步规则见 `CLAUDE.md`（不在这里重复，重复必然漂移）。
