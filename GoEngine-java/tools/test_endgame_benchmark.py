#!/usr/bin/env python3
"""Offline tests for fixed-position CPU/GPU endgame benchmark; no ADB needed."""
import json
import re
import unittest

from benchmark_endgame_android import (
    POSITIONS, inspect_grid, make_sgf, measure_case, parse_score, parse_dead,
    parse_raw_white_lead, summarize,
)


class PositionTests(unittest.TestCase):
    def test_all_positions_have_two_eyes_and_valid_live_groups(self):
        for name, fixture in POSITIONS.items():
            with self.subTest(name=name):
                info = inspect_grid(fixture["rows"])
                self.assertEqual(info["size"], 9)
                self.assertGreaterEqual(len(info["black_eyes"]), 2)
                self.assertGreaterEqual(len(info["white_eyes"]), 2)
                self.assertGreaterEqual(info["black_stones"], 5)
                self.assertGreaterEqual(info["white_stones"], 5)

    def test_sgf_contains_exact_grid_setup_points_and_no_moves(self):
        fixture = POSITIONS["territory"]
        sgf, info = make_sgf(fixture["rows"], fixture["description"])
        self.assertTrue(sgf.startswith("(;FF[4]GM[1]SZ[9]RU[Chinese]KM[5.5]PL[B]"))
        self.assertNotIn(";B[", sgf)
        self.assertNotIn(";W[", sgf)
        self.assertEqual(sgf.count("["), info["black_stones"] + info["white_stones"] + 6)
        self.assertIn("AB[aa]", sgf)
        self.assertIn("AW[", sgf)

    def test_bad_positions_rejected(self):
        with self.assertRaisesRegex(ValueError, "9x9"):
            inspect_grid(["B...."] * 9)
        with self.assertRaisesRegex(ValueError, "two simple"):
            inspect_grid(["BBBBB....", "BB.BB....", "BBBBB....", ".........",
                          ".........", ".........", "....WWWWW", "....W.W.W",
                          "....WWWWW"])


class GtpParserTests(unittest.TestCase):
    def test_gtp_score(self):
        for value in ("W+86.5", "B+2.5", "0"):
            self.assertEqual(parse_score(" " + value + "\n"), value)
        for value in ("", "W+R", "? error", "W+1.5 extra"):
            with self.assertRaises(ValueError):
                parse_score(value)

    def test_dead_stones(self):
        self.assertEqual(parse_dead("C9 A1 B2"), "A1 B2 C9")
        self.assertEqual(parse_dead(""), "")
        for value in ("I4", "A10", "J20", "A0", "pass"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                parse_dead(value)

    def test_raw_white_lead(self):
        self.assertEqual(parse_raw_white_lead("whiteWin 0.5 whiteLead -2.75 whiteLoss 0.5"), -2.75)
        with self.assertRaises(ValueError):
            parse_raw_white_lead("whiteWin 0.5 whiteLoss 0.5")


class EngineTests(unittest.TestCase):
    def test_each_stage_measures_same_loaded_fixture(self):
        class FakeEngine:
            calls = []
            responses = {
                "loadsgf /positions/simple.sgf": ("", 11.0),
                "kata-get-rules": (json.dumps({"scoring": "AREA", "tax": "NONE", "komi": 5.5}), 1),
                "clear_cache": ("", 1),
                "final_score": ("W+3.5", 750.25),
                "final_status_list dead": ("A1 B2", 100.5),
                "kata-raw-nn 0": ("whiteWin 0.6 whiteLead 1.5 whiteLoss 0.4", 20.0),
            }
            def command(self, c):
                self.calls.append(c)
                return self.responses[c]

        fixture = {"remote": "/positions/simple.sgf", "sha256": "abc",
                   "black_stones": 13, "white_stones": 13}
        engine = FakeEngine()
        result = measure_case(engine, "gpu", "simple", fixture, 1, 1, 8.0)
        self.assertEqual(engine.calls, ["loadsgf /positions/simple.sgf", "kata-get-rules",
                                        "clear_cache", "final_score",
                                        "final_status_list dead", "kata-raw-nn 0"])
        self.assertEqual(result["score"], "W+3.5")
        self.assertAlmostEqual(result["total_ms"], 870.75)
        self.assertEqual(result["dead"], "A1 B2")

    def test_wrong_rules_fail_before_scoring(self):
        class FakeEngine:
            def command(self, c):
                if c == "kata-get-rules":
                    return (json.dumps({"scoring": "TERRITORY", "tax": "NONE", "komi": 5.5}), 1)
                return ("", 1)
        with self.assertRaisesRegex(RuntimeError, "Unexpected rules"):
            measure_case(FakeEngine(), "cpu", "simple", {
                "remote": "a.sgf", "sha256": "abc", "black_stones": 12,
                "white_stones": 12}, 1, 1, 8.0)

    def test_summary_independent_per_backend(self):
        rows = [dict(fixture="simple", backend=b, score=s, total_ms=v,
                     final_score_ms=v-10, dead_stones_ms=8, raw_nn_ms=2)
                for b, s, v in [("cpu", "B+0.5", 100), ("cpu", "B+0.5", 120),
                                ("gpu", "W+0.5", 40)]]
        result = summarize(rows, ["simple"], ["cpu", "gpu"])
        self.assertEqual(result["simple"]["cpu"]["total_ms"], 110)
        self.assertEqual(result["simple"]["gpu"]["scores"], ["W+0.5"])


if __name__ == "__main__":
    unittest.main()
