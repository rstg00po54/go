#!/usr/bin/env python3
"""Run KataGo GTP against the real cgos9_3 move-64 ko, not a mock evaluator."""
import argparse
import json
import re
import subprocess
import tempfile
import time
from pathlib import Path

def fixture_from_corpus(corpus):
    records = json.loads(corpus.read_text(encoding="utf-8"))["cases"]
    sgf = next(item["sgf"] for item in records if item["name"] == "cgos9_3")
    moves = re.findall(r";([BW])\[([a-i]{0,2})\]", sgf)
    if len(moves) < 64 or moves[63] != ("W", "hg"):
        raise AssertionError("Expected white H3 ko capture at move 64")
    return "(;GM[1]FF[4]SZ[9]KM[7]RU[Chinese]" + "".join(
        f";{color}[{vertex}]" for color, vertex in moves[:64]) + ")"

class Gtp:
    def __init__(self, args):
        command = [args.katago, "gtp", "-config", args.config, "-model", args.model,
                   "-override-config", "numSearchThreads=1,maxVisits=48,maxTime=1,ponderingEnabled=false"]
        self.proc = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     text=True, bufsize=1)
    def ask(self, command):
        start = time.monotonic()
        self.proc.stdin.write(command + "\n")
        self.proc.stdin.flush()
        lines = []
        while True:
            line = self.proc.stdout.readline()
            if not line:
                raise RuntimeError(f"KataGo terminated during {command}: {self.proc.poll()}")
            if lines and not line.strip():
                break
            if line.strip():
                lines.append(line.rstrip("\n"))
        answer = "\n".join(lines)
        print(f"{command:<28} {time.monotonic()-start:7.3f}s {answer[:260]}", flush=True)
        return answer
    def close(self):
        if self.proc.poll() is None:
            try:
                self.ask("quit")
            except Exception:
                self.proc.kill()
            self.proc.wait(timeout=15)

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--katago", required=True)
    p.add_argument("--config", required=True)
    p.add_argument("--model", required=True)
    p.add_argument("--corpus", default=str(Path(__file__).resolve().parent / "real_scored_games.json"))
    args = p.parse_args()
    with tempfile.TemporaryDirectory() as temp:
        fixture = Path(temp) / "ko64.sgf"
        fixture.write_text(fixture_from_corpus(Path(args.corpus)), encoding="utf-8")
        gtp = Gtp(args)
        try:
            for cmd in ("name", "version", "boardsize 9", "clear_board", "kata-set-rules chinese", "komi 7"):
                assert gtp.ask(cmd).startswith("="), cmd
            assert gtp.ask("loadsgf " + str(fixture)).startswith("=")
            score = gtp.ask("final_score")
            dead = gtp.ask("final_status_list dead")
            immediate = gtp.ask("play b H4")
            print("SCORE_DURING_KO:", score, flush=True)
            print("DEAD_STATUS_DURING_KO:", dead, flush=True)
            print("IMMEDIATE_KO_RECAPTURE:", immediate, flush=True)
            print("RESULTS: score_returned=%s dead_returned=%s ko_blocked=%s" %
                  (score.startswith("="), dead.startswith("="), immediate.startswith("?")), flush=True)
            assert score.startswith("="), "KataGo final_score did not return during ko"
            assert dead.startswith("="), "KataGo final_status_list dead did not return during ko"
            assert immediate.startswith("?"), "Immediate recapture H4 was not rejected"
        finally:
            gtp.close()

if __name__ == "__main__":
    main()
