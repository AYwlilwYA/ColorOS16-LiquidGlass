#!/bin/bash
# service.sh 行为单测：宿主 sh + pidof/log 桩
# 表：稳→healthy / 单崩→suspect / 双崩→disabled / 树不在位留原状 / SF 没等到留原状 / disabled 不布防 / suspect 恢复→healthy
set -u
T=$(cd "$(dirname "$0")/.." && pwd)
OUT="$T/test/out"
mkdir -p "$OUT/bin"
total=0
fails=0

ok() { total=$((total + 1)); echo "PASS $1"; }
ng() { total=$((total + 1)); fails=$((fails + 1)); echo "FAIL $1 got=[$2]"; }

cat > "$OUT/bin/pidof" <<'EOF'
#!/bin/bash
case "${LG_PIDOF_MODE:-always}" in
  always) echo 4242 ;;
  never) ;;
  change)
    c=$(cat "${LG_TEST_OUT}/pidof_count" 2>/dev/null || echo 0)
    c=$((c + 1))
    echo "$c" > "${LG_TEST_OUT}/pidof_count"
    if [ "$c" -eq 1 ]; then echo 4242; else echo 9999; fi
    ;;
esac
exit 0
EOF
cat > "$OUT/bin/log" <<'EOF'
#!/bin/bash
exit 0
EOF
chmod +x "$OUT/bin/pidof" "$OUT/bin/log"
export PATH="$OUT/bin:$PATH"
export LG_TEST_OUT="$OUT"

echo "BID-TEST" > "$OUT/boot_id"
MOD="$OUT/moddir"
TREE="$MOD/system/system_ext/lib64/oplusex"

seed_tree() {
    rm -rf "$MOD"
    mkdir -p "$TREE"
    echo TRAMP > "$TREE/libdisplayppex.so"
    echo REAL > "$TREE/libdisplayppex.real.so"
    echo "id=lg_sf_channel" > "$MOD/module.prop"
}

run_svc() {
    # $1=state file $2=pidof mode
    LG_STATE_FILE="$1" LG_MODDIR="$MOD" LG_STABLE_SEC=0 LG_SF_WAIT_SEC=2 \
      LG_BOOT_ID_FILE="$OUT/boot_id" LG_PIDOF_MODE="$2" sh "$T/service.sh" >/dev/null 2>&1
}

# A 稳 + 树在位 + 首轮（state 缺失）→ healthy
seed_tree
rm -f "$OUT/st_a" "$OUT/pidof_count"
run_svc "$OUT/st_a" always
[ "$(cat "$OUT/st_a" 2>/dev/null)" = "healthy BID-TEST" ] && ok "svc-stable-fresh-healthy" || ng "svc-stable-fresh-healthy" "$(cat "$OUT/st_a" 2>/dev/null)"

# B 单轮崩溃（pid 变）+ prev≠suspect → suspect
seed_tree
echo "booting BID-B" > "$OUT/st_b"
rm -f "$OUT/pidof_count"
run_svc "$OUT/st_b" change
[ "$(cat "$OUT/st_b" 2>/dev/null)" = "suspect BID-TEST" ] && ok "svc-crash-once-suspect" || ng "svc-crash-once-suspect" "$(cat "$OUT/st_b" 2>/dev/null)"

# C 连续两轮崩溃（prev=suspect + pid 变）→ disabled
seed_tree
echo "suspect BID-C" > "$OUT/st_c"
rm -f "$OUT/pidof_count"
run_svc "$OUT/st_c" change
[ "$(cat "$OUT/st_c" 2>/dev/null)" = "disabled BID-TEST" ] && ok "svc-crash-twice-disabled" || ng "svc-crash-twice-disabled" "$(cat "$OUT/st_c" 2>/dev/null)"

# D 树不在位 + pid 稳 → 写了 booting 后不升级，留 booting（不误标）
seed_tree
rm -f "$TREE/libdisplayppex.so"
rm -f "$OUT/st_d" "$OUT/pidof_count"
run_svc "$OUT/st_d" always
case "$(cat "$OUT/st_d" 2>/dev/null)" in
    booting\ *) ok "svc-tree-missing-keeps-booting" ;;
    *) ng "svc-tree-missing-keeps-booting" "$(cat "$OUT/st_d" 2>/dev/null)" ;;
esac

# E SF 没等到（≤2s）→ 留原状（不写 booting 不改值）
seed_tree
echo "healthy BID-E" > "$OUT/st_e"
rm -f "$OUT/pidof_count"
run_svc "$OUT/st_e" never
[ "$(cat "$OUT/st_e" 2>/dev/null)" = "healthy BID-E" ] && ok "svc-sf-absent-untouched" || ng "svc-sf-absent-untouched" "$(cat "$OUT/st_e" 2>/dev/null)"

# F disabled → 不布防不改写
seed_tree
echo "disabled BID-F" > "$OUT/st_f"
rm -f "$OUT/pidof_count"
run_svc "$OUT/st_f" always
[ "$(cat "$OUT/st_f" 2>/dev/null)" = "disabled BID-F" ] && ok "svc-disabled-untouched" || ng "svc-disabled-untouched" "$(cat "$OUT/st_f" 2>/dev/null)"

# G 上轮 suspect + 本轮稳 → healthy（自愈）
seed_tree
echo "suspect BID-G" > "$OUT/st_g"
rm -f "$OUT/pidof_count"
run_svc "$OUT/st_g" always
[ "$(cat "$OUT/st_g" 2>/dev/null)" = "healthy BID-TEST" ] && ok "svc-suspect-recovers" || ng "svc-suspect-recovers" "$(cat "$OUT/st_g" 2>/dev/null)"

echo "SERVICE_RESULT: pass=$((total - fails)) fail=$fails"
[ "$fails" -eq 0 ]
