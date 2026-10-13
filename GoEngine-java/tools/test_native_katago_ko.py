#!/usr/bin/env python3
"""Exercise REAL KataGo on archived move-64 board and a constructed unambiguous simple ko."""
import argparse
import json
import re
import subprocess
import tempfile
import time
from pathlib import Path

def moves_from_corpus(corpus):
    cases = json.loads(corpus.read_text(encoding="utf-8"))["cases"]
    sgf = next(item["sgf"] for item in cases if item["name"] == "cgos9_3")
    moves = re.findall(r";([BW])\[([a-i]{0,2})\]", sgf)
    assert len(moves) >= 64 and moves[63] == ("W","hg")
    return moves[:64]

def vertex_to_gtp(vertex):
    if not vertex: return "pass"
    return "ABCDEFGHJ"[ord(vertex[0])-ord("a")] + str(9-(ord(vertex[1])-ord("a")))

class Gtp:
    def __init__(self,args):
        cmd = [args.katago,"gtp","-config",args.config,"-model",args.model,
               "-override-config","numSearchThreads=1,maxVisits=48,maxTime=1,ponderingEnabled=false"]
        self.proc = subprocess.Popen(cmd,stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True,bufsize=1)
    def ask(self,command,quiet=False):
        start = time.monotonic()
        self.proc.stdin.write(command+"\n")
        self.proc.stdin.flush()
        rows=[]
        while True:
            line=self.proc.stdout.readline()
            if not line: raise RuntimeError("KataGo ended while answering "+command)
            if rows and not line.strip(): break
            if line.strip(): rows.append(line.rstrip("\n"))
        result="\n".join(rows)
        if not quiet: print(f"{command:<30} {time.monotonic()-start:7.3f}s {result[:400]}",flush=True)
        return result
    def close(self):
        if self.proc.poll() is None:
            try: self.ask("quit")
            except Exception: self.proc.kill()
            self.proc.wait(timeout=15)

def main():
    p=argparse.ArgumentParser()
    for name in ("katago","config","model"): p.add_argument("--"+name,required=True)
    p.add_argument("--corpus",default=str(Path(__file__).resolve().parent/"real_scored_games.json"))
    args=p.parse_args()
    moves=moves_from_corpus(Path(args.corpus))
    with tempfile.TemporaryDirectory() as folder:
        sgf=Path(folder)/"cgos9_3_move64.sgf"
        sgf.write_text("(;GM[1]FF[4]SZ[9]KM[7]RU[Chinese]"+
                       "".join(";"+side+"["+v+"]" for side,v in moves)+")",encoding="utf-8")
        gtp=Gtp(args)
        try:
            for cmd in ("name","version","boardsize 9","clear_board","kata-set-rules chinese","komi 7"):
                assert gtp.ask(cmd).startswith("=")
            print("\nCASE A: archived real 9x9 game after move 64, loaded by loadsgf",flush=True)
            assert gtp.ask("loadsgf "+str(sgf)).startswith("=")
            board=gtp.ask("showboard")
            score=gtp.ask("final_score")
            dead=gtp.ask("final_status_list dead")
            recap=gtp.ask("play b H4")
            print("ARCHIVED: score_returned=%s dead_returned=%s H4_illegal=%s" %
                  (score.startswith("="),dead.startswith("="),recap.startswith("?")),flush=True)
            assert score.startswith("=") and dead.startswith("=")

            print("\nCASE B: exact same archived game, replay each GTP move for full history",flush=True)
            assert gtp.ask("clear_board").startswith("=")
            for turn,(side,vertex) in enumerate(moves,1):
                cmd="play "+side.lower()+" "+vertex_to_gtp(vertex)
                answer=gtp.ask(cmd,quiet=True)
                if not answer.startswith("="):
                    raise AssertionError(f"Replay failed at turn {turn}: {cmd} {answer}")
            replay_recap=gtp.ask("play b H4")
            print("REPLAY: H4_illegal=%s" % replay_recap.startswith("?"),flush=True)

            print("\nCASE C: controlled classic 9x9 simple ko, with full alternating GTP moves",flush=True)
            assert gtp.ask("clear_board").startswith("=")
            # Black surrounds E5, then black E4 captures the single white E5.
            # White's immediate E5 recapture is the canonical simple-ko prohibition.
            commands=("play b E6","play w E5","play b D5","play w D4",
                      "play b F5","play w F4","play b A1","play w E3","play b E4")
            for cmd in commands:
                response=gtp.ask(cmd)
                assert response.startswith("="), "Bad synthetic setup: "+cmd+" "+response
            true_score=gtp.ask("final_score")
            true_dead=gtp.ask("final_status_list dead")
            true_recap=gtp.ask("play w E5")
            print("CONTROLLED: score_returned=%s dead_returned=%s immediate_recapture_illegal=%s" %
                  (true_score.startswith("="),true_dead.startswith("="),true_recap.startswith("?")),flush=True)
            assert true_score.startswith("="), "Expected scoring estimate even during active ko"
            assert true_dead.startswith("="), "Expected dead-stone estimate during active ko"
            assert true_recap.startswith("?"), "Expected simple ko to forbid immediate white E5"
            print("PASS: In actual KataGo, final_score and final_status_list can return during a confirmed ko.",flush=True)
        finally:
            gtp.close()

if __name__=="__main__":
    main()
