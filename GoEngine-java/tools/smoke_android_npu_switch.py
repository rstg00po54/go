#!/usr/bin/env python3
"""RK3588 NPU 9/13/19/9 fixed-shape switch and genmove regression.

Uses a debug-only activity intent (not UI tapping). By default installs the
already-built APK, runs real KataGo 10b NPU + CPU Human SL searches, records
per-move timing, and uploads PASS/FAIL to the PUBLIC ui-test-results Git branch.

Examples:
    python3 tools/smoke_android_npu_switch.py --serial 8719e18a71a2a66c
    python3 tools/smoke_android_npu_switch.py --serial 8719e18a71a2a66c --skip-install
    python3 tools/smoke_android_npu_switch.py --serial 8719e18a71a2a66c --no-human-sl --no-upload
    python3 tools/smoke_android_npu_switch.py --upload-existing build/android_npu_smoke/<run>
"""
import argparse
from datetime import datetime, timezone
import json
import math
import statistics
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path
from uuid import uuid4

from upload_referee_test_results import UploadError, upload_referee_results

PROJECT = Path(__file__).resolve().parents[1]
APK = PROJECT / "app/build/outputs/apk/debug/app-debug.apk"
PACKAGE = "com.badukai.java.nputest"
ACTIVITY = PACKAGE + "/com.badukai.MainActivity"
DEFAULT_SIZES = (9, 13, 19, 9)
TAGS = ("NpuSmoke:I", "KataGoRKNN:V", "KataGoHumanRKNN:V", "KataGoEngine:I", "libc:F", "DEBUG:F", "AndroidRuntime:E")


def command(argv, timeout=30, check=True):
    proc = subprocess.run(argv, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    if check and proc.returncode:
        raise RuntimeError("Command failed (%d): %s\n%s" %
                           (proc.returncode, " ".join(map(str, argv)), proc.stdout[-1600:]))
    return proc.stdout


def connected_serial(adb, requested):
    devices = []
    for line in command([adb, "devices"]).splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])
    if requested:
        if requested not in devices:
            raise RuntimeError("ADB device %s unavailable; connected %s" % (requested, devices))
        return requested
    if len(devices) != 1:
        raise RuntimeError("Use --serial; connected devices: %s" % devices)
    return devices[0]


def sizes_arg(value):
    try:
        sizes = tuple(int(x) for x in value.split(","))
    except ValueError as exc:
        raise argparse.ArgumentTypeError("Expected comma-separated 9,11,13,15,19") from exc
    if not 1 <= len(sizes) <= 12 or any(x not in (9, 11, 13, 15, 19) for x in sizes):
        raise argparse.ArgumentTypeError("Allowed board sizes: 9,11,13,15,19; 1-12 steps")
    return sizes


def event(line, tag, name, run_id):
    return tag in line and bool(re.search(r"\b" + re.escape(name) + r" id=" + re.escape(run_id) + r"(?:\s|$)", line))


def value(line, name):
    match = re.search(r"\b" + re.escape(name) + r"=([^\s]+)", line)
    return match.group(1) if match else None


