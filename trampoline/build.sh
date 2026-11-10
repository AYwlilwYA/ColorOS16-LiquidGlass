#!/bin/bash
# WSL 构建：树内槽路径断言 → PC 单测门禁 → linkstub → trampoline.so(aarch64) → ELF 断言 → 落位 assets
set -e
NDK=/mnt/f/ikenrel/nh/android-ndk-r27d
CXX="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++"
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
SYSROOT="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
ROOT=/mnt/f/_Android/ColorOS16-LiquidGlass
T="$ROOT/trampoline"
OUT="$T/out"
ASSETS="$ROOT/app/src/main/assets/lg-sf"
PACKER="$ROOT/app/src/main/kotlin/com/coloros16/liquidglass/ui/SfModulePacker.kt"
mkdir -p "$OUT" "$ASSETS"

echo "== 0/4 树内槽路径断言（以打包器 overlay 树条目为准，树根 system/ → 设备绝对路径） =="
TREE_TRAMP=$(sed -n 's/.*"\(system\/system_ext\/lib64\/oplusex\/libdisplayppex\.so\)".*/\1/p' "$PACKER" | head -1)
TREE_REAL=$(sed -n 's/.*"\(system\/system_ext\/lib64\/oplusex\/libdisplayppex\.real\.so\)".*/\1/p' "$PACKER" | head -1)
if [ -z "$TREE_TRAMP" ] || [ -z "$TREE_REAL" ]; then
    echo "ASSERT-FAIL: 打包器树内槽条目未解析（tramp='$TREE_TRAMP' real='$TREE_REAL'）"
    exit 1
fi
grep -q '"system/system_ext/lib64/oplusex/libdisplayppex.so" to trampoline' "$PACKER" || {
    echo "ASSERT-FAIL: 树内 displayppex 槽未绑定 trampoline 字节"
    exit 1
}
grep -q '"system/system_ext/lib64/oplusex/libdisplayppex.real.so" to real' "$PACKER" || {
    echo "ASSERT-FAIL: 树内自造真身槽未绑定真身字节"
    exit 1
}
if grep -q '"system/system_ext/lib64/oplusex/libOplusDualHeadPhoneEx.so"' "$PACKER"; then
    echo "ASSERT-FAIL: 借 DualHead 槽已废（nabu 无此文件且会顶原厂耳机件），树内不得再有该条目"
    exit 1
fi
if grep -q '"trampoline.so" to trampoline' "$PACKER" || grep -q '"libdisplayppex.real.so" to real' "$PACKER"; then
    echo "ASSERT-FAIL: 根条目 trampoline.so/libdisplayppex.real.so 应已删（内容进树）"
    exit 1
fi
TREE_TRAMP_ABS="/${TREE_TRAMP#system/}"
TREE_REAL_ABS="/${TREE_REAL#system/}"
REAL_PATH_CPP=$(sed -n 's/^#define REAL_PATH "\(.*\)"/\1/p' "$T/trampoline.cpp")
if [ "$REAL_PATH_CPP" != "$TREE_REAL_ABS" ]; then
    echo "ASSERT-FAIL: trampoline REAL_PATH($REAL_PATH_CPP) != 打包器树内真身槽设备路径($TREE_REAL_ABS)"
    exit 1
fi
echo "ASSERT-OK 树内槽一致: tree-root=system/ tramp=$TREE_TRAMP real=$TREE_REAL → dev $TREE_REAL_ABS"
REAL_SLOT="$TREE_REAL_ABS"

echo "== 1/4 PC 单测（单测不过不进包） =="
bash "$T/test/run_tests.sh"

echo "== 2/4 linkstub（绝对路径 soname 空桩） =="
"$CC" --target=aarch64-linux-android31 --sysroot="$SYSROOT" \
  -shared -fPIC -O2 \
  -Wl,-soname,"$REAL_SLOT" \
  -o "$OUT/linkstub.so" "$T/linkstub.c"
readelf -d "$OUT/linkstub.so" | grep -q "SONAME.*\[$REAL_SLOT\]" || {
    echo "ASSERT-FAIL: linkstub SONAME($REAL_SLOT) 与打包器槽不符"
    readelf -d "$OUT/linkstub.so" | grep SONAME
    exit 1
}

echo "== 3/4 trampoline.so (aarch64) =="
"$CXX" --target=aarch64-linux-android31 --sysroot="$SYSROOT" \
  -shared -fPIC -O2 -std=c++17 -fvisibility=hidden \
  -Wl,--no-undefined -Wl,-soname,lg_sf_trampoline.so \
  -Wl,--no-as-needed "$OUT/linkstub.so" -Wl,--as-needed \
  "$T/trampoline.cpp" -llog -ldl \
  -o "$OUT/trampoline.so"

echo "== 4/4 ELF 断言 + 落位 =="
D=$(readelf -d "$OUT/trampoline.so")
echo "$D" | grep -q "NEEDED.*\[$REAL_SLOT\]" || {
    echo "ASSERT-FAIL: DT_NEEDED 与打包器真身槽不符"
    echo "$D" | grep NEEDED
    exit 1
}
echo "$D" | grep -q 'SONAME.*\[lg_sf_trampoline.so\]' || {
    echo "ASSERT-FAIL: SONAME 不符"
    exit 1
}
EXPORTED=$(readelf --dyn-syms -W "$OUT/trampoline.so" | awk '$5 == "GLOBAL" && $6 == "DEFAULT" && $7 != "UND"' | wc -l)
if [ "$EXPORTED" -ne 0 ]; then
    echo "ASSERT-FAIL: 存在默认可见导出符号 count=$EXPORTED"
    readelf --dyn-syms -W "$OUT/trampoline.so" | awk '$5 == "GLOBAL" && $6 == "DEFAULT" && $7 != "UND"'
    exit 1
fi
echo "ASSERT-OK: NEEDED-absolute($REAL_SLOT) + SONAME + zero-export"

cp "$OUT/trampoline.so" "$ASSETS/trampoline.so"
cp "$T/service.sh" "$ASSETS/service.sh"

echo "== 产物 =="
ls -l "$ASSETS"
sha256sum "$ASSETS/trampoline.so" "$ASSETS/service.sh"
echo "BUILD_OK"
