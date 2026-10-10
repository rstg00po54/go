#!/usr/bin/env python3
"""Measure real KataGo GTP genmove latency on Android, without changing the APK.

Run with: python3 tools/benchmark_katago_genmove_android.py
KataGo stays alive per backend/thread setting. Model startup and one warm-up
move are excluded. Fixed opening positions and identical limits are used.
"""
import argparse
import csv
import os
import re
import select
import shutil
import statistics
import subprocess
import sys
import time
from collections import defaultdict
from datetime import datetime
from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent
REMOTE = "/data/local/tmp/katago_bench"
OPENINGS = ["D4", "Q16", "D16", "Q4", "K10", "K4", "D10", "Q10",
            "G7", "N13", "C3", "R17", "K16", "K7", "E11", "P8", "F17", "O3"]
MOVE_FIELDS = ["backend", "threads", "repeat", "position", "plies", "move", "latency_ms", "root_visits"]
SUMMARY_FIELDS = ["backend", "threads", "moves", "mean_ms", "median_ms", "p90_ms", "min_ms", "max_ms", "mean_visits", "at_visit_cap_pct"]


def run(args, capture=False):
    command = [str(v) for v in args]
    result = subprocess.run(command, stdout=subprocess.PIPE if capture else None,
                            stderr=subprocess.PIPE if capture else None, text=True)
    if result.returncode:
        detail = (result.stderr or result.stdout or "").strip() if capture else ""
        raise RuntimeError("Command failed (%d): %s\n%s" % (result.returncode, " ".join(command), detail))
    return result.stdout if capture else ""


def device_serial(adb, serial):
    if serial:
        return serial
    output = run([adb, "devices"], capture=True)
    devices = [parts[0] for line in output.splitlines()
               if len(parts := line.split()) == 2 and parts[1] == "device"]
    if len(devices) != 1:
        raise RuntimeError("Connect exactly one authorized Android device, or set ANDROID_SERIAL.")
    return devices[0]


def percentile(values, percent):
    data = sorted(values)
    index = (len(data) - 1) * percent
    left = int(index)
    fraction = index - left
    return data[left] * (1 - fraction) + data[min(left + 1, len(data) - 1)] * fraction


