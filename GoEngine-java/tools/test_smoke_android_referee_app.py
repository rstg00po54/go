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
                                      validate_confirmation_dialog)


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
        self.assertIn("refereePanel.setVisibility(refereeMode ? View.VISIBLE : View.GONE)",activity)
        self.assertIn('refereeExitButton.setOnClickListener(v -> leaveRefereeReview())',activity)
        self.assertIn('refereeContinueButton.setOnClickListener(v -> resumeFromReferee())',activity)
        self.assertIn('refereeContinueButton.setVisibility(mustContinue ? View.VISIBLE : View.GONE)',activity)
        self.assertIn('refereeConfirmButton.setVisibility(refereeMode && !mustContinue ? View.VISIBLE : View.GONE)',activity)
        self.assertIn('refereeConfirmButton.setText(needsReview ? "确认死子" : "确认数子")',activity)
        self.assertIn("render(shortStatus);",activity)
        self.assertIn("refereePanelScore.setText(",activity)

    def test_smart_referee_entry_stays_labeled_after_new_game(self):
        root = Path(__file__).resolve().parents[1]
        activity = (root/"app/src/main/java/com/badukai/MainActivity.java").read_text(encoding="utf-8")
        self.assertIn('countTerritoryButton.setText("智能裁判");',activity)
        self.assertIn('new AlertDialog.Builder(this).setTitle("智能裁判 · 对局还没结束")',activity)
        self.assertIn('countTerritoryButton.setEnabled(engineReady && gameReady',activity)
        self.assertIn('if (refereeRequiresContinuation()) return;',activity)

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
        text = ("智能裁判 · 需要人工复核 待复核 2 块 黑先：F7 搜索胜率 "
                "白先：H5 不同死子组合可能改变胜负 黄圈")
        self.assertEqual(validate_dialog(text), [])
        self.assertIn("白先：", validate_dialog(text.replace("白先：","")))

    def test_finished_disputed_referee_offers_dead_review_not_continue(self):
        panel = "智能裁判 · 请确认死子 试算：黑 34.0 · 白 41.0 确认死子"
        self.assertEqual(validate_review_panel(panel),[])
        self.assertIn("确认死子",validate_review_panel(panel.replace("确认死子","")))
        for label in ("继续下棋","确认数子","恢复建议","退出复核"):
            self.assertIn("unexpected button: "+label,validate_review_panel(panel+" "+label))

    def test_settled_referee_can_confirm_score_without_continue_button(self):
        panel = "智能裁判 · 可以确认数子 黑 34.0 · 白 41.0 恢复建议 确认数子 退出复核"
        self.assertEqual(validate_review_panel(panel,"cgos9_1"),[])
        self.assertIn("unexpected button: 继续下棋",
                      validate_review_panel(panel+" 继续下棋","cgos9_1"))
        self.assertIn("确认数子",validate_review_panel(panel.replace("确认数子",""),"cgos9_1"))

    def test_android_confirmation_dialogs_for_double_pass_cases(self):
        samples = {
            "cgos9_expand_006_12":"还有 2 块死子需要确认 返回棋盘确认 黄圈全部判活",
            "cgos9_2":"还有 1 块死子需要确认 返回棋盘确认 黄圈全部判活",
            "cgos9_5":"疑似双活或劫争尚未证明 人工确认并数子",
            "cgos9_1":"确认中国规则数子 已复核并确认"}
        for case,text in samples.items():
            self.assertEqual(validate_confirmation_dialog(text,case),[],case)
            self.assertNotEqual(validate_confirmation_dialog("",case),[],case)
            if case != "cgos9_1":
                self.assertIn("unexpected forced continuation",
                              validate_confirmation_dialog(text+" 继续下棋",case))

    def test_tap_ui_text_uses_exact_accessibility_bounds(self):
        xml = ET.fromstring('<hierarchy><node text="确认死子" bounds="[50,100][250,136]" />'
                            '<node text="确认数子" bounds="[251,100][451,136]" /></hierarchy>')
        with mock.patch.object(smoke,"read_dialog_root",return_value=xml), \
             mock.patch.object(smoke,"run",return_value="") as adb_run:
            smoke.click_ui_text("adb","serial","确认死子")
            self.assertEqual(adb_run.call_args.args[0][-2:],["150","118"])
            with self.assertRaises(RuntimeError):
                smoke.click_ui_text("adb","serial","继续下棋")

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
        text = "智能裁判 · 已可数子 待复核 0 块 黄圈"
        self.assertEqual(validate_dialog(text,"cgos9_1"),[])
        self.assertIn("黑先：",validate_dialog(text,"cgos9_expand_006_12"))

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
