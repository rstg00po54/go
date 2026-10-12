#!/usr/bin/env python3
"""RK3588 KataGo end-to-end AI response benchmark using real Android logcat times.

Examples (run from GoEngine-java):
    python3 tools/bench_app_ai.py -s 8719e18a71a2a66c --moves 4
    python3 tools/bench_app_ai.py -s 8719e18a71a2a66c --moves 6 --warmup-moves 1

The runner reuses test_app_ui.py. These times are NOT pure NN inference or
OpenCL kernel timings: they include app move handling, GTP and AI search.
"""
import csv
import datetime
import io
import json
import math
import re
import statistics
import subprocess
import sys
from pathlib import Path

MAIN_LOG = re.compile(
    r"^(?P<stamp>\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+"
    r"[VDIWEF]/MainActivity\(\s*\d+\):\s*(?P<message>.*)$"
)
START = re.compile(r"Saved new-game preference boardSize=(\d+) backend=(CPU|GPU)")
BACKEND = re.compile(r"Game backend requested=(CPU|GPU) actual=(.*?) ready=(true|false)")
TAP = re.compile(r"onBoardTap in, x=(\d+), y=(\d+)")
AI = re.compile(r"handleAiMove in, move=([^,\s]+)")


def log_time(stamp, year):
    moment = datetime.datetime.strptime(f"{year}-{stamp}", "%Y-%m-%d %H:%M:%S.%f")
    # Integer microseconds avoid catastrophic cancellation when subtracting
    # two large absolute floating-point timestamps (~1e11 seconds). Android
    # -v time is millisecond-resolution, so the difference is exact.
    return (((moment.toordinal() * 24 + moment.hour) * 60 + moment.minute) *
            60 + moment.second) * 1000000 + moment.microsecond


def parse_cases(log_text, year=None):
    """Pair accepted taps with AI returns and the subsequent player-ready render.

    A tap is accepted only if MainActivity renders "AI 思考中" after it.
    Multiple thinking renders belong to the same tap. An illegal/occupied tap
    never produces an accepted sample. Every case is bounded by the app's
    Saved new-game preference log, independent of the Python runner clock.
    """
    if year is None:
        year = datetime.datetime.now().year
    cases, current, pending_tap, pending_move = [], None, None, None

    def flush_tap():
        nonlocal pending_tap, pending_move
        if current is not None:
            if pending_tap is not None:
                current["rejected_taps"] += 1
            if pending_move is not None:
                current["incomplete_moves"] += 1
        pending_tap, pending_move = None, None

    for line in log_text.splitlines():
        found = MAIN_LOG.match(line)
        if found is None:
            continue
        message = found.group("message")
        when = log_time(found.group("stamp"), year)
        start = START.search(message)
        if start:
            flush_tap()
            current = {
                "case": len(cases) + 1, "requested": start.group(2).lower(),
                "size": int(start.group(1)), "start_time": when,
                "actual": None, "engine_ready": False, "startup_ms": None,
                "rejected_taps": 0, "incomplete_moves": 0, "moves": []
            }
            cases.append(current)
            continue
        if current is None:
            continue
        backend = BACKEND.search(message)
        if backend:
            current["actual"] = backend.group(2).strip()
            current["engine_ready"] = backend.group(3) == "true"
            current["startup_ms"] = round((when - current["start_time"]) / 1000, 3)
            continue
        tap = TAP.search(message)
        if tap:
            flush_tap()
            pending_tap = {"time": when, "coord": f"({tap.group(1)},{tap.group(2)})"}
            continue
        if "render in, message=AI 思考中" in message and pending_tap is not None and pending_move is None:
            pending_move = {"tap_time": pending_tap["time"], "coord": pending_tap["coord"],
                            "accepted_time": when}
            pending_tap = None
            continue
        ai = AI.search(message)
        if ai is not None and pending_move is not None and "ai_return_time" not in pending_move:
            pending_move["ai_return_time"] = when
            pending_move["ai_move"] = ai.group(1)
            continue
        if pending_move is not None and "ai_return_time" in pending_move and (
                "render in, message=轮到你了" in message or
                "render in, message=AI 停一手，你下" in message):
            tap_time = pending_move["tap_time"]
            current["moves"].append({
                "move_index": len(current["moves"]) + 1,
                "coordinate": pending_move["coord"],
                "ai_move": pending_move["ai_move"],
                "tap_to_ai_ms": round((pending_move["ai_return_time"] - tap_time) / 1000, 3),
                "tap_to_ready_ms": round((when - tap_time) / 1000, 3),
                "ai_search_from_accept_ms": round(
                    (pending_move["ai_return_time"] - pending_move["accepted_time"]) / 1000, 3)
            })
            pending_move = None
    flush_tap()
    return cases


