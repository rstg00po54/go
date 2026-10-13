#!/usr/bin/env python3
"""Read KataGo's OWN no-result probability and ownershipStdev; do not classify life/death."""
import argparse, json, re, statistics, tempfile, time
from pathlib import Path
from benchmark_katago_referee_batch import make_cases, request
from test_native_katago_ko import Gtp

VALUE=re.compile(r"(?:^|\s)noResult\s+([-+]?(?:\d+(?:\.\d*)?|\.\d+))(?=\s|$)")
OWN=re.compile(r"(?:^|\s)ownershipStdev\s+(.+?)(?=\s+(?:rootInfo|ownership|info|play)\b|$)",re.S)
FLOAT=re.compile(r"[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?")

def main():
    parser=argparse.ArgumentParser()
    for key in ("katago","config","model","out"):parser.add_argument("--"+key,required=True)
    args=parser.parse_args()
    records=[]
    with tempfile.TemporaryDirectory() as folder:
        cases=make_cases(Path(folder))
        gtp=Gtp(args)
        try:
            version,_=request(gtp,"version")
            request(gtp,"kata-set-param maxVisits 12")
            request(gtp,"kata-set-param maxTime 0.5")
            for case in cases:
                request(gtp,"loadsgf "+str(case["path"]))
                raw,raw_ms=request(gtp,"kata-raw-nn 0")
                raw_nr=VALUE.search(raw)
                player="black"
                start=time.perf_counter_ns()
                response,search_ms=request(gtp,f"kata-search_analyze {player} 50 minmoves 1 maxmoves 1 rootInfo true ownershipStdev true")
                match=OWN.search(response)
                stdev=list(map(float,FLOAT.findall(match.group(1)))) if match else []
                rec=dict(fixture=case["name"],size=case["size"],version=version,
                         noResult=(float(raw_nr.group(1)) if raw_nr else None),raw_ms=raw_ms,
                         ownership_stdev_len=len(stdev),expected_len=case["size"]**2,
                         mean_ownership_stdev=(round(statistics.mean(stdev),5) if stdev else None),
                         max_ownership_stdev=(round(max(stdev),5) if stdev else None),
                         stdev_gt_025=sum(v>0.25 for v in stdev),
                         stdev_gt_05=sum(v>0.5 for v in stdev),
                         search_ms=search_ms,has_root_info=("rootInfo" in response))
                records.append(rec)
                print("NATIVE_UNCERTAINTY "+json.dumps(rec,ensure_ascii=False),flush=True)
                if len(stdev)!=case["size"]**2: print("UNEXPECTED_OWNERSHIP_STDEV_LEN "+case["name"],flush=True)
        finally:gtp.close()
    out=Path(args.out);out.mkdir(parents=True,exist_ok=True)
    (out/"report.json").write_text(json.dumps(records,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    lines=["# KataGo direct uncertainty fields (native GTP)","",
           "Measured fields: raw NN noResult, 12-visit ownershipStdev via kata-search_analyze; these are not proof of seki, active ko, or life/death.",
           "", "| Position | Size | noResult | ownershipStdev mean | max | >0.25 points | Search ms |",
           "|---|---:|---:|---:|---:|---:|---:|"]
    for r in records:lines.append(f"| {r['fixture']} | {r['size']} | {r['noResult']} | {r['mean_ownership_stdev']} | {r['max_ownership_stdev']} | {r['stdev_gt_025']} | {r['search_ms']} |")
    (out/"report.md").write_text("\n".join(lines)+"\n",encoding="utf-8")
    missing=[r for r in records if r["noResult"] is None or r["ownership_stdev_len"]!=r["expected_len"]]
    print(f"NATIVE_UNCERTAINTY_DONE count={len(records)} missing={len(missing)}",flush=True)
    if missing: raise SystemExit(1)

if __name__=="__main__":main()
