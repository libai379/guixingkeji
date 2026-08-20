#!/usr/bin/env bash
# 公开版出包前的硬性把关 —— 2026-08-20
#
# 为什么需要这个脚本：公开版是靠"物理删除源码"实现的，但两套代码意味着每次从私人版同步东西，都
# 存在手滑带回一个不该在的文件的可能。这类事故**不会在编译时报错**——多一个类照样编译通过、照样
# 能装能跑，只有在别人解包看的时候才暴露。靠"我记得删过"把关是不可靠的，所以做成脚本，每次出包跑。
#
# 它检查三件事：
#   1. 被删功能的类/特征字符串，确实不在最终 APK 的 dex 里
#   2. 权限清单里没有敏感权限
#   3. 清单里声明的每个组件，对应的类确实存在（防"悬空声明"——ContentProvider 尤其致命，
#      声明了却找不到类会在应用启动时 ClassNotFoundException 直接崩，连界面都到不了。
#      2026-08-20 真的发生过：移除 Shizuku 依赖时忘了删它的 provider 声明。）
#
# 用法： bash verify-public-apk.sh [apk路径]
#        不传路径就用 app/build/outputs/apk/gkd/debug/ 下的那个。
# 退出码 0 = 全过；非 0 = 有问题，不要发出去。

set -uo pipefail
cd "$(dirname "$0")"

APK="${1:-$(ls app/build/outputs/apk/gkd/debug/*.apk 2>/dev/null | head -1)}"
[ -f "$APK" ] || { echo "找不到 APK：$APK"; exit 2; }
AAPT=$(ls /f/android_sdk/build-tools/*/aapt2.exe 2>/dev/null | tail -1)
[ -x "$AAPT" ] || { echo "找不到 aapt2"; exit 2; }

echo "核对：$APK"
echo

python - "$APK" "$AAPT" << 'PYEOF'
import sys, zipfile, subprocess, re

apk, aapt = sys.argv[1], sys.argv[2]
fail = []

# ---- 1. dex 里不该出现的符号 ----
# 被删功能的包路径和特征类名。以后再删功能，往这里加。
FORBIDDEN = [
    ("li/songe/gkd/mockloc",        "虚拟定位包"),
    ("MockLocationActivity",        "虚拟定位界面"),
    ("MockLocationService",         "虚拟定位注入服务"),
    ("MockLocationCoordTransform",  "坐标纠偏算法"),
    ("li/songe/gkd/a11y",           "无障碍(去广告本体)"),
    ("li/songe/gkd/shizuku",        "Shizuku"),
    ("rikka/shizuku",               "Shizuku 库"),
    ("li/songe/gkd/service",        "GKD 内核服务"),
    ("li/songe/gkd/MainActivity",   "GKD 主界面"),
    ("RawSubscription",             "订阅规则模型"),
    ("SelectToSpeakService",        "无障碍服务包装"),
]
# 必须存在的（防止误删过头）
REQUIRED = [
    ("TeleprompterActivity", "提词器"),
    ("CallGuardActivity",    "电话拦截"),
    ("WebToolActivity",      "定位修改"),
]

z = zipfile.ZipFile(apk)
dex = b"".join(z.read(n) for n in z.namelist() if n.endswith(".dex"))

print("[1/3] dex 符号检查")
for probe, label in FORBIDDEN:
    if probe.encode() in dex:
        print("   FAIL  %-28s 不该存在，但在包里" % label); fail.append("残留:" + label)
    else:
        print("   ok    %-28s 已清除" % label)
for probe, label in REQUIRED:
    if probe.encode() not in dex:
        print("   FAIL  %-28s 应该存在，却找不到" % label); fail.append("缺失:" + label)
    else:
        print("   ok    %-28s 存在" % label)

# ---- 2. 权限 ----
print("\n[2/3] 权限检查")
SENSITIVE = [
    "ACCESS_MOCK_LOCATION", "WRITE_SECURE_SETTINGS", "GET_APP_OPS_STATS",
    "QUERY_ALL_PACKAGES", "GET_INSTALLED_APPS", "SYSTEM_ALERT_WINDOW",
    "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "BIND_ACCESSIBILITY_SERVICE",
    "moe.shizuku",
]
badging = subprocess.run([aapt, "dump", "badging", apk],
                         capture_output=True, text=True, errors="replace").stdout
perms = re.findall(r"uses-permission: name='([^']+)'", badging)
for s in SENSITIVE:
    hit = [p for p in perms if s in p]
    if hit:
        print("   FAIL  声明了敏感权限 %s" % hit); fail.append("权限:" + s)
if not fail or not any(f.startswith("权限") for f in fail):
    print("   ok    无敏感权限（当前共 %d 项）" % len(perms))

# ---- 3. 悬空组件声明 ----
print("\n[3/3] 组件声明 vs 类是否存在")
tree = subprocess.run([aapt, "dump", "xmltree", apk, "--file", "AndroidManifest.xml"],
                      capture_output=True, text=True, errors="replace").stdout
pkg = re.search(r"package.*?Raw: \"([^\"]+)\"", tree)
pkg = pkg.group(1) if pkg else ""
# 收集 activity/service/provider/receiver 的 android:name
comps = []
cur = None
for line in tree.splitlines():
    m = re.search(r"E: (activity|service|provider|receiver) ", line)
    if m: cur = m.group(1); continue
    if cur and "android:name" in line:
        m2 = re.search(r'Raw: "([^"]+)"', line)
        if m2: comps.append((cur, m2.group(1))); cur = None

missing = []
for kind, name in comps:
    cls = (pkg + name) if name.startswith(".") else name
    if cls.encode().replace(b".", b"/") not in dex:
        missing.append((kind, cls))
if missing:
    for kind, cls in missing:
        sev = "致命" if kind == "provider" else "警告"
        print("   FAIL  [%s] %s 声明了 %s，但类不在 dex 里" % (sev, kind, cls))
        fail.append("悬空声明:" + cls)
else:
    print("   ok    %d 个组件声明，类都存在" % len(comps))

print()
if fail:
    print("!! 未通过 %d 项，不要发布：" % len(fail))
    for f in fail: print("   -", f)
    sys.exit(1)
print("全部通过，可以发布。")
PYEOF
