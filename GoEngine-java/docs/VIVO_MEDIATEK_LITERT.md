# vivo X300 Pro / MediaTek LiteRT NPU bring-up

## Status

- **Model conversion: PASS**. Three independent 10b KataGo g170 9/13/19 TFLite graphs,
  exported by upstream `katago dumponnx`, converted using `onnx2tf` and CPU-invoked.
- **Android Java API: PASS**. `CompiledModel` 2.3.0 class compiled on GitHub Actions.
- **MediaTek runtime Maven artifact: resolved**. `litert-npu-runtime-mediatek:2.3.0`
  dependency resolves in the Android compile workflow; APK packaging is checked separately.
- **MediaTek real NPU execution: NOT VERIFIED** without on-device delegate traces.
- **KataGo `genmove` with MediaTek NPU: NOT YET IMPLEMENTED**. Probe is actual
  graph tensor inference, NOT the KataGo JNI search/evaluator backend.

## Models

https://github.com/rstg00po54/go/releases/tag/vivo-litert-10b-9-13-19-v1

Every release item has a TFLite graph and JSON manifest. Do not rename or
edit the independent 9x9, 13x13, 19x19 files; do not substitute RKNN files.
Output order is resolved from the generated manifest, not assumed.

## Build the experimental APK

On your existing Ubuntu developer machine with Android SDK API 35:

```bash
cd /mnt/hgfs/D/code/go/GoEngine-java
git fetch origin
git switch feature/vivo-mediatek-litert
bash tools/prepare_vivo_litert.sh
bash build.sh --apk-only -PenableKataGoNpuJni=false
```

The APK should package three `.tflite` / `.json` model pairs and
`com.google.ai.edge.litert:litert-npu-runtime-mediatek:2.3.0` vendor runtime.
It must NOT package `librknnrt.so`. The CI workflow `vivo_litert_android_apk.yml`
builds and checks this without touching the validated RK3588 branch.

## Run the first MediaTek graph inference smoke

Connect **vivo X300 Pro** using ADB USB or Wi-Fi. Replace the serial with the
output of `adb devices`. Running the test changes only the app install and
clears the device logcat buffers for deterministic checks.

```bash
adb devices
python3 tools/smoke_vivo_litert.py --serial <vivo_adb_serial> --size 9
```

The script installs the APK, launches a debug-only test activity, invokes
the full KataGo 10b network 3 times, verifies the five head lengths and
finite outputs, and uploads logs and report to the public `ui-test-results`
branch (omit with `--no-upload`). Follow with `--size 13` and `--size 19`.

Use this grep if diagnosing vendor backend:

```bash
adb logcat -d -v time | grep -Ei "KataGoMTK|LiteRt|MediaTek|Neuron|Dispatch|compiler plugin|fallback"
```

**Do not claim NPU acceleration just because `Accelerator.NPU` was
requested or `MTK_SMOKE_PASS` appeared. LiteRT can fall back to CPU.**
Check vendor delegate/partition logs and benchmark against CPU; a true
backend integration also needs CPU-vs-NPU numerical output parity.

The MediaTek runtime is not compatible with RK3588 RKNN graphs; the
original RK3588 NPU backend remains on `feature/rk3588-rknn`.
