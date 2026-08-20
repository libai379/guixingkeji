#!/usr/bin/env bash
# 把私人版(F:\gkd-fork)的共用代码同步到公开版(F:\gkd-public) —— 2026-08-20
#
# 背景：癸月拆成了两个独立工程（见 docs/DECISIONS.md 2026-08-20）。提词器、电话拦截、定位修改、
# 液态玻璃组件两边都有且**内容完全一致**，改完一边必须同步另一边，否则两版逐渐产生差异。
#
# 这个脚本的价值不在"复制"，而在于**它记住了哪些文件绝对不能复制**。那几个文件两边是故意不一样
# 的：盲目 cp 过去，轻则公开版编译失败，重则把已经删掉的"去广告""虚拟定位"又装回公开版。
#
# 用法：  bash sync-to-public.sh          # 预览会同步什么，不实际写入
#         bash sync-to-public.sh --apply  # 真正执行
#
# 执行后记得到 F:\gkd-public 编译一次确认，再各自出包。

set -euo pipefail

SRC="/f/gkd-fork"
DST="/f/gkd-public"
APPLY=0
[ "${1:-}" = "--apply" ] && APPLY=1

# ── 绝对不能同步的文件（两边故意不同）────────────────────────────────────────
# 每一条都写清楚"为什么不同"，以后要动这个列表时不用重新考古。
NEVER_COPY=(
  # 版本标识：名称/功能开关/开发者署名，这是两边差异的唯一"设计意图"载体
  "app/src/main/kotlin/li/songe/gkd/Edition.kt"
  # 启动页：公开版少两张卡片，且已删除对应的 import 和回调参数
  "app/src/main/kotlin/li/songe/gkd/LauncherActivity.kt"
  # 关于/使用说明：公开版少两条说明条目
  "app/src/main/kotlin/li/songe/gkd/LauncherAbout.kt"
  # 启动流程：公开版只剩 initToast()/initStore()，其余初始化的内核已删除
  "app/src/main/kotlin/li/songe/gkd/App.kt"
  # 公开版裁到只剩深浅色/动态取色两个字段
  "app/src/main/kotlin/li/songe/gkd/store/SettingsStore.kt"
  # 公开版去掉了规则匹配/无障碍白名单相关的 flow
  "app/src/main/kotlin/li/songe/gkd/store/StoreExt.kt"
  # 公开版去掉了 LocalMainViewModel（MainViewModel 已删）
  "app/src/main/kotlin/li/songe/gkd/ui/share/LocalExt.kt"
  # 公开版去掉了 shizuku 的 RpcError 引用
  "app/src/main/kotlin/li/songe/gkd/util/CoroutineExt.kt"
  # 公开版去掉了 buildLogFile()（依赖 data/permission/shizuku）
  "app/src/main/kotlin/li/songe/gkd/util/FolderExt.kt"
  # 公开版只保留 tryStartActivity/openUri/openAppDetailsSettings
  "app/src/main/kotlin/li/songe/gkd/util/IntentExt.kt"
  # 公开版去掉了 MainActivity 扩展和 appInfoMapFlow 依赖
  "app/src/main/kotlin/li/songe/gkd/util/Others.kt"
  # 公开版退化成系统 Toast（自绘悬浮 Toast 依赖已删除的无障碍通道）
  "app/src/main/kotlin/li/songe/gkd/util/Toast.kt"
  # 公开版删掉了内核的全部组件和 13 项权限
  "app/src/main/AndroidManifest.xml"
  # 公开版 applicationId 不同，且移除了 Shizuku 依赖
  "app/build.gradle.kts"

  # ── 下面这些是"公开版已删除"的文件。它们躺在被整目录同步的路径里，如果不显式挡住，
  #    这个脚本会把删掉的功能又原样搬回公开版。2026-08-20 首次试运行就撞上了 mock-location.html。
  #    以后再删公开版的任何功能，只要那个文件位于 SYNC_DIRS 覆盖范围内，都要补进这里。
  "app/src/main/assets/web/mock-location.html"   # 虚拟定位的地图选点页
)

# ── 可以整目录同步的共用部分 ─────────────────────────────────────────────────
SYNC_DIRS=(
  "app/src/main/kotlin/li/songe/gkd/teleprompter"
  "app/src/main/kotlin/li/songe/gkd/callscreen"
  "app/src/main/kotlin/li/songe/gkd/glassui"
  "app/src/main/kotlin/li/songe/gkd/glasstuning"
  "app/src/main/kotlin/li/songe/gkd/appearance"
  "app/src/main/kotlin/li/songe/gkd/ui/style"
  "app/src/main/assets/web"
  "docs"
)
SYNC_FILES=(
  "app/src/main/kotlin/li/songe/gkd/TeleprompterActivity.kt"
  "app/src/main/kotlin/li/songe/gkd/WebToolActivity.kt"
)

is_never_copy() {
  local rel="$1"
  for n in "${NEVER_COPY[@]}"; do [ "$rel" = "$n" ] && return 0; done
  return 1
}

changed=0
copy_one() {
  local rel="$1"
  if is_never_copy "$rel"; then return; fi
  local a="$SRC/$rel" b="$DST/$rel"
  [ -f "$a" ] || return
  if [ -f "$b" ] && cmp -s "$a" "$b"; then return; fi
  changed=$((changed+1))
  echo "  ${b:+}$rel"
  if [ "$APPLY" = "1" ]; then
    mkdir -p "$(dirname "$b")"
    cp "$a" "$b"
  fi
}

echo "同步 私人版 -> 公开版$([ "$APPLY" = 1 ] && echo "（实际执行）" || echo "（预览，加 --apply 才真正写入）")"
echo
echo "有差异、将被同步的文件："
for d in "${SYNC_DIRS[@]}"; do
  [ -d "$SRC/$d" ] || continue
  while IFS= read -r f; do copy_one "${f#$SRC/}"; done < <(find "$SRC/$d" -type f)
done
for f in "${SYNC_FILES[@]}"; do copy_one "$f"; done

[ "$changed" = 0 ] && echo "  （无差异，两边已一致）"
echo
echo "跳过（两边故意不同，绝不能覆盖）：${#NEVER_COPY[@]} 个文件，见脚本内 NEVER_COPY 注释"
if [ "$APPLY" = "1" ] && [ "$changed" != 0 ]; then
  echo
  echo "下一步：cd /f/gkd-public && ./gradlew.bat :app:assembleGkdDebug 验证编译"
fi
