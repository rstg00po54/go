#!/usr/bin/env python3
"""Run actual KataGo BoardHistory::endGameIfAllPassAlive on 19 repo fixtures."""
import argparse,csv,json,statistics,tempfile,time
from pathlib import Path
from datetime import datetime,timezone
from benchmark_katago_referee_batch import make_cases
from test_native_katago_ko import Gtp

HERE=Path(__file__).resolve().parents[1]
SOURCE=HERE/"reports/20261013_katago_referee_batch.csv"
FIELDS=["type","fixture","size","finished","unknown","native_us","gtp_ms",
        "passalive_score","earlier_nn_score","earlier_nn_score_ms","status"]

def controls(root):
    specimens=[
        ("control_empty9",["........."]*9,False),
        ("control_two_eyes9",["BBBBBBBBB","B.BBBB.BB","BBBBBBBBB",
          "BBBBBBBBB","BBBBBBBBB","BBBBWWWWW","BBBBW.W.W",
          "BBBBWWWWW","BBBBWWWWW"],True)]
    out=[]
    for name,board,expected in specimens:
        pieces=["(;FF[4]GM[1]SZ[9]RU[Chinese]KM[7]PL[B]"]
        for color,label in (("B","AB"),("W","AW")):
            points=["[%s%s]"%(chr(97+x),chr(97+y)) for y,row in enumerate(board)
                    for x,value in enumerate(row) if value==color]
            if points: pieces.append(label+"".join(points))
        p=root/(name+".sgf")
        p.write_text("".join(pieces)+")",encoding="ascii")
        out.append(dict(name=name,category="control",size=9,path=p,expected=expected))
    return out

def test(gtp,case,repeat,n):
    res=gtp.ask("loadsgf "+str(case["path"]),quiet=True)
    if not res.startswith("="):raise RuntimeError("loadsgf "+res)
    start=time.perf_counter_ns()
    response=gtp.ask("kata-test-all-pass-alive "+str(n),quiet=True)
    ms=(time.perf_counter_ns()-start)/1000000
    if not response.startswith("="):raise RuntimeError(response)
    terms=response[1:].strip().split()
    if len(terms)%2:raise RuntimeError("Unexpected command response "+response)
    data=dict(zip(terms[::2],terms[1::2]))
    finished=data["pass_alive_finished"]=="1"
    if finished!=(data["scored"]=="1"):raise AssertionError("Scoring state inconsistent")
    if int(data["repeats"])!=n:raise AssertionError("loop mismatch")
    return dict(name=case["name"],category=case["category"],size=case["size"],
        repeat=repeat,finished=finished,unknown=int(data["unknown"]),
        native_us=float(data["average_ns"])/1000,gtp_ms=ms,
        score=float(data["score_white_minus_black"]) if finished else None)

