#!/usr/bin/env python3
"""Regression tests for the Java <-> Python referee TSV protocol (no device needed)."""
import io
import unittest

from benchmark_endgame_referee_android import java_case


class StubProc:
    def __init__(self, response):
        self.stdin = io.StringIO()
        self.stdout = io.StringIO(response)


class JavaRefereeProtocolTests(unittest.TestCase):
    def parse(self, last_field):
        fields = [
            "RESULT", "protocol_fixture", "REVIEW_REQUIRED", "1", "3", "10", "8.000",
            "UNCERTAIN_DEATH,POSSIBLE_KO", "A9", "1", "G8,H8,J8,H7",
            "B9,E9", "POSSIBLE_KO:A9,B9", "2", "E9,A8",
            "-29.000", "8.000", "3", "3", "B9,E9,A8", last_field,
        ]
        self.assertEqual(len(fields), 21)
        proc = StubProc("\t".join(fields) + "\n")
        case = {"name": "protocol_fixture", "size": 9, "sgf": "(;GM[1]SZ[9]KM[7];B[];W[])", "komi": 7}
        row = java_case(proc, case, [0.0] * 81)
        self.assertIn("GAME\tprotocol_fixture\t9", proc.stdin.getvalue())
        return row

    def test_trailing_empty_max_additional_dead(self):
        row = self.parse("")
        self.assertEqual(row["what_if_min_white_lead"], -29.0)
        self.assertEqual(row["what_if_max_white_lead"], 8.0)
        self.assertEqual(row["what_if_review_groups"], 3)
        self.assertEqual(row["what_if_enumerated_groups"], 3)
        self.assertEqual(row["what_if_max_additional_dead"], [])

    def test_nonempty_final_column(self):
        row = self.parse("B9,E9")
        self.assertEqual(row["what_if_max_additional_dead"], ["B9", "E9"])
        self.assertEqual(row["what_if_min_additional_dead"], ["B9", "E9", "A8"])


if __name__ == "__main__":
    unittest.main()