def parse_log(log, run_id, sizes, require_human_rknn=False, human_rknn_timing_only=False):
    """Require a real RKNN_INIT + RKNN_READY for *each* test step, no CPU fallback.

    No events from a previous run qualify because NpuSmoke has a unique run ID.
    Only logs occurring after the matching STEP_BEGIN qualify for that board.
    """
    lines = log.splitlines()
    start = next((i for i, line in enumerate(lines) if event(line, "NpuSmoke", "START", run_id)), None)
    if start is None:
        return {"status": "FAIL", "errors": ["No NpuSmoke START event for this run"], "steps": []}
    lines = lines[start:]
    indices = [(i, line) for i, line in enumerate(lines) if event(line, "NpuSmoke", "STEP_BEGIN", run_id)]
    final_pass = [line for line in lines if event(line, "NpuSmoke", "PASS", run_id)]
    final_fail = [line for line in lines if event(line, "NpuSmoke", "FAIL", run_id)]
    errors, steps = [], []
    if len(indices) != len(sizes):
        errors.append("Only %d/%d STEP_BEGIN events" % (len(indices), len(sizes)))
    for at, (begin, begin_line) in enumerate(indices):
        end = indices[at + 1][0] if at + 1 < len(indices) else len(lines)
        section = lines[begin:end]
        expected = sizes[at] if at < len(sizes) else None
        board = value(begin_line, "board")
        index = value(begin_line, "index")
        model_name = "katago_10b_%sx%s.rknn" % (expected, expected)
        inits = [line for line in section if "KataGoRKNN" in line and "RKNN_INIT board=" in line]
        readies = [line for line in section if "KataGoRKNN" in line and "RKNN_READY board=" in line]
        selected = [line for line in section if "KataGoEngine" in line and "NPU_MODEL_SELECTED board=" in line]
        fallback = [line for line in section if "RKNN_DISABLED_FALLBACK_EIGEN" in line
                    or "NPU_BOARD_SIZE_MISMATCH" in line]
        passed = [line for line in section if event(line, "NpuSmoke", "STEP_PASS", run_id)]
        problems = []
        if expected is None or board != str(expected) or index != str(at):
            problems.append("STEP_BEGIN index/board unexpected: " + begin_line.strip())
        if len(inits) != 1 or value(inits[0], "board") != str(expected) or not (
                value(inits[0], "path") or "").endswith("/" + model_name):
            problems.append("Missing/wrong RKNN_INIT for " + model_name)
        if len(readies) != 1 or value(readies[0], "board") != str(expected) or not (
                value(readies[0], "model") or "").endswith("/" + model_name):
            problems.append("Missing/wrong RKNN_READY for " + model_name)
        if len(selected) != 1 or value(selected[0], "board") != str(expected) or not (
                value(selected[0], "path") or "").endswith("/" + model_name):
            problems.append("NPU_MODEL_SELECTED wrong or missing for " + model_name)
        if fallback:
            problems.append("Native RKNN fallback or mismatched board: " + fallback[-1].strip())
        if require_human_rknn:
            human_ready = [line for line in section
                           if "KataGoHumanRKNN" in line and f"HUMAN_RKNN_READY board={expected}" in line]
            human_parity = [line for line in section
                            if "KataGoHumanRKNN" in line and "HUMAN_RKNN_NATIVE_PARITY " in line]
            human_fallback = [line for line in section
                              if "KataGoHumanRKNN" in line and "HUMAN_RKNN_CPU_FALLBACK" in line]
            expected_heads = {"policyPass", "policy", "value", "score", "ownership"}
            present_heads = {value(line, "head") for line in human_parity}
            human_timings = [line for line in section
                             if "KataGoHumanRKNN" in line and "HUMAN_RKNN_INFER_TIMING " in line]
            if human_rknn_timing_only:
                enabled = [line for line in section if "KataGoEngine" in line
                           and f"HUMAN_RKNN_EXPERIMENT_REQUESTED board={expected} compareCpu=false" in line]
                if not enabled or human_parity:
                    problems.append("Timing-only test unexpectedly enabled native Eigen parity")
            npu_total_ms, npu_run_ms = [], []
            for line in human_timings:
                try:
                    total = float(value(line, "totalMs"))
                    run = float(value(line, "runMs"))
                    if not math.isfinite(total) or not math.isfinite(run) or not (0 < run <= total):
                        raise ValueError("invalid NPU timing")
                    npu_total_ms.append(total)
                    npu_run_ms.append(run)
                except (TypeError, ValueError):
                    problems.append("Malformed Human NPU inference timing: " + line.strip()[-250:])
            if not npu_total_ms:
                problems.append("Missing real Human RKNN inference timing: stale JNI or NPU was not run")
            if expected not in (9, 11, 13, 15, 19) or not human_ready:
                problems.append(f"Human b18 RKNN {expected}x{expected} did not initialize")
            elif not any("scale8Compensation=8" in line for line in human_ready):
                problems.append("Human RKNN JNI is missing scale8 compensation; rebuild and reinstall APK")
            if not human_rknn_timing_only:
                if not expected_heads.issubset(present_heads):
                    problems.append("Missing Human native CPU/NPU parity heads: "
                                    + repr(sorted(expected_heads - present_heads)))
                if any(int(value(line, "bad") or "-1") != 0 for line in human_parity):
                    problems.append("Human native CPU/NPU parity has out-of-tolerance values")
            if human_fallback:
                problems.append("Human RKNN fell back to CPU: " + human_fallback[-1].strip())
        if len(passed) != 1 or value(passed[0], "index") != str(at) or value(passed[0], "board") != str(expected):
            problems.append("Missing/mismatched STEP_PASS")
        if passed and value(passed[0], "active") != str(expected):
            problems.append("Active model does not match requested board")
        if passed:
            for key in ("blackMs", "whiteMs"):
                raw = value(passed[0], key)
                if raw is None or not raw.isdigit():
                    problems.append("Missing/invalid NN+GTP move timing: " + key)
            for key in ("black", "white"):
                if not value(passed[0], key):
                    problems.append("Missing AI move: " + key)
        detail = {"index": at, "board": expected, "status": "PASS" if not problems else "FAIL",
                  "npu_initialized": bool(inits), "npu_ready": bool(readies),
                  "model": model_name, "errors": problems}
        if require_human_rknn:
            detail["human_npu_timing_samples"] = len(npu_total_ms)
            if npu_total_ms:
                detail["human_npu_total_median_ms"] = round(statistics.median(npu_total_ms), 3)
                detail["human_npu_run_median_ms"] = round(statistics.median(npu_run_ms), 3)
                detail["human_npu_total_min_ms"] = round(min(npu_total_ms), 3)
                detail["human_npu_total_max_ms"] = round(max(npu_total_ms), 3)
        if passed:
            for key in ("startupMs", "blackMs", "whiteMs"):
                raw = value(passed[0], key)
                try: detail[key] = int(raw) if raw is not None else None
                except ValueError: detail[key] = None
            for key in ("black", "white"): detail[key] = value(passed[0], key)
        steps.append(detail)
        errors.extend("step %d: %s" % (at, item) for item in problems)
    for line in final_fail:
        errors.append("App reported failure: " + line.strip()[-600:])
    if not final_pass:
        errors.append("Missing final NpuSmoke PASS")
    if any("Fatal signal" in line or "SIGABRT" in line for line in lines):
        errors.append("Native crash detected in logcat")
    return {"status": "PASS" if not errors else "FAIL", "errors": errors, "steps": steps}


