#!/usr/bin/env bash
# Run real 9x9 KataGo Human b18 RKNN in the Android debug app.
# Opt-in flag is scoped to this test, and removed even on failure.
# The existing NPU smoke uploads both PASS and FAIL to ui-test-results.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERIAL="${ANDROID_SERIAL:-8719e18a71a2a66c}"
if [[ $# -gt 0 && "$1" != --* ]]; then
  SERIAL="$1"
  shift
fi
PKG="com.badukai.java.nputest"
LOCAL_MODEL="$ROOT/build/rk3588_human/9/katago_human_9x9.rknn"
REMOTE_MODEL="/data/local/tmp/katago_human_rknn_probe/katago_human_9x9.rknn"
FLAG="files/engine/enable_human_rknn_9x9.flag"
TIMING_FLAG="files/engine/human_rknn_timing_only.flag"

echo "[1/5] 编译实验版 RKNN JNI 和 Android APK（复用本地缓存）"
if [[ "${HUMAN_RKNN_SKIP_BUILD:-0}" != "1" ]]; then
  KATAGO_JOBS="${KATAGO_JOBS:-2}" bash "$ROOT/tools/build_katago_from_source.sh" rknnjni
  ALLOW_4K_RKNN_RUNTIME=1 bash "$ROOT/build.sh" --apk-only
fi
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
[[ -s "$APK" ]] || { echo "缺少 APK: $APK" >&2; exit 1; }

echo "[2/5] 安装调试 APK，不清除应用数据"
adb -s "$SERIAL" install -r -t "$APK"

echo "[3/5] 检查已经通过五输出精度对比的 Human b18 NPU 模型"
# This script downloads once, verifies checksum against CI manifest, stages
# runtime in /data/local/tmp, and runs the independent NPU sanity check.
bash "$ROOT/tools/test_rk3588_human_rknn.sh" "$SERIAL"
[[ -s "$LOCAL_MODEL" ]] || { echo "Human 9x9 RKNN model missing" >&2; exit 1; }
HOST_SHA="$(sha256sum "$LOCAL_MODEL" | awk '{print $1}')"
# No model is bundled into the APK; it is copied only into the application's
# private data on the board. The flag is temporary and normally absent.
echo "[4/5] 临时启用应用内 Human b18 NPU"
adb -s "$SERIAL" shell "chmod 644 '$REMOTE_MODEL'"
adb -s "$SERIAL" shell "run-as '$PKG' sh -c 'mkdir -p files/engine && cp $REMOTE_MODEL files/engine/katago_human_9x9.rknn'"
BOARD_SHA="$(adb -s "$SERIAL" shell "run-as '$PKG' sha256sum files/engine/katago_human_9x9.rknn" | awk '{print $1}' | tr -d '\r')"
if [[ "$HOST_SHA" != "$BOARD_SHA" ]]; then
  echo "应用内模型校验失败，拒绝启用实验功能" >&2
  exit 1
fi
echo "HUMAN_RKNN_APP_MODEL_SHA_PASS sha256=$HOST_SHA"
adb -s "$SERIAL" shell "run-as '$PKG' touch '$FLAG'"
# Default is safe: four real-game CPU/NPU checks. An explicit timing-only
# run benchmarks NPU without the added Eigen comparison cost.
SMOKE_ARGS=()
if [[ "${HUMAN_RKNN_TIMING_ONLY:-0}" == "1" ]]; then
  adb -s "$SERIAL" shell "run-as '$PKG' touch '$TIMING_FLAG'"
  SMOKE_ARGS+=(--human-rknn-timing-only)
  echo "Human b18 计时模式：关闭额外 CPU 对照，仍保持 NPU 运行错误时回退 CPU"
else
  adb -s "$SERIAL" shell "run-as '$PKG' rm -f '$TIMING_FLAG'"
fi
cleanup() {
  echo "关闭应用内 Human RKNN 实验标记（模型缓存保留）"
  adb -s "$SERIAL" shell "run-as '$PKG' rm -f '$FLAG' '$TIMING_FLAG'" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "[5/5] 用真实 KataGo 棋局/原生等级数据进行 Human NPU 测试，并自动上传报告"
python3 "$ROOT/tools/smoke_android_npu_switch.py" \
  --serial "$SERIAL" --skip-install --sizes 9 --require-human-rknn "${SMOKE_ARGS[@]}" "$@"
echo "HUMAN_RKNN_REAL_GAME_SMOKE_PASS"
