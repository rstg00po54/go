#!/usr/bin/env bash
set -e

if [ -z "$1" ]; then
    echo "Usage:"
    echo "  ./build.sh <project-path> [all|clean|build|install|run]"
    echo
    echo "Examples:"
    echo "  ./build.sh GoEngine-java"
    echo "  ./build.sh GoVision-kt build"
    echo "  ./build.sh ./GoVision-java install"
    exit 1
fi

PROJECT="$1"
ACTION="${2:-all}"

if [ ! -d "$PROJECT" ]; then
    echo "ERROR: project not found: $PROJECT"
    exit 1
fi

PROJECT_DIR="$(cd "$PROJECT" && pwd)"
cd "$PROJECT_DIR"

if [ ! -x "./gradlew" ]; then
    chmod +x ./gradlew 2>/dev/null || true
fi

APK_DIR="app/build/outputs/apk/debug"

find_apk() {
    APK="$(find "$APK_DIR" -type f -name '*.apk' 2>/dev/null | head -n 1)"

    if [ -z "$APK" ]; then
        echo "ERROR: APK not found"
        exit 1
    fi
}

get_package() {
    PACKAGE="$(grep -RhsE 'applicationId\s*=' app/build.gradle.kts app/build.gradle 2>/dev/null \
        | head -n 1 \
        | sed -E 's/.*applicationId\s*=\s*"([^"]+)".*/\1/')"

    if [ -z "$PACKAGE" ]; then
        echo "ERROR: package name not found"
        exit 1
    fi
}

do_clean() {
    echo "== clean: $PROJECT =="
    ./gradlew clean
}

do_build() {
    echo "== build: $PROJECT =="
    ./gradlew assembleDebug
    find_apk

    echo
    echo "Build successful"
    echo "APK: $APK"
    ls -lh "$APK"
}

do_install() {
    find_apk

    echo "== install =="
    adb install -r "$APK"

    echo
    echo "Install successful"
}

do_run() {
    get_package

    echo "== launch =="
    echo "Package: $PACKAGE"

    adb shell monkey \
        -p "$PACKAGE" \
        -c android.intent.category.LAUNCHER \
        1 >/dev/null

    echo "App launched"
}

case "$ACTION" in
    all)
        do_clean
        do_build
        do_install
        do_run
        ;;

    clean)
        do_clean
        ;;

    build)
        do_build
        ;;

    install)
        do_install
        ;;

    run)
        do_run
        ;;

    *)
        echo "ERROR: unknown action: $ACTION"
        echo "Allowed: all clean build install run"
        exit 1
        ;;
esac
