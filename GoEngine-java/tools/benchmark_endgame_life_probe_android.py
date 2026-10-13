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
import re
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
    # Keep the original final passes. They clear the immediate ko prohibition,
    # so undoing them would incorrectly reject valid recaptures (e.g. A9/B9).
    # GTP 'play' may append further moves even when the players previously passed.
    engine.command("loadsgf " + case["remote"])


def parse_search_candidates(response):
    """KataGo kata-search_analyze info entries; scoreLead is REPLY side's lead."""
    candidates = []
    segments = re.split(r"\\binfo\\s+move\\s+", response)
    for segment in segments[1:]:
        move = segment.split(None,1)[0] if segment.split() else None
        if not move:
            continue
        def metric(key):
            match = re.search(r"\\b"+re.escape(key)+r"\\s+([^\\s]+)",segment)
            return match.group(1) if match else None
        score, winrate, visits = metric("scoreLead"), metric("winrate"), metric("visits")
        if score is None or winrate is None or visits is None:
            continue
        try:
            score, winrate, visits = float(score),float(winrate),int(visits)
        except ValueError:
            continue
        pv_match = re.search(r"\\bpv\\s+(.+)",segment,re.S)
        variation = []
        if pv_match:
            for item in pv_match.group(1).split():
                if item in ("info","rootInfo","ownership","ownershipStdev","play"):
                    break
                variation.append(item)
                if len(variation) >= 10:
                    break
        candidates.append({"move":move,"visits":visits,"reply_winrate":round(winrate,4),
                           "reply_score_lead":round(score,3),"pv":variation})
    candidates.sort(key=lambda c: -c["visits"])
    return candidates[:3]


def run_probes(engine, case, candidates, watched, search_visits=0, search_seconds=3.0):
    check_engine_position(engine, case)
    if search_visits:
        engine.command("kata-set-param maxVisits %d" % search_visits)
        engine.command("kata-set-param maxTime %.3f" % search_seconds)
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
            if search_visits:
                reply = "white" if color == "black" else "black"
                row["reply_player"] = reply
                try:
                    text, elapsed = engine.command(
                        "kata-search_analyze %s 50 minmoves 3 maxmoves 3" % reply)
                    row["reply_search_ms"] = round(elapsed,3)
                    row["reply_candidates"] = parse_search_candidates(text)
                    if not row["reply_candidates"]:
                        row["reply_search_warning"] = "No structured candidate info returned"
                except (RuntimeError,TimeoutError,ValueError) as exc:
                    row["reply_search_warning"] = str(exc)[:240]
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
    p.add_argument("--search-visits", type=int, default=0,
                   help="MCTS opponent reply visits for EACH legal one-move probe (0 disables)")
    p.add_argument("--search-seconds", type=float, default=3.0,
                   help="KataGo MCTS time cap in seconds per tested reply")
    p.add_argument("--timeout", type=float, default=160.0)
    p.add_argument("--adb", default=os.getenv("ADB") or shutil.which("adb"))
    p.add_argument("--sgf-dir", type=Path)
    p.add_argument("--gpu-binary", type=Path, default=PROJECT / "build/katago_android_arm64_opencl/libkatago_exec_opencl.so")
    p.add_argument("--cpu-binary", type=Path, default=PROJECT / "build/katago_android_arm64_eigen/libkatago_exec.so")
    args = p.parse_args()
    if args.limit < 1 or not 1 <= args.threads <= 64 or args.search_visits < 0 or args.search_seconds <= 0:
        p.error("Invalid --limit, --threads, --search-visits or --search-seconds")
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
            baseline, probes = run_probes(engine, case, candidates, watched,
                                          args.search_visits,args.search_seconds)
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
                if "reply_candidates" in probe:
                    candidates_text = [
                        "%s(v=%d win=%.3f score=%+.2f pv=%s)" %
                        (c["move"],c["visits"],c["reply_winrate"],
                         c["reply_score_lead"]," ".join(c["pv"][:6]))
                        for c in probe["reply_candidates"]]
                    print("    MCTS %s reply: %s" %
                          (probe["reply_player"], "; ".join(candidates_text) or "no candidates"),flush=True)
                if "reply_search_warning" in probe:
                    print("    MCTS warning:",probe["reply_search_warning"],flush=True)
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
