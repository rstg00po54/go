#!/usr/bin/env bash
# Build the portable Android arm64 KataGo Human SL capable engine.
# By default this script DOES NOT overwrite the currently working Android engine.
set -euo pipefail

KATAGO_TAG="v1.15.0"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
WORK_DIR="${KATAGO_WORKDIR:-$HOME/.cache/goengine_katago}"
SOURCE_DIR="${KATAGO_SOURCE_DIR:-$WORK_DIR/KataGo}"
BUILD_DIR="$WORK_DIR/build_android_arm64"
OUTPUT_DIR="$PROJECT_DIR/build/katago_android_arm64"
INSTALL=0
if [[ "${1:-}" == "--install" && "$#" == 1 ]]; then
    INSTALL=1
elif [[ "$#" -ne 0 ]]; then
    echo "Usage: $0 [--install]" >&2
    exit 2
fi

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" && -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/ndk" ]]; then
    NDK="$(find "$ANDROID_HOME/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
fi
if [[ -z "$NDK" || ! -f "$NDK/build/cmake/android.toolchain.cmake" ]]; then
    echo "Android NDK not found. Set ANDROID_NDK_HOME to the installed NDK directory." >&2
    exit 1
fi

EIGEN_CMAKE_DIR="${EIGEN3_CMAKE_DIR:-/usr/share/eigen3/cmake}"
if [[ ! -f "$EIGEN_CMAKE_DIR/Eigen3Config.cmake" ]]; then
    echo "Eigen3Config.cmake not found: $EIGEN_CMAKE_DIR" >&2
    echo "Install libeigen3-dev or set EIGEN3_CMAKE_DIR." >&2
    exit 1
fi
command -v cmake >/dev/null || { echo "cmake not found" >&2; exit 1; }
command -v ninja >/dev/null || { echo "ninja not found" >&2; exit 1; }

mkdir -p "$WORK_DIR" "$OUTPUT_DIR"
if [[ ! -d "$SOURCE_DIR/.git" ]]; then
    git clone --depth 1 --branch "$KATAGO_TAG" https://github.com/lightvector/KataGo.git "$SOURCE_DIR"
fi
if [[ "$(git -C "$SOURCE_DIR" describe --tags --exact-match 2>/dev/null || true)" != "$KATAGO_TAG" ]]; then
    echo "Source at $SOURCE_DIR must be KataGo tag $KATAGO_TAG" >&2
    exit 1
fi

cmake -S "$SOURCE_DIR/cpp" -B "$BUILD_DIR" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release -DUSE_BACKEND=EIGEN \
    -DEigen3_DIR="$EIGEN_CMAKE_DIR" -DBUILD_DISTRIBUTED=OFF \
    -DNO_GIT_REVISION=ON -DUSE_AVX2=OFF -DUSE_TCMALLOC=OFF \
    -DCMAKE_CXX_FLAGS="-DLITTLE_ENDIAN=1234 -DBIG_ENDIAN=4321 -DBYTE_ORDER=1234"
cmake --build "$BUILD_DIR" --parallel "${KATAGO_JOBS:-2}"

BINARY="$BUILD_DIR/katago"
if [[ ! -f "$BINARY" ]]; then
    echo "KataGo executable not found at $BINARY" >&2
    exit 1
fi
# GNU readelf can inspect Android AArch64 ELF files from an x86-64 Linux host.
# Android NDK r27 no longer ships llvm-readelf under the expected path.
READELF="$(command -v readelf || true)"
if [[ -z "$READELF" ]]; then
    echo "readelf not found. Install binutils on the Linux build host." >&2
    exit 1
fi
HEADER="$("$READELF" -h "$BINARY")"
if ! grep -q 'Machine:.*AArch64' <<< "$HEADER" || ! grep -q 'Type:.*DYN' <<< "$HEADER"; then
    echo "Expected Android arm64 PIE executable (ELF AArch64, ET_DYN), got:" >&2
    echo "$HEADER" >&2
    exit 1
fi
cp "$BINARY" "$OUTPUT_DIR/libkatago_exec.so"
echo "Built $OUTPUT_DIR/libkatago_exec.so (KataGo $KATAGO_TAG, CPU/Eigen, Android ARM64)"
"$READELF" -d "$OUTPUT_DIR/libkatago_exec.so" | grep NEEDED || true

if (( INSTALL )); then
    DEST="$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a/libkatago_exec.so"
    if [[ -f "$DEST" ]]; then
        cp "$DEST" "$OUTPUT_DIR/libkatago_exec.previous.so"
        echo "Saved current engine at $OUTPUT_DIR/libkatago_exec.previous.so"
    fi
    cp "$OUTPUT_DIR/libkatago_exec.so" "$DEST"
    echo "Installed $DEST. Verify GTP startup before distributing an APK."
fi
