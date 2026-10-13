#!/usr/bin/env python3
"""Focused one-move KataGo NN probes on recorded SGFs (not life/death proof).

For each candidate point, reload the original SGF, optionally undo its final
two passes, play either color at that point, and compare KataGo raw NN output.
Each probe is independent. No original SGF, game app or board is modified.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import sys

from benchmark_endgame_referee_android import (
    PROJECT, GtpEngine, collect_games, device_serial, ownership_from_raw,
    parse_nn_metrics, prepare_device, prepare_real_games, check_engine_position,
)

GTP_COLUMNS = "ABCDEFGHJKLMNOPQRSTUVWXYZ"


def parse_vertices(text, size):
    result = []
    for item in text.split(","):
        vertex = item.strip().upper()
        if not vertex:
            continue
        if len(vertex) < 2 or vertex[0] not in GTP_COLUMNS[:size]:
            raise ValueError("Invalid GTP coordinate: " + vertex)
        try:
            row = int(vertex[1:])
        except ValueError:
            raise ValueError("Invalid GTP coordinate: " + vertex)
        if not 1 <= row <= size:
            raise ValueError("Out-of-board GTP coordinate: " + vertex)
        if vertex not in result:
            result.append(vertex)
    return result


def nn_ownership_at(ownership, vertex, size):
    col = GTP_COLUMNS.index(vertex[0])
    row = size - int(vertex[1:])
    return round(ownership[row * size + col], 4)


def restore_probe_position(engine, case):
    engine.command("loadsgf " + case["remote"])
    # On a two-pass finish, enter the same playable board as before both passes.
    # Do not replay alternative moves into a finished GTP position.
    if case["terminal_passes"]:
        engine.command("undo")
        engine.command("undo")


def run_probes(engine, case, candidates, watched):
    check_engine_position(engine, case)
    restore_probe_position(engine, case)
    raw, baseline_ms = engine.command("kata-raw-nn 0")
    initial_lead, _, _ = parse_nn_metrics(raw)
    initial_ownership = ownership_from_raw(raw, case["size"])
    baseline = {"white_lead": round(initial_lead, 3), "nn_ms": round(baseline_ms, 3),
                "watch_white_ownership": {
                    v: nn_ownership_at(initial_ownership, v, case["size"]) for v in watched}}
    rows = []
    for vertex in candidates:
        for color in ("black", "white"):
            restore_probe_position(engine, case)
            row = {"move": vertex, "color": color}
            try:
                engine.command("play %s %s" % (color, vertex))
            except RuntimeError as exc:
                row["status"] = "illegal_or_rejected"
                row["message"] = str(exc)[:300]
                rows.append(row)
                continue
            result, nn_ms = engine.command("kata-raw-nn 0")
            lead, _, _ = parse_nn_metrics(result)
            ownership = ownership_from_raw(result, case["size"])
            row.update(status="ok", white_lead=round(lead, 3),
                       delta_white_lead=round(lead - initial_lead, 3),
                       nn_ms=round(nn_ms, 3),
                       watch_white_ownership={
                           v: nn_ownership_at(ownership, v, case["size"]) for v in watched})
            rows.append(row)
    restore_probe_position(engine, case)
    return baseline, rows


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--games", required=True, help="Comma-separated exact SGF case names")
    p.add_argument("--probe-vertices", required=True, help="Comma-separated moves to test as black AND white")
    p.add_argument("--watch-vertices", required=True, help="Locations whose raw whiteOwnership should be tracked")
    p.add_argument("--serial", default=os.getenv("ANDROID_SERIAL"))
    p.add_argument("--mode", choices=("gpu", "cpu"), default="gpu")
    p.add_argument("--limit", type=int, default=115)
    p.add_argument("--threads", type=int, default=1)
    p.add_argument("--timeout", type=float, default=160.0)
    p.add_argument("--adb", default=os.getenv("ADB") or shutil.which("adb"))
    p.add_argument("--sgf-dir", type=Path)
    p.add_argument("--gpu-binary", type=Path, default=PROJECT / "build/katago_android_arm64_opencl/libkatago_exec_opencl.so")
    p.add_argument("--cpu-binary", type=Path, default=PROJECT / "build/katago_android_arm64_eigen/libkatago_exec.so")
    args = p.parse_args()
    if args.limit < 1 or not 1 <= args.threads <= 64:
        p.error("Invalid --limit or --threads")
    names = {name.strip() for name in args.games.split(",") if name.strip()}
    if not names:
        p.error("--games cannot be empty")
    cases, _ = collect_games(args.sgf_dir, args.limit)
    chosen = [case for case in cases if case["name"] in names]
    missing = names - {case["name"] for case in chosen}
    if missing:
        p.error("Unknown SGF names: " + ", ".join(sorted(missing)))
    if not args.adb:
        p.error("adb not found")
    binary = args.gpu_binary if args.mode == "gpu" else args.cpu_binary
    model = PROJECT / "app/src/main/assets/engine/10b.bin"
    config = PROJECT / "app/src/main/assets/engine/default_gtp.cfg"
    for file in (binary, model, config):
        if not file.is_file():
            p.error("Missing file: %s" % file)

    output = PROJECT / "build/endgame_life_probe" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output.mkdir(parents=True, exist_ok=True)
    engine = None
    rows = []
    try:
        serial = device_serial(args.adb, args.serial)
        use_egl = prepare_device(args.adb, serial, (args.mode,), {args.mode: binary}, model, config, output)
        prepare_real_games(args.adb, serial, output, chosen)
        engine = GtpEngine(args.adb, serial, args.mode, args.threads, 8, 8.0, output, args.timeout,
                           use_egl_mali=(args.mode == "gpu" and use_egl))
        for case in chosen:
            size = case["size"]
            candidates = parse_vertices(args.probe_vertices, size)
            watched = parse_vertices(args.watch_vertices, size)
            baseline, probes = run_probes(engine, case, candidates, watched)
            print("%s: baseline NN white lead %+6.2f; target white ownership %s" %
                  (case["name"], baseline["white_lead"], baseline["watch_white_ownership"]), flush=True)
            for probe in probes:
                if probe["status"] != "ok":
                    print("  %-5s %-6s rejected: %s" %
                          (probe["color"], probe["move"], probe["message"]), flush=True)
                    continue
                changed = probe["watch_white_ownership"]
                print("  %-5s %-6s NN lead=%+7.2f delta=%+7.2f targets=%s" %
                      (probe["color"], probe["move"], probe["white_lead"],
                       probe["delta_white_lead"], changed), flush=True)
            rows.append({"game": case["name"], "size": size, "official_re": case["official_re"],
                         "baseline": baseline, "probes": probes})
        report = output / "life_probe.json"
        report.write_text(json.dumps({"type": "endgame_one_move_nn_probes",
            "warning": "Single-ply raw NN differences cannot prove that a group lives or dies.",
            "device": serial, "mode": args.mode, "cases": rows}, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8")
        print("Local report:", report, flush=True)
        return 0
    except (OSError, RuntimeError, ValueError, TimeoutError) as exc:
        print("ERROR:", exc, "completed cases:", len(rows), "output:", output, file=sys.stderr)
        return 1
    finally:
        if engine is not None:
            engine.close()


if __name__ == "__main__":
    sys.exit(main())
