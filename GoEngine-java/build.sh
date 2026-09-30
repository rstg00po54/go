#!/usr/bin/env bash
set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

echo "== Badukai build =="

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
