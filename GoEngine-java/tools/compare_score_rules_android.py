#!/usr/bin/env python3
"""Investigate KataGo's finished Chinese game scoring without changing APP behavior.

The same fixed SGF receives two real passes in both modes. Only the
friendlyPassOk option differs. Fast "direct" scores ARE NOT interchangeable
with KataGo's current human-friendly final_score prediction.
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

from benchmark_endgame_android import (PROJECT, POSITIONS, GtpEngine,
                                       device_serial, prepare_device, load_fixtures,
                                       parse_score, parse_dead, chinese_area_score)

FIELDS = ("backend", "fixture", "repeat", "scoring", "threads",
          "friendly_pass_ok", "rules", "sha256", "score", "dead",
          "local_white_lead", "score_minus_local_lead",
          "score_ms", "dead_ms", "total_ms")


def select_binaries(args):
    cpu = args.cpu_binary or PROJECT / "app/build/generated/katagoJniLibs/arm64-v8a/libkatago_exec.so"
    if not cpu.is_file() and not args.cpu_binary:
        cpu = Path(os.getenv("KATAGO_WORKDIR", str(Path.home() / ".cache/goengine_katago"))) / "build_android_arm64_eigen/katago"
    gpu = args.gpu_binary or PROJECT / "build/katago_android_arm64_opencl/libkatago_exec_opencl.so"
    return {"cpu": cpu, "gpu": gpu}


def validate_same_rules(a, b):
    """Never attribute score differences to a different ko/komi/scoring rule."""
    if a["friendlyPassOk"] == b["friendlyPassOk"]:
        raise RuntimeError("friendlyPassOk was not switched")
    core_a, core_b = dict(a), dict(b)
    core_a.pop("friendlyPassOk", None)
    core_b.pop("friendlyPassOk", None)
    if core_a != core_b:
        raise RuntimeError("Other rules changed: %s vs %s" % (core_a, core_b))


def run_case(engine, fixture, backend, name, repeat, threads, friendly):
    # loadsgf carries the same stones and PL[B] in both cases.
    engine.command("loadsgf " + fixture["remote"])
    engine.command("kata-set-rule friendlyPassOk " + ("true" if friendly else "false"))
    rules_json, _ = engine.command("kata-get-rules")
    rules = json.loads(rules_json)
    if rules.get("scoring") != "AREA" or rules.get("tax") != "NONE" or rules.get("ko") != "SIMPLE":
        raise RuntimeError("Expected Chinese area/simple-ko rules, got " + rules_json)
    if rules.get("friendlyPassOk") is not friendly:
        raise RuntimeError("KataGo rejected friendlyPassOk " + str(friendly))
    if not engine.command("play B pass")[0] == "":
        raise RuntimeError("Unexpected reply to first pass")
    if not engine.command("play W pass")[0] == "":
        raise RuntimeError("Unexpected reply to second pass")
    engine.command("clear_cache")
    score, score_ms = engine.command("final_score")
    dead, dead_ms = engine.command("final_status_list dead")
    score = parse_score(score)
    dead = parse_dead(dead)
    local = chinese_area_score(fixture["grid"], dead, 5.5)
    lead = 0.0 if score == "0" else (1 if score.startswith("W+") else -1) * float(score[2:])
    return {
        "backend": backend, "fixture": name, "repeat": repeat,
        "scoring": "human_predict" if friendly else "finished_rules",
        "threads": threads, "friendly_pass_ok": friendly, "rules": rules_json,
        "sha256": fixture["sha256"], "score": score, "dead": dead,
        "local_white_lead": local["white_lead"],
        "score_minus_local_lead": round(lead - local["white_lead"], 2),
        "score_ms": round(score_ms, 2), "dead_ms": round(dead_ms, 2),
        "total_ms": round(score_ms + dead_ms, 2),
    }


def analyze(rows, names, backends):
    comparisons = []
    for backend in backends:
        for name in names:
            repeats = sorted(set(r["repeat"] for r in rows if r["backend"] == backend and r["fixture"] == name))
            for repeat in repeats:
                pair = {r["scoring"]: r for r in rows
                        if r["backend"] == backend and r["fixture"] == name and r["repeat"] == repeat}
                human, direct = pair["human_predict"], pair["finished_rules"]
                a, b = json.loads(human["rules"]), json.loads(direct["rules"])
                validate_same_rules(a, b)
                if human["sha256"] != direct["sha256"]:
                    raise RuntimeError("SGF hash mismatch")
                comparisons.append({
                    "backend": backend, "fixture": name, "repeat": repeat,
                    "predict_score": human["score"], "rules_score": direct["score"],
                    "same_score": human["score"] == direct["score"],
                    "predict_dead": human["dead"], "rules_dead": direct["dead"],
                    "same_dead": human["dead"] == direct["dead"],
                    "predict_total_ms": human["total_ms"], "rules_total_ms": direct["total_ms"],
                })
    return comparisons


def main(argv=None):
    parser = argparse.ArgumentParser(description="Compare prediction vs Chinese rule endgame on identical two-pass positions")
    parser.add_argument("--serial", default=os.getenv("ANDROID_SERIAL"))
    parser.add_argument("--mode", choices=("cpu", "gpu", "both"), default="cpu")
    parser.add_argument("--positions", default="simple,territory,tactical")
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--repeats", type=int, default=1)
    parser.add_argument("--visits", type=int, default=8)
    parser.add_argument("--timeout", type=float, default=120)
    parser.add_argument("--adb", default=os.getenv("ADB") or shutil.which("adb"))
    parser.add_argument("--cpu-binary", type=Path)
    parser.add_argument("--gpu-binary", type=Path)
    parser.add_argument("--no-upload", action="store_true")
    args = parser.parse_args(argv)
    names = [x.strip() for x in args.positions.split(",") if x.strip()]
    if not names or any(x not in POSITIONS for x in names) or len(set(names)) != len(names):
        parser.error("Unsupported or repeated fixture")
    if not args.adb or not 1 <= args.threads <= 64 or args.repeats < 1 or args.visits < 1:
        parser.error("Invalid ADB/threads/repeats/visits")
    backends = ["cpu", "gpu"] if args.mode == "both" else [args.mode]
    binaries = select_binaries(args)
    for backend in backends:
        if not binaries[backend].is_file():
            parser.error("Missing %s ARM64 KataGo executable: %s" % (backend, binaries[backend]))
    model = PROJECT / "app/src/main/assets/engine/10b.bin"
    config = PROJECT / "app/src/main/assets/engine/default_gtp.cfg"
    if not model.is_file() or not config.is_file():
        parser.error("Missing 10b model or GTP config")
    output = PROJECT / "build/endgame_mode_compare" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output.mkdir(parents=True, exist_ok=True)
    try:
        serial = device_serial(args.adb, args.serial)
        gpu_egl = prepare_device(args.adb, serial, backends, binaries, model, config, output)
        fixtures = load_fixtures(args.adb, serial, output, names)
        rows = []
        with (output / "endgame.csv").open("w", encoding="utf-8", newline="") as out:
            writer = csv.DictWriter(out, fieldnames=FIELDS)
            writer.writeheader()
            for backend in backends:
                engine = GtpEngine(args.adb, serial, backend, args.threads,
                                   args.visits, 8.0, output, args.timeout,
                                   use_egl_mali=(backend == "gpu" and gpu_egl))
                try:
                    engine.command("boardsize 9")
                    engine.command("komi 5.5")
                    engine.command("kata-set-rules chinese")
                    engine.command("kata-raw-nn 0")  # Untimed warmup.
                    for repeat in range(1, args.repeats + 1):
                        for name in names:
                            # Start with the existing human-friendly mode.
                            # Each case reloads the identical fixture and passes.
                            for friendly in (True, False):
                                result = run_case(engine, fixtures[name], backend, name,
                                                  repeat, args.threads, friendly)
                                rows.append(result)
                                writer.writerow(result)
                                out.flush()
                                print("%s %s #%d %s final=%s dead=%s time=%.1fms" %
                                      (backend, name, repeat, result["scoring"],
                                       result["score"], result["dead"], result["total_ms"]), flush=True)
                finally:
                    engine.close()
        comparisons = analyze(rows, names, backends)
        report = {
            "type": "friendly_pass_vs_direct_scoring",
            "note": "Two passes on identical AB/AW setup. Same area/simple-ko rules except friendlyPassOk. Direct result may be different and is NOT a drop-in replacement for the prediction.",
            "device": serial, "model_sha256": hashlib.sha256(model.read_bytes()).hexdigest(),
            "binaries": {k: hashlib.sha256(binaries[k].read_bytes()).hexdigest() for k in backends},
            "threads": args.threads, "repeats": args.repeats,
            "fixtures": fixtures, "rows": rows, "comparisons": comparisons,
        }
        (output / "endgame.json").write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
        for row in comparisons:
            print("COMPARE %s/%s: prediction=%s direct=%s, same_score=%s, same_dead=%s, %.1f vs %.1fms" %
                  (row["backend"], row["fixture"], row["predict_score"], row["rules_score"],
                   row["same_score"], row["same_dead"],
                   row["predict_total_ms"], row["rules_total_ms"]), flush=True)
        print("Local report: %s" % output, flush=True)
        if not args.no_upload:
            from upload_endgame_results import upload_results
            print("Report: " + upload_results(PROJECT.parent, output), flush=True)
        return 0
    except (OSError, ValueError, RuntimeError) as exc:
        print("ERROR: %s (partial data in %s)" % (exc, output), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
