#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

# Gradle builds KataGo C++, packages native binaries and assembles the APK.
GRADLE_ARGS=()
if [[ "${1:-}" == "--apk-only" ]]; then
    GRADLE_ARGS+=("-PskipKataGoNative=true")
    shift
elif [[ "${1:-}" == "--with-native" || "${1:-}" == "with-native" ]]; then
    shift
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
