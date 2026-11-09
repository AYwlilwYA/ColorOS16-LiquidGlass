#!/bin/bash
# WSL 构建：挂载槽一致性断言 → PC 单测门禁 → linkstub → trampoline.so(aarch64) → ELF 断言 → 落位 assets
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

echo "== 0/4 挂载槽一致性断言（以打包器真身挂载槽为准） =="
REAL_SLOT=$(grep 'mount --bind' "$PACKER" | grep 'libdisplayppex\.real\.so' | sed 's/.*mount --bind [^ ]* //' | head -1)
if [ -z "$REAL_SLOT" ]; then
    echo "ASSERT-FAIL: 打包器真身挂载槽未解析"
    exit 1
fi
REAL_PATH_CPP=$(sed -n 's/^#define REAL_PATH "\(.*\)"/\1/p' "$T/trampoline.cpp")
if [ "$REAL_PATH_CPP" != "$REAL_SLOT" ]; then
    echo "ASSERT-FAIL: trampoline REAL_PATH($REAL_PATH_CPP) != 打包器真身槽($REAL_SLOT)"
    exit 1
fi
echo "ASSERT-OK 挂载槽一致: $REAL_SLOT"

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
cp "$T/sepolicy.rule" "$ASSETS/sepolicy.rule"

echo "== 产物 =="
ls -l "$ASSETS"
sha256sum "$ASSETS/trampoline.so" "$ASSETS/service.sh" "$ASSETS/sepolicy.rule"
echo "BUILD_OK"
