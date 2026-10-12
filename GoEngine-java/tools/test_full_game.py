#!/usr/bin/env python3
"""Offline tests of the full-match Android UI driver and settlement parser."""
import datetime
import unittest
from types import SimpleNamespace

from full_game_test import BoardTracker, from_gtp, score_timings, run_full_game, _await_ai
from test_app_ui import TestFailure
from go_play_strategy import rank_moves, assess_final_position


def line(milliseconds, tag, message):
    dt = datetime.datetime(2026, 10, 12, 13, 30) + datetime.timedelta(milliseconds=milliseconds)
    return f"{dt.strftime('%m-%d %H:%M:%S.%f')[:-3]} D/{tag}( 10270): {message}"


def completed_game_logs():
    return "\n".join([
        line(0, "MainActivity", "finishByScore in, pendingPassColor=black"),
        line(50, "KataGoEngine", "parseGtpResponse in, response== 0"),
        line(100, "KataGoEngine", "sendCommandSync in, command=final_score, running=true"),
        line(130, "KataGoEngine", "parseGtpResponse in, response== 0"),
        line(640, "KataGoEngine", "parseGtpResponse in, response== W+86.5"),
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


class DefensiveStrategyTests(unittest.TestCase):
    def test_opening_takes_safe_corner_not_center(self):
        board = BoardTracker(9)
        choices = rank_moves(board)
        self.assertEqual(choices[0][0], (2, 2))
        self.assertNotIn((4, 4), [p for p, _ in choices])

    def test_repair_group_in_atari_before_expanding(self):
        board = BoardTracker(5)
        board.play("B", (2, 2))
        for point in ((1, 2), (2, 1), (3, 2)):
            board.play("W", point)
        self.assertEqual(len(board.group(board.stones, (2, 2))[1]), 1)
        self.assertEqual(rank_moves(board)[0][0], (2, 3))

    def test_do_not_fill_owned_eye(self):
        board = BoardTracker(5)
        for point in ((2, 1), (1, 2), (3, 2), (2, 3)):
            board.play("B", point)
        self.assertNotIn((2, 2), [p for p, _ in rank_moves(board)])

    def test_cannot_retry_excluded_point(self):
        board = BoardTracker(9)
        self.assertNotIn((2, 2), [p for p, _ in rank_moves(board, {(2, 2)})])

    def test_unrepresentative_one_color_game_is_detected(self):
        board = BoardTracker(9)
        for point in ((2, 2), (3, 2), (4, 2)):
            board.play("W", point)
        stats = assess_final_position(
            "Chinese area score B stones=0 territory=0, W stones=57 territory=24", board)
        self.assertEqual(stats["benchmark_quality"], "UNREPRESENTATIVE")
        self.assertEqual(stats["black_stones"], 0)

    def test_two_sided_territory_is_representative(self):
        board = BoardTracker(9)
        area = "Chinese area score B stones=8 territory=12, W stones=10 territory=9"
        result = assess_final_position(area, board)
        self.assertEqual(result["benchmark_quality"], "REPRESENTATIVE")
        self.assertEqual(result["black_territory"], 12)
        self.assertFalse(result["tracker_matches_score"])

    def test_dead_stones_reconcile_with_unadjudicated_board(self):
        board = BoardTracker(9)
        board.play("B", (2, 2))
        board.play("W", (6, 6))
        area = ("Chinese area score B stones=0 territory=0, "
                "W stones=1 territory=20, dead B=1 W=0")
        result = assess_final_position(area, board)
        self.assertTrue(result["tracker_matches_score"])
        self.assertEqual(result["dead_black"], 1)
        self.assertEqual(result["benchmark_quality"], "UNREPRESENTATIVE")

    def test_missing_area_log_is_unknown(self):
        self.assertEqual(assess_final_position(None, BoardTracker(9))["benchmark_quality"], "UNKNOWN")


class EndgameTests(unittest.TestCase):
    def test_endgame_stage_durations(self):
        result = score_timings(completed_game_logs(), year=2026)
        self.assertEqual(result["total_ms"], 4900)
        self.assertEqual(result["before_final_score_ms"], 100)
        self.assertEqual(result["final_score_ms"], 550)
        self.assertEqual(result["dead_stones_ms"], 3350)
        self.assertEqual(result["gtp_final_score"], "W+86.5")
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
        self.assertEqual(result["ai_passes"], 1)
        self.assertEqual(result["settlement"]["dead_stones_ms"], 3350)
        self.assertEqual(result["position_quality"]["benchmark_quality"], "REPRESENTATIVE")
        self.assertEqual(calls[-1], ("pass", "passButton"))


if __name__ == "__main__":
    unittest.main()
