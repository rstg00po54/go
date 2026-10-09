#!/usr/bin/env bash
# Run the same KataGo benchmark on Android CPU/Eigen or GPU/OpenCL executables.
# Nothing is installed into the app, and the working CPU APK is untouched.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
MODE="${1:-gpu}"
if [[ $# -gt 1 || ( "$MODE" != "gpu" && "$MODE" != "cpu" ) ]]; then
    echo "Usage: $0 [gpu|cpu]" >&2
    exit 2
fi

WORK_DIR="${KATAGO_WORKDIR:-$HOME/.cache/goengine_katago}"
if [[ "$MODE" == "gpu" ]]; then
    BINARY="$PROJECT_DIR/build/katago_android_arm64_opencl/libkatago_exec_opencl.so"
else
    BINARY="$PROJECT_DIR/app/build/generated/katagoJniLibs/arm64-v8a/libkatago_exec.so"
    [[ -f "$BINARY" ]] || BINARY="$WORK_DIR/build_android_arm64_eigen/katago"
fi
[[ -f "$BINARY" ]] || { echo "Missing $MODE binary: $BINARY" >&2; exit 1; }

MODEL="$PROJECT_DIR/app/src/main/assets/engine/10b.bin"
CONFIG="$PROJECT_DIR/app/src/main/assets/engine/default_gtp.cfg"
[[ -f "$MODEL" && -f "$CONFIG" ]] || { echo "Bundled 10b model/config not found" >&2; exit 1; }

ADB="${ADB:-$(command -v adb || true)}"
[[ -n "$ADB" ]] || { echo "adb not found; install Android platform-tools or set ADB=/path/to/adb" >&2; exit 1; }
SERIAL="${ANDROID_SERIAL:-}"
if [[ -z "$SERIAL" ]]; then
    mapfile -t devices < <("$ADB" devices | awk 'NR > 1 && $2 == "device" {print $1}')
    if [[ "${#devices[@]}" -ne 1 ]]; then
        echo "Connect exactly one authorized Android device (or set ANDROID_SERIAL)." >&2
        exit 1
    fi
    SERIAL="${devices[0]}"
fi

REMOTE="/data/local/tmp/katago_bench"
NAME="katago_$MODE"
VISITS="${KATAGO_BENCH_VISITS:-100}"
THREADS="${KATAGO_BENCH_THREADS:-2}"
POSITIONS="${KATAGO_BENCH_POSITIONS:-2}"
BOARD="${KATAGO_BENCH_BOARD:-19}"

echo "KataGo $MODE benchmark on $SERIAL, board=$BOARD visits=$VISITS threads=$THREADS positions=$POSITIONS"
"$ADB" -s "$SERIAL" shell "mkdir -p $REMOTE"
"$ADB" -s "$SERIAL" push "$BINARY" "$REMOTE/$NAME" >/dev/null
"$ADB" -s "$SERIAL" push "$MODEL" "$REMOTE/10b.bin" >/dev/null
"$ADB" -s "$SERIAL" push "$CONFIG" "$REMOTE/default_gtp.cfg" >/dev/null
"$ADB" -s "$SERIAL" shell "chmod 755 $REMOTE/$NAME"

# On some Mali devices /vendor/lib64/libOpenCL.so has SONAME=libGLES_mali.so,
# but no file named libGLES_mali.so exists in the default executable namespace.
# Stage the same vendor library under its requested SONAME inside the bench dir.
# Do not modify /vendor or package proprietary libraries in the app.
if [[ "$MODE" == "gpu" ]] && readelf -d "$BINARY" | grep -Fq '[libGLES_mali.so]'; then
    echo "Staging Mali OpenCL runtime as $REMOTE/libGLES_mali.so"
    "$ADB" -s "$SERIAL" shell "cp /vendor/lib64/libOpenCL.so $REMOTE/libGLES_mali.so" || {
        echo "Failed to stage device's Mali OpenCL library; check vendor path/permissions." >&2
        exit 1
    }
fi

# KataGo deliberately uses single-dash long options (see command/commandline.h).
"$ADB" -s "$SERIAL" shell "cd $REMOTE && LD_LIBRARY_PATH=$REMOTE:/vendor/lib64:/system/vendor/lib64 $REMOTE/$NAME benchmark -model 10b.bin -config default_gtp.cfg -v $VISITS -t $THREADS -n $POSITIONS -boardsize $BOARD"
