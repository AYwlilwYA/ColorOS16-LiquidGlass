#!/bin/bash
# post-fs-data.sh 行为单测：从 SfModulePacker.kt 提取内嵌脚本，宿主 sh 跑 overlay 树判定表
# 覆盖 首装/healthy 放行、bak 恢复、校验失败回滚、disabled/booting/畸形 拒载、幂等、零 bind 静态门
set -u
T=$(cd "$(dirname "$0")/.." && pwd)
ROOT=$(cd "$T/.." && pwd)
PACKER="$ROOT/app/src/main/kotlin/com/coloros16/liquidglass/ui/SfModulePacker.kt"
OUT="$T/test/out"
mkdir -p "$OUT/bin" "$OUT/postfs"
total=0
fails=0

ok() { total=$((total + 1)); echo "PASS $1"; }
ng() { total=$((total + 1)); fails=$((fails + 1)); echo "FAIL $1 got=[$2]"; }

# 从 Kotlin raw string 提取脚本并还原 ${'$'} 转义
SCRIPT="$OUT/post-fs-data-under-test.sh"
awk '/private val POST_FS_DATA_SH = """$/{f=1;next} f&&/"""\.trimIndent\(\)/{f=0} f{sub(/^        /,""); gsub(/\$\{.\$.\}/,"$"); print}' "$PACKER" > "$SCRIPT"
[ -s "$SCRIPT" ] || { echo "FAIL postfs-extract 空"; exit 1; }
grep -q 'state 缺失=首装/重置 放行' "$SCRIPT" || { echo "FAIL postfs-extract 内容不符"; exit 1; }

# 日志桩：logcat 命令落文件
cat > "$OUT/bin/log" <<'EOF'
#!/bin/bash
echo "log $*" >> "${LG_TEST_OUT}/postfs_log"
exit 0
EOF
chmod +x "$OUT/bin/log"
export PATH="$OUT/bin:$PATH"
export LG_TEST_OUT="$OUT"

MOD="$OUT/postfs/mod"
TREE="$MOD/system_ext/lib64/oplusex"
seed_mod() {
    rm -rf "$OUT/postfs"
    mkdir -p "$TREE" "$OUT/bin"
    chmod +x "$OUT/bin/log"
    echo TRAMP > "$TREE/libdisplayppex.so"
    echo REAL > "$TREE/libOplusDualHeadPhoneEx.so"
    echo "id=lg_sf_channel" > "$MOD/module.prop"
    : > "$OUT/postfs_log"
}

run_postfs() {
    LG_MODDIR="$MOD" LG_STATE_FILE="$MOD/state" \
      sh "$SCRIPT" >/dev/null 2>&1
}

tree_state() {
    # 树内两 so 与 .bak 的在位快照
    for f in libdisplayppex.so libOplusDualHeadPhoneEx.so; do
        if [ -f "$TREE/$f" ]; then printf '%s=so ' "$f"; elif [ -f "$TREE/$f.bak" ]; then printf '%s=bak ' "$f"; else printf '%s=none ' "$f"; fi
    done
}

has_log() { grep -q "$1" "$OUT/postfs_log" 2>/dev/null; }

# A 首装（state 缺失）+ 树校验通过 → 放行、文件不动
seed_mod
run_postfs
has_log "树校验通过" && ok "postfs-firstinstall-allow" || ng "postfs-firstinstall-allow" "$(cat "$OUT/postfs_log")"
[ "$(tree_state)" = "libdisplayppex.so=so libOplusDualHeadPhoneEx.so=so " ] && ok "postfs-firstinstall-untouched" || ng "postfs-firstinstall-untouched" "$(tree_state)"

# B healthy 放行
seed_mod
echo "healthy BID-1" > "$MOD/state"
run_postfs
has_log "树校验通过" && ok "postfs-healthy-allow" || ng "postfs-healthy-allow" "$(cat "$OUT/postfs_log")"
[ "$(tree_state)" = "libdisplayppex.so=so libOplusDualHeadPhoneEx.so=so " ] && ok "postfs-healthy-untouched" || ng "postfs-healthy-untouched" "$(tree_state)"

# C 树内缺件但有 bak → 恢复后放行
seed_mod
echo "healthy BID-2" > "$MOD/state"
rm -f "$TREE/libdisplayppex.so"
echo TRAMP-BAK > "$TREE/libdisplayppex.so.bak"
run_postfs
[ "$(tree_state)" = "libdisplayppex.so=so libOplusDualHeadPhoneEx.so=so " ] && ok "postfs-restore-bak" || ng "postfs-restore-bak" "$(tree_state)"
has_log "自 bak 恢复" && ok "postfs-restore-bak-log" || ng "postfs-restore-bak-log" "$(cat "$OUT/postfs_log")"

