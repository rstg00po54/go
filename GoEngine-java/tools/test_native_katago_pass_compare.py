#!/usr/bin/env python3
import argparse, re, pathlib, tempfile
from benchmark_katago_referee_batch import make_cases, request
from test_native_katago_ko import Gtp

def evaluate(gtp,command):
    result,ms=request(gtp,command)
    parts=re.split(r'\binfo\s+',result)[1:]
    moves=[]
    for part in parts:
        move=re.search(r'\bmove\s+(\S+)',part)
        lead=re.search(r'\bscoreLead\s+(-?[\d.]+)',part)
        if move and lead:moves.append((move.group(1),float(lead.group(1))))
    return moves,ms

def main():
    p=argparse.ArgumentParser()
    for key in ("katago","config","model"):p.add_argument("--"+key,required=True)
    a=p.parse_args()
    with tempfile.TemporaryDirectory() as temp:
        cases=make_cases(pathlib.Path(temp))
        g=Gtp(a)
        try:
            version,_=request(g,"version")
            request(g,"kata-set-param maxVisits 24")
            request(g,"kata-set-param maxTime 0.5")
            for case in cases:
                request(g,"loadsgf "+str(case["path"]))
                for color in ("black","white"):
                    unrestricted,ms1=evaluate(g,"kata-search_analyze "+color+" 50 minmoves 1 maxmoves 1")
                    forced,ms2=evaluate(g,"kata-search_analyze "+color+" 50 minmoves 1 maxmoves 1 allow "+color+" pass 1")
                    print("COMPARE",case["name"],color,"best=",unrestricted[:1],"pass=",forced[:1],"time_ms=",ms1,ms2,flush=True)
                    assert forced and forced[0][0].lower()=="pass",(case["name"],color,forced)
        finally:g.close()
    print("PASS_COMPARE_OK version="+version+" cases="+str(len(cases)),flush=True)
if __name__=="__main__":main()
