#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

# Gradle now owns KataGo C++ compilation, native packaging, and Java build.
# --apk-only keeps the tracked, previously working native executable.
if [[ "${1:-}" == "--apk-only" ]]; then
    shift
    exec ./gradlew assembleDebug -PskipKataGoNative=true "$@"
fi
if [[ "${1:-}" == "--with-native" || "${1:-}" == "with-native" ]]; then shift; fi
exec ./gradlew assembleDebug "$@"
