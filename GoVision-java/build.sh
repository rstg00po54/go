#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
case "${1:-all}" in
  clean) ./gradlew clean ;;
  build) ./gradlew assembleDebug ;;
  install) adb install -r app/build/outputs/apk/debug/app-debug.apk ;;
  all) ./gradlew clean assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk ;;
  *) echo "Usage: ./build.sh [all|clean|build|install]"; exit 1 ;;
esac
