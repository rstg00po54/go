#!/usr/bin/env bash
# Real RK3588 13x13/19x19 Human b18 NPU test with automatic public GitHub
# PASS/FAIL report. Unlike the 9x9 experimental smoke, both models are
# already bundled inside the new APK; no run-as flags or manual model copies.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERIAL="${ANDROID_SERIAL:-8719e18a71a2a66c}"
if [[ $# -gt 0 && "$1" != --* ]]; then
  SERIAL="$1"
  shift
fi
if [[ "${HUMAN_MULTISIZE_SKIP_BUILD:-0}" != 1 ]]; then
  echo "Building size-specific 10b + Human b18 9/13/19 RKNN APK"
  RKNN_MULTISIZE=1 bash "$ROOT/tools/prepare_rknn.sh"
  KATAGO_JOBS="${KATAGO_JOBS:-2}" bash "$ROOT/tools/build_katago_from_source.sh" rknnjni
  (cd "$ROOT" && chmod +x gradlew && \
    REQUIRE_HUMAN_RKNN_MULTISIZE=1 ALLOW_4K_RKNN_RUNTIME=1 \
    bash build.sh --apk-only)
fi
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
[[ -s "$APK" ]] || { echo "Missing APK: $APK" >&2; exit 1; }
# Native 13/19 CPU+NPU parity compares all five heads on four actual
# KataGo Human SGF metadata positions; it is explicitly REQUIRED to pass.
python3 "$ROOT/tools/smoke_android_npu_switch.py" \
  --serial "$SERIAL" --apk "$APK" --sizes 13,19 \
  --require-human-rknn --timeout 720 "$@"
echo "HUMAN_RKNN_13_19_REAL_GAME_TEST_PASS"
