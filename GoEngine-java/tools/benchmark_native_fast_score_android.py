#!/usr/bin/env python3
"""Compare KataGo-native experimental scoring with original final_score on fixed SGFs.

Requires a rebuilt Android ARM64 KataGo binary that recognizes kata-fast-final-score.
The production APK and existing original GTP commands are untouched.
"""
import argparse
import csv
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import statistics
import sys

from benchmark_endgame_android import (
    PROJECT, POSITIONS, GtpEngine, device_serial, prepare_device, load_fixtures,
    parse_score, parse_dead, white_lead_from_gtp,
)
from complex_endgame_fixtures import COMPLEX_POSITIONS

FIELDS = [
    "backend", "fixture", "repeat", "sha256",
    "native_ms", "original_score_ms", "original_dead_ms",
    "native_fast_score", "native_raw_rule_score", "original_score",
    "nn_white_lead", "native_white_lead", "original_white_lead",
    "native_dead", "original_dead", "dead_same", "dead_only_native",
    "dead_only_original", "dead_groups", "groups", "black_area", "white_area",
]


def validate_native_result(data, fixture):
    if not isinstance(data, dict) or data.get("mode") != "raw_nn_chain_heuristic":
        raise ValueError("Unexpected native scoring result: %r" % data)
    if data.get("authoritative") is not False:
        raise ValueError("Experimental native result must be marked non-authoritative")
    fast = parse_score(data["fastRuleScore"])
    direct = parse_score(data["ruleScoreWithoutDead"])
    if abs(white_lead_from_gtp(fast) - float(data["whiteMinusBlackScore"])) > 0.01:
        raise ValueError("Native reported score and whiteMinusBlackScore disagree")
    if not 0 <= int(data["deadGroups"]) <= int(data["groups"]):
        raise ValueError("Invalid group counters")
    if not 0 <= int(data["blackArea"]) + int(data["whiteArea"]) <= fixture["size"] ** 2:
        raise ValueError("Invalid total area")
    dead = data.get("dead")
    if not isinstance(dead, list):
        raise ValueError("Native dead must be a list")
    normalized = parse_dead(" ".join(dead), fixture["size"])
    if len(normalized.split()) != len(dead):
        raise ValueError("Duplicate native dead vertices")
    if not dead and fast != direct:
        raise ValueError("Native score changed despite zero predicted dead stones")
    return normalized

GTP_LETTERS = "ABCDEFGHJKLMNOPQRST"


def describe_disagreement_groups(rows, fast_only, original_only):
    """Group discrepancies by actual connected chain, with geometric liberty counts.

    A low liberty count is NOT proof that a chain is dead. These diagnostics
    let us distinguish missing a whole chain from mismarking an isolated stone.
    """
    if not rows:
        return []
    size = len(rows)
    issues = []
    for source, vertices in (("native_only", fast_only), ("original_only", original_only)):
        seen = set()
        for vertex in vertices:
            x = GTP_LETTERS.index(vertex[0].upper())
            y = size - int(vertex[1:])
            color = rows[y][x]
            if color not in "BW":
                raise ValueError("Disagreement location is not a stone: " + vertex)
            if (x, y) in seen:
                continue  # Several differing stones may be in the same chain.
            queue, group, liberties = [(x, y)], set(), set()
            while queue:
                point = queue.pop()
                if point in group:
                    continue
                group.add(point)
                seen.add(point)
                px, py = point
                for nx, ny in ((px-1, py), (px+1, py), (px, py-1), (px, py+1)):
                    if not (0 <= nx < size and 0 <= ny < size):
                        continue
                    neighbor = rows[ny][nx]
                    if neighbor == ".":
                        liberties.add((nx, ny))
                    elif neighbor == color and (nx, ny) not in group:
                        queue.append((nx, ny))
            to_gtp = lambda point: GTP_LETTERS[point[0]] + str(size-point[1])
            issues.append({
                "source": source, "color": color, "stone_count": len(group),
                "liberties": len(liberties),
                "stones": sorted(to_gtp(p) for p in group),
                "liberty_vertices": sorted(to_gtp(p) for p in liberties),
            })
    return issues


