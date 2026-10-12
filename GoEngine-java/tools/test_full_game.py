#!/usr/bin/env python3
"""Offline tests of the full-match Android UI driver and settlement parser."""
import datetime
import unittest
from types import SimpleNamespace

from full_game_test import BoardTracker, from_gtp, score_timings, run_full_game, _await_ai
from test_app_ui import TestFailure


def line(milliseconds, tag, message):
    dt = datetime.datetime(2026, 10, 12, 13, 30) + datetime.timedelta(milliseconds=milliseconds)
    return f"{dt.strftime('%m-%d %H:%M:%S.%f')[:-3]} D/{tag}( 10270): {message}"


def completed_game_logs():
    return "\n".join([
        line(0, "MainActivity", "finishByScore in, pendingPassColor=black"),
        line(100, "KataGoEngine", "sendCommandSync in, command=final_score, running=true"),
        line(650, "KataGoEngine", "sendCommandSync in, command=final_status_list dead, running=true"),
        line(4000, "KataGoEngine", "sendCommandSync in, command=kata-raw-nn 0, running=true"),
        line(4500, "KataGoEngine", "Position evaluation whiteWin=0.50 elapsedMs=499"),
        line(4800, "MainActivity", "Chinese area score B stones=22 territory=5, W stones=20 territory=6"),
        line(4900, "MainActivity", "render in, message=黑27目"),
    ])


class BoardTrackerTests(unittest.TestCase):
    def test_gtp_coordinates_skip_i_and_flip_y(self):
        self.assertEqual(from_gtp("A9", 9), (0, 0))
        self.assertEqual(from_gtp("J1", 9), (8, 8))
        self.assertIsNone(from_gtp("I5", 9))
        self.assertIsNone(from_gtp("A10", 9))
        self.assertIsNone(from_gtp("pass", 9))

    def test_capture_removes_surrounded_white_stone(self):
        b = BoardTracker(5)
        b.play("W", (2, 2))
        for p in ((2, 1), (1, 2), (3, 2), (2, 3)):
            b.play("B", p)
        self.assertNotIn((2, 2), b.stones)
        self.assertTrue(b.legal("B", (2, 2)))

    def test_suicide_and_occupied_moves_rejected(self):
        b = BoardTracker(3)
        for p in ((1, 0), (0, 1), (2, 1), (1, 2)):
            b.play("W", p)
        self.assertFalse(b.legal("B", (1, 1)))
        self.assertFalse(b.legal("B", (1, 0)))
        self.assertRaises(TestFailure, b.play, "B", (1, 1))

    def test_pass_preserves_board(self):
        b = BoardTracker(9)
        b.play("B", (4, 4))
        b.play("W", None)
        self.assertEqual(b.stones[(4, 4)], "B")


class EndgameTests(unittest.TestCase):
    def test_endgame_stage_durations(self):
        result = score_timings(completed_game_logs(), year=2026)
        self.assertEqual(result["total_ms"], 4900)
        self.assertEqual(result["before_final_score_ms"], 100)
        self.assertEqual(result["final_score_ms"], 550)
        self.assertEqual(result["dead_stones_ms"], 3350)
        self.assertEqual(result["raw_nn_to_render_ms"], 900)

    def test_missing_dead_stones_command_is_failure(self):
        broken = completed_game_logs().replace("final_status_list dead", "not_dead")
        with self.assertRaisesRegex(ValueError, "日志不完整"):
            score_timings(broken, year=2026)

    def test_last_game_only_is_measured(self):
        raw = completed_game_logs() + "\n" + completed_game_logs().replace("final_status_list dead", "missing")
        with self.assertRaisesRegex(ValueError, "日志不完整"):
            score_timings(raw, year=2026)

    def test_wait_ai_pass_and_scoring(self):
        baseline = {"final_enter": 0, "ai_replies": [], "ai_pass_ready": 0, "turns": 0}
        r = SimpleNamespace(wait_log=lambda pred, seconds, reason: pred({
            "final_enter": 0, "ai_replies": ["pass"], "ai_pass_ready": 1, "turns": 0}))
        self.assertEqual(_await_ai(r, baseline, 5), ("pass", "pass"))
        r.wait_log = lambda pred, seconds, reason: pred({
            "final_enter": 1, "ai_replies": ["pass"], "ai_pass_ready": 0, "turns": 0})
        self.assertEqual(_await_ai(r, baseline, 5), ("scoring", None))

    def test_complete_fake_match_requires_two_passes_and_real_score(self):
        state = {"thinking": 0, "ai_replies": [], "ai_pass_ready": 0, "turns": 0,
                 "final_enter": 0, "player_passes": 0, "score_summary": 0, "final_render": 0, "fatal": False}
        calls = []
        def snap():
            return {k: list(v) if isinstance(v, list) else v for k, v in state.items()}
        def tap(x, y):
            calls.append(("stone", x, y))
            state["thinking"] += 1
            state["ai_replies"].append("pass")
            state["ai_pass_ready"] += 1
        def click(rid=None):
            calls.append(("pass", rid))
            state["player_passes"] += 1
            state["final_enter"] += 1
            state["score_summary"] += 1
            state["final_render"] += 1
        def wait_log(fn, duration, desc):
            result = fn(snap())
            if result is None or result is False:
                raise AssertionError("Mock did not reach event: " + desc)
            return result
        runner = SimpleNamespace(
            use_log_wait=True, geometry={"boardView": [0, 0, 900, 900]},
            density=lambda: 2.0, screen_size_cached=(900,900),
            read_log_state=snap, tap=tap, click=click, wait_log=wait_log,
            log=lambda msg: None, args=SimpleNamespace(think_timeout=3),
            adb=lambda *args, **kwargs: completed_game_logs())
        result = run_full_game(runner, 9, "cpu", 3, 10)
        self.assertEqual(result["end_type"], "two_passes")
        self.assertEqual(result["black_stones_played"], 1)
        self.assertEqual(result["player_passes"], 1)
        self.assertEqual(result["settlement"]["dead_stones_ms"], 3350)
        self.assertEqual(calls[-1], ("pass", "passButton"))


if __name__ == "__main__":
    unittest.main()
