#!/usr/bin/env python3
"""Replay real 9x9 SGFs on Android and validate read-only KataGo NN estimates.

Each debug session verifies backend, NN area points, win rates, dialog and unchanged game.
No adjudication, death marking, manual scoring, MCTS or confirmation workflow.
"""
import argparse
from datetime import datetime, timezone
import json
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path
from uuid import uuid4
import xml.etree.ElementTree as ET

from upload_referee_test_results import UploadError, upload_referee_results

PROJECT = Path(__file__).resolve().parents[1]
PACKAGE = "com.badukai.java"
MAIN_ACTIVITY_CLASS = "com.badukai.MainActivity"
ACTIVITY = PACKAGE + "/" + MAIN_ACTIVITY_CLASS
CASES = ("cgos9_expand_006_12", "cgos9_1", "cgos9_2", "cgos9_5")
STRICT_CASE = "cgos9_expand_006_12"


def run(argv, timeout=30, check=True):
    result = subprocess.run(argv, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError("Command failed (%d): %s\n%s" %
                           (result.returncode, " ".join(map(str,argv)), result.stdout[-1800:]))
    return result.stdout


def connected_serial(adb, requested):
    lines = run([adb,"devices"]).splitlines()[1:]
    devices = [line.split()[0] for line in lines if line.split()[1:2] == ["device"]]
    if requested:
        if requested not in devices:
            raise RuntimeError("ADB device %s not ready; connected: %s" % (requested,devices))
        return requested
    if len(devices) != 1:
        raise RuntimeError("Provide --serial; detected devices: %s" % devices)
    return devices[0]


def selected_cases(game=None, games=None, all_games=False):
    if sum(bool(x) for x in (game,games,all_games)) > 1:
        raise ValueError("Choose only one of --game, --games, --all")
    names = list(CASES) if all_games else ([name.strip() for name in games.split(",") if name.strip()]
                                           if games else [game or STRICT_CASE])
    if not names or len(names) != len(set(names)):
        raise ValueError("Empty or duplicate SGF case names")
    unknown = [name for name in names if name not in CASES]
    if unknown:
        raise ValueError("Unsupported debug SGF %s; available: %s" % (unknown,", ".join(CASES)))
    return names


def smoke_event(log, run_id):
    """Require unique run ID; don't mistake a prior PASS for this run."""
    final = None
    for line in log.splitlines():
        match = re.search(r"\b(PASS|FAIL) id=" + re.escape(run_id) + r"\b",line)
        if "RefereeSmoke" in line and match:
            final = {"status":match.group(1),"line":line.strip()}
    return final


def read_referee_log(adb,serial,run_id):
    """Fetch app smoke events with short ADB commands, trying both log buffers.

    Keep diagnostic output when Android logcat filters unexpectedly return empty.
    Every parsed event is still matched against the current unique run ID.
    """
    primary = run([adb,"-s",serial,"logcat","-d","-v","brief",
                   "-s","RefereeSmoke:I","RefereeSmoke:E"],timeout=15)
    if "id="+run_id in primary:
        return primary, "tag-filter"
    fallback = run([adb,"-s",serial,"shell","logcat","-d","-b","all",
                    "-t","1500","-v","brief"],timeout=15)
    fallback_events = "\n".join(line for line in fallback.splitlines()
                                if "RefereeSmoke" in line or "AndroidRuntime" in line)
    if "id="+run_id in fallback_events:
        return fallback_events, "buffer-all"
    return primary+"\n"+fallback_events, "no-current-event"


def read_dialog_root(adb,serial):
    target = "/sdcard/referee_smoke_window.xml"
    run([adb,"-s",serial,"shell","uiautomator","dump",target],timeout=35)
    content = run([adb,"-s",serial,"shell","cat",target],timeout=15)
    start = content.find("<?xml")
    if start < 0:
        raise RuntimeError("UI Automator did not return an XML hierarchy")
    return ET.fromstring(content[start:])


def read_dialog_xml(adb,serial,root=None):
    if root is None: root = read_dialog_root(adb,serial)
    return " ".join(node.attrib.get("text","") for node in root.iter())


def click_ui_text(adb,serial,label,root=None):
    if root is None: root = read_dialog_root(adb,serial)
    for node in root.iter():
        if node.attrib.get("text") != label:
            continue
        bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",node.attrib.get("bounds",""))
        if bounds:
            x1,y1,x2,y2 = map(int,bounds.groups())
            if x2>x1 and y2>y1:
                run([adb,"-s",serial,"shell","input","tap",
                     str((x1+x2)//2),str((y1+y2)//2)],timeout=12)
                return
    raise RuntimeError("Cannot find visible UI button: "+label)


def capture_screenshot(adb,serial,target):
    proc = subprocess.run([adb,"-s",serial,"exec-out","screencap","-p"],
                          stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=25)
    if proc.returncode or not proc.stdout.startswith(b"\x89PNG\r\n\x1a\n"):
        raise RuntimeError("ADB screenshot failed")
    target.write_bytes(proc.stdout)


def validate_dialog(text,game=STRICT_CASE,midgame=False):
    required = ("智能裁判 · NN预估","黑棋 ","白棋 ","胜率 黑 "," · 白 ",
                "目","继续下棋" if midgame else "关闭")
    errors = [part for part in required if part not in text]
    if "领先" not in text and "双方持平" not in text:
        errors.append("missing NN lead")
    for removed in ("确认中国规则数子","恢复 AI 建议","已复核并确认","复核死子",
                    "争议标记仍保留","双方目数按 NN 地盘归属估算","建议复核死活",
                    "不是确认数子结果","不代表已经判定死活"):
        if removed in text:
            errors.append("deprecated UI: "+removed)
    return errors


def nn_event_values(line,backend,midgame):
    match = re.search(r"\bstage=nn_dialog\b.*\bbackend=(\S+) moves=(\d+) terminal=(true|false)"
                      r" whiteLead=([-+0-9.eE]+) blackWin=([-+0-9.eE]+) whiteWin=([-+0-9.eE]+)"
                      r" blackPoints=([-+0-9.eE]+) whitePoints=([-+0-9.eE]+) nnMs=(\d+)",line)
    if not match:
        raise ValueError("Missing validated NN result fields")
    actual,moves,terminal,lead,bwin,wwin,black,white,ms = match.groups()
    if backend=="gpu" and not actual.upper().startswith("GPU"):
        raise ValueError("GPU requested but backend was "+actual)
    if backend=="cpu" and not actual.upper().startswith("CPU"):
        raise ValueError("CPU requested but backend was "+actual)
    if (terminal=="true") == midgame:
        raise ValueError("Wrong terminal/midgame state")
    values = [float(value) for value in (lead,bwin,wwin,black,white)]
    if not all(__import__("math").isfinite(value) for value in values):
        raise ValueError("Nonfinite NN values")
    if not (0<=values[1]<=1 and 0<=values[2]<=1 and 0<=values[3]<=81 and 0<=values[4]<=88):
        raise ValueError("NN values out of range")
    if abs(values[3]+values[4]-88)>0.025:
        raise ValueError("Area points must sum to 81+7 (9x9 Chinese komi)")
    if int(moves)<4:
        raise ValueError("SGF replay was too short")
    return {"backend":actual,"moves":int(moves),"terminal":terminal=="true",
            "whiteLead":values[0],"blackWin":values[1],"whiteWin":values[2],
            "blackPoints":values[3],"whitePoints":values[4],"nnMs":int(ms)}


def dismiss_event(log,run_id):
    for line in reversed(log.splitlines()):
        if "RefereeSmoke" in line and re.search(r"\bNN_DISMISS id="+re.escape(run_id)+r"\b",line):
            return line.strip()
    return None


def run_one_case(adb,serial,backend,game,timeout,skip_ui,out_dir,midgame=False):
    case_dir = out_dir / (game+"_midgame" if midgame else game)
    case_dir.mkdir(parents=True,exist_ok=True)
    run_id = uuid4().hex[:16]
    result = {"game":game,"run_id":run_id,"status":"ERROR","backend":backend,
              "inspection":"midgame" if midgame else "terminal"}
    try:
        print("\n--- %s [%s] ---" % (game,backend),flush=True)
        print("  [1/7] Starting isolated Android activity (id=%s)..." % run_id,flush=True)
        run([adb,"-s",serial,"shell","am","force-stop",PACKAGE],timeout=15)
        # Never wait for Activity idle (-W). GPU initialization and NN inference
        # are asynchronous; RefereeSmoke PASS/FAIL is our actual completion event.
        activity = run([adb,"-s",serial,"shell","am","start","-n",ACTIVITY,
                        "--ez","referee_smoke","true","--es","referee_smoke_id",run_id,
                        "--es","referee_smoke_game",game,"--es","referee_smoke_backend",backend,
                        "--ez","referee_smoke_midgame",str(midgame).lower()],timeout=20)
        (case_dir/"activity.txt").write_text(activity,encoding="utf-8")
        print("  [2/7] Activity started; waiting for NN result (limit %.0fs)..." % timeout,flush=True)
        if "Error:" in activity or "Exception" in activity:
            raise RuntimeError("Could not launch debug activity: "+activity)
        deadline = time.monotonic()+timeout
        terminal = None
        log = ""
        last_progress = time.monotonic()
        while time.monotonic()<deadline:
            log, source = read_referee_log(adb,serial,run_id)
            terminal = smoke_event(log,run_id)
            if terminal:
                print("  RefereeSmoke event found via %s." % source,flush=True)
                break
            if time.monotonic()-last_progress>=10:
                seen = [line.strip() for line in log.splitlines() if "RefereeSmoke" in line]
                print("  Waiting for NN PASS/FAIL (id=%s, source=%s, events=%d, %.0fs left)..." %
                      (run_id,source,len(seen),max(0,deadline-time.monotonic())),flush=True)
                if seen:
                    print("  Latest device event: "+seen[-1][:250],flush=True)
                last_progress=time.monotonic()
            time.sleep(2)
        (case_dir/"logcat.txt").write_text(log,encoding="utf-8")
        if terminal is None:
            raise RuntimeError("Timed out waiting for NN result")
        result["app_result"] = terminal
        print(terminal["line"],flush=True)
        if terminal["status"]!="PASS":
            raise RuntimeError("Android NN assertion failed (see logcat.txt)")
        if "game="+game not in terminal["line"]:
            raise RuntimeError("Wrong SGF in result")
        if "stage=nn_dialog" not in terminal["line"]:
            raise RuntimeError("Old referee flow used instead of NN dialog")
        result["nn"] = nn_event_values(terminal["line"],backend,midgame)
        print("  [3/7] NN OK: %.0f ms, B=%.3f W=%.3f" %
              (result["nn"]["nnMs"],result["nn"]["blackPoints"],result["nn"]["whitePoints"]),flush=True)
        if not skip_ui:
            print("  [4/7] Reading Android dialog hierarchy (uiautomator, up to 35s)...",flush=True)
            root = read_dialog_root(adb,serial)
            dialog = read_dialog_xml(adb,serial,root)
            (case_dir/"dialog.txt").write_text(dialog+"\n",encoding="utf-8")
            errors = validate_dialog(dialog,game,midgame=midgame)
            if errors:
                raise RuntimeError("NN dialog UI mismatch: %s" % errors)
            print("  [5/7] Saving NN dialog screenshot...",flush=True)
            capture_screenshot(adb,serial,case_dir/"nn_dialog.png")
            print("  [6/7] Clicking dialog button (reuse same UI hierarchy)...",flush=True)
            click_ui_text(adb,serial,"继续下棋" if midgame else "关闭",root)
            dismiss = None
            dismiss_log = ""
            deadline = time.monotonic()+20
            while time.monotonic()<deadline:
                dismiss_log, _ = read_referee_log(adb,serial,run_id)
                dismiss = dismiss_event(dismiss_log,run_id)
                if dismiss:
                    break
                time.sleep(1)
            (case_dir/"dismiss_logcat.txt").write_text(dismiss_log,encoding="utf-8")
            count = result["nn"]["moves"]
            state = "false" if midgame else "true"
            if dismiss is None or "moves="+str(count) not in dismiss or "terminal="+state not in dismiss:
                raise RuntimeError("Dismissing NN dialog changed the game: "+str(dismiss))
            print("  [7/7] Button acknowledged; capturing dismissed screen...",flush=True)
            capture_screenshot(adb,serial,case_dir/"dismissed.png")
            result["dismiss"] = dismiss
            print("NN UI PASS: metrics shown; dialog dismissed; board unchanged.",flush=True)
        else:
            print("  UI hierarchy / screenshots / dismissal intentionally skipped (--skip-ui).",flush=True)
        result["status"] = "PASS"
    except (OSError,RuntimeError,ValueError,subprocess.TimeoutExpired,ET.ParseError) as exc:
        result["status"] = "FAIL"
        result["error"] = str(exc)
        print("FAIL %s: %s" % (game,exc),file=sys.stderr,flush=True)
    finally:
        (case_dir/"result.json").write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial",help="ADB device ID")
    parser.add_argument("--adb",default=shutil.which("adb"))
    parser.add_argument("--apk",type=Path,default=PROJECT/"app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--backend",choices=("cpu","gpu"),default="cpu")
    parser.add_argument("--midgame",action="store_true",
                        help="Replay SGF without terminal passes; inspect while game is still running")
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--game",choices=CASES,help="Single fixture (default: cgos9_expand_006_12)")
    selection.add_argument("--games",help="Comma-separated subset of known fixture names")
    selection.add_argument("--all",action="store_true",help="Run all curated SGFs, one fresh app session per game")
    parser.add_argument("--build",action="store_true",help="Build Debug APK before install")
    parser.add_argument("--skip-install",action="store_true",help="Use already installed Debug APK")
    parser.add_argument("--skip-ui",action="store_true",help="Skip uiautomator hierarchy check")
    parser.add_argument("--timeout",type=float,default=240,help="Seconds allowed PER game")
    parser.add_argument("--no-upload",action="store_true",
                        help="Opt out of the existing ui-test-results GitHub uploader")
    parser.add_argument("--upload-remote",default="auto",
                        help="Git remote to publish using existing credentials (auto: origin/github)")
    parser.add_argument("--upload-branch",default="ui-test-results",
                        help="Separate GitHub results branch (unchanged source branch)")
    parser.add_argument("--upload-existing",type=Path,
                        help="Upload an earlier local report directory (no ADB/build required)")
    args = parser.parse_args()
    if args.upload_existing:
        report_dir = args.upload_existing.resolve()
        if not (report_dir/"report.json").is_file():
            parser.error("--upload-existing requires a directory containing report.json")
        if args.no_upload:
            parser.error("--no-upload cannot be used with --upload-existing")
        try:
            report = json.loads((report_dir/"report.json").read_text(encoding="utf-8"))
            upload_referee_results(report_dir,report,args.upload_remote,args.upload_branch)
            return 0
        except (UploadError,OSError,ValueError,RuntimeError) as exc:
            print("Existing report upload failed:",exc,file=sys.stderr,flush=True)
            return 1
    if not args.adb:
        parser.error("adb not found; set --adb")
    if args.timeout<15:
        parser.error("--timeout must be >= 15 seconds")
    try:
        games = selected_cases(args.game,args.games,args.all)
    except ValueError as exc:
        parser.error(str(exc))
    out_dir = PROJECT/"build/android_referee_smoke"/datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    out_dir.mkdir(parents=True,exist_ok=True)
    report = {"status":"ERROR","backend_requested":args.backend,
              "inspection":"midgame" if args.midgame else "terminal","cases":[],"outputs":str(out_dir)}
    code = 1
    upload_error = None
    try:
        serial = connected_serial(args.adb,args.serial)
        report["serial"] = serial
        if args.build:
            gradle = [str(PROJECT/"gradlew"),"assembleDebug"]
            if args.backend=="gpu":
                gradle.append("-PenableKataGoGpuJni=true")
            print("Building DEBUG APK...",flush=True)
            proc = subprocess.run(gradle,cwd=PROJECT,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
            (out_dir/"gradle.log").write_text(proc.stdout,encoding="utf-8")
            if proc.returncode:
                raise RuntimeError("Gradle build failed; check %s" % (out_dir/"gradle.log"))
            print(run([sys.executable,str(PROJECT/"tools/check_android_16kb.py"),str(args.apk)],timeout=45),flush=True)
        if not args.skip_install:
            if not args.apk.is_file():
                raise RuntimeError("Missing DEBUG APK: %s (use --build)" % args.apk)
            print("Installing:",args.apk,flush=True)
            print(run([args.adb,"-s",serial,"install","-r","-t",str(args.apk)],timeout=120).strip(),flush=True)
        for game in games:
            report["cases"].append(run_one_case(args.adb,serial,args.backend,game,args.timeout,
                                                args.skip_ui,out_dir,midgame=args.midgame))
        good = sum(case["status"]=="PASS" for case in report["cases"])
        report["status"] = "PASS" if good==len(games) else "FAIL"
        print("\nSummary: %d/%d PASS; %s" % (good,len(games),report["status"]),flush=True)
        code = 0 if report["status"]=="PASS" else 1
    except KeyboardInterrupt:
        report["status"] = "INTERRUPTED"
        report["error"] = "Ctrl+C during automatic referee test"
        code = 130
        print("\nInterrupted; preserving local data and attempting GitHub upload...",flush=True)
    except (OSError,RuntimeError,subprocess.TimeoutExpired) as exc:
        report["status"] = "FAIL"
        report["error"] = str(exc)
        print("FAIL:",exc,file=sys.stderr,flush=True)
        code = 1
    except Exception as exc:
        report["status"] = "ERROR"
        report["error"] = type(exc).__name__+": "+str(exc)
        print("ERROR:",exc,file=sys.stderr,flush=True)
        code = 1
    finally:
        try:
            (out_dir/"report.json").write_text(
                json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
            print("Report:",out_dir/"report.json",flush=True)
        except OSError as exc:
            print("Cannot write final local report:",exc,file=sys.stderr,flush=True)
            upload_error = str(exc)
        if not args.no_upload:
            try:
                link = upload_referee_results(out_dir,report,args.upload_remote,args.upload_branch)
                report["upload_url"] = link
                report["upload_status"] = "PASS"
            except (UploadError,OSError,RuntimeError,KeyboardInterrupt) as exc:
                upload_error = str(exc)
                report["upload_status"] = "FAIL"
                report["upload_error"] = upload_error
                print("GitHub auto-upload failed; local reports remain:",exc,
                      file=sys.stderr,flush=True)
        else:
            report["upload_status"] = "SKIPPED"
        try:
            (out_dir/"report.json").write_text(
                json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
        except OSError:
            pass
    return code if not upload_error else (code if code==130 else 1)


if __name__=="__main__":
    sys.exit(main())
