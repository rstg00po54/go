#!/usr/bin/env python3
"""Measure real KataGo 4-query PASS comparisons at 24/12/8 visits on repo fixtures."""
import argparse,csv,json,re,statistics,tempfile,time
from pathlib import Path
from datetime import datetime,timezone
from benchmark_katago_referee_batch import make_cases,request
from test_native_katago_ko import Gtp

VISITS=(24,12,8)
COLS=("fixture","size","repeat","visits","ms","black_gain","white_gain",
      "black_move","white_move","ownership_high","ownership_max","verdict","error")

def candidates(text):
    # Like Android: most recent info per move, then highest current-side winrate.
    latest={}
    for part in re.split(r"\binfo\s+",text)[1:]:
        part=re.split(r"\b(?:rootInfo|ownershipStdev|ownership|play)\b",part,maxsplit=1)[0]
        body=part.split(" pv ",1)[0]
        def value(key):
            found=re.search(r"\b"+key+r"\s+(\S+)",body)
            return found.group(1) if found else None
        try:
            name=value("move")
            visits=int(value("visits"));order=int(value("order"))
            win=float(value("winrate"));lead=float(value("scoreLead"))
            if name and visits>=0 and order>=0 and 0<=win<=1:
                latest[name]=(name,win,lead,order)
        except (ValueError,TypeError):pass
    return sorted(latest.values(),key=lambda x:(-x[1],x[3]))

def measure(gtp,case,visits,repeat):
    request(gtp,"loadsgf "+str(case["path"]))
    request(gtp,"kata-set-param maxVisits "+str(visits))
    request(gtp,"kata-set-param maxTime 0.5")
    t=time.monotonic()
    r={}
    for color in ("black","white"):
        natural,_=request(gtp,"kata-search_analyze "+color+" 50 minmoves 1 maxmoves 3"+
                          (" rootInfo true ownershipStdev true" if color=="white" else ""))
        force,_=request(gtp,"kata-search_analyze "+color+
                        " 50 minmoves 1 maxmoves 3 allow "+color+" pass 1")
        n=candidates(natural);p=next((x for x in candidates(force) if x[0].lower()=="pass"),None)
        if not n or p is None:raise RuntimeError(color+" missing best/PASS")
        r[color+"_move"]=n[0][0]
        r[color+"_gain"]=round(n[0][2]-p[2],5)
        if color=="white":
            idx=natural.rfind("ownershipStdev ")
            if idx<0:raise RuntimeError("missing ownershipStdev")
            values=natural[idx+len("ownershipStdev "):].strip().split()
            values=[float(x) for x in values[:case["size"]**2]]
            if len(values)!=case["size"]**2:raise RuntimeError("ownershipStdev array truncated")
            r["ownership_max"]=round(max(values),5)
            r["ownership_high"]=sum(x>0.25 for x in values)
    r["ms"]=round((time.monotonic()-t)*1000,2)
    b,w=r["black_gain"],r["white_gain"]
    r["verdict"]="PLAY" if b>=1.5 or w>=1.5 else "REVIEW" if r["ownership_high"] else "SMALL" if b>0.5 or w>0.5 else "ALMOST"
    return dict(fixture=case["name"],size=case["size"],repeat=repeat,visits=visits,error="",**r)