class GtpEngine:
    def __init__(self, adb, serial, backend, threads, visits, max_time, log_dir, timeout):
        overrides = ",".join((
            "numSearchThreads=%d" % threads,
            "maxVisits=%d" % visits,
            "maxTime=%.3f" % max_time,
            "ponderingEnabled=false",
            "allowResignation=false",
            "logAllGTPCommunication=false",
            "logSearchInfo=false",
            "logToStderr=false",
            "ogsChatToStderr=true",  # One compact 'MALKOVICH:Visits N' diagnostic per genmove.
        ))
        remote_cmd = ("cd %s && HOME=%s TMPDIR=%s LD_LIBRARY_PATH=%s:/vendor/lib64:/system/vendor/lib64 "
                      "%s/katago_%s gtp -model 10b.bin -config default_gtp.cfg -override-config %s" %
                      (REMOTE, REMOTE, REMOTE, REMOTE, REMOTE, backend, overrides))
        self.stderr_path = log_dir / ("%s_%d.stderr.log" % (backend, threads))
        self.stderr_file = self.stderr_path.open("wb")
        self.stderr_read_pos = 0
        self.process = subprocess.Popen([adb, "-s", serial, "shell", "-T", remote_cmd],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=self.stderr_file, bufsize=0)
        self.pending = b""
        self.request_id = 0
        self.timeout = timeout

    def command(self, command):
        self.request_id += 1
        number = self.request_id
        wire = ("%d %s\n" % (number, command)).encode("ascii")
        start = time.perf_counter()
        try:
            self.process.stdin.write(wire)
            self.process.stdin.flush()
        except (BrokenPipeError, OSError) as error:
            raise RuntimeError("Engine exited before %s: %s" % (command, error))
        deadline = start + self.timeout

        while True:
            marker = self.pending.find(b"\n\n")
            if marker >= 0:
                packet = self.pending[:marker].decode("utf-8", errors="replace").strip()
                self.pending = self.pending[marker + 2:]
                if not packet:
                    continue
                match = re.match(r"^([=?])\s*(\d+)(?:\s|$)", packet)
                if not match:
                    raise RuntimeError("Unexpected GTP response for %s: %r" % (command, packet[:250]))
                if int(match.group(2)) != number:
                    raise RuntimeError("Unexpected GTP id %s, expected %d: %r" % (match.group(2), number, packet[:250]))
                if match.group(1) == "?":
                    raise RuntimeError("KataGo rejected '%s': %s" % (command, packet))
                text = packet[match.end():].strip()
                return text, (time.perf_counter() - start) * 1000.0

            remaining = deadline - time.perf_counter()
            if remaining <= 0:
                raise TimeoutError("GTP command timed out after %.1fs: %s" % (self.timeout, command))
            ready, _, _ = select.select([self.process.stdout], [], [], remaining)
            if not ready:
                raise TimeoutError("GTP command timed out after %.1fs: %s" % (self.timeout, command))
            block = os.read(self.process.stdout.fileno(), 65536)
            if not block:
                raise RuntimeError("KataGo/ADB closed output while running %s (exit=%s)" %
                                   (command, self.process.poll()))
            self.pending += block.replace(b"\r\n", b"\n")

    def read_root_visits(self):
        # OGS chat diagnostic is written synchronously to stderr before GTP reply.
        with self.stderr_path.open("rb") as source:
            source.seek(self.stderr_read_pos)
            chunk = source.read()
            self.stderr_read_pos = source.tell()
        found = re.findall(rb"MALKOVICH:Visits\s+(\d+)", chunk)
        if not found:
            raise RuntimeError("No root visit count in KataGo stderr: %s" % self.stderr_path)
        return int(found[-1])

    def close(self):
        if self.process.poll() is None:
            self.timeout = min(self.timeout, 3.0)
            try:
                self.command("quit")
            except (OSError, RuntimeError, TimeoutError):
                pass
        try:
            self.process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait()
        self.stderr_file.close()


def summarize(report_dir, measurements, threads, backends, visit_cap):
    groups = defaultdict(list)
    visits = defaultdict(list)
    for row in measurements:
        key = (row["backend"], row["threads"])
        groups[key].append(row["latency_ms"])
        visits[key].append(row["root_visits"])
    summary = {}
    with (report_dir / "summary.csv").open("w", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=SUMMARY_FIELDS)
        writer.writeheader()
        for backend in backends:
            for thread in threads:
                values = groups.get((backend, thread), [])
                if not values:
                    continue
                row = dict(backend=backend, threads=thread, moves=len(values),
                           mean_ms=statistics.mean(values), median_ms=statistics.median(values),
                           p90_ms=percentile(values, 0.90), min_ms=min(values), max_ms=max(values),
                           mean_visits=statistics.mean(visits[(backend, thread)]),
                           at_visit_cap_pct=100 * sum(v >= visit_cap for v in visits[(backend, thread)]) / len(values))
                summary[(backend, thread)] = row
                writer.writerow({key: "%.2f" % value if isinstance(value, float) else value
                                 for key, value in row.items()})

    print("\n======== Real genmove latency (ms; lower is better) ========")
    print("%-7s %7s %7s %10s %10s %10s %10s %10s %10s" %
          ("Backend", "Threads", "Moves", "Mean", "Median", "P90", "Max", "Visits", "HitCap%"))
    for backend in backends:
        for thread in threads:
            row = summary.get((backend, thread))
            if row:
                print("%-7s %7d %7d %10.2f %10.2f %10.2f %10.2f %10.1f %9.1f%%" %
                      (backend.upper(), thread, row["moves"], row["mean_ms"],
                       row["median_ms"], row["p90_ms"], row["max_ms"], row["mean_visits"], row["at_visit_cap_pct"]))

    if "cpu" in backends and "gpu" in backends:
        print("\n======== GPU latency reduction vs CPU (same threads) ========")
        print("%-7s %12s %12s %15s" % ("Threads", "CPU mean", "GPU mean", "GPU reduction"))
        for thread in threads:
            cpu, gpu = summary.get(("cpu", thread)), summary.get(("gpu", thread))
            if cpu and gpu:
                reduction = 100 * (1 - gpu["mean_ms"] / cpu["mean_ms"])
                print("%-7d %12.2f %12.2f %14.1f%%" %
                      (thread, cpu["mean_ms"], gpu["mean_ms"], reduction))

    for backend in backends:
        choices = [summary[(backend, thread)] for thread in threads if (backend, thread) in summary]
        if choices:
            best = min(choices, key=lambda row: row["mean_ms"])
            print("Lowest %s mean: %d threads, %.2f ms" %
                  (backend.upper(), best["threads"], best["mean_ms"]))
    print("Per-move CSV: %s" % (report_dir / "moves.csv"))
    print("Summary CSV:  %s" % (report_dir / "summary.csv"))
    print("Engine stderr logs: %s" % report_dir)


