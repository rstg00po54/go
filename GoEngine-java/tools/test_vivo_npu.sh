#!/usr/bin/env bash
# Smoke actual KataGo LiteRT graph inference on Vivo MediaTek Android.
# This validates graph execution but does not yet prove NPU delegation or
# make the normal Go UI use NPU. Results auto-upload to ui-test-results.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERIAL="${1:-${ANDROID_SERIAL:-}}"
if [[ -z "$SERIAL" ]]; then
    echo "Usage: bash tools/test_vivo_npu.sh <vivo-ADB-serial>" >&2
    adb devices
    exit 2
fi
IFS=, read -r -a SIZES <<< "${VIVO_NPU_SIZES:-9,13,19}"
FIRST=true
for SIZE in "${SIZES[@]}"; do
    case "$SIZE" in 9|13|19) ;; *) echo "Invalid board: $SIZE" >&2; exit 2;; esac
    echo "====== MediaTek LiteRT ${SIZE}x${SIZE} graph probe ======"
    ARGS=(--serial "$SERIAL" --size "$SIZE")
    [[ "$FIRST" == true ]] || ARGS+=(--skip-install)
    python3 "$DIR/smoke_vivo_litert.py" "${ARGS[@]}"
    FIRST=false
done
echo "All requested LiteRT graph probes passed. Real NPU delegation requires vendor-log verification."
