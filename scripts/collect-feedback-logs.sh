#!/system/bin/sh
# ColorOS16-LiquidGlass 反馈日志一键采集（手机端 root 执行，无需电脑）
# 执行方式：用带 root 的终端/文件管理器，以 root 权限运行本脚本
# 执行后屏幕会黑一下重启系统界面与桌面，等提示「采集完成」后
# 把 /sdcard 下生成的 .tar.gz 或 .zip 文件发回反馈群即可

if [ "$(id -u)" != "0" ]; then
    echo "需要 root 权限：请用 root 终端或文件管理器的 root 方式执行"
    exit 1
fi

OUT=/sdcard/lg-collect-$(date +%m%d-%H%M%S)
mkdir -p "$OUT"

echo "[1/7] 启动流式 logcat，防止重启期日志被环形缓冲冲掉..."
logcat -b all -v threadtime > "$OUT/logcat.txt" 2>/dev/null &
LOGCAT_PID=$!
sleep 2
if ! kill -0 "$LOGCAT_PID" 2>/dev/null; then
    echo "logcat 启动失败，继续采集其余内容"
    LOGCAT_PID=
fi

echo "[2/7] 重启系统界面与桌面，抓取完整模块加载日志..."
pkill -f com.android.systemui
pkill -f com.android.launcher

echo "[3/7] 现在请下拉通知栏 2 到 3 次，看到玻璃效果后再等，25 秒后自动继续..."
sleep 25

echo "[4/7] 停止 logcat..."
if [ -n "$LOGCAT_PID" ]; then
    kill "$LOGCAT_PID" 2>/dev/null
    sleep 1
fi

echo "[5/7] 拉取模块日志与各框架心跳库..."
for BASE_DIR in /data/user/0 /data/user_de/0; do
    if [ -d "$BASE_DIR/com.coloros16.liquidglass/files/logs" ]; then
        cp -r "$BASE_DIR/com.coloros16.liquidglass/files/logs" "$OUT/module-logs" 2>/dev/null
        break
    fi
done
cp /data/adb/drip/db/drip.db "$OUT/drip.db" 2>/dev/null
cp -r /data/adb/lspd/shared_prefs "$OUT/lspd-shared-prefs" 2>/dev/null
cp -r /data/misc/lspd "$OUT/lspd-misc" 2>/dev/null
mkdir -p "$OUT/framework-prefs"
find /data/adb /data/misc /data/system_de -name "*liquid_glass*" -type f 2>/dev/null | while read -r F; do
    cp "$F" "$OUT/framework-prefs/" 2>/dev/null
done
find /data/adb /data/misc -name "*.xml" -path "*shared_prefs*" 2>/dev/null | grep -E "lspd|lsposed|drip" | head -20 | while read -r F; do
    cp "$F" "$OUT/framework-prefs/" 2>/dev/null
done

echo "[6/7] 导出系统与版本信息..."
getprop > "$OUT/props.txt" 2>/dev/null
dumpsys package com.android.launcher > "$OUT/launcher-version.txt" 2>/dev/null
dumpsys package com.android.systemui > "$OUT/systemui-version.txt" 2>/dev/null
dumpsys package com.coloros16.liquidglass > "$OUT/module-version.txt" 2>/dev/null

echo "[7/7] 打包..."
cd /sdcard || exit 1
BASE=$(basename "$OUT")
tar -czf "$OUT.tar.gz" "$BASE" 2>/dev/null
if [ ! -f "$OUT.tar.gz" ]; then
    zip -r "$OUT.zip" "$BASE" > /dev/null 2>&1
fi
rm -rf "$OUT"

if [ -f "$OUT.tar.gz" ]; then
    echo ""
    echo "================"
    echo "采集完成: $OUT.tar.gz"
    echo "请把这个文件发回反馈群"
    echo "================"
elif [ -f "$OUT.zip" ]; then
    echo ""
    echo "================"
    echo "采集完成: $OUT.zip"
    echo "请把这个文件发回反馈群"
    echo "================"
else
    echo "打包失败，请截图本页面反馈"
fi
