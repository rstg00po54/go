#!/usr/bin/env python3
"""Offline tests of the native fast-scoring benchmark transport and safety checks."""
import json
import unittest

from benchmark_native_fast_score_android import validate_native_result, measure_case


class FakeGtp:
    def __init__(self):
        self.commands = []

    def command(self, command):
        self.commands.append(command)
        if command == "kata-get-rules":
            return json.dumps({"scoring": "AREA", "tax": "NONE",
                               "ko": "SIMPLE", "friendlyPassOk": True}), 1
        if command == "get_komi":
            return "5.5", 1
        if command == "kata-fast-final-score":
            return json.dumps({
                "mode": "raw_nn_chain_heuristic",
                "authoritative": False,
                "fastRuleScore": "W+8.5",
                "ruleScoreWithoutDead": "W+7.5",
                "whiteMinusBlackScore": 8.5,
                "nnWhiteLead": 6.8,
                "dead": ["F5"], "deadGroups": 1, "groups": 4,
                "blackArea": 26, "whiteArea": 29,
            }), 24.0
        if command == "final_score":
            return "W+6.5", 2400.0
        if command == "final_status_list dead":
            return "F5", 750.0
        return "", 1.0


class NativeFastScoreTests(unittest.TestCase):
    fixture = {"size": 9, "sha256": "abcdef", "remote": "/data/local/tmp/tactical.sgf"}

    def test_native_result_consistency(self):
        raw = json.loads(FakeGtp().command("kata-fast-final-score")[0])
        self.assertEqual(validate_native_result(raw, self.fixture), "F5")

    def test_rule_score_and_numeric_score_must_agree(self):
        raw = json.loads(FakeGtp().command("kata-fast-final-score")[0])
        raw["whiteMinusBlackScore"] = 9.5
        with self.assertRaisesRegex(ValueError, "disagree"):
            validate_native_result(raw, self.fixture)

    def test_no_dead_must_not_change_direct_score(self):
        raw = json.loads(FakeGtp().command("kata-fast-final-score")[0])
        raw["dead"] = []
        with self.assertRaisesRegex(ValueError, "zero predicted dead"):
            validate_native_result(raw, self.fixture)

    def test_reject_wrong_mode_or_ground_truth_claim(self):
        raw = json.loads(FakeGtp().command("kata-fast-final-score")[0])
        raw["authoritative"] = True
        with self.assertRaisesRegex(ValueError, "non-authoritative"):
            validate_native_result(raw, self.fixture)

    def test_preserves_board_between_fast_and_slow(self):
        engine = FakeGtp()
        result = measure_case(engine, self.fixture, "gpu", "tactical", 1)
        self.assertEqual(engine.commands, [
            "loadsgf /data/local/tmp/tactical.sgf", "kata-get-rules", "get_komi",
            "play B pass", "play W pass", "clear_cache",
            "kata-fast-final-score", "final_score", "final_status_list dead",
        ])
        self.assertTrue(result["dead_same"])
        self.assertEqual(result["native_fast_score"], "W+8.5")
        self.assertEqual(result["original_score"], "W+6.5")
        self.assertEqual(result["native_ms"], 24.0)
        self.assertEqual(result["dead_only_native"], [])
        self.assertEqual(result["dead_only_original"], [])


if __name__ == "__main__":
    unittest.main()
