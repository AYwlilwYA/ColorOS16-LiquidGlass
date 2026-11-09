#!/system/bin/sh
# Magisk service 阶段状态机：预创建状态文件（SF 域建不了），SF 稳定 N 秒后标 healthy
STATE=${LG_STATE_FILE:-/data/local/tmp/lg_sf_tramp.state}
STABLE_SEC=${LG_STABLE_SEC:-60}
TAG=LG-TRAMPOLINE

lg_log() {
    log -p i -t "$TAG" "SVC $1" 2>/dev/null || echo "$TAG SVC $1"
}

# 预创建 + 放行 SF 写权限：目录 drwxrwx--x，system uid 无法自建文件
if [ ! -f "$STATE" ]; then
    : > "$STATE" 2>/dev/null || { lg_log "precreate-fail path=$STATE"; exit 0; }
    chown system:system "$STATE" 2>/dev/null
    chmod 666 "$STATE" 2>/dev/null
    lg_log "precreated"
fi

line=$(cat "$STATE" 2>/dev/null)
status=${line%% *}
if [ "$status" = "disabled" ]; then
    lg_log "locked keep"
    exit 0
fi

sf0=$(pidof surfaceflinger 2>/dev/null)
if [ -z "$sf0" ]; then
    lg_log "sf-absent skip"
    exit 0
fi

# 文件为空 = 装载件本轮未能建档，补起 booting 让本轮可闭环
if [ -z "$status" ]; then
    bid=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
    echo "booting $bid 0" > "$STATE" 2>/dev/null
    lg_log "booting-armed-fresh bid=$bid"
fi

sleep "$STABLE_SEC"

if [ -z "$(pidof surfaceflinger 2>/dev/null)" ]; then
    lg_log "sf-gone keep-state"
    exit 0
fi

line=$(cat "$STATE" 2>/dev/null)
status=${line%% *}
if [ "$status" = "disabled" ]; then
    lg_log "locked-mid keep"
    exit 0
fi

# 升级为 healthy：保留装载件写入的 grants，boot_id 刷成本轮
rest=${line#* }
bid=${rest%% *}
grants=${rest#* }
grants=${grants%% *}
if [ -z "$bid" ]; then
    bid=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
fi
if [ -z "$grants" ]; then
    grants=0
fi
echo "healthy $bid $grants" > "$STATE" 2>/dev/null && lg_log "healthy grants=$grants"
