# vivo X300 Pro / MediaTek LiteRT NPU bring-up

## Status

- **Model conversion: PASS**. Three independent 10b KataGo g170 9/13/19 TFLite graphs,
  exported by upstream `katago dumponnx`, converted using `onnx2tf` and CPU-invoked.
- **Android Java API: PASS**. `CompiledModel` 2.3.0 class compiled on GitHub Actions.
- **Android probe APK: CI generated** when the GitHub workflow passes; this dedicated APK is not a regular playable Go APK.
- **MediaTek runtime Maven artifact: resolved**. `litert-npu-runtime-mediatek:2.3.0`
  dependency resolves in the Android compile workflow; APK packaging is checked separately.
- **MediaTek real NPU execution: NOT VERIFIED** without on-device delegate traces.
- **KataGo `genmove` with MediaTek LiteRT: 9/13/19 validated** by real GTP
  inference and raw five-head CPU/Eigen parity. Main-game selection is
  implemented and requires a new on-device Human SL UI regression test.

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
git switch feature/rk3588-rknn
git pull --ff-only
bash tools/build_vivo_npu.sh
```

The experimental APK uses Android application ID `com.badukai.java.nputest` and
launcher label **BadukaiJ NPU测试**. It installs alongside the existing
`com.badukai.java` app without replacing its data. Android Java/JNI class
names remain under `com.badukai`.

This **playable CPU/GPU APK** includes `libkatago_exec.so` (KataGo PIE),
`libkatago.so` (CPU JNI), and `libkatago_gpu.so` (OpenCL JNI), plus three
`.tflite` / `.json` model pairs and the MediaTek LiteRT vendor runtime.
The script downloads and SHA256-checks all six models as part of the Gradle
build, then checks the APK for every required model and native library.
It explicitly excludes Rockchip `librknnrt.so` for vivo's 16 KB-page system.

The GitHub workflow `vivo_litert_android_apk.yml` still produces a fast
**probe-only** APK with `-PvivoProbeApk=true`, for isolated LiteRT smoke tests.
That special CI build deliberately omits normal KataGo game binaries. Do not
use `-PvivoProbeApk=true` to build the playable phone app. The full local build
uses `-PbundleVivoLiteRtModels=true` and includes native game engines.

## ONNX scale8 repair (2026-10-15)

The real 9x9 GTP test passed, but the first CPU/Eigen vs LiteRT raw-head
parity report showed a nearly exact **8x mismatch on all five heads**.
Upstream KataGo `dumponnx` applies scale8 by default to keep intermediate
convnet activations FP16-safe. That graph intentionally returns raw outputs
divided by 8, and records the required undo factor under
`katago.postProcess.outputScaleMultiplier`. The original
`convert_katago_litert.py` and Android JNI runner had discarded this
metadata, and thus forwarded 1/8-scale logits directly into KataGo.

The repaired converter persists `outputScaleMultiplier` and
`scale8Applied` in the signed JSON model manifest. The Android LiteRT
runner restores the exact declared factor on all five **raw** output heads
*before* returning them to KataGo. Existing models remain supported because
only SHA256-pinned legacy 9/13/19 graphs may assume their old omitted factor
is 8; all other missing-scale graphs fail closed. CPU/GPU/RKNN paths are
unchanged.

The published model graphs are bitwise unchanged; the updated v2
manifests are in release `vivo-litert-10b-9-13-19-v2`, while the normal
APK still uses pinned `v1` graph downloads. The v1 manifests were also
updated by an earlier conversion run, so the downloader and APK verifier
now accept **only** the original and scale-aware v1 JSON SHA256 values.
JVM regression tests check both restoration and hash pinning.

To verify the numeric fix on the actual vivo, rebuild and test once:

```bash
git pull --ff-only
bash tools/build_vivo_mtk_gtp.sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
python3 tools/smoke_vivo_litert.py --gtp --size 9 --skip-install
```

The report now records `outputScaleRestoration` (factor and source) and
`cpuLiteRtParity` separately from `GTP_PASS`. Expect factor 8 for this
original ONNX model. **Numerical parity after the repair is not yet
confirmed on a vivo device** until the next real GTP test report is uploaded.

## End-to-end vivo CPU/GPU/NPU performance comparison

After building the experimental **playable** APK with
`bash tools/build_vivo_mtk_gtp.sh`, install it and run:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
python3 tools/bench_vivo_backends.py --skip-install
```