def measure_case(engine, fixture, backend, name, repeat):
    engine.command("loadsgf " + fixture["remote"])
    rules_str, _ = engine.command("kata-get-rules")
    rules = json.loads(rules_str)
    expected = {"scoring": "AREA", "ko": "SIMPLE", "tax": "NONE", "friendlyPassOk": True}
    if any(rules.get(k) != v for k, v in expected.items()):
        raise ValueError("Unexpected rules for fixed SGF: " + rules_str)
    komi_str, _ = engine.command("get_komi")
    if abs(float(komi_str) - 5.5) > 1e-6:
        raise ValueError("Expected 5.5 komi, got " + komi_str)
    engine.command("play B pass")
    engine.command("play W pass")
    engine.command("clear_cache")

    native_str, native_ms = engine.command("kata-fast-final-score")
    native = json.loads(native_str)
    native_dead = validate_native_result(native, fixture)
    original_str, score_ms = engine.command("final_score")
    original_dead_str, dead_ms = engine.command("final_status_list dead")
    original_score = parse_score(original_str)
    original_dead = parse_dead(original_dead_str, fixture["size"])
    native_set = set(native_dead.split())
    original_set = set(original_dead.split())

    return {
        "backend": backend, "fixture": name, "repeat": repeat,
        "sha256": fixture["sha256"],
        "native_ms": round(native_ms, 3),
        "original_score_ms": round(score_ms, 3),
        "original_dead_ms": round(dead_ms, 3),
        "native_fast_score": native["fastRuleScore"],
        "native_raw_rule_score": native["ruleScoreWithoutDead"],
        "original_score": original_score,
        "nn_white_lead": round(float(native["nnWhiteLead"]), 3),
        "native_white_lead": round(float(native["whiteMinusBlackScore"]), 3),
        "original_white_lead": white_lead_from_gtp(original_score),
        "native_dead": native_dead,
        "original_dead": original_dead,
        "dead_same": native_set == original_set,
        "dead_only_native": sorted(native_set - original_set),
        "dead_only_original": sorted(original_set - native_set),
        "disagreement_groups": describe_disagreement_groups(
            fixture.get("grid"), sorted(native_set - original_set),
            sorted(original_set - native_set)),
        "dead_groups": native["deadGroups"], "groups": native["groups"],
        "black_area": native["blackArea"], "white_area": native["whiteArea"],
    }


def summarize_comparisons(rows):
    summary = {}
    keys = sorted({(r["backend"], r["fixture"]) for r in rows})
    for backend, fixture in keys:
        group = [r for r in rows if r["backend"] == backend and r["fixture"] == fixture]
        original_leads = [r["original_white_lead"] for r in group]
        summary[backend + "/" + fixture] = {
            "samples": len(group),
            "dead_matches": sum(r["dead_same"] for r in group),
            "dead_disagreements": sum(not r["dead_same"] for r in group),
            "native_ms_average": round(statistics.mean(r["native_ms"] for r in group), 3),
            "original_ms_average": round(statistics.mean(
                r["original_score_ms"] + r["original_dead_ms"] for r in group), 3),
            "nn_to_original_delta_average_abs": round(statistics.mean(
                abs(r["nn_white_lead"] - r["original_white_lead"]) for r in group), 3),
            "prediction_to_rule_delta_average_abs": round(statistics.mean(
                abs(r["native_white_lead"] - r["original_white_lead"]) for r in group), 3),
            "original_prediction_range": round(max(original_leads) - min(original_leads), 3),
            "native_dead_variants": sorted({r["native_dead"] for r in group}),
            "original_dead_variants": sorted({r["original_dead"] for r in group}),
        }
    return summary


