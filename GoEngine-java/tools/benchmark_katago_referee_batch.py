#!/usr/bin/env python3
"""Run real KataGo GTP commands on 9 complex, 3 baseline, 6 real, and 1 ko positions."""
import argparse, csv, json, re, statistics, tempfile, time
from datetime import datetime, timezone
from pathlib import Path
from benchmark_endgame_android import POSITIONS, make_sgf, parse_score, parse_dead
from complex_endgame_fixtures import COMPLEX_POSITIONS
from test_native_katago_ko import Gtp, moves_from_corpus

CORPUS = Path(__file__).with_name("real_scored_games.json")
COLS = ("category","fixture","size","recorded_result","prediction","dead","score_ms","dead_ms","total_ms","repeats","status","flag")

def make_cases(folder):
    result=[]
    for category,items in (("complex",COMPLEX_POSITIONS),("baseline",POSITIONS)):
        for name,item in items.items():
            sgf,info=make_sgf(item["rows"],item["description"])
            path=folder/(name+".sgf"); path.write_text(sgf,encoding="ascii")
            result.append(dict(name=name,category=category,size=info["size"],path=path,
                               recorded="",atari=list(item.get("atari",()))))
    corpus=json.loads(CORPUS.read_text(encoding="utf-8"))["cases"]
    for item in corpus:
        sgf=item["sgf"]; name=item["name"]
        found_size=re.search(r"SZ\[(\d+)\]",sgf)
        size=int(found_size.group(1)) if found_size else 19  # SGF defaults to 19 if SZ is absent
        recorded=re.search(r"RE\[([^\]]+)\]",sgf)
        path=folder/(name+".sgf"); path.write_text(sgf,encoding="utf-8")
        result.append(dict(name=name,category="recorded",size=size,path=path,
                           recorded=recorded.group(1) if recorded else "",atari=[]))
    moves=moves_from_corpus(CORPUS)
    path=folder/"cgos9_3_move64.sgf"
    path.write_text("(;GM[1]FF[4]SZ[9]KM[7]RU[Chinese]"+
                    "".join(";"+color+"["+v+"]" for color,v in moves)+")",encoding="ascii")
    result.append(dict(name="cgos9_3_move64",category="midgame_ko",size=9,
                       path=path,recorded="",atari=[]))
    assert len(COMPLEX_POSITIONS)==9 and len(POSITIONS)==3 and len(corpus)==6 and len(result)==19
    return result

def request(gtp,cmd):
    start=time.perf_counter_ns()
    response=gtp.ask(cmd,quiet=True)
    elapsed=round((time.perf_counter_ns()-start)/1e6,2)
    if not response.startswith("="): raise RuntimeError(cmd+": "+response[:160])
    return response[1:].strip(),elapsed

def test_case(gtp,case,repeat):
    started=time.perf_counter_ns()
    _,load_ms=request(gtp,"loadsgf "+str(case["path"]))
    rules,_=request(gtp,"kata-get-rules")
    j=json.loads(rules)
    if j.get("scoring")!="AREA" or j.get("ko")!="SIMPLE": raise ValueError(rules)
    komi,_=request(gtp,"get_komi")
    score,score_ms=request(gtp,"final_score")
    dead,dead_ms=request(gtp,"final_status_list dead")
    score=parse_score(score); dead=parse_dead(dead,case["size"])
    return dict(category=case["category"],fixture=case["name"],size=case["size"],
                repeat=repeat,recorded_result=case["recorded"],komi=float(komi),
                score=score,dead=dead,score_ms=score_ms,dead_ms=dead_ms,
                total_ms=round((time.perf_counter_ns()-started)/1e6,2),
                load_ms=load_ms,atari_markers=case["atari"])

def summary(cases,records,repeats):
    out=[]
    for case in cases:
        samples=[r for r in records if r["fixture"]==case["name"] and "error" not in r]
        score=sorted({r["score"] for r in samples})
        dead=sorted({r["dead"] for r in samples})
        status=("ERROR" if len(samples)!=repeats else
                "VARIABLE" if len(score)>1 or len(dead)>1 else "STABLE")
        if case["category"]=="midgame_ko": flag="KO_UNFINISHED"
        elif case["category"]=="complex": flag="SYNTHETIC_REVIEW"
        elif case["category"]=="recorded":
            flag="WINNER_MATCH" if len(score)==1 and case["recorded"][:1].upper()==score[0][:1] else "WINNER_DIFFERS"
        else: flag="BASELINE"
        median=lambda key: round(statistics.median([r[key] for r in samples]),1) if samples else None
        out.append(dict(category=case["category"],fixture=case["name"],size=case["size"],
            recorded_result=case["recorded"],prediction="/".join(score) or "ERROR",
            dead=" / ".join(dead) or ("-" if samples else "ERROR"),
            score_ms=median("score_ms"),dead_ms=median("dead_ms"),total_ms=median("total_ms"),
            repeats=len(samples),status=status,flag=flag))
    return out

