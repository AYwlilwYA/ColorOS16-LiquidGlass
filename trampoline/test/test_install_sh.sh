#!/bin/bash
# install.sh（打包器动态生成模板）行为单测：提取模板 + 渲染设备参数 → 宿主 sh 跑安装判定表
# 表：SRC_BAD / NOT_COLOROS / SELF_COPY / INSTALLED / 回显标记齐全（SfUiStubs 分支消费）
set -u
T=$(cd "$(dirname "$0")/.." && pwd)
ROOT=$(cd "$T/.." && pwd)
PACKER="$ROOT/app/src/main/kotlin/com/coloros16/liquidglass/ui/SfModulePacker.kt"
STUBS="$ROOT/app/src/main/kotlin/com/coloros16/liquidglass/ui/SfUiStubs.kt"
OUT="$T/test/out"
mkdir -p "$OUT/install-bin"
total=0
fails=0

ok() { total=$((total + 1)); echo "PASS $1"; }
ng() { total=$((total + 1)); fails=$((fails + 1)); echo "FAIL $1 got=[$2]"; }

# 从 Kotlin raw string 提取模板 → 还原 ${'$'} 转义 → 渲染 MODULE_ID/REAL_SO_PATH（模拟 Kotlin 插值）
SCRIPT="$OUT/install-under-test.sh"
awk '/private fun installSh\(\): String = """$/{f=1;next} f&&/"""\.trimIndent\(\)/{f=0} f{sub(/^        /,""); gsub(/\$\{.\$.\}/,"$"); print}' "$PACKER" > "$SCRIPT.raw"
[ -s "$SCRIPT.raw" ] || { echo "FAIL install-extract 空"; exit 1; }
sed -e 's|\$MODULE_ID|lg_sf_channel|g' \
    -e 's|\$REAL_SO_PATH|/system_ext/lib64/oplusex/libdisplayppex.so|g' \
    "$SCRIPT.raw" > "$SCRIPT"
grep -q 'echo INSTALLED' "$SCRIPT" || { echo "FAIL install-extract 内容不符"; exit 1; }
grep -q '/data/adb/modules/lg_sf_channel' "$SCRIPT" || { echo "FAIL install-extract MODULE_ID 未渲染"; exit 1; }
grep -q '/system_ext/lib64/oplusex/libdisplayppex.so' "$SCRIPT" || { echo "FAIL install-extract REAL_SO_PATH 未渲染"; exit 1; }
if grep -q '\$MODULE_ID\|\$REAL_SO_PATH' "$SCRIPT"; then
    echo "FAIL install-extract 渲染残留 \$MODULE_ID/\$REAL_SO_PATH"
    exit 1
fi
ok "install-extract-render"

# 桩：root 域命令在 host 不可执行 → chown 放行；独立 bin 目录不共享其它单测的桩
cat > "$OUT/install-bin/chown" <<'EOF'
#!/bin/bash
exit 0
EOF
chmod +x "$OUT/install-bin/chown"
export PATH="$OUT/install-bin:$PATH"

WORK="$OUT/install"
seed_src() {
    rm -rf "$WORK"
    mkdir -p "$WORK/src/system/system_ext/lib64/oplusex"
    echo "id=lg_sf_channel" > "$WORK/src/module.prop"
    echo TRAMP > "$WORK/src/system/system_ext/lib64/oplusex/libdisplayppex.so"
    echo REAL > "$WORK/src/system/system_ext/lib64/oplusex/libdisplayppex.real.so"
    echo "#!/system/bin/sh" > "$WORK/src/service.sh"
    echo FEATURE > "$WORK/feature.so"
}

run_install() {
    LG_MODDIR="$WORK/mod" LG_FEATURE_PATH="$WORK/feature.so" \
      sh "$WORK/src/install.sh" 2>/dev/null
}

