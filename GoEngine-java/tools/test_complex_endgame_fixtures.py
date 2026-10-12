#!/usr/bin/env python3
"""Offline validation of reproducible complex SGF stress cases, no ADB needed."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from benchmark_endgame_android import inspect_grid, make_sgf, load_fixtures
from complex_endgame_fixtures import COMPLEX_POSITIONS
from benchmark_native_fast_score_android import summarize_comparisons, describe_disagreement_groups


LETTERS = "ABCDEFGHJKLMNOPQRST"


def chain_liberties(rows, gtp_vertex):
    size = len(rows)
    x = LETTERS.index(gtp_vertex[0].upper())
    y = size - int(gtp_vertex[1:])
    color = rows[y][x]
    if color not in "BW":
        raise AssertionError("Geometric atari marker is not a stone: " + gtp_vertex)
    seen, pending, liberties = set(), [(x, y)], set()
    while pending:
        px, py = pending.pop()
        if (px, py) in seen:
            continue
        seen.add((px, py))
        for nx, ny in ((px-1, py), (px+1, py), (px, py-1), (px, py+1)):
            if not (0 <= nx < size and 0 <= ny < size):
                continue
            if rows[ny][nx] == ".":
                liberties.add((nx, ny))
            elif rows[ny][nx] == color and (nx, ny) not in seen:
                pending.append((nx, ny))
    return liberties


class ComplexEndgameFixturesTests(unittest.TestCase):
    def test_every_complex_fixture_is_legal_setup_with_both_corner_eyes(self):
        self.assertEqual(len(COMPLEX_POSITIONS), 9)
        self.assertEqual({len(p["rows"]) for p in COMPLEX_POSITIONS.values()}, {9, 13, 19})
        for name, fixture in COMPLEX_POSITIONS.items():
            with self.subTest(name=name):
                info = inspect_grid(fixture["rows"])
                self.assertIn(info["size"], (9, 13, 19))
                self.assertGreaterEqual(len(info["black_eyes"]), 2)
                self.assertGreaterEqual(len(info["white_eyes"]), 2)
                self.assertGreaterEqual(info["black_stones"], 5)
                self.assertGreaterEqual(info["white_stones"], 5)
                self.assertIn("focus", fixture)
                self.assertIn("atari", fixture)

    def test_only_geometric_atari_targets_are_asserted(self):
        for name, fixture in COMPLEX_POSITIONS.items():
            for vertex in fixture["atari"]:
                with self.subTest(name=name, atari=vertex):
                    self.assertEqual(len(chain_liberties(fixture["rows"], vertex)), 1)

    def test_sgf_dimension_and_stone_setup_match_each_grid(self):
        for name, fixture in COMPLEX_POSITIONS.items():
            with self.subTest(name=name):
                sgf, info = make_sgf(fixture["rows"], fixture["description"])
                self.assertIn("SZ[%d]" % info["size"], sgf)
                self.assertIn("RU[Chinese]", sgf)
                self.assertIn("KM[5.5]", sgf)
                self.assertIn("PL[B]", sgf)
                self.assertNotIn(";B[", sgf)
                self.assertNotIn(";W[", sgf)
                self.assertEqual(sgf.count("["), info["black_stones"] + info["white_stones"] + 6)

    def test_fixture_loader_preserves_extra_diagnostic_metadata(self):
        name = "double_ko_shape13"
        fixture = COMPLEX_POSITIONS[name]
        with tempfile.TemporaryDirectory() as tmp:
            with patch("benchmark_endgame_android.run", return_value=""):
                report = load_fixtures("adb", "fake", Path(tmp), [name],
                                       positions=COMPLEX_POSITIONS)
            self.assertEqual(report[name]["expected_atari"], fixture["atari"])
            self.assertEqual(report[name]["focus"], fixture["focus"])
            self.assertEqual(report[name]["size"], 13)
            path = Path(tmp) / "positions" / (name + ".sgf")
            self.assertEqual(report[name]["sha256"], hashlib.sha256(path.read_bytes()).hexdigest())
            self.assertIn("SZ[13]", path.read_text())

    def test_disagreement_groups_include_color_size_and_liberties(self):
        rows = COMPLEX_POSITIONS["ko_shape9"]["rows"]
        group = describe_disagreement_groups(rows, ["E5"], [])[0]
        self.assertEqual(group["source"], "native_only")
        self.assertEqual(group["color"], "W")
        self.assertEqual(group["stone_count"], 1)
        self.assertEqual(group["liberties"], 1)
        self.assertEqual(group["liberty_vertices"], ["E4"])

    def test_multiple_disagreeing_stones_in_one_chain_report_once(self):
        rows = COMPLEX_POSITIONS["ko_shape9"]["rows"]
        groups = describe_disagreement_groups(rows, ["A9", "B9"], [])
        self.assertEqual(len(groups), 1)
        self.assertEqual(groups[0]["color"], "B")
        self.assertEqual(groups[0]["stone_count"], 14)  # Includes black E6 connected to the corner block.

    def test_comparison_summary_records_uncertainty_not_accuracy(self):
        rows = [
            dict(backend="gpu", fixture="double_ko_shape13", native_ms=30,
                 original_score_ms=6000, original_dead_ms=2000,
                 native_white_lead=5.5, nn_white_lead=4.6, original_white_lead=4.5,
                 dead_same=False, native_dead="F8", original_dead="K7"),
            dict(backend="gpu", fixture="double_ko_shape13", native_ms=40,
                 original_score_ms=6200, original_dead_ms=2400,
                 native_white_lead=5.5, nn_white_lead=5.6, original_white_lead=5.5,
                 dead_same=True, native_dead="K7", original_dead="K7"),
        ]
        out = summarize_comparisons(rows)["gpu/double_ko_shape13"]
        self.assertEqual(out["samples"], 2)
        self.assertEqual(out["dead_matches"], 1)
        self.assertEqual(out["dead_disagreements"], 1)
        self.assertEqual(out["native_ms_average"], 35)
        self.assertEqual(out["original_prediction_range"], 1)
        self.assertEqual(out["nn_to_original_delta_average_abs"], 0.1)
        self.assertEqual(out["native_dead_variants"], ["F8", "K7"])


if __name__ == "__main__":
    unittest.main()
