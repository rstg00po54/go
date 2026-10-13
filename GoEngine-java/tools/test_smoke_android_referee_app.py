#!/usr/bin/env python3
"""Host-side unit tests for Android referee smoke log and UI validation."""
import re
import subprocess
import sys
from contextlib import ExitStack
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
from unittest import mock
import smoke_android_referee_app as smoke
from smoke_android_referee_app import (ACTIVITY, CASES, MAIN_ACTIVITY_CLASS, PACKAGE,
                                      selected_cases, smoke_event, validate_dialog, validate_review_panel,
                                      validate_confirmation_dialog, validate_dispute_overlay)


class RefereeSmokeHostTests(unittest.TestCase):
    def test_review_ui_layout_and_java_bindings(self):
        root = Path(__file__).resolve().parents[1]
        layout_path = root/"app/src/main/res/layout/activity_main.xml"
        ET.parse(str(layout_path))  # Reject broken Android layout XML before device builds.
        layout = layout_path.read_text(encoding="utf-8")
        activity = (root/"app/src/main/java/com/badukai/MainActivity.java").read_text(encoding="utf-8")
        ids = ("refereePanel","refereePanelTitle","refereePanelScore","refereePanelHint",
               "refereeResetButton","refereeConfirmButton","refereeExitButton",
               "refereeContinueButton","gameActionPanel")
        for name in ids:
            self.assertEqual(layout.count('android:id="@+id/'+name+'"'),1)
            self.assertIn("R.id."+name,activity)
        self.assertIn("gameActionPanel.setVisibility(refereeMode ? View.GONE : View.VISIBLE)",activity)
        self.assertIn("refereePanel.setVisibility(refereeMode && !(mustContinue && evaluating) ? View.VISIBLE : View.GONE)",activity)
        self.assertIn('refereeExitButton.setOnClickListener(v -> leaveRefereeReview())',activity)
        self.assertIn('refereeContinueButton.setOnClickListener(v -> resumeFromReferee())',activity)
        self.assertIn('refereeTapOverlay.setOnClickListener(v -> {',activity)
        self.assertIn('refereeTapOverlay.setVisibility(mustContinue && refereeAwaitingTap && !evaluating',activity)
        self.assertIn('if (refereeMode && refereeRequiresContinuation() && refereeAwaitingTap)',activity)
        self.assertIn('refereeAwaitingTap = false;',activity)
        self.assertIn('refereeAwaitingTap = true;',activity)
        self.assertIn('armRefereeTapToResume())',activity)
        self.assertIn('.setCancelable(false)',activity)
        self.assertIn('? "dialog" : "dispute_dialog"',activity)
        self.assertIn('refereeContinueButton.setVisibility(View.GONE)',activity)
        self.assertIn('boardView.setRefereeMarks(null,null);',activity)
        self.assertIn('refereeConfirmButton.setVisibility(refereeMode && !mustContinue ? View.VISIBLE : View.GONE)',activity)
        self.assertIn('refereeConfirmButton.setText("确认数子")',activity)
        self.assertIn('endgameDecision.verdict != EndgameAdjudicator.Verdict.READY_TO_COUNT;',activity)
        self.assertIn('if (decision.verdict != EndgameAdjudicator.Verdict.READY_TO_COUNT',activity)
        self.assertIn("render(shortStatus);",activity)
        self.assertIn("refereePanelScore.setText(",activity)

    def test_smart_referee_entry_stays_labeled_after_new_game(self):
        root = Path(__file__).resolve().parents[1]
        activity = (root/"app/src/main/java/com/badukai/MainActivity.java").read_text(encoding="utf-8")
        self.assertIn('countTerritoryButton.setText("智能裁判");',activity)
        self.assertIn('new AlertDialog.Builder(this).setTitle("智能裁判 · 对局还没结束")',activity)
        self.assertIn('countTerritoryButton.setEnabled(engineReady && gameReady',activity)
        self.assertIn('if (refereeAwaitingTap) resumeFromReferee();',activity)

    def test_referee_overlay_is_above_game_page_and_hidden_by_default(self):
        root = Path(__file__).resolve().parents[1]
        layout = ET.parse(str(root/"app/src/main/res/layout/activity_main.xml")).getroot()
        android = "{http://schemas.android.com/apk/res/android}"
        ids = [element.get(android+"id") for element in layout]
        self.assertLess(ids.index("@+id/gamePageContainer"),ids.index("@+id/refereeTapOverlay"))
        overlay = list(layout)[ids.index("@+id/refereeTapOverlay")]
        self.assertEqual(overlay.get(android+"visibility"),"gone")
        self.assertEqual(overlay.get(android+"clickable"),"true")
        self.assertEqual(overlay.get(android+"background"),"@android:color/transparent")
        self.assertEqual(overlay.get(android+"contentDescription"),"点击屏幕继续下棋")

    def test_screenshot_accepts_only_png_bytes(self):
        class Result:
            returncode = 0
            stdout = b"\x89PNG\r\n\x1a\n"+b"test"
            stderr = b""
        with tempfile.TemporaryDirectory() as folder:
            dest = Path(folder)/"screen.png"
            with mock.patch.object(smoke.subprocess,"run",return_value=Result()):
                smoke.capture_screenshot("adb","device",dest)
            self.assertTrue(dest.read_bytes().startswith(b"\x89PNG"))
            Result.stdout = b"not a png"
            with mock.patch.object(smoke.subprocess,"run",return_value=Result()):
                with self.assertRaises(RuntimeError):
                    smoke.capture_screenshot("adb","device",dest)

    def test_activity_component_is_not_application_id_relative(self):
        self.assertEqual(PACKAGE, "com.badukai.java")
        self.assertEqual(MAIN_ACTIVITY_CLASS, "com.badukai.MainActivity")
        self.assertEqual(ACTIVITY, "com.badukai.java/com.badukai.MainActivity")

    def test_only_matching_run_id_counts_as_success(self):
        log = ("I/RefereeSmoke(123): PASS id=old stage=dialog\n"
               "I/RefereeSmoke(123): START id=current requested=cpu\n"
               "I/RefereeSmoke(123): PASS id=current stage=dialog verdict=REVIEW_REQUIRED")
        self.assertEqual(smoke_event(log, "current")["status"], "PASS")
        self.assertIsNone(smoke_event(log, "unknown"))
        self.assertIsNone(smoke_event(log, "cur"))

    def test_explicit_failure_must_win_over_prior_pass(self):
        log = ("I/RefereeSmoke: PASS id=123 stage=dialog\n"
               "E/RefereeSmoke: FAIL id=123 stage=dialog")
        self.assertEqual(smoke_event(log, "123")["status"], "FAIL")

    def test_dialog_requirements(self):
        ready = "智能裁判 · 可以进入确认数子 黄圈 确认数子"
        self.assertEqual(validate_dialog(ready,"cgos9_1"),[])
        self.assertIn("黄圈",validate_dialog(ready.replace("黄圈",""),"cgos9_1"))
        disputed = ("智能裁判 · 存在争议 棋盘上已标出争议：红叉 5 处、黄圈 2 处、橙圈 1 处。"
                    "点「继续下棋」关闭弹窗，争议标记仍保留。"
                    "然后再点屏幕任意位置，清除标记并恢复对局。")
        for case in ("cgos9_expand_006_12","cgos9_2","cgos9_5"):
            self.assertEqual(validate_dialog(disputed,case),[],case)
            self.assertIn("争议标记仍保留",validate_dialog(disputed.replace("争议标记仍保留",""),case))

    def test_dispute_dialog_then_retained_full_screen_overlay(self):
        xml = ET.fromstring('<hierarchy><node text="智能裁判 · 存在争议" clickable="false" />'
                            '<node content-desc="点击屏幕继续下棋" clickable="true" '
                            'bounds="[0,0][960,1500]" /></hierarchy>')
        for case in ("cgos9_expand_006_12","cgos9_2","cgos9_5"):
            self.assertEqual(validate_dispute_overlay(xml),[],case)
            with self.assertRaises(ValueError):
                validate_review_panel("继续下棋",case)
        xml[1].set("clickable","false")
        self.assertIn("transparent full-screen tap overlay missing",validate_dispute_overlay(xml))

    def test_settled_referee_can_confirm_score_without_continue_button(self):
        panel = "智能裁判 · 可以确认数子 黑 34.0 · 白 41.0 恢复建议 确认数子 退出复核"
        self.assertEqual(validate_review_panel(panel,"cgos9_1"),[])
        self.assertIn("unexpected continue-playing action",
                      validate_review_panel(panel+" 继续下棋","cgos9_1"))
        self.assertIn("确认数子",validate_review_panel(panel.replace("确认数子",""),"cgos9_1"))

    def test_only_ready_to_count_opens_scoring_confirmation(self):
        text = "确认中国规则数子 已复核并确认"
        self.assertEqual(validate_confirmation_dialog(text,"cgos9_1"),[])
        self.assertIn("已复核并确认",validate_confirmation_dialog("确认中国规则数子","cgos9_1"))
        for case in ("cgos9_expand_006_12","cgos9_2","cgos9_5"):
            with self.assertRaises(ValueError):
                validate_confirmation_dialog(text,case)

    def test_overlay_marker_counts_require_unique_run_and_real_coordinates(self):
        log = ("I/RefereeSmoke: OVERLAY id=old dead=1 review=0 alerts=3\n"
               "I/RefereeSmoke: OVERLAY id=active dead=0 review=4 alerts=2")
        self.assertEqual(smoke.overlay_marks(log,"active"),{"dead":0,"review":4,"alerts":2})
        self.assertIsNone(smoke.overlay_marks(log,"missing"))
        self.assertIsNone(smoke.overlay_marks("I/Other: OVERLAY id=active dead=1 review=2 alerts=3","active"))

    def test_first_modal_continue_arms_without_undoing_moves(self):
        log = ("I/RefereeSmoke: ARMED id=other moves=70 terminal=true marked=5\n"
               "I/RefereeSmoke: ARMED id=active moves=82 terminal=true marked=4")
        self.assertEqual(smoke.armed_event(log,"active"),log.splitlines()[-1])
        self.assertIsNone(smoke.resume_event(log,"active"))
        self.assertIsNone(smoke.armed_event(log,"missing"))

    def test_resume_event_from_live_android_app(self):
        records = ("I/RefereeSmoke: RESUME id=prior moves=99 terminal=false refereeMode=false gameReady=true\n"
                   "I/RefereeSmoke: RESUME_FAIL id=active moves=82 undo=false\n"
                   "I/RefereeSmoke: RESUME id=active moves=80 terminal=false refereeMode=false gameReady=true")
        self.assertEqual(smoke.resume_event(records,"active")["status"],"PASS")
        self.assertEqual(smoke.resume_event(records.splitlines()[1],"active")["status"],"FAIL")
        self.assertIsNone(smoke.resume_event(records,"missing"))

    def test_tap_ui_text_uses_exact_accessibility_bounds(self):
        xml = ET.fromstring('<hierarchy><node text="继续下棋" bounds="[50,100][250,136]" />'
                            '<node text="确认数子" bounds="[251,100][451,136]" /></hierarchy>')
        with mock.patch.object(smoke,"read_dialog_root",return_value=xml), \
             mock.patch.object(smoke,"run",return_value="") as adb_run:
            smoke.click_ui_text("adb","serial","继续下棋")
            self.assertEqual(adb_run.call_args.args[0][-2:],["150","118"])
            with self.assertRaises(RuntimeError):
                smoke.click_ui_text("adb","serial","确认死子")

    def test_tap_overlay_intercepts_any_screen_position(self):
        xml = ET.fromstring('<hierarchy><node content-desc="点击屏幕继续下棋" '
                            'clickable="true" bounds="[0,0][1000,1600]" /></hierarchy>')
        with mock.patch.object(smoke,"read_dialog_root",return_value=xml), \
             mock.patch.object(smoke,"run",return_value="") as cmd:
            smoke.click_ui_description("adb","serial","点击屏幕继续下棋")
            self.assertEqual(cmd.call_args.args[0][-2:],["500","800"])
            with self.assertRaises(RuntimeError):
                smoke.click_ui_description("adb","serial","不存在的提示")

    def test_referee_buttons_share_one_compact_row(self):
        root = Path(__file__).resolve().parents[1]
        layout = ET.parse(str(root/"app/src/main/res/layout/activity_main.xml")).getroot()
        tag = "{http://schemas.android.com/apk/res/android}"
        ids = {"refereeContinueButton","refereeResetButton","refereeConfirmButton","refereeExitButton"}
        rows = []
        for row in layout.iter("LinearLayout"):
            buttons = [child for child in row if child.tag=="Button"
                       and child.get(tag+"id","").replace("@+id/","") in ids]
            if buttons:
                rows.append(buttons)
        self.assertEqual(len(rows),1,"All referee actions should live in one horizontal row")
        self.assertEqual({child.get(tag+"id").replace("@+id/","") for child in rows[0]},ids)
        self.assertEqual(rows[0][0].get(tag+"visibility"),"gone")
        self.assertEqual(rows[0][0].get(tag+"layout_width"),"150dp")
        self.assertIsNone(rows[0][0].get(tag+"layout_weight"))
        self.assertEqual(rows[0][2].get(tag+"layout_width"),"150dp")
        for button in rows[0]:
            self.assertEqual(button.get(tag+"layout_height"),"36dp")
            self.assertEqual(button.get(tag+"minHeight"),"0dp")
            self.assertLessEqual(float(button.get(tag+"textSize").replace("sp","")),12.0)

    def test_curated_case_selection_and_input_validation(self):
        self.assertEqual(len(CASES), 4)
        self.assertEqual(selected_cases(), ["cgos9_expand_006_12"])
        self.assertEqual(selected_cases(all_games=True), list(CASES))
        self.assertEqual(selected_cases(games="cgos9_1,cgos9_5"), ["cgos9_1","cgos9_5"])
        with self.assertRaises(ValueError):
            selected_cases(games="cgos9_1,cgos9_1")
        with self.assertRaises(ValueError):
            selected_cases(games="no_such_case")
        with self.assertRaises(ValueError):
            selected_cases(game="cgos9_1",all_games=True)

    def test_debug_fixture_integrity(self):
        root = Path(__file__).resolve().parents[1] / "app/src/debug/assets"
        known_counts = {"cgos9_1":45,"cgos9_2":60,"cgos9_5":56,
                        "cgos9_expand_006_12":82}
        for game,expected in known_counts.items():
            filename = "referee_smoke_006_12.sgf" if game.endswith("_006_12") else "referee_smoke_"+game+".sgf"
            text = (root / filename).read_text(encoding="utf-8")
            self.assertIn("SZ[9]",text,game)
            self.assertIn("KM[7]",text,game)
            self.assertRegex(text,r"RE\[[BW]\+\d+\]")
            nodes = re.findall(r";([BW])\[([a-i]{0,2})\]",text)
            self.assertEqual(len(nodes),expected,game)
            self.assertEqual([vertex for _,vertex in nodes[-2:]],["",""],game)

    def test_generic_case_does_not_require_mcts_or_whatif(self):
        ready = "智能裁判 · 已可数子 待复核 0 块 黄圈 确认数子"
        self.assertEqual(validate_dialog(ready,"cgos9_1"),[])
        # A disputed game now intentionally OPENS a dialogue with board markers:
        # missing dispute text is a validation result, not an exception.
        self.assertIn("智能裁判 · 存在争议",
                      validate_dialog(ready,"cgos9_expand_006_12"))
        disputed = ("智能裁判 · 存在争议 棋盘上已标出争议：红叉 1 处、黄圈 2 处、橙圈 3 处。"
                    "点「继续下棋」关闭弹窗，争议标记仍保留。"
                    "然后再点屏幕任意位置，清除标记并恢复对局。")
        self.assertEqual(validate_dialog(disputed,"cgos9_expand_006_12"),[])

    def test_upload_on_success_fail_and_ctrl_c(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for outcome in ("PASS","FAIL","INTERRUPTED"):
                def execute_case(*args,**kwargs):
                    if outcome=="INTERRUPTED":
                        raise KeyboardInterrupt
                    return {"status":outcome,"game":"cgos9_1","run_id":"testcase"}
                with ExitStack() as ctx:
                    ctx.enter_context(mock.patch.object(smoke,"PROJECT",root))
                    ctx.enter_context(mock.patch.object(smoke,"connected_serial",return_value="device"))
                    ctx.enter_context(mock.patch.object(smoke,"run_one_case",side_effect=execute_case))
                    uploader = ctx.enter_context(mock.patch.object(smoke,"upload_referee_results",
                        return_value="https://github.com/rstg00po54/go/blob/ui-test-results/runs/fixture.json"))
                    ctx.enter_context(mock.patch.object(sys,"argv",
                        ["test","--game","cgos9_1","--skip-install","--skip-ui"]))
                    exit_code = smoke.main()
                self.assertEqual(exit_code,0 if outcome=="PASS" else
                                 130 if outcome=="INTERRUPTED" else 1)
                self.assertEqual(uploader.call_count,1)
                report = uploader.call_args.args[1]
                self.assertEqual(report["status"],outcome)

    def test_upload_failure_does_not_erase_local_report(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with ExitStack() as ctx:
                ctx.enter_context(mock.patch.object(smoke,"PROJECT",root))
                ctx.enter_context(mock.patch.object(smoke,"connected_serial",return_value="device"))
                ctx.enter_context(mock.patch.object(smoke,"run_one_case",
                                  return_value={"status":"PASS","game":"cgos9_1"}))
                ctx.enter_context(mock.patch.object(smoke,"upload_referee_results",
                                  side_effect=smoke.UploadError("bad Git credentials")))
                ctx.enter_context(mock.patch.object(sys,"argv",
                                  ["test","--game","cgos9_1","--skip-install"]))
                code = smoke.main()
            self.assertEqual(code,1)
            reports = list((root/"build/android_referee_smoke").glob("*/report.json"))
            self.assertEqual(len(reports),1)
            data = __import__("json").loads(reports[0].read_text(encoding="utf-8"))
            self.assertEqual(data["status"],"PASS")
            self.assertEqual(data["upload_status"],"FAIL")

    def test_no_false_pass_from_other_tag(self):
        self.assertIsNone(smoke_event("I/OtherTag: PASS id=xyz", "xyz"))


if __name__ == "__main__":
    unittest.main()
