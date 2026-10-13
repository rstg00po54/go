#!/usr/bin/env python3
"""Real SGF -> Android raw NN ownership -> actual Java SmartReferee/EndgameAdjudicator.

This is diagnostic triage, NOT ground-truth dead-stone classification. Recorded
SGF RE is a result label; it does not certify which stones were dead. No uploads.
"""
import argparse
import collections
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

from benchmark_real_sgf_nn_android import (
    PROJECT, collect_games, parse_nn_metrics, prepare_real_games, sgf_nodes,
)
from benchmark_endgame_android import GtpEngine, REMOTE, device_serial, prepare_device

GAME_FILES = ("GoBoard.java", "Intersection.java", "Move.java", "Point.java",
              "StoneColor.java", "SmartReferee.java", "EndgameAdjudicator.java")
JAVA = r"""package com.badukai.game;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RealRefereeRunner {
    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
        String line, name = null;
        GoBoard board = null;
        float[] ownership = null;
        int move = 0, size = 0;
        while ((line = in.readLine()) != null) {
            String[] fields = line.split("\t", -1);
            if ("GAME".equals(fields[0])) {
                name = fields[1]; size = Integer.parseInt(fields[2]);
                board = new GoBoard(size); ownership = null; move = 0;
            } else if ("MOVE".equals(fields[0])) {
                if (board == null) throw new IllegalStateException("Missing GAME");
                StoneColor color = "B".equals(fields[1]) ? StoneColor.BLACK : StoneColor.WHITE;
                String point = fields[2];
                if (point.isEmpty() || "tt".equalsIgnoreCase(point))
                    board.playMove(new Move.Pass(color));
                else {
                    Point p = new Point(point.charAt(0)-'a',point.charAt(1)-'a');
                    if (!board.isLegalMove(p,color))
                        throw new IllegalArgumentException(name + " illegal move " + ++move + " " + fields[1] + " " + point);
                    board.playMove(new Move.Stone(p,color));
                }
                move++;
            } else if ("OWN".equals(fields[0])) {
                String[] parts = fields[1].split(" ");
                if (parts.length != size*size) throw new IllegalArgumentException("NN length");
                ownership = new float[parts.length];
                for (int i=0;i<parts.length;i++) ownership[i] = Float.parseFloat(parts[i]);
            } else if ("END".equals(fields[0])) {
                if (board == null || ownership == null) throw new IllegalArgumentException("Incomplete case");
                SmartReferee.Analysis dead = SmartReferee.analyze(board,ownership);
                EndgameAdjudicator.Result result = EndgameAdjudicator.analyze(board,ownership);
                GoBoard.FinalScore count = board.countChineseScore(dead.suggestedDead,
                        Double.parseDouble(fields[1]),0);
                StringBuilder reasons = new StringBuilder(), moves = new StringBuilder(), locations = new StringBuilder();
                for (EndgameAdjudicator.Finding finding : result.findings) {
                    if (reasons.length()>0) reasons.append(",");
                    reasons.append(finding.reason.name());
                    if (locations.length()>0) locations.append("|");
                    locations.append(finding.reason.name()).append(":")
                             .append(SmartReferee.toGtpVertices(finding.points,size).replace(' ',','));
                    if (finding.suggestedMove != null) {
                        if (moves.length()>0) moves.append(",");
                        moves.append(finding.suggestedMove.toGtp(size));
                    }
                }
                System.out.printf(Locale.US,"RESULT\t%s\t%s\t%d\t%d\t%d\t%.3f\t%s\t%s\t%d\t%s\t%s\t%s\t%d\t%s%n",
                        name,result.verdict,dead.suggestedGroups,dead.reviewGroups,
                        result.groups,count.whitePoints-count.blackPoints,reasons,moves,
                        board.isGameOver()?1:0,
                        SmartReferee.toGtpVertices(dead.suggestedDead,size).replace(' ',','),
                        SmartReferee.toGtpVertices(dead.needsReview,size).replace(' ',','),
                        locations,dead.vulnerableGroups,
                        SmartReferee.toGtpVertices(dead.vulnerableReview,size).replace(' ',','));
                System.out.flush();
                name = null; board = null;
            } else throw new IllegalArgumentException("Unknown input: " + fields[0]);
        }
    }
}
"""
STUB = """package com.badukai.util;
public final class DebugLog {
    public static void v(String a, String b) {}
    public static void enter(String a, String b) {}
}
"""


def compile_java(destination):
    game = PROJECT / "app/src/main/java/com/badukai/game"
    source = destination / "RealRefereeRunner.java"
    stub = destination / "DebugLog.java"
    source.write_text(JAVA, encoding="utf-8")
    stub.write_text(STUB, encoding="utf-8")
    classes = destination / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(classes), str(source),
                    str(stub)] + [str(game / name) for name in GAME_FILES], check=True)
    return classes