def read_log(adb, serial):
    return command([adb, "-s", serial, "logcat", "-d", "-v", "brief", "-s", *TAGS],
                   timeout=25, check=False)


def write_json(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--serial", help="RK3588 ADB serial; required when multiple devices connected")
    p.add_argument("--adb", default=shutil.which("adb"))
    p.add_argument("--apk", type=Path, default=APK)
    p.add_argument("--sizes", type=sizes_arg, default=DEFAULT_SIZES, help="e.g. 9,11,13,15,19")
    p.add_argument("--timeout", type=float, default=420, help="Total test deadline in seconds")
    p.add_argument("--skip-install", action="store_true", help="Test already installed Debug APK")
    p.add_argument("--no-human-sl", action="store_true", help="Test 10b NPU without loading CPU Human SL")
    p.add_argument("--require-human-rknn", action="store_true",
                   help="Require actual Human b18 RKNN, native 5-head parity and zero CPU fallback on every board")
    p.add_argument("--human-rknn-timing-only", action="store_true",
                   help="Only with --require-human-rknn: verify Human NPU timing without extra CPU parity")
    p.add_argument("--no-upload", action="store_true", help="Keep report locally; no GitHub push")
    p.add_argument("--upload-existing", type=Path, help="Retry uploading an existing report directory")
    p.add_argument("--upload-remote", default="auto", help="Git remote (auto: origin/github)")
    p.add_argument("--upload-branch", default="ui-test-results", help="Dedicated GitHub report branch")
    args = p.parse_args(argv)

    if args.upload_existing is not None:
        folder = args.upload_existing.resolve()
        if not (folder / "report.json").is_file() or args.no_upload:
            p.error("--upload-existing needs a report.json directory and cannot use --no-upload")
        try:
            report = json.loads((folder / "report.json").read_text(encoding="utf-8"))
            print(upload_referee_results(folder, report, args.upload_remote, args.upload_branch, run_kind="npu"))
            return 0
        except (UploadError, OSError, ValueError) as exc:
            print("Upload failed:", exc, file=sys.stderr)
            return 1
    if not args.adb:
        p.error("adb not found")
    if args.timeout < 20:
        p.error("Total --timeout must be at least 20 seconds")
    if args.require_human_rknn and (args.no_human_sl or any(size not in (9,11,13,15,19) for size in args.sizes)):
        p.error("--require-human-rknn requires Human SL enabled and only 9x9/11x11/13x13/15x15/19x19 steps")
    if args.human_rknn_timing_only and not args.require_human_rknn:
        p.error("--human-rknn-timing-only requires --require-human-rknn")
    run_id = uuid4().hex[:16]
    folder = PROJECT / "build/android_npu_smoke" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    folder.mkdir(parents=True, exist_ok=True)
    report = {"test": "RK3588 NPU multi-board smoke", "run_id": run_id,
              "sizes": list(args.sizes), "human_sl": not args.no_human_sl,
              "require_human_rknn": args.require_human_rknn,
              "human_rknn_timing_only": args.human_rknn_timing_only,
              "status": "ERROR", "steps": [], "errors": [],
              "started_utc": datetime.now(timezone.utc).isoformat()}
    serial, started = None, False
    code = 1
    try:
        serial = connected_serial(args.adb, args.serial)
        report["serial"] = serial
        soc = command([args.adb, "-s", serial, "shell",
                       "getprop ro.board.platform; getprop ro.hardware; getprop ro.soc.model"],
                      timeout=15)
        pagesize = command([args.adb, "-s", serial, "shell", "getconf PAGESIZE"], timeout=10).strip()
        (folder / "activity.txt").write_text("board/hardware: " + soc + "\npage_size=" + pagesize + "\n",
                                             encoding="utf-8")
        if "rk3588" not in soc.lower():
            raise RuntimeError("Refusing NPU smoke on non-RK3588 device: " + soc.strip())
        if not args.skip_install:
            if not args.apk.is_file():
                raise RuntimeError("Missing APK " + str(args.apk) + "; build it first")
            print("Installing", args.apk, flush=True)
            print(command([args.adb, "-s", serial, "install", "-r", "-t", str(args.apk)], timeout=150).strip())
        # A controlled debug-only smoke session. Clear stale logcat/crash buffers,
        # force-stop the app, and preserve the run ID for unambiguous correlation.
        command([args.adb, "-s", serial, "shell", "am", "force-stop", PACKAGE], timeout=15)
        command([args.adb, "-s", serial, "logcat", "-c", "-b", "all"], timeout=15)
        cmd = [args.adb, "-s", serial, "shell", "am", "start", "-n", ACTIVITY,
               "--ez", "npu_switch_smoke", "true", "--es", "npu_smoke_id", run_id,
               "--es", "npu_smoke_sizes", ",".join(map(str, args.sizes)),
               "--ez", "npu_smoke_human", "false" if args.no_human_sl else "true"]
        print("Starting NPU smoke sizes:", args.sizes, flush=True)
        print(command(cmd, timeout=25).strip(), flush=True)
        started = True
        deadline = time.monotonic() + args.timeout
        last_progress = -1
        while time.monotonic() < deadline:
            log = read_log(args.adb, serial)
            begun = sum(event(l, "NpuSmoke", "STEP_BEGIN", run_id) for l in log.splitlines())
            if begun != last_progress:
                print("NPU test step %d/%d" % (begun, len(args.sizes)), flush=True)
                last_progress = begun
            if any(event(l, "NpuSmoke", "PASS", run_id) or event(l, "NpuSmoke", "FAIL", run_id)
                   for l in log.splitlines()):
                break
            if "Fatal signal" in log and PACKAGE in log:
                raise RuntimeError("App native crash during NPU smoke")
            time.sleep(2)
        else:
            report["errors"].append("Timed out after %.1f seconds" % args.timeout)
        parsed = parse_log(read_log(args.adb, serial), run_id, args.sizes,
                           args.require_human_rknn, args.human_rknn_timing_only)
        parsed["errors"] = report["errors"] + parsed["errors"]
        if parsed["errors"]: parsed["status"] = "FAIL"
        report.update(parsed)
        code = 0 if report["status"] == "PASS" else 1
    except KeyboardInterrupt:
        report.update(status="INTERRUPTED", errors=["Ctrl+C"])
        code = 130
    except (OSError, subprocess.TimeoutExpired, RuntimeError) as exc:
        report.update(status="FAIL", errors=report["errors"] + [str(exc)])
        print("FAIL:", exc, file=sys.stderr, flush=True)
    except Exception as exc:
        report.update(status="ERROR", errors=report["errors"] + [type(exc).__name__ + ": " + str(exc)])
        print("ERROR:", exc, file=sys.stderr, flush=True)
    finally:
        if serial is not None:
            try:
                log = read_log(args.adb, serial)
                (folder / "logcat.txt").write_text(log, encoding="utf-8")
                # Crash/timeout raises from the poll loop before parse_log().
                # Recover successful early steps from the final log instead of
                # reporting an inaccurate 0/N PASS.
                if started and report["status"] != "PASS":
                    recovered = parse_log(log, run_id, args.sizes, args.require_human_rknn,
                                          args.human_rknn_timing_only)
                    report["steps"] = recovered["steps"]
                    report["errors"] = list(dict.fromkeys(report["errors"] + recovered["errors"]))
                    if report["status"] != "INTERRUPTED": report["status"] = "FAIL"
                crash = command([args.adb, "-s", serial, "logcat", "-b", "crash", "-d", "-v", "brief"],
                                timeout=20, check=False)
                # Device was cleared before this run; save crash diagnostics if present.
                if crash.strip(): (folder / "crash.txt").write_text(crash[-50000:], encoding="utf-8")
                if started and report["status"] == "PASS" and PACKAGE in crash and "Fatal signal" in crash:
                    report.update(status="FAIL", errors=report["errors"] + ["App crash buffer contains SIGABRT"])
                    code = 1
            except (OSError, subprocess.TimeoutExpired, RuntimeError) as exc:
                report["errors"].append("Cannot collect final ADB logs: " + str(exc))
                if report["status"] == "PASS":
                    report["status"], code = "FAIL", 1
        report["finished_utc"] = datetime.now(timezone.utc).isoformat()
        write_json(folder / "report.json", report)
        good = sum(s["status"] == "PASS" for s in report["steps"])
        print("Summary: %d/%d steps PASS; %s" % (good, len(args.sizes), report["status"]), flush=True)
        for step in report["steps"]:
            print("  %sx%s %-4s black=%s(%sms) white=%s(%sms)" %
                  (step["board"], step["board"], step["status"],
                   step.get("black"), step.get("blackMs"), step.get("white"), step.get("whiteMs")), flush=True)
        for error in report["errors"]: print("  ERROR:", error, flush=True)
        print("Local report:", folder / "report.json", flush=True)
        if not args.no_upload:
            try:
                url = upload_referee_results(folder, report, args.upload_remote, args.upload_branch, run_kind="npu")
                report["upload_url"] = url
                report["upload_status"] = "PASS"
            except (UploadError, OSError, RuntimeError) as exc:
                report["upload_status"] = "FAIL"
                report["upload_error"] = str(exc)
                print("GitHub upload failed; local report preserved:", exc, file=sys.stderr, flush=True)
                if code != 130: code = 1
        else:
            report["upload_status"] = "SKIPPED"
        write_json(folder / "report.json", report)
    return code


if __name__ == "__main__":
    sys.exit(main())
