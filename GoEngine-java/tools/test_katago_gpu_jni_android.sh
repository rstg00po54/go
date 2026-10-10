#!/usr/bin/env bash
# Opt-in GPU/OpenCL JNI smoke test. CPU JNI remains the normal APP engine.
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERIAL="${1:-8719e18a71a2a66c}"
ADB="${ADB:-adb}"
APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
[[ -s "$APK" ]] || { echo "APK missing: $APK" >&2; exit 1; }

# Confirm the special GPU build flag was used, instead of launching a stale CPU-only APK.
if command -v unzip >/dev/null 2>&1; then
    unzip -Z1 "$APK" | grep -Fxq 'lib/arm64-v8a/libkatago_gpu.so' || {
        echo "GPU JNI not packaged in APK. Run ./build.sh -PenableKataGoJniCore=true -PenableKataGoGpuJni=true" >&2
        exit 1
    }
fi

"$ADB" -s "$SERIAL" install -r "$APK"
"$ADB" -s "$SERIAL" logcat -c
"$ADB" -s "$SERIAL" shell am force-stop com.badukai.java
"$ADB" -s "$SERIAL" shell am start -n com.badukai.java/com.badukai.GpuSmokeActivity

REPORT_DIR="$PROJECT_DIR/build/katago_gpu_jni_test"
mkdir -p "$REPORT_DIR"
REPORT="$REPORT_DIR/${SERIAL}_$(date +%Y%m%d_%H%M%S).log"
echo "Waiting for OpenCL GPU JNI genmove and raw NN on $SERIAL; report: $REPORT"
for attempt in $(seq 1 480); do
    "$ADB" -s "$SERIAL" logcat -d -s KataGoGpuJni:I AndroidRuntime:E linker:E '*:S' > "$REPORT"
    if grep -Fq 'PASS: GPU JNI OpenCL' "$REPORT"; then
        cat "$REPORT"
        exit 0
    fi
    if grep -Fq 'FAIL: GPU JNI OpenCL' "$REPORT" || grep -Fq 'FATAL EXCEPTION' "$REPORT"; then
        cat "$REPORT"
        "$ADB" -s "$SERIAL" logcat -d -b crash -v threadtime | tail -100 >&2 || true
        exit 1
    fi
    # A native OpenCL crash may kill the process without a Java exception or FAIL log.
    if (( attempt > 10 )) && ! "$ADB" -s "$SERIAL" shell pidof com.badukai.java:katago_gpu | grep -Eq '[0-9]'; then
        cat "$REPORT"
        echo "ERROR: Isolated GPU process exited before PASS (likely native crash)." >&2
        "$ADB" -s "$SERIAL" logcat -d -b crash -v threadtime | tail -100 >&2 || true
        exit 1
    fi
    sleep 1
done
cat "$REPORT"
"$ADB" -s "$SERIAL" logcat -d -b crash -v threadtime | tail -100 >&2 || true
echo "ERROR: GPU JNI GTP did not finish in the diagnostic window. Check $REPORT." >&2
exit 1
