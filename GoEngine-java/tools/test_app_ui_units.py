#!/usr/bin/env python3
"""Offline regression tests for the RK3588 KataGo UI automation.

Run without Android/ADB:
    python3 tools/test_app_ui_units.py
"""
import json
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from types import SimpleNamespace

from test_app_ui import AppTest, TestFailure


class UiLeafNodeTests(unittest.TestCase):
    def setUp(self):
        # A trimmed version of the actual failed.xml uploaded by the RK3588.
        self.root = ET.fromstring(
            '<hierarchy><node text="悔棋" '
            'resource-id="com.badukai.java:id/undoButton" '
            'class="android.widget.Button" clickable="true" enabled="true" '
            'bounds="[545,1538][797,1651]" />'
            '<node text="开始对局" '
            'resource-id="com.badukai.java:id/startGameButton" '
            'class="android.widget.Button" clickable="true" enabled="true" '
            'bounds="[400,1100][700,1210]" /></hierarchy>'
        )
        self.app = object.__new__(AppTest)
        self.app.hierarchy = lambda fresh=False: self.root
        self.app.log = lambda message: None

    def test_matched_leaf_is_falsy_but_is_found(self):
        button = AppTest.node(self.root, rid="undoButton")
        self.assertIsNotNone(button)
        self.assertFalse(bool(button))  # The original UI test bug.
        found = self.app.poll(lambda root: AppTest.node(root, rid="undoButton"), 1, "悔棋按钮")
        self.assertIs(found, button)
        self.assertEqual(AppTest.center(found), (671, 1594))

    def test_start_game_leaf_is_found(self):
        button = self.app.poll(lambda root: AppTest.node(root, rid="startGameButton"),
                               1, "开始对局按钮")
        self.assertIsNotNone(button)
        self.assertEqual(button.get("text"), "开始对局")

    def test_text_lookup_leaf_is_found(self):
        button = self.app.poll(lambda root: AppTest.node(root, text="悔棋"),
                               1, "悔棋文字")
        self.assertEqual(button.get("resource-id"), "com.badukai.java:id/undoButton")

    def test_boolean_wait_predicate(self):
        self.assertTrue(self.app.poll(lambda root: AppTest.node(root, rid="undoButton") is not None,
                                      1, "按钮状态"))


