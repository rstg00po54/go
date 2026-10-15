#!/usr/bin/env python3
"""Compare full KataGo genmove latency across CPU, GPU/OpenCL and MediaTek LiteRT.

Uses debug-only Android intent in a *fresh app process per backend* to avoid
cross-backend native state interference. Every backend measures the same blank
board with the same KataGo 10b model, visits and time limit. Warm-up moves are
excluded; engine startup is timed separately. Optional --human-sl measures
realistic Human SL game overhead rather than primarily the 10b evaluator.

Default: 9x9, 32 max visits, 12s move cap, 1 warm-up + 5 measurements
per backend. Outputs one JSON and full log per backend and auto-uploads to
the GitHub ui-test-results branch without touching older reports.
"""
import argparse
import csv
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import sys
import time
from uuid import uuid4

from upload_referee_test_results import UploadError, upload_referee_results

ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
PKG = "com.badukai.java.nputest"
ACTIVITY = PKG + "/com.badukai.MainActivity"
TAGS = ("KataGoBench:V", "KataGoEngine:I", "KataGoMTK:I",
        "AndroidRuntime:E", "libc:F")
BACKENDS = ("CPU", "GPU", "NPU")


def cmd(parts, timeout=30, check=True):
    result = subprocess.run(parts, capture_output=True, text=True, timeout=timeout)
    combined = result.stdout + result.stderr
    if check and result.returncode:
        raise RuntimeError("%s failed (%d): %s" % (" ".join(map(str, parts)),
                                                 result.returncode, combined[-1800:]))
    return combined


def select_serial(adb, preferred):
    connected = []
    for line in cmd([adb, "devices"]).splitlines()[1:]:
        fields = line.split()
        if len(fields) > 1 and fields[1] == "device":
            connected.append(fields[0])
    if preferred:
        if preferred not in connected:
            raise RuntimeError("ADB serial unavailable; connected: " + str(connected))
        return preferred
    if len(connected) != 1:
        raise RuntimeError("Specify --serial; connected: " + str(connected))
    return connected[0]


def parse_backends(raw):
    values = tuple(s.strip().upper() for s in raw.split(","))
    if not values or any(s not in BACKENDS for s in values) or len(set(values)) != len(values):
        raise argparse.ArgumentTypeError("Unique comma-separated backends from cpu,gpu,npu required")
    return values


def field(line, key):
    match = re.search(r"\b" + re.escape(key) + r"=([^\s]+)", line)
    return match.group(1) if match else None


