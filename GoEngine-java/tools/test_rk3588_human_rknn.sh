#!/usr/bin/env bash
# Probe original Human b18 RKNN on RK3588 Android, without changing the Go APK.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERIAL="${1:-${ANDROID_SERIAL:-8719e18a71a2a66c}}"
SIZE="${HUMAN_RKNN_BOARD_SIZE:-9}"
case "$SIZE" in 9|11|13|15|19) ;; *) echo "Human b18 RKNN supports 9/11/13/15/19" >&2; exit 2;; esac
RUN_ID="${HUMAN_RKNN_CI_RUN:-38839604816}"
WORK="$ROOT/build/rk3588_human/device_probe"
MODEL="${HUMAN_RKNN_MODEL:-$ROOT/build/rk3588_human/releases/$SIZE/katago_human_${SIZE}x${SIZE}.rknn}"
if [[ "$SIZE" == 9 && ! -s "$MODEL" ]]; then
  MODEL="$ROOT/build/rk3588_human/9/katago_human_9x9.rknn"
fi
MANIFEST="${HUMAN_RKNN_MANIFEST:-${MODEL%.rknn}.json}"
SDK="$ROOT/build/rknn_sdk"
mkdir -p "$WORK" "$(dirname "$MODEL")"

if [[ ! -s "$MODEL" ]]; then
  TAG="rk3588-human-b18-${SIZE}x${SIZE}-experimental-v1"
  [[ "$SIZE" != 9 ]] || TAG=rk3588-human-b18-9x9-device-verified-v1
  URL="https://github.com/rstg00po54/go/releases/download/$TAG/katago_human_${SIZE}x${SIZE}.rknn"
  if command -v curl >/dev/null 2>&1 && curl -fL --retry 2 --connect-timeout 15 -o "$MODEL.tmp" "$URL" && \
     curl -fL --retry 2 --connect-timeout 15 -o "$MANIFEST.tmp" "${URL%.rknn}.json"; then
    mv "$MANIFEST.tmp" "$MANIFEST"
    mv "$MODEL.tmp" "$MODEL"
  else
    rm -f "$MODEL.tmp" "$MANIFEST.tmp"
    if [[ "$SIZE" != 9 ]] || ! command -v gh >/dev/null 2>&1; then
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
case "$SIZE" in
  9) EXPECTED="78bee9b2561c4ba14024c1428d750e4a4cec3206011290b6b6b1d7b1e95947c7" ;;
  11) EXPECTED="d5e33822766ff86f4e06dcfda416ae791df3af292f157969f59a69713c46df29" ;;
  13) EXPECTED="8029fdfe086e96d99d2ce7fb7340cc6bab49d232c03fb44fddcfc64866eb5b40" ;;
  15) EXPECTED="73deb6fed2674fdf5061f140d80f994f121390789db534e45843a05a63158705" ;;
  19) EXPECTED="1bcdddac3687e72705f8cd058bd2c6cd44a1ee221cf30182762598dc0ad43158" ;;
esac
if [[ -s "$MANIFEST" ]]; then
  CHECKED="$(python3 - "$MANIFEST" "$SIZE" <<'PY'
import json,sys
doc=json.load(open(sys.argv[1]))
n=int(sys.argv[2])
assert doc["model"]=="b18c384nbt-humanv0" and doc["board"]==n
assert doc["target"]=="rk3588" and doc["quantized"] is False
assert doc["inputs"]=={
  "InputSpatial":[1,22,n,n],"InputGlobal":[1,19,1,1],
  "InputMeta":[1,192,1,1],"InputMask":[1,1,n,n]
}
assert doc["outputs"]==["OutputPolicyPass","OutputPolicy","OutputValue","OutputScoreValue","OutputOwnership"]
print(doc["sha256"])
PY
)"
  if [[ "$CHECKED" != "$EXPECTED" ]]; then
    echo "Human RKNN Release manifest does not match pinned SHA256" >&2; exit 1
  fi
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
"$COMPILER" -std=c++17 -O2 -fPIE -pie -static-libstdc++ -Wl,-z,max-page-size=16384 \
  -I"$SDK/include" "$ROOT/tools/human_rknn_probe.cpp" \
  -L"$SDK" -lrknnrt -o "$WORK/human_rknn_probe"

# Fail before transferring the 59 MB model if the executable needs a
# C++ shared runtime which is not staged by this standalone probe.
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
if "$READELF" -d "$WORK/human_rknn_probe" | grep -Fq 'libc++_shared.so'; then
  echo 'ERROR: probe still depends on libc++_shared.so despite static C++ link' >&2
  exit 1
fi
echo 'Android probe C++ runtime: static (libc++_shared.so not required)'

DEVICE=/data/local/tmp/katago_human_rknn_probe
adb -s "$SERIAL" shell "mkdir -p '$DEVICE'"
adb -s "$SERIAL" push "$WORK/human_rknn_probe" "$DEVICE/human_rknn_probe"
adb -s "$SERIAL" push "$SDK/librknnrt.so" "$DEVICE/librknnrt.so"
adb -s "$SERIAL" push "$MODEL" "$DEVICE/katago_human_${SIZE}x${SIZE}.rknn"
echo "Running original Human b18 RKNN on $SERIAL (APK untouched)"
adb -s "$SERIAL" shell "cd '$DEVICE' && chmod 755 ./human_rknn_probe && LD_LIBRARY_PATH='$DEVICE' ./human_rknn_probe ./katago_human_${SIZE}x${SIZE}.rknn '$SIZE'" \
  | tee "$WORK/device_smoke.log"
grep -q "^HUMAN_RKNN_SMOKE_PASS board=$SIZE " "$WORK/device_smoke.log" || {
  echo "No HUMAN_RKNN_SMOKE_PASS line: check $WORK/device_smoke.log" >&2; exit 1;
}
echo "Saved: $WORK/device_smoke.log"
