#!/usr/bin/env python3
"""Offline regression tests for the RK3588 KataGo UI automation.

Run without Android/ADB:
    python3 tools/test_app_ui_units.py
"""
import unittest
import xml.etree.ElementTree as ET

from test_app_ui import AppTest


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


if __name__ == "__main__":
    unittest.main()