# I1 正常安装 → INSTALLED + 树落位 + module.prop 在
seed_src
cp "$SCRIPT" "$WORK/src/install.sh"
out=$(run_install)
[ "$out" = "INSTALLED" ] && ok "install-ok" || ng "install-ok" "$out"
[ -f "$WORK/mod/module.prop" ] && ok "install-prop-landed" || ng "install-prop-landed" "缺 module.prop"
[ -f "$WORK/mod/system/system_ext/lib64/oplusex/libdisplayppex.so" ] && ok "install-tramp-landed" || ng "install-tramp-landed" "缺树内 tramp"
[ -f "$WORK/mod/install.sh" ] && ok "install-script-landed" || ng "install-script-landed" "缺 install.sh"

# I2 特征缺失 → NOT_COLOROS（第一道拒载）
seed_src
cp "$SCRIPT" "$WORK/src/install.sh"
rm -f "$WORK/feature.so"
out=$(run_install)
[ "$out" = "NOT_COLOROS" ] && ok "install-notcoloros" || ng "install-notcoloros" "$out"
[ ! -d "$WORK/mod" ] && ok "install-notcoloros-no-land" || ng "install-notcoloros-no-land" "mod 已被建"

# I3 SRC 不完整（缺 module.prop）→ SRC_BAD
seed_src
cp "$SCRIPT" "$WORK/src/install.sh"
rm -f "$WORK/src/module.prop"
out=$(run_install)
[ "$out" = "SRC_BAD" ] && ok "install-src-bad" || ng "install-src-bad" "$out"

# I3b SRC 缺树内 tramp → SRC_BAD
seed_src
cp "$SCRIPT" "$WORK/src/install.sh"
rm -f "$WORK/src/system/system_ext/lib64/oplusex/libdisplayppex.so"
out=$(run_install)
[ "$out" = "SRC_BAD" ] && ok "install-src-bad-tree" || ng "install-src-bad-tree" "$out"

# I4 自拷贝守卫：完整模块树本身位于 MODDIR 内执行 → SELF_COPY
seed_src
mkdir -p "$WORK/mod"
cp -rf "$WORK/src/." "$WORK/mod/"
cp "$SCRIPT" "$WORK/mod/install.sh"
out=$(LG_MODDIR="$WORK/mod" LG_FEATURE_PATH="$WORK/feature.so" sh "$WORK/mod/install.sh" 2>/dev/null)
[ "$out" = "SELF_COPY" ] && ok "install-self-copy" || ng "install-self-copy" "$out"
[ -f "$WORK/mod/module.prop" ] && ok "install-self-copy-untouched" || ng "install-self-copy-untouched" "mod 被清"

# I5 回显标记与 SfUiStubs 分支一一对齐（防 UI 分支悬空）
for mark in INSTALLED SRC_BAD SELF_COPY NOT_COLOROS RM_FAIL MKDIR_FAIL COPY_FAIL MISSING; do
    grep -qE "echo \"?$mark\"?" "$SCRIPT" || { ng "install-mark-$mark" "脚本缺 $mark"; continue; }
    if [ "$mark" = "MISSING" ]; then
        ok "install-mark-$mark"
    else
        grep -q "\"$mark\"" "$STUBS" && ok "install-mark-$mark" || ng "install-mark-$mark" "SfUiStubs 无 $mark 分支"
    fi
done

# I6 静态门：不 restorecon、不写 state、特征门在拷贝之前
if grep -q 'restorecon' "$SCRIPT"; then ng "install-no-restorecon" "含 restorecon"; else ok "install-no-restorecon"; fi
if grep -q 'state' "$SCRIPT"; then ng "install-no-state" "含 state 写"; else ok "install-no-state"; fi
feature_line=$(grep -n 'NOT_COLOROS' "$SCRIPT" | head -1 | cut -d: -f1)
copy_line=$(grep -n 'rm -rf "\$MODDIR"' "$SCRIPT" | head -1 | cut -d: -f1)
if [ -n "$feature_line" ] && [ -n "$copy_line" ] && [ "$feature_line" -lt "$copy_line" ]; then
    ok "install-feature-gate-before-copy"
else
    ng "install-feature-gate-before-copy" "feature=$feature_line copy=$copy_line"
fi

rm -f "$SCRIPT.raw"
echo "INSTALL_RESULT: pass=$((total - fails)) fail=$fails"
[ "$fails" -eq 0 ]
