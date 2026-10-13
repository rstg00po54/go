#!/usr/bin/env python3
"""Host-side unit tests for Android referee smoke log and UI validation."""
import re
import sys
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import smoke_android_referee_app as smoke
from smoke_android_referee_app import (ACTIVITY, CASES, MAIN_ACTIVITY_CLASS, PACKAGE,
                                      selected_cases, smoke_event, validate_dialog)


class RefereeSmokeHostTests(unittest.TestCase):
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
                with (mock.patch.object(smoke,"PROJECT",root),
                      mock.patch.object(smoke,"connected_serial",return_value="device"),
                      mock.patch.object(smoke,"run_one_case",side_effect=execute_case),
                      mock.patch.object(smoke,"upload_referee_results",
                                        return_value="https://github.com/rstg00po54/go/blob/ui-test-results/runs/fixture.json") as uploader,
                      mock.patch.object(sys,"argv",["test","--game","cgos9_1",
                                                     "--skip-install","--skip-ui"])):
                    exit_code = smoke.main()
                self.assertEqual(exit_code,0 if outcome=="PASS" else
                                 130 if outcome=="INTERRUPTED" else 1)
                self.assertEqual(uploader.call_count,1)
                report = uploader.call_args.args[1]
                self.assertEqual(report["status"],outcome)

    def test_upload_failure_does_not_erase_local_report(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with (mock.patch.object(smoke,"PROJECT",root),
                  mock.patch.object(smoke,"connected_serial",return_value="device"),
                  mock.patch.object(smoke,"run_one_case",
                                    return_value={"status":"PASS","game":"cgos9_1"}),
                  mock.patch.object(smoke,"upload_referee_results",
                                    side_effect=smoke.UploadError("bad Git credentials")),
                  mock.patch.object(sys,"argv",["test","--game","cgos9_1","--skip-install"])):
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
