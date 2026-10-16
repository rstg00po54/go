#!/usr/bin/env bash
# Probe original Human b18 RKNN on RK3588 Android, without changing the Go APK.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERIAL="${1:-${ANDROID_SERIAL:-8719e18a71a2a66c}}"
RUN_ID="${HUMAN_RKNN_CI_RUN:-38839604816}"
WORK="$ROOT/build/rk3588_human/device_probe"
MODEL="${HUMAN_RKNN_MODEL:-$ROOT/build/rk3588_human/9/katago_human_9x9.rknn}"
MANIFEST="${HUMAN_RKNN_MANIFEST:-${MODEL%.rknn}.json}"
SDK="$ROOT/build/rknn_sdk"
mkdir -p "$WORK" "$(dirname "$MODEL")"

if [[ ! -s "$MODEL" ]]; then
  URL="https://github.com/rstg00po54/go/releases/download/rk3588-human-b18-9x9-experimental-v1/katago_human_9x9.rknn"
  if command -v curl >/dev/null 2>&1 && curl -fL --retry 2 --connect-timeout 15 -o "$MODEL.tmp" "$URL" && \
     curl -fL --retry 2 --connect-timeout 15 -o "$MANIFEST.tmp" "${URL%.rknn}.json"; then
    mv "$MANIFEST.tmp" "$MANIFEST"
    mv "$MODEL.tmp" "$MODEL"
  else
    rm -f "$MODEL.tmp" "$MANIFEST.tmp"
    if ! command -v gh >/dev/null 2>&1; then
      echo "Public release is not ready and GitHub CLI is missing." >&2
      echo "Download the model from https://github.com/rstg00po54/go/actions/runs/$RUN_ID" >&2
      echo "Set HUMAN_RKNN_MODEL to the extracted katago_human_9x9.rknn" >&2
      exit 1
    fi
    mkdir -p "$WORK/ci"
    gh run download "$RUN_ID" -R rstg00po54/go -n rk3588-b18-human-9-experimental -D "$WORK/ci"
    found="$(find "$WORK/ci" -type f -name katago_human_9x9.rknn -print -quit)"
    [[ -n "$found" ]] || { echo "CI artifact does not contain RKNN" >&2; exit 1; }
    cp "$found" "$MODEL"
    found_manifest="$(find "$WORK/ci" -type f -name katago_human_9x9.json -print -quit)"
    [[ -n "$found_manifest" ]] || { echo "Missing CI model manifest" >&2; exit 1; }
    cp "$found_manifest" "$MANIFEST"
  fi
fi
EXPECTED="3692a0726ae4e0f5fd601cdc782cb04fe60e6a0c6f06c2789f6e073d52f61474"
if [[ -s "$MANIFEST" ]]; then
  EXPECTED="$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); assert d["model"]=="b18c384nbt-humanv0" and d["board"]==9 and d["target"]=="rk3588" and d["quantized"] is False and len(d["inputs"])==4 and len(d["outputs"])==5; print(d["sha256"])' "$MANIFEST")"
fi
ACTUAL="$(sha256sum "$MODEL" | awk '{print $1}')"
if [[ "$ACTUAL" != "$EXPECTED" && "${HUMAN_RKNN_ALLOW_OTHER_MODEL:-0}" != "1" ]]; then
  echo "Model hash mismatch: $ACTUAL (expected $EXPECTED)" >&2
  echo "Only for deliberately substituted experimental models, set HUMAN_RKNN_ALLOW_OTHER_MODEL=1" >&2
  exit 1
fi

if [[ ! -s "$SDK/librknnrt.so" || ! -s "$SDK/include/rknn_api.h" ]]; then
  bash "$ROOT/tools/prepare_rknn.sh"
fi
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" ]]; then
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk"; do
    [[ -n "$sdk" && -d "$sdk/ndk" ]] || continue
    NDK="$(find "$sdk/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
    [[ -z "$NDK" ]] || break
  done
fi
COMPILER="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang++"
[[ -x "$COMPILER" ]] || { echo "Android ARM64 NDK compiler not found: $COMPILER" >&2; exit 1; }
command -v adb >/dev/null || { echo "adb required" >&2; exit 1; }
"$COMPILER" -std=c++17 -O2 -fPIE -pie -Wl,-z,max-page-size=16384 \
  -I"$SDK/include" "$ROOT/tools/human_rknn_probe.cpp" \
  -L"$SDK" -lrknnrt -o "$WORK/human_rknn_probe"

DEVICE=/data/local/tmp/katago_human_rknn_probe
adb -s "$SERIAL" shell "mkdir -p '$DEVICE'"
adb -s "$SERIAL" push "$WORK/human_rknn_probe" "$DEVICE/human_rknn_probe"
adb -s "$SERIAL" push "$SDK/librknnrt.so" "$DEVICE/librknnrt.so"
adb -s "$SERIAL" push "$MODEL" "$DEVICE/katago_human_9x9.rknn"
echo "Running original Human b18 RKNN on $SERIAL (APK untouched)"
adb -s "$SERIAL" shell "cd '$DEVICE' && chmod 755 ./human_rknn_probe && LD_LIBRARY_PATH='$DEVICE' ./human_rknn_probe ./katago_human_9x9.rknn" \
  | tee "$WORK/device_smoke.log"
grep -q '^HUMAN_RKNN_SMOKE_PASS ' "$WORK/device_smoke.log" || {
  echo "No HUMAN_RKNN_SMOKE_PASS line: check $WORK/device_smoke.log" >&2; exit 1;
}
echo "Saved: $WORK/device_smoke.log"
