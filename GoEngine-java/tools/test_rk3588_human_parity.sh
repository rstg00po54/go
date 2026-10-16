#!/usr/bin/env bash
# Compare RK3588 Human b18 NPU with ORIGINAL KataGo b18 ONNX CPU reference.
# Existing Android APK is untouched. The first board smoke stages the model.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Outer invocation records stdout/stderr, summarizes all five heads, and
# uploads even a FAIL. The inner invocation is kept as the original board test.
# --no-upload keeps everything local; --upload-existing retries without ADB.
if [[ "${HUMAN_PARITY_CHILD:-0}" != "1" ]]; then
  exec python3 "$ROOT/tools/run_rk3588_human_parity.py" "$@"
fi
SERIAL="${1:-${ANDROID_SERIAL:-8719e18a71a2a66c}}"
SIZE="${HUMAN_RKNN_BOARD_SIZE:-9}"
case "$SIZE" in 9|13|19) ;; *) echo "Unknown Human b18 board $SIZE" >&2; exit 2;; esac
WORK="$ROOT/build/rk3588_human/device_probe"
SDK="$ROOT/build/rknn_sdk"
GOLDEN="$WORK/human_b18_cpu${SIZE}_reference.bin"
GOLDEN_META="$WORK/human_b18_cpu${SIZE}_reference.json"
BASE="https://github.com/rstg00po54/go/releases/download/rk3588-human-b18-${SIZE}x${SIZE}-experimental-v1"
mkdir -p "$WORK"

# Reuses local model and validates its checksum/manifest. Stages NPU runtime.
bash "$ROOT/tools/test_rk3588_human_rknn.sh" "$SERIAL"

if [[ ! -s "$GOLDEN" || ! -s "$GOLDEN_META" ]]; then
  curl -fL --retry 3 --connect-timeout 15 -o "$GOLDEN.tmp" "$BASE/human_b18_cpu${SIZE}_reference.bin"
  curl -fL --retry 3 --connect-timeout 15 -o "$GOLDEN_META.tmp" "$BASE/human_b18_cpu${SIZE}_reference.json"
  mv "$GOLDEN.tmp" "$GOLDEN"
  mv "$GOLDEN_META.tmp" "$GOLDEN_META"
fi
python3 - "$GOLDEN" "$GOLDEN_META" "$SIZE" <<'PY'
import hashlib,json,pathlib,sys
path,meta=map(pathlib.Path,sys.argv[1:3])
size=int(sys.argv[3])
doc=json.loads(meta.read_text())
assert doc["model"]=="b18c384nbt-humanv0"
assert doc["board"]==size and doc["cases"]==4
assert doc["format"]=="B18REF01-le-f32" and doc["cpuBackend"]=="onnxruntime-cpu"
assert doc["syntheticInputsOnly"] is True
assert doc["inputCounts"]==[22*size*size,19,192,size*size]
assert doc["outputCounts"]==[2,2*size*size,3,6,size*size]
actual=hashlib.sha256(path.read_bytes()).hexdigest()
assert actual==doc["fixtureSha256"],"Original ONNX CPU golden fixture checksum mismatch"
print("HUMAN_CPU_REFERENCE_SHA_PASS sha256="+actual)
PY

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" ]]; then
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk"; do
    [[ -n "$sdk" && -d "$sdk/ndk" ]] || continue
    NDK="$(find "$sdk/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
    [[ -z "$NDK" ]] || break
  done
fi
COMPILER="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang++"
[[ -x "$COMPILER" ]] || { echo "Missing Android arm64 compiler: $COMPILER" >&2; exit 1; }
"$COMPILER" -std=c++17 -O2 -fPIE -pie -static-libstdc++ -Wl,-z,max-page-size=16384 \
  -I"$SDK/include" "$ROOT/tools/human_rknn_parity.cpp" \
  -L"$SDK" -lrknnrt -o "$WORK/human_rknn_parity"
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
if "$READELF" -d "$WORK/human_rknn_parity" | grep -Fq 'libc++_shared.so'; then
  echo "ERROR: parity executable requires libc++_shared.so" >&2
  exit 1
fi

DEVICE="/data/local/tmp/katago_human_rknn_probe"
adb -s "$SERIAL" push "$WORK/human_rknn_parity" "$DEVICE/human_rknn_parity"
adb -s "$SERIAL" push "$GOLDEN" "$DEVICE/human_b18_cpu${SIZE}_reference.bin"
echo "Comparing RK3588 NPU to ORIGINAL Human b18 ONNX CPU reference..."
adb -s "$SERIAL" shell "cd '$DEVICE' && chmod 755 ./human_rknn_parity && LD_LIBRARY_PATH='$DEVICE' ./human_rknn_parity ./katago_human_${SIZE}x${SIZE}.rknn ./human_b18_cpu${SIZE}_reference.bin" \
  | tee "$WORK/human_parity.log"
grep -q '^HUMAN_CPU_NPU_PARITY PASS ' "$WORK/human_parity.log" || {
  echo "Human b18 CPU/NPU parity FAIL. See $WORK/human_parity.log" >&2
  exit 1
}
echo "HUMAN_RKNN_PARITY_SCRIPT_PASS report=$WORK/human_parity.log"