def save(out,version,cases,runs):
    out.mkdir(parents=True,exist_ok=True)
    with (out/"runs.csv").open("w",newline="",encoding="utf-8") as f:
        writer=csv.DictWriter(f,fieldnames=COLS);writer.writeheader();writer.writerows(runs)
    good=[r for r in runs if not r["error"]];err=[r for r in runs if r["error"]]
    med=lambda seq:round(statistics.median(seq),2) if seq else None
    stats={v:dict(median_ms=med([r["ms"] for r in good if r["visits"]==v]),runs=len([r for r in good if r["visits"]==v])) for v in VISITS}
    diffs={12:[],8:[]}
    lines=["# Actual KataGo PASS comparison, visits 24 / 12 / 8","",
           f"Engine {version}; Linux x86_64 CPU/Eigen AVX2; 19 fixtures, two repeats.",
           "Same four sequential KataGo non-playing searches as Android, maxTime=0.5s.",
           "Each visit limit and repeat runs in a fresh KataGo process to avoid cross-limit MCTS tree reuse. Times exclude SGF setup and search-parameter configuration.",
           "Verdict labels compare against 24-visit outputs, not a certified game-ending truth.",
           "","| Fixture | Size | 24 ms | 12 ms | 8 ms | 24 verdict | 12 verdict | 8 verdict |",
           "|---|---:|---:|---:|---:|---|---|---|"]
    cases_out=[]
    for case in cases:
        rows={v:[r for r in good if r["fixture"]==case["name"] and r["visits"]==v] for v in VISITS}
        avg={v:med([r["ms"] for r in rows[v]]) for v in VISITS}
        verdicts={v:sorted(set(r["verdict"] for r in rows[v])) for v in VISITS}
        cases_out.append(dict(name=case["name"],times=avg,verdicts=verdicts))
        for v in (12,8):
            if verdicts[v]!=verdicts[24]:diffs[v].append(case["name"])
        lines.append("| "+ " | ".join(map(str,[case["name"],case["size"]]+[avg[v] for v in VISITS]+
                    ["/".join(verdicts[v]) for v in VISITS]))+" |")
    lines+=["","## Overall medians","",
            "| Visits | Median for four searches | Relative to 24 |",
            "|---:|---:|---:|"]
    for v in VISITS:
        base=stats[24]["median_ms"];now=stats[v]["median_ms"]
        lines.append(f"| {v} | {now} ms | {round(base/now,2) if base and now else '-'}x |")
        print(f"STATS version={version} visits={v} medianMs={now} n={stats[v]['runs']}",flush=True)
    lines+=["",f"12 vs 24 differing verdicts: {len(diffs[12])} — {', '.join(diffs[12]) or '-'}",
            f"8 vs 24 differing verdicts: {len(diffs[8])} — {', '.join(diffs[8]) or '-'}",
            f"Errors: {len(err)}.",
            "Note: synthetic ko-like setups have no actual ko ban; life/death/territory accuracy was not verified."]
    (out/"report.md").write_text("\n".join(lines)+"\n",encoding="utf-8")
    (out/"report.json").write_text(json.dumps(dict(version=version,
        timestamp=datetime.now(timezone.utc).isoformat(),limits=VISITS,
        stats=stats,differing=diffs,fixtures=cases_out,raw=runs),indent=2,ensure_ascii=False)+"\n",encoding="utf-8")
    print(f"DONE version={version} runs={len(runs)} errors={len(err)} diff12={len(diffs[12])} diff8={len(diffs[8])}",flush=True)
    if err:raise SystemExit("Some cases failed")

def main():
    p=argparse.ArgumentParser()
    for name in ("katago","config","model","out"):p.add_argument("--"+name,required=True)
    args=p.parse_args()
    with tempfile.TemporaryDirectory() as tmp:
        cases=make_cases(Path(tmp));assert len(cases)==19
        rows=[];version=""
        # Critical: one FRESH KataGo process per visit limit and repeat.
        # Otherwise a completed 24-visit root can make subsequent 12-visit
        # queries finish instantly by reusing already accumulated visits.
        for v in VISITS:
            for repeat in (1,2):
                g=Gtp(args)
                try:
                    version,_=request(g,"version")
                    # Change fixture order in the second replicate without
                    # sharing any search cache across visit configurations.
                    batch=cases if repeat==1 else list(reversed(cases))
                    for case in batch:
                        try:
                            r=measure(g,case,v,repeat);rows.append(r)
                            print(f"RUN fixture={case['name']} visits={v} repeat={repeat} ms={r['ms']} "
                                  f"verdict={r['verdict']} B={r['black_gain']} W={r['white_gain']} "
                                  f"Bmove={r['black_move']} Wmove={r['white_move']} high={r['ownership_high']}",flush=True)
                        except Exception as ex:
                            rows.append(dict(fixture=case["name"],size=case["size"],repeat=repeat,visits=v,
                                ms=None,black_gain=None,white_gain=None,black_move="",white_move="",
                                ownership_high=None,ownership_max=None,verdict="ERROR",error=str(ex)))
                            print(f"ERROR fixture={case['name']} visits={v} repeat={repeat} error={ex}",flush=True)
                finally:
                    g.close()
        save(Path(args.out),version,cases,rows)

if __name__=="__main__":main()
