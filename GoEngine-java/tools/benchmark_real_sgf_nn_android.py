#!/usr/bin/env python3
"""KataGo one-forward-pass NN versus *recorded* scores of real completed games.

Unlike fixed synthetic endgame benchmarks, the RE[...] on the original SGF is
the reference. Never append passes, re-score the SGF, or use KataGo final_score
as the target. This measures one raw NN evaluation at the original final move.
SGF records may still have imperfect/missing dead-stone or rule information.
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
    PROJECT, GtpEngine, REMOTE, device_serial, prepare_device,
    parse_raw_white_lead, run,
)

CORPUS = Path(__file__).with_name("real_scored_games.json")
COLS = ("backend", "game", "size", "moves", "source", "komi", "official_re",
        "official_white_lead", "nn_white_lead", "nn_white_win", "nn_white_loss",
        "nn_predicted_winner", "official_winner", "winner_correct",
        "margin_error", "absolute_error", "nn_ms", "terminal_passes", "sha256")
RESULT = re.compile(r"^([BW])\+([0-9]+(?:\.[0-9]+)?)$", re.I)
FLOAT = r"[-+]?(?:\d+(?:\.\d*)?|\.\d+)"


def sgf_nodes(text):
    """Read one SGF linear game; reject variations instead of guessing a branch.

    Unlike regex-only property parsing, this honors escaped brackets in SGF
    comments, CRLF, whitespace, and multiple properties with repeated values.
    """
    i, size, nodes = 0, len(text), []
    def spaces():
        nonlocal i
        while i < size and text[i].isspace():
            i += 1

    spaces()
    if i >= size or text[i] != "(":
        raise ValueError("Expected one SGF game tree starting with '('")
    i += 1
    while True:
        spaces()
        if i >= size:
            raise ValueError("Unclosed SGF game tree")
        if text[i] == ")":
            i += 1
            spaces()
            if i != size:
                raise ValueError("Multiple games or trailing garbage, split SGFs into files")
            if not nodes:
                raise ValueError("Empty SGF game")
            return nodes
        if text[i] == "(":
            raise ValueError("SGF variations are unsupported; supply a single main line")
        if text[i] != ";":
            raise ValueError("Expected SGF node at offset %d" % i)
        i += 1
        props = {}
        while True:
            spaces()
            if i >= size or text[i] in ";)":
                break
            if text[i] == "(":
                raise ValueError("SGF variations are unsupported")
            start = i
            while i < size and "A" <= text[i] <= "Z":
                i += 1
            key = text[start:i]
            if not key:
                raise ValueError("Invalid SGF property near offset %d" % i)
            spaces()
            if i >= size or text[i] != "[":
                raise ValueError("SGF property has no value: " + key)
            values = []
            while i < size and text[i] == "[":
                i += 1
                chars = []
                while i < size and text[i] != "]":
                    if text[i] == "\\":
                        i += 1
                        if i >= size:
                            raise ValueError("Unfinished SGF escape")
                        if text[i] == "\r" and i + 1 < size and text[i+1] == "\n":
                            i += 1
                        elif text[i] not in "\r\n":
                            chars.append(text[i])
                    else:
                        chars.append(text[i])
                    i += 1
                if i >= size:
                    raise ValueError("Unclosed SGF property: " + key)
                i += 1
                values.append("".join(chars))
                spaces()
            props.setdefault(key, []).extend(values)
        nodes.append(props)


def read_recorded_game(sgf_text, filename):
    nodes = sgf_nodes(sgf_text)
    root = nodes[0]
    def one(key, default=None):
        values = root.get(key, [])
        if len(values) > 1:
            raise ValueError("Multiple %s values" % key)
        return values[0] if values else default

    if one("GM", "1") != "1":
        raise ValueError("Not a Go SGF")
    try:
        size = int(one("SZ", "19"))
        komi = float(one("KM", "nan"))
        handicap = int(one("HA", "0"))
    except ValueError:
        raise ValueError("Non-numeric board size, komi, or handicap")
    if size not in (9, 13, 19) or not math.isfinite(komi):
        raise ValueError("Only 9/13/19 square boards with known komi supported")
    if handicap > 1 or root.get("AB") or root.get("AW") or root.get("AE"):
        raise ValueError("Handicap/setup SGFs excluded for an even-game comparison")
    rules = (one("RU") or "").strip().lower()
    if rules not in ("chinese", "chinese-kgs"):
        raise ValueError("Only explicitly Chinese-rules SGFs supported; found RU=%s" % rules)
    re_text = (one("RE") or "").strip().upper()
    match = RESULT.fullmatch(re_text)
    if not match:
        raise ValueError("Only numeric scored results B+N or W+N; RE=%s" % re_text)
    official_winner = match.group(1)
    margin = float(match.group(2))
    official_lead = margin if official_winner == "W" else -margin
    if margin == 0:
        raise ValueError("Draw/zero score omitted from win-rate tally")

    moves = []
    for node in nodes[1:]:
        if any(key in node for key in ("AB", "AW", "AE", "SZ", "KM", "RU", "RE")):
            raise ValueError("Midgame setup/rule editing unsupported")
        if "B" not in node and "W" not in node:
            continue
        if ("B" in node) == ("W" in node):
            raise ValueError("One move per node required")
        color = "B" if "B" in node else "W"
        value = node[color]
        if len(value) != 1:
            raise ValueError("Move has multiple values")
        point = value[0]
        if point and point.lower() != "tt":
            if (len(point) != 2 or
                    any(not ("a" <= c <= chr(ord("a")+size-1)) for c in point)):
                raise ValueError("Move outside board: " + point)
        moves.append((color, point))

    if len(moves) < (20 if size == 9 else 35 if size == 13 else 100):
        raise ValueError("Too few moves to be a real final position")
    for a, b in zip(moves, moves[1:]):
        if a[0] == b[0]:
            raise ValueError("Consecutive moves by same player; game may be malformed")
    terminal_passes = len(moves) >= 2 and all(not m[1] or m[1].lower() == "tt" for m in moves[-2:])
    return {
        "name": filename, "size": size, "komi": komi, "rules": "Chinese",
        "official_re": re_text, "official_white_lead": official_lead,
        "official_winner": official_winner, "moves": len(moves),
        "terminal_passes": terminal_passes, "black": one("PB", ""), "white": one("PW", ""),
    }


def collect_games(sgf_dir=None, limit=100):
    sources = []
    if sgf_dir is None:
        data = json.loads(CORPUS.read_text(encoding="utf-8"))
        for case in data["cases"]:
            sources.append((case["name"], case["sgf"], case["url"]))
    else:
        if not sgf_dir.is_dir():
            raise ValueError("Not a directory: " + str(sgf_dir))
        files = sorted(sgf_dir.rglob("*.sgf"))
        if not files:
            raise ValueError("No .sgf files in " + str(sgf_dir))
        for path in files:
            if path.stat().st_size > 256 * 1024:
                continue
            sources.append((str(path.relative_to(sgf_dir)), path.read_text(
                encoding="utf-8-sig", errors="replace"), str(path)))
    accepted, skipped = [], []
    for name, sgf, source in sources:
        if len(accepted) >= limit:
            break
        try:
            item = read_recorded_game(sgf, name)
            item.update(sgf=sgf, source=source,
                        sha256=hashlib.sha256(sgf.encode("utf-8")).hexdigest())
            accepted.append(item)
        except ValueError as err:
            skipped.append({"name": name, "reason": str(err)})
    if not accepted:
        raise ValueError("No qualified completed numeric Chinese-rule SGF games. Rejected: " +
                         json.dumps(skipped[:8], ensure_ascii=False))
    return accepted, skipped


def prepare_real_games(adb, serial, output, cases):
    path = output / "positions"
    path.mkdir(parents=True, exist_ok=True)
    manifest = {}
    run([adb, "-s", serial, "shell", "mkdir -p %s/positions" % REMOTE])
    for index, game in enumerate(cases):
        filename = "recorded_%03d.sgf" % index
        local = path / filename
        local.write_text(game["sgf"], encoding="utf-8")
        remote = "%s/positions/%s" % (REMOTE, filename)
        run([adb, "-s", serial, "push", local, remote])
        game["remote"] = remote
        manifest[filename] = {k: v for k, v in game.items() if k != "sgf"}
    (output / "fixtures.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def parse_nn_metrics(response):
    def field(key):
        match = re.search(r"(?:^|\s)" + re.escape(key) + r"\s+(" + FLOAT + r")(?=\s|$)", response)
        if not match:
            raise ValueError("KataGo raw NN missing " + key)
        value = float(match.group(1))
        if not math.isfinite(value):
            raise ValueError("Non-finite KataGo raw NN " + key)
        return value
    lead = parse_raw_white_lead(response)
    win = field("whiteWin")
    loss = field("whiteLoss")
    if not 0 <= win <= 1 or not 0 <= loss <= 1:
        raise ValueError("Invalid NN win/loss probabilities")
    return lead, win, loss


def compare_game(engine, game, backend):
    # Do not append two passes or rewrite komi: both would change the tested position.
    engine.command("loadsgf " + game["remote"])
    rules_raw, _ = engine.command("kata-get-rules")
    rules = json.loads(rules_raw)
    if rules.get("scoring") != "AREA" or rules.get("tax") != "NONE":
        raise ValueError("KataGo changed rules for %s: %s" % (game["name"], rules_raw))
    komi_raw, _ = engine.command("get_komi")
    if abs(float(komi_raw) - game["komi"]) > 0.001:
        raise ValueError("KataGo komi differs from recorded SGF for %s: %s" %
                         (game["name"], komi_raw))
    engine.command("clear_cache")
    nn_raw, nn_ms = engine.command("kata-raw-nn 0")
    lead, win, loss = parse_nn_metrics(nn_raw)
    official = game["official_white_lead"]
    pred_winner = "W" if lead > 0 else "B" if lead < 0 else "0"
    return {
        "backend": backend, "game": game["name"], "size": game["size"],
        "moves": game["moves"], "source": game["source"], "komi": game["komi"],
        "official_re": game["official_re"], "official_white_lead": official,
        "nn_white_lead": round(lead, 3), "nn_white_win": round(win, 6),
        "nn_white_loss": round(loss, 6),
        "nn_predicted_winner": pred_winner,
        "official_winner": game["official_winner"],
        "winner_correct": pred_winner == game["official_winner"],
        "margin_error": round(lead - official, 3),
        "absolute_error": round(abs(lead - official), 3),
        "nn_ms": round(nn_ms, 3),
        "terminal_passes": game["terminal_passes"],
        "sha256": game["sha256"],
    }


def summarize(rows):
    groups = {"all": rows}
    for size in sorted(set(r["size"] for r in rows)):
        groups[str(size) + "x" + str(size)] = [r for r in rows if r["size"] == size]
    result = {}
    for name, group in groups.items():
        if not group:
            continue
        errors = [r["absolute_error"] for r in group]
        result[name] = {
            "samples": len(group),
            "unique_games": len(set(r["sha256"] for r in group)),
            "correct": sum(r["winner_correct"] for r in group),
            "winner_accuracy": round(sum(r["winner_correct"] for r in group) / len(group), 4),
            "mae": round(statistics.mean(errors), 3),
            "median_ae": round(statistics.median(errors), 3),
            "max_ae": round(max(errors), 3),
            "within_1_5_points": sum(v <= 1.5 for v in errors),
            "nn_ms_mean": round(statistics.mean(r["nn_ms"] for r in group), 3),
            "pass_terminated": sum(r["terminal_passes"] for r in group),
        }
    return result


def main(argv=None):
    p = argparse.ArgumentParser(description="Real recorded-SGF outcome vs one raw KataGo NN pass")
    p.add_argument("--serial", default=os.getenv("ANDROID_SERIAL"))
    p.add_argument("--mode", choices=("gpu", "cpu", "both"), default="gpu")
    p.add_argument("--sgf-dir", type=Path, help="Your own real scored SGFs, supports 9/13/19; private by default")
    p.add_argument("--limit", type=int, default=100)
    p.add_argument("--repeats", type=int, default=2)
    p.add_argument("--threads", type=int, default=1)
    p.add_argument("--adb", default=os.getenv("ADB") or shutil.which("adb"))
    p.add_argument("--timeout", type=float, default=160)
    p.add_argument("--cpu-binary", type=Path)
    p.add_argument("--gpu-binary", type=Path, default=PROJECT / "build/katago_android_arm64_opencl/libkatago_exec_opencl.so")
    p.add_argument("--preflight", action="store_true", help="Validate and list SGFs, no ADB/device required")
    p.add_argument("--no-upload", action="store_true", help="Keep report locally only")
    p.add_argument("--upload", action="store_true", help="Explicitly allow uploading your own SGFs to public report branch")
    args = p.parse_args(argv)
    if args.repeats < 1 or args.limit < 1 or not 1 <= args.threads <= 64:
        p.error("Invalid repeats, limit, or threads")
    if args.sgf_dir and args.upload and args.no_upload:
        p.error("--upload and --no-upload conflict")
    try:
        cases, skipped = collect_games(args.sgf_dir, args.limit)
    except ValueError as err:
        p.error(str(err))
    counts = {size: sum(g["size"] == size for g in cases) for size in (9, 13, 19)}
    print("Validated %d recorded scored games (9=%d, 13=%d, 19=%d), skipped %d" %
          (len(cases), counts[9], counts[13], counts[19], len(skipped)), flush=True)
    for game in cases:
        print("  %-34s %2dx%d %-8s passes=%s" % (
            game["name"], game["size"], game["size"], game["official_re"],
            game["terminal_passes"]), flush=True)
    for drop in skipped[:10]:
        print("  SKIP %s: %s" % (drop["name"], drop["reason"]), file=sys.stderr)
    if args.preflight:
        return 0
    if not args.adb:
        p.error("adb not found")
    backends = ("cpu", "gpu") if args.mode == "both" else (args.mode,)
    binaries = {"cpu": args.cpu_binary or PROJECT / "build/katago_android_arm64_eigen/libkatago_exec.so",
                "gpu": args.gpu_binary}
    for backend in backends:
        if not binaries[backend].is_file():
            p.error("Missing %s native binary; build or provide --%s-binary" % (backend, backend))
    model = PROJECT / "app/src/main/assets/engine/10b.bin"
    config = PROJECT / "app/src/main/assets/engine/default_gtp.cfg"
    if not model.is_file() or not config.is_file():
        p.error("Missing 10b model/config")
    output = PROJECT / "build/real_sgf_nn" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output.mkdir(parents=True, exist_ok=True)
    try:
        serial = device_serial(args.adb, args.serial)
        gpu_egl = prepare_device(args.adb, serial, backends, binaries, model, config, output)
        prepare_real_games(args.adb, serial, output, cases)
        rows = []
        with (output / "endgame.csv").open("w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=COLS)
            writer.writeheader()
            for backend in backends:
                engine = GtpEngine(args.adb, serial, backend, args.threads, 8, 8.0,
                                   output, args.timeout, use_egl_mali=(backend == "gpu" and gpu_egl))
                try:
                    warmed = set()
                    for repeat in range(1, args.repeats + 1):
                        for game in cases:
                            if game["size"] not in warmed:
                                engine.command("loadsgf " + game["remote"])
                                engine.command("kata-raw-nn 0")
                                warmed.add(game["size"])
                            row = compare_game(engine, game, backend)
                            rows.append(row)
                            writer.writerow(row)
                            handle.flush()
                            print("%s %-28s #%d RE=%-7s NN=%+7.2f lead "
                                  "match=%-5s err=%5.2f NNtime=%6.1fms" % (
                                      backend, game["name"], repeat, row["official_re"],
                                      row["nn_white_lead"], row["winner_correct"],
                                      row["absolute_error"], row["nn_ms"]), flush=True)
                finally:
                    engine.close()
        report = {
            "type": "real_sgf_raw_nn_vs_recorded_numeric_score",
            "reference": "Original SGF RE numeric recorded score; not KataGo's own final_score",
            "caveats": ["Komi/rules preserved; no moves or passes are appended.",
                        "Scored game records are external labels, not independently re-counted.",
                        "19x19 pro game lacks two passes in SGF; recorded score may reflect agreed dead stones.",
                        "Results cannot establish general accuracy from a tiny convenience sample."],
            "device": serial, "model_sha256": hashlib.sha256(model.read_bytes()).hexdigest(),
            "backends": list(backends), "repeats": args.repeats,
            "from_user_sgf_dir": args.sgf_dir is not None,
            "skipped": skipped,
            "cases": [{k: v for k, v in x.items() if k != "sgf"} for x in cases],
            "rows": rows, "summary": summarize(rows),
        }
        (output / "endgame.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        for key, metrics in report["summary"].items():
            print("%-5s n=%2d games=%d winner=%d/%d MAE=%.2f max=%.2f ms=%.1f" % (
                key, metrics["samples"], metrics["unique_games"], metrics["correct"],
                metrics["samples"], metrics["mae"], metrics["max_ae"],
                metrics["nn_ms_mean"]), flush=True)
        print("Local report: " + str(output), flush=True)
        allowed = args.upload if args.sgf_dir is not None else not args.no_upload
        if allowed:
            from upload_endgame_results import upload_results
            print("Public report: " + upload_results(PROJECT.parent, output), flush=True)
        else:
            print("Report not uploaded (private/local SGFs remain local).", flush=True)
        return 0
    except (OSError, RuntimeError, ValueError, TimeoutError) as err:
        print("ERROR: %s (partial results: %s)" % (err, output), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
