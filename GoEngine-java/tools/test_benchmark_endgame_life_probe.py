#!/usr/bin/env python3
"""Offline smoke tests for local NN life/death probes. Does not require ADB."""
import unittest

from benchmark_endgame_life_probe_android import (
    nn_ownership_at, parse_vertices, run_probes,
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
