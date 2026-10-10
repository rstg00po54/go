#!/usr/bin/env bash
# Compare Android vendor OpenCL ELF ABI on RK3588 and phones.
# Read-only diagnostics: no device mutations, no proprietary libraries committed.
set -euo pipefail
export LC_ALL=C

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ADB="${ADB:-$(command -v adb || true)}"
READELF="${READELF:-$(command -v readelf || true)}"
[[ -n "$ADB" && -n "$READELF" ]] || { echo "Requires adb and readelf" >&2; exit 1; }
if (( $# < 1 )); then
    echo "Usage: bash tools/diagnose_katago_opencl_abi_android.sh <serial1> [serial2 ...]" >&2
    exit 2
fi

BIN="$PROJECT_DIR/build/katago_android_arm64_opencl/libkatago_exec_opencl.so"
[[ -f "$BIN" ]] || { echo "GPU binary missing: $BIN" >&2; exit 1; }
REPORT_DIR="${KATAGO_DIAG_REPORT_DIR:-$PROJECT_DIR/build/katago_opencl_abi/$(date +%Y%m%d_%H%M%S)}"
mkdir -p "$REPORT_DIR"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

# Keep exact dynamic dependencies and version requirements for inspection.
"$READELF" -W -h -d -V "$BIN" > "$REPORT_DIR/katago_gpu_elf.txt"
echo "GPU executable: $BIN"
echo "GPU ELF dependencies:"
"$READELF" -W -d "$BIN" | grep -E '\((NEEDED|RUNPATH|RPATH)\)' || true
echo "GPU version requirements:"
"$READELF" -W -V "$BIN" | grep -E 'File:|Name:' | head -n 60 || true
echo

for serial in "$@"; do
    [[ "$serial" =~ ^[[:alnum:]_.:-]+$ ]] || { echo "Invalid ADB serial: $serial" >&2; exit 2; }
    safe="${serial//:/_}"
    out="$REPORT_DIR/$safe"
    mkdir -p "$out"
    {
        echo "serial=$serial"
        echo "model=$("$ADB" -s "$serial" shell getprop ro.product.model | tr -d '\r')"
        echo "android=$("$ADB" -s "$serial" shell getprop ro.build.version.release | tr -d '\r')"
        echo "abi=$("$ADB" -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
    } | tee "$out/device.txt"
    any=0
    for lib in /vendor/lib64/libOpenCL.so /vendor/lib64/libGLES_mali.so \
               /vendor/lib64/egl/libGLES_mali.so /system/vendor/lib64/libOpenCL.so; do
        if ! "$ADB" -s "$serial" shell "test -r '$lib'" >/dev/null 2>&1; then
            echo "Not readable: $lib" | tee -a "$out/device.txt"
            continue
        fi
        name="${lib//\//_}"
        local_tmp="$TMP_DIR/${safe}_${name}"
        if ! "$ADB" -s "$serial" pull "$lib" "$local_tmp" >/dev/null; then
            echo "Pull failed: $lib" | tee -a "$out/device.txt"
            continue
        fi
        any=1
        "$READELF" -W -h -d -V "$local_tmp" > "$out/${name}.elf.txt"
        echo "Library: $lib"
        echo "SHA256: $(sha256sum "$local_tmp" | awk '{print $1}')"
        "$READELF" -W -d "$local_tmp" | grep -E '\((SONAME|NEEDED)\)' || true
        "$READELF" -W -V "$local_tmp" | grep -E 'File:|Name:' | head -n 25 || true
        echo
    done
    if (( any == 0 )); then
        echo "WARNING: no readable OpenCL or Mali GPU library on $serial" >&2
    fi
done
echo "ELF reports: $REPORT_DIR"
echo "Vendor libraries were inspected in a temporary directory and removed."
