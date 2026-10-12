#!/usr/bin/env python3
"""Compare experimental FastEndgameScorer against full KataGo on fixed SGF boards.

Runs on the real Android ARM64 KataGo binary through ADB. Does not install or
modify the APK. Both paths use the *same loaded position after two passes*.
The NN is NOT a definitive judge; discrepancies are diagnosis, not proof.
"""
import argparse
import csv
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import statistics
import sys

from benchmark_endgame_android import (
    PROJECT, POSITIONS, GtpEngine, device_serial, prepare_device, load_fixtures,
    parse_score, parse_dead, parse_raw_white_lead, white_lead_from_gtp,
    chinese_area_score,
)

COLUMNS = (
    "backend", "fixture", "repeat", "sha256", "threads",
    "raw_nn_ms", "original_score_ms", "original_dead_ms",
    "fast_white_lead", "original_white_lead", "lead_delta",
    "fast_dead", "original_dead", "dead_same",
    "fast_area_lead", "original_area_lead",
    "fast_dead_count", "original_dead_count",
)

LETTER = "ABCDEFGHJKLMNOPQRST"
OWNERSHIP_KEY = "whiteOwnership"
MIN_ENEMY_OWNERSHIP = 0.55
MEAN_ENEMY_OWNERSHIP = 0.70


def parse_white_ownership(text, size):
    """Parse the same whiteOwnership array and top-row-first order as KataGoEngine.java."""
    tokens = text.split()
    if tokens.count(OWNERSHIP_KEY) != 1:
        raise ValueError("Expected one whiteOwnership array in kata-raw-nn response")
    at = tokens.index(OWNERSHIP_KEY) + 1
    if len(tokens) - at < size * size:
        raise ValueError("Incomplete whiteOwnership array")
    values = []
    for raw in tokens[at:at + size * size]:
        number = float(raw)
        if not math.isfinite(number) or abs(number) > 1.01:
            raise ValueError("Invalid ownership value: " + raw)
        values.append(max(-1.0, min(1.0, number)))
    return values


def suggested_dead(rows, ownership):
    """Mirror FastEndgameScorer.java: reject partial chains and low ownership."""
    size = len(rows)
    if len(ownership) != size * size or any(len(row) != size for row in rows):
        raise ValueError("Board and ownership sizes differ")
    visited = set()
    dead = set()
    groups = 0
    suggested_groups = 0
    for y in range(size):
        for x in range(size):
            color = rows[y][x]
            if color not in "BW" or (x, y) in visited:
                continue
            groups += 1
            stack = [(x, y)]
            chain = []
            visited.add((x, y))
            while stack:
                a, b = stack.pop()
                chain.append((a, b))
                for nx, ny in ((a-1, b), (a+1, b), (a, b-1), (a, b+1)):
                    if (0 <= nx < size and 0 <= ny < size
                            and (nx, ny) not in visited and rows[ny][nx] == color):
                        visited.add((nx, ny))
                        stack.append((nx, ny))
            enemy = [ownership[b * size + a] * (1 if color == "B" else -1)
                     for a, b in chain]
            if all(v >= MIN_ENEMY_OWNERSHIP for v in enemy) and (
                    sum(enemy) / len(enemy) >= MEAN_ENEMY_OWNERSHIP):
                dead.update(chain)
                suggested_groups += 1
    vertices = ["%s%d" % (LETTER[x], size-y) for x, y in dead]
    return " ".join(sorted(vertices)), groups, suggested_groups


def dead_difference(fast, original):
    proposed = set(fast.split())
    reference = set(original.split())
    return {"fast_only": sorted(proposed - reference),
            "original_only": sorted(reference - proposed)}


