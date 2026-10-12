#!/usr/bin/env python3
"""Compare KataGo's exact same SGF endgame positions on Android CPU and GPU.

Uses the existing native GTP benchmark's ADB/GtpEngine transport. It does NOT
test UI rendering or Java counting and does not install/change the APK.
SGFs contain static setup positions (AB/AW), not historical played move lists.
"""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import sys
from datetime import datetime

from benchmark_katago_genmove_android import GtpEngine, PROJECT, REMOTE, device_serial, run

# Board diagrams are top-to-bottom. An empty point is ".". Each color starts
# with two real enclosed eye points; tactical sets add vulnerable neighbors.
# These are controlled synthetic snapshots, NOT certified professional games.
POSITIONS = {
    "simple": {
        "description": "Two stable separated two-eye groups; basic score baseline",
        "rows": (
            "BBBBB....",
            "B.B.B....",
            "BBBBB....",
            ".........",
            ".........",
            ".........",
            "....WWWWW",
            "....W.W.W",
            "....WWWWW",
        ),
    },
    "territory": {
        "description": "Two alive corner groups with longer opposing borders",
        "rows": (
            "BBBBB....",
            "B.B.B..WW",
            "BBBBB.W.W",
            "..B...WWW",
            "..B...W..",
            "..BB.WW..",
            "....WWWWW",
            "....W.W.W",
            "....WWWWW",
        ),
    },
    "tactical": {
        "description": "Two alive corner groups plus weak central groups; unsettled status",
        "rows": (
            "BBBBB.WWW",
            "B.B.B.W.W",
            "BBBBB.WWW",
            "..B.B.W..",
            "..BWWBW..",
            "..BB.WWW.",
            "....WWWWW",
            "....W.W.W",
            "....WWWWW",
        ),
    },
}
DETAIL_FIELDS = ["backend", "fixture", "repeat", "threads", "sha256", "black_stones",
                 "white_stones", "score", "dead", "raw_white_lead", "rules",
                 "local_white_lead", "score_minus_local_lead",
                 "load_sgf_ms", "final_score_ms", "dead_stones_ms",
                 "raw_nn_ms", "total_ms"]
SCORE = re.compile(r"(?:[BW]\+[0-9]+(?:\.[0-9]+)?|0)", re.I)
VERTEX = re.compile(r"[A-HJ-T](?:[1-9]|1[0-9])", re.I)


def inspect_grid(rows):
    size = len(rows)
    if size not in (9, 13, 19) or any(len(row) != size or any(c not in ".BW" for c in row)
                                          for row in rows):
        raise ValueError("Fixtures must be 9x9, 13x13, or 19x19 grids of B/W/. cells")
    stones = {(x, y): cell for y, row in enumerate(rows)
              for x, cell in enumerate(row) if cell != "."}
    if min(sum(c == "B" for c in stones.values()),
           sum(c == "W" for c in stones.values())) < 5:
        raise ValueError("Each color must have at least five stones")

    def adjacent(point):
        x, y = point
        return [(a, b) for a, b in ((x-1, y), (x+1, y), (x, y-1), (x, y+1))
                if 0 <= a < size and 0 <= b < size]

    seen = set()
    for start, color in stones.items():
        if start in seen:
            continue
        stack, liberties = [start], set()
        while stack:
            point = stack.pop()
            if point in seen:
                continue
            seen.add(point)
            for n in adjacent(point):
                if n not in stones:
                    liberties.add(n)
                elif stones[n] == color and n not in seen:
                    stack.append(n)
        if not liberties:
            raise ValueError("A setup group has zero liberties: %s" % (start,))
    eyes = {color: [p for p in ((x, y) for y in range(size) for x in range(size))
                    if p not in stones and len(adjacent(p)) >= 2
                    and all(stones.get(n) == color for n in adjacent(p))]
            for color in ("B", "W")}
    if any(len(eyes[c]) < 2 for c in ("B", "W")):
        raise ValueError("Each color must have two simple enclosed eye points")
    return {"size": size, "black_stones": sum(c == "B" for c in stones.values()),
            "white_stones": sum(c == "W" for c in stones.values()),
            "black_eyes": eyes["B"], "white_eyes": eyes["W"]}


