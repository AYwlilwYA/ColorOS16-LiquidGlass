#!/bin/bash
# service.sh 行为单测：宿主 sh 执行 + pidof/log 桩，覆盖 disabled 保持/SF 缺席/pid 稳定写 healthy/pid 变化留 booting/装载件缺失留 booting
set -u
T=$(cd "$(dirname "$0")/.." && pwd)
OUT="$T/test/out"
mkdir -p "$OUT/bin"
fails=0

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
mkdir -p "$MOD"
touch "$MOD/trampoline.so"

run_svc() {
    # $1=state file $2=pidof mode $3=moddir
    LG_STATE_FILE="$1" LG_MODDIR="$3" LG_STABLE_SEC=0 LG_SF_WAIT_SEC=2 \
      LG_BOOT_ID_FILE="$OUT/boot_id" LG_PIDOF_MODE="$2" sh "$T/service.sh" >/dev/null 2>&1
}

ok() { echo "PASS $1"; }
ng() { echo "FAIL $1 got=[$2]"; fails=$((fails + 1)); }

# A 首轮（state 缺失）+ pid 稳定 + 装载件在位 → healthy
rm -f "$OUT/st_a" "$OUT/pidof_count"
run_svc "$OUT/st_a" always "$MOD"
c=$(cat "$OUT/st_a" 2>/dev/null)
[ "$c" = "healthy BID-TEST" ] && ok "svc-fresh-healthy" || ng "svc-fresh-healthy" "$c"

# B disabled 残留：原样保留不改写、不布防
printf 'disabled BID-B\n' >"$OUT/st_b"
rm -f "$OUT/pidof_count"
run_svc "$OUT/st_b" always "$MOD"
c=$(cat "$OUT/st_b" 2>/dev/null)
[ "$c" = "disabled BID-B" ] && ok "svc-disabled-untouched" || ng "svc-disabled-untouched" "$c"

# C 上轮 booting 残留 + pid 稳定 → 升 healthy
printf 'booting BID-C\n' >"$OUT/st_c"
rm -f "$OUT/pidof_count"
run_svc "$OUT/st_c" always "$MOD"
c=$(cat "$OUT/st_c" 2>/dev/null)
[ "$c" = "healthy BID-TEST" ] && ok "svc-booting-promotes" || ng "svc-booting-promotes" "$c"

# D SF 缺席：不写状态
printf 'booting BID-D\n' >"$OUT/st_d"
rm -f "$OUT/pidof_count"
run_svc "$OUT/st_d" never "$MOD"
c=$(cat "$OUT/st_d" 2>/dev/null)
[ "$c" = "booting BID-D" ] && ok "svc-sf-absent-untouched" || ng "svc-sf-absent-untouched" "$c"

# E 稳定窗内 pid 变化（init 秒拉新）：留 booting 不写 healthy
rm -f "$OUT/st_e" "$OUT/pidof_count"
run_svc "$OUT/st_e" change "$MOD"
c=$(cat "$OUT/st_e" 2>/dev/null)
case "$c" in
    booting\ *) ok "svc-pid-changed-keeps-booting" ;;
    *) ng "svc-pid-changed-keeps-booting" "$c" ;;
esac

# F 装载件不在位（拒载轮）：留 booting 不写 healthy
MOD_EMPTY="$OUT/moddir_empty"
mkdir -p "$MOD_EMPTY"
rm -f "$OUT/st_f" "$OUT/pidof_count"
run_svc "$OUT/st_f" always "$MOD_EMPTY"
c=$(cat "$OUT/st_f" 2>/dev/null)
case "$c" in
    booting\ *) ok "svc-so-missing-keeps-booting" ;;
    *) ng "svc-so-missing-keeps-booting" "$c" ;;
esac

echo "SERVICE_RESULT: pass=$((6 - fails)) fail=$fails"
[ "$fails" -eq 0 ]