def compare_one(engine, fixture, backend, name, repeat, threads):
    # SGF stores PL[B] and Chinese rules. A live APP has exactly two passes
    # at this point: reproduce those, don't compare non-terminal setups.
    engine.command("loadsgf " + fixture["remote"])
    rules_json, _ = engine.command("kata-get-rules")
    rules = json.loads(rules_json)
    expected = {"scoring": "AREA", "tax": "NONE", "ko": "SIMPLE",
                "friendlyPassOk": True}
    if any(rules.get(key) != val for key, val in expected.items()):
        raise RuntimeError("Not the expected Chinese/friendlyPass rules: " + rules_json)
    engine.command("play B pass")
    engine.command("play W pass")
    engine.command("clear_cache")

    # Exactly one network evaluation is the experiment's default APP fast path.
    raw, raw_ms = engine.command("kata-raw-nn 0")
    fast_lead = parse_raw_white_lead(raw)
    ownership = parse_white_ownership(raw, len(fixture["grid"]))
    fast_dead, groups, dead_groups = suggested_dead(fixture["grid"], ownership)
    local_fast = chinese_area_score(fixture["grid"], fast_dead, 5.5)

    # No board change between NN and full GTP scoring; same engine/model/rules.
    full, score_ms = engine.command("final_score")
    original_dead, dead_ms = engine.command("final_status_list dead")
    original = parse_score(full)
    original_dead = parse_dead(original_dead)
    original_lead = white_lead_from_gtp(original)
    local_original = chinese_area_score(fixture["grid"], original_dead, 5.5)
    delta = round(fast_lead - original_lead, 3)
    return {
        "backend": backend, "fixture": name, "repeat": repeat,
        "sha256": fixture["sha256"], "threads": threads,
        "raw_nn_ms": round(raw_ms, 3),
        "original_score_ms": round(score_ms, 3),
        "original_dead_ms": round(dead_ms, 3),
        "fast_white_lead": round(fast_lead, 3),
        "original_white_lead": round(original_lead, 3),
        "lead_delta": delta,
        "original_score": original,
        "fast_dead": fast_dead,
        "original_dead": original_dead,
        "dead_same": set(fast_dead.split()) == set(original_dead.split()),
        "dead_difference": dead_difference(fast_dead, original_dead),
        "fast_area_lead": local_fast["white_lead"],
        "original_area_lead": local_original["white_lead"],
        "fast_dead_count": len(fast_dead.split()),
        "original_dead_count": len(original_dead.split()),
        "groups": groups, "suggested_dead_groups": dead_groups,
        "rules": rules,
    }


def summarize(rows):
    summary = {}
    for (backend, fixture) in sorted({(r["backend"], r["fixture"]) for r in rows}):
        group = [r for r in rows if r["backend"] == backend and r["fixture"] == fixture]
        summary[backend + "/" + fixture] = {
            "samples": len(group),
            "nn_ms_avg": round(statistics.mean(r["raw_nn_ms"] for r in group), 2),
            "full_ms_avg": round(statistics.mean(
                r["original_score_ms"] + r["original_dead_ms"] for r in group), 2),
            "abs_lead_delta_avg": round(statistics.mean(abs(r["lead_delta"]) for r in group), 2),
            "lead_deltas": [r["lead_delta"] for r in group],
            "dead_matches": sum(r["dead_same"] for r in group),
        }
    return summary


