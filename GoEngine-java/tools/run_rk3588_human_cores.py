#!/usr/bin/env python3
"""Benchmark the unmodified original Human b18 RKNN graph on one or three RK3588 cores.

Run with test_rk3588_human_cores.sh. Writes a local report and, by default,
publishes PASS *and* FAIL with diagnostics to the public ui-test-results branch.
--no-upload disables public publication; --upload-existing retries a prior result.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from uuid import uuid4

from upload_referee_test_results import UploadError, upload_referee_results

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
SCRIPT = ROOT / "tools/test_rk3588_human_cores.sh"
REPORT_ROOT = ROOT / "build/rk3588_human/core_reports"
FIELDS = re.compile(r"([a-zA-Z0-9_]+)=([^\s]+)")


def values(line):
    return dict(FIELDS.findall(line))


def parse_output(log):
    modes = {}
    samples = {}
    result = None
    for line in log.splitlines():
        if line.startswith("HUMAN_BENCH_SAMPLE "):
            row = values(line)
            phase = row.get("phase")
            if phase:
                try:
                    samples.setdefault(phase, []).append({
                        key: float(row[key]) for key in
                        ("inputMs", "runMs", "outputMs", "totalMs")
                    })
                except (ValueError, KeyError):
                    continue
        if line.startswith("HUMAN_BENCH_SUMMARY "):
            row=values(line)
            phase=row.get("phase")
            if phase:
                try:
                    modes[phase]={
                        "runs": int(row["runs"]), "warmup": int(row["warmup"]),
                        **{key: float(row[key]) for key in
                           ("medianRunMs","p90RunMs","medianTotalMs",
                            "minRunMs","maxRunMs")}
                    }
                except (ValueError, KeyError):
                    continue
        if line.startswith("HUMAN_BENCH_RESULT "):
            result = values(line)
    return {"result": result, "modes": modes, "samples": samples}


def run(serial, log_path):
    env=os.environ.copy()
    env["HUMAN_CORES_CHILD"]="1"
    with log_path.open("w",encoding="utf-8") as logfile:
        proc=subprocess.Popen(
            ["bash",str(SCRIPT),serial],cwd=ROOT,env=env,
            stdout=subprocess.PIPE,stderr=subprocess.STDOUT,
            text=True,encoding="utf-8",errors="replace",bufsize=1
        )
        try:
            for line in proc.stdout:
                sys.stdout.write(line)
                sys.stdout.flush()
                logfile.write(line)
                logfile.flush()
            return proc.wait()
        except KeyboardInterrupt:
            proc.terminate()
            try: proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()
            logfile.write("\n用户中断\n")
            return 130


def main(argv=None):
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("serial",nargs="?",default=os.getenv("ANDROID_SERIAL","8719e18a71a2a66c"))
    p.add_argument("--no-upload",action="store_true",help="只保留本地，不公开上传")
    p.add_argument("--upload-existing",type=Path,help="仅上传已有报告，不再跑 NPU")
    p.add_argument("--upload-remote",default="auto")
    args=p.parse_args(argv)
    if args.no_upload and args.upload_existing:
        p.error("不能同时使用 --no-upload 和 --upload-existing")
    if args.upload_existing:
        folder=args.upload_existing.resolve()
        try:
            doc=json.loads((folder/"report.json").read_text(encoding="utf-8"))
            url=upload_referee_results(folder,doc,remote=args.upload_remote,
                                      repo_root=REPO,run_kind="human_cores")
            print("历史报告上传成功：",url,flush=True)
            return 0
        except (UploadError,OSError,ValueError) as exc:
            print("上传失败：",exc,file=sys.stderr)
            return 1
    folder=REPORT_ROOT/(datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
                        +"_"+uuid4().hex[:8])
    folder.mkdir(parents=True,exist_ok=False)
    started=datetime.now(timezone.utc).isoformat()
    print("Human b18 单核/三核性能报告：",folder,flush=True)
    log_path=folder/"human_cores.txt"
    try:
        code=run(args.serial,log_path)
    except (OSError,subprocess.SubprocessError) as exc:
        code=1
        with log_path.open("a",encoding="utf-8") as file:
            file.write("RUN ERROR: "+str(exc)+"\n")
    raw=log_path.read_text(encoding="utf-8")
    parsed=parse_output(raw)
    result=parsed["result"] or {}
    modes=parsed["modes"]
    core3_ok=result.get("cores3Available")=="1"
    success=(code==0 and result.get("status")=="PASS"
             and "core0_before" in modes and "core0_after" in modes
             and (not core3_ok or "core012" in modes)
             and all(len(parsed["samples"].get(mode,[]))==info["runs"]
                     for mode,info in modes.items()))
    if code==0 and not success: code=1
    status="PASS" if success else ("INTERRUPTED" if code==130 else "FAIL")
    report={
        "test":"RK3588 Human b18 9x9 one-core versus three-core NPU benchmark",
        "model":"b18c384nbt-humanv0","board":9,
        "syntheticInputsOnly":True,"cpuFallbackAvailable":False,
        "serial":args.serial,"status":status,"exit_code":code,
        "started_utc":started,"finished_utc":datetime.now(timezone.utc).isoformat(),
        "core3Available":core3_ok,
        "core0MedianRunMs":float(result["core0RunMedianMs"]) if success else None,
        "core3MedianRunMs":float(result["core3RunMedianMs"]) if success and core3_ok else None,
        "core3VsCore0Ratio":float(result["core3VsCore0Ratio"]) if success and core3_ok else None,
        "maxOutputDelta":float(result["maxOutputDelta"]) if success else None,
        "phases":modes,
        "errors":[s[-450:] for s in raw.splitlines()
                  if "FAIL" in s or "ERROR" in s or "UNAVAILABLE" in s][-15:]
    }
    (folder/"human_cores.json").write_text(
        json.dumps(parsed,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    (folder/"report.json").write_text(
        json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print("测试结果：",status,"三核支持：",core3_ok,
          "本地报告：",folder/"report.json",flush=True)
    if not args.no_upload:
        print("上传报告到公开 GitHub ui-test-results 分支（包含设备标识和测试日志）",flush=True)
        try:
            url=upload_referee_results(folder,report,remote=args.upload_remote,
                                       repo_root=REPO,run_kind="human_cores")
            print("上传结果：",url,flush=True)
        except (UploadError,OSError,ValueError) as exc:
            print("上传失败，本地结果保留：",exc,file=sys.stderr,flush=True)
    else:
        print("报告仅保存在本地。",flush=True)
    return code


if __name__=="__main__":
    sys.exit(main())
