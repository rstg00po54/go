#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

MODE="${1:-with-native}"
case "$MODE" in
    with-native|--with-native) BUILD_NATIVE=1 ;;
    --apk-only) BUILD_NATIVE=0 ;;
    *) echo "Usage: $0 [--with-native|--apk-only]" >&2; exit 2 ;;
esac

echo "== Badukai Android ARM64 build =="

echo "[1/3] clean"
./gradlew clean

if (( BUILD_NATIVE )); then
    echo "[2/3] Compile KataGo C++ (Android ARM64, CPU/Eigen)"
    echo "       Requires Android NDK, CMake, Ninja and libeigen3-dev."
    echo "       For an APK-only rebuild, run: ./build.sh --apk-only"
    # Choose C++ parallelism based on available cores and memory (~1.5 GiB per job).
    # Override when desired: KATAGO_JOBS=8 ./build.sh
    if [[ -z "${KATAGO_JOBS:-}" ]]; then
        CPU_JOBS="$(nproc 2>/dev/null || echo 2)"
        AVAILABLE_KB="$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo 2>/dev/null || true)"
        if [[ "$AVAILABLE_KB" =~ ^[0-9]+$ ]]; then
            MEM_JOBS=$((AVAILABLE_KB / 1572864))
            if (( MEM_JOBS < 1 )); then MEM_JOBS=1; fi
        else
            MEM_JOBS=2
        fi
        KATAGO_JOBS=$((CPU_JOBS < MEM_JOBS ? CPU_JOBS : MEM_JOBS))
        if (( KATAGO_JOBS > 8 )); then KATAGO_JOBS=8; fi
    fi
    if ! [[ "$KATAGO_JOBS" =~ ^[1-9][0-9]*$ ]]; then
        echo "ERROR: KATAGO_JOBS must be a positive integer" >&2
        exit 2
    fi
    export KATAGO_JOBS
    echo "KataGo C++ parallel jobs: $KATAGO_JOBS"
    bash tools/build_katago_from_source.sh eigen

    SRC="$PROJECT_DIR/build/katago_android_arm64_eigen/libkatago_exec.so"
    DEST="$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a/libkatago_exec.so"
    BACKUP="$PROJECT_DIR/build/katago_android_arm64_eigen/libkatago_exec.previous.so"
    [[ -s "$SRC" ]] || { echo "ERROR: native build did not produce $SRC" >&2; exit 1; }
    mkdir -p "$(dirname "$DEST")"
    if [[ -f "$DEST" ]]; then
        cp -p "$DEST" "$BACKUP"
        echo "Previous working engine backed up: $BACKUP"
    fi
    cp "$SRC" "$DEST"
    echo "Native engine copied into APK inputs: $DEST"
else
    echo "[2/3] SKIPPED C++ (--apk-only): using existing libkatago_exec.so"
fi

echo "[3/3] assembleDebug"
./gradlew assembleDebug

APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
[[ -s "$APK" ]] || { echo "ERROR: APK not found: $APK" >&2; exit 1; }

echo
echo "Build successful"
echo "APK: $APK"
ls -lh "$APK"
