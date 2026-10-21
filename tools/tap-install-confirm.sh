#!/system/bin/sh
#
# 在手机本地跑：循环点掉小米「USB安装提示」确认弹窗。
#
# 为什么必须在设备本地跑：那个弹窗有 10 秒倒计时，超时自动拒绝。
# 从电脑上「dump → 取坐标 → input tap」每步都是一次 adb 往返，
# 加起来往往超过 10 秒 —— 点晚了等于没点。整个循环放进设备里，
# 只有一次 adb 往返，检测到弹窗后 1 秒内就能点到。
#
# 只点「继续安装」「允许」，绝不点「拒绝」。

i=0
while [ $i -lt 40 ]; do
    i=$((i + 1))
    uiautomator dump /sdcard/.ai.xml >/dev/null 2>&1
    for KEY in 继续安装 允许 确定 安装; do
        L=$(grep -o "text=\"$KEY\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" /sdcard/.ai.xml 2>/dev/null | head -1)
        [ -z "$L" ] && continue
        N=$(echo "$L" | sed 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/')
        set -- $N
        [ $# -lt 4 ] && continue
        X=$(( ($1 + $3) / 2 ))
        Y=$(( ($2 + $4) / 2 ))
        input tap "$X" "$Y"
        echo "tapped [$KEY] $X,$Y"
        sleep 2
        break
    done
    sleep 1
done
echo "loop done"