def report(folder,cases,runs,version,repeats,n):
    folder.mkdir(parents=True,exist_ok=True)
    with SOURCE.open(encoding="utf-8",newline="") as f:
        prior={row["局面"]:row for row in csv.DictReader(f)}
    rows=[]
    for case in cases:
        data=[r for r in runs if r["name"]==case["name"] and "error" not in r]
        finished=set(r["finished"] for r in data)
        result=next(iter(finished)) if len(finished)==1 else None
        ok=len(data)==repeats and len(finished)==1 and len({x["unknown"] for x in data})==1
        if "expected" in case:ok=ok and result==case["expected"]
        med=lambda k:round(statistics.median(r[k] for r in data),4) if data else None
        earlier=prior.get(case["name"],{})
        rows.append(dict(type=case["category"],fixture=case["name"],size=case["size"],
            finished=result,unknown=data[0]["unknown"] if data else None,
            native_us=med("native_us"),gtp_ms=med("gtp_ms"),
            passalive_score=data[0]["score"] if data else None,
            earlier_nn_score=earlier.get("v1.15预测",""),
            earlier_nn_score_ms=earlier.get("v1.15计分ms",""),
            status="PASS" if ok else "FAIL"))
    with (folder/"report.csv").open("w",newline="",encoding="utf-8") as f:
        w=csv.DictWriter(f,fieldnames=FIELDS);w.writeheader();w.writerows(rows)
    (folder/"report.json").write_text(json.dumps(dict(
        engine=version,environment="GitHub Linux x86_64 CPU/Eigen, NOT local RK3588",
        created=datetime.now(timezone.utc).isoformat(),calls_per_request=n,
        repeats=repeats,summary=rows,raw_runs=runs),ensure_ascii=False,indent=2)+"\n",
        encoding="utf-8")
    succeeded=sum(row["status"]=="PASS" for row in rows)
    passed=sum(row["finished"] is True for row in rows if row["type"]!="control")
    md=["# KataGo endGameIfAllPassAlive 原生函数实测","",
        f"版本：{version}。Linux x86_64 CPU/Eigen。不是 Android 或 RK3588 本地。",
        f"仓库19盘棋，另有2个正负对照。每盘重复{repeats}次，每次在 C++ 内调用{n}遍。",
        "函数直接使用 KataGo 官方 BoardHistory::endGameIfAllPassAlive，不运行 NN/MCTS。",
        "每次构建新的、未结束的 BoardHistory，以棋盘形状独立判定，防止双 PASS 产生假阳性。",
        "函数只在 Benson 活棋地盘覆盖全棋盘时证明可结束；返回否不等于没下完。",
        f"结果：仓库19盘中有{passed}盘满足证明条件；含对照共{succeeded}/{len(rows)}项测试通过。",
        "","| 类型 | 局面 | 路数 | 可证明结束 | 未归属点 | 原生耗时 µs | GTP整次 ms | 原生分数 | 先前 NN 分数 |",
        "|---|---|---:|---|---:|---:|---:|---|---|"]
    for r in rows:
        vals=(r["type"],r["fixture"],r["size"],
              "是" if r["finished"] is True else "否" if r["finished"] is False else "错误",
              r["unknown"],r["native_us"],r["gtp_ms"],
              r["passalive_score"] if r["passalive_score"] is not None else "-",
              r["earlier_nn_score"] or "-")
        md.append("| "+" | ".join(map(str,vals))+" |")
    md+=["","注意：之前的 NN 分数来自仓库历史实测表，仅作参考。这里的毫秒是 GTP 往返时延，微秒是单次 C++ 函数本体耗时。",
          "未归属点是严格的 pass-alive 面积未覆盖点数；多于零时不能据此确认终局。",
          "报告仅为 Linux CPU 原生函数实测，未修改 Android APK。",
          ""]
    (folder/"report.md").write_text("\n".join(md)+"\n",encoding="utf-8")
    for r in rows:
        print("SUMMARY fixture=%s finished=%s unknown=%s nativeUs=%s gtpMs=%s score=%s status=%s"%
            (r["fixture"],r["finished"],r["unknown"],r["native_us"],
             r["gtp_ms"],r["passalive_score"],r["status"]),flush=True)
    print("PASS_ALIVE_COMPLETE proved=%d/19 success=%d/%d"%
          (passed,succeeded,len(rows)),flush=True)
    if succeeded!=len(rows):raise SystemExit(1)

def main():
    ap=argparse.ArgumentParser()
    for x in ("katago","config","model","out"):ap.add_argument("--"+x,required=True)
    ap.add_argument("--loops",type=int,default=2000)
    ap.add_argument("--repeats",type=int,default=3)
    args=ap.parse_args()
    with tempfile.TemporaryDirectory() as tmp:
        cases=make_cases(Path(tmp))
        assert len(cases)==19
        cases+=controls(Path(tmp))
        g=Gtp(args);runs=[]
        try:
            version=g.ask("version",quiet=True).strip()[1:]
            if g.ask("known_command kata-test-all-pass-alive",quiet=True).strip()!="= true":
                raise RuntimeError("Native test GTP command not registered")
            for case in cases:
                for repeat in range(1,args.repeats+1):
                    try:
                        r=test(g,case,repeat,args.loops);runs.append(r)
                        print("RUN fixture=%s repeat=%d finished=%s unknown=%d nativeUs=%.3f"%
                              (case["name"],repeat,r["finished"],r["unknown"],r["native_us"]),flush=True)
                    except Exception as e:
                        print("ERROR fixture=%s repeat=%d error=%s"%
                              (case["name"],repeat,e),flush=True)
                        runs.append(dict(name=case["name"],repeat=repeat,error=str(e)))
        finally:g.close()
        report(Path(args.out),cases,runs,version,args.repeats,args.loops)

if __name__=="__main__":main()