def stats(values):
    if not values:
        return None
    ordered = sorted(values)
    # Nearest-rank P95. For n <= 20 this can equal the maximum.
    return {
        "samples": len(ordered), "average_ms": round(statistics.mean(ordered), 3),
        "median_ms": round(statistics.median(ordered), 3),
        "p95_ms": round(ordered[math.ceil(0.95 * len(ordered)) - 1], 3),
        "min_ms": round(ordered[0], 3), "max_ms": round(ordered[-1], 3)
    }


def analyze(log_text, requested_backends, expected_moves, warmup_moves=0, year=None, size=None):
    """Return validated per-case and per-backend metrics or raise ValueError."""
    cases = parse_cases(log_text, year=year)
    if not cases:
        raise ValueError("logcat 缺少 Saved new-game preference，无法测量实际 AI 耗时")
    if len(cases) != len(requested_backends):
        raise ValueError(f"预期 {len(requested_backends)} 局，日志实际只有 {len(cases)} 局")
    if warmup_moves < 0 or expected_moves <= warmup_moves:
        raise ValueError("warmup_moves 必须小于每局 moves，且不能为负数")
    by_backend = {"cpu": [], "gpu": []}
    for index, (case, expected_backend) in enumerate(zip(cases, requested_backends), start=1):
        if case["requested"] != expected_backend:
            raise ValueError(f"第 {index} 局期望 {expected_backend}，实际请求 {case['requested']}")
        if size is not None and case["size"] != size:
            raise ValueError(f"第 {index} 局期望 {size}路，实际 {case['size']}路")
        actual = case["actual"] or ""
        if not case["engine_ready"] or not actual.startswith(
                "GPU/OpenCL JNI" if expected_backend == "gpu" else "CPU/Eigen JNI"):
            raise ValueError(f"第 {index} 局后端启动不符：{actual}, ready={case['engine_ready']}")
        if len(case["moves"]) != expected_moves:
            raise ValueError(f"第 {index} 局预期 {expected_moves} 手，"
                             f"只找到 {len(case['moves'])} 手有完整日志的有效 AI 回棋")
        case["warmup_moves"] = warmup_moves
        case["response"] = stats([m["tap_to_ai_ms"] for m in case["moves"][warmup_moves:]])
        case["player_ready"] = stats([m["tap_to_ready_ms"] for m in case["moves"][warmup_moves:]])
        by_backend[expected_backend].extend(case["moves"][warmup_moves:])
        del case["start_time"]
    aggregates = {}
    for backend, moves in by_backend.items():
        if moves:
            aggregates[backend] = {
                "response": stats([m["tap_to_ai_ms"] for m in moves]),
                "player_ready": stats([m["tap_to_ready_ms"] for m in moves])
            }
    return {
        "schema": 1, "measurement": "Android MainActivity wall-clock event timestamps",
        "meaning": "tap to handleAiMove includes JNI/GTP, move sync and AI search; NOT isolated neural-network inference",
        "p95_method": "nearest-rank", "expected_moves_per_case": expected_moves,
        "warmup_moves_per_case": warmup_moves,
        "cases": cases, "by_backend": aggregates
    }


