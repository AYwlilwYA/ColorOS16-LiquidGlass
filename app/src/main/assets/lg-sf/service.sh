#!/system/bin/sh
# 开机稳定判定：等 SF 起 → 记 pid → 布防 booting → 睡 120s → 按结果背书
# 单次意外永不关门：pid 崩过一轮=写 suspect，连续两轮亲眼见崩=才写 disabled
# 没跑完（SF 没等到/树不在位/写失败）=留原状不改写，booting 残留下轮由 post-fs-data 放行兜底
STATE=${LG_STATE_FILE:-/data/adb/modules/lg_sf_channel/state}
MODDIR=${LG_MODDIR:-/data/adb/modules/lg_sf_channel}
TREE=${LG_TREE_DIR:-$MODDIR/system/system_ext/lib64/oplusex}
STABLE_SEC=${LG_STABLE_SEC:-120}
SF_WAIT_SEC=${LG_SF_WAIT_SEC:-60}

lg_log() { log -p i -t lg-sf-service "$1" 2>/dev/null || echo "lg-sf-service: $1"; }

prev=""
if [ -f "$STATE" ]; then
  rest=""
  read -r prev rest < "$STATE" 2>/dev/null
  if [ "$prev" = "disabled" ]; then
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
  lg_log "SF 未出现，留原状不改写"
  exit 0
fi

BID=$(cat "${LG_BOOT_ID_FILE:-/proc/sys/kernel/random/boot_id}" 2>/dev/null || echo unknown)
if ! echo "booting $BID" > "$STATE"; then
  lg_log "state 写失败，留原状"
  exit 1
fi
lg_log "booting $BID pid=$SF_PID 稳定窗 ${STABLE_SEC}s prev=$prev"

sleep "$STABLE_SEC"

NEW_PID=""
for p in $(pidof surfaceflinger 2>/dev/null); do
  NEW_PID=$p
  break
done
if [ -z "$NEW_PID" ] || [ "$NEW_PID" != "$SF_PID" ]; then
  if [ "$prev" = "suspect" ]; then
    echo "disabled $BID" > "$STATE" 2>/dev/null
    lg_log "pid 变化 $SF_PID -> ${NEW_PID:-gone}，连续两轮崩溃 → disabled"
  else
    echo "suspect $BID" > "$STATE" 2>/dev/null
    lg_log "pid 变化 $SF_PID -> ${NEW_PID:-gone}，单轮不关门 → suspect"
  fi
  exit 0
fi
if [ ! -f "$TREE/libdisplayppex.so" ]; then
  lg_log "树内装载件不在位，留原状（booting）"
  exit 0
fi
if ! echo "healthy $BID" > "$STATE"; then
  lg_log "state 写失败"
  exit 1
fi
lg_log "healthy $BID pid=$SF_PID 稳定 ${STABLE_SEC}s"
exit 0