def main(argv=None):
    p = argparse.ArgumentParser(description="RK3588 native KataGo fast rule scoring experiment")
    p.add_argument("--serial", default=os.getenv("ANDROID_SERIAL"))
    p.add_argument("--mode", choices=("cpu", "gpu", "both"), default="gpu")
    p.add_argument("--suite", choices=("baseline", "complex", "all"), default="complex",
                   help="complex includes 9/13/19 boards; baseline keeps the old three")
    p.add_argument("--positions", default=None,
                   help="Comma-separated fixture names from the selected suite; default is all")
    p.add_argument("--repeats", type=int, default=2)
    p.add_argument("--threads", type=int, default=1)
    p.add_argument("--visits", type=int, default=8)
    p.add_argument("--timeout", type=float, default=160)
    p.add_argument("--adb", default=os.getenv("ADB") or shutil.which("adb"))
    p.add_argument("--cpu-binary", type=Path)
    p.add_argument("--gpu-binary", type=Path, default=PROJECT / "build/katago_android_arm64_opencl/libkatago_exec_opencl.so")
    p.add_argument("--no-upload", action="store_true")
    args = p.parse_args(argv)
    available = dict(POSITIONS) if args.suite == "baseline" else (
        dict(COMPLEX_POSITIONS) if args.suite == "complex" else dict(POSITIONS, **COMPLEX_POSITIONS))
    selected = list(available) if args.positions is None else [
        n.strip() for n in args.positions.split(",") if n.strip()]
    if not selected or len(set(selected)) != len(selected) or any(n not in available for n in selected):
        p.error("Select distinct positions from: " + ",".join(available))
    if args.repeats < 1 or not 1 <= args.threads <= 64 or args.visits < 1 or not args.adb:
        p.error("Invalid repeats/threads/visits/adb")
    backends = ["cpu", "gpu"] if args.mode == "both" else [args.mode]
    cpu = args.cpu_binary or PROJECT / "build/katago_android_arm64_eigen/libkatago_exec.so"
    binaries = {"cpu": cpu, "gpu": args.gpu_binary}
    model = PROJECT / "app/src/main/assets/engine/10b.bin"
    config = PROJECT / "app/src/main/assets/engine/default_gtp.cfg"
    for backend in backends:
        if not binaries[backend].is_file():
            p.error("Please rebuild native KataGo %s: bash tools/build_katago_from_source.sh %s" % (
                backend, "opencl" if backend == "gpu" else "eigen"))
    if not model.is_file() or not config.is_file():
        p.error("Missing 10b model/config")

    output = PROJECT / "build/native_fast_score" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output.mkdir(parents=True, exist_ok=True)
    try:
        serial = device_serial(args.adb, args.serial)
        gpu_egl = prepare_device(args.adb, serial, backends, binaries, model, config, output)
        fixtures = load_fixtures(args.adb, serial, output, selected, positions=available)
        rows = []
        with (output / "endgame.csv").open("w", encoding="utf-8", newline="") as csv_out:
            writer = csv.DictWriter(csv_out, fieldnames=FIELDS, extrasaction="ignore")
            writer.writeheader()
            for backend in backends:
                engine = GtpEngine(args.adb, serial, backend, args.threads, args.visits,
                                   8.0, output, args.timeout,
                                   use_egl_mali=(backend == "gpu" and gpu_egl))
                try:
                    known, _ = engine.command("known_command kata-fast-final-score")
                    if known.strip().lower() != "true":
                        raise RuntimeError("This binary is stale: rebuild 'bash tools/build_katago_from_source.sh %s'" %
                                           ("opencl" if backend == "gpu" else "eigen"))
                    engine.command("boardsize 9")
                    engine.command("komi 5.5")
                    engine.command("kata-set-rules chinese")
                    engine.command("kata-raw-nn 0")  # Warmup, not part of measured time.
                    warmed_sizes = {9}
                    for repeat in range(1, args.repeats + 1):
                        for name in selected:
                            # First 13x13/19x19 inference may trigger OpenCL allocation
                            # or shape-specific warm-up. Keep it outside timed native calls.
                            size = fixtures[name]["size"]
                            if size not in warmed_sizes:
                                engine.command("loadsgf " + fixtures[name]["remote"])
                                engine.command("kata-raw-nn 0")
                                warmed_sizes.add(size)
                            item = measure_case(engine, fixtures[name], backend, name, repeat)
                            rows.append(item)
                            writer.writerow(item)
                            csv_out.flush()
                            print("%s/%s (%dx%d) #%d native %s (raw %s, nnLead %+.3f, %6.1fms), "
                                  "original %s (%6.1fms), dead %s vs %s%s" % (
                                    backend, name, fixtures[name]["size"], fixtures[name]["size"],
                                    repeat, item["native_fast_score"],
                                    item["native_raw_rule_score"], item["nn_white_lead"], item["native_ms"],
                                    item["original_score"], item["original_score_ms"] + item["original_dead_ms"],
                                    item["native_dead"] or "(none)", item["original_dead"] or "(none)",
                                    "" if item["dead_same"] else " [DEAD MISMATCH]"
                                  ), flush=True)
                finally:
                    engine.close()
        report = {
            "type": "native_fast_score_vs_original",
            "status": "COMPARISON_ONLY",
            "warning": "Native fastRuleScore is current-board Chinese area counting after "
                       "RAW NN's heuristic dead removal; final_score is a prediction "
                       "of continued play, and neither is certified ground truth. "
                       "No APK/JNI/UI change is made.",
            "device": serial, "backends": backends, "threads": args.threads,
            "suite": args.suite, "repeats": args.repeats,
            "fixture_names": selected,
            "model_sha256": hashlib.sha256(model.read_bytes()).hexdigest(),
            "binaries": {backend: hashlib.sha256(binaries[backend].read_bytes()).hexdigest()
                         for backend in backends},
            "fixtures": fixtures, "rows": rows,
            "summary": summarize_comparisons(rows),
        }
        (output / "endgame.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print("\nSummary (agreement is NOT certified correctness):")
        for key, item in report["summary"].items():
            print("%s n=%d dead-mismatches=%d, native=%.1fms original=%.1fms, "
                  "NN-to-original=%.1f目, rule-to-original=%.1f目, original-variance=%.1f目" % (
                      key, item["samples"], item["dead_disagreements"],
                      item["native_ms_average"], item["original_ms_average"],
                      item["nn_to_original_delta_average_abs"],
                      item["prediction_to_rule_delta_average_abs"],
                      item["original_prediction_range"]), flush=True)
        print("Local report: " + str(output))
        if not args.no_upload:
            from upload_endgame_results import upload_results
            print("Public report: " + upload_results(PROJECT.parent, output), flush=True)
        return 0
    except (OSError, RuntimeError, ValueError, TimeoutError) as err:
        print("ERROR: %s (partial results: %s)" % (err, output), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