The benchmark tests CPU/Eigen, GPU/OpenCL JNI and MediaTek LiteRT JNI
in separate Android processes. By default: 9x9, `maxVisits=32`,
`maxTime=12`, one excluded warm-up `genmove`, then five measured
`genmove black` operations per backend on **different, reproducible opening
positions**. All three backends receive the same sequence of replayed positions.
This avoids a previous cache-hit benchmark that sometimes showed 0 ms moves;
the host rejects NPU trials with no real LiteRT evaluator call. It times
position replay separately from model/session creation and **entire GTP
move latency** (search, NN evaluation and Java/JNI overhead). Median,
mean, min, max and nearest-rank p95 are recorded, as are individual
moves, cold startup times, and MediaTek LiteRT inference logs when
present. Logs and `timings.csv` are automatically uploaded under distinct
`ui-test-results/runs/*mediatek_bench*` paths; previous reports are
preserved. The script clears the phone's logcat before each backend,
not archived GitHub results.

Use `--backends gpu,npu` for only the requested accelerators;
`--size 13` or `--size 19` for larger graphs; `--human-sl` to measure
full Human SL-style game overhead (including its CPU work); and
`--no-upload` for entirely local reports. A missing GPU/OpenCL runtime
or automatic CPU fallback is recorded as a **failure** for GPU, never
used as a valid GPU-vs-NPU speed comparison.

These are approximate, device- and thermal-state-dependent measurements:
complete GTP latency is not raw NN throughput, and successful LiteRT
Neuron delegation is not independent proof that physical NPU silicon
performed the operations.

## Experimental Human SL b18 LiteRT (two independent neural networks)

Original official `b18c384nbt-humanv0` Human SL was converted to separate
fixed-shape 9x9, 13x13 and 19x19 float32 LiteRT graphs with **192 SGF metadata
channels**, **2 policy channels** and **6 score channels**. These are *not*
10b graphs renamed as Human models. All three passed the conversion tool's
LiteRT **CPU** numerical finiteness probe, and the outputs preserve KataGo
ONNX `outputScaleMultiplier`. The full six-file release is pinned by SHA-256
in `tools/prepare_vivo_human_litert.sh`.

**Device delegation, CPU/Eigen-to-LiteRT five-head numerical parity, and
actual Human SL rank fidelity have not yet passed a vivo device test.**
Do not describe it as production NPU Human SL yet.

The regular 10b MediaTek game remains unchanged. Its separate native JNI
callback routes b18 NN calls to Human LiteRT only when all assets are present,
the model graph SHA-256 and tensor contract match, and Android's NPU provider
successfully compiles the Human graph. Otherwise Human remains on Eigen/CPU;
the displayed backend explicitly distinguishes these cases. Native KataGo
still computes the 192-dimensional rank-specific features, rather than
constructing a fake rank vector in Java.

**First 9x9 trial:** the extra Human file is about 102 MiB. To avoid adding
three such graphs to the APK before validating NPU compilation, stage only 9x9:

```bash
git pull --ff-only
bash tools/prepare_vivo_human_litert.sh --size 9
bash tools/build_vivo_mtk_gtp.sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
python3 tools/smoke_vivo_mtk_game.py --skip-install --sizes 9 --require-human-npu
```

The last command enables *debug-only* Eigen parity for the first 16 NN
evaluations per model. It requires actual `MTK_GTP_INFER ... model=human`
entries and a complete, bounded five-head Human output comparison, so a CPU
fallback **fails** the Human NPU smoke, even if normal gameplay succeeds.
Use `--no-upload` if logs must stay local; by default the report and logcat
are auto-uploaded with a unique name into `ui-test-results`.
This is a provisional numerical tolerance, not yet proof of identical
human ranks or neural silicon execution.

