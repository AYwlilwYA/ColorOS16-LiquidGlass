#!/system/bin/sh
# 开机稳定判定：等 SF 起 → 记 pid → 布防 booting → 睡 120s → pid 未变且装载件在位 → 写 healthy
# 状态文件=模块自家目录（root 地盘）；disabled 残留原样保留（手动重置=删 state）
# SF 崩了 init 秒拉新进程、pidof 恒真，必须比对 pid 变化才算稳定
STATE=${LG_STATE_FILE:-/data/adb/modules/lg_sf_channel/state}
MODDIR=${LG_MODDIR:-/data/adb/modules/lg_sf_channel}
STABLE_SEC=${LG_STABLE_SEC:-120}
SF_WAIT_SEC=${LG_SF_WAIT_SEC:-60}

lg_log() { log -p i -t lg-sf-service "$1" 2>/dev/null || echo "lg-sf-service: $1"; }

if [ -f "$STATE" ]; then
  st=""; rest=""
  read -r st rest < "$STATE" 2>/dev/null
  if [ "$st" = "disabled" ]; then
    lg_log "state=disabled 保持，不布防"
    exit 0
  fi
fi

i=0
SF_PID=""
while [ "$i" -lt "$SF_WAIT_SEC" ]; do
  for p in $(pidof surfaceflinger 2>/dev/null); do
    SF_PID=$p
    break
  done
  [ -n "$SF_PID" ] && break
  sleep 1
  i=$((i + 1))
done
if [ -z "$SF_PID" ]; then
  lg_log "SF 未出现，不写状态"
  exit 0
fi

BID=$(cat "${LG_BOOT_ID_FILE:-/proc/sys/kernel/random/boot_id}" 2>/dev/null || echo unknown)
if ! echo "booting $BID" > "$STATE"; then
  lg_log "state 写失败"
  exit 1
fi
lg_log "booting $BID pid=$SF_PID 稳定窗 ${STABLE_SEC}s"

sleep "$STABLE_SEC"

NEW_PID=""
for p in $(pidof surfaceflinger 2>/dev/null); do
  NEW_PID=$p
  break
done
if [ -z "$NEW_PID" ] || [ "$NEW_PID" != "$SF_PID" ]; then
  lg_log "pid 变化 $SF_PID -> ${NEW_PID:-gone} 留 booting"
  exit 0
fi
if [ ! -f "$MODDIR/trampoline.so" ]; then
  lg_log "装载件不在位（拒载轮）留 booting"
  exit 0
fi
if ! echo "healthy $BID" > "$STATE"; then
  lg_log "state 写失败"
  exit 1
fi
lg_log "healthy $BID pid=$SF_PID 稳定 ${STABLE_SEC}s"
exit 0
