#!/usr/bin/env bash
# Build a *playable* vivo Android APK: KataGo CPU + GPU + three LiteRT model
# pairs. MediaTek LiteRT remains an experimental NPU inference probe; it is
# NOT yet connected to KataGo genmove. Rockchip RKNN is excluded on vivo.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Full build.sh compiles CPU/Eigen JNI, GPU/OpenCL JNI, and a 16 KB-aligned
# KataGo PIE for the independent analysis engine. Do not use --apk-only or
# vivoProbeApk=true here: those omit native code needed for normal gameplay.
ALLOW_4K_RKNN_RUNTIME=0 KATAGO_JOBS="${KATAGO_JOBS:-8}" bash "$ROOT/build.sh" \
    -PenableKataGoNpuJni=false \
    -PbundleVivoLiteRtModels=true "$@"

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
python3 "$ROOT/tools/verify_vivo_litert_apk.py" "$APK"
python3 "$ROOT/tools/verify_vivo_playable_apk.py" "$APK"
echo "Vivo playable CPU/GPU + LiteRT probe APK: $APK"
echo "MediaTek NPU GTP/genmove is not integrated yet. Test graph only via tools/test_vivo_npu.sh."
