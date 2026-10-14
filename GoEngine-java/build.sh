#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

# Build GPU JNI before Gradle, so the normal build.sh command produces a GPU-capable APK.
GRADLE_ARGS=()
BUILD_GPU_JNI=true
if [[ "${1:-}" == "--apk-only" ]]; then
    GRADLE_ARGS+=("-PskipKataGoNative=true")
    BUILD_GPU_JNI=false
    shift
elif [[ "${1:-}" == "--with-native" || "${1:-}" == "with-native" ]]; then
    shift
fi

if [[ "$BUILD_GPU_JNI" == true ]]; then
    echo "Building KataGo CPU/Eigen JNI..."
    bash "$PROJECT_DIR/tools/build_katago_from_source.sh" eigenjni
    echo "Building KataGo GPU/OpenCL JNI..."
    bash "$PROJECT_DIR/tools/build_katago_from_source.sh" opencljni
fi

# --apk-only reuses previously built CPU/GPU JNI libraries when present.
if [[ -s "$PROJECT_DIR/build/katago_android_arm64_eigenjni/libkatago.so" ]]; then
    GRADLE_ARGS+=("-PenableKataGoJniCore=true")
elif [[ "$BUILD_GPU_JNI" == true ]]; then
    echo "ERROR: CPU JNI library was not produced" >&2
    exit 1
fi
if [[ -s "$PROJECT_DIR/build/katago_android_arm64_opencljni/libkatago_gpu.so" ]]; then
    GRADLE_ARGS+=("-PenableKataGoGpuJni=true")
elif [[ "$BUILD_GPU_JNI" == true ]]; then
    echo "ERROR: GPU JNI library was not produced" >&2
    exit 1
fi

# The independent win-rate engine runs the KataGo PIE even when the main game
# uses CPU/GPU JNI. Both normal and --apk-only builds must bundle a 16 KB PIE.
# If the old CMake cache still contains a 4 KB executable, rebuild that one
# native target completely; leave separately built CPU/GPU JNI libraries alone.
aligned_pie=false
for pie in "$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a/libkatago_exec.so" \
           "${KATAGO_WORKDIR:-$HOME/.cache/goengine_katago}/build_android_arm64_eigen/katago" \
           "$PROJECT_DIR/build/katago_android_arm64_eigen/libkatago_exec.so"; do
    [[ -s "$pie" ]] || continue
    if python3 "$PROJECT_DIR/tools/check_katago_pie.py" "$pie" >/dev/null
    then
        aligned_pie=true
        echo "Reusing 16 KB-aligned KataGo PIE: $pie"
        break
    fi
done
if [[ "$aligned_pie" == false ]]; then
    pie_cache="${KATAGO_WORKDIR:-$HOME/.cache/goengine_katago}/build_android_arm64_eigen"
    if [[ -d "$pie_cache" ]]; then
        # Restrict deletion to this generated CMake build directory only.
        [[ "${pie_cache##*/}" == build_android_arm64_eigen ]] || {
            echo "Unsafe KataGo build cache path: $pie_cache" >&2
            exit 1
        }
        echo "Removing old 4 KB KataGo PIE build cache: $pie_cache"
        rm -rf -- "$pie_cache"
    fi
    echo "Building fresh 16 KB-aligned KataGo PIE for independent win-rate analysis..."
    KATAGO_JOBS="${KATAGO_JOBS:-2}" bash "$PROJECT_DIR/tools/build_katago_from_source.sh" eigen
fi

./gradlew assembleDebug "${GRADLE_ARGS[@]}" "$@"

APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -s "$APK" ]]; then
    echo "ERROR: APK not found: $APK" >&2
    exit 1
fi

echo
echo "Checking final APK for 16 KB ELF page alignment..."
python3 "$PROJECT_DIR/tools/check_android_16kb.py" "$APK"

echo "Build successful"
echo "APK: $APK"
ls -lh "$APK"
