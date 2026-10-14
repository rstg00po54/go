# RK3588 RKNPU2 integration (experimental, 19x19)

This branch is derived from `rk3588-engine` and does **not** replace it.
The existing `eigenjni` and `opencljni` builds remain unchanged.
At present, the native backend accelerates **only** the original
`g170-b10c128-s1141046784-d204142634` KataGo 10b NN, using the separately
compiled 19x19 RKNPU2 model built and validated by MiniGoCpp.

The Human SL `b18c384nbt-humanv0` network **still runs on Eigen CPU** within
the NPU library. The Human SL network needs its own b18 ONNX export, RKNN
compilation, accuracy tests and NN input/head mapping. Selecting NPU in the
app must not be interpreted as an NPU-accelerated Human SL policy.

## Compile on the Ubuntu x86 host

For RK3588 Android 14, Android SDK/NDK, Eigen, CMake and Ninja should already
be installed for `eigenjni`. Use the **same** `rk3588-engine` workspace:

```bash
cd /mnt/hgfs/D/code/go/GoEngine-java
git fetch
git switch feature/rk3588-rknn
bash tools/prepare_rknn.sh
KATAGO_JOBS=2 bash tools/build_katago_from_source.sh rknnjni
# Check the RK3588 target first. Only use this path if device page size = 4096.
adb -s 8719e18a71a2a66c shell getconf PAGESIZE
ALLOW_4K_RKNN_RUNTIME=1 bash build.sh --apk-only
```

`prepare_rknn.sh` reuses cached MiniGoCpp artifacts when available; else
downloads the 19x19 model from its release plus Rockchip's Android
`librknnrt.so` and `rknn_api.h` **v2.3.2** into ignored working paths.
No binaries are committed to the source tree.

The build should produce:

- `build/katago_android_arm64_rknnjni/libkatago_npu.so`
- `build/rknn_sdk/librknnrt.so`
- `app/src/main/assets/engine/katago_10b_19x19.rknn`
- `app/build/outputs/apk/debug/app-debug.apk`

Gradle packages the model and runtime only when they are present and
`-PenableKataGoNpuJni=true` (or auto-detection) enables the NPU backend.
Disable experimental packaging with `-PenableKataGoNpuJni=false`.

## Device verification

Install the resulting APK on **RK3588S**, select **19路** and **NPU / RKNN**
in New Game, and play a few moves. Confirm log entries from the native
NN-server thread containing `RKNN (RK3588 NPU)`, plus Java startup messages
identifying the NPU session. The 10b and Human SL b18 should show separate
backend labels.

```bash
adb -s 8719e18a71a2a66c install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 8719e18a71a2a66c logcat -d -v time | grep -E 'RKNN|NPU|Backend requested:|Game backend requested=' | tail -60
```

After confirming GTP startup, compare identical 19x19 positions between
NPU and CPU: candidate moves, ownership and score estimates. The existing
MiniGoCpp test validates all five RKNN heads against an ONNX reference on
an **artificial** 19x19 input, not the App's real board inputs or complete
MCTS search. Do not call the full app validated until this test passes.

## Technical constraints

- 10b NN tensor shapes: `InputSpatial [1,22,19,19]`,
  `InputGlobal [1,19,1,1]`, `InputMask [1,1,19,19]`.
- Output heads: `OutputPolicyPass`, `OutputPolicy`, `OutputValue`,
  `OutputScoreValue`, `OutputOwnership`.
- RKNN runner uses the NHWC spatial input prepared by KataGo and constructs
  `InputMask` from feature plane 0. It performs FP32 input/output marshaling,
  while the NPU model uses the converter's internal precision.
- A single NPU core (0) and batch=1 are used for the first integration.
- 9/11/13/15 board sizes remain on CPU/GPU. Do **not** claim that an all-ones
  19x19 mask can represent smaller boards.
- Failure to initialize RKNN should fall back to Eigen with an explicit log.
- Current NPU branch has **not been compiled and device-tested by ChatGPT**.
  It is integration source ready for host-side compilation and RK3588 tests.

## Future Human SL acceleration

Convert the actual `b18c384nbt-humanv0` network separately. Capture the
human-rank conditioning inputs and model version, compare all heads against
the native Eigen Human SL output for multiple ranks and legal 19x19 games,
then extend the backend's model dispatch. Conversion success and NPU support
for the full b18 graph remain to be established.

## Important: Rockchip runtime page alignment and phone builds

The RKNPU2 Android runtime `librknnrt.so` from Rockchip v2.3.2 in
`tools/prepare_rknn.sh` has ELF `PT_LOAD p_align=0x1000`. The native
`libkatago_npu.so` and our other JNI libraries are correctly linked with
`p_align=0x4000`.

To avoid weakening checks accidentally, APK packaging will **reject** the
Rockchip runtime by default. For development on RK3588 Android systems verified
to use **4 KB memory pages** (`adb shell getconf PAGESIZE` returns `4096`),
opt in using `ALLOW_4K_RKNN_RUNTIME=1 bash build.sh --apk-only`. Gradle
then validates only the exact `librknnrt.so` by a 4 KB threshold and keeps
the 16 KB requirement for every other packaged ELF. The final APK inspection
explicitly prints a `WARN` and does not count this runtime as 16 KB compatible.

Do **not** ship that APK for a 16 KB page-size device or publish it as an
all-device 16 KB-compatible APK. For such devices, obtain and validate a truly
16 KB-aligned Android `librknnrt.so` from the SoC/firmware vendor, or produce
a separate phone build without the Rockchip NPU backend and its dependencies.
The vivo smartphone uses its CPU/GPU backend, **not** Rockchip's RKNN runtime.