# D 校验失败（trampoline 缺且无 bak）→ 回滚拒载：real 也下掉，不留半态
seed_mod
echo "healthy BID-3" > "$MOD/state"
rm -f "$TREE/libdisplayppex.so"
run_postfs
[ "$(tree_state)" = "libdisplayppex.so=none libOplusDualHeadPhoneEx.so=bak " ] && ok "postfs-halfstate-rollback" || ng "postfs-halfstate-rollback" "$(tree_state)"
has_log "回滚拒载安全态" && ok "postfs-rollback-log" || ng "postfs-rollback-log" "$(cat "$OUT/postfs_log")"

# E module.prop 缺失 → 回滚拒载（两件全下掉）
seed_mod
echo "healthy BID-4" > "$MOD/state"
rm -f "$MOD/module.prop"
run_postfs
[ "$(tree_state)" = "libdisplayppex.so=bak libOplusDualHeadPhoneEx.so=bak " ] && ok "postfs-prop-missing-rollback" || ng "postfs-prop-missing-rollback" "$(tree_state)"

# F disabled 拒载：两件下掉，state 不动
seed_mod
echo "disabled BID-5" > "$MOD/state"
run_postfs
[ "$(tree_state)" = "libdisplayppex.so=bak libOplusDualHeadPhoneEx.so=bak " ] && ok "postfs-disabled-reject" || ng "postfs-disabled-reject" "$(tree_state)"
[ "$(cat "$MOD/state")" = "disabled BID-5" ] && ok "postfs-disabled-state-kept" || ng "postfs-disabled-state-kept" "$(cat "$MOD/state")"

# G booting（上轮未证稳）→ 改写 disabled + 拒载
seed_mod
echo "booting BID-6" > "$MOD/state"
run_postfs
case "$(cat "$MOD/state")" in
    disabled\ *) ok "postfs-booting-flips-disabled" ;;
    *) ng "postfs-booting-flips-disabled" "$(cat "$MOD/state")" ;;
esac
[ "$(tree_state)" = "libdisplayppex.so=bak libOplusDualHeadPhoneEx.so=bak " ] && ok "postfs-booting-reject" || ng "postfs-booting-reject" "$(tree_state)"

# H 畸形 state → 改写 disabled + 拒载
seed_mod
echo "garbage x y" > "$MOD/state"
run_postfs
case "$(cat "$MOD/state")" in
    disabled\ *) ok "postfs-malformed-flips-disabled" ;;
    *) ng "postfs-malformed-flips-disabled" "$(cat "$MOD/state")" ;;
esac
[ "$(tree_state)" = "libdisplayppex.so=bak libOplusDualHeadPhoneEx.so=bak " ] && ok "postfs-malformed-reject" || ng "postfs-malformed-reject" "$(tree_state)"

# I 拒载后的下一轮 healthy：两 bak 恢复再放行
seed_mod
echo "disabled BID-7" > "$MOD/state"
run_postfs
echo "healthy BID-7" > "$MOD/state"
run_postfs
[ "$(tree_state)" = "libdisplayppex.so=so libOplusDualHeadPhoneEx.so=so " ] && ok "postfs-recover-after-reject" || ng "postfs-recover-after-reject" "$(tree_state)"
has_log "树校验通过" && ok "postfs-recover-allow-log" || ng "postfs-recover-allow-log" "$(cat "$OUT/postfs_log")"

# J 拒载轮重复执行幂等：仍是 bak，不报错
seed_mod
echo "disabled BID-8" > "$MOD/state"
run_postfs
run_postfs
[ "$(tree_state)" = "libdisplayppex.so=bak libOplusDualHeadPhoneEx.so=bak " ] && ok "postfs-reject-idempotent" || ng "postfs-reject-idempotent" "$(tree_state)"

# K 零 bind 零 tmp 静态门：脚本不许出现 mount/cp/tmp 落盘
if grep -q 'mount --bind\|cp -f\|/data/local/tmp' "$SCRIPT"; then
    ng "postfs-no-bind-static" "脚本含 mount/cp/tmp"
else
    ok "postfs-no-bind-static"
fi

echo "POSTFS_RESULT: pass=$((total - fails)) fail=$fails"
[ "$fails" -eq 0 ]
