#!/usr/bin/env python3
"""Offline checks for the compare_score_rules_android diagnostic (no ADB)."""
import json
import unittest

from benchmark_endgame_android import POSITIONS
from compare_score_rules_android import analyze, run_case, validate_same_rules


class FakeGtp:
    def __init__(self):
        self.calls = []
        self.friendly = True
    def command(self, value):
        self.calls.append(value)
        if value.startswith("kata-set-rule friendlyPassOk "):
            self.friendly = value.endswith("true")
        if value == "kata-get-rules":
            return json.dumps({"scoring": "AREA", "ko": "SIMPLE", "tax": "NONE",
                               "friendlyPassOk": self.friendly, "suicide": False}), 1
        if value == "final_score":
            return ("W+2.5" if self.friendly else "W+5.5"), 6000 if self.friendly else 1
        if value == "final_status_list dead":
            return "", 2000 if self.friendly else 1
        return "", 1


class ScoreRulesTests(unittest.TestCase):
    def setUp(self):
        self.fixture = {"remote": "/positions/simple.sgf", "sha256": "abc",
                        "grid": POSITIONS["simple"]["rows"]}
    def test_identical_position_but_different_score_criteria(self):
        engine = FakeGtp()
        predicted = run_case(engine, self.fixture, "cpu", "simple", 1, 1, True)
        direct = run_case(engine, self.fixture, "cpu", "simple", 1, 1, False)
        self.assertEqual(predicted["score"], "W+2.5")
        self.assertEqual(direct["score"], "W+5.5")
        self.assertEqual(predicted["sha256"], direct["sha256"])
        self.assertEqual(engine.calls.count("play B pass"), 2)
        self.assertEqual(engine.calls.count("play W pass"), 2)
        result = analyze([predicted, direct], ["simple"], ["cpu"])
        self.assertFalse(result[0]["same_score"])
        self.assertTrue(result[0]["same_dead"])
        self.assertGreater(result[0]["predict_total_ms"], result[0]["rules_total_ms"])

    def test_other_rules_remain_identical(self):
        original = {"scoring": "AREA", "ko": "SIMPLE", "tax": "NONE",
                    "friendlyPassOk": True}
        direct = dict(original, friendlyPassOk=False)
        validate_same_rules(original, direct)

    def test_disallow_unrelated_rules_changes(self):
        before = {"scoring": "AREA", "friendlyPassOk": True}
        after = {"scoring": "TERRITORY", "friendlyPassOk": False}
        with self.assertRaisesRegex(RuntimeError, "Other rules changed"):
            validate_same_rules(before, after)

    def test_disallow_no_effect_rule_switch(self):
        before = {"scoring": "AREA", "friendlyPassOk": True}
        with self.assertRaisesRegex(RuntimeError, "was not switched"):
            validate_same_rules(before, dict(before))

    def test_incorrect_rule_readback_fails(self):
        class BadEngine(FakeGtp):
            def command(self, value):
                result = super().command(value)
                if value == "kata-get-rules":
                    return json.dumps({"scoring": "AREA", "tax": "NONE",
                                       "ko": "SIMPLE", "friendlyPassOk": False}), 1
                return result
        with self.assertRaisesRegex(RuntimeError, "rejected friendlyPassOk"):
            run_case(BadEngine(), self.fixture, "cpu", "simple", 1, 1, True)


if __name__ == "__main__":
    unittest.main()
