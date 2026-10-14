#!/usr/bin/env bash
# Build the vivo LiteRT inference-probe APK in the shared source branch.
# Gradle automatically fetches and validates all six KataGo model assets.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
./gradlew :app:assembleDebug \
    -PskipKataGoNative=true \
    -PvivoProbeApk=true \
    -PenableKataGoNpuJni=false \
    -PenableKataGoGpuJni=false \
    -PenableKataGoJniCore=false "$@"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
python3 tools/verify_vivo_litert_apk.py "$APK"
echo "Vivo LiteRT model probe APK: $APK"
echo "NOTE: This tests model inference only, not NPU gameplay."
