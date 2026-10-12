#!/usr/bin/env python3
"""Pure-Python tests for the fixed SGF Android scoring comparator; no device needed."""
import unittest

from compare_fast_endgame_fixed_android import (
    dead_difference, parse_white_ownership, suggested_dead, compare_one, summarize,
)


class FakeGtp:
    def __init__(self):
        self.calls = []
    def command(self, cmd):
        self.calls.append(cmd)
        if cmd == "kata-get-rules":
            return ('{"friendlyPassOk":true,"scoring":"AREA","tax":"NONE","ko":"SIMPLE"}', 1)
        if cmd == "kata-raw-nn 0":
            own = [0.] * 81
            own[0] = 0.9  # Black A9 captured in fast and deep routes.
            own[80] = 0.9 # White J1 alive.
            return "whiteLead 3.5 whiteOwnership " + " ".join(str(x) for x in own), 27
        if cmd == "final_score":
            return "W+5.5", 1765
        if cmd == "final_status_list dead":
            return "A9", 630
        return "", 1


class FastFixedTests(unittest.TestCase):
    def test_extract_raw_ownership_top_row_first(self):
        arr = parse_white_ownership("whiteLead 1.0 whiteOwnership 0.9 -0.9 0.0", 1) 
        self.assertEqual(arr, [0.9])
        arr = parse_white_ownership("policy 0.1 0.2 whiteOwnership -1.01 0.88 0.1 0.0", 2)
        self.assertEqual(arr, [-1., 0.88, 0.1, 0.])
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            parse_white_ownership("whiteOwnership 0.2 0.3", 2)
        with self.assertRaisesRegex(ValueError, "Invalid"):
            parse_white_ownership("whiteOwnership nan", 1)
        with self.assertRaisesRegex(ValueError, "Expected one"):
            parse_white_ownership("whiteLead 3", 1)

    def test_never_mark_only_part_of_chain(self):
        board = ("BB.", "...", "..W")
        own = [0.9, 0.1, 0., 0., 0., 0., 0., 0., -0.8]
        dead, scanned, selected = suggested_dead(board, own)
        self.assertEqual(dead, "C1")
        self.assertEqual(scanned, 2)
        self.assertEqual(selected, 1)

    def test_white_and_black_group_coordinates(self):
        board = ("B.W", "...", "..B")
        own = [0.9, 0., -0.9, 0., 0., 0., 0., 0., -0.9]
        dead, scanned, selected = suggested_dead(board, own)
        self.assertEqual(dead, "A3 C3")
        self.assertEqual(scanned, 3)
        self.assertEqual(selected, 2)

    def test_dead_difference(self):
        self.assertEqual(dead_difference("A9 B9", "A9 C8"),
                         {"fast_only": ["B9"], "original_only": ["C8"]})

    def test_genuine_same_state_compare_no_replay_difference(self):
        engine = FakeGtp()
        fixture = {"remote": "/dev/positions/simple.sgf", "sha256": "abc",
                   "grid": ("B........", ".........", ".........",
                            ".........", ".........", ".........",
                            ".........", ".........", "........W")}
        item = compare_one(engine, fixture, "gpu", "simple", 1, 1)
        self.assertEqual(engine.calls[:5], [
            "loadsgf /dev/positions/simple.sgf", "kata-get-rules",
            "play B pass", "play W pass", "clear_cache"])
        self.assertEqual(engine.calls[5:], [
            "kata-raw-nn 0", "final_score", "final_status_list dead"])
        self.assertEqual(item["fast_dead"], "A9")
        self.assertEqual(item["original_dead"], "A9")
        self.assertTrue(item["dead_same"])
        self.assertEqual(item["fast_white_lead"], 3.5)
        self.assertEqual(item["original_white_lead"], 5.5)
        self.assertEqual(item["lead_delta"], -2)
        self.assertEqual(summarize([item])["gpu/simple"]["dead_matches"], 1)

    def test_reject_rules_change(self):
        class BrokenGtp(FakeGtp):
            def command(self, cmd):
                if cmd == "kata-get-rules":
                    return '{"friendlyPassOk":false,"scoring":"AREA","tax":"NONE","ko":"SIMPLE"}', 1
                return super().command(cmd)
        fixture = {"remote": "a.sgf", "sha256": "abc", "grid": (".........",) * 9}
        with self.assertRaisesRegex(RuntimeError, "Not the expected"):
            compare_one(BrokenGtp(), fixture, "gpu", "x", 1, 1)


if __name__ == "__main__":
    unittest.main()
