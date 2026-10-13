#!/usr/bin/env python3
"""Measure KataGo raw NN and a 12-visit native no-play search on 19 repo SGFs."""
import argparse, csv, json, re, statistics, tempfile, time
from datetime import datetime, timezone
from pathlib import Path
from benchmark_katago_referee_batch import make_cases, request
from test_native_katago_ko import Gtp

LEAD=re.compile(r"(?:^|\s)whiteLead\s+(-?(?:\d+(?:\.\d*)?|\.\d+))(?=\s|$)")
PASS=re.compile(r"(?:^|\s)policyPass\s+(-?(?:\d+(?:\.\d*)?|\.\d+))(?=\s|$)")
MOVE=re.compile(r"(?:^|\s)info\s+move\s+(\S+)")
SCORE=re.compile(r"(?:^|\s)scoreLead\s+(-?(?:\d+(?:\.\d*)?|\.\d+))(?=\s|$)")

def measure(g,case,repeat):
    _,load_ms=request(g,"loadsgf "+str(case["path"]))
    raw,raw_ms=request(g,"kata-raw-nn 0")
    white_lead=LEAD.search(raw)
    if not white_lead: raise RuntimeError("raw NN missing whiteLead")
    policy_pass=PASS.search(raw)
    try:
        search,search_ms=request(g,"kata-search_analyze black 50 minmoves 1 maxmoves 1")
        best=MOVE.search(search)
        search_lead=SCORE.search(search)
        error=""
    except Exception as exc:
        search_ms=None;best=None;search_lead=None;error=str(exc)
    return dict(fixture=case["name"],category=case["category"],size=case["size"],repeat=repeat,
                raw_ms=raw_ms,white_lead=float(white_lead.group(1)),
                policy_pass=float(policy_pass.group(1)) if policy_pass else None,
                search_ms=search_ms,search_move=best.group(1) if best else "",
                search_lead=float(search_lead.group(1)) if search_lead else None,
                search_error=error,load_ms=load_ms)

def publish(out,version,cases,runs,repeats):
    out.mkdir(parents=True,exist_ok=True)
    data=dict(version=version,platform="GitHub Linux x86_64 Eigen CPU AVX2",
              tested_at=datetime.now(timezone.utc).isoformat(),
              search_limits=dict(maxVisits=12,maxTime=0.5),
              disclaimer="Neither raw NN nor 12-visit search judges dead stones or game completion",runs=runs)
    (out/"report.json").write_text(json.dumps(data,indent=2,ensure_ascii=False)+"\n",encoding="utf-8")
    fields=("fixture","category","size","raw_ms","white_lead","search_ms","search_moves","search_errors","repeats")
    table=[]
    for c in cases:
        rows=[x for x in runs if x["fixture"]==c["name"] and "error" not in x]
        med=lambda k:round(statistics.median(x[k] for x in rows if x[k] is not None),2) if rows and all(x[k] is not None for x in rows) else None
        table.append(dict(fixture=c["name"],category=c["category"],size=c["size"],
                          raw_ms=med("raw_ms"),white_lead="/".join(str(x) for x in sorted({r["white_lead"] for r in rows})),
                          search_ms=med("search_ms"),
                          search_moves="/".join(sorted({r["search_move"] for r in rows if r["search_move"]})),
                          search_errors=";".join(x["search_error"] for x in rows if x["search_error"]),
                          repeats=len(rows)))
    with (out/"report.csv").open("w",newline="",encoding="utf-8") as f:
        w=csv.DictWriter(f,fieldnames=fields);w.writeheader();w.writerows(table)
    lines=["# KataGo Native Fast-Path Timing","",
        "Version: "+version+". GitHub Linux CPU Eigen AVX2, "+str(repeats)+" runs per position.",
        "Raw NN = kata-raw-nn 0; no-play search = kata-search_analyze black (max 12 visits, 0.5 seconds).",
        "Elapsed values are medians in milliseconds; neither mode is a final-score or dead-stone verdict.",
        "","| Position | Size | Raw NN ms | Raw NN whiteLead | 12 visits ms | Suggested moves | Search errors |",
        "|---|---:|---:|---|---:|---|---|"]
    for r in table:lines.append("| "+ " | ".join(str(x) if x is not None else "-" for x in
        (r["fixture"],r["size"],r["raw_ms"],r["white_lead"],r["search_ms"],
         r["search_moves"] or "-",r["search_errors"] or "-"))+" |")
    (out/"report.md").write_text("\n".join(lines)+"\n",encoding="utf-8")
    ok=sum(r["repeats"]==repeats for r in table)
    print("FAST_BATCH_DONE success=%d/%d raw_median_ms=%.2f"%
          (ok,len(cases),statistics.median(x["raw_ms"] for x in runs if "raw_ms" in x)),flush=True)
    if ok!=len(cases):raise SystemExit(1)

def main():
    p=argparse.ArgumentParser()
    for n in ("katago","config","model","out"):p.add_argument("--"+n,required=True)
    p.add_argument("--repeats",type=int,default=2)
    a=p.parse_args()
    with tempfile.TemporaryDirectory() as folder:
        cases=make_cases(Path(folder))
        g=Gtp(a);runs=[]
        try:
            version,_=request(g,"version")
            for command in ("kata-set-param maxVisits 12","kata-set-param maxTime 0.5"):
                request(g,command)
            print("FAST_BATCH_BEGIN version=%s count=%d repeats=%d"%(version,len(cases),a.repeats),flush=True)
            for repeat in range(1,a.repeats+1):
                for ix,case in enumerate(cases,1):
                    try:
                        result=measure(g,case,repeat);runs.append(result)
                        print("FAST_RESULT %02d/19 repeat=%d %s rawMs=%s whiteLead=%s searchMs=%s move=%s error=%s"%
                            (ix,repeat,case["name"],result["raw_ms"],result["white_lead"],
                             result["search_ms"],result["search_move"],result["search_error"] or "-"),flush=True)
                    except Exception as exc:
                        runs.append(dict(fixture=case["name"],repeat=repeat,error=str(exc)))
                        print("FAST_ERROR fixture=%s repeat=%d %s"%(case["name"],repeat,exc),flush=True)
        finally:g.close()
        publish(Path(a.out),version,cases,runs,a.repeats)

if __name__=="__main__":main()