def make_sgf(rows, description):
    info = inspect_grid(rows)
    fields = ["FF[4]", "GM[1]", "SZ[%d]" % info["size"], "RU[Chinese]", "KM[5.5]", "PL[B]"]
    for color, property_name in (("B", "AB"), ("W", "AW")):
        coords = ["[%s%s]" % (chr(97+x), chr(97+y))
                  for y, row in enumerate(rows) for x, cell in enumerate(row) if cell == color]
        fields.append(property_name + "".join(coords))
    # All setup stones, no moves or final passes: KataGo must estimate a score.
    return "(;" + "".join(fields) + ")\n", info


def parse_score(text):
    value = text.strip().upper()
    if not SCORE.fullmatch(value):
        raise ValueError("Invalid final_score: %r" % text[:80])
    return value


def parse_dead(text, size=9):
    values = text.split()
    if any(not VERTEX.fullmatch(v) or int(re.search(r"\d+", v).group()) > size
           or "ABCDEFGHJKLMNOPQRST".index(v[0].upper()) >= size for v in values):
        raise ValueError("Invalid dead-stone vertices: %s" % text[:100])
    return " ".join(sorted(v.upper() for v in values))


def parse_raw_white_lead(text):
    match = re.search(r"(?:^|\s)whiteLead\s+([-+]?(?:\d+(?:\.\d*)?|\.\d+))(?=\s|$)", text)
    if not match:
        raise ValueError("kata-raw-nn missing numeric whiteLead")
    return float(match.group(1))


def white_lead_from_gtp(score):
    score = parse_score(score)
    return 0.0 if score == "0" else float(score[2:]) * (1 if score.startswith("W+") else -1)


def chinese_area_score(rows, dead_vertices, komi=5.5):
    """Replicate GoBoard.countChineseScore's flood fill on an SGF setup grid.

    Unlike KataGo's final_score, this treats its predicted dead stones as
    removed and then counts unequivocal single-color bordered empty regions.
    Differences are informative, but not automatically a KataGo bug.
    """
    size = len(rows)
    live = {(x, y): c for y, row in enumerate(rows) for x, c in enumerate(row)
            if c in "BW"}
    dead = []
    for vertex in dead_vertices.split():
        x = "ABCDEFGHJKLMNOPQRST".index(vertex[0].upper())
        y = size - int(vertex[1:])
        if (x, y) not in live:
            raise ValueError("KataGo marked an empty point as dead: " + vertex)
        dead.append((x, y))
    for point in set(dead):
        live.pop(point)

    def adjacent(p):
        x, y = p
        return [(a, b) for a, b in ((x-1, y), (x+1, y), (x, y-1), (x, y+1))
                if 0 <= a < size and 0 <= b < size]

    territory = {"B": 0, "W": 0}
    visited = set()
    for y in range(size):
        for x in range(size):
            start = (x, y)
            if start in live or start in visited:
                continue
            stack, area, borders = [start], 0, set()
            while stack:
                p = stack.pop()
                if p in visited:
                    continue
                visited.add(p)
                area += 1
                for neighbour in adjacent(p):
                    if neighbour in live:
                        borders.add(live[neighbour])
                    elif neighbour not in visited:
                        stack.append(neighbour)
            if len(borders) == 1:
                territory[next(iter(borders))] += area
    black = sum(v == "B" for v in live.values()) + territory["B"]
    white = sum(v == "W" for v in live.values()) + territory["W"] + komi
    return {"black_points": black, "white_points": white,
            "black_territory": territory["B"], "white_territory": territory["W"],
            "dead_black": sum(rows[p[1]][p[0]] == "B" for p in set(dead)),
            "dead_white": sum(rows[p[1]][p[0]] == "W" for p in set(dead)),
            "white_lead": round(white-black, 3)}


