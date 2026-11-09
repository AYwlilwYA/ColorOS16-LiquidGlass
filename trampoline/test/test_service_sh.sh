#!/bin/bash
# service.sh 行为单测：宿主 sh 执行 + pidof/log 桩，覆盖预创建/锁定保留/稳定标 healthy/SF 缺席
set -u
T=$(cd "$(dirname "$0")/.." && pwd)
OUT="$T/test/out"
mkdir -p "$OUT/bin"
fails=0

cat > "$OUT/bin/pidof" <<'EOF'
#!/bin/bash
case "${LG_PIDOF_MODE:-always}" in
  always) echo 4242 ;;
  first)
    c=$(cat "${LG_TEST_OUT}/pidof_count" 2>/dev/null || echo 0)
    c=$((c + 1))
    echo "$c" > "${LG_TEST_OUT}/pidof_count"
    if [ "$c" -eq 1 ]; then echo 4242; fi
    ;;
  never) ;;
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

run_svc() {
    # $1=state file $2=pidof mode
    LG_STATE_FILE="$1" LG_STABLE_SEC=0 LG_PIDOF_MODE="$2" sh "$T/service.sh" >/dev/null 2>&1
}

ok() { echo "PASS $1"; }
ng() { echo "FAIL $1 got=[$2]"; fails=$((fails + 1)); }

# A 全新文件：预创建 → booting → 稳定后 healthy
rm -f "$OUT/st_a" "$OUT/pidof_count"
run_svc "$OUT/st_a" always
c=$(cat "$OUT/st_a" 2>/dev/null)
case "$c" in
    healthy\ *\ 0) ok "svc-fresh-healthy" ;;
    *) ng "svc-fresh-healthy" "$c" ;;
esac
[ -f "$OUT/st_a" ] && ok "svc-fresh-precreated" || ng "svc-fresh-precreated" "missing"

# B 锁定态：原样保留，不改写
printf 'disabled BID-B 0\n' >"$OUT/st_b"
rm -f "$OUT/pidof_count"
run_svc "$OUT/st_b" always
c=$(cat "$OUT/st_b" 2>/dev/null)
[ "$c" = "disabled BID-B 0" ] && ok "svc-disabled-untouched" || ng "svc-disabled-untouched" "$c"

# C 装载件已写 booting：升级 healthy 且保留 grants
printf 'booting BID-C 5\n' >"$OUT/st_c"
rm -f "$OUT/pidof_count"
run_svc "$OUT/st_c" always
c=$(cat "$OUT/st_c" 2>/dev/null)
[ "$c" = "healthy BID-C 5" ] && ok "svc-healthy-preserves-grants" || ng "svc-healthy-preserves-grants" "$c"

# D SF 缺席：不动状态
printf 'booting BID-D 0\n' >"$OUT/st_d"
rm -f "$OUT/pidof_count"
run_svc "$OUT/st_d" never
c=$(cat "$OUT/st_d" 2>/dev/null)
[ "$c" = "booting BID-D 0" ] && ok "svc-sf-absent-untouched" || ng "svc-sf-absent-untouched" "$c"

# E 窗内 SF 消失：只留 booting，不标 healthy
rm -f "$OUT/st_e" "$OUT/pidof_count"
run_svc "$OUT/st_e" first
c=$(cat "$OUT/st_e" 2>/dev/null)
case "$c" in
    booting\ *) ok "svc-sf-gone-keeps-booting" ;;
    *) ng "svc-sf-gone-keeps-booting" "$c" ;;
esac

echo "SERVICE_RESULT: pass=$((6 - fails)) fail=$fails"
[ "$fails" -eq 0 ]
