#!/usr/bin/env python3
"""Watch RK3588 native crashes and immediately show C++ function + file:line.

Unlike plain 'adb logcat -b crash', this host-side watcher can read DWARF from
the exact locally-built libkatago_npu.so. The installed APK is not modified.

  python3 tools/watch_npu_crash.py --serial 8719e18a71a2a66c
  python3 tools/watch_npu_crash.py --serial 8719e18a71a2a66c --include-existing --once

Leave this running in a separate Ubuntu terminal while using the Android app.
"""
import argparse
from datetime import datetime, timezone
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

PROJECT = Path(__file__).resolve().parents[1]
SYMBOLIZER = Path(__file__).with_name("symbolize_npu_crash.sh")
FATAL = re.compile(r"^.*\bFatal signal\s+\d+\b.*$", re.MULTILINE)
APP = "com.badukai.java"


def latest_crash(raw):
    """Return identity line and crash text; preserve actual DEBUG frames."""
    matches = list(FATAL.finditer(raw))
    if not matches:
        return None, ""
    match = matches[-1]
    return match.group(0), raw[match.start():]


def has_symbolizable_npu_frames(text):
    return bool(re.search(r"#\d+\s+pc\s+[0-9a-fA-F]+\s+.*libkatago_npu\.so", text))


def crash_log(adb, serial):
    proc = subprocess.run([adb, "-s", serial, "logcat", "-b", "crash", "-d", "-v", "threadtime"],
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=18)
    if proc.returncode:
        raise RuntimeError("adb logcat failed: " + proc.stderr.strip()[-500:])
    return proc.stdout


def symbolize(serial, crash, outdir, symbolizer=SYMBOLIZER):
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    pid = re.search(r"\bpid[:=]\s*(\d+)", crash)
    destination = outdir / (stamp + "_pid" + (pid.group(1) if pid else "unknown"))
    destination.mkdir(parents=True, exist_ok=False)
    crash_path = destination / "crash.txt"
    crash_path.write_text(crash, encoding="utf-8")
    try:
        proc = subprocess.run(["bash", str(symbolizer), serial, str(crash_path)],
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True, timeout=35)
        result = proc.stdout
        status = proc.returncode
    except (OSError, subprocess.TimeoutExpired) as exc:
        result = "Symbolizer error: " + str(exc) + "\n"
        status = 1
    (destination / "native_symbols.txt").write_text(result, encoding="utf-8")
    return status, result, destination


def run(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL", "8719e18a71a2a66c"))
    parser.add_argument("--adb", default=shutil.which("adb"))
    parser.add_argument("--outdir", type=Path, default=PROJECT / "build/npu_crashes")
    parser.add_argument("--interval", type=float, default=1.0)
    parser.add_argument("--include-existing", action="store_true", help="Also symbolize the latest buffered crash")
    parser.add_argument("--once", action="store_true", help="Stop after the next crash is reported")
    args = parser.parse_args(argv)
    if not args.adb: parser.error("adb executable not found")
    if args.interval < 0.2: parser.error("--interval must be >= 0.2 seconds")
    args.outdir.mkdir(parents=True, exist_ok=True)
    try:
        initial_key, _ = latest_crash(crash_log(args.adb, args.serial))
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as exc:
        parser.error(str(exc))
    last_processed = None if args.include_existing else initial_key
    pending_key, pending_since = None, None
    print("Watching RK3588 native NPU crashes on %s (Ctrl+C to stop)" % args.serial, flush=True)
    print("Crash output: %s; requires the matching local unstripped .so" % args.outdir, flush=True)
    if initial_key and not args.include_existing:
        print("Existing crash log skipped; use --include-existing to decode it", flush=True)
    try:
        while True:
            try:
                key, text = latest_crash(crash_log(args.adb, args.serial))
            except (OSError, RuntimeError, subprocess.TimeoutExpired) as exc:
                print("ADB read error: %s" % exc, file=sys.stderr, flush=True)
                time.sleep(max(2.0, args.interval))
                continue
            if not key or key == last_processed:
                pending_key, pending_since = None, None
                time.sleep(args.interval)
                continue
            if key != pending_key:
                pending_key, pending_since = key, time.monotonic()
            # Android's debuggerd prints the fatal line before its backtrace.
            # Wait for the tombstone to settle to avoid saving partial frames.
            if time.monotonic() - pending_since < 2.0 or not has_symbolizable_npu_frames(text):
                if time.monotonic() - pending_since > 12.0:
                    last_processed = key
                    if APP in text:
                        print("Crash detected but no libkatago_npu.so frame found: " + key, flush=True)
                time.sleep(args.interval)
                continue
            print("\n" + "=" * 65, flush=True)
            print("NATIVE CRASH: " + key, flush=True)
            status, result, folder = symbolize(args.serial, text, args.outdir)
            print(result, end="" if result.endswith("\n") else "\n", flush=True)
            print("Saved: %s  (symbolizer exit=%d)" % (folder, status), flush=True)
            print("=" * 65, flush=True)
            last_processed = key
            if args.once: return 0 if status == 0 else 1
            time.sleep(args.interval)
    except KeyboardInterrupt:
        print("\nCrash watcher stopped", flush=True)
        return 0


if __name__ == "__main__":
    sys.exit(run())