def prepare_device(adb, serial, backends, binaries, model, config, report_dir):
    run([adb, "-s", serial, "shell", "mkdir -p %s/.katago %s/gtp_logs %s/positions" %
         (REMOTE, REMOTE, REMOTE)])
    for source, target in ((model, "10b.bin"), (config, "default_gtp.cfg")):
        run([adb, "-s", serial, "push", source, "%s/%s" % (REMOTE, target)])
    gpu_egl_first = False
    for backend in backends:
        binary = binaries[backend]
        run([adb, "-s", serial, "push", binary, "%s/katago_%s" % (REMOTE, backend)])
        run([adb, "-s", serial, "shell", "chmod 755 %s/katago_%s" % (REMOTE, backend)])
        if backend == "gpu":
            dynamic = run(["readelf", "-W", "-d", binary], capture=True)
            if "Shared library: [libGLES_mali.so]" in dynamic:
                version = run(["readelf", "-W", "-V", binary], capture=True)
                if re.search(r"Name: OPENCL_[0-9]", version):
                    run([adb, "-s", serial, "shell",
                         "cp /vendor/lib64/libOpenCL.so %s/libGLES_mali.so" % REMOTE])
                else:
                    gpu_egl_first = True
    return gpu_egl_first


def load_fixtures(adb, serial, report_dir, selected, positions=None):
    if positions is None:
        positions = POSITIONS
    folder = report_dir / "positions"
    folder.mkdir(parents=True, exist_ok=True)
    manifest = {}
    for name in selected:
        fixture = positions[name]
        sgf, info = make_sgf(fixture["rows"], fixture["description"])
        local = folder / (name + ".sgf")
        local.write_text(sgf, encoding="ascii")
        digest = hashlib.sha256(sgf.encode("ascii")).hexdigest()
        remote_name = "%s/positions/%s.sgf" % (REMOTE, name)
        run([adb, "-s", serial, "push", local, remote_name])
        manifest[name] = dict(description=fixture["description"], grid=fixture["rows"],
                              focus=fixture.get("focus", ""), expected_atari=fixture.get("atari", ()),
                              sha256=digest, remote=remote_name, **info)
    (report_dir / "fixtures.json").write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return manifest


def measure_case(engine, backend, name, fixture, repeat, threads, max_time):
    # loadsgf changes the position and can also change the rule settings; read
    # back the rules *after* the load. Clear MCTS cache consistently each time.
    _, setup_ms = engine.command("loadsgf " + fixture["remote"])
    rules, _ = engine.command("kata-get-rules")
    parsed_rules = json.loads(rules)
    if parsed_rules.get("scoring") != "AREA" or parsed_rules.get("tax") != "NONE":
        raise RuntimeError("Unexpected rules after loadsgf: " + rules)
    if abs(float(parsed_rules.get("komi", 5.5)) - 5.5) > 1e-6:
        raise RuntimeError("Unexpected komi after loadsgf: " + rules)
    engine.command("clear_cache")
    score_text, score_ms = engine.command("final_score")
    dead_text, dead_ms = engine.command("final_status_list dead")
    raw_text, raw_ms = engine.command("kata-raw-nn 0")
    score = parse_score(score_text)
    dead = parse_dead(dead_text)
    lead = parse_raw_white_lead(raw_text)
    local = chinese_area_score(fixture["grid"], dead)
    return dict(backend=backend, fixture=name, repeat=repeat, threads=threads,
                sha256=fixture["sha256"], black_stones=fixture["black_stones"],
                white_stones=fixture["white_stones"], score=score, dead=dead,
                raw_white_lead=round(lead, 3), rules=rules,
                local_white_lead=local["white_lead"],
                score_minus_local_lead=round(white_lead_from_gtp(score)-local["white_lead"], 3),
                load_sgf_ms=round(setup_ms, 3),
                final_score_ms=round(score_ms, 3),
                dead_stones_ms=round(dead_ms, 3), raw_nn_ms=round(raw_ms, 3),
                total_ms=round(score_ms+dead_ms+raw_ms, 3))