def format_summary(result):
    lines = ["KataGo Android AI response benchmark (milliseconds)",
             "Timing: onBoardTap -> handleAiMove, and onBoardTap -> player-ready render",
             "Not a pure KataGo neural-network inference benchmark.",
             "P95: nearest-rank (with small N, P95 may equal maximum).", ""]
    for case in result["cases"]:
        m = case["response"]
        lines.append(
            f"Case {case['case']} {case['requested'].upper()} "
            f"startup={case['startup_ms']}ms accepted={len(case['moves'])} "
            f"rejected_taps={case['rejected_taps']} "
            f"ignored_warmup={case['warmup_moves']} "
            f"avg={m['average_ms']:.1f}ms p95={m['p95_ms']:.1f}ms "
            f"max={m['max_ms']:.1f}ms")
        for move in case["moves"]:
            lines.append(f"  {move['move_index']:2d}. {move['coordinate']:>6} -> "
                         f"{move['ai_move']:>5}: AI={move['tap_to_ai_ms']:.1f}ms "
                         f"ready={move['tap_to_ready_ms']:.1f}ms")
    lines.append("")
    for backend, values in result["by_backend"].items():
        m, p = values["response"], values["player_ready"]
        lines.append(f"{backend.upper()} total n={m['samples']}: "
                     f"avg={m['average_ms']:.1f}ms median={m['median_ms']:.1f}ms "
                     f"p95={m['p95_ms']:.1f}ms max={m['max_ms']:.1f}ms; "
                     f"ready_avg={p['average_ms']:.1f}ms")
    return "\n".join(lines) + "\n"


def export_csv(result):
    output = io.StringIO()
    writer = csv.writer(output)
    writer.writerow(["case", "backend", "size", "move_index", "warmup",
                     "coordinate", "ai_move", "tap_to_ai_ms", "tap_to_ready_ms",
                     "ai_search_from_accept_ms"])
    for case in result["cases"]:
        for move in case["moves"]:
            writer.writerow([case["case"], case["requested"], case["size"],
                             move["move_index"], move["move_index"] <= case["warmup_moves"],
                             move["coordinate"], move["ai_move"], move["tap_to_ai_ms"],
                             move["tap_to_ready_ms"], move["ai_search_from_accept_ms"]])
    return output.getvalue()


def main():
    import argparse
    parser = argparse.ArgumentParser(description="Run actual CPU/GPU KataGo AI response benchmark")
    parser.add_argument("--analyze", metavar="LOGCAT", help="Only analyze an existing -v time logcat file")
    parser.add_argument("--out", help="Destination for offline analysis JSON")
    parser.add_argument("--moves", type=int, default=4)
    parser.add_argument("--backends", default="cpu,gpu,cpu")
    parser.add_argument("--rounds", type=int, default=1)
    parser.add_argument("--warmup-moves", type=int, default=0)
    parser.add_argument("--size", type=int, default=9)
    args, extras = parser.parse_known_args()
    if args.analyze:
        if extras:
            parser.error("离线模式有未知参数：" + " ".join(extras))
        requested = [b.strip().lower() for b in args.backends.split(",")] * args.rounds
        try:
            result = analyze(Path(args.analyze).read_text(encoding="utf-8", errors="replace"),
                             requested, args.moves, args.warmup_moves, size=args.size)
        except ValueError as exc:
            parser.error(str(exc))
        print(format_summary(result))
        if args.out:
            Path(args.out).write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                                      encoding="utf-8")
        return 0
    # Preserve all existing device, upload, and safety switches; the benchmark
    # flag only adds log analysis after the UI tests have completed successfully.
    cmd = [sys.executable, str(Path(__file__).with_name("test_app_ui.py")),
           "--benchmark", "--moves", str(args.moves),
           "--backends", args.backends, "--rounds", str(args.rounds),
           "--size", str(args.size), "--warmup-moves", str(args.warmup_moves)] + extras
    return subprocess.call(cmd)


if __name__ == "__main__":
    sys.exit(main())
