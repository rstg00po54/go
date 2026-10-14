#!/usr/bin/env bash
# Resolve Android libkatago_npu.so SIGSEGV PC offsets with the exact local ELF.
# Usage: bash tools/symbolize_npu_crash.sh [ADB_SERIAL] [crash_log_file]
# No APK rebuild or reinstallation required, if the matching old ELF still exists.
set -euo pipefail
SERIAL="${1:-8719e18a71a2a66c}"
LOG_FILE="${2:-}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ELF="${KATAGO_NPU_ELF:-${KATAGO_WORKDIR:-$HOME/.cache/goengine_katago}/build_android_arm64_rknnjni/libkatago_npu.so}"
if [[ ! -s "$ELF" ]]; then
    ELF="$ROOT/build/katago_android_arm64_rknnjni/libkatago_npu.so"
fi
[[ -s "$ELF" ]] || { echo "Missing locally built libkatago_npu.so. Set KATAGO_NPU_ELF to the ELF from this APK's build." >&2; exit 2; }
if [[ -n "$LOG_FILE" ]]; then
    [[ -r "$LOG_FILE" ]] || { echo "Crash log is unreadable: $LOG_FILE" >&2; exit 2; }
    CRASH="$(cat "$LOG_FILE")"
else
    CRASH="$(adb -s "$SERIAL" logcat -b crash -d -v threadtime)"
fi
# Isolate the most recent native crash so an older APK's BuildId cannot be mistaken
# for the installed binary when the log buffer holds multiple tombstones.
CRASH="$(printf '%s\\n' "$CRASH" | awk '/Fatal signal [0-9]+/ { block="" } { block=block $0 "\\n" } END { printf "%s", block }')"
[[ "$CRASH" == *libkatago_npu.so* ]] || { echo "No libkatago_npu.so frames in latest Android crash / provided log file" >&2; exit 2; }
CRASH_BUILD_ID="$(printf '%s\n' "$CRASH" | grep 'libkatago_npu.so' | sed -nE 's/.*BuildId: ([[:xdigit:]]+).*/\1/p' | head -1)"
ELF_BUILD_ID="$(readelf -n "$ELF" | sed -nE 's/.*Build ID: ([[:xdigit:]]+).*/\1/p' | head -1)"
echo "Crash Build ID: ${CRASH_BUILD_ID:-unknown}"
echo "Local ELF ID:   ${ELF_BUILD_ID:-unknown} ($ELF)"
if [[ -z "$CRASH_BUILD_ID" || -z "$ELF_BUILD_ID" || "$CRASH_BUILD_ID" != "$ELF_BUILD_ID" ]]; then
    echo "ERROR: build IDs differ or are missing: symbolizing another .so would give WRONG functions." >&2
    echo "Locate the original unstripped .so from the APK build, then set KATAGO_NPU_ELF=/path/to/libkatago_npu.so." >&2
    exit 1
fi
mapfile -t ADDRESSES < <(printf '%s\n' "$CRASH" | grep -E 'pc[[:space:]]+[[:xdigit:]]+' | grep -F libkatago_npu.so |
    sed -nE 's/.*pc[[:space:]]+([[:xdigit:]]+)[[:space:]]+.*libkatago_npu\.so.*/0x\1/p' | awk '!seen[$0]++')
[[ "${#ADDRESSES[@]}" -gt 0 ]] || { echo "No native PC offsets were found" >&2; exit 1; }
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" ]]; then
    for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk"; do
        if [[ -n "$sdk" && -d "$sdk/ndk" ]]; then NDK="$(find "$sdk/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n1)"; break; fi
    done
fi
SYM=""
if [[ -n "$NDK" && -x "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-addr2line" ]]; then
    SYM="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-addr2line"
elif command -v llvm-addr2line >/dev/null 2>&1; then
    SYM="$(command -v llvm-addr2line)"
elif command -v addr2line >/dev/null 2>&1; then
    SYM="$(command -v addr2line)"
else
    echo "Missing llvm-addr2line. Install Android NDK or llvm/binutils." >&2; exit 2
fi
echo "Crash PCs: ${ADDRESSES[*]}"
echo "Symbolizer: $SYM"
for addr in "${ADDRESSES[@]}"; do
    echo "--- $addr ---"
    "$SYM" -C -f -i -e "$ELF" "$addr"
done
echo "If output is ??:0, this Release ELF lacks DWARF; recompile a symbol-rich NPU .so, reproduce, then run again."
