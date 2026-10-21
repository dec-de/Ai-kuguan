#!/usr/bin/env bash
#
# 在 HyperOS / MIUI 上把 APK 装到真机。
#
# 用法：  bash tools/adb-install-auto.sh <序列号> <apk> <包名>
# 例：    bash tools/adb-install-auto.sh 61a55c6c app/build/outputs/apk/debug/app-debug.apk com.kuguan.app.debug
#
# ## K70 安装结论（2026-10-18 复验）
#
# `adb install` 在小米上可能被安装确认拦下；`pm install` 是实际成功的安装路径。
# 2026-10-18 K70 复验：即使走 `pm install`，也可能出现「继续安装」弹窗；
# 所以安装期间同时启动设备端白名单点击器。只匹配允许的确认文案。
#
# | 方式 | 结果 |
# |------|------|
# | `adb install -r x.apk` | 卡在 `Performing Streamed Install`，几分钟不返回（等弹窗，弹窗常常不显示） |
# | `adb push` + `adb shell pm install -r` | 已成功装机；仍可能弹「继续安装」，需设备端白名单点击器配合 |
#
# 传输速率实测 103 MB/s（62MB 用时 0.58s），所以卡住跟包大小无关，
# `pm install` 避开曾卡住的 streamed install 路径，但不能保证跳过安全中心确认。
#
# 先准备点击器，再 `pm install`；无弹窗时点击器不会点任何位置。
#
# ## 仍然必须做的两件事
#
# 1. **屏幕要亮**：安装确认可能在 `pm install` 期间弹出；息屏时
#    `input` 注入会被静默丢弃、`uiautomator dump` 会报 "null root node"。
#    用 `svc power stayon true`（`settings put global stay_on_while_plugged_in` 在 MIUI 上不可靠）。
# 2. **本地路径要转 Windows 形式**：脚本 export 了 MSYS_NO_PATHCONV=1（保护 `/sdcard/...`），
#    副作用是本地路径也不再转换，`$HERE` 是 `/e/...` 而 Windows 版 adb 不认。
#    push 会静默失败，然后你会以为装上了。用 cygpath -w。

set -u

ADB="${ADB:-adb}"
DEVICE="${1:?缺设备序列号}"
APK="${2:?缺 apk 路径}"
PKG="${3:?缺目标包名}"
REMOTE="/data/local/tmp/kuguan-install.apk"

export MSYS_NO_PATHCONV=1

say() { echo "→ $*"; }

# ① 亮屏 + 常亮
if "$ADB" -s "$DEVICE" shell dumpsys power | grep -q 'mWakefulness=Asleep'; then
    say "屏幕睡着，唤醒"
    "$ADB" -s "$DEVICE" shell input keyevent 224
    sleep 2
    "$ADB" -s "$DEVICE" shell wm dismiss-keyguard >/dev/null 2>&1
else
    say "屏幕已亮"
fi
source "$(dirname "$0")/preserve-stay-on.sh"
if ! remember_stay_on; then
    echo "❌ 无法读取原有的保持亮屏设置，停止安装" >&2
    exit 1
fi
"$ADB" -s "$DEVICE" shell svc power stayon true || exit 1

# ② 记录安装前的版本，用来判断到底装没装上
BEFORE=$("$ADB" -s "$DEVICE" shell "dumpsys package $PKG 2>/dev/null | grep lastUpdateTime" | tr -d '\r')
say "安装前：${BEFORE:-未安装}"

# ③ push —— 路径必须转 Windows 形式
LOCAL_APK="$(cygpath -w "$APK" 2>/dev/null || echo "$APK")"
say "推送 $LOCAL_APK"
if ! PUSH_OUTPUT=$("$ADB" -s "$DEVICE" push "$LOCAL_APK" "$REMOTE" 2>&1); then
    echo "$PUSH_OUTPUT" | tr -d '\r' | tail -1
    echo "❌ 推送失败，后面装的一定是旧包"
    exit 1
fi
echo "$PUSH_OUTPUT" | tr -d '\r' | tail -1

# ④ 安装期间在设备端识别白名单弹窗。安全中心的倒计时很短，
#    从电脑往返 dump+tap 可能超时，故点击循环必须在设备本地运行。
LOCAL_TAPPER="$(cygpath -w "$(dirname "$0")/tap-install-confirm.sh" 2>/dev/null || echo "$(dirname "$0")/tap-install-confirm.sh")"
if ! "$ADB" -s "$DEVICE" push "$LOCAL_TAPPER" /data/local/tmp/kuguan-tap-install.sh >/dev/null; then
    echo "❌ 白名单点击器推送失败；停止安装以免弹窗超时"
    "$ADB" -s "$DEVICE" shell "rm -f $REMOTE" >/dev/null 2>&1
    exit 1
fi
CLICK_PID=$("$ADB" -s "$DEVICE" shell 'sh /data/local/tmp/kuguan-tap-install.sh >/data/local/tmp/kuguan-tap-install.log 2>&1 & echo $!' | tr -d '\r')

# ⑤ 设备端安装 —— 走 PackageManager，并在需要时点击白名单按钮
say "pm install"
RESULT=$("$ADB" -s "$DEVICE" shell "pm install -r $REMOTE" 2>&1 | tr -d '\r' | tail -1)
echo "   $RESULT"
if [ -n "$CLICK_PID" ]; then
    "$ADB" -s "$DEVICE" shell "kill $CLICK_PID" >/dev/null 2>&1 || true
fi
"$ADB" -s "$DEVICE" shell "rm -f /data/local/tmp/kuguan-tap-install.sh /data/local/tmp/kuguan-tap-install.log" >/dev/null 2>&1

# ⑥ 判成功 —— **不能只看包在不在**
#
# `pm list packages` 对旧包同样返回真：装失败时旧包还在，脚本会报 ✅，
# 然后你在手机上跑的是上一个版本的代码，还以为是新代码有 bug。
AFTER=$("$ADB" -s "$DEVICE" shell "dumpsys package $PKG 2>/dev/null | grep lastUpdateTime" | tr -d '\r')
say "安装后：${AFTER:-未安装}"

"$ADB" -s "$DEVICE" shell "rm -f $REMOTE" >/dev/null 2>&1
if echo "$RESULT" | grep -qi "Success" && [ "$AFTER" != "$BEFORE" ]; then
    echo "✅ $PKG 已更新到新 APK"
    exit 0
fi
echo "❌ 没装上（Success 或 lastUpdateTime 未变）"
echo "   前置检查：getprop persist.security.adbinstall 应为 1；"
echo "   开发者选项里「USB安装」和「USB调试（安全设置）」都要开。"
exit 1
