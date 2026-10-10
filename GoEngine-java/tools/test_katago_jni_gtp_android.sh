#!/usr/bin/env bash
# Run the opt-in JNI CPU/GTP smoke test on RK3588 without modifying the default engine.
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERIAL="${1:-8719e18a71a2a66c}"
MODE="${2:-smoke}"
case "$MODE" in
    smoke) EXTRA=katago_gtp ;;
    repeat) EXTRA=katago_gtp_repeat ;;
    *) echo "Usage: $0 [adb-serial] [smoke|repeat]" >&2; exit 2 ;;
esac
ADB="${ADB:-adb}"
APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$APK" ]] || { echo "APK missing: $APK (build with ./build.sh -PenableKataGoJniCore=true)" >&2; exit 1; }

"$ADB" -s "$SERIAL" install -r "$APK"
"$ADB" -s "$SERIAL" logcat -c
"$ADB" -s "$SERIAL" shell am force-stop com.badukai.java
"$ADB" -s "$SERIAL" shell am start -n com.badukai.java/com.badukai.MainActivity --ez "$EXTRA" true

REPORT_DIR="$PROJECT_DIR/build/katago_jni_gtp_test"
mkdir -p "$REPORT_DIR"
REPORT="$REPORT_DIR/${SERIAL}_$(date +%Y%m%d_%H%M%S).log"
echo "Waiting for JNI GTP $MODE result on $SERIAL; report: $REPORT"
for attempt in $(seq 1 120); do
    "$ADB" -s "$SERIAL" logcat -d -s KataGoJniGtp:I '*:S' > "$REPORT"
    if grep -Fq 'PASS: JNI GTP' "$REPORT"; then
        cat "$REPORT"
        exit 0
    fi
    if grep -Fq 'FAIL: JNI GTP' "$REPORT"; then
        cat "$REPORT"
        exit 1
    fi
    starts="$(grep -Fc 'Starting JNI CPU/Eigen GTP session' "$REPORT" || true)"
    if [[ "$starts" -ge 3 ]]; then
        cat "$REPORT"
        echo "ERROR: Android APP restarted $starts times during JNI GTP test." >&2
        "$ADB" -s "$SERIAL" logcat -d -b crash -v threadtime | tail -80 >&2 || true
        exit 1
    fi
    sleep 1
done
cat "$REPORT"
echo "ERROR: JNI GTP smoke test did not finish within the diagnostic window." >&2
exit 1
