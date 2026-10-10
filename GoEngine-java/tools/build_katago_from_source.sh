#!/usr/bin/env bash
# Build KataGo from the vendored upstream C++ source files in this repository.
# Experimental GPU output is separate: the working CPU .so is never replaced.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SOURCE_DIR="${KATAGO_SOURCE_DIR:-$PROJECT_DIR/native/KataGo}"
BUILD_VARIANT="${1:-eigen}"
BACKEND="$BUILD_VARIANT"
PORTABLE_OPENCL=false
if [[ "$BUILD_VARIANT" == "openclportable" || "$BUILD_VARIANT" == "opencljni" ]]; then BACKEND="opencl"; PORTABLE_OPENCL=true; fi
if [[ "$BUILD_VARIANT" == "eigenjni" ]]; then BACKEND="eigen"; fi
if [[ $# -gt 1 || ("$BACKEND" != "eigen" && "$BACKEND" != "opencl") ]]; then
    echo "Usage: $0 [eigen|opencl|openclportable|eigenjni|opencljni]" >&2
    exit 2
fi
if [[ ! -f "$SOURCE_DIR/cpp/CMakeLists.txt" ]]; then
    echo "Missing KataGo C++ sources at $SOURCE_DIR" >&2
    echo "Fetch the latest rk3588-engine branch; KataGo sources are tracked directly." >&2
    exit 1
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
if [[ -z "$NDK" ]]; then
    for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk"; do
        if [[ -n "$sdk" && -d "$sdk/ndk" ]]; then
            NDK="$(find "$sdk/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
            [[ -z "$NDK" ]] || break
        fi
    done
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
BUILD_DIR="$WORK_DIR/build_android_arm64_${BUILD_VARIANT}"
OUTPUT_DIR="$PROJECT_DIR/build/katago_android_arm64_${BUILD_VARIANT}"
mkdir -p "$OUTPUT_DIR"

ARGS=(
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static
    -DCMAKE_BUILD_TYPE=Release -DUSE_BACKEND="${BACKEND^^}"
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DCMAKE_EXE_LINKER_FLAGS=-pie
    -DBUILD_DISTRIBUTED=OFF -DNO_GIT_REVISION=ON -DUSE_AVX2=OFF -DUSE_TCMALLOC=OFF
    -DCMAKE_CXX_FLAGS="-DLITTLE_ENDIAN=1234 -DBIG_ENDIAN=4321 -DBYTE_ORDER=1234"
)
if [[ "$BUILD_VARIANT" == "eigenjni" || "$BUILD_VARIANT" == "opencljni" ]]; then
    ARGS+=(-DKATAGO_BUILD_JNI_LIBRARY=ON)
fi
if [[ "$BACKEND" == "eigen" ]]; then
    EIGEN_CMAKE_DIR="$(find_eigen_cmake_dir)"
    echo "Eigen3 CMake dir: $EIGEN_CMAKE_DIR"
    ARGS+=(-DEigen3_DIR="$EIGEN_CMAKE_DIR")
else
    # OpenCL-Headers are architecture-neutral. The link library MUST be Android ARM64.
    # Use a device-provided library only locally; never commit proprietary binaries.
    if [[ -z "${OPENCL_INCLUDE_DIR:-}" ]]; then
        for dir in /usr/include /usr/local/include "$WORK_DIR/OpenCL-Headers"; do
            if [[ -f "$dir/CL/cl.h" ]]; then OPENCL_INCLUDE_DIR="$dir"; break; fi
        done
    fi
    if [[ ! -f "${OPENCL_INCLUDE_DIR:-}/CL/cl.h" ]]; then
        echo "OpenCL headers missing. Install: sudo apt install opencl-headers" >&2
        echo "Or set OPENCL_INCLUDE_DIR to the directory containing CL/cl.h." >&2
        exit 1
    fi

    # CMake may discard /usr/include as an implicit host include directory during
    # cross-compilation. Stage only the portable CL headers outside the host sysroot.
    # Passing /usr/include directly to Android clang would also mix host libc headers.
    OPENCL_STAGE_DIR="$WORK_DIR/opencl_headers"
    mkdir -p "$OPENCL_STAGE_DIR/CL"
    cp -a "$OPENCL_INCLUDE_DIR/CL/." "$OPENCL_STAGE_DIR/CL/"
    OPENCL_INCLUDE_DIR="$OPENCL_STAGE_DIR"

    if [[ "$PORTABLE_OPENCL" == true ]]; then
        OPENCL_LIBRARY="${OPENCL_LIBRARY:-$WORK_DIR/opencl_arm64_portable/libOpenCL.so}"
    else
        OPENCL_LIBRARY="${OPENCL_LIBRARY:-$WORK_DIR/opencl_arm64/libOpenCL.so}"
    fi
    if [[ ! -f "$OPENCL_LIBRARY" ]]; then
        echo "Android ARM64 libOpenCL.so not cached: $OPENCL_LIBRARY"
        if command -v adb >/dev/null 2>&1; then
            SERIAL="${ANDROID_SERIAL:-}"
            if [[ -z "$SERIAL" ]]; then
                mapfile -t connected < <(adb devices | awk 'NR > 1 && $2 == "device" {print $1}')
                if [[ "${#connected[@]}" -eq 1 ]]; then SERIAL="${connected[0]}"; fi
            fi
            if [[ -n "$SERIAL" ]]; then
                echo "Reading Android vendor OpenCL link library from device $SERIAL"
                mkdir -p "$(dirname "$OPENCL_LIBRARY")"
                # This file is used for linking only and is NOT packaged into the APK.
                if ! adb -s "$SERIAL" pull /vendor/lib64/libOpenCL.so "$OPENCL_LIBRARY"; then
                    rm -f "$OPENCL_LIBRARY"
                fi
            fi
        fi
    fi
    if [[ ! -f "$OPENCL_LIBRARY" ]]; then
        echo "Android ARM64 libOpenCL.so missing." >&2
        echo "Connect one Android device with adb and rerun, or run:" >&2
        echo "  adb pull /vendor/lib64/libOpenCL.so $WORK_DIR/opencl_arm64/libOpenCL.so" >&2
        echo "Alternatively set OPENCL_LIBRARY to your Android ARM64 libOpenCL.so file." >&2
        exit 1
    fi
    OPENCL_LIBRARY="$(realpath "$OPENCL_LIBRARY")"
    HEADER_LIB="$(LC_ALL=C "$READELF" -h "$OPENCL_LIBRARY")"
    if ! grep -Eq '^[[:space:]]*Machine:[[:space:]]*AArch64([[:space:]]|$)' <<< "$HEADER_LIB"; then
        echo "ERROR: OPENCL_LIBRARY is not Android ARM64: $OPENCL_LIBRARY" >&2
        exit 1
    fi
    if [[ "$PORTABLE_OPENCL" == true ]]; then
        # The MediaTek /vendor/lib64/libOpenCL.so can be a dispatcher without
        # directly exported OpenCL C entry points. Inspect defined dynsym
        # entries and optionally link against the actual EGL Mali driver.
        has_opencl_api() {
            "$READELF" -W --dyn-syms "$1" | awk '
                $7 != "UND" && $8 ~ /^clGetPlatformIDs(@.*)?$/ { found=1 }
                END { exit !found }'
        }
        if ! has_opencl_api "$OPENCL_LIBRARY"; then
            echo "The vendor OpenCL loader does not directly export clGetPlatformIDs: $OPENCL_LIBRARY"
            echo "Trying the phone's EGL Mali driver as the link input..."
            MALI_LINK_LIB="$WORK_DIR/opencl_arm64_portable/libGLES_mali.so"
            if [[ ! -f "$MALI_LINK_LIB" ]]; then
                SERIAL="${ANDROID_SERIAL:-}"
                if [[ -z "$SERIAL" ]]; then
                    mapfile -t connected < <(adb devices | awk 'NR > 1 && $2 == "device" {print $1}')
                    if [[ "${#connected[@]}" -eq 1 ]]; then SERIAL="${connected[0]}"; fi
                fi
                if [[ -z "$SERIAL" ]] || ! adb -s "$SERIAL" pull /vendor/lib64/egl/libGLES_mali.so "$MALI_LINK_LIB"; then
                    echo "ERROR: cannot read phone Mali library. Use ANDROID_SERIAL or provide OPENCL_LIBRARY." >&2
                    exit 1
                fi
            fi
            OPENCL_LIBRARY="$(realpath "$MALI_LINK_LIB")"
            if ! has_opencl_api "$OPENCL_LIBRARY"; then
                echo "ERROR: neither phone libOpenCL.so nor EGL libGLES_mali.so exports clGetPlatformIDs." >&2
                echo "Defined cl* symbols in the Mali driver:" >&2
                "$READELF" -W --dyn-syms "$OPENCL_LIBRARY" | grep -E ' cl[A-Z]' | head -n 20 >&2 || true
                echo "A runtime OpenCL dispatch implementation may be necessary; not safe to link blindly." >&2
                exit 1
            fi
        fi
        LIB_SONAME="$("$READELF" -W -d "$OPENCL_LIBRARY" | sed -nE 's/.*\(SONAME\).*\[([^]]+)\].*/\1/p' | head -n 1)"
        if [[ "$LIB_SONAME" != "libOpenCL.so" && "$LIB_SONAME" != "libGLES_mali.so" ]]; then
            echo "ERROR: unexpected OpenCL link SONAME '${LIB_SONAME:-none}'." >&2
            exit 1
        fi
        if "$READELF" -W -V "$OPENCL_LIBRARY" | grep -Eq 'Name: OPENCL_[0-9]'; then
            echo "ERROR: selected OpenCL link library uses versioned OPENCL_ symbols." >&2
            exit 1
        fi
        echo "Portable OpenCL link input verified: $OPENCL_LIBRARY (SONAME=$LIB_SONAME)"
    fi
    echo "OpenCL: headers=$OPENCL_INCLUDE_DIR library=$OPENCL_LIBRARY"
    ARGS+=(-DOpenCL_INCLUDE_DIR="$OPENCL_INCLUDE_DIR" -DOpenCL_LIBRARY="$OPENCL_LIBRARY")
fi

cmake -Wno-deprecated -S "$SOURCE_DIR/cpp" -B "$BUILD_DIR" -G Ninja "${ARGS[@]}"
cmake --build "$BUILD_DIR" --parallel "${KATAGO_JOBS:-8}"
if [[ "$BUILD_VARIANT" == "opencljni" ]]; then
    BIN="$BUILD_DIR/libkatago_gpu.so"
elif [[ "$BUILD_VARIANT" == "eigenjni" ]]; then
    BIN="$BUILD_DIR/libkatago.so"
else
    BIN="$BUILD_DIR/katago"
fi
[[ -f "$BIN" ]] || { echo "Missing compiled binary $BIN" >&2; exit 1; }
HEADER="$(LC_ALL=C "$READELF" -h "$BIN")"
echo "$HEADER" | grep -E '^[[:space:]]*(Type|Machine):' || true
if ! grep -Eq '^[[:space:]]*Machine:[[:space:]]*AArch64([[:space:]]|$)' <<< "$HEADER" || \
   ! grep -Eq '^[[:space:]]*Type:[[:space:]]*DYN([[:space:]]|$)' <<< "$HEADER"; then
    echo "ERROR: expected Android ARM64 ET_DYN ELF (PIE executable or JNI shared library)." >&2
    echo "Actual ELF header:" >&2
    echo "$HEADER" >&2
    echo "Check NDK toolchain, target architecture, and PIE linker flags." >&2
    exit 1
fi
if [[ "$BUILD_VARIANT" == "eigenjni" || "$BUILD_VARIANT" == "opencljni" ]]; then
    EXPECTED_JNI_SONAME="libkatago.so"
    if [[ "$BUILD_VARIANT" == "opencljni" ]]; then EXPECTED_JNI_SONAME="libkatago_gpu.so"; fi
    OUT="$OUTPUT_DIR/$EXPECTED_JNI_SONAME"
    if ! "$READELF" -W -d "$BIN" | grep -Fq "[$EXPECTED_JNI_SONAME]"; then
        echo "ERROR: JNI ELF does not have $EXPECTED_JNI_SONAME SONAME." >&2
        exit 1
    fi
    if ! "$READELF" -W --dyn-syms "$BIN" | grep -F Java_com_badukai_engine_KataGoNative_nativeBuildStatus >/dev/null; then
        echo "ERROR: JNI nativeBuildStatus symbol not exported." >&2
        exit 1
    fi
    if [[ "$BUILD_VARIANT" == "opencljni" ]]; then
        for symbol in nativeCreateSession nativeSendCommand nativeReadOutput nativeIsSessionAlive nativeStopSearch nativeDestroySession; do
            if ! "$READELF" -W --dyn-syms "$BIN" | grep -F "Java_com_badukai_engine_KataGoGpuNative_$symbol" >/dev/null; then
                echo "ERROR: GPU-specific JNI symbol missing: $symbol" >&2
                exit 1
            fi
        done
    fi
elif [[ "$BACKEND" == "opencl" ]]; then
    OUT="$OUTPUT_DIR/libkatago_exec_opencl.so"
else
    OUT="$OUTPUT_DIR/libkatago_exec.so"
fi
if [[ "$BUILD_VARIANT" == "opencljni" && "$LIB_SONAME" != "libOpenCL.so" ]]; then
    echo "ERROR: Android APP JNI GPU candidate requires portable libOpenCL.so SONAME, got $LIB_SONAME." >&2
    echo "Do not link the JNI library to a vendor-private Mali SONAME." >&2
    exit 1
fi
if [[ "$PORTABLE_OPENCL" == true ]]; then
    if ! "$READELF" -W -d "$BIN" | sed -nE 's/.*\(NEEDED\).*\[([^]]+)\].*/\1/p' | grep -Fxq "$LIB_SONAME"; then
        echo "ERROR: portable GPU build must depend on $LIB_SONAME." >&2
        "$READELF" -W -d "$BIN" | grep NEEDED >&2 || true
        exit 1
    fi
    if "$READELF" -W -V "$BIN" | grep -Eq 'Name: OPENCL_[0-9]|File: libGLES_mali.so'; then
        echo "ERROR: portable GPU build still requires RK3588-specific OpenCL symbol versions." >&2
        exit 1
    fi
fi
cp "$BIN" "$OUT"
echo "Built $OUT"
"$READELF" -d "$OUT" | grep NEEDED || true
if [[ "$BACKEND" == "opencl" ]]; then
    # The Android vendor's libOpenCL.so can have a different ELF SONAME (e.g. libGLES_mali.so).
    # In that case the executable will correctly list the SONAME, not the filename we linked.
    LIB_SONAME="$("$READELF" -d "$OPENCL_LIBRARY" | sed -nE 's/.*\(SONAME\).*\[([^]]+)\].*/\1/p' | head -n 1)"
    NEEDED_LIBS="$("$READELF" -d "$OUT" | sed -nE 's/.*\(NEEDED\).*\[([^]]+)\].*/\1/p')"
    if [[ -n "$LIB_SONAME" ]] && grep -Fxq "$LIB_SONAME" <<< "$NEEDED_LIBS"; then
        echo "OpenCL vendor dependency: $LIB_SONAME (linker input: $(basename "$OPENCL_LIBRARY"))"
    elif grep -Fxq 'libOpenCL.so' <<< "$NEEDED_LIBS"; then
        echo "OpenCL dependency: libOpenCL.so"
    else
        echo "WARNING: No matching OpenCL dependency recorded in the ELF." >&2
        echo "Inspect the vendor library SONAME and Android runtime loader behavior." >&2
    fi
fi
echo "No files were changed under app/src/main/jniLibs."
if [[ "$BUILD_VARIANT" == "eigenjni" ]]; then
    echo "CPU/Eigen JNI library built. Use Gradle -PenableKataGoJniCore=true to package it."
elif [[ "$BUILD_VARIANT" == "opencljni" ]]; then
    echo "GPU/OpenCL JNI ELF built with independent SONAME and JNI method names."
    echo "Run bash build.sh to package and test it on Android."
fi
