#!/bin/bash
# post-fs-data.sh 行为单测：从 SfModulePacker.kt 提取内嵌脚本，宿主 sh 跑 overlay 树判定表
# 表（只读不写 state）：缺失/healthy/suspect/booting/畸形 五种放行、disabled 唯一拒载、校验失败回滚保留、零 bind 零 tmp 零 state 写静态门
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
TREE="$MOD/system/system_ext/lib64/oplusex"
seed_mod() {
    rm -rf "$OUT/postfs"
    mkdir -p "$TREE" "$OUT/bin"
    chmod +x "$OUT/bin/log"
    echo TRAMP > "$TREE/libdisplayppex.so"
    echo REAL > "$TREE/libdisplayppex.real.so"
    echo "id=lg_sf_channel" > "$MOD/module.prop"
    echo FEATURE > "$FEATURE_FILE"
    : > "$OUT/postfs_log"
}

FEATURE_FILE="$OUT/postfs/feature.so"

run_postfs() {
    LG_MODDIR="$MOD" LG_STATE_FILE="$MOD/state" LG_FEATURE_PATH="$FEATURE_FILE" \
      sh "$SCRIPT" >/dev/null 2>&1
}

tree_state() {
    for f in libdisplayppex.so libdisplayppex.real.so; do
        if [ -f "$TREE/$f" ]; then printf '%s=so ' "$f"; elif [ -f "$TREE/$f.bak" ]; then printf '%s=bak ' "$f"; else printf '%s=none ' "$f"; fi
    done
}

has_log() { grep -q "$1" "$OUT/postfs_log" 2>/dev/null; }
SO_SO="libdisplayppex.so=so libdisplayppex.real.so=so "
BAK_BAK="libdisplayppex.so=bak libdisplayppex.real.so=bak "

# A1 state 缺失（首装/重置）→ 放行
seed_mod
run_postfs
has_log "state 缺失=首装/重置 放行" && ok "postfs-missing-allow" || ng "postfs-missing-allow" "$(cat "$OUT/postfs_log")"
[ "$(tree_state)" = "$SO_SO" ] && ok "postfs-missing-untouched" || ng "postfs-missing-untouched" "$(tree_state)"

# A2 healthy → 放行
seed_mod
echo "healthy BID-1" > "$MOD/state"
run_postfs
has_log "树校验通过" && ok "postfs-healthy-allow" || ng "postfs-healthy-allow" "$(cat "$OUT/postfs_log")"
[ "$(cat "$MOD/state")" = "healthy BID-1" ] && ok "postfs-healthy-state-untouched" || ng "postfs-healthy-state-untouched" "$(cat "$MOD/state")"

# A3 suspect → 放行（单次不关门）
seed_mod
echo "suspect BID-2" > "$MOD/state"
run_postfs
has_log "state=suspect 放行" && ok "postfs-suspect-allow" || ng "postfs-suspect-allow" "$(cat "$OUT/postfs_log")"
[ "$(tree_state)" = "$SO_SO" ] && ok "postfs-suspect-untouched" || ng "postfs-suspect-untouched" "$(tree_state)"
[ "$(cat "$MOD/state")" = "suspect BID-2" ] && ok "postfs-suspect-state-untouched" || ng "postfs-suspect-state-untouched" "$(cat "$MOD/state")"

# A4 booting 残留 → 放行（旧表改 disabled 已删：postfs 只读不写）
seed_mod
echo "booting BID-3" > "$MOD/state"
run_postfs
has_log "state=booting 放行" && ok "postfs-booting-allow" || ng "postfs-booting-allow" "$(cat "$OUT/postfs_log")"
[ "$(cat "$MOD/state")" = "booting BID-3" ] && ok "postfs-booting-state-untouched" || ng "postfs-booting-state-untouched" "$(cat "$MOD/state")"
[ "$(tree_state)" = "$SO_SO" ] && ok "postfs-booting-loads" || ng "postfs-booting-loads" "$(tree_state)"

# A5 畸形 → 放行（不关门、不改写）
seed_mod
echo "garbage x y" > "$MOD/state"
run_postfs
has_log "state 畸形" && ok "postfs-malformed-allow" || ng "postfs-malformed-allow" "$(cat "$OUT/postfs_log")"
[ "$(cat "$MOD/state")" = "garbage x y" ] && ok "postfs-malformed-state-untouched" || ng "postfs-malformed-state-untouched" "$(cat "$MOD/state")"

# B disabled → 唯一拒载条件：两 so 下掉，state 不动
seed_mod
echo "disabled BID-4" > "$MOD/state"
run_postfs
[ "$(tree_state)" = "$BAK_BAK" ] && ok "postfs-disabled-reject" || ng "postfs-disabled-reject" "$(tree_state)"
[ "$(cat "$MOD/state")" = "disabled BID-4" ] && ok "postfs-disabled-state-untouched" || ng "postfs-disabled-state-untouched" "$(cat "$MOD/state")"

