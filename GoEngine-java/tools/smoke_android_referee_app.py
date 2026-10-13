#!/usr/bin/env python3
"""One-command Android DEBUG APK end-to-end smart-referee smoke.

Replays bundled real SGF cgos9_expand_006_12 inside MainActivity, drives its
actual KataGo engine and real referee dialog, and verifies logcat + dialog UI.
No taps, Human SL download, root, or test-framework APK required.
"""
import argparse
from datetime import datetime, timezone
import json
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path
from uuid import uuid4
import xml.etree.ElementTree as ET

PROJECT = Path(__file__).resolve().parents[1]
PACKAGE = "com.badukai.java"  # Gradle applicationId, not Java source package.
MAIN_ACTIVITY_CLASS = "com.badukai.MainActivity"  # Manifest activity in namespace com.badukai.
ACTIVITY = PACKAGE + "/" + MAIN_ACTIVITY_CLASS


def run(argv, timeout=30, check=True):
    completed = subprocess.run(argv, text=True, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, timeout=timeout)
    if check and completed.returncode:
        raise RuntimeError("Command failed (%d): %s\n%s" %
                           (completed.returncode, " ".join(map(str, argv)), completed.stdout[-1800:]))
    return completed.stdout


def connected_serial(adb, requested):
    lines = run([adb, "devices"]).splitlines()[1:]
    devices = [line.split()[0] for line in lines if line.split()[1:2] == ["device"]]
    if requested:
        if requested not in devices:
            raise RuntimeError("ADB device %s not ready; connected: %s" % (requested, devices))
        return requested
    if len(devices) != 1:
        raise RuntimeError("Provide --serial; detected devices: %s" % devices)
    return devices[0]


def smoke_event(log, run_id):
    """Find the terminal event for THIS run, never stale prior log entries."""
    final = None
    for line in log.splitlines():
        match = re.search(r"\b(PASS|FAIL) id=" + re.escape(run_id) + r"\b", line)
        if "RefereeSmoke" in line and match:
            final = {"status": match.group(1), "line": line.strip()}
    return final


def read_dialog_xml(adb, serial):
    target = "/sdcard/referee_smoke_window.xml"
    run([adb, "-s", serial, "shell", "uiautomator", "dump", target], timeout=35)
    xml = run([adb, "-s", serial, "shell", "cat", target], timeout=15)
    root = ET.fromstring(xml[xml.find("<?xml"):])
    return " ".join(node.attrib.get("text", "") for node in root.iter())


def validate_dialog(text):
    required = ("智能裁判", "黑先：", "白先：", "搜索胜率", "不同死子组合", "黄圈")
    return [item for item in required if item not in text]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="ADB device ID (required if multiple devices connected)")
    parser.add_argument("--adb", default=shutil.which("adb"), help="ADB executable")
    parser.add_argument("--apk", type=Path, default=PROJECT / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--backend", choices=("cpu", "gpu"), default="cpu",
                        help="Actual App backend; gpu requires an APK built with GPU JNI")
    parser.add_argument("--build", action="store_true", help="Build assembleDebug before installing")
    parser.add_argument("--skip-install", action="store_true", help="Reuse installed DEBUG app")
    parser.add_argument("--skip-ui", action="store_true", help="Skip hierarchy check on locked/headless screens")
    parser.add_argument("--timeout", type=float, default=240, help="Time allowed for engine/SGF/referee")
    args = parser.parse_args()
    if not args.adb:
        parser.error("adb not found; install Android platform-tools or supply --adb")
    if args.timeout < 15:
        parser.error("--timeout must be >= 15 seconds")
    out_dir = PROJECT / "build/android_referee_smoke" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    out_dir.mkdir(parents=True, exist_ok=True)
    report = {"status": "ERROR", "backend_requested": args.backend, "outputs": str(out_dir)}
    try:
        serial = connected_serial(args.adb, args.serial)
        report["serial"] = serial
        if args.build:
            print("Building DEBUG APK...", flush=True)
            gradle = [str(PROJECT / "gradlew"), "assembleDebug"]
            if args.backend == "gpu":
                gradle.append("-PenableKataGoGpuJni=true")
            proc = subprocess.run(gradle, cwd=PROJECT, text=True, stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT)
            (out_dir / "gradle.log").write_text(proc.stdout, encoding="utf-8")
            if proc.returncode:
                raise RuntimeError("Gradle failed; see %s" % (out_dir / "gradle.log"))
        if not args.skip_install:
            if not args.apk.is_file():
                raise RuntimeError("Missing DEBUG APK: %s (run ./gradlew assembleDebug)" % args.apk)
            print("Installing:", args.apk, flush=True)
            print(run([args.adb, "-s", serial, "install", "-r", "-t", str(args.apk)],
                      timeout=120).strip(), flush=True)

        run_id = uuid4().hex[:16]
        report["run_id"] = run_id
        print("Starting automated real-SGF referee on %s (%s)..." % (serial, args.backend), flush=True)
        run([args.adb, "-s", serial, "shell", "am", "force-stop", PACKAGE])
        start = run([args.adb, "-s", serial, "shell", "am", "start", "-W", "-n", ACTIVITY,
                     "--ez", "referee_smoke", "true", "--es", "referee_smoke_id", run_id,
                     "--es", "referee_smoke_backend", args.backend], timeout=35)
        if "Error:" in start or "Exception" in start:
            raise RuntimeError("Failed to start Activity: " + start)
        deadline = time.monotonic() + args.timeout
        terminal = None
        log = ""
        while time.monotonic() < deadline:
            # The unique ID prevents an old logcat PASS from passing this run.
            log = run([args.adb, "-s", serial, "logcat", "-d", "-v", "brief",
                       "-s", "RefereeSmoke:I", "AndroidRuntime:E", "*:S"], timeout=20)
            terminal = smoke_event(log, run_id)
            if terminal:
                break
            time.sleep(2)
        (out_dir / "logcat.txt").write_text(log, encoding="utf-8")
        if not terminal:
            raise RuntimeError("Timed out waiting for RefereeSmoke PASS/FAIL (see logcat.txt); "
                               "check engine initialization, GPU library, model, and activity.")
        report["app_result"] = terminal
        print(terminal["line"], flush=True)
        if terminal["status"] != "PASS":
            raise RuntimeError("Android referee returned FAIL; inspect %s/logcat.txt" % out_dir)
        if not args.skip_ui:
            dialog_text = read_dialog_xml(args.adb, serial)
            (out_dir / "dialog.txt").write_text(dialog_text + "\n", encoding="utf-8")
            missing = validate_dialog(dialog_text)
            if missing:
                raise RuntimeError("Dialog missing %s (screen locked/covered or actual UI regression); "
                                   "see dialog.txt. Use --skip-ui for headless testing." % missing)
            print("Dialog UI PASS: title, two-sided search, winrate, score what-if and review hint.", flush=True)
        else:
            print("Skipped UI hierarchy; Android engine/referee/dialog assertions passed.", flush=True)
        report["status"] = "PASS"
        return 0
    except (OSError, RuntimeError, subprocess.TimeoutExpired, ET.ParseError) as exc:
        report["error"] = str(exc)
        print("FAIL:", exc, file=sys.stderr, flush=True)
        return 1
    finally:
        (out_dir / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                                             encoding="utf-8")
        print("Report:", out_dir / "report.json", flush=True)


if __name__ == "__main__":
    sys.exit(main())
