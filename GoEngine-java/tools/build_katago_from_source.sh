#!/usr/bin/env bash
# Build KataGo from the vendored upstream C++ source files in this repository.
# Experimental GPU output is separate: the working CPU .so is never replaced.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SOURCE_DIR="${KATAGO_SOURCE_DIR:-$PROJECT_DIR/native/KataGo}"
BACKEND="${1:-eigen}"
if [[ $# -gt 1 || ("$BACKEND" != "eigen" && "$BACKEND" != "opencl") ]]; then
    echo "Usage: $0 [eigen|opencl]" >&2
    exit 2
fi
if [[ ! -f "$SOURCE_DIR/cpp/CMakeLists.txt" ]]; then
    echo "Missing KataGo C++ sources at $SOURCE_DIR" >&2
    echo "Fetch the latest rk3588-engine branch; KataGo sources are tracked directly." >&2
    exit 1
fi

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" && -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/ndk" ]]; then
    NDK="$(find "$ANDROID_HOME/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
fi
if [[ ! -f "$NDK/build/cmake/android.toolchain.cmake" ]]; then
    echo "Android NDK not found; set ANDROID_NDK_HOME." >&2
    exit 1
fi
command -v cmake >/dev/null || { echo "cmake not found" >&2; exit 1; }
command -v ninja >/dev/null || { echo "ninja not found" >&2; exit 1; }
READELF="$(command -v readelf || true)"
[[ -n "$READELF" ]] || { echo "readelf (binutils) not found" >&2; exit 1; }

WORK_DIR="${KATAGO_WORKDIR:-$HOME/.cache/goengine_katago}"
BUILD_DIR="$WORK_DIR/build_android_arm64_${BACKEND}"
OUTPUT_DIR="$PROJECT_DIR/build/katago_android_arm64_${BACKEND}"
mkdir -p "$OUTPUT_DIR"

ARGS=(
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static
    -DCMAKE_BUILD_TYPE=Release -DUSE_BACKEND="${BACKEND^^}"
    -DBUILD_DISTRIBUTED=OFF -DNO_GIT_REVISION=ON -DUSE_AVX2=OFF -DUSE_TCMALLOC=OFF
    -DCMAKE_CXX_FLAGS="-DLITTLE_ENDIAN=1234 -DBIG_ENDIAN=4321 -DBYTE_ORDER=1234"
)
if [[ "$BACKEND" == "eigen" ]]; then
    EIGEN_CMAKE_DIR="${EIGEN3_CMAKE_DIR:-/usr/share/eigen3/cmake}"
    [[ -f "$EIGEN_CMAKE_DIR/Eigen3Config.cmake" ]] || {
        echo "Missing Eigen3Config.cmake: $EIGEN_CMAKE_DIR" >&2
        exit 1
    }
    ARGS+=(-DEigen3_DIR="$EIGEN_CMAKE_DIR")
else
    # Get the OpenCL headers from Khronos OpenCL-Headers (CL/cl.h).
    # Point OPENCL_LIBRARY to an Android arm64 OpenCL link library.
    # Do not check the device's proprietary vendor library into Git.
    [[ -f "${OPENCL_INCLUDE_DIR:-}/CL/cl.h" ]] || {
        echo "Set OPENCL_INCLUDE_DIR to a directory containing CL/cl.h" >&2
        exit 1
    }
    [[ -f "${OPENCL_LIBRARY:-}" ]] || {
        echo "Set OPENCL_LIBRARY to a local Android arm64 OpenCL link library" >&2
        exit 1
    }
    ARGS+=(-DOpenCL_INCLUDE_DIR="$OPENCL_INCLUDE_DIR" -DOpenCL_LIBRARY="$OPENCL_LIBRARY")
fi

cmake -S "$SOURCE_DIR/cpp" -B "$BUILD_DIR" -G Ninja "${ARGS[@]}"
cmake --build "$BUILD_DIR" --parallel "${KATAGO_JOBS:-2}"
BIN="$BUILD_DIR/katago"
[[ -f "$BIN" ]] || { echo "Missing compiled binary $BIN" >&2; exit 1; }
HEADER="$("$READELF" -h "$BIN")"
if ! grep -q 'Machine:.*AArch64' <<< "$HEADER" || ! grep -q 'Type:.*DYN' <<< "$HEADER"; then
    echo "Expected Android AArch64 PIE executable; check toolchain and build." >&2
    exit 1
fi
if [[ "$BACKEND" == "opencl" ]]; then
    OUT="$OUTPUT_DIR/libkatago_exec_opencl.so"
else
    OUT="$OUTPUT_DIR/libkatago_exec.so"
fi
cp "$BIN" "$OUT"
echo "Built $OUT"
"$READELF" -d "$OUT" | grep NEEDED || true
if [[ "$BACKEND" == "opencl" ]] && ! "$READELF" -d "$OUT" | grep -q 'libOpenCL.so'; then
    echo "WARNING: ELF does not request libOpenCL.so by name." >&2
    echo "Check the link library's SONAME and Android vendor namespace before testing." >&2
fi
echo "No files were changed under app/src/main/jniLibs."
