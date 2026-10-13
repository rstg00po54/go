#!/usr/bin/env python3
"""Host-side unit tests for Android referee smoke log and UI validation."""
import re
from pathlib import Path
import unittest
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

    def test_no_false_pass_from_other_tag(self):
        self.assertIsNone(smoke_event("I/OtherTag: PASS id=xyz", "xyz"))


if __name__ == "__main__":
    unittest.main()
