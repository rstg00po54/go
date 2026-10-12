#!/usr/bin/env python3
"""Offline checks for real scored-SGF NN benchmark; uses no Android/device."""
import unittest

from benchmark_real_sgf_nn_android import (
    collect_games, read_recorded_game, sgf_nodes, parse_nn_metrics, compare_game, summarize,
)


class FakeEngine:
    def __init__(self, lead=-2.4, komi="7", rules='{"scoring":"AREA","tax":"NONE"}'):
        self.calls = []
        self.lead = lead
        self.komi = komi
        self.rules = rules

    def command(self, value):
        self.calls.append(value)
        if value == "kata-get-rules":
            return self.rules, 0.1
        if value == "get_komi":
            return self.komi, 0.1
        if value == "kata-raw-nn 0":
            return "whiteWin 0.12 whiteLoss 0.88 whiteLead %.3f" % self.lead, 31.2
        return "", 0.1


class RealSgfTests(unittest.TestCase):
    def test_bundled_games_are_real_scored_sgfs_with_provenance(self):
        games, rejected = collect_games()
        self.assertEqual(len(games), 6)
        self.assertEqual(rejected, [])
        self.assertEqual(sorted(set(g["size"] for g in games)), [9, 19])
        self.assertEqual(sum(g["size"] == 9 for g in games), 5)
        self.assertEqual(sum(g["size"] == 19 for g in games), 1)
        self.assertTrue(all(g["source"].startswith("https://raw.githubusercontent.com/")
                            for g in games))
        self.assertTrue(all(g["rules"] == "Chinese" for g in games))
        self.assertEqual(games[0]["official_re"], "B+2")
        self.assertEqual(games[0]["official_white_lead"], -2)
        self.assertTrue(games[0]["terminal_passes"])
        self.assertEqual(games[-1]["official_re"], "W+0.5")
        self.assertFalse(games[-1]["terminal_passes"])

    def test_sgf_properties_escaped_bracket_do_not_fake_result_or_moves(self):
        game = "(;GM[1]SZ[9]KM[7]RU[Chinese]RE[B+2]C[Fake RE\\[W+3\\] " \
               "semi ; and close \\] with \\\\ escape];" + \
               ";".join("B[aa];W[bb]" for _ in range(11)) + ";B[];W[])"
        nodes = sgf_nodes(game)
        self.assertGreater(len(nodes), 20)
        item = read_recorded_game(game, "test")
        self.assertEqual(item["official_re"], "B+2")
        self.assertTrue(item["terminal_passes"])

    def test_reject_non_scored_or_unsupported_game(self):
        samples = [
            ("RE[W+R]RU[Chinese]", "Only numeric scored"),
            ("RE[B+Time]RU[Chinese]", "Only numeric scored"),
            ("RE[W+2]RU[Japanese]", "Only explicitly Chinese"),
            ("RE[W+2]RU[Chinese]HA[2]AB[aa][bb]", "Handicap/setup"),
        ]
        for values, message in samples:
            text = "(;GM[1]SZ[9]KM[7]%s;" % values + \
                   ";".join("B[aa];W[bb]" for _ in range(12)) + ")"
            with self.subTest(values=values), self.assertRaisesRegex(ValueError, message):
                read_recorded_game(text, "bad")

    def test_reject_variations_instead_of_guessing_final_line(self):
        with self.assertRaisesRegex(ValueError, "variations"):
            sgf_nodes("(;GM[1]SZ[9]KM[7]RU[Chinese]RE[W+2](;B[aa])(;B[bb]))")

    def test_nn_metrics_and_outcome(self):
        lead, win, loss = parse_nn_metrics(
            "whiteWin 0.123 whiteLoss 0.877 whiteLead -2.400 policy 0.01")
        self.assertEqual((lead, win, loss), (-2.4, 0.123, 0.877))
        with self.assertRaisesRegex(ValueError, "missing"):
            parse_nn_metrics("whiteWin 0.3 whiteLoss 0.7")

    def test_comparison_preserves_exact_sgf_position_and_komi(self):
        case = collect_games()[0][0]
        case["remote"] = "/data/local/tmp/katago_bench/positions/test.sgf"
        engine = FakeEngine()
        out = compare_game(engine, case, "gpu")
        self.assertEqual(engine.calls, [
            "loadsgf /data/local/tmp/katago_bench/positions/test.sgf",
            "kata-get-rules", "get_komi", "clear_cache", "kata-raw-nn 0",
        ])
        self.assertTrue(out["winner_correct"])
        self.assertEqual(out["absolute_error"], 0.4)
        self.assertEqual(out["nn_ms"], 31.2)
        self.assertEqual(out["komi"], 7)

    def test_scoring_rules_and_komi_mismatch_fail(self):
        case = collect_games()[0][0]
        case["remote"] = "/positions/test.sgf"
        with self.assertRaisesRegex(ValueError, "komi differs"):
            compare_game(FakeEngine(komi="5.5"), case, "gpu")
        with self.assertRaisesRegex(ValueError, "changed rules"):
            compare_game(FakeEngine(rules='{"scoring":"TERRITORY","tax":"NONE"}'),
                         case, "gpu")

    def test_summary_is_explicit_about_game_count_and_repeats(self):
        case = collect_games()[0][0]
        case["remote"] = "/positions/test.sgf"
        engine = FakeEngine()
        row = compare_game(engine, case, "gpu")
        summary = summarize([row, row])["9x9"]
        self.assertEqual(summary["samples"], 2)
        self.assertEqual(summary["unique_games"], 1)
        self.assertEqual(summary["correct"], 2)
        self.assertEqual(summary["mae"], 0.4)
        self.assertEqual(summary["pass_terminated"], 2)


if __name__ == "__main__":
    unittest.main()
