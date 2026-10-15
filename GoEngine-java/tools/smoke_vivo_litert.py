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


def run(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--adb", default=shutil.which("adb"))
    p.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    p.add_argument("--size", type=int, choices=(9, 13, 19), default=9)
    p.add_argument("--timeout", type=int, default=210)
    p.add_argument("--skip-install", action="store_true")
    p.add_argument("--no-upload", action="store_true")
    args = p.parse_args(argv)
    if not args.adb: p.error("adb executable not found")
    serial = detect(args)
    folder = ROOT / "build/vivo_litert_smoke" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    folder.mkdir(parents=True)
    ident = uuid4().hex[:16]
    report = {"test": "Vivo MediaTek KataGo 10b LiteRT graph",
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
                  "--ez", "mtk_litert_smoke", "true", "--es", "mtk_smoke_id", ident,
                  "--ei", "mtk_board_size", str(args.size)).strip(), flush=True)
        end = time.monotonic() + args.timeout
        events = []
        while time.monotonic() < end:
            txt = adb(args, serial, "logcat", "-d", "-v", "time", "-s",
                      "KataGoMTK:V", "AndroidRuntime:E", "libc:F", timeout=20)
            events = [l for l in txt.splitlines() if "KataGoMTK" in l]
            if any("MTK_SMOKE_PASS id=" + ident in x or "MTK_SMOKE_FAIL id=" + ident in x for x in events):
                break
            if "Fatal signal" in txt or "FATAL EXCEPTION" in txt:
                raise RuntimeError("App crashed while probing LiteRT")
            time.sleep(2)
        else:
            raise RuntimeError("Vivo LiteRT smoke timed out (%ds)" % args.timeout)
        (folder / "logcat.txt").write_text(txt, encoding="utf-8")
        ok = any("MTK_SMOKE_PASS id=" + ident in x for x in events)
        failures = [x for x in events if "MTK_SMOKE_FAIL id=" + ident in x]
        inferences = [x for x in events if "MTK_LITERT_INFER board=" in x]
        report.update(status="PROBE_PASS" if ok and len(inferences) >= 3 and not failures else "FAIL",
                      modelInferenceCount=len(inferences), errors=failures)
        if report["status"] != "PROBE_PASS": raise RuntimeError("LiteRT model inference probe failed")
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
            # This is observational evidence, not a certificate of real NPU use.
            vendor = [line for line in relevant if re.search(
                r"Neuron|dispatch|compiler.plugin|delegate|accelerat", line, re.IGNORECASE)]
            (folder / "vendor_litert.txt").write_text("\n".join(vendor) + "\n", encoding="utf-8")
            report["vendorLogLineCount"] = len(vendor)
            report["fallbackWarnings"] = [line.strip()[-220:] for line in vendor
                if re.search(r"fallback|CPU.instead|No.compiler.plugin|Failed.to.apply", line, re.IGNORECASE)][-12:]
            crash = adb(args, serial, "logcat", "-b", "crash", "-d", "-v", "brief")
            if crash.strip(): (folder / "crash.txt").write_text(crash, encoding="utf-8")
        except Exception as err:
            report["errors"].append("Failed to save diagnostics: " + str(err))
        (folder / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
        print("Status:", report["status"], "(real NPU execution unverified)", flush=True)
        print("Report:", folder / "report.json", flush=True)
        if not args.no_upload:
            try:
                url = upload_referee_results(folder, report, run_kind="mediatek")
                print("Uploaded:", url, flush=True)
            except Exception as err:
                print("GitHub upload failed; local report preserved:", err, file=sys.stderr)
    return result


if __name__ == "__main__":
    sys.exit(run())
