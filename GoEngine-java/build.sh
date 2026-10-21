#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

usage() {
    cat <<'USAGE'
Usage:
  bash build.sh --vivo [--with-native] [Gradle -P...]
  bash build.sh --rk3588 [--with-native] [Gradle -P...]
  bash build.sh [--apk-only | --with-native] [Gradle -P...]

Modes:
  --vivo       Fast APK with MediaTek JNI + Vivo LiteRT models; no RKNN.
  --rk3588     Fast APK with RKNN (requires prepared RKNN libraries/models); no MediaTek.
  --apk-only   Reuse cached CPU/GPU JNI binaries; rebuild the KataGo PIE only if needed.
  --with-native  Rebuild CPU + GPU JNI before APK (does not build platform NPU JNI).
  --help       Print this help.

Without a platform mode, the existing build behavior is unchanged.
Explicit Gradle -P flags are forwarded last, overriding mode defaults.
USAGE
}

# Parse script-specific switches in any order; leave -P and other Gradle
# options alone. The original command remains backwards compatible.
PROFILE=""
BUILD_CHOICE=""
FORWARD_ARGS=()
while (( $# > 0 )); do
    case "$1" in
        --vivo|--rk3588)
            requested_profile="${1#--}"
            if [[ -n "$PROFILE" && "$PROFILE" != "$requested_profile" ]]; then
                echo "ERROR: --vivo and --rk3588 cannot be used together" >&2
                exit 2
            fi
            PROFILE="$requested_profile"
            ;;
        --apk-only)
            if [[ -n "$BUILD_CHOICE" && "$BUILD_CHOICE" != apk-only ]]; then
                echo "ERROR: --apk-only conflicts with --with-native" >&2
                exit 2
            fi
            BUILD_CHOICE=apk-only
            ;;
        --with-native|with-native)
            if [[ -n "$BUILD_CHOICE" && "$BUILD_CHOICE" != with-native ]]; then
                echo "ERROR: --with-native conflicts with --apk-only" >&2
                exit 2
            fi
            BUILD_CHOICE=with-native
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        --)
            shift
            FORWARD_ARGS+=("$@")
            break
            ;;
        *)
            FORWARD_ARGS+=("$1")
            ;;
    esac
    shift
done

# Profiles default to a quick UI/APK rebuild. The old no-profile command
# still builds native CPU/GPU JNI, just as it did before these switches.
if [[ -n "$PROFILE" && -z "$BUILD_CHOICE" ]]; then
    BUILD_CHOICE=apk-only
fi

# Build GPU JNI before Gradle, so the normal build.sh command produces a GPU-capable APK.
GRADLE_ARGS=()
if [[ "${REQUIRE_RKNN_FIVE_SIZES:-0}" == 1 ]]; then
    GRADLE_ARGS+=("-PrequireRknnFiveSizes=true" "-PrequireHumanRknnMultisize=true")
    echo "Building five-board RK3588 10b + Human b18 NPU APK: 9,11,13,15,19"
fi
if [[ "${REQUIRE_HUMAN_RKNN_MULTISIZE:-0}" == 1 ]]; then
    GRADLE_ARGS+=("-PrequireHumanRknnMultisize=true")
    echo "Building full RK3588 Human b18 NPU APK with 9x9/13x13/19x19 verified model assets"
fi
BUILD_GPU_JNI=true
# RK3588 Android boards use 4 KB pages and the bundled Rockchip v2.3.2
# librknnrt.so is only 4 KB ELF-aligned. Automatically allow this known
# exception for --rk3588; never enable it by default for Vivo/other builds.
# ALLOW_4K_RKNN_RUNTIME=0 explicitly opts out of this default.
if [[ "$PROFILE" == rk3588 && -z "${ALLOW_4K_RKNN_RUNTIME+x}" ]]; then
    ALLOW_4K_RKNN_RUNTIME=1
fi
if [[ "${ALLOW_4K_RKNN_RUNTIME:-0}" == 1 ]]; then
    GRADLE_ARGS+=("-Pallow4kRknnRuntime=true")
    echo "WARNING: building RK3588 4 KB-page test APK; NOT compatible with 16 KB page-size devices"
fi

case "$PROFILE" in
    vivo)
        GRADLE_ARGS+=(
            "-PenableKataGoNpuJni=false"
            "-PenableKataGoMtkJni=true"
            "-PbundleVivoLiteRtModels=true"
        )
        echo "Build profile: Vivo / MediaTek (CPU/GPU cached, MediaTek JNI + LiteRT models)"
        ;;
    rk3588)
        GRADLE_ARGS+=(
            "-PenableKataGoNpuJni=true"
            "-PenableKataGoMtkJni=false"
            "-PbundleVivoLiteRtModels=false"
        )
        echo "Build profile: RK3588 (CPU/GPU cached, RKNN enabled)"
        ;;
esac

if [[ "$BUILD_CHOICE" == apk-only ]]; then
    GRADLE_ARGS+=("-PskipKataGoNative=true")
    BUILD_GPU_JNI=false
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
    KATAGO_JOBS="${KATAGO_JOBS:-8}" bash "$PROJECT_DIR/tools/build_katago_from_source.sh" eigen
fi

./gradlew assembleDebug "${GRADLE_ARGS[@]}" "${FORWARD_ARGS[@]}"

APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -s "$APK" ]]; then
    echo "ERROR: APK not found: $APK" >&2
    exit 1
fi

echo
echo "Checking final APK for 16 KB ELF page alignment..."
if [[ "${ALLOW_4K_RKNN_RUNTIME:-0}" == 1 ]]; then
    python3 "$PROJECT_DIR/tools/check_android_16kb.py" --allow-4k-rknnrt "$APK"
else
    python3 "$PROJECT_DIR/tools/check_android_16kb.py" "$APK"
fi

echo "Build successful"
echo "APK: $APK"
ls -lh "$APK"