def parse_backend_log(raw, run_id, backend, board, warmups, repeats):
    """Reject CPU fallbacks and incomplete measurements; summarize only real moves."""
    lines = [line for line in raw.splitlines() if "KataGoBench" in line
             and field(line, "id") == run_id]
    starts = [x for x in lines if "START id=" + run_id in x]
    ready = [x for x in lines if "READY id=" + run_id in x]
    moves = [x for x in lines if "MOVE id=" + run_id in x]
    passes = [x for x in lines if "PASS id=" + run_id in x]
    failures = [x for x in lines if "FAIL id=" + run_id in x]
    errors = []
    if len(starts) != 1 or field(starts[0], "backend") != backend or field(starts[0], "board") != str(board):
        errors.append("Missing or mismatched benchmark START")
    if len(ready) != 1:
        errors.append("Missing or duplicated READY")
    elif field(ready[0], "backend") != backend or field(ready[0], "board") != str(board) or not (
            field(ready[0], "actual") or "").startswith(backend + "/"):
        errors.append("Requested backend not confirmed (possible CPU fallback)")
    if len(passes) != 1 or field(passes[0], "backend") != backend:
        errors.append("No matching completed benchmark PASS")
    if failures:
        errors.extend("Android reported " + x[-350:] for x in failures[-3:])
    if "FATAL EXCEPTION" in raw or "Fatal signal" in raw:
        errors.append("Android/native crash")
    measurements, warmup_samples = [], []
    found_iters = set()
    for row in moves:
        if field(row, "backend") != backend or field(row, "board") != str(board):
            errors.append("Wrong backend/board in MOVE")
            continue
        try:
            iteration = int(field(row, "iter"))
            elapsed = int(field(row, "ms"))
        except (TypeError, ValueError):
            errors.append("Invalid MOVE iteration or milliseconds")
            continue
        if iteration in found_iters or iteration < 0 or iteration >= warmups + repeats or elapsed < 0:
            errors.append("Duplicate/out-of-range MOVE iteration")
            continue
        found_iters.add(iteration)
        warmup = iteration < warmups
        if field(row, "warmup") != str(warmup).lower():
            errors.append("Incorrect warm-up marker")
        move = field(row, "vertex")
        if not move or not re.fullmatch(r"(?:PASS|RESIGN|[A-HJ-T](?:1[0-9]|[1-9]))", move):
            errors.append("Invalid GTP move")
        sample = {"iter": iteration, "move": move, "elapsedMs": elapsed,
                  "warmup": warmup}
        (warmup_samples if warmup else measurements).append(sample)
    if len(measurements) != repeats or len(warmup_samples) != warmups:
        errors.append("Incomplete moves: %s/%s timed, %s/%s warm-up" %
                      (len(measurements), repeats, len(warmup_samples), warmups))
    times = [sample["elapsedMs"] for sample in sorted(measurements, key=lambda s: s["iter"])]
    startup_ms = None
    if len(ready) == 1:
        try:
            startup_ms = int(field(ready[0], "startupMs"))
            if startup_ms < 0: raise ValueError("negative startup time")
        except (TypeError, ValueError):
            errors.append("Missing/invalid cold startup milliseconds")
            startup_ms = None
    npu_calls = sum("MTK_GTP_INFER board=" + str(board) in line for line in raw.splitlines())
    npu_times = []
    for line in raw.splitlines():
        if "MTK_LITERT_INFER board=" + str(board) in line:
            ms = field(line, "ms")
            if ms is not None:
                try: npu_times.append(float(ms))
                except ValueError: pass
    if backend == "NPU" and npu_calls < 1:
        errors.append("MediaTek LiteRT evaluator never invoked")
    sorted_times = sorted(times)
    summary = None
    if len(times) == repeats:
        summary = {
            "meanMs": round(statistics.mean(times), 3),
            "medianMs": round(statistics.median(times), 3),
            "minMs": min(times),
            "maxMs": max(times),
            "p95NearestRankMs": sorted_times[(95 * len(times) + 99) // 100 - 1],
        }
    return {
        "status": "PASS" if not errors else "FAIL",
        "backend": backend, "board": board,
        "startupMs": startup_ms,
        "latencyMs": summary,
        "measuredMoves": sorted(measurements, key=lambda s: s["iter"]),
        "warmupMoves": sorted(warmup_samples, key=lambda s: s["iter"]),
        "liteRtInferenceCalls": npu_calls if backend == "NPU" else None,
        "liteRtMeanInferenceMs": round(statistics.mean(npu_times), 3)
            if backend == "NPU" and npu_times else None,
        "errors": errors
    }


def collect(adb, serial):
    return cmd([adb, "-s", serial, "logcat", "-d", "-v", "time", "-s", *TAGS],
               timeout=30, check=False)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=shutil.which("adb"))
    parser.add_argument("--serial")
    parser.add_argument("--backends", type=parse_backends, default=BACKENDS,
                        help="Comparison order, e.g. gpu,npu or cpu,gpu,npu")
    parser.add_argument("--size", type=int, choices=(9, 13, 19), default=9)
    parser.add_argument("--visits", type=int, default=32)
    parser.add_argument("--seconds", type=float, default=12.0)
    parser.add_argument("--warmup", type=int, default=1)
    parser.add_argument("--repeats", type=int, default=5)
    parser.add_argument("--human-sl", action="store_true",
                        help="Include CPU Human SL for realistic game timings")
    parser.add_argument("--skip-install", action="store_true")
    parser.add_argument("--no-upload", action="store_true")
    parser.add_argument("--timeout-per-backend", type=int, default=480)
    args = parser.parse_args(argv)
    if not args.adb: parser.error("adb is not installed")
    if args.visits < 1 or args.visits > 512 or not 0.1 <= args.seconds <= 50.0:
        parser.error("visits must be 1..512, seconds 0.1..50")
    if args.repeats < 2 or args.repeats > 30 or args.warmup < 1 or args.warmup > 10:
        parser.error("repeats 2..30, warmup 1..10")
    if args.timeout_per_backend < 60:
        parser.error("--timeout-per-backend must be >= 60")
    run_id = uuid4().hex[:16]
    folder = ROOT / "build/vivo_backend_bench" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    folder.mkdir(parents=True, exist_ok=False)
    report = {
        "test": "Vivo KataGo CPU GPU MediaTek GTP wall time benchmark",
        "run_id": run_id, "board": args.size,
        "backends": list(args.backends), "visits": args.visits,
        "maxTimeSeconds": args.seconds, "warmup": args.warmup,
        "repeats": args.repeats, "humanSL": args.human_sl,
        "timingScope": "Full genmove GTP wall time (model inference + search + IPC)",
        "hardwareExecution": "UNVERIFIED",
        "status": "FAIL", "results": {}, "errors": [],
        "startedUtc": datetime.now(timezone.utc).isoformat(),
    }
    serial = None
    try:
        serial = select_serial(args.adb, args.serial)
        report["serial"] = serial
        soc = cmd([args.adb, "-s", serial, "shell", "getprop", "ro.soc.model"]).strip()
        report["socModel"] = soc
        if not re.search(r"MT6990|MT6993|mtk|mediatek", soc, re.IGNORECASE):
            raise RuntimeError("Only supported on MediaTek vivo; SoC=" + soc)
        if not args.skip_install:
            if not APK.is_file(): raise RuntimeError("APK not built: " + str(APK))
            print(cmd([args.adb, "-s", serial, "install", "-r", str(APK)],
                      timeout=180).strip(), flush=True)
        for backend in args.backends:
            ident = run_id + "_" + backend.lower()
            print("\n=== %s / %dx%d, %d visits, %d warm-up + %d measured ===" %
                  (backend, args.size, args.size, args.visits, args.warmup, args.repeats),
                  flush=True)
            log = ""
            try:
                # A new app process per backend prevents persistent OpenCL/Neuron
                # state from invalidating a fair comparison.
                cmd([args.adb, "-s", serial, "shell", "am", "force-stop", PKG])
                cmd([args.adb, "-s", serial, "logcat", "-b", "all", "-c"])
                output = cmd([args.adb, "-s", serial, "shell", "am", "start", "-n", ACTIVITY,
                              "--ez", "katago_backend_bench", "true",
                              "--es", "bench_id", ident,
                              "--es", "bench_backend", backend,
                              "--ei", "bench_board", str(args.size),
                              "--ei", "bench_visits", str(args.visits),
                              "--ed", "bench_seconds", str(args.seconds),
                              "--ei", "bench_warmup", str(args.warmup),
                              "--ei", "bench_repeats", str(args.repeats),
                              "--ez", "bench_human", str(args.human_sl).lower()])
                print(output.strip(), flush=True)
                deadline = time.monotonic() + args.timeout_per_backend
                latest_marker = ""
                last_notice = time.monotonic()
                while time.monotonic() < deadline:
                    log = collect(args.adb, serial)
                    events = [line for line in log.splitlines()
                              if "KataGoBench" in line and field(line, "id") == ident]
                    markers = [line for line in events if any(
                        word + " id=" + ident in line for word in
                        ("PHASE", "READY", "MOVE", "PASS", "FAIL"))]
                    if markers and markers[-1] != latest_marker:
                        latest_marker = markers[-1]
                        last_notice = time.monotonic()
                        short = latest_marker.split("KataGoBench", 1)[-1].strip()
                        print("  " + short, flush=True)
                    elif time.monotonic() - last_notice >= 15:
                        print("  Still running (%s): %s" %
                              (backend, latest_marker.split("KataGoBench", 1)[-1].strip()
                               if latest_marker else "Activity startup"), flush=True)
                        last_notice = time.monotonic()
                    if any("PASS id=" + ident in line or "FAIL id=" + ident in line
                           for line in events):
                        break
                    if "FATAL EXCEPTION" in log or "Fatal signal" in log:
                        break
                    time.sleep(2)
                else:
                    report["errors"].append(backend + " timed out after " +
                                            str(args.timeout_per_backend) + " seconds")
                log = collect(args.adb, serial)
                (folder / (backend.lower() + "_logcat.txt")).write_text(log, encoding="utf-8")
                result = parse_backend_log(log, ident, backend, args.size, args.warmup, args.repeats)
                report["results"][backend] = result
                median = result["latencyMs"]["medianMs"] if result["latencyMs"] else None
                print("  %s: startup=%sms median move=%sms, mean=%sms" %
                      (result["status"], result["startupMs"], median,
                       result["latencyMs"]["meanMs"] if result["latencyMs"] else None),
                      flush=True)
                for error in result["errors"]: print("  WARNING: " + error, flush=True)
            except (OSError, RuntimeError, subprocess.TimeoutExpired) as exc:
                print("  ERROR:", exc, flush=True)
                report["results"][backend] = {"status": "FAIL", "errors": [str(exc)]}
                report["errors"].append(backend + ": " + str(exc))
                if log: (folder / (backend.lower() + "_logcat.txt")).write_text(log, encoding="utf-8")
        successful = [name for name, item in report["results"].items() if item["status"] == "PASS"]
        report["status"] = ("PASS" if len(successful) == len(args.backends)
                            else "PARTIAL" if successful else "FAIL")
        # Comparisons are valid only if BOTH backends ran with exact same settings.
        ref = report["results"].get("GPU", {})
        other = report["results"].get("NPU", {})
        if ref.get("status") == "PASS" and other.get("status") == "PASS":
            gpu = ref["latencyMs"]["medianMs"]
            npu = other["latencyMs"]["medianMs"]
            report["comparison"] = {
                "reference": "GPU", "target": "NPU",
                "gpuMedianMs": gpu, "npuMedianMs": npu,
                "gpuOverNpuSpeedup": round(gpu / npu, 3) if npu else None,
                "meaning": "Full GTP genmove median ratio; not standalone silicon inference speed"
            }
        print("\nSummary:", report["status"], flush=True)
        for backend, result in report["results"].items():
            ms = result.get("latencyMs")
            print("  %-3s %-7s cold-start=%s ms median=%s ms mean=%s ms" %
                  (backend, result["status"], result.get("startupMs"),
                   ms["medianMs"] if ms else "-", ms["meanMs"] if ms else "-"), flush=True)
        if "comparison" in report:
            print("  GPU / NPU median wall time ratio: %.3fx" %
                  report["comparison"]["gpuOverNpuSpeedup"], flush=True)
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as exc:
        report["errors"].append(str(exc))
        print("Benchmark setup failed:", exc, file=sys.stderr, flush=True)
    finally:
        report["finishedUtc"] = datetime.now(timezone.utc).isoformat()
        (folder / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                                            encoding="utf-8")
        print("Local report:", folder / "report.json", flush=True)
        if not args.no_upload:
            try:
                url = upload_referee_results(folder, report, run_kind="mediatek_bench")
                print("Uploaded:", url, flush=True)
            except (UploadError, OSError, RuntimeError) as exc:
                print("Auto-upload failed; local files kept:", exc, file=sys.stderr, flush=True)
                report["errors"].append("Upload failed: " + str(exc))
                report["status"] = "PARTIAL"
        (folder / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                                            encoding="utf-8")
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
