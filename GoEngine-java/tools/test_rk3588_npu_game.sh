#!/usr/bin/env bash
# Real Android Go UI match regression: open the new-game dialog, start each game,
# tap legal board points, wait for AI moves, capture screenshots and auto-upload.
# Reuses the working tools/test_app_ui.py UI automation, not background-only GTP.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "$DIR/.." && pwd)"
SERIAL="${ANDROID_SERIAL:-8719e18a71a2a66c}"
if [[ $# -gt 0 && "$1" != --* ]]; then SERIAL="$1"; shift; fi
SIZES="${NPU_GAME_SIZES:-9,13,19,9}"
MOVES="${NPU_GAME_MOVES:-3}"
IFS=, read -r -a BOARD_LIST <<< "$SIZES"
[[ ${#BOARD_LIST[@]} -gt 0 ]] || { echo "Empty NPU_GAME_SIZES" >&2; exit 2; }
BACKENDS=""
for size in "${BOARD_LIST[@]}"; do
    case "$size" in 9|13|19) ;; *) echo "Unsupported NPU board size: $size" >&2; exit 2 ;; esac
    [[ -z "$BACKENDS" ]] && BACKENDS=npu || BACKENDS="$BACKENDS,npu"
done
APK="$PROJECT/app/build/outputs/apk/debug/app-debug.apk"
[[ -s "$APK" ]] || { echo "APK missing: $APK" >&2; exit 1; }
echo "Real UI game test on $SERIAL: sizes=$SIZES, human moves per game=$MOVES"
echo "Tests real board taps, AI moves, exact RKNN model for each size; results auto-upload."
# Print function + source:line from debuggerd via local matching ELF.
# Disabled only by setting NPU_CRASH_WATCH=0.
WATCH_PID=""
if [[ "${NPU_CRASH_WATCH:-1}" == 1 ]]; then
    python3 "$DIR/watch_npu_crash.py" --serial "$SERIAL" &
    WATCH_PID=$!
fi
cleanup_watcher() {
    if [[ -n "$WATCH_PID" ]]; then
        kill "$WATCH_PID" 2>/dev/null || true
        wait "$WATCH_PID" 2>/dev/null || true
    fi
}
trap cleanup_watcher EXIT
python3 "$DIR/test_app_ui.py" -s "$SERIAL" --backends "$BACKENDS" \
    --sizes "$SIZES" --moves "$MOVES" --install "$APK" "$@"
