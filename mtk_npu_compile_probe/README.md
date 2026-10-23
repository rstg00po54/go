# Independent MediaTek NPU Compilation Probe

This is an **isolated Android diagnostic APK** for vivo MT6993/Dimensity 9500.
It does not launch KataGo or modify `GoEngine-java`. Its single purpose is to
compare whether the **same LiteRT 2.3.0 / MediaTek Neuron API** can compile
the already-working KataGo 10b graph and the failing Human SL b18 graph.

**This is not a production patch or proof that NPU silicon executes every op.**
A successful compile only proves `CompiledModel.create` and buffer creation.

## Build

From your existing `go` repository checkout on Ubuntu:

```bash
cd /mnt/hgfs/D/code/go
git fetch origin
git switch feature/mtk-npu-compile-probe
git pull --ff-only
cd mtk_npu_compile_probe
../GoEngine-java/gradlew -p "$PWD" :app:assembleDebug
```

Prerequisites are Android SDK 35, Java 17, Gradle 8.5 (the existing wrapper),
ADB and a connected vivo. The APK has its own package:
`com.example.mtkprobe`, which will not replace BadukaiJ.

## Test 10b against Human SL b18

Use **TFLite graphs**, not the native KataGo `.bin` files.
The tested 10b model should be the existing verified 9x9 TFLite export.
If b18 is missing, stage it from the original project:

```bash
cd ../GoEngine-java
bash tools/prepare_vivo_human_litert.sh --size 9
cd ../mtk_npu_compile_probe
```

Run both models on the same phone and LiteRT version:

```bash
python3 tools/run_probe.py \
  --model 10b=../GoEngine-java/app/src/main/assets/engine/katago_10b_9x9.tflite \
  --model human=../GoEngine-java/app/src/main/assets/engine/katago_human_9x9.tflite \
  --accelerators NPU --timeout 240
```

If both a wired and a Wi-Fi ADB connection are listed, specify
`--serial <vivo-serial>`. To also compare LiteRT CPU compilation:

```bash
python3 tools/run_probe.py --skip-install \
  --model human=../GoEngine-java/app/src/main/assets/engine/katago_human_9x9.tflite \
  --accelerators CPU,NPU
```

The Python runner installs the separate APK (unless `--skip-install`),
copies each graph into its own private storage via debug `run-as`, starts
one fresh process per compile, collects phases and relevant Neuron logcat
lines, and writes per-case JSON results into `reports/`. It does **not**
clear global logcat or touch the normal BadukaiJ app.

### Interpret the outcome

| 10b NPU | b18 NPU | What can be concluded |
| --- | --- | --- |
| PASS | FAIL at COMPILED_MODEL_CREATE | Failure is b18 graph-specific under this compiler configuration; still not the exact operator |
| FAIL | FAIL | Investigate SDK, device, model path, provider and permissions first |
| PASS | PASS | Compare with original app graph, manifest, initialization order and runtime environment |
| PASS | TIMEOUT_OR_CRASH | Inspect captured vendor log lines / tombstone for native failure |

Potential b18 vendor error:
`PrepareTensors: Currently we can't support dynamic shape`.

### Optional graph-prefix experiments

The separate diagnostic helper can create *temporary* cut graphs:

```bash
python3 -m pip install --user tflite flatbuffers
python3 tools/make_prefix_probes.py \
  --source ../GoEngine-java/app/src/main/assets/engine/katago_human_9x9.tflite \
  --counts 100,300,600,1000,1400 --out segments
python3 tools/run_probe.py --skip-install \
  --model cut100=segments/cut_0100.tflite \
  --model cut300=segments/cut_0300.tflite \
  --accelerators CPU,NPU
```

Always validate each cut graph on CPU before interpreting an NPU failure.
Some cuts can be invalid, and a compiler may prune unused branches. **The
first failed cut does not prove that its last operator is the unsupported one**.
Full b18 graph must be retained unchanged for any production human-rank mode.

## Validation

Python syntax/static checks can run without Android. Android compilation
and real MT6993 Neuron execution still require your actual vivo.
The source is an experimental diagnostic, not a claim that b18 has been fixed.