After a 9x9 device test passes, opt into additional graphs:

```bash
bash tools/prepare_vivo_human_litert.sh --all
bash tools/build_vivo_mtk_gtp.sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
python3 tools/smoke_vivo_mtk_game.py --skip-install --require-human-npu
```

The experimental release is
`vivo-human-litert-9-13-19-experimental-v0`. Never silently include it in
the standard release APK until a successful on-device parity and gameplay run
has been reviewed.

## MediaTek NPU selectable in the playable Android game

9x9, 13x13 and 19x19 real `genmove` tests and identical-feature CPU/Eigen
numerical parity on all five raw NN heads passed on the vivo MT6993. The
playable Android app now connects `NPU / MediaTek LiteRT` to the original
`KataGoEngine` GTP session lifecycle. `libkatago_mtk.so` supplies the
g170 10b evaluator, while the Human SL b18 network continues on Eigen/CPU.

After updating source, build the **playable game APK with MediaTek JNI**:

```bash
git pull --ff-only
bash tools/build_vivo_mtk_gtp.sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The build defaults to `KATAGO_JOBS=8`. The app label is **BadukaiJ NPU测试**
and the Android application ID is `com.badukai.java.nputest`; it is separate
from the original `com.badukai.java` installation.

In the app, open **新开一局** and select `NPU / MediaTek LiteRT`.
NPU is offered only when the phone is detected as MediaTek, the correct
`libkatago_mtk.so` has been packaged and all board-specific graphs/manifests
are installed. 11x11 and 15x15 remain CPU/GPU only; selecting NPU for them
shows a clear warning without starting a game. The display indicates the
active backend, and NPU startup failure logs the reason and visibly notifies
the user if it falls back to CPU.

Before relying on the new interface, exercise the **entire ordinary Java
game controller and Human SL path** rather than the standalone GTP probe:

```bash
python3 tools/smoke_vivo_mtk_game.py --skip-install
```

This debug-only test uses the same main-game `KataGoEngine` class as the UI
and runs 9→13→19→9 with Human SL enabled, 12-kyu profile, board setup,
black/white moves, and per-step model switching. It rejects unintended CPU
fallback, unsupported board models and missing real NPU initialization.
Logs/reports are uploaded into separately named
`ui-test-results/runs/*mediatek_game*` paths; use `--no-upload` to keep
the report local. The test clears only device logcat and reuses the app's
temporary engine files, not previous local or remote result directories.

Note: complete Human SL gameplay and backend-switching have not yet been
validated on the user's device after this UI integration. LiteRT graph
delegation was confirmed in the earlier neural parity smoke, but physical
silicon NPU execution has not been independently measured.

## Experimental real KataGo `genmove` (opt-in, **not yet device validated**)

The `MEDIATEK` KataGo C++ backend uses Eigen for Human SL and a JNI callback
to the Java LiteRT `CompiledModel` for the g170 10b neural evaluator.
This is a separate `libkatago_mtk.so`; it does not replace CPU/GPU or RK3588
RKNN. Normal game backend selection deliberately continues to hide MediaTek NPU
until real GTP, neural numerical parity, and hardware execution are verified.

To build the experimental APK (Android NDK/Eigen/CMake required):

```bash
git pull --ff-only
bash tools/build_vivo_mtk_gtp.sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

This keeps the separate Android application ID `com.badukai.java.nputest`
so the existing working app remains installed.

Test **real KataGo GTP moves** on 9x9, without installing again:

```bash
python3 tools/smoke_vivo_litert.py --gtp --size 9 --skip-install
```

For 9x9, this experimental GTP smoke now also compares the first 16
real KataGo NN evaluations per native compute handle against the **same**
g170 10b network evaluated on CPU/Eigen. It measures the five raw output
heads before normal KataGo postprocessing (`policyPass`, `policy`,
`value`, `score`, `ownership`) and retains the LiteRT output for
search, so it tests real gameplay rather than a separately generated input.
Results include max absolute difference, element-weighted mean absolute
difference, max normalized relative difference and the most different
CPU/LiteRT element. It saves `parity_summary.json` in addition to the
usual diagnostic log, and automatically uploads it to `ui-test-results`
unless `--no-upload` is supplied.

`GTP_PASS` and `cpuLiteRtParity.status` are separate conclusions:
a legal GTP move is not proof of numerical agreement. The provisional
maximum absolute error thresholds are 0.025 for policyPass, policy, value
and ownership, and 0.20 for score; exceeding them yields `MISMATCH`,
missing raw-head samples yields `INCOMPLETE`, and `NO_DATA` means this
binary did not capture parity. These thresholds are smoke-test screening
values, **not a scientific equivalence guarantee**; review the raw
head statistics before exposing the NPU in the main game UI.

The script launches a debug-only `mtk_gtp_smoke` intent. The app copies
matching model/config/manifest files, initializes the in-process C++ GTP
search engine, calls `name`, `boardsize`, `clear_board`, `komi`,
`genmove black` and `genmove white`. It checks legal move strings and
requires actual `MTK_GTP_INFER` callbacks before reporting `GTP_PASS`.
On-device inference and vendor diagnostics are saved in
`build/vivo_litert_smoke/<run>/` and uploaded automatically to the
**PUBLIC** `ui-test-results` Git branch; use `--no-upload` to keep them local.
The default GTP timeout is 480 seconds; probe-only default remains 210 seconds.

`GTP_PASS` means the engine returned moves using its LiteRT evaluator,
not that its move quality matches the CPU network or that the silicon NPU
has been independently measured. Do not enable MediaTek NPU as a normal
user-selectable gameplay option before those checks pass.

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

### Diagnostic uploads (no repeated install or model inference)

The smoke script automatically records LiteRT inference times, MediaTek compiler
plugin loading, Neuron SDK version, full/partial delegate partitions, AIDL
service availability and fallback indicators in `report.json`. It also writes
`npu_diagnostics.txt` and `vendor_litert.txt` and uploads both with the
filtered `logcat.txt` to the **PUBLIC** `ui-test-results` branch by default.
An unverified silicon execution status remains `UNVERIFIED` even if a model
was fully delegated; CPU accelerator registration alone does not mean fallback.

To run again without reinstalling or uploading:

```bash
python3 tools/smoke_vivo_litert.py --size 9 --skip-install --no-upload
```

To publish already-saved results **without ADB, reinstalling, or re-inference**:

```bash
python3 tools/smoke_vivo_litert.py --upload-existing \
  build/vivo_litert_smoke/20261015T015938792417Z \
  build/vivo_litert_smoke/20261015T015943999937Z \
  build/vivo_litert_smoke/20261015T015952496003Z
```

This requires the existing Git remote and working push credentials. The reports
include raw device diagnostic lines, so use `--no-upload` when you do not
want them published. A failed upload preserves all local diagnostic files.

Use this grep if diagnosing vendor backend:

```bash
adb logcat -d -v time | grep -Ei "KataGoMTK|LiteRt|MediaTek|Neuron|Dispatch|compiler plugin|fallback"
```

**Do not claim NPU acceleration just because `Accelerator.NPU` was
requested or `MTK_SMOKE_PASS` appeared. LiteRT can fall back to CPU.**
Check vendor delegate/partition logs and benchmark against CPU; a true
backend integration also needs CPU-vs-NPU numerical output parity.

The MediaTek runtime is not compatible with RK3588 RKNN graphs. Both
backends live in `feature/rk3588-rknn`, with runtime detection via
`NpuHardware` (`Build.SOC_MODEL`, `Build.SOC_MANUFACTURER`, etc.).
RK3588 keeps its validated RKNN GTP implementation; MediaTek LiteRT is still
an independent inference probe and never advertises playable NPU mode.
The probe APK needs its own build option because Rockchip's 4 KB
`librknnrt.so` is not suitable for Vivo 16 KB-page devices.
