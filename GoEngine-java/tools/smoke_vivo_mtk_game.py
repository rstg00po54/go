#!/usr/bin/env python3
"""Test actual KataGoEngine UI backend orchestration on MediaTek Android.

Runs the debug-only NpuSmoke intent, loading Human SL and size-specific
MediaTek LiteRT for 9,13,19,9. Asserts every step used MediaTek and returned
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
DEFAULT_SIZES = (9, 13, 19, 9)


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


def parse_game_log(raw, run_id, sizes, human=True):
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
    if len(indexes) != len(sizes): errors.append("Expected %d steps; saw %d" % (len(sizes), len(indexes)))
    for index, begin in enumerate(indexes):
        requested = sizes[index] if index < len(sizes) else None
        part = lines[begin: indexes[index+1] if index+1 < len(indexes) else len(lines)]
        begin_line = part[0]
        prepared = [line for line in part if "MTK_LITERT_INIT board=" in line]
        ready = [line for line in part if "MTK_GAME_BACKEND_READY board=" in line]
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
        if human and ready and field(ready[0], "humanSL") != "true":
            part_errors.append("Human SL was not active")
        if len(terminal) != 1 or field(terminal[0], "active") != str(requested):
            part_errors.append("No matching STEP_PASS/active board")
        if fallback: part_errors.append("NPU fallback observed")
        if terminal:
            for color in ("black", "white"):
                if not vertex(field(terminal[0], color), requested):
                    part_errors.append("Illegal/missing " + color + " move")
            for name in ("startupMs", "blackMs", "whiteMs"):
                value = field(terminal[0], name)
                if value is None or not value.isdigit(): part_errors.append("Invalid " + name)
        item = {"index": index, "board": requested,
                "status": "PASS" if not part_errors else "FAIL", "errors": part_errors,
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


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--adb", default=shutil.which("adb"))
    p.add_argument("--serial")
    p.add_argument("--skip-install", action="store_true")
    p.add_argument("--no-human-sl", action="store_true")
    p.add_argument("--no-upload", action="store_true")
    p.add_argument("--timeout", type=int, default=480)
    args = p.parse_args(argv)
    if not args.adb: p.error("adb not installed")
    run_id = uuid4().hex[:16]
    folder = PROJECT / "build/vivo_mtk_game_smoke" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    folder.mkdir(parents=True, exist_ok=False)
    report = {"test": "vivo MediaTek KataGo real game backend with Human SL", "run_id": run_id,
              "sizes": list(DEFAULT_SIZES), "humanSL": not args.no_human_sl,
              "hardwareExecution": "UNVERIFIED", "status": "FAIL", "errors": [], "steps": []}
    code, serial = 1, None
    try:
        serial = connected(args.adb, args.serial)
        report["serial"] = serial
        soc = run_cmd([args.adb, "-s", serial, "shell", "getprop", "ro.soc.model"]).strip()
        if not re.search(r"MT6990|MT6993|mtk|mediatek", soc, re.IGNORECASE):
            raise RuntimeError("MediaTek phone required (SoC=" + soc + ")")
        if not args.skip_install:
            if not APK.is_file(): raise RuntimeError("Build experimental APK first")
            print(run_cmd([args.adb, "-s", serial, "install", "-r", str(APK)], timeout=150).strip())
        run_cmd([args.adb, "-s", serial, "shell", "am", "force-stop", PACKAGE])
        run_cmd([args.adb, "-s", serial, "logcat", "-b", "all", "-c"])
        print(run_cmd([args.adb, "-s", serial, "shell", "am", "start", "-n", ACTIVITY,
                       "--ez", "npu_switch_smoke", "true",
                       "--es", "npu_smoke_id", run_id,
                       "--es", "npu_smoke_sizes", ",".join(map(str, DEFAULT_SIZES)),
                       "--ez", "npu_smoke_human", "false" if args.no_human_sl else "true"]).strip())
        end = time.monotonic() + args.timeout
        tags = ["NpuSmoke:V", "KataGoMTK:V", "KataGoEngine:V", "AndroidRuntime:E", "libc:F"]
        while time.monotonic() < end:
            logs = run_cmd([args.adb, "-s", serial, "logcat", "-d", "-v", "time", "-s", *tags],
                           timeout=30, check=False)
            if re.search(r"\b(?:PASS|FAIL) id=" + re.escape(run_id) + r"(?:\s|$)", logs):
                break
            if "Fatal signal" in logs or "FATAL EXCEPTION" in logs: break
            time.sleep(2)
        else:
            report["errors"].append("Timed out waiting for Human SL NPU game regression")
        log = run_cmd([args.adb, "-s", serial, "logcat", "-d", "-v", "time", "-s", *tags],
                      timeout=30, check=False)
        (folder / "logcat.txt").write_text(log, encoding="utf-8")
        result = parse_game_log(log, run_id, DEFAULT_SIZES, not args.no_human_sl)
        result["errors"] = report["errors"] + result["errors"]
        if result["errors"]: result["status"] = "FAIL"
        report.update(result)
        code = 0 if report["status"] == "PASS" else 1
    except (OSError, subprocess.TimeoutExpired, RuntimeError) as exc:
        report["errors"].append(str(exc))
        print("FAIL:", exc, file=sys.stderr)
    finally:
        (folder / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n",
                                            encoding="utf-8")
        print("MediaTek Human SL game status:", report["status"],
              "steps:", len([s for s in report["steps"] if s["status"] == "PASS"]),
              "/", len(DEFAULT_SIZES), flush=True)
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
