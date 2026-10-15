#!/usr/bin/env python3
"""Vivo/MediaTek experimental LiteRT smoke using actual KataGo 10b tensors.

This is a graph inference probe, not yet a complete GTP game integration.
Even with Accelerator.NPU requested, LiteRT may delegate to CPU. Mark actual
hardware execution UNVERIFIED until MediaTek partition traces prove NPU use.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
from statistics import mean, median
import subprocess
import sys
import time
from uuid import uuid4
from upload_referee_test_results import UploadError, upload_referee_results

ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
COMPONENT = "com.badukai.java.nputest/com.badukai.MainActivity"


def adb(args, serial, *command, timeout=40):
    proc = subprocess.run([args.adb, "-s", serial, *command], capture_output=True, text=True, timeout=timeout)
    if proc.returncode:
        raise RuntimeError("%s: %s" % (" ".join(command), proc.stderr[-800:]))
    return proc.stdout


def detect(args):
    out = subprocess.run([args.adb, "devices"], capture_output=True, text=True, check=True).stdout
    devices = [l.split()[0] for l in out.splitlines()[1:] if l.split()[1:] == ["device"]]
    if args.serial:
        if args.serial not in devices: raise RuntimeError("Device unavailable: " + args.serial)
        return args.serial
    if len(devices) != 1:
        raise RuntimeError("Use --serial; connected devices=" + repr(devices))
    return devices[0]



def summarize_npu_logs(lines, board):
    """Extract per-run delegate evidence, without claiming confirmed NPU silicon execution."""
    plugin_loaded = any("Loaded plugin at:" in line and "MediaTek" in line for line in lines)
    dispatch_ready = any("DispatchDelegate::Initialize" in line for line in lines)
    aidl = next((m.group(1) for line in lines
                 if (m := re.search(r"AidlNeuronClient get aidl version=(\d+)", line))), None)
    neuron = next((m.group(1) for line in lines
                   if (m := re.search(r"Neuron api version:\s*([0-9.]+)", line))), None)
    partitions = []
    for line in lines:
        m = re.search(r"Replacing (\d+) out of (\d+) node\(s\) with delegate "
                      r"\(DispatchDelegate\) node, yielding (\d+) partitions", line)
        if m: partitions.append({"delegated": int(m.group(1)), "total": int(m.group(2)),
                                 "partitions": int(m.group(3))})
    scale_entries = []
    for line in lines:
        m = re.search(r"MTK_LITERT_INIT board=(\d+).*?outputScaleMultiplier=([0-9.]+)"
                      r"\s+scaleSource=([a-z_]+)", line)
        if m and int(m.group(1)) == board:
            scale_entries.append({"factor": float(m.group(2)), "source": m.group(3)})
    fallback = [line.strip()[-300:] for line in lines if re.search(
        r"fallback|CPU instead|No compiler plugin|Failed to apply|Failed to delegate|"
        r"delegate failed|failed to invoke", line, re.IGNORECASE)]
    # Registering the XNNPACK CPU accelerator is normal, not evidence of fallback.
    if partitions and all(p["total"] > 0 and p["delegated"] == p["total"] for p in partitions) and not fallback:
        delegate_status = "FULL_GRAPH_DELEGATED"
    elif partitions:
        delegate_status = "PARTIAL_OR_FALLBACK"
    else:
        delegate_status = "NOT_OBSERVED"
    durations = []
    for line in lines:
        m = re.search(r"MTK_LITERT_INFER board=(\d+) ms=([0-9]+(?:\.[0-9]+)?)", line)
        if m and int(m.group(1)) == board: durations.append(float(m.group(2)))
    timings = {"count": len(durations), "samplesMs": durations}
    if durations:
        timings.update(minMs=min(durations), medianMs=median(durations),
                       meanMs=round(mean(durations), 3), maxMs=max(durations))
    return {"compilerPluginLoaded": plugin_loaded, "dispatchInitialized": dispatch_ready,
            "neuronApiVersion": neuron, "aidlVersion": aidl,
            "delegateStatus": delegate_status, "delegatePartitions": partitions,
            "inferenceTiming": timings, "fallbackIndicators": fallback[-12:],
            "outputScaleRestoration": scale_entries,
            "hardwareExecution": "UNVERIFIED"}


def save_npu_diagnostics(folder, relevant, report, board):
    """Make compact summaries available both in local JSON and public result uploads."""
    # Publish compact per-run delegate proof and neural inference timings.
    vendor = [line for line in relevant if re.search(
        r"Neuron|dispatch|compiler.plugin|delegate|accelerat", line, re.IGNORECASE)]
    (folder / "vendor_litert.txt").write_text("\n".join(vendor) + "\n", encoding="utf-8")
    diagnostic = [line for line in relevant if re.search(
        r"MTK_LITERT_|MTK_SMOKE_|MTK_GTP_|Loaded plugin at:|Neuron api version|Neuron SDK version|"
        r"Replacing [0-9]+ out of [0-9]+|DispatchDelegate::Initialize|AidlNeuronClient|"
        r"HidlNeuronClient|Failed to load symbol|fallback|failed to delegate|"
        r"FATAL EXCEPTION|Fatal signal", line, re.IGNORECASE)]
    (folder / "npu_diagnostics.txt").write_text("\n".join(diagnostic) + "\n", encoding="utf-8")
    report["vendorLogLineCount"] = len(vendor)
    report["npuDiagnostics"] = summarize_npu_logs(relevant, board)
    report["fallbackWarnings"] = report["npuDiagnostics"]["fallbackIndicators"]
    info = report["npuDiagnostics"]
    print("Delegate:", info["delegateStatus"], "Neuron SDK:", info["neuronApiVersion"],
          "AIDL:", info["aidlVersion"], flush=True)
    samples = info["inferenceTiming"]
    print("Inference times (ms):", samples["samplesMs"],
          "average:", samples.get("meanMs", "unknown"), flush=True)



def parse_gtp_events(events, ident):
    """Validate real KataGo GTP moves and LiteRT bridge callbacks for one run."""
    marker = "MTK_GTP_SMOKE_PASS id=" + ident
    pass_line = next((line for line in events if marker in line), "")
    failures = [line for line in events if "MTK_GTP_SMOKE_FAIL id=" + ident in line]
    inference_count = sum("MTK_GTP_INFER board=" in line for line in events)
    litert_count = sum("MTK_LITERT_INFER board=" in line for line in events)
    black = re.search(r"\bblack=(\S+)", pass_line)
    white = re.search(r"\bwhite=(\S+)", pass_line)
    moves = {"black": black.group(1), "white": white.group(1)} if black and white else {}
    ok = bool(pass_line and not failures and moves and inference_count >= 1 and
              litert_count >= 1 and
              any("MTK_GTP_BRIDGE_INSTALLED" in line for line in events) and
              any("MTK_GTP_REPLY command=genmove black" in line for line in events) and
              any("MTK_GTP_REPLY command=genmove white" in line for line in events))
    return {"passed": ok, "searchMoves": moves, "gtpNeuralInferenceCount": inference_count,
            "modelInferenceCount": litert_count, "errors": failures}



# Provisional thresholds for raw *pre-postprocessing* float heads. A passing
# result is not yet certification of hardware or all board positions.
PARITY_TOLERANCES = {"policyPass": 0.025, "policy": 0.025, "value": 0.025,
                     "score": 0.20, "ownership": 0.025}


def parse_parity_events(lines, board):
    """Read CPU/Eigen vs LiteRT comparisons from the same NN feature buffers."""
    pattern = re.compile(
        r"MTK_PARITY_HEAD handle=(0x[0-9a-fA-F]+) sample=(\d+) board=(\d+) "
        r"head=(policyPass|policy|value|score|ownership) count=(\d+) "
        r"maxAbs=([\deE+.-]+) mae=([\deE+.-]+) maxRel=([\deE+.-]+) "
        r"worst=(\d+) cpu=([\deE+.-]+) npu=([\deE+.-]+)")
    cpu_pattern = re.compile(
        r"MTK_PARITY_CPU handle=(0x[0-9a-fA-F]+) sample=(\d+) "
        r"board=(\d+) batch=(\d+) ms=(\d+)")
    samples = {}
    for line in lines:
        m = cpu_pattern.search(line)
        if m and int(m.group(3)) == board:
            key = (m.group(1), int(m.group(2)))
            samples.setdefault(key, {"heads": {}, "cpuMs": None})["cpuMs"] = int(m.group(5))
        m = pattern.search(line)
        if not m or int(m.group(3)) != board:
            continue
        key = (m.group(1), int(m.group(2)))
        sample = samples.setdefault(key, {"heads": {}, "cpuMs": None})
        head = m.group(4)
        duplicate = head in sample["heads"]
        sample["heads"][head] = {
            "count": int(m.group(5)), "maxAbs": float(m.group(6)),
            "mae": float(m.group(7)), "maxRel": float(m.group(8)),
            "worstIndex": int(m.group(9)),
            "cpuAtWorst": float(m.group(10)), "npuAtWorst": float(m.group(11)),
            "duplicate": duplicate or sample["heads"].get(head, {}).get("duplicate", False)
        }
    groups = []
    totals = {head: {"count": 0, "weightedAbs": 0.0, "maxAbs": 0.0,
                     "maxRel": 0.0, "worst": None} for head in PARITY_TOLERANCES}
    import math
    complete = bool(samples)
    for (handle, index), record in sorted(samples.items(), key=lambda p: (p[0][0], p[0][1])):
        missing = sorted(set(PARITY_TOLERANCES) - set(record["heads"]))
        if missing or record["cpuMs"] is None:
            complete = False
        groups.append({"sessionHandle": handle, "sample": index, "cpuMs": record["cpuMs"],
                       "missingHeads": missing})
        for head, detail in record["heads"].items():
            if detail.get("duplicate") or not all(math.isfinite(detail[k]) for k in
                    ("maxAbs", "mae", "maxRel", "cpuAtWorst", "npuAtWorst")):
                complete = False
                continue
            aggregate = totals[head]
            count = detail["count"]
            if count < 1 or detail["worstIndex"] >= count:
                complete = False
                continue
            aggregate["count"] += count
            aggregate["weightedAbs"] += detail["mae"] * count
            if detail["maxAbs"] >= aggregate["maxAbs"]:
                aggregate["maxAbs"] = detail["maxAbs"]
                aggregate["worst"] = {"sessionHandle": handle, "sample": index,
                                      "index": detail["worstIndex"],
                                      "cpu": detail["cpuAtWorst"], "litert": detail["npuAtWorst"]}
            aggregate["maxRel"] = max(aggregate["maxRel"], detail["maxRel"])
    heads = {}
    within = bool(samples) and complete
    for head, aggregate in totals.items():
        n = aggregate["count"]
        value = {"comparedElements": n, "maxAbs": aggregate["maxAbs"],
                 "meanAbs": round(aggregate["weightedAbs"] / n, 9) if n else None,
                 "maxRel": aggregate["maxRel"], "worst": aggregate["worst"],
                 "provisionalMaxAbsTolerance": PARITY_TOLERANCES[head]}
        heads[head] = value
        if n == 0 or aggregate["maxAbs"] > PARITY_TOLERANCES[head]:
            within = False
    status = ("NO_DATA" if not samples else "INCOMPLETE" if not complete
              else "WITHIN_PROVISIONAL_TOLERANCE" if within else "MISMATCH")
    return {"status": status, "sampleCount": len(samples), "samples": groups,
            "heads": heads, "hardwareExecution": "UNVERIFIED"}


def run(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--adb", default=shutil.which("adb"))
    p.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    p.add_argument("--size", type=int, choices=(9, 13, 19), default=9)
    p.add_argument("--timeout", type=int, default=None)
    p.add_argument("--skip-install", action="store_true")
    p.add_argument("--gtp", action="store_true", help="Run real KataGo GTP genmove instead of blank-board graph probe")
    p.add_argument("--no-upload", action="store_true")
    p.add_argument("--upload-existing", type=Path, nargs="+", metavar="RUN_DIR",
                   help="Upload saved diagnostics without connecting ADB or running inference")
    args = p.parse_args(argv)
    if args.upload_existing:
        if args.no_upload: p.error("--upload-existing cannot be combined with --no-upload")
        failed = False
        for folder in args.upload_existing:
            try:
                summary_path = folder / "report.json"
                report = json.loads(summary_path.read_text(encoding="utf-8"))
                board = int(report["board"])
                if board not in (9, 13, 19): raise ValueError("Unsupported saved board size")
                relevant = (folder / "logcat.txt").read_text(encoding="utf-8").splitlines()
                save_npu_diagnostics(folder, relevant, report, board)
                summary_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n",
                                        encoding="utf-8")
                url = upload_referee_results(folder, report, run_kind="mediatek")
                print("Uploaded:", url, flush=True)
            except (OSError, ValueError, KeyError, UploadError) as err:
                failed = True
                print("Upload failed for", folder, ":", err, file=sys.stderr, flush=True)
        return 1 if failed else 0
    if args.timeout is None: args.timeout = 480 if args.gtp else 210
    if args.timeout < 1: p.error("--timeout must be positive")
    if not args.adb: p.error("adb executable not found")
    serial = detect(args)
    folder = ROOT / "build/vivo_litert_smoke" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    folder.mkdir(parents=True)
    ident = uuid4().hex[:16]
    report = {"test": "Vivo KataGo real GTP MediaTek LiteRT" if args.gtp else "Vivo MediaTek KataGo 10b LiteRT graph",
              "run_id": ident, "board": args.size, "serial": serial,
              "status": "ERROR", "hardwareExecution": "UNVERIFIED", "errors": []}
    result = 1
    try:
        soc = adb(args, serial, "shell", "getprop", "ro.soc.model").strip()
        hardware = adb(args, serial, "shell", "getprop", "ro.hardware").strip()
        report.update(soc=soc, hardware=hardware)
        if not any(n in (soc + " " + hardware).lower() for n in ("mt6990", "mt6993", "mtk", "mediatek")):
            raise RuntimeError("MediaTek SoC required; got %s / %s" % (soc, hardware))
        if not args.skip_install:
            if not APK.is_file(): raise RuntimeError("Missing APK: " + str(APK))
            print(adb(args, serial, "install", "-r", str(APK), timeout=140).strip(), flush=True)
        adb(args, serial, "shell", "am", "force-stop", "com.badukai.java.nputest")
        adb(args, serial, "logcat", "-b", "all", "-c")
        print(adb(args, serial, "shell", "am", "start", "-n", COMPONENT,
                  "--ez", "mtk_gtp_smoke" if args.gtp else "mtk_litert_smoke", "true",
                  "--es", "mtk_smoke_id", ident,
                  "--ei", "mtk_board_size", str(args.size)).strip(), flush=True)
        pass_event = "MTK_GTP_SMOKE_PASS" if args.gtp else "MTK_SMOKE_PASS"
        fail_event = "MTK_GTP_SMOKE_FAIL" if args.gtp else "MTK_SMOKE_FAIL"
        end = time.monotonic() + args.timeout
        events = []
        while time.monotonic() < end:
            txt = adb(args, serial, "logcat", "-d", "-v", "time", "-s",
                      "KataGoMTK:V", "AndroidRuntime:E", "libc:F", timeout=20)
            events = [l for l in txt.splitlines() if "KataGoMTK" in l]
            if any(pass_event + " id=" + ident in x or fail_event + " id=" + ident in x for x in events):
                break
            if "Fatal signal" in txt or "FATAL EXCEPTION" in txt:
                raise RuntimeError("App crashed while probing LiteRT")
            time.sleep(2)
        else:
            raise RuntimeError("Vivo LiteRT smoke timed out (%ds)" % args.timeout)
        (folder / "logcat.txt").write_text(txt, encoding="utf-8")
        if args.gtp:
            details = parse_gtp_events(events, ident)
            passed = details.pop("passed")
            report.update(details)
        else:
            failures = [x for x in events if fail_event + " id=" + ident in x]
            inferences = [x for x in events if "MTK_LITERT_INFER board=" in x]
            passed = any(pass_event + " id=" + ident in x for x in events) and len(inferences) >= 3 and not failures
            report.update(modelInferenceCount=len(inferences), errors=failures)
        report["status"] = ("GTP_PASS" if args.gtp else "PROBE_PASS") if passed else "FAIL"
        if not passed: raise RuntimeError("MediaTek GTP test failed" if args.gtp else "LiteRT graph probe failed")
        result = 0
    except (OSError, ValueError, subprocess.TimeoutExpired, RuntimeError) as err:
        report["status"] = "FAIL"
        report["errors"].append(str(err))
        print("FAIL:", err, file=sys.stderr)
    finally:
        try:
            full_logcat = adb(args, serial, "logcat", "-d", "-v", "time", timeout=35)
            relevant = [line for line in full_logcat.splitlines() if re.search(
                r"KataGoMTK|LiteRT|litert|MediaTek|neuron|Neuron|Dispatch|compiler.plugin|"
                r"accelerator|FATAL.EXCEPTION|Fatal.signal|SIGABRT|SIGSEGV", line, re.IGNORECASE)]
            (folder / "logcat.txt").write_text("\n".join(relevant) + "\n", encoding="utf-8")
            save_npu_diagnostics(folder, relevant, report, args.size)
            if args.gtp:
                report["cpuLiteRtParity"] = parse_parity_events(relevant, args.size)
                parity = report["cpuLiteRtParity"]
                print("CPU/LiteRT parity:", parity["status"], "samples:", parity["sampleCount"], flush=True)
                for head, values in parity["heads"].items():
                    print("  ", head, "maxAbs:", values["maxAbs"],
                          "MAE:", values["meanAbs"], flush=True)
                (folder / "parity_summary.json").write_text(
                    json.dumps(parity, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
            crash = adb(args, serial, "logcat", "-b", "crash", "-d", "-v", "brief")
            if crash.strip(): (folder / "crash.txt").write_text(crash, encoding="utf-8")
        except Exception as err:
            report["errors"].append("Failed to save diagnostics: " + str(err))
        (folder / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
        print("Status:", report["status"], "(real NPU execution unverified)", flush=True)
        if args.gtp: print("GTP moves:", report.get("searchMoves"), "real NN calls:", report.get("gtpNeuralInferenceCount"), flush=True)
        print("Report:", folder / "report.json", flush=True)
        if not args.no_upload:
            try:
                url = upload_referee_results(folder, report, run_kind="mediatek_gtp" if args.gtp else "mediatek")
                print("Uploaded:", url, flush=True)
            except Exception as err:
                print("GitHub upload failed; local report preserved:", err, file=sys.stderr)
    return result


if __name__ == "__main__":
    sys.exit(run())