# C 回滚保留：healthy + trampoline 缺且无 bak → 回滚（real 也下掉，不留半态）
seed_mod
echo "healthy BID-5" > "$MOD/state"
rm -f "$TREE/libdisplayppex.so"
run_postfs
[ "$(tree_state)" = "libdisplayppex.so=none libdisplayppex.real.so=bak " ] && ok "postfs-halfstate-rollback" || ng "postfs-halfstate-rollback" "$(tree_state)"
has_log "回滚拒载安全态" && ok "postfs-rollback-log" || ng "postfs-rollback-log" "$(cat "$OUT/postfs_log")"

# C2 module.prop 缺失 → 回滚两件全下
seed_mod
echo "healthy BID-6" > "$MOD/state"
rm -f "$MOD/module.prop"
run_postfs
[ "$(tree_state)" = "$BAK_BAK" ] && ok "postfs-prop-missing-rollback" || ng "postfs-prop-missing-rollback" "$(tree_state)"

# D 树内缺件但有 bak → 恢复后放行
seed_mod
echo "suspect BID-7" > "$MOD/state"
rm -f "$TREE/libdisplayppex.so"
echo TRAMP-BAK > "$TREE/libdisplayppex.so.bak"
run_postfs
[ "$(tree_state)" = "$SO_SO" ] && ok "postfs-restore-bak" || ng "postfs-restore-bak" "$(tree_state)"
has_log "自 bak 恢复" && ok "postfs-restore-bak-log" || ng "postfs-restore-bak-log" "$(cat "$OUT/postfs_log")"

# E 拒载后的下一轮（state 仍 disabled 之外的放行态）：两 bak 恢复再放行
seed_mod
echo "disabled BID-8" > "$MOD/state"
run_postfs
echo "healthy BID-8" > "$MOD/state"
run_postfs
[ "$(tree_state)" = "$SO_SO" ] && ok "postfs-recover-after-reject" || ng "postfs-recover-after-reject" "$(tree_state)"

# F 拒载轮重复执行幂等：仍是 bak
seed_mod
echo "disabled BID-9" > "$MOD/state"
run_postfs
run_postfs
[ "$(tree_state)" = "$BAK_BAK" ] && ok "postfs-reject-idempotent" || ng "postfs-reject-idempotent" "$(tree_state)"

# F2 非 ColorOS 拒载：特征文件缺失 → 两 so 下掉 + log（state 不动）
seed_mod
echo "healthy BID-10" > "$MOD/state"
rm -f "$FEATURE_FILE"
run_postfs
[ "$(tree_state)" = "$BAK_BAK" ] && ok "postfs-notcoloros-reject" || ng "postfs-notcoloros-reject" "$(tree_state)"
has_log "非 ColorOS（特征缺失" && ok "postfs-notcoloros-log" || ng "postfs-notcoloros-log" "$(cat "$OUT/postfs_log")"
[ "$(cat "$MOD/state")" = "healthy BID-10" ] && ok "postfs-notcoloros-state-untouched" || ng "postfs-notcoloros-state-untouched" "$(cat "$MOD/state")"

# F3 特征门在 state 判定之前（disabled 轮也走特征分支）
seed_mod
echo "disabled BID-11" > "$MOD/state"
rm -f "$FEATURE_FILE"
run_postfs
has_log "非 ColorOS（特征缺失" && ok "postfs-feature-gate-first" || ng "postfs-feature-gate-first" "$(cat "$OUT/postfs_log")"

# G 静态门：不许 bind/cp/tmp，不许改写 state
if grep -q 'mount --bind\|cp -f\|/data/local/tmp' "$SCRIPT"; then
    ng "postfs-no-bind-static" "脚本含 mount/cp/tmp"
else
    ok "postfs-no-bind-static"
fi
if grep -qE '> *"\$STATE"|> *\$\{?STATE' "$SCRIPT"; then
    ng "postfs-readonly-static" "脚本存在 state 写重定向"
else
    ok "postfs-readonly-static"
fi
# 树根必须是 system/ 前缀（APatch 挂载只认 system/）
if grep -q 'MODDIR/system/system_ext/lib64/oplusex' "$SCRIPT"; then
    ok "postfs-tree-root-system-prefix"
else
    ng "postfs-tree-root-system-prefix" "$(grep 'TREE=' "$SCRIPT")"
fi
# 非 ColorOS 特征门静态在场
if grep -q 'LG_FEATURE_PATH' "$SCRIPT" && grep -q '非 ColorOS' "$SCRIPT"; then
    ok "postfs-feature-gate-static"
else
    ng "postfs-feature-gate-static" "缺 FEATURE 门"
fi
# 真身槽=自造名，禁借 DualHead
if grep -q 'libdisplayppex\.real\.so' "$SCRIPT" && ! grep -q 'libOplusDualHeadPhoneEx' "$SCRIPT"; then
    ok "postfs-real-slot-selfmade"
else
    ng "postfs-real-slot-selfmade" "$(grep -o 'lib[A-Za-z]*\.real\.so\|libOplusDualHeadPhoneEx' "$SCRIPT" | tr '
' ' ')"
fi

echo "POSTFS_RESULT: pass=$((total - fails)) fail=$fails"
[ "$fails" -eq 0 ]