def reports(folder,version,cases,records,rows,repeats):
    folder.mkdir(parents=True,exist_ok=True)
    full=dict(version=version,backend="GitHub Linux x86_64 CPU/Eigen AVX2",
        generated_at=datetime.now(timezone.utc).isoformat(),repeats=repeats,
        note="Score/dead are predictions. Synthetic fixtures have no certified life/death solution.",
        details=records,summary=rows)
    (folder/"report.json").write_text(json.dumps(full,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    with (folder/"report.csv").open("w",newline="",encoding="utf-8") as stream:
        writer=csv.DictWriter(stream,fieldnames=COLS);writer.writeheader();writer.writerows(rows)
    lines=["# KataGo 原生裁判批量实测",
        f"版本：{version}，CPU/Eigen AVX2，共 {len(cases)} 个局面，每局 {repeats} 次。",
        "耗时是两次的中位数（毫秒），**稳定**只表示两次预测一致，不代表准确。",
        "", "| 分类 | 局面 | 路数 | 棋谱结果 | KataGo 预测 | 死子 | 计分 ms | 死子 ms | 总耗时 ms | 次数 | 状态 | 标记 |",
        "|---|---|---:|---|---|---|---:|---:|---:|---:|---|---|"]
    for r in rows:
        values=(r["category"],r["fixture"],r["size"],r["recorded_result"],r["prediction"],
                r["dead"] if len(r["dead"])<=42 else r["dead"][:40]+"…",r["score_ms"],
                r["dead_ms"],r["total_ms"],str(r["repeats"])+"/"+str(repeats),
                r["status"],r["flag"])
        lines.append("| "+" | ".join(str(v) if v is not None else "-" for v in values)+" |")
    lines.extend(["","**标记说明**：KO_UNFINISHED=已另外验证存在劫禁；SYNTHETIC_REVIEW=合成复杂棋形，非真值；WINNER_MATCH/DIFFERS=预测胜方与棋谱记录胜方一致/相反；BASELINE=基础对照。",
        "同一局面死子或预测目数两次不一致则标 VARIABLE。计分能够返回，不代表已经收官。没有额外给棋盘停一手。"])
    (folder/"report.md").write_text("\n".join(lines)+"\n",encoding="utf-8")

def main():
    a=argparse.ArgumentParser()
    for name in ("katago","config","model","out"): a.add_argument("--"+name,required=True)
    a.add_argument("--repeats",type=int,default=2)
    a.add_argument("--list",action="store_true")
    args=a.parse_args()
    with tempfile.TemporaryDirectory() as t:
        cases=make_cases(Path(t))
        if args.list:
            for c in cases:print("CASE",c["category"],c["size"],c["name"],flush=True)
            return
        gtp=Gtp(args); records=[]
        try:
            version,_=request(gtp,"version")
            print(f"BATCH_BEGIN version={version} cases={len(cases)} repeats={args.repeats}",flush=True)
            for repeat in range(1,args.repeats+1):
                for index,case in enumerate(cases,1):
                    try:
                        r=test_case(gtp,case,repeat);records.append(r)
                        print(f"RESULT {index:02d}/19 repeat={repeat} {case['name']} "
                              f"score={r['score']} dead={r['dead'] or '-'} "
                              f"scoreMs={r['score_ms']} deadMs={r['dead_ms']} totalMs={r['total_ms']}",flush=True)
                    except Exception as exc:
                        records.append(dict(fixture=case["name"],category=case["category"],
                                            repeat=repeat,error=str(exc)))
                        print(f"RESULT_ERROR {case['name']} repeat={repeat} {exc}",flush=True)
        finally:
            gtp.close()
        rows=summary(cases,records,args.repeats)
        reports(Path(args.out),version,cases,records,rows,args.repeats)
        good=sum(r["status"]!="ERROR" for r in rows)
        stable=sum(r["status"]=="STABLE" for r in rows)
        print(f"BATCH_DONE good={good}/19 stable={stable}/19 out={args.out}",flush=True)
        if good!=len(cases): raise SystemExit(1)

if __name__=="__main__":main()
