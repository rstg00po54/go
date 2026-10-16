#!/usr/bin/env bash
# Full RK3588 Android 11x11/15x15 10b+Human b18 NPU real-game smoke.
# Reports are uploaded by the Python runner; shell is the one-command entry.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERIAL="${ANDROID_SERIAL:-8719e18a71a2a66c}"
if [[ $# -gt 0 && "$1" != --* ]]; then
  SERIAL="$1"
  shift
fi
if [[ "${HUMAN_MULTISIZE_SKIP_BUILD:-0}" != 1 ]]; then
  echo "Building all five 10b+Human NPU size-specific models (9 11 13 15 19)"
  RKNN_MULTISIZE=1 bash "$ROOT/tools/prepare_rknn.sh"
  KATAGO_JOBS="${KATAGO_JOBS:-2}" bash "$ROOT/tools/build_katago_from_source.sh" rknnjni
  (cd "$ROOT" && chmod +x gradlew && REQUIRE_RKNN_FIVE_SIZES=1 \
    ALLOW_4K_RKNN_RUNTIME=1 bash build.sh --apk-only)
fi
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
[[ -s "$APK" ]] || { echo "Missing APK: $APK" >&2; exit 1; }
python3 "$ROOT/tools/smoke_android_npu_switch.py" \
  --serial "$SERIAL" --apk "$APK" --sizes 11,15 \
  --require-human-rknn --timeout 720 "$@"
echo "HUMAN_AND_10B_RKNN_11_15_REAL_DEVICE_PASS"
