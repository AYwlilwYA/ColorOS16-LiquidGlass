#!/bin/bash
# PC 侧单测：装载层转发准入 + 状态机表 + 门禁金丝雀 + 护栏节流；含故意失败的自证门
set -u
T=$(cd "$(dirname "$0")/.." && pwd)
OUT="$T/test/out"
mkdir -p "$OUT"

fail=0

echo "== build test artifacts =="
gcc -shared -fPIC -O1 -o "$OUT/libreal.so" "$T/test/real_lib.c" || exit 1
gcc -shared -fPIC -Wl,-soname,"$OUT/libreal.so" -o "$OUT/linkstub_host.so" "$T/linkstub.c" || exit 1
g++ -shared -fPIC -O1 -std=c++17 -DLG_HOST_TEST -fvisibility=hidden \
  -Wl,--no-undefined -Wl,-soname,lg_sf_trampoline.so \
  -Wl,--no-as-needed "$OUT/linkstub_host.so" -Wl,--as-needed \
  "$T/trampoline.cpp" -ldl -o "$OUT/trampoline_host.so" || exit 1
g++ -O1 -std=c++17 -DLG_HOST_TEST "$T/test/test_trampoline.cpp" -ldl \
  -o "$OUT/test_trampoline" || exit 1

echo "== ELF 断言（host 构建） =="
D=$(readelf -d "$OUT/trampoline_host.so")
echo "$D" | grep -q "NEEDED.*\[$OUT/libreal.so\]" || { echo "ASSERT-FAIL NEEDED abs path"; exit 1; }
echo "$D" | grep -q "SONAME.*\[lg_sf_trampoline.so\]" || { echo "ASSERT-FAIL SONAME"; exit 1; }
echo "ASSERT-OK NEEDED-absolute+SONAME"

echo "== 反证门（故意失败必须非 0 退出，否则测试框架本身失效） =="
LG_TEST_SELFCHECK=1 LG_TEST_OUT="$OUT" \
  "$OUT/test_trampoline" --selfcheck >"$OUT/selfcheck.log" 2>&1
rc=$?
if [ "$rc" -eq 0 ]; then
  echo "CONTROL selfcheck: FAIL（框架不成立，拒绝出结果）"
  exit 1
fi
echo "CONTROL selfcheck: OK（故意失败被正确判红 rc=$rc）"

echo "== 单测主跑 =="
LG_TEST_OUT="$OUT" \
LG_TEST_TRAMP_SO="$OUT/trampoline_host.so" \
LG_TEST_REAL_SO="$OUT/libreal.so" \
  "$OUT/test_trampoline" | tee "$OUT/unit.log"
rc=${PIPESTATUS[0]}
if [ "$rc" -ne 0 ]; then
  echo "UNITTEST: FAIL"
  exit 1
fi

echo "== service.sh 语法 + 行为单测 =="
sh -n "$T/service.sh" || { echo "ASSERT-FAIL service.sh syntax"; exit 1; }
bash "$T/test/test_service_sh.sh" || { echo "UNITTEST service.sh: FAIL"; exit 1; }

echo "UNITTEST_OK"