def summarize(rows, selected, backends):
    data = {}
    for name in selected:
        data[name] = {}
        for backend in backends:
            group = [r for r in rows if r["fixture"] == name and r["backend"] == backend]
            if group:
                data[name][backend] = {
                    field: round(statistics.mean(float(r[field]) for r in group), 3)
                    for field in ("final_score_ms", "dead_stones_ms", "raw_nn_ms", "total_ms")
                }
                data[name][backend]["samples"] = len(group)
                data[name][backend]["scores"] = sorted(set(r["score"] for r in group))
    return data


def main(argv=None):
    parser = argparse.ArgumentParser(description="Benchmark fixed SGF endgame positions on Android CPU/GPU")
    parser.add_argument("--mode", choices=("both", "cpu", "gpu"), default="both")
    parser.add_argument("--positions", default="simple,territory,tactical")
    parser.add_argument("--repeats", type=int, default=2)
    parser.add_argument("--threads", type=int, default=1)
    parser.add_argument("--visits", type=int, default=8)
    parser.add_argument("--max-time", type=float, default=8.0)
    parser.add_argument("--timeout", type=float, default=150)
    parser.add_argument("--serial", default=os.getenv("ANDROID_SERIAL"))
    parser.add_argument("--adb", default=os.getenv("ADB") or shutil.which("adb"))
    parser.add_argument("--cpu-binary", type=Path)
    parser.add_argument("--gpu-binary", type=Path,
                        default=Path(os.getenv("KATAGO_BENCH_GPU_BINARY") or str(
                            PROJECT / "build/katago_android_arm64_opencl/libkatago_exec_opencl.so")))
    parser.add_argument("--report-dir", type=Path)
    parser.add_argument("--no-upload", action="store_true", help="Do not upload report to the public ui-test-results branch")
    args = parser.parse_args(argv)
    names = [v.strip() for v in args.positions.split(",") if v.strip()]
    if not names or len(set(names)) != len(names) or any(v not in POSITIONS for v in names):
        parser.error("positions must be distinct and chosen from: " + ",".join(POSITIONS))
    if args.repeats < 1 or not 1 <= args.threads <= 64 or args.visits < 1 or (
            args.max_time <= 0 or args.timeout <= 0):
        parser.error("Invalid repeats, threads, visits, max-time or timeout")
    if not args.adb:
        parser.error("adb not found")
    backends = ["cpu", "gpu"] if args.mode == "both" else [args.mode]
    cpu = args.cpu_binary or (PROJECT / "app/build/generated/katagoJniLibs/arm64-v8a/libkatago_exec.so")
    if not cpu.is_file() and args.cpu_binary is None:
        cpu = Path(os.getenv("KATAGO_WORKDIR", str(Path.home() / ".cache/goengine_katago"))) / "build_android_arm64_eigen/katago"
    binaries = {"cpu": cpu, "gpu": args.gpu_binary}
    model = PROJECT / "app/src/main/assets/engine/10b.bin"
    config = PROJECT / "app/src/main/assets/engine/default_gtp.cfg"
    output = args.report_dir or PROJECT / "build/endgame_benchmark" / datetime.now().strftime("%Y%m%d_%H%M%S")
    output.mkdir(parents=True, exist_ok=True)
    try:
        serial = device_serial(args.adb, args.serial)
        for backend in backends:
            if not binaries[backend].is_file():
                raise RuntimeError("%s executable missing: %s" % (backend, binaries[backend]))
        if not model.is_file() or not config.is_file():
            raise RuntimeError("Missing 10b model/config")
        print("RK3588 GTP endgame; serial=%s backends=%s threads=%d repeats=%d" %
              (serial, backends, args.threads, args.repeats), flush=True)
        print("Report: %s" % output, flush=True)
        gpu_egl = prepare_device(args.adb, serial, backends, binaries, model, config, output)
        fixtures = load_fixtures(args.adb, serial, output, names)
        rows = []
        engine_versions = {}
        csv_file = output / "endgame.csv"
        with csv_file.open("w", newline="", encoding="utf-8") as target:
            writer = csv.DictWriter(target, fieldnames=DETAIL_FIELDS)
            writer.writeheader()
            for backend in backends:
                print("\n-- %s --" % backend.upper(), flush=True)
                engine = GtpEngine(args.adb, serial, backend, args.threads,
                                   args.visits, args.max_time, output, args.timeout,
                                   use_egl_mali=(backend == "gpu" and gpu_egl))
                try:
                    engine_name, _ = engine.command("name")
                    engine_version, _ = engine.command("version")
                    engine_versions[backend] = {"name": engine_name, "version": engine_version}
                    print("  KataGo: %s %s" % (engine_name, engine_version), flush=True)
                    engine.command("boardsize 9")
                    engine.command("komi 5.5")
                    engine.command("kata-set-rules chinese")
                    engine.command("kata-raw-nn 0")  # Untimed GPU/CPU warm-up.
                    for repeat in range(1, args.repeats + 1):
                        for name in names:
                            row = measure_case(engine, backend, name, fixtures[name], repeat,
                                               args.threads, args.max_time)
                            rows.append(row)
                            writer.writerow(row)
                            target.flush()
                            print("  %s #%d score=%s localLead=%+.1f diff=%+.1f total=%.1f ms (score=%.1f dead=%.1f raw=%.1f)" %
                                  (name, repeat, row["score"], row["local_white_lead"],
                                   row["score_minus_local_lead"], row["total_ms"],
                                   row["final_score_ms"], row["dead_stones_ms"], row["raw_nn_ms"]),
                                  flush=True)
                finally:
                    engine.close()
        grouped = summarize(rows, names, backends)
        warnings = []
        if len(backends) == 2 and (
                engine_versions["cpu"]["name"] != engine_versions["gpu"]["name"] or
                engine_versions["cpu"]["version"] != engine_versions["gpu"]["version"]):
            warnings.append("CPU and GPU binaries report different KataGo name/version; comparisons may not be apples-to-apples")
        for row in rows:
            if abs(row["score_minus_local_lead"]) >= 0.5:
                warnings.append("%s/%s #%d: GTP final_score differs from local Chinese count by %+.1f points" %
                                (row["backend"], row["fixture"], row["repeat"], row["score_minus_local_lead"]))
        for name in names:
            if "cpu" in grouped[name] and "gpu" in grouped[name]:
                cpu_scores = grouped[name]["cpu"]["scores"]
                gpu_scores = grouped[name]["gpu"]["scores"]
                if cpu_scores != gpu_scores:
                    warnings.append("%s: CPU/GPU final_score differs (%s / %s); numerical/search variations possible" %
                                    (name, cpu_scores, gpu_scores))
        report = {"schema": 1, "device": serial, "type": "native_gtp_sgf_setup",
                  "note": "Synthetic fixed SGF snapshots; not Android UI/JNI Human SL; GTP command wall time via ADB.",
                  "same_model": str(model), "model_sha256": hashlib.sha256(model.read_bytes()).hexdigest(),
                  "binaries": {b: {"path": str(binaries[b]), "sha256": hashlib.sha256(binaries[b].read_bytes()).hexdigest()}
                               for b in backends},
                  "engines": engine_versions, "rules": "Chinese AREA, komi 5.5",
                  "threads": args.threads, "max_visits": args.visits,
                  "max_time_s": args.max_time, "repeats": args.repeats,
                  "fixtures": fixtures, "rows": rows, "summary": grouped, "warnings": warnings}
        (output / "endgame.json").write_text(
            json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        for name in names:
            print("\n%s:" % name)
            for backend, values in grouped[name].items():
                print("  %s n=%d total=%.1f score=%.1f dead=%.1f raw=%.1f ms; %s" %
                      (backend, values["samples"], values["total_ms"], values["final_score_ms"],
                       values["dead_stones_ms"], values["raw_nn_ms"], values["scores"]))
        for message in warnings:
            print("WARNING: " + message, file=sys.stderr)
        print("\nFinished: %s" % output)
        if not args.no_upload:
            from upload_endgame_results import upload_results
            link = upload_results(PROJECT.parent, output)
            print("Public benchmark report: " + link, flush=True)
        return 0
    except (OSError, ValueError, RuntimeError, TimeoutError, json.JSONDecodeError,
            subprocess.SubprocessError) as error:
        print("ERROR: %s; partial files in %s" % (error, output), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
