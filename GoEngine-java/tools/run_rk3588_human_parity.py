#!/usr/bin/env python3
"""Run the RK3588 Human b18 parity test, save results and publish to ui-test-results.

The GitHub results branch is PUBLIC. Upload includes the board serial and
unredacted test output, but NEVER includes model weights, unrelated files,
git credentials, or the 37 KB golden-reference binary.
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
SCRIPT = ROOT / "tools/test_rk3588_human_parity.sh"
OUTPUT = ROOT / "build/rk3588_human/parity_reports"
HEAD_RE = re.compile(
    r"^HUMAN_HEAD_PARITY case=(\d+) name=(\w+) "
    r"maxAbs=([\d.eE+-]+) meanAbs=([\d.eE+-]+) "
    r"worstIndex=(\d+) cpu=([\d.eE+-]+) npu=([\d.eE+-]+) "
    r"outOfTolerance=(\d+) status=(PASS|FAIL)\b"
)
SUMMARY_RE = re.compile(
    r"^HUMAN_CPU_NPU_PARITY (PASS|FAIL) cases=(\d+) heads=(\d+) "
    r"badHeads=(\d+) maxAbs=([\d.eE+-]+) atol=([\d.eE+-]+) rtol=([\d.eE+-]+)"
)


def parse_output(output):
    heads = []
    summary = None
    for line in output.splitlines():
        hit = HEAD_RE.match(line)
        if hit:
            case, name, max_abs, mean_abs, worst, cpu, npu, bad, status = hit.groups()
            heads.append({
                "case": int(case), "name": name, "maxAbs": float(max_abs),
                "meanAbs": float(mean_abs), "worstIndex": int(worst),
                "cpu": float(cpu), "npu": float(npu),
                "outOfTolerance": int(bad), "status": status,
            })
        hit = SUMMARY_RE.match(line)
        if hit:
            status, cases, count, bad, max_abs, atol, rtol = hit.groups()
            summary = {
                "status": status, "cases": int(cases), "heads": int(count),
                "badHeads": int(bad), "maxAbs": float(max_abs),
                "atol": float(atol), "rtol": float(rtol),
            }
    return {"summary": summary, "heads": heads}


def run_test(serial, log_path):
    env = os.environ.copy()
    env["HUMAN_PARITY_CHILD"] = "1"
    with log_path.open("w", encoding="utf-8") as output:
        proc = subprocess.Popen(
            ["bash", str(SCRIPT), serial],
            cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace", bufsize=1,
        )
        try:
            for line in proc.stdout:
                sys.stdout.write(line)
                sys.stdout.flush()
                output.write(line)
                output.flush()
            return proc.wait()
        except KeyboardInterrupt:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()
            output.write("\nTest interrupted (Ctrl+C)\n")
            return 130


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial", nargs="?", default=os.environ.get(
        "ANDROID_SERIAL", "8719e18a71a2a66c"))
    parser.add_argument("--no-upload", action="store_true",
                        help="Keep the report locally; do not publish publicly")
    parser.add_argument("--upload-existing", type=Path,
                        help="Upload an already saved parity report without running NPU again")
    parser.add_argument("--upload-remote", default="auto", help="Git remote: origin/github/auto")
    args = parser.parse_args(argv)
    board = int(os.environ.get("HUMAN_RKNN_BOARD_SIZE", "9"))
    if board not in (9, 13, 19):
        parser.error("HUMAN_RKNN_BOARD_SIZE must be 9, 13 or 19")
    if args.no_upload and args.upload_existing:
        parser.error("--upload-existing cannot be used with --no-upload")

    if args.upload_existing:
        folder = args.upload_existing.resolve()
        try:
            report = json.loads((folder / "report.json").read_text(encoding="utf-8"))
            url = upload_referee_results(folder, report,
                remote=args.upload_remote, repo_root=REPO, run_kind="human_rknn_parity")
            print("已上传此前的测试结果：", url, flush=True)
            return 0
        except (UploadError, OSError, ValueError) as exc:
            print("上传失败：", exc, file=sys.stderr)
            return 1

    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    folder = OUTPUT / (timestamp + "_" + uuid4().hex[:8])
    folder.mkdir(parents=True, exist_ok=False)
    started = datetime.now(timezone.utc).isoformat()
    print("精度报告目录：", folder, flush=True)
    try:
        exit_code = run_test(args.serial, folder / "human_parity.txt")
    except (OSError, subprocess.SubprocessError) as exc:
        exit_code = 1
        with (folder / "human_parity.txt").open("a", encoding="utf-8") as f:
            f.write("\nRUN ERROR: " + str(exc) + "\n")
        print("运行失败：", exc, file=sys.stderr)
    log = (folder / "human_parity.txt").read_text(encoding="utf-8")
    metrics = parse_output(log)
    summary = metrics["summary"]
    success = (
        exit_code == 0 and summary is not None and
        summary["status"] == "PASS" and summary["cases"] == 4 and
        summary["heads"] == 20 and summary["badHeads"] == 0 and
        len(metrics["heads"]) == 20 and
        all(h["status"] == "PASS" for h in metrics["heads"])
    )
    status = "PASS" if success else ("INTERRUPTED" if exit_code == 130 else "FAIL")
    if exit_code == 0 and not success:
        exit_code = 1
    report = {
        "test": "RK3588 Human b18 original ONNX CPU versus RKNN NPU parity",
        "status": status, "serial": args.serial, "board": board,
        "model": "b18c384nbt-humanv0", "exit_code": exit_code,
        "started_utc": started,
        "finished_utc": datetime.now(timezone.utc).isoformat(),
        "summary": summary, "heads": metrics["heads"],
        "syntheticInputsOnly": True, "nativeRankFeaturesVerified": False,
        "errors": [] if success else [line for line in log.splitlines()
                                    if "FAIL" in line or "ERROR" in line][-12:],
    }
    (folder / "report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (folder / "parity_summary.json").write_text(
        json.dumps(metrics, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("本地报告：", folder / "report.json", flush=True)

    if args.no_upload:
        print("已禁用 GitHub 公开上传。", flush=True)
    else:
        print("正在上传到 GitHub 公开 ui-test-results 分支（包含测试日志及设备序列号）。", flush=True)
        try:
            url = upload_referee_results(folder, report,
                remote=args.upload_remote, repo_root=REPO, run_kind="human_rknn_parity")
            print("已上传测试结果：", url, flush=True)
        except (UploadError, OSError, ValueError) as exc:
            print("GitHub 上传失败；本地报告已保留：", exc, file=sys.stderr)
            print("可重试：bash tools/test_rk3588_human_parity.sh --upload-existing "
                  + str(folder), flush=True)
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
