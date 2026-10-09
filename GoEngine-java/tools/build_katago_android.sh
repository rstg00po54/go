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

# Locate Eigen3's CMake package on Ubuntu/Debian and other common Linux layouts.
# An explicit EIGEN3_CMAKE_DIR takes precedence; do not create system-wide symlinks.
find_eigen_cmake_dir() {
    local dir
    if [[ -n "${EIGEN3_CMAKE_DIR:-}" ]]; then
        [[ -f "$EIGEN3_CMAKE_DIR/Eigen3Config.cmake" ]] || {
            echo "Invalid EIGEN3_CMAKE_DIR: $EIGEN3_CMAKE_DIR (Eigen3Config.cmake not found)" >&2
            return 1
        }
        printf '%s\n' "$EIGEN3_CMAKE_DIR"
        return
    fi
    for dir in /usr/lib/cmake/eigen3 /usr/share/eigen3/cmake \
               /usr/lib/x86_64-linux-gnu/cmake/eigen3 /usr/local/lib/cmake/eigen3 \
               /usr/local/share/eigen3/cmake; do
        if [[ -f "$dir/Eigen3Config.cmake" ]]; then
            printf '%s\n' "$dir"
            return
        fi
    done
    echo "Eigen3Config.cmake not found. Install libeigen3-dev or set EIGEN3_CMAKE_DIR." >&2
    return 1
}

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" && -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/ndk" ]]; then
    NDK="$(find "$ANDROID_HOME/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
fi
if [[ -z "$NDK" || ! -f "$NDK/build/cmake/android.toolchain.cmake" ]]; then
    echo "Android NDK not found. Set ANDROID_NDK_HOME to the installed NDK directory." >&2
    exit 1
fi

EIGEN_CMAKE_DIR="$(find_eigen_cmake_dir)"
echo "Eigen3 CMake dir: $EIGEN_CMAKE_DIR"
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

cmake -Wno-deprecated -S "$SOURCE_DIR/cpp" -B "$BUILD_DIR" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release -DUSE_BACKEND=EIGEN \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DCMAKE_EXE_LINKER_FLAGS=-pie \
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
HEADER="$(LC_ALL=C "$READELF" -h "$BINARY")"
echo "$HEADER" | grep -E '^[[:space:]]*(Type|Machine):' || true
if ! grep -Eq '^[[:space:]]*Machine:[[:space:]]*AArch64([[:space:]]|$)' <<< "$HEADER" || \
   ! grep -Eq '^[[:space:]]*Type:[[:space:]]*DYN([[:space:]]|$)' <<< "$HEADER"; then
    echo "ERROR: expected Android ARM64 PIE executable (AArch64, ET_DYN)." >&2
    echo "Actual ELF header:" >&2
    echo "$HEADER" >&2
    echo "Check NDK toolchain, target architecture, and PIE linker flags." >&2
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
