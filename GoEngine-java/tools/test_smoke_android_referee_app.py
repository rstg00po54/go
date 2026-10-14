#!/usr/bin/env python3
"""Host-side regression tests for the read-only Android KataGo NN smoke workflow."""
import re
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from contextlib import ExitStack
from pathlib import Path
from unittest import mock

import smoke_android_referee_app as smoke

ROOT = Path(__file__).resolve().parents[1]
ACTIVITY = ROOT/"app/src/main/java/com/badukai/MainActivity.java"
LAYOUT = ROOT/"app/src/main/res/layout/activity_main.xml"
BOARD_VIEW = ROOT/"app/src/main/java/com/badukai/ui/GoBoardView.java"

GOOD_GPU = ("I/RefereeSmoke: PASS id=current game=cgos9_expand_006_12 stage=nn_dialog "
            "backend=GPU/OpenCL moves=82 terminal=true whiteLead=-2.000 blackWin=0.6000 "
            "whiteWin=0.4000 blackPoints=45.000 whitePoints=43.000 nnMs=18 passAlive=false")
GOOD_NATIVE = "I/RefereeSmoke: PASS_ALIVE_SELFTEST id=current positive=true negative=false backend=GPU/OpenCL_JNI"



class NnSmokeTests(unittest.TestCase):
    def test_android_referee_entry_uses_only_nn(self):
        activity = ACTIVITY.read_text(encoding="utf-8")
        block = activity.split("private void showEndgameScoringOptions()",1)[1].split(
            "private void showQuickNnRefereeEstimate()",1)[0]
        self.assertIn("showQuickNnRefereeEstimate();",block)
        self.assertNotIn("startSmartReferee(",activity)
        self.assertIn('sendCommandSync("kata-raw-nn 0")',
                      (ROOT/"app/src/main/java/com/badukai/engine/KataGoEngine.java").read_text(encoding="utf-8"))

    def test_legacy_adjudication_and_manual_confirmation_are_gone(self):
        activity = ACTIVITY.read_text(encoding="utf-8")
        for old in ("SmartReferee", "EndgameAdjudicator", "MidgameTacticalReview",
                    "确认中国规则数子", "恢复 AI 建议", "已复核并确认",
                    "refereeMode", "startSmartReferee", "showRefereeConfirmation",
                    "engine.getFinalScore()", "engine.getFinalDeadStones()",
                    "boardView.setRefereeMarks", "boardView.setEndgameAlerts"):
            self.assertNotIn(old,activity,old)

    def test_layout_has_no_review_toolbar_or_overlay(self):
        ET.parse(str(LAYOUT))
        layout = LAYOUT.read_text(encoding="utf-8")
        for obsolete in ("refereePanel","refereeTapOverlay","refereeResetButton",
                         "refereeConfirmButton","refereeExitButton","refereeContinueButton",
                         "复核死子","确认数子","点击屏幕继续下棋"):
            self.assertNotIn(obsolete,layout)
        self.assertIn('@+id/countTerritoryButton',layout)
        self.assertIn('@+id/nnAreaScoreText',layout)

    def test_board_has_no_referee_cross_or_dispute_marks(self):
        view = BOARD_VIEW.read_text(encoding="utf-8")
        for old in ("setRefereeMarks","setEndgameAlerts","drawRefereeMarks",
                    "drawEndgameAlerts","drawDeadStoneOwnership"):
            self.assertNotIn(old,view)

    def test_real_fixture_debug_calls_same_nn_entry(self):
        activity = ACTIVITY.read_text(encoding="utf-8")
        replay = activity.split("private void startDebugRefereeSmoke()",1)[1].split(
            "private void runJniGtpSmoke(",1)[0]
        self.assertIn("showQuickNnRefereeEstimate();",replay)
        self.assertNotIn("startSmartReferee();",replay)
        self.assertNotIn("engine.evaluatePosition(",replay)
        self.assertIn('getBooleanExtra("referee_smoke_midgame"',replay)

    def test_nn_dialog_has_area_winrates_lead_and_timing(self):
        activity = ACTIVITY.read_text(encoding="utf-8")
        quick = activity.split("private void showQuickNnRefereeEstimate()",1)[1].split(
            "private void finishByResignation(",1)[0]
        self.assertEqual(quick.count("engine.evaluatePosition(size)"),1)
        for expected in ("NnAreaScore.estimate(", "result.whiteLead", "result.blackWin",
                         "result.whiteWin", "nnMs", "stage=nn_dialog",
                         "setTitle(\"智能裁判 · NN预估\")", "NN_DISMISS"):
            self.assertIn(expected,quick)
        self.assertNotIn("searchRecommendations(",quick)
        self.assertNotIn("engine.playMove(",quick)
        self.assertNotIn("engine.undo()",quick)
        self.assertIn('.setMessage(summary)',quick)
        for obsolete in ("positionWarning","当前存在劫禁","单气棋","建议复核死活",
                         "双方已停棋","双方目数按 NN 地盘归属估算"):
            self.assertNotIn(obsolete,quick)

    def test_black_white_area_status_text_present(self):
        activity = ACTIVITY.read_text(encoding="utf-8")
        layout = LAYOUT.read_text(encoding="utf-8")
        self.assertIn('nnAreaScoreText = findViewById(R.id.nnAreaScoreText);',activity)
        self.assertIn('nnAreaScoreText.setVisibility(nnAreaScoreMessage == null',activity)
        self.assertEqual(layout.count('android:id="@+id/nnAreaScoreText"'),1)

    def test_ai_move_time_remains(self):
        activity = ACTIVITY.read_text(encoding="utf-8")
        self.assertIn('long elapsedMs = SystemClock.elapsedRealtime() - startedMs;',activity)
        self.assertIn("AI_MOVE_TIME move=",activity)
        self.assertIn("aiMoveTimeText.setText(thinking ?",activity)

    def test_terminal_nn_result_parses(self):
        got = smoke.nn_event_values(GOOD_GPU,"gpu",False)
        self.assertEqual(got["moves"],82)
        self.assertEqual(got["nnMs"],18)
        self.assertEqual(got["blackPoints"],45)
        self.assertEqual(got["whitePoints"],43)
        self.assertTrue(got["terminal"])
        self.assertFalse(got["passAlive"])

    def test_midgame_nn_result_parses(self):
        line = GOOD_GPU.replace("moves=82 terminal=true","moves=80 terminal=false")
        got = smoke.nn_event_values(line,"gpu",True)
        self.assertEqual(got["moves"],80)
        self.assertFalse(got["terminal"])

    def test_wrong_backend_or_terminal_mode_fails(self):
        with self.assertRaisesRegex(ValueError,"GPU requested"):
            smoke.nn_event_values(GOOD_GPU.replace("GPU/OpenCL","CPU/Eigen"),"gpu",False)
        with self.assertRaisesRegex(ValueError,"Wrong terminal"):
            smoke.nn_event_values(GOOD_GPU,"gpu",True)

    def test_nn_numeric_invariants(self):
        for altered in (GOOD_GPU.replace("blackPoints=45.000","blackPoints=44.000"),
                        GOOD_GPU.replace("blackWin=0.6000","blackWin=1.2000"),
                        GOOD_GPU.replace("whiteLead=-2.000","whiteLead=NaN")):
            with self.assertRaises(ValueError):
                smoke.nn_event_values(altered,"gpu",False)

    def test_current_run_id_does_not_accept_stale_pass(self):
        logs = "I/RefereeSmoke: PASS id=old stage=nn_dialog\n"+GOOD_GPU
        self.assertEqual(smoke.smoke_event(logs,"current")["status"],"PASS")
        self.assertIsNone(smoke.smoke_event(logs,"unknown"))
        self.assertIsNone(smoke.smoke_event("I/OtherTag: PASS id=current","current"))

    def test_failure_overrides_prior_pass(self):
        log = GOOD_GPU+"\nE/RefereeSmoke: FAIL id=current stage=nn_dialog reason=nn_invalid"
        self.assertEqual(smoke.smoke_event(log,"current")["status"],"FAIL")

    def test_dialog_requires_nn_content_and_rejects_old_text(self):
        text = ("智能裁判 · NN预估 黑棋领先 2.0目 黑棋 45.0目 · 白棋 43.0目 "
                "胜率 黑 60.0% · 白 40.0% 收官：未确认 关闭")
        self.assertEqual(smoke.validate_dialog(text),[])
        self.assertEqual(smoke.validate_dialog(text,certified=False),[])
        self.assertIn("strict endgame status disagrees",str(smoke.validate_dialog(text,certified=True)))
        self.assertEqual(smoke.validate_dialog(text.replace("收官：未确认","收官：已完成"),certified=True),[])
        self.assertIn("deprecated UI",str(smoke.validate_dialog(text+" 确认中国规则数子")))
        self.assertIn("继续下棋",smoke.validate_dialog(text,midgame=True))
        self.assertEqual(smoke.validate_dialog(text.replace("关闭","继续下棋"),midgame=True),[])
        self.assertEqual(smoke.validate_dialog(text.replace("黑棋领先 2.0目","双方持平")),[])
        self.assertIn("deprecated UI",str(smoke.validate_dialog(text+" 建议复核死活")))

    def test_native_smoke_proof_requires_both_positive_and_negative(self):
        self.assertIn("PASS_ALIVE_SELFTEST",smoke.native_proof_event(GOOD_NATIVE,"current"))
        self.assertIsNone(smoke.native_proof_event(GOOD_NATIVE.replace("positive=true","positive=false"),"current"))
        self.assertIsNone(smoke.native_proof_event(GOOD_NATIVE,"other"))

    def test_status_log_required_and_independent_from_game_over_or_winrate(self):
        with self.assertRaisesRegex(ValueError,"Missing strict KataGo pass-alive"):
            smoke.nn_event_values(GOOD_GPU.replace(" passAlive=false",""),"gpu",False)
        completed = smoke.nn_event_values(GOOD_GPU.replace("passAlive=false","passAlive=true"),"gpu",False)
        self.assertTrue(completed["passAlive"])
        ongoing = smoke.nn_event_values(GOOD_GPU.replace("moves=82 terminal=true","moves=80 terminal=false")
                                            .replace("passAlive=false","passAlive=true"),"gpu",True)
        self.assertTrue(ongoing["passAlive"])
        self.assertFalse(ongoing["terminal"])

    def test_native_pass_alive_wiring_is_fail_closed(self):
        activity = ACTIVITY.read_text(encoding="utf-8")
        engine = (ROOT/"app/src/main/java/com/badukai/engine/KataGoEngine.java").read_text(encoding="utf-8")
        native = (ROOT/"app/src/main/cpp/EngineSession.cpp").read_text(encoding="utf-8")
        assert 'final boolean passAliveCertified = engine.isStrictPassAliveComplete(snapshot);' in activity
        self.assertIn('passAliveCertified ? "已完成" : "未确认"',activity)
        self.assertIn("position.getSimpleKoPoint() != null",engine)
        self.assertIn("KataGoGpuNative.isAllPassAlive(size,colors)",engine)
        self.assertIn("KataGoNative.isAllPassAlive(size,colors)",engine)
        self.assertIn("history.endGameIfAllPassAlive(snapshot);",native)
        self.assertIn("history.isGameFinished && history.isScored",native)
        self.assertNotIn("passAliveCertified = gameEnded",activity)

    def test_dismiss_event_is_run_specific(self):
        log = "I/RefereeSmoke: NN_DISMISS id=other moves=80 terminal=false\n"
        self.assertIsNone(smoke.dismiss_event(log,"current"))
        self.assertIn("moves=82",smoke.dismiss_event(
            log+"I/RefereeSmoke: NN_DISMISS id=current moves=82 terminal=true","current"))

    def test_curated_fixtures_and_selection(self):
        self.assertEqual(len(smoke.CASES),4)
        self.assertEqual(smoke.selected_cases(all_games=True),list(smoke.CASES))
        self.assertEqual(smoke.selected_cases(game="cgos9_1"),["cgos9_1"])
        with self.assertRaises(ValueError):
            smoke.selected_cases(games="cgos9_1,cgos9_1")
        with self.assertRaises(ValueError):
            smoke.selected_cases(games="unknown")

    def test_device_logcat_fallback_finds_current_run(self):
        calls = []
        def fake_run(argv,timeout=30,check=True):
            calls.append(argv)
            if "shell" in argv and "logcat" in argv:
                return "I/RefereeSmoke: START id=current\n"+GOOD_GPU+"\nI/OtherTag: noise"
            return ""
        with mock.patch.object(smoke,"run",side_effect=fake_run):
            log, source = smoke.read_referee_log("adb","device","current")
        self.assertEqual(source,"buffer-all")
        self.assertEqual(smoke.smoke_event(log,"current")["status"],"PASS")
        self.assertEqual(len(calls),2)
        self.assertIn("-b",calls[1])
        self.assertIn("all",calls[1])

    def test_device_logcat_primary_does_not_use_stale_pass(self):
        with mock.patch.object(smoke,"run",return_value=GOOD_GPU) as adb:
            log, source = smoke.read_referee_log("adb","device","current")
        self.assertEqual(source,"tag-filter")
        self.assertEqual(smoke.smoke_event(log,"current")["status"],"PASS")
        adb.assert_called_once()

    def test_launch_does_not_block_on_android_activity_idle(self):
        class Id:
            hex = "current"
        adb_calls = []
        def fake_run(argv,timeout=30,check=True):
            adb_calls.append(argv)
            if "logcat" in argv:
                return GOOD_NATIVE+"\n"+GOOD_GPU
            if "am" in argv and "start" in argv:
                return "Starting: Intent { ... }"
            return ""
        with tempfile.TemporaryDirectory() as folder:
            with mock.patch.object(smoke,"uuid4",return_value=Id()):
                with mock.patch.object(smoke,"run",side_effect=fake_run):
                    result = smoke.run_one_case("adb","device","gpu","cgos9_expand_006_12",
                                                15,True,Path(folder))
        self.assertEqual(result["status"],"PASS",result.get("error"))
        starts = [call for call in adb_calls if "am" in call and "start" in call]
        self.assertEqual(len(starts),1)
        self.assertNotIn("-W",starts[0])
        self.assertIn("--ez",starts[0])

    def test_ui_uses_single_hierarchy_dump_for_validation_and_button_tap(self):
        class Id:
            hex = "current"
        state = {"clicked":False}
        ui = ET.fromstring("""<hierarchy>
            <node text="智能裁判 · NN预估" />
            <node text="黑棋领先 2.0目 黑棋 45.0目 · 白棋 43.0目 胜率 黑 60.0% · 白 40.0% 收官：未确认" />
            <node text="关闭" bounds="[100,500][300,570]" />
        </hierarchy>""")
        def fake_run(argv,timeout=30,check=True):
            if "logcat" in argv:
                suffix = "\nI/RefereeSmoke: NN_DISMISS id=current moves=82 terminal=true" if state["clicked"] else ""
                return GOOD_NATIVE+"\n"+GOOD_GPU + suffix
            if "input" in argv and "tap" in argv:
                state["clicked"]=True
            if "am" in argv and "start" in argv:
                return "Starting: Intent { ... }"
            return ""
        def fake_screenshot(adb,serial,target):
            target.write_bytes(b"fake png")
        with tempfile.TemporaryDirectory() as folder:
            with mock.patch.object(smoke,"uuid4",return_value=Id()):
                with mock.patch.object(smoke,"run",side_effect=fake_run):
                    with mock.patch.object(smoke,"read_dialog_root",return_value=ui) as dump:
                        with mock.patch.object(smoke,"capture_screenshot",side_effect=fake_screenshot):
                            result=smoke.run_one_case("adb","device","gpu","cgos9_expand_006_12",
                                                       15,False,Path(folder))
        self.assertEqual(result["status"],"PASS",result.get("error"))
        self.assertTrue(state["clicked"])
        dump.assert_called_once_with("adb","device")

    def test_upload_error_keeps_local_report(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with ExitStack() as ctx:
                ctx.enter_context(mock.patch.object(smoke,"PROJECT",root))
                ctx.enter_context(mock.patch.object(smoke,"connected_serial",return_value="device"))
                ctx.enter_context(mock.patch.object(smoke,"run_one_case",return_value=
                    {"game":"cgos9_1","status":"PASS","nn":{"nnMs":18}}))
                ctx.enter_context(mock.patch.object(smoke,"upload_referee_results",
                    side_effect=smoke.UploadError("bad Git credentials")))
                ctx.enter_context(mock.patch.object(sys,"argv",[
                    "test","--game","cgos9_1","--skip-install"]))
                code = smoke.main()
            self.assertEqual(code,1)
            reports = list((root/"build/android_referee_smoke").glob("*/report.json"))
            self.assertEqual(len(reports),1)
            self.assertIn('"status": "PASS"',reports[0].read_text(encoding="utf-8"))

    def test_no_upload_option_does_not_require_git_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            with ExitStack() as ctx:
                ctx.enter_context(mock.patch.object(smoke,"PROJECT",Path(directory)))
                ctx.enter_context(mock.patch.object(smoke,"connected_serial",return_value="device"))
                ctx.enter_context(mock.patch.object(smoke,"run_one_case",return_value=
                    {"game":"cgos9_1","status":"PASS","nn":{"nnMs":7}}))
                upload = ctx.enter_context(mock.patch.object(smoke,"upload_referee_results"))
                ctx.enter_context(mock.patch.object(sys,"argv",[
                    "test","--game","cgos9_1","--skip-install","--no-upload"]))
                self.assertEqual(smoke.main(),0)
                upload.assert_not_called()


if __name__ == "__main__":
    unittest.main()
