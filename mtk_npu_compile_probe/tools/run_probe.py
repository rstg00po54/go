#!/usr/bin/env python3
"""Compile TFLite models independently of KataGo on a MediaTek Android phone.

Example:
 python3 tools/run_probe.py --model 10b=../GoEngine-java/app/src/main/assets/engine/katago_10b_9x9.tflite --model human=../GoEngine-java/app/src/main/assets/engine/katago_human_9x9.tflite --accelerators CPU,NPU
"""
from __future__ import annotations
import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid

PACKAGE = "com.example.mtkprobe"
ACTIVITY = PACKAGE + "/.ProbeActivity"
ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"


def adb(serial, *args, timeout=40, check=True):
    argv = ["adb", *(["-s", serial] if serial else []), *args]
    p = subprocess.run(argv, text=True, stdout=subprocess.PIPE,
                       stderr=subprocess.STDOUT, timeout=timeout)
    if check and p.returncode:
        raise RuntimeError("Command failed: " + repr(argv) + "\n" + p.stdout[-1500:])
    return p.stdout


def connected(serial):
    listing = adb(None, "devices")
    ready = [line.split()[0] for line in listing.splitlines()[1:]
             if len(line.split()) >= 2 and line.split()[1] == "device"]
    if serial:
        if serial not in ready:
            raise RuntimeError("ADB device not connected: " + serial + " found=" + repr(ready))
        return serial
    if len(ready) != 1:
        raise RuntimeError("Specify --serial; connected: " + repr(ready))
    return ready[0]


def model_arg(value):
    if "=" not in value:
        raise argparse.ArgumentTypeError("--model expects NAME=PATH")
    label, filename = value.split("=", 1)
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,90}", label) or ".." in label:
        raise argparse.ArgumentTypeError("Invalid short model name")
    path = Path(filename).expanduser().resolve()
    if path.suffix != ".tflite" or not path.is_file():
        raise argparse.ArgumentTypeError("Missing .tflite model: " + str(path))
    return label + ".tflite", path


def copy_model(serial, name, path):
    adb(serial, "shell", "run-as", PACKAGE, "mkdir", "-p", "files/models")
    command = ["adb", "-s", serial, "exec-out", "run-as", PACKAGE,
               "sh", "-c", "cat > files/models/" + name]
    with path.open("rb") as source:
        p = subprocess.Popen(command, stdin=source, stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE)
        try:
            stdout, stderr = p.communicate(timeout=180)
        except subprocess.TimeoutExpired:
            p.kill()
            p.communicate()
            raise RuntimeError("Model transfer timeout")
    if p.returncode:
        raise RuntimeError("Model transfer failed: " + (stdout + stderr).decode(errors="replace")[-1500:])
    actual = adb(serial, "shell", "run-as", PACKAGE, "stat", "-c", "%s",
                 "files/models/" + name).strip()
    if not actual.isdecimal() or int(actual) != path.stat().st_size:
        raise RuntimeError("Model transfer size mismatch, phone=" + actual)


def collect_logs(serial, run_id):
    logs = adb(serial, "logcat", "-d", "-v", "brief", "MTKPROBE:I", "*:S",
               timeout=45, check=False).splitlines()
    return [line for line in logs if "id=" + run_id + " " in line]


def diagnose(serial):
    logs = adb(serial, "logcat", "-d", "-v", "time", "-b", "all",
               timeout=50, check=False).splitlines()
    rx = re.compile(r"MTKPROBE|Neuron|PrepareTensors|APUSYS|MDLA|LiteRT|FATAL EXCEPTION|Fatal signal", re.I)
    return "\n".join(line for line in logs if rx.search(line))[-24000:]


def run_one(serial, model, accelerator, mode, timeout):
    run_id = "p" + uuid.uuid4().hex[:14]
    adb(serial, "shell", "am", "force-stop", PACKAGE)
    start_output = adb(serial, "shell", "am", "start", "-W", "-n", ACTIVITY,
                       "--es", "run_id", run_id, "--es", "model", model,
                       "--es", "accelerator", accelerator, "--es", "mode", mode)
    if "Error" in start_output or "Exception" in start_output:
        raise RuntimeError("Activity startup failed: " + start_output)
    start = time.monotonic()
    terminal = None
    lines = []
    while time.monotonic() - start < timeout:
        lines = collect_logs(serial, run_id)
        terminal = next((line for line in lines if "event=PASS " in line or "event=FAIL " in line), None)
        if terminal:
            break
        time.sleep(1.5)
    status = ("PASS" if terminal and "event=PASS " in terminal
              else "FAIL" if terminal else "TIMEOUT_OR_CRASH")
    record = {
        "id": run_id, "model": model, "accelerator": accelerator,
        "mode": mode, "status": status, "elapsedSeconds": round(time.monotonic() - start, 2),
        "events": lines, "terminal": terminal,
        "vendorLogs": diagnose(serial) if status != "PASS" else "",
    }
    adb(serial, "shell", "am", "force-stop", PACKAGE, check=False)
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="ADB serial; required when multiple devices are connected")
    parser.add_argument("--model", type=model_arg, action="append", required=True)
    parser.add_argument("--accelerators", default="NPU")
    parser.add_argument("--mode", choices=["COMPILE", "BUFFERS"], default="BUFFERS")
    parser.add_argument("--timeout", type=int, default=240)
    parser.add_argument("--skip-install", action="store_true")
    parser.add_argument("--apk", type=Path, default=APK)
    parser.add_argument("--out", type=Path, default=ROOT / "reports")
    args = parser.parse_args()
    accelerators = [s.strip().upper() for s in args.accelerators.split(",")]
    if args.timeout < 10 or not accelerators or any(a not in ("CPU", "NPU") for a in accelerators):
        parser.error("--timeout >=10 and --accelerators CPU,NPU or NPU")
    serial = connected(args.serial)
    if not args.skip_install:
        if not args.apk.is_file():
            parser.error("Build the APK first: " + str(args.apk))
        print(adb(serial, "install", "-r", str(args.apk), timeout=120))
    print("Device", serial, "SoC", adb(serial, "shell", "getprop", "ro.soc.model").strip())
    args.out.mkdir(parents=True, exist_ok=True)
    ok = True
    for model, path in args.model:
        print("Copying", model, path, flush=True)
        copy_model(serial, model, path)
        sha = hashlib.sha256()
        with path.open("rb") as stream:
            for block in iter(lambda: stream.read(1048576), b""):
                sha.update(block)
        for accelerator in accelerators:
            print("Testing", model, accelerator, flush=True)
            report = run_one(serial, model, accelerator, args.mode, args.timeout)
            report["modelSha256"] = sha.hexdigest()
            report["device"] = serial
            stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            filename = args.out / (stamp + "_" + model + "_" + accelerator + "_" + report["id"] + ".json")
            filename.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
            print(report["status"], "report:", filename, flush=True)
            if report["terminal"]:
                print(report["terminal"][-1000:], flush=True)
            ok &= report["status"] == "PASS"
    return 0 if ok else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
        print("ERROR:", error, file=sys.stderr)
        sys.exit(2)