def ownership_from_raw(raw, size):
    parts = raw.split()
    try:
        offset = parts.index("whiteOwnership") + 1
    except ValueError:
        raise ValueError("kata-raw-nn has no whiteOwnership array")
    if len(parts) - offset < size * size:
        raise ValueError("Incomplete NN ownership array")
    values = [float(v) for v in parts[offset:offset + size*size]]
    if any(not math.isfinite(v) or abs(v) > 1.01 for v in values):
        raise ValueError("Invalid ownership values")
    return values


def check_engine_position(engine, case):
    engine.command("loadsgf " + case["remote"])
    board, _ = engine.command("showboard")
    number = re.search(r"MoveNum:\s*(\d+)", board)
    if number is None or int(number.group(1)) != case["moves"]:
        raise ValueError("SGF replay move count mismatch for " + case["name"])
    rules, _ = engine.command("kata-get-rules")
    info = json.loads(rules)
    if info.get("scoring") != "AREA" or info.get("tax") != "NONE":
        raise ValueError("Not Chinese AREA rules: " + rules)
    komi, _ = engine.command("get_komi")
    if abs(float(komi) - case["komi"]) > 0.001:
        raise ValueError("Komi mismatch for " + case["name"])


def java_case(proc, case, ownership):
    lines = ["GAME\t%s\t%d" % (case["name"],case["size"])]
    for node in sgf_nodes(case["sgf"])[1:]:
        for color in ("B","W"):
            if color in node:
                for coord in node[color]:
                    lines.append("MOVE\t%s\t%s" % (color,coord))
    lines.append("OWN\t" + " ".join("%.7g" % x for x in ownership))
    lines.append("END\t" + str(case["komi"]))
    proc.stdin.write("\n".join(lines) + "\n")
    proc.stdin.flush()
    response = proc.stdout.readline().strip()
    parts = response.split("\t")
    if len(parts) != 15 or parts[0] != "RESULT" or parts[1] != case["name"]:
        raise ValueError("Unexpected Java referee result: " + repr(response))
    return dict(game=parts[1], verdict=parts[2], dead_groups=int(parts[3]),
                review_groups=int(parts[4]), total_groups=int(parts[5]),
                local_white_lead=float(parts[6]), reasons=parts[7].split(",") if parts[7] else [],
                suggested_moves=parts[8].split(",") if parts[8] else [],
                terminal_passes_java=parts[9] == "1",
                suggested_dead_vertices=parts[10].split(",") if parts[10] else [],
                needs_review_vertices=parts[11].split(",") if parts[11] else [],
                finding_locations={entry.split(":",1)[0]+"#"+str(i): entry.split(":",1)[1].split(",")
                                   if entry.split(":",1)[1] else []
                                   for i,entry in enumerate(parts[12].split("|")) if ":" in entry},
                vulnerable_groups=int(parts[13]),
                vulnerable_vertices=parts[14].split(",") if parts[14] else [])


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--serial", default=os.getenv("ANDROID_SERIAL"))
    p.add_argument("--mode", choices=("gpu","cpu"), default="gpu")
    p.add_argument("--limit", type=int, default=115)
    p.add_argument("--games", help="Comma-separated exact SGF case names to debug, e.g. cgos9_expand_001_05,cgos9_expand_006_12")
    p.add_argument("--debug-ownership", action="store_true", help="Keep raw per-intersection NN ownership in local JSON report")
    p.add_argument("--threads", type=int, default=1)
    p.add_argument("--timeout", type=float, default=160)
    p.add_argument("--adb", default=os.getenv("ADB") or shutil.which("adb"))
    p.add_argument("--preflight", action="store_true")
    p.add_argument("--sgf-dir", type=Path)
    p.add_argument("--gpu-binary", type=Path, default=PROJECT / "build/katago_android_arm64_opencl/libkatago_exec_opencl.so")
    p.add_argument("--cpu-binary", type=Path, default=PROJECT / "build/katago_android_arm64_eigen/libkatago_exec.so")
    args = p.parse_args()
    if args.limit < 1 or not 1 <= args.threads <= 64: p.error("Invalid limit/threads")
    cases, skipped = collect_games(args.sgf_dir,args.limit)
    if args.games:
        names = {item.strip() for item in args.games.split(",") if item.strip()}
        selected = [game for game in cases if game["name"] in names]
        missing = names - {game["name"] for game in selected}
        if missing: p.error("Unknown case name or beyond --limit: " + ", ".join(sorted(missing)))
        cases = selected
    print("Referee corpus: %d qualified SGFs selected, %d skipped" % (len(cases),len(skipped)),flush=True)
    if args.preflight: return 0
    if not args.adb: p.error("adb not found")
    if not shutil.which("javac") or not shutil.which("java"): p.error("JDK javac/java missing")
    binary = args.gpu_binary if args.mode=="gpu" else args.cpu_binary
    model = PROJECT / "app/src/main/assets/engine/10b.bin"
    config = PROJECT / "app/src/main/assets/engine/default_gtp.cfg"
    for path in (binary,model,config):
        if not path.is_file(): p.error("Missing file: %s" % path)
    output = PROJECT / "build/endgame_referee" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output.mkdir(parents=True,exist_ok=True)
    engine = None
    java = None
    results = []
    try:
        classes = compile_java(output)
        java = subprocess.Popen(["java","-cp",str(classes),"com.badukai.game.RealRefereeRunner"],
                                stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True,bufsize=1)
        serial = device_serial(args.adb,args.serial)
        gpu_egl = prepare_device(args.adb,serial,(args.mode,),{args.mode:binary},model,config,output)
        prepare_real_games(args.adb,serial,output,cases)
        engine = GtpEngine(args.adb,serial,args.mode,args.threads,8,8.0,output,args.timeout,
                           use_egl_mali=(args.mode=="gpu" and gpu_egl))
        warm = set()
        for case in cases:
            check_engine_position(engine,case)
            if case["size"] not in warm:
                engine.command("kata-raw-nn 0")
                warm.add(case["size"])
            engine.command("clear_cache")
            raw, nn_ms = engine.command("kata-raw-nn 0")
            nn_lead,nn_win,nn_loss = parse_nn_metrics(raw)
            ownership = ownership_from_raw(raw,case["size"])
            row = java_case(java,case,ownership)
            if args.debug_ownership:
                row["white_ownership_rows"] = [ownership[i:i+case["size"]]
                                               for i in range(0,len(ownership),case["size"])]
            row.update(size=case["size"],official_re=case["official_re"],
                       official_white_lead=case["official_white_lead"],
                       nn_white_lead=nn_lead,nn_ms=round(nn_ms,3),
                       nn_winner_correct=(nn_lead>0) == (case["official_white_lead"]>0),
                       tentative_area_error=round(abs(row["local_white_lead"]-case["official_white_lead"]),3),
                       sha256=case["sha256"])
            if not row["terminal_passes_java"] and case["terminal_passes"]:
                raise ValueError("Java double-pass end differs from SGF for " + case["name"])
            lead_gap = row["local_white_lead"] - case["official_white_lead"]
            opposite_winner = row["local_white_lead"] * case["official_white_lead"] < 0
            row["recorded_score_gap"] = round(lead_gap,3)
            row["recorded_winner_conflict"] = opposite_winner
            row["needs_recorded_score_review"] = opposite_winner or abs(lead_gap) >= 5.0
            results.append(row)
            if args.games:
                print("  Dead:",",".join(row["suggested_dead_vertices"]),
                      "Review:",",".join(row["needs_review_vertices"]),
                      "Low-liberty:",",".join(row["vulnerable_vertices"]),
                      "Finding locations:",row["finding_locations"],flush=True)
            print("%-27s %-22s dead=%d review=%d raw=%+6.2f area=%+6.2f RE=%s %s%s" %
                  (case["name"],row["verdict"],row["dead_groups"],row["review_groups"],
                   nn_lead,row["local_white_lead"],case["official_re"],
                   ",".join(row["reasons"]),
                   " *** RE/AREA CONFLICT ***" if row["needs_recorded_score_review"] else ""),flush=True)
        counts = collections.Counter(row["verdict"] for row in results)
        reasons = collections.Counter(reason for row in results for reason in row["reasons"])
        conflicts = [row["game"] for row in results if row["needs_recorded_score_review"]]
        report = {"type":"real_sgf_nn_java_smart_referee_triage",
                  "reference":"SGF RE is an external score label, not a certified dead-stone map",
                  "caveats":["Classifications are conservative heuristics, not proofs of ko, seki or solved life/death.",
                             "Area count uses unconfirmed AI-marked dead groups; do not call it official scoring.",
                             "Triage may say REVIEW for 19x19 records without terminal passes.",
                             "No SGF or engine data was publicly uploaded."],
                  "device":serial,"mode":args.mode,"summary":dict(verdict_counts=counts,reason_counts=reasons,
                      recorded_score_review_games=conflicts),
                  "skipped":skipped,"cases":results}
        filename=output / "referee.json"
        filename.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
        print("Verdicts:",dict(counts),"\nReasons:",dict(reasons),
              "\nSGF result conflicts:",conflicts,"\nLocal report:",filename,flush=True)
        return 0
    except (OSError,ValueError,RuntimeError,subprocess.CalledProcessError,TimeoutError) as err:
        print("ERROR:",err,"partial rows:",len(results),"report directory:",output,file=sys.stderr)
        return 1
    finally:
        if engine is not None: engine.close()
        if java is not None:
            if java.poll() is None:
                java.stdin.close()
                try: java.wait(timeout=5)
                except subprocess.TimeoutExpired: java.kill()
            if java.stdout: java.stdout.close()


if __name__ == "__main__":
    sys.exit(main())
