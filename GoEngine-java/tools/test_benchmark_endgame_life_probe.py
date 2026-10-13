#!/usr/bin/env python3
"""Offline smoke tests for local NN life/death probes. Does not require ADB."""
import unittest

from benchmark_endgame_life_probe_android import (
    nn_ownership_at, parse_vertices, parse_search_candidates, run_probes,
)


class FakeGtpEngine:
    def __init__(self):
        self.calls = []
        self.move = None

    def command(self, command):
        self.calls.append(command)
        if command.startswith("loadsgf "):
            self.move = None
            return "", 0.1
        if command == "showboard":
            return "MoveNum: 82", 0.1
        if command == "kata-get-rules":
            return '{"scoring":"AREA","tax":"NONE"}', 0.1
        if command == "get_komi":
            return "7", 0.1
        if command.startswith("kata-set-param "):
            return "",0.1
        if command.startswith("kata-search_analyze "):
            return ("info move D4 visits 64 winrate 0.73 scoreLead 3.25 "
                    "prior 0.13 order 0 pv D4 C7 B9 "
                    "info move A9 visits 12 winrate 0.50 scoreLead -0.75 "
                    "order 1 pv A9 B9"),2.5
        if command.startswith("play "):
            self.move = command
            return "", 0.1
        if command == "kata-raw-nn 0":
            score = 1.0 if self.move is None else (2.0 if "black" in self.move else 0.0)
            ownership = [0.1] * 81
            ownership[1] = 0.5 if self.move else 0.2
            data = "whiteLead %s whiteWin 0.5 whiteLoss 0.5 whiteOwnership %s" % (
                score, " ".join(str(value) for value in ownership))
            return data, 0.3
        raise AssertionError("unexpected GTP command: " + command)


class LifeProbeTests(unittest.TestCase):
    def test_gtp_coordinates(self):
        self.assertEqual(parse_vertices("a9, c7, A9, t1", 19), ["A9", "C7", "T1"])
        self.assertEqual(nn_ownership_at([0.0,0.75]+[0.0]*79, "B9", 9), 0.75)
        with self.assertRaises(ValueError):
            parse_vertices("I9", 9)
        with self.assertRaises(ValueError):
            parse_vertices("T9", 9)
        with self.assertRaises(ValueError):
            parse_vertices("A0", 9)

    def test_search_parse(self):
        rows = parse_search_candidates(
            "info move D4 visits 64 winrate 0.73 scoreLead 3.25 order 0 pv D4 C7 B9 "
            "info move A9 visits 12 winrate 0.50 scoreLead -0.75 order 1 pv A9 B9")
        self.assertEqual([r["move"] for r in rows], ["D4","A9"])
        self.assertEqual(rows[0]["pv"], ["D4","C7","B9"])
        self.assertEqual(rows[0]["reply_score_lead"], 3.25)
        # KataGo's documented format may include rootInfo, an initial timestamp,
        # or a finishing 'play' line; the parser should ignore those.
        documented = ("info move E4 visits 487 utility -0.04 winrate 0.480018 "
                      "scoreLead -0.611848 order 0 pv E4 E3 F3 D3 "
                      "info move P16 visits 470 winrate 0.479712 "
                      "scoreLead -0.63075 order 1 pv P16 P17 "
                      "rootInfo visits 1101 winrate 0.48 play E4")
        found = parse_search_candidates(documented)
        self.assertEqual([item["move"] for item in found], ["E4","P16"])
        self.assertEqual(found[0]["visits"], 487)
        self.assertEqual(found[0]["pv"], ["E4","E3","F3","D3"])

    def test_mcts_reply_probe_is_nonplaying(self):
        engine = FakeGtpEngine()
        case = {"name":"case", "remote":"/tmp/end.sgf", "size":9,
                "moves":82, "komi":7.0, "terminal_passes":True}
        baseline, rows = run_probes(engine, case, ["A9"], ["B9"],64,3.0)
        self.assertEqual([r["reply_player"] for r in rows], ["white","black"])
        self.assertEqual(rows[0]["reply_candidates"][0]["move"], "D4")
        self.assertIn("info move D4", rows[0]["reply_search_raw"])
        self.assertGreater(rows[0]["reply_search_raw_length"], 20)
        self.assertEqual(engine.calls.count("kata-search_analyze white 50 minmoves 3 maxmoves 3"),1)
        self.assertEqual(engine.calls.count("kata-search_analyze black 50 minmoves 3 maxmoves 3"),1)
        self.assertEqual(engine.calls[-1], "loadsgf /tmp/end.sgf")

    def test_probes_reload_original_passes_and_are_independent(self):
        engine = FakeGtpEngine()
        case = {"name":"case", "remote":"/tmp/end.sgf", "size":9,
                "moves":82, "komi":7.0, "terminal_passes":True}
        baseline, results = run_probes(engine, case, ["A9"], ["B9"])
        self.assertEqual(baseline["white_lead"], 1.0)
        self.assertEqual([row["status"] for row in results], ["ok","ok"])
        self.assertEqual([row["delta_white_lead"] for row in results], [1.0,-1.0])
        self.assertEqual([row["watch_white_ownership"]["B9"] for row in results], [0.5,0.5])
        self.assertEqual(engine.calls.count("loadsgf /tmp/end.sgf"), 5)
        self.assertNotIn("undo", engine.calls)
        self.assertEqual(engine.calls[-1], "loadsgf /tmp/end.sgf")


if __name__ == "__main__":
    unittest.main()
