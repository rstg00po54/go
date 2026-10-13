#!/usr/bin/env python3
"""Run the REAL Android smart-referee dialog against curated scored SGFs.

Debug-only intent, no screen taps. Builds/installs once, then starts an isolated
app session per case; checks Kotlin-free Java referee, KataGo GTP and UI dialog.
Official SGF RE is NOT treated as ground-truth for dead-stone adjudication.
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


def resume_event(log,run_id):
    """Last matching RESUME / RESUME_FAIL event after tapping Continue."""
    result = None
    for line in log.splitlines():
        if "RefereeSmoke" not in line:
            continue
        if re.search(r"\bRESUME_FAIL id="+re.escape(run_id)+r"\b",line):
            result = {"status":"FAIL","line":line.strip()}
        elif re.search(r"\bRESUME id="+re.escape(run_id)+r"\b",line):
            result = {"status":"PASS","line":line.strip()}
    return result


def midgame_resume_event(log,run_id):
    """Midgame dismiss keeps every move, unlike terminal-pass undo."""
    for line in reversed(log.splitlines()):
        if "RefereeSmoke" in line and re.search(r"\bINSPECT_RESUME id="+re.escape(run_id)+r"\b",line):
            return line.strip()
    return None


def armed_event(log,run_id):
    """Ensure dialog Continue only ARMS a future tap; it must not undo passes."""
    result = None
    for line in log.splitlines():
        if "RefereeSmoke" in line and re.search(r"\bARMED id="+re.escape(run_id)+r"\b",line):
            result = line.strip()
    return result


def overlay_marks(log,run_id):
    # Ground highlighted positions in the real Java board, not only UI labels.
    result = None
    for line in log.splitlines():
        if "RefereeSmoke" not in line:
            continue
        match = re.search(r"\bOVERLAY id="+re.escape(run_id)
                          +r"\b dead=(\d+) review=(\d+) alerts=(\d+)\b",line)
        if match:
            result = {"dead":int(match.group(1)),"review":int(match.group(2)),
                      "alerts":int(match.group(3))}
    return result


def read_dialog_root(adb,serial):
    target = "/sdcard/referee_smoke_window.xml"
    run([adb,"-s",serial,"shell","uiautomator","dump",target],timeout=35)
    content = run([adb,"-s",serial,"shell","cat",target],timeout=15)
    start = content.find("<?xml")
    if start < 0:
        raise RuntimeError("UI Automator did not return an XML hierarchy")
    return ET.fromstring(content[start:])


def read_dialog_xml(adb,serial):
    return " ".join(node.attrib.get("text","") for node in read_dialog_root(adb,serial).iter())


def click_ui_text(adb,serial,label):
    # Click the exact visible action rather than relying on screen resolution.
    root = read_dialog_root(adb,serial)
    for node in root.iter():
        if node.attrib.get("text") != label:
            continue
        bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",node.attrib.get("bounds",""))
        if not bounds:
            break
        x1,y1,x2,y2 = map(int,bounds.groups())
        if x1>=x2 or y1>=y2:
            break
        run([adb,"-s",serial,"shell","input","tap",
             str((x1+x2)//2),str((y1+y2)//2)],timeout=12)
        return
    raise RuntimeError("Cannot find clickable visible UI label: "+label)


def click_ui_description(adb,serial,description):
    # This is a full-screen transparent tap target, not a Go intersection.
    root = read_dialog_root(adb,serial)
    for node in root.iter():
        if node.attrib.get("content-desc") != description:
            continue
        bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",node.attrib.get("bounds",""))
        if bounds and node.attrib.get("clickable") == "true":
            x1,y1,x2,y2 = map(int,bounds.groups())
            if x2>x1 and y2>y1:
                run([adb,"-s",serial,"shell","input","tap",
                     str((x1+x2)//2),str((y1+y2)//2)],timeout=12)
                return
    raise RuntimeError("Missing clickable full-screen referee overlay: "+description)


def validate_dialog(text,game=STRICT_CASE,midgame=False):
    # Both stages: popup with live board marks, then tap-to-dismiss.
    if midgame:
        required = ("智能裁判 · 当前棋形分析","正在对局","红叉","黄圈","橙圈",
                    "继续下棋","争议标记仍保留","不会撤销落子")
    elif game != "cgos9_1":
        required = ("智能裁判 · 存在争议","棋盘上已标出争议","红叉","黄圈","橙圈",
                    "继续下棋","争议标记仍保留","再点屏幕任意位置")
    else:
        required = ("智能裁判","黄圈","确认数子")
    return [part for part in required if part not in text]


def capture_screenshot(adb,serial,target):
    """Grab original Android framebuffer bytes; no cropping or conversion."""
    proc = subprocess.run([adb,"-s",serial,"exec-out","screencap","-p"],
                          stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=25)
    if proc.returncode or not proc.stdout.startswith(b"\x89PNG\r\n\x1a\n"):
        raise RuntimeError("ADB screenshot failed: " + proc.stderr.decode("utf-8","replace")[-300:])
    target.write_bytes(proc.stdout)


def validate_review_panel(text,game=STRICT_CASE):
    if game != "cgos9_1":
        raise ValueError("Disputed endgames use the board overlay, not a scoring toolbar")
    required = ("智能裁判 ·","黑","白","恢复建议","确认数子","退出复核")
    missing = [part for part in required if part not in text]
    if "继续下棋" in text:
        missing.append("unexpected continue-playing action")
    return missing


def validate_dispute_overlay(root):
    # A full-screen hit target must sit above the board. No dialog should
    # prevent the player from seeing the marked stones.
    buttons = [node for node in root.iter()
               if node.attrib.get("content-desc") == "点击屏幕继续下棋"
               and node.attrib.get("clickable") == "true"]
    return [] if buttons else ["transparent full-screen tap overlay missing"]


def validate_confirmation_dialog(text,game):
    if game != "cgos9_1":
        raise ValueError("Only READY_TO_COUNT games open a scoring confirmation dialog")
    required = ("确认中国规则数子","已复核并确认")
    return [part for part in required if part not in text]


def run_one_case(adb,serial,backend,game,timeout,skip_ui,out_dir,midgame=False):
    case_dir = out_dir / (game+"_midgame" if midgame else game)
    case_dir.mkdir(parents=True,exist_ok=True)
    run_id = uuid4().hex[:16]
    result = {"game":game,"run_id":run_id,"status":"ERROR","backend":backend,
              "inspection":"midgame" if midgame else "terminal"}
    try:
        print("\n--- %s [%s] ---" % (game,backend),flush=True)
        run([adb,"-s",serial,"shell","am","force-stop",PACKAGE])
        start = run([adb,"-s",serial,"shell","am","start","-W","-n",ACTIVITY,
                     "--ez","referee_smoke","true",
                     "--es","referee_smoke_id",run_id,
                     "--es","referee_smoke_game",game,
                     "--es","referee_smoke_backend",backend,
                     "--ez","referee_smoke_midgame",str(midgame).lower()],timeout=35)
        (case_dir/"activity.txt").write_text(start,encoding="utf-8")
        if "Error:" in start or "Exception" in start:
            raise RuntimeError("Failed to start Activity: " + start)
        deadline = time.monotonic()+timeout
        log = ""
        terminal = None
        while time.monotonic()<deadline:
            log = run([adb,"-s",serial,"logcat","-d","-v","brief",
                       "-s","RefereeSmoke:I","AndroidRuntime:E","*:S"],timeout=20)
            terminal = smoke_event(log,run_id)
            if terminal:
                break
            time.sleep(2)
        (case_dir/"logcat.txt").write_text(log,encoding="utf-8")
        if terminal is None:
            raise RuntimeError("Timed out waiting for referee terminal event; inspect logcat.txt")
        result["app_result"] = terminal
        print(terminal["line"],flush=True)
        if terminal["status"] != "PASS":
            raise RuntimeError("Android referee assertion failed (see logcat.txt)")
        if "game="+game not in terminal["line"]:
            raise RuntimeError("Incorrect SGF in terminal event")
        if not skip_ui:
            if game=="cgos9_1" and not midgame:
                dialog = read_dialog_xml(adb,serial)
                (case_dir/"dialog.txt").write_text(dialog+"\n",encoding="utf-8")
                missing = validate_dialog(dialog,game)
                if missing:
                    raise RuntimeError("Ready-to-count dialog missing: %s" % missing)
                capture_screenshot(adb,serial,case_dir/"analysis_dialog.png")
                run([adb,"-s",serial,"shell","input","keyevent","4"],timeout=10)
                panel = read_dialog_xml(adb,serial)
                (case_dir/"review_panel.txt").write_text(panel+"\n",encoding="utf-8")
                missing_panel = validate_review_panel(panel,game)
                if missing_panel:
                    raise RuntimeError("Ready-to-count toolbar missing: %s" % missing_panel)
                capture_screenshot(adb,serial,case_dir/"review_screen.png")
                click_ui_text(adb,serial,"确认数子")
                confirmation = read_dialog_xml(adb,serial)
                (case_dir/"confirmation_dialog.txt").write_text(confirmation+"\n",encoding="utf-8")
                missing_confirmation = validate_confirmation_dialog(confirmation,game)
                if missing_confirmation:
                    raise RuntimeError("Final scoring confirmation missing: %s" % missing_confirmation)
                capture_screenshot(adb,serial,case_dir/"confirmation_dialog.png")
                print("Ready-to-count UI PASS: scoring dialog opens.",flush=True)
            else:
                expected_stage = "midgame_dialog" if midgame else "dispute_dialog"
                if "stage="+expected_stage not in terminal["line"]:
                    raise RuntimeError("Expected referee "+expected_stage+" before screen touch")
                marks = overlay_marks(log,run_id)
                if marks is None or (not midgame and sum(marks.values())==0):
                    raise RuntimeError("Referee dispute mark log missing or terminal marks empty")
                result["marks"] = marks
                first = read_dialog_xml(adb,serial)
                (case_dir/"dialog.txt").write_text(first+"\n",encoding="utf-8")
                missing = validate_dialog(first,game,midgame=midgame)
                if missing:
                    raise RuntimeError("Initial dispute dialog missing: %s" % missing)
                capture_screenshot(adb,serial,case_dir/"analysis_dialog.png")
                if not validate_dispute_overlay(read_dialog_root(adb,serial)):
                    raise RuntimeError("Tap-to-dismiss unexpectedly armed before Continue")
                # Stage one: the modal disappears, BUT the game and marks remain.
                click_ui_text(adb,serial,"继续下棋")
                armed = None
                arm_log = ""
                deadline = time.monotonic()+20
                while time.monotonic()<deadline:
                    arm_log = run([adb,"-s",serial,"logcat","-d","-v","brief",
                                   "-s","RefereeSmoke:I","AndroidRuntime:E","*:S"],timeout=20)
                    armed = armed_event(arm_log,run_id)
                    if armed:
                        break
                    time.sleep(1)
                (case_dir/"armed_logcat.txt").write_text(arm_log,encoding="utf-8")
                before = int(re.search(r"\bmoves=(\d+)\b",terminal["line"]).group(1))
                original_terminal = "false" if midgame else "true"
                if armed is None or "moves="+str(before) not in armed or ("terminal="+original_terminal) not in armed:
                    raise RuntimeError("First Continue incorrectly changed the current game: "+str(armed))
                if resume_event(arm_log,run_id):
                    raise RuntimeError("First Continue resumed before the required screen tap")
                root = read_dialog_root(adb,serial)
                missing = validate_dispute_overlay(root)
                if missing:
                    raise RuntimeError("Marks and screen tap target not retained: %s" % missing)
                panel = " ".join(node.attrib.get("text","") for node in root.iter())
                (case_dir/"review_panel.txt").write_text(panel+"\n",encoding="utf-8")
                capture_screenshot(adb,serial,case_dir/"review_screen.png")
                print("First Continue PASS: popup closed, dispute marks retained, game unchanged.",flush=True)
                # Stage two: tap the full-screen overlay. This gesture is never
                # forwarded to the Go board as a move.
                click_ui_description(adb,serial,"点击屏幕继续下棋")
                expected_moves = before if midgame else before-2
                event = None
                logs = ""
                deadline = time.monotonic()+40
                while time.monotonic()<deadline:
                    logs = run([adb,"-s",serial,"logcat","-d","-v","brief",
                                "-s","RefereeSmoke:I","AndroidRuntime:E","*:S"],timeout=20)
                    event = midgame_resume_event(logs,run_id) if midgame else resume_event(logs,run_id)
                    if event:
                        break
                    time.sleep(2)
                (case_dir/"resume_logcat.txt").write_text(logs,encoding="utf-8")
                if not event:
                    raise RuntimeError("Second-tap resume event missing")
                if midgame:
                    if ("moves="+str(expected_moves)) not in event or (
                            "after="+str(expected_moves)) not in event or "terminal=false" not in event or (
                            "refereeMode=false") not in event or "gameReady=true" not in event:
                        raise RuntimeError("Midgame inspection changed moves or active game: "+event)
                    if resume_event(logs,run_id):
                        raise RuntimeError("Midgame inspection unexpectedly undid terminal passes")
                    result["resumed"] = {"status":"PASS","line":event}
                else:
                    if event["status"]!="PASS":
                        raise RuntimeError("Terminal resume failed: "+str(event))
                    line = event["line"]
                    if ("moves="+str(expected_moves)) not in line or "terminal=false" not in line or (
                            "refereeMode=false") not in line or "gameReady=true" not in line:
                        raise RuntimeError("Terminal resume changed the board incorrectly: "+line)
                    result["resumed"] = event
                capture_screenshot(adb,serial,case_dir/"resumed_screen.png")
                print("Second screen tap PASS: %s, no accidental move." %
                      ("midgame moves preserved" if midgame else "terminal passes undone"),flush=True)
        else:
            print("UI check skipped (engine + referee assertions still checked).",flush=True)
        result["status"] = "PASS"
    except (OSError,RuntimeError,subprocess.TimeoutExpired,ET.ParseError) as exc:
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
