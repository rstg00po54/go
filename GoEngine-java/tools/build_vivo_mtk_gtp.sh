#!/usr/bin/env bash
# Explicit experimental build for genuine KataGo genmove over MediaTek LiteRT.
# The ordinary CPU/GPU APK and normal Vivo build remain unchanged.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$DIR"

echo "Building independent Android MediaTek GTP JNI library..."
KATAGO_JOBS="${KATAGO_JOBS:-8}" bash tools/build_katago_from_source.sh mediatekjni

echo "Building playable vivo CPU/GPU APK plus opt-in MediaTek GTP JNI..."
KATAGO_JOBS="${KATAGO_JOBS:-8}" bash tools/build_vivo_npu.sh -PenableKataGoMtkJni=true
APK="$DIR/app/build/outputs/apk/debug/app-debug.apk"
unzip -l "$APK" | grep -F "lib/arm64-v8a/libkatago_mtk.so" || {
  echo "ERROR: experimental MediaTek GTP JNI not found in APK" >&2
  exit 1
}
echo "Experimental GTP test APK: $APK"
echo "Automatic real genmove smoke:"
echo "  python3 tools/smoke_vivo_litert.py --gtp --size 9 --timeout 480"
echo "NPU is NOT offered in the normal UI until this test and CPU parity pass."