class CacheAndLogTests(unittest.TestCase):
    def setUp(self):
        self.app = object.__new__(AppTest)
        self.app.log = lambda message: None
        self.app.screen_size = lambda: (1080, 1920)

    def test_geometry_persist_and_reuse(self):
        with tempfile.TemporaryDirectory() as directory:
            self.app.cache_file = Path(directory) / "ui_geometry_cache.json"
            self.app.geometry_key = "v2|rk3588|1080x1920|2.0|test"
            self.app.geometry = {}
            self.app.store_geometry("undoButton", "[545,1538][797,1651]")
            self.assertEqual(self.app.cached_center("undoButton"), (671, 1594))
            persisted = json.loads(self.app.cache_file.read_text(encoding="utf-8"))
            self.assertEqual(persisted["bounds"]["undoButton"], [545, 1538, 797, 1651])
            self.app.geometry = persisted["bounds"]
            self.assertEqual(self.app.cached_center("undoButton"), (671, 1594))

    def test_cached_bounds_must_be_onscreen(self):
        self.app.geometry = {"undoButton": [1050, 1500, 1300, 1700]}
        self.assertIsNone(self.app.cached_center("undoButton"))

    def test_mainactivity_log_events(self):
        log = (
            "I/MainActivity( 4823): Game backend requested=CPU actual=CPU/Eigen JNI ready=true\n"
            "D/MainActivity( 4823): render in, message=轮到你了, engineReady=true\n"
            "D/MainActivity( 4823): render in, message=AI 思考中..., engineReady=true\n"
            "D/MainActivity( 4823): handleAiMove in, move=F4, aiColor=WHITE\n"
            "D/MainActivity( 4823): render in, message=轮到你了, engineReady=true\n"
            "D/MainActivity( 4823): render in, message=已悔棋，轮到你了, engineReady=true\n"
        )
        self.app.adb = lambda *args, **kwargs: log
        state = self.app.read_log_state()
        self.assertEqual(state["backend"], [("CPU", "CPU/Eigen JNI", True)])
        self.assertEqual(state["turns"], 2)
        self.assertEqual(state["ai_moves"], 1)
        self.assertEqual(state["thinking"], 1)
        self.assertEqual(state["undo_done"], 1)
        self.assertTrue(state["debug_active"])

    def test_npu_log_tracks_actual_human_sl_backend(self):
        raw = ("I/MainActivity( 4823): Game backend requested=NPU "
               "actual=NPU 10b + CPU Human SL ready=true\\n"
               "D/MainActivity( 4823): render in, message=轮到你了, engineReady=true\\n")
        self.app.adb = lambda *args, **kwargs: raw
        state = self.app.read_log_state()
        self.assertEqual(state["backend"], [("NPU", "NPU 10b + CPU Human SL", True)])

    def test_npu_log_parser_matches_actual_rk3588_preload(self):
        # Lines copied from the published 2026-10-14 RK3588 failed_logcat,
        # which the original over-escaped regex incorrectly parsed as empty.
        model = "/data/user/0/com.badukai.java/files/engine/katago_10b_9x9.rknn"
        raw = ("10-14 16:19:34.421 I/KataGoEngine( 8887): NPU_MODEL_SELECTED board=9 path=" + model + "\\n"
               "10-14 16:19:34.811 I/KataGoRKNN( 8887): RKNN_INIT board=9 path=" + model + "\\n"
               "10-14 16:19:34.863 I/KataGoRKNN( 8887): RKNN_READY board=9 model=" + model + "\\n")
        arguments = []
        def fake_adb(*args, **kwargs):
            arguments.append(args)
            return raw
        self.app.adb = fake_adb
        state = self.app.read_npu_state()
        self.assertEqual(state["selected"], [("9", model)])
        self.assertEqual(state["init"], [("9", model)])
        self.assertEqual(state["ready"], [("9", model)])
        self.assertIn("KataGoRKNN:I", arguments[0])
        self.app.last_verified_npu_size = None
        self.app.assert_npu_board(9, state)
        self.assertEqual(self.app.last_verified_npu_size, 9)

    def test_npu_board_verifies_real_model_instead_of_selected_label(self):
        self.app.last_verified_npu_size = 9
        model = "/data/user/0/com.badukai.java/files/engine/katago_10b_13x13.rknn"
        before = {"selected": [("9", "old")], "init": [("9", "old")],
                  "ready": [("9", "old")], "fallback": 0}
        current = {"selected": before["selected"] + [("13", model)],
                   "init": before["init"] + [("13", model)],
                   "ready": before["ready"] + [("13", model)], "fallback": 0}
        self.app.read_npu_state = lambda: current
        self.app.assert_npu_board(13, before)
        self.assertEqual(self.app.last_verified_npu_size, 13)
        bad = dict(current, ready=before["ready"] + [("9", model.replace("13x13", "9x9"))])
        self.app.read_npu_state = lambda: bad
        with self.assertRaises(TestFailure):
            self.app.assert_npu_board(13, before)
        self.app.read_npu_state = lambda: dict(current, fallback=1)
        with self.assertRaises(TestFailure):
            self.app.assert_npu_board(13, before)

    def test_fast_wait_detects_new_backend_and_turn(self):
        self.app.use_log_wait = True
        self.app.args = SimpleNamespace(think_timeout=5)
        self.app.wait_log = lambda predicate, timeout, description: predicate({
            "backend": [("CPU", "CPU/Eigen JNI", True)],
            "turns": 1, "ai_moves": 0
        })
        before = {"backend": [], "turns": 0, "ai_moves": 0}
        self.assertEqual(self.app.wait_turn(9, "cpu", baseline=before), 1)

    def test_fast_wait_rejects_wrong_backend(self):
        self.app.use_log_wait = True
        self.app.args = SimpleNamespace(think_timeout=5)
        self.app.wait_log = lambda predicate, timeout, description: predicate({
            "backend": [("CPU", "CPU/Eigen JNI", True)],
            "turns": 1, "ai_moves": 0
        })
        before = {"backend": [], "turns": 0, "ai_moves": 0}
        with self.assertRaises(TestFailure):
            self.app.wait_turn(9, "gpu", baseline=before)

    def test_cached_spinner_transition_verifies_dialog_after_tap(self):
        self.app.geometry = {
            "backendSpinner": [400, 940, 750, 1130],
            "choice:backendSpinner:CPU / Eigen->GPU / OpenCL": [430, 1130, 725, 1210]
        }
        self.app.selected_size = 9
        self.app.selected_backend = "CPU"
        hits = []
        self.app.click = lambda rid=None, text=None: hits.append(("click", rid))
        self.app.tap = lambda x, y: hits.append(("tap", x, y))
        root = ET.fromstring(
            '<hierarchy><node resource-id="com.badukai.java:id/backendSpinner" '
            'bounds="[400,940][750,1130]"><node text="GPU / OpenCL" '
            'bounds="[410,980][740,1040]" /></node>'
            '<node resource-id="com.badukai.java:id/startGameButton" '
            'bounds="[400,1200][700,1300]" /></hierarchy>'
        )
        self.app.poll = lambda predicate, timeout, description: predicate(root)
        self.app.spinner("backendSpinner", "GPU / OpenCL")
        self.assertEqual(self.app.selected_backend, "GPU")
        self.assertEqual(hits[0], ("click", "backendSpinner"))
        self.assertEqual(hits[1], ("tap", 577, 1170))

    def test_game_start_requires_mainactivity_ack(self):
        self.app.args = SimpleNamespace(size=9, slow_ui=True)
        calls = []
        logs = ["", "I/MainActivity( 4823): Saved new-game preference boardSize=9 backend=GPU"]
        self.app.adb = lambda *args, **kwargs: logs.pop(0) if logs else logs[-1]
        self.app.start_game_from_layout = lambda: calls.append("tap")
        self.app.start_game_confirmed("gpu")
        self.assertEqual(calls, ["tap"])

    def test_game_start_rejects_unexpected_backend(self):
        self.app.args = SimpleNamespace(size=9, slow_ui=True)
        logs = ["", "I/MainActivity( 4823): Saved new-game preference boardSize=9 backend=CPU"]
        self.app.adb = lambda *args, **kwargs: logs.pop(0) if len(logs) > 1 else logs[0]
        self.app.start_game_from_layout = lambda: None
        with self.assertRaises(TestFailure):
            self.app.start_game_confirmed("gpu")


    def test_full_game_mainactivity_events(self):
        log = (
            "D/MainActivity( 123): handleAiMove in, move=pass, aiColor=WHITE\n"
            "D/MainActivity( 123): render in, message=AI 停一手，你下, engineReady=true\n"
            "D/MainActivity( 123): pass in, engineReady=true, thinking=false\n"
            "D/MainActivity( 123): finishByScore in, pendingPassColor=black\n"
            "I/MainActivity( 123): Chinese area score B stones=5 territory=7 W stones=4\n"
            "D/MainActivity( 123): render in, message=黑12目\n"
        )
        self.app.adb = lambda *args, **kwargs: log
        result = self.app.read_log_state()
        self.assertEqual(result["ai_replies"], ["pass"])
        self.assertEqual(result["ai_pass_ready"], 1)
        self.assertEqual(result["player_passes"], 1)
        self.assertEqual(result["final_enter"], 1)
        self.assertEqual(result["score_summary"], 1)
        self.assertEqual(result["final_render"], 1)
    def test_read_log_state_keeps_debug_filter(self):
        # On Android a duplicate MainActivity:I spec can mask MainActivity:D.
        arguments = []
        def adb(*args, **kwargs):
            arguments.append(args)
            return (
                "I/MainActivity( 7326): Game backend requested=GPU actual=GPU/OpenCL JNI ready=true\n"
                "D/MainActivity( 7326): render in, message=AI 思考中..., engineReady=true\n"
                "D/MainActivity( 7326): handleAiMove in, move=F3, aiColor=WHITE\n"
                "D/MainActivity( 7326): render in, message=轮到你了, engineReady=true\n"
            )
        self.app.adb = adb
        state = self.app.read_log_state()
        self.assertTrue(state["debug_active"])
        self.assertEqual(state["backend"][-1], ("GPU", "GPU/OpenCL JNI", True))
        self.assertEqual(state["thinking"], 1)
        self.assertEqual(state["ai_moves"], 1)
        self.assertEqual(state["turns"], 1)
        self.assertIn("MainActivity:D", arguments[0])
        self.assertNotIn("MainActivity:I", arguments[0])

    def test_fast_mode_activates_after_first_start_not_home(self):
        self.app.args = SimpleNamespace(size=9, slow_ui=False)
        self.app.use_log_wait = False
        calls = []
        logs = ["", "I/MainActivity( 4823): Saved new-game preference boardSize=9 backend=CPU"]
        self.app.adb = lambda *args, **kwargs: logs.pop(0) if len(logs) > 1 else logs[0]
        self.app.start_game_from_layout = lambda: calls.append("tap")
        self.app.read_log_state = lambda: {"debug_active": True}
        self.app.start_game_confirmed("cpu")
        self.assertTrue(self.app.use_log_wait)
        self.assertEqual(calls, ["tap"])

    def test_fast_accepted_ai_move_requires_new_thinking_event(self):
        self.app.use_log_wait = True
        self.app.geometry = {"boardView": [0, 447, 1075, 1522]}
        self.app.density = lambda: 3.0
        self.app.read_log_state = lambda: {"thinking": 3, "ai_moves": 2, "turns": 4}
        self.app.tap = lambda *args: None
        self.app.wait_log = lambda predicate, timeout, label: predicate({"thinking": 4})
        self.app.wait_turn = lambda size, backend, minimum, **kwargs: minimum
        result = self.app.move(9, "cpu", 5, 1)
        self.assertEqual(result, 7)

    def test_fast_invalid_point_tries_next_candidate(self):
        self.app.use_log_wait = True
        self.app.geometry = {"boardView": [0, 447, 1075, 1522]}
        self.app.density = lambda: 3.0
        self.app.read_log_state = lambda: {"thinking": 3, "ai_moves": 2, "turns": 4}
        clicks = []
        self.app.tap = lambda *args: clicks.append(args)
        attempts = [0]
        def wait_log(predicate, timeout, label):
            attempts[0] += 1
            if attempts[0] == 1:
                raise TestFailure("确认落子，日志等待超过 2.5s")
            return predicate({"thinking": 4})
        self.app.wait_log = wait_log
        self.app.wait_turn = lambda size, backend, minimum, **kwargs: minimum
        result = self.app.move(9, "cpu", 5, 1)
        self.assertEqual(result, 7)
        self.assertEqual(len(clicks), 2)
        self.assertNotEqual(clicks[0], clicks[1])

    def test_fast_spinner_uses_cache_without_dump(self):
        self.app.args = SimpleNamespace(slow_ui=False)
        self.app.geometry = {
            "backendSpinner": [400, 940, 750, 1130],
            "choice:backendSpinner:CPU / Eigen->GPU / OpenCL": [430, 1130, 725, 1210]
        }
        self.app.selected_backend = "CPU"
        self.app.selected_size = 9
        self.app.poll = lambda *args: self.fail("Fast menu must not dump the UI tree")
        hits = []
        self.app.click = lambda rid=None, text=None: hits.append(("click", rid))
        self.app.tap = lambda x, y: hits.append(("tap", x, y))
        self.app.spinner("backendSpinner", "GPU / OpenCL")
        self.assertEqual(hits, [("click", "backendSpinner"), ("tap", 577, 1170)])
        self.assertEqual(self.app.selected_backend, "GPU")

    def test_slow_spinner_still_verifies_visible_selection(self):
        self.app.args = SimpleNamespace(slow_ui=True)
        self.app.geometry = {
            "backendSpinner": [400, 940, 750, 1130],
            "choice:backendSpinner:CPU / Eigen->GPU / OpenCL": [430, 1130, 725, 1210]
        }
        self.app.selected_backend = "CPU"
        self.app.selected_size = 9
        hits = []
        self.app.click = lambda rid=None, text=None: hits.append(("click", rid))
        self.app.tap = lambda x, y: hits.append(("tap", x, y))
        root = ET.fromstring(
            '<hierarchy><node resource-id="com.badukai.java:id/backendSpinner" '
            'bounds="[400,940][750,1130]"><node text="GPU / OpenCL" '
            'bounds="[410,980][740,1040]" /></node>'
            '<node resource-id="com.badukai.java:id/startGameButton" '
            'bounds="[400,1200][700,1300]" /></hierarchy>'
        )
        confirmations = []
        def poll(predicate, timeout, description):
            confirmations.append(description)
            return predicate(root)
        self.app.poll = poll
        self.app.spinner("backendSpinner", "GPU / OpenCL")
        self.assertEqual(len(confirmations), 1)
        self.assertEqual(self.app.selected_backend, "GPU")

    def test_success_diagnostics_skips_ui_dump(self):
        with tempfile.TemporaryDirectory() as directory:
            self.app.out = Path(directory)
            self.app.last_xml = b'<hierarchy />'
            self.app.hierarchy = lambda *args, **kwargs: self.fail("PASS must not export XML")
            self.app.screenshot = lambda name: (self.app.out / name).write_bytes(b'PNG')
            self.app.adb = lambda *args, **kwargs: 'I/MainActivity: ready'
            self.app.diagnostics("final", capture_ui=False)
            self.assertFalse((self.app.out / "final.xml").exists())
            self.assertTrue((self.app.out / "final.png").exists())
            self.assertTrue((self.app.out / "final_logcat.txt").exists())

    def test_benchmark_reuses_no_human_stone_positions(self):
        self.app.args = SimpleNamespace(benchmark=True)
        self.app.use_log_wait = True
        self.app.geometry = {"boardView": [0, 447, 1075, 1522]}
        self.app.density = lambda: 3.0
        self.app.bench_used_positions = {(2, 2)}
        self.app.read_log_state = lambda: {"thinking": 1, "ai_moves": 0, "turns": 1}
        self.app.wait_log = lambda callback, timeout, desc: callback({"thinking": 2})
        self.app.wait_turn = lambda size, backend, minimum, **kwargs: minimum
        points = []
        self.app.tap = lambda x, y: points.append((x, y))
        self.assertEqual(self.app.move(9, "cpu", 1, 0), 3)
        self.assertEqual(len(points), 1)
        self.assertIn((6, 6), self.app.bench_used_positions)

    def test_normal_ui_move_does_not_require_benchmark_state(self):
        self.app.args = SimpleNamespace(benchmark=False)
        self.app.use_log_wait = True
        self.app.geometry = {"boardView": [0, 447, 1075, 1522]}
        self.app.density = lambda: 3.0
        self.app.read_log_state = lambda: {"thinking": 1, "ai_moves": 0, "turns": 1}
        self.app.wait_log = lambda callback, timeout, desc: callback({"thinking": 2})
        self.app.wait_turn = lambda size, backend, minimum, **kwargs: minimum
        self.app.tap = lambda *args: None
        self.assertEqual(self.app.move(9, "cpu", 1, 0), 3)


if __name__ == "__main__":
    unittest.main()
