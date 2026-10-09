#!/usr/bin/env bash
set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

echo "== Badukai build =="

if [ -f "$PROJECT_DIR/build_native.sh" ]; then
    echo "[native] Building KataGo C++..."
    bash "$PROJECT_DIR/build_native.sh"
else
    echo "[native] SKIPPED: no build_native.sh; Gradle will package existing native binaries."
fi

if [ ! -f "app/src/main/assets/libkatago.so" ]; then
    echo "WARNING: app/src/main/assets/libkatago.so is missing (required by KataGoEngine.java)."
fi

echo "[1/2] clean"
./gradlew clean

echo "[2/2] assembleDebug"
./gradlew assembleDebug

APK="$(find app/build/outputs/apk/debug -name '*.apk' | head -n 1)"

if [ -z "$APK" ]; then
    echo "ERROR: APK not found"
    exit 1
fi

echo
echo "Build successful"
echo "APK: $APK"
ls -lh "$APK"
