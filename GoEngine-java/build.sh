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

./gradlew assembleDebug "${GRADLE_ARGS[@]}" "$@"

APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -s "$APK" ]]; then
    echo "ERROR: APK not found: $APK" >&2
    exit 1
fi

echo
echo "Build successful"
echo "APK: $APK"
ls -lh "$APK"