def main():
    parser = argparse.ArgumentParser(description="Measure KataGo GTP genmove latency on Android")
    parser.add_argument("--mode", choices=["both", "cpu", "gpu"], default="both",
                        help="Backend order: CPU then GPU by default")
    parser.add_argument("--threads", default="16,24,32", help="Comma-separated search threads")
    parser.add_argument("--board", type=int, default=19, choices=[19],
                        help="Board size; current fixed opening suite is 19x19")
    parser.add_argument("--visits", type=int, default=20, help="Maximum visits per move")
    parser.add_argument("--max-time", type=float, default=0.4, help="Maximum seconds per move")
    parser.add_argument("--positions", type=int, default=10, help="Distinct fixed 19x19 positions (1-10)")
    parser.add_argument("--repeats", type=int, default=2, help="Repetitions of each position")
    parser.add_argument("--timeout", type=float, default=90.0, help="Timeout per GTP command")
    parser.add_argument("--adb", default=os.getenv("ADB") or shutil.which("adb"))
    parser.add_argument("--serial", default=os.getenv("ANDROID_SERIAL"))
    parser.add_argument("--report-dir", type=Path)
    args = parser.parse_args()
    try:
        threads = [int(item) for item in args.threads.split(",")]
        if not threads or len(set(threads)) != len(threads) or any(t < 1 or t > 256 for t in threads):
            parser.error("--threads must be unique integers from 1 to 256")
        if not (1 <= args.positions <= 10 and args.repeats >= 1 and args.visits >= 1 and
                0 < args.max_time <= 60 and args.timeout > 0):
            parser.error("Expected positions=1..10, repeats>=1, visits>=1, 0<max-time<=60, timeout>0")
        if not args.adb:
            parser.error("adb not found; use --adb or install platform-tools")
        serial = device_serial(args.adb, args.serial)
        backends = ["cpu", "gpu"] if args.mode == "both" else [args.mode]
        report_dir = args.report_dir or (PROJECT / "build" / "katago_genmove" /
                                         datetime.now().strftime("%Y%m%d_%H%M%S"))
        report_dir.mkdir(parents=True, exist_ok=True)

        cpu = PROJECT / "app/build/generated/katagoJniLibs/arm64-v8a/libkatago_exec.so"
        if not cpu.is_file():
            cpu = Path(os.getenv("KATAGO_WORKDIR", str(Path.home() / ".cache/goengine_katago"))) / "build_android_arm64_eigen/katago"
        gpu = PROJECT / "build/katago_android_arm64_opencl/libkatago_exec_opencl.so"
        binaries = {"cpu": cpu, "gpu": gpu}
        for backend in backends:
            if not binaries[backend].is_file():
                raise RuntimeError("Missing %s executable: %s" % (backend, binaries[backend]))
        model = PROJECT / "app/src/main/assets/engine/10b.bin"
        config = PROJECT / "app/src/main/assets/engine/default_gtp.cfg"
        if not model.is_file() or not config.is_file():
            raise RuntimeError("Missing 10b model or default_gtp.cfg")

        print("Device: %s | 19x19 | genmove maxVisits=%d maxTime=%.3fs" %
              (serial, args.visits, args.max_time), flush=True)
        print("Threads: %s | positions: %d | repeats: %d | backends: %s" %
              (threads, args.positions, args.repeats, backends), flush=True)
        print("Reports: %s" % report_dir, flush=True)
        run([args.adb, "-s", serial, "shell", "mkdir -p %s/.katago %s/gtp_logs" % (REMOTE, REMOTE)])
        for source, target in ((model, "10b.bin"), (config, "default_gtp.cfg")):
            run([args.adb, "-s", serial, "push", source, "%s/%s" % (REMOTE, target)])
        for backend in backends:
            run([args.adb, "-s", serial, "push", binaries[backend], "%s/katago_%s" % (REMOTE, backend)])
            run([args.adb, "-s", serial, "shell", "chmod 755 %s/katago_%s" % (REMOTE, backend)])
        if "gpu" in backends:
            # The Mali OpenCL vendor runtime declares SONAME libGLES_mali.so.
            run([args.adb, "-s", serial, "shell",
                 "cp /vendor/lib64/libOpenCL.so %s/libGLES_mali.so" % REMOTE])

        measurements = []
        with (report_dir / "moves.csv").open("w", newline="") as csv_file:
            writer = csv.DictWriter(csv_file, fieldnames=MOVE_FIELDS)
            writer.writeheader()
            for backend in backends:
                for thread in threads:
                    print("\n======== %s / %d search threads ========" % (backend.upper(), thread), flush=True)
                    engine = GtpEngine(args.adb, serial, backend, thread, args.visits,
                                       args.max_time, report_dir, args.timeout)
                    try:
                        engine.command("boardsize 19")
                        engine.command("komi 7.5")
                        engine.command("clear_board")
                        engine.command("genmove B")  # Warm GPU kernels, search threads, and model.
                        engine.read_root_visits()
                        for repeat in range(1, args.repeats + 1):
                            for position in range(args.positions):
                                engine.command("clear_board")
                                plies = position * 2
                                for idx, move in enumerate(OPENINGS[:plies]):
                                    color = "B" if idx % 2 == 0 else "W"
                                    engine.command("play %s %s" % (color, move))
                                engine.command("clear_cache")
                                move, latency_ms = engine.command("genmove B")
                                actual_visits = engine.read_root_visits()
                                if not re.fullmatch(r"(?:PASS|RESIGN|[A-HJ-T](?:[1-9]|1[0-9]))",
                                                    move, flags=re.IGNORECASE):
                                    raise RuntimeError("Unexpected genmove response: %r" % move)
                                row = dict(backend=backend, threads=thread, repeat=repeat,
                                           position=position+1, plies=plies, move=move,
                                           latency_ms=round(latency_ms, 3), root_visits=actual_visits)
                                measurements.append(row)
                                writer.writerow(row)
                                csv_file.flush()
                                print("  repeat %d position %02d/%02d: %7.1f ms, visits %d/%d, move %s" %
                                      (repeat, position+1, args.positions, latency_ms, actual_visits, args.visits, move), flush=True)
                    except (OSError, RuntimeError, TimeoutError):
                        print("Engine details: %s/%s_%d.stderr.log" %
                              (report_dir, backend, thread), file=sys.stderr)
                        raise
                    finally:
                        engine.close()
            summarize(report_dir, measurements, threads, backends, args.visits)
    except (OSError, RuntimeError, TimeoutError, subprocess.SubprocessError) as error:
        print("ERROR: %s" % error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
