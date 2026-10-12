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
import sys

from benchmark_endgame_android import (
    PROJECT, POSITIONS, GtpEngine, device_serial, prepare_device, load_fixtures,
    parse_score, parse_dead, white_lead_from_gtp,
)

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
        "dead_groups": native["deadGroups"], "groups": native["groups"],
        "black_area": native["blackArea"], "white_area": native["whiteArea"],
    }


def main(argv=None):
    p = argparse.ArgumentParser(description="RK3588 native KataGo fast rule scoring experiment")
    p.add_argument("--serial", default=os.getenv("ANDROID_SERIAL"))
    p.add_argument("--mode", choices=("cpu", "gpu", "both"), default="gpu")
    p.add_argument("--positions", default="simple,territory,tactical")
    p.add_argument("--repeats", type=int, default=2)
    p.add_argument("--threads", type=int, default=1)
    p.add_argument("--visits", type=int, default=8)
    p.add_argument("--timeout", type=float, default=160)
    p.add_argument("--adb", default=os.getenv("ADB") or shutil.which("adb"))
    p.add_argument("--cpu-binary", type=Path)
    p.add_argument("--gpu-binary", type=Path, default=PROJECT / "build/katago_android_arm64_opencl/libkatago_exec_opencl.so")
    p.add_argument("--no-upload", action="store_true")
    args = p.parse_args(argv)
    selected = [n.strip() for n in args.positions.split(",") if n.strip()]
    if not selected or len(set(selected)) != len(selected) or any(n not in POSITIONS for n in selected):
        p.error("Select distinct positions from: " + ",".join(POSITIONS))
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
        fixtures = load_fixtures(args.adb, serial, output, selected)
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
                    for repeat in range(1, args.repeats + 1):
                        for name in selected:
                            item = measure_case(engine, fixtures[name], backend, name, repeat)
                            rows.append(item)
                            writer.writerow(item)
                            csv_out.flush()
                            print("%s/%s #%d native %s (raw %s, nnLead %+.3f, %6.1fms), "
                                  "original %s (%6.1fms), dead %s vs %s" % (
                                    backend, name, repeat, item["native_fast_score"],
                                    item["native_raw_rule_score"], item["nn_white_lead"], item["native_ms"],
                                    item["original_score"], item["original_score_ms"] + item["original_dead_ms"],
                                    item["native_dead"] or "(none)", item["original_dead"] or "(none)"
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
            "repeats": args.repeats, "model_sha256": hashlib.sha256(model.read_bytes()).hexdigest(),
            "binaries": {backend: hashlib.sha256(binaries[backend].read_bytes()).hexdigest()
                         for backend in backends},
            "fixtures": fixtures, "rows": rows,
        }
        (output / "endgame.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
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
