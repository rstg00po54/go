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
            "D/MainActivity( 4823): handleAiMove in, move=F4, aiColor=WHITE\n"
            "D/MainActivity( 4823): render in, message=轮到你了, engineReady=true\n"
            "D/MainActivity( 4823): render in, message=已悔棋，轮到你了, engineReady=true\n"
        )
        self.app.adb = lambda *args, **kwargs: log
        state = self.app.read_log_state()
        self.assertEqual(state["backend"], [("CPU", "CPU/Eigen JNI", True)])
        self.assertEqual(state["turns"], 2)
        self.assertEqual(state["ai_moves"], 1)
        self.assertEqual(state["undo_done"], 1)
        self.assertTrue(state["debug_active"])

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

    def test_cached_spinner_transition_does_not_fetch_hierarchy(self):
        self.app.geometry = {
            "backendSpinner": [400, 940, 750, 1130],
            "choice:backendSpinner:CPU / Eigen->GPU / OpenCL": [430, 1130, 725, 1210]
        }
        self.app.selected_size = 9
        self.app.selected_backend = "CPU"
        self.app.hierarchy = lambda *args, **kwargs: self.fail("Unexpected hierarchy dump")
        hits = []
        self.app.click = lambda rid=None, text=None: hits.append(("click", rid))
        self.app.tap = lambda x, y: hits.append(("tap", x, y))
        self.app.spinner("backendSpinner", "GPU / OpenCL")
        self.assertEqual(self.app.selected_backend, "GPU")
        self.assertEqual(hits[0], ("click", "backendSpinner"))
        self.assertEqual(hits[1], ("tap", 577, 1170))


if __name__ == "__main__":
    unittest.main()
