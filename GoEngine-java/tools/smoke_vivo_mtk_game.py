#!/usr/bin/env python3
"""Test actual KataGoEngine UI backend orchestration on MediaTek Android.

Runs the debug-only NpuSmoke intent, loading Human SL and size-specific
MediaTek LiteRT for 9,11,13,15,19,9. Asserts every step used MediaTek and returned
two legal moves. Uploads separate reports to ui-test-results unless disabled.
This does not erase previous results (but clears Android logcat before a run).
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
from uuid import uuid4

from upload_referee_test_results import UploadError, upload_referee_results

PROJECT = Path(__file__).resolve().parents[1]
APK = PROJECT / "app/build/outputs/apk/debug/app-debug.apk"
PACKAGE = "com.badukai.java.nputest"
ACTIVITY = PACKAGE + "/com.badukai.MainActivity"
DEFAULT_SIZES = (9, 11, 13, 15, 19, 9)


def run_cmd(argv, timeout=30, check=True):
    proc = subprocess.run(argv, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    if check and proc.returncode:
        raise RuntimeError("Command failed: " + " ".join(map(str, argv)) + "\n" + proc.stdout[-1200:])
    return proc.stdout


def connected(adb, serial):
    devices = []
    for line in run_cmd([adb, "devices"]).splitlines()[1:]:
        fields = line.split()
        if len(fields) >= 2 and fields[1] == "device": devices.append(fields[0])
    if serial and serial in devices: return serial
    if serial: raise RuntimeError("ADB serial not connected")
    if len(devices) != 1: raise RuntimeError("Specify --serial; connected devices: " + str(devices))
    return devices[0]


def field(line, key):
    match = re.search(r"\b" + re.escape(key) + r"=([^\s]+)", line)
    return match.group(1) if match else None


def vertex(move, size):
    if move == "PASS": return True
    m = re.fullmatch(r"([A-HJ-T])(1[0-9]|[1-9])", move or "")
    return bool(m and ord(m.group(1)) - ord("A") - (m.group(1) > "I") < size
                and int(m.group(2)) <= size)


def parse_game_log(raw, run_id, sizes, human=True, require_human_npu=False):
    lines = raw.splitlines()
    prefix = "NpuSmoke"
    start = next((idx for idx, line in enumerate(lines)
                  if prefix in line and "START id=" + run_id in line), None)
    if start is None: return {"status": "FAIL", "steps": [], "errors": ["No matching NpuSmoke START"]}
    lines = lines[start:]
    indexes = [idx for idx, line in enumerate(lines) if prefix in line and
               "STEP_BEGIN id=" + run_id in line]
    end_ok = any(prefix in line and re.search(r"\bPASS id=" + re.escape(run_id) + r"(?:\s|$)", line) for line in lines)
    errors, steps = [], []
    precheck = [line for line in lines if prefix in line
                and "MTK_CPU_TO_NPU_PRECHECK_READY id=" + run_id in line]
    if len(precheck) != 1 or "backend=CPU/" not in precheck[0]:
        errors.append("CPU JNI preload and CPU-to-MediaTek switch precheck missing")
    if len(indexes) != len(sizes): errors.append("Expected %d steps; saw %d" % (len(sizes), len(indexes)))
    for index, begin in enumerate(indexes):
        requested = sizes[index] if index < len(sizes) else None
        part = lines[begin: indexes[index+1] if index+1 < len(indexes) else len(lines)]
        begin_line = part[0]
        prepared = [line for line in part if "MTK_LITERT_INIT board=" in line
                    and field(line, "model") != "human"]
        human_init = [line for line in part if "MTK_LITERT_INIT board=" in line
                      and field(line, "model") == "human"]
        human_ready = [line for line in part if "MTK_HUMAN_LITERT_READY board=" in line]
        human_calls = [line for line in part if "MTK_GTP_INFER board=" in line
                       and field(line, "board") == str(requested)
                       and field(line, "model") == "human"]
        human_parity = [line for line in part if "MTK_PARITY_HEAD" in line
                        and field(line, "model") == "human"]
        ready = [line for line in part if "MTK_GAME_BACKEND_READY board=" in line]
        neural = [line for line in part if "MTK_GTP_INFER board=" in line
                  and field(line, "board") == str(requested)]
        terminal = [line for line in part if prefix in line and "STEP_PASS id=" + run_id in line]
        fallback = [line for line in part if "falling back to cpu" in line.lower()
                    or ("fallback" in line.lower() and "npu" in line.lower())
                    or "MTK_GTP_SMOKE_FAIL" in line]
        part_errors = []
        if field(begin_line, "index") != str(index) or field(begin_line, "board") != str(requested):
            part_errors.append("Incorrect board/index in STEP_BEGIN")
        if len(prepared) != 1 or field(prepared[0], "board") != str(requested):
            part_errors.append("No size-matched MediaTek graph initialization")
        if len(ready) != 1 or field(ready[0], "board") != str(requested):
            part_errors.append("No genuine MediaTek game session")
        if not neural:
            part_errors.append("MediaTek game session did not actually invoke LiteRT evaluator")
        if human and ready and field(ready[0], "humanSL") != "true":
            part_errors.append("Human SL was not active")
        if len(terminal) != 1 or field(terminal[0], "active") != str(requested):
            part_errors.append("No matching STEP_PASS/active board")
        if fallback: part_errors.append("NPU fallback observed")
        if require_human_npu:
            if len(human_init) != 1 or len(human_ready) != 1:
                part_errors.append("No separately compiled b18 Human LiteRT graph")
            if not human_calls: part_errors.append("Human SL still executed on CPU/Eigen")
            human_heads = {field(line, "head") for line in human_parity}
            if human_heads != {"policyPass", "policy", "value", "score", "ownership"}:
                part_errors.append("Missing actual Human CPU/LiteRT 5-head parity")
            for line in human_parity:
                try:
                    diff = float(field(line, "maxAbs"))
                    tolerance = 0.20 if field(line, "head") == "score" else 0.025
                    if not (0 <= diff <= tolerance):
                        part_errors.append("Human output parity failure " + str(field(line, "head"))
                                           + " maxAbs=" + str(diff))
                except (TypeError, ValueError):
                    part_errors.append("Invalid Human CPU/LiteRT parity diagnostic")
            if any("MTK_HUMAN_CPU_FALLBACK" in line for line in part):
                part_errors.append("Human SL NPU graph fell back to CPU")
        if terminal:
            for color in ("black", "white"):
                if not vertex(field(terminal[0], color), requested):
                    part_errors.append("Illegal/missing " + color + " move")
            for name in ("startupMs", "blackMs", "whiteMs"):
                value = field(terminal[0], name)
                if value is None or not value.isdigit(): part_errors.append("Invalid " + name)
        timing = {"10b": [], "human": []}
        for line in part:
            if "MTK_LITERT_INFER board=" not in line or field(line, "board") != str(requested):
                continue
            model = field(line, "model")
            if model in timing:
                try: timing[model].append(float(field(line, "ms")))
                except (ValueError, TypeError): part_errors.append("Invalid inference timing")
        summary = {model: {"count": len(values),
                           "meanMs": round(sum(values)/len(values),3) if values else None,
                           "minMs": min(values) if values else None,
                           "maxMs": max(values) if values else None}
                   for model,values in timing.items()}
        phase_ms = {}
        if terminal:
            for key in ("startupMs", "setupMs", "blackMs", "whiteMs"):
                value = field(terminal[0], key)
                if value is not None:
                    try: phase_ms[key] = float(value)
                    except ValueError: part_errors.append("Invalid phase timing " + key)
        item = {"index": index, "board": requested, "phaseTimingMs": phase_ms,
                "inferenceTiming": summary,
                "status": "PASS" if not part_errors else "FAIL", "errors": part_errors,
                "litertInferenceCount": len(neural),
                "humanLiteRtInferenceCount": len(human_calls),
                "humanParitySampleCount": len(human_parity),
                "black": field(terminal[0], "black") if terminal else None,
                "white": field(terminal[0], "white") if terminal else None}
        steps.append(item)
        errors.extend("Step %d: %s" % (index, err) for err in part_errors)
    if not end_ok: errors.append("Missing overall NpuSmoke PASS")
    if any(prefix in line and "FAIL id=" + run_id in line for line in lines):
        errors.append("App reported FAIL")
    if "FATAL EXCEPTION" in raw or "Fatal signal" in raw:
        errors.append("Android/native crash")
    return {"status": "PASS" if not errors else "FAIL", "steps": steps, "errors": errors}


def capture_activity_launch_diagnostics(adb, serial, folder, run_id):
    """Identify app crash, unlaunched activity or missing filtered log tags."""
    pid = run_cmd([adb, "-s", serial, "shell", "pidof", PACKAGE], check=False).strip()
    raw = run_cmd([adb, "-s", serial, "logcat", "-d", "-b", "all", "-v", "time"],
                  timeout=35, check=False)
    pattern = re.compile(r"(?i)(com\\.badukai|NpuSmoke|AndroidRuntime|"
                         r"FATAL EXCEPTION|am_crash|am_anr|neuron.*compile)")
    lines = [line[:1500] for line in raw.splitlines() if pattern.search(line)]
    file = folder / "activity_launch_diagnostics.txt"
    file.write_text("app_pid=" + (pid or "NOT_RUNNING") + "\n" +
                    "\n".join(lines[-180:]) + "\n", encoding="utf-8")
    print("No NpuSmoke START observed: app PID=" + (pid or "NOT_RUNNING") +
          ", matched system lines=" + str(len(lines)), flush=True)
    for line in lines[-10:]:
        print("  Android:", line[-350:], flush=True)
    print("Startup diagnostics:", file, flush=True)
    return bool(pid)


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--adb", default=shutil.which("adb"))
    p.add_argument("--serial")
    p.add_argument("--skip-install", action="store_true")
    p.add_argument("--no-human-sl", action="store_true")
    p.add_argument("--diagnose-compiler", action="store_true",
                   help="Capture filtered MediaTek Neuron compiler diagnostics; no root needed")
    p.add_argument("--require-human-npu", action="store_true",
                   help="Require real b18 Human NN LiteRT inference and 5-head Eigen parity (experimental)")
    p.add_argument("--sizes", default="9,11,13,15,19,9",
                   help="Ordered board sizes; e.g. --sizes 9 for first Human NPU validation")
    p.add_argument("--no-upload", action="store_true")
    p.add_argument("--timeout", type=int, default=480)
    p.add_argument("--stall-timeout", type=int, default=150,
                   help="Fail with captured logs if no new engine stage appears for this many seconds")
    args = p.parse_args(argv)
    if not args.adb: p.error("adb not installed")
    try:
        sizes = tuple(int(x) for x in args.sizes.split(","))
        if not sizes or len(sizes) > 12 or any(x not in (9,11,13,15,19) for x in sizes):
            raise ValueError("Invalid board sizes")
    except ValueError as exc:
        p.error("--sizes accepts a comma-separated sequence of 9,11,13,15,19 only: " + str(exc))
    if args.require_human_npu and args.no_human_sl:
        p.error("--require-human-npu requires Human SL enabled")
    if args.timeout < 10 or args.stall_timeout < 10:
        p.error("--timeout and --stall-timeout must both be at least 10 seconds")
    run_id = uuid4().hex[:16]
    folder = PROJECT / "build/vivo_mtk_game_smoke" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    folder.mkdir(parents=True, exist_ok=False)
    report = {"test": "vivo MediaTek KataGo real game backend with Human SL", "run_id": run_id,
              "sizes": list(sizes), "humanSL": not args.no_human_sl,
              "requireHumanNpu": args.require_human_npu,
              "hardwareExecution": "UNVERIFIED", "status": "FAIL", "errors": [], "steps": []}
    code, serial = 1, None
    previous_neuron_props = {}
    try:
        serial = connected(args.adb, args.serial)
        report["serial"] = serial
        soc = run_cmd([args.adb, "-s", serial, "shell", "getprop", "ro.soc.model"]).strip()
        report["socModel"] = soc
        if not re.search(r"MT6990|MT6993|mtk|mediatek", soc, re.IGNORECASE):
            raise RuntimeError("MediaTek phone required (SoC=" + soc + ")")
        if not args.skip_install:
            if not APK.is_file(): raise RuntimeError("Build experimental APK first")
            print(run_cmd([args.adb, "-s", serial, "install", "-r", str(APK)], timeout=150).strip())
        run_cmd([args.adb, "-s", serial, "shell", "am", "force-stop", PACKAGE])
        if args.diagnose_compiler:
            for name in ("debug.neuron.runtime.ShowTargetReport",
                         "debug.neuron.runtime.ShowExecPlan"):
                try:
                    old = run_cmd([args.adb, "-s", serial, "shell", "getprop", name]).strip()
                    previous_neuron_props[name] = old
                    run_cmd([args.adb, "-s", serial, "shell", "setprop", name, "true"])
                except (RuntimeError, OSError, subprocess.TimeoutExpired) as ex:
                    print("Neuron debug property unavailable (normal on retail phones):",
                          name, str(ex)[:200], flush=True)
        run_cmd([args.adb, "-s", serial, "logcat", "-b", "all", "-c"])
        print(run_cmd([args.adb, "-s", serial, "shell", "am", "start", "-n", ACTIVITY,
                       "--ez", "npu_switch_smoke", "true",
                       "--es", "npu_smoke_id", run_id,
                       "--es", "npu_smoke_sizes", ",".join(map(str, sizes)),
                       "--ez", "npu_smoke_parity", "true" if args.require_human_npu else "false",
                       "--ez", "npu_smoke_human", "false" if args.no_human_sl else "true"]).strip())
        started_at = last_progress_at = last_heartbeat = time.monotonic()
        end = started_at + args.timeout
        last_progress = ""
        launch_checked = False
        tags = ["NpuSmoke:V", "KataGoMTK:V", "KataGoEngine:V", "AndroidRuntime:E", "libc:F"]
        print("Tracking CPU preload, NPU initialization and Human SL moves"
              " (overall timeout=%ds; stall timeout=%ds)" %
              (args.timeout, args.stall_timeout), flush=True)
        while time.monotonic() < end:
            logs = run_cmd([args.adb, "-s", serial, "logcat", "-d", "-v", "time", "-s", *tags],
                           timeout=30, check=False)
            # The Java test emits one marker before each blocking operation.
            # Track the LAST new marker, not the total count of log entries,
            # so the script shows exactly where a device stops making progress.
            progress_lines = [line for line in logs.splitlines()
                              if "NpuSmoke" in line
                              and any(marker + " id=" + run_id in line for marker in
                                      ("START", "STAGE", "MTK_CPU_TO_NPU_PRECHECK_READY",
                                       "STEP_BEGIN", "STEP_PASS"))]
            newest = progress_lines[-1] if progress_lines else ""
            now = time.monotonic()
            if newest and newest != last_progress:
                last_progress = newest
                last_progress_at = now
                last_heartbeat = now
                print("Progress (%.0fs): %s" %
                      (now - started_at, newest.split("NpuSmoke:", 1)[-1].strip()), flush=True)
            elif now - last_heartbeat >= 15:
                print("Still running: %.0fs total, %.0fs since stage %s" %
                      (now - started_at, now - last_progress_at,
                       last_progress.split("NpuSmoke:", 1)[-1].strip() if last_progress else "Activity launch"),
                      flush=True)
                last_heartbeat = now
            if not newest and not launch_checked and now - started_at >= 20:
                launch_checked = True
                try:
                    app_running = capture_activity_launch_diagnostics(
                        args.adb, serial, folder, run_id)
                    if not app_running:
                        report["errors"].append(
                            "Android game process is not running; check activity_launch_diagnostics.txt")
                        break
                except (OSError, RuntimeError, subprocess.TimeoutExpired) as ex:
                    print("Could not capture startup diagnostics:", ex, flush=True)
            if re.search(r"\b(?:PASS|FAIL) id=" + re.escape(run_id) + r"(?:\s|$)", logs):
                break
            if "Fatal signal" in logs or "FATAL EXCEPTION" in logs: break
            if now - last_progress_at >= args.stall_timeout:
                report["errors"].append("No new game phase in %ds; last: %s" %
                                        (args.stall_timeout, last_progress[-250:] or "Activity launch"))
                break
            time.sleep(2)
        else:
            report["errors"].append("Timed out after %ds; last stage: %s" %
                                    (args.timeout, last_progress[-250:] or "Activity launch"))
        log = run_cmd([args.adb, "-s", serial, "logcat", "-d", "-v", "time", "-s", *tags],
                      timeout=30, check=False)
        (folder / "logcat.txt").write_text(log, encoding="utf-8")
        if "Fatal signal" in log or "FATAL EXCEPTION" in log:
            # Capture the crash buffer separately: the normal filtered app log
            # only contains SIGABRT, not its abort message/native stack.
            crash = run_cmd([args.adb, "-s", serial, "logcat", "-d", "-b", "crash",
                             "-v", "time"], timeout=35, check=False)
            crash_lines = crash.splitlines()
            # Avoid uploading unrelated prior process crashes.
            crash_lines = [line[:1600] for line in crash_lines
                           if any(token in line for token in
                                  (PACKAGE, "KataGo", "Abort message", "backtrace",
                                   "signal 6", "SIGABRT", "DEBUG", "#00", "#01",
                                   "#02", "#03", "#04", "#05", "#06", "#07",
                                   "#08", "#09", "#10", "#11", "pid:", "tid:",
                                   "Build fingerprint", "ABI:"))]
            (folder / "native_crash_diagnostics.txt").write_text(
                "\\n".join(crash_lines[-160:]) + "\\n", encoding="utf-8")
            print("Native crash frames captured:", len(crash_lines), flush=True)
        if args.diagnose_compiler:
            # Only compiler-related system messages, never a raw all-app log.
            # Vendor services log outside our app PID/tag: previous captures
            # omitted the Neuron target report and hid the unsupported op.
            raw = run_cmd([args.adb, "-s", serial, "logcat", "-d", "-b", "all", "-v", "time"],
                          timeout=40, check=False)
            pattern = re.compile(r"(?i)(neuron|neuropilot|apusys|mediatek.*compil|"
                                 r"litert.*compil|compiler_plugin|target report|"
                                 r"executionplan|unsupported op|failed to compile model|"
                                 r"subgraph legalization|model compilation)")
            records = [line[:1200] for line in raw.splitlines() if pattern.search(line)]
            (folder / "neuron_compile_diagnostics.txt").write_text(
                "\n".join(records[-2500:]) + "\n", encoding="utf-8")
            print("Neuron compiler messages collected:", len(records),
                  "file:", folder / "neuron_compile_diagnostics.txt", flush=True)
        result = parse_game_log(log, run_id, sizes, not args.no_human_sl, args.require_human_npu)
        result["errors"] = report["errors"] + result["errors"]
        if result["errors"]: result["status"] = "FAIL"
        report.update(result)
        report["performanceSummary"] = [{"board": step["board"],
            "inferenceTiming": step.get("inferenceTiming", {}),
            "phaseTimingMs": step.get("phaseTimingMs", {})} for step in result["steps"]]
        for row in report["performanceSummary"]:
            print("BOARD_PERF board=%s 10b=%s human=%s" % (
                row["board"],row["inferenceTiming"].get("10b",{}).get("meanMs"),
                row["inferenceTiming"].get("human",{}).get("meanMs")), flush=True)
        code = 0 if report["status"] == "PASS" else 1
    except (OSError, subprocess.TimeoutExpired, RuntimeError) as exc:
        report["errors"].append(str(exc))
        print("FAIL:", exc, file=sys.stderr)
    finally:
        if serial and previous_neuron_props:
            for name, old in previous_neuron_props.items():
                try:
                    # If property had no previous override, reset to false
                    # rather than keeping debug logging enabled after test.
                    run_cmd([args.adb, "-s", serial, "shell", "setprop", name,
                             old if old else "false"], check=False)
                except (OSError, RuntimeError, subprocess.TimeoutExpired):
                    pass
        (folder / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n",
                                            encoding="utf-8")
        print("MediaTek Human SL game status:", report["status"],
              "steps:", len([s for s in report["steps"] if s["status"] == "PASS"]),
              "/", len(sizes), flush=True)
        print("Report:", folder / "report.json", flush=True)
        if not args.no_upload:
            try:
                url = upload_referee_results(folder, report, run_kind="mediatek_game")
                print("Uploaded:", url, flush=True)
            except (UploadError, OSError, RuntimeError) as exc:
                print("Upload failed, local results retained:", exc, file=sys.stderr)
                code = 1
    return code


if __name__ == "__main__":
    sys.exit(main())
