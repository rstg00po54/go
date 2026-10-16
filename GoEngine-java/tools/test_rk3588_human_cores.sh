#!/usr/bin/env bash
# Standalone, synthetic-input benchmark of b18 on RK3588 NPU core0 vs 0+1+2.
# No APK installation, no app data changes. Outer wrapper automatically uploads
# PASS or FAIL to the existing public ui-test-results Git branch.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ "${HUMAN_CORES_CHILD:-0}" != "1" ]]; then
  exec python3 "$ROOT/tools/run_rk3588_human_cores.py" "$@"
fi
SERIAL="${1:-${ANDROID_SERIAL:-8719e18a71a2a66c}}"
RUNS="${HUMAN_CORES_RUNS:-24}"
WARMUP="${HUMAN_CORES_WARMUP:-5}"
[[ "$RUNS" =~ ^[0-9]+$ && "$WARMUP" =~ ^[0-9]+$ ]] || {
  echo "Invalid HUMAN_CORES_RUNS or HUMAN_CORES_WARMUP" >&2; exit 2;
}
WORK="$ROOT/build/rk3588_human/device_probe"
SDK="$ROOT/build/rknn_sdk"
MODEL="${HUMAN_RKNN_MODEL:-$ROOT/build/rk3588_human/9/katago_human_9x9.rknn}"
DEVICE=/data/local/tmp/katago_human_rknn_probe
mkdir -p "$WORK"

echo "检查模型和 NPU 环境（不安装 APK）"
bash "$ROOT/tools/test_rk3588_human_rknn.sh" "$SERIAL"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" ]]; then
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk"; do
    [[ -n "$sdk" && -d "$sdk/ndk" ]] || continue
    NDK="$(find "$sdk/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
    [[ -n "$NDK" ]] && break
  done
fi
COMPILER="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang++"
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
[[ -x "$COMPILER" && -x "$READELF" ]] || { echo "Android NDK compiler/readelf missing" >&2; exit 1; }
"$COMPILER" -std=c++17 -O2 -fPIE -pie -static-libstdc++ -Wl,-z,max-page-size=16384 \
  -I"$SDK/include" "$ROOT/tools/human_rknn_cores.cpp" \
  -L"$SDK" -lrknnrt -o "$WORK/human_rknn_cores"
if "$READELF" -d "$WORK/human_rknn_cores" | grep -Fq 'libc++_shared.so'; then
  echo "Unexpected libc++_shared.so dependency" >&2; exit 1
fi
adb -s "$SERIAL" push "$WORK/human_rknn_cores" "$DEVICE/human_rknn_cores"
echo "开始 A/B/A 测试：单核 → 三核 → 单核，预热=$WARMUP，每阶段=$RUNS 次"
adb -s "$SERIAL" shell "cd '$DEVICE' && chmod 755 ./human_rknn_cores && LD_LIBRARY_PATH='$DEVICE' ./human_rknn_cores ./katago_human_9x9.rknn '$RUNS' '$WARMUP'"