def main(argv=None):
    parser = argparse.ArgumentParser(description="Fixed SGF: fast NN vs original KataGo endgame")
    parser.add_argument("--serial", default=os.getenv("ANDROID_SERIAL"))
    parser.add_argument("--mode", choices=("cpu", "gpu", "both"), default="gpu")
    parser.add_argument("--positions", default="simple,territory,tactical")
    parser.add_argument("--threads", type=int, default=1)
    parser.add_argument("--repeats", type=int, default=1)
    parser.add_argument("--visits", type=int, default=8)
    parser.add_argument("--timeout", type=float, default=180)
    parser.add_argument("--adb", default=os.getenv("ADB") or shutil.which("adb"))
    parser.add_argument("--cpu-binary", type=Path)
    parser.add_argument("--gpu-binary", type=Path, default=(
        PROJECT / "build/katago_android_arm64_opencl/libkatago_exec_opencl.so"))
    parser.add_argument("--no-upload", action="store_true")
    args = parser.parse_args(argv)
    selected = [name.strip() for name in args.positions.split(",") if name.strip()]
    if not selected or any(name not in POSITIONS for name in selected) or len(set(selected)) != len(selected):
        parser.error("Select unique fixtures among " + ",".join(POSITIONS))
    if not args.adb or args.repeats < 1 or args.visits < 1 or not (1 <= args.threads <= 64):
        parser.error("Invalid adb, repeats, visits or threads")

    backends = ["cpu", "gpu"] if args.mode == "both" else [args.mode]
    cpu = args.cpu_binary or PROJECT / "app/build/generated/katagoJniLibs/arm64-v8a/libkatago_exec.so"
    if not cpu.is_file() and args.cpu_binary is None:
        cpu = Path(os.getenv("KATAGO_WORKDIR", str(Path.home() / ".cache/goengine_katago"))) / "build_android_arm64_eigen/katago"
    binaries = {"cpu": cpu, "gpu": args.gpu_binary}
    model = PROJECT / "app/src/main/assets/engine/10b.bin"
    config = PROJECT / "app/src/main/assets/engine/default_gtp.cfg"
    for backend in backends:
        if not binaries[backend].is_file():
            parser.error("Missing %s ARM64 KataGo binary: %s" % (backend, binaries[backend]))
    if not model.is_file() or not config.is_file():
        parser.error("Missing bundled 10b model/config")

    output = PROJECT / "build/fast_endgame_fixed" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output.mkdir(parents=True, exist_ok=True)
    try:
        serial = device_serial(args.adb, args.serial)
        gpu_egl = prepare_device(args.adb, serial, backends, binaries, model, config, output)
        fixtures = load_fixtures(args.adb, serial, output, selected)
        rows = []
        with (output / "endgame.csv").open("w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=COLUMNS, extrasaction="ignore")
            writer.writeheader()
            for backend in backends:
                engine = GtpEngine(args.adb, serial, backend, args.threads, args.visits,
                                   8.0, output, args.timeout,
                                   use_egl_mali=(backend == "gpu" and gpu_egl))
                try:
                    engine.command("boardsize 9")
                    engine.command("komi 5.5")
                    engine.command("kata-set-rules chinese")
                    engine.command("kata-raw-nn 0")  # Warmup outside measured loop.
                    for repeat in range(1, args.repeats + 1):
                        for name in selected:
                            item = compare_one(engine, fixtures[name], backend, name,
                                               repeat, args.threads)
                            rows.append(item)
                            writer.writerow(item)
                            f.flush()
                            print("%s/%s #%d rawNN=%.1fms fast=%+.2f full=%+.2f "
                                  "delta=%+.2f dead: %s vs %s full=%.1fms" %
                                  (backend, name, repeat, item["raw_nn_ms"],
                                   item["fast_white_lead"], item["original_white_lead"],
                                   item["lead_delta"], item["fast_dead"] or "(none)",
                                   item["original_dead"] or "(none)",
                                   item["original_score_ms"] + item["original_dead_ms"]),
                                  flush=True)
                finally:
                    engine.close()
        report = {
            "type": "fixed_sgf_fast_nn_vs_full",
            "status": "COMPARISON_ONLY",
            "note": "Static AB/AW SGF with two passes, 10b model, default GTP config. "
                    "KataGo final_score is a prediction, NOT ground truth. NN death "
                    "thresholds mirror FastEndgameScorer.java. APK Human SL configuration "
                    "may differ. No formal correctness PASS is claimed.",
            "device": serial, "threads": args.threads, "repeats": args.repeats,
            "model_sha256": hashlib.sha256(model.read_bytes()).hexdigest(),
            "config_sha256": hashlib.sha256(config.read_bytes()).hexdigest(),
            "binaries": {b: hashlib.sha256(binaries[b].read_bytes()).hexdigest()
                         for b in backends},
            "fixtures": fixtures, "rows": rows, "summary": summarize(rows),
        }
        (output / "endgame.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print("\nSummary (diagnostic only):")
        for key, item in report["summary"].items():
            print("%s: NN %.1fms, old %.1fms, delta %.2f目, dead_same=%d/%d" %
                  (key, item["nn_ms_avg"], item["full_ms_avg"],
                   item["abs_lead_delta_avg"], item["dead_matches"], item["samples"]))
        print("Local: " + str(output))
        if not args.no_upload:
            from upload_endgame_results import upload_results
            print("Report: " + upload_results(PROJECT.parent, output))
        return 0
    except (OSError, RuntimeError, ValueError, TimeoutError) as exc:
        print("ERROR: %s (partial files at %s)" % (exc, output), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
