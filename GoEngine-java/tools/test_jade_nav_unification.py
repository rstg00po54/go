#!/usr/bin/env python3
"""Regression tests for the single jade navigation bar shared across pages."""
import unittest
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
LAYOUT = ROOT / "app/src/main/res/layout"
JAVA = ROOT / "app/src/main/java/com/badukai"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def views(path):
    root = ET.parse(LAYOUT / path).getroot()
    return {
        node.get(ANDROID + "id")[5:]: node
        for node in root.iter()
        if node.get(ANDROID + "id", "").startswith("@+id/")
    }


class JadeNavTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.shared = (JAVA / "ui/JadeBottomNavView.java").read_text(encoding="utf-8")
        cls.route = (JAVA / "ui/JadeNavigation.java").read_text(encoding="utf-8")
        cls.main = (JAVA / "MainActivity.java").read_text(encoding="utf-8")
        cls.replay = (JAVA / "ReplayActivity.java").read_text(encoding="utf-8")
        cls.training = (JAVA / "TsumegoActivity.java").read_text(encoding="utf-8")
        cls.home_layout = views("activity_main.xml")
        cls.dialog = views("dialog_new_game.xml")
        cls.replay_layout = views("activity_replay.xml")
        cls.training_layout = views("activity_tsumego.xml")

    def test_every_page_reuses_identical_component(self):
        for name, page in (
            ("homeNavBarArtwork", self.home_layout),
            ("gameSharedNav", self.home_layout),
            ("newGameSharedNav", self.dialog),
            ("replaySharedNav", self.replay_layout),
            ("tsumegoSharedNav", self.training_layout),
        ):
            with self.subTest(page=name):
                self.assertEqual("com.badukai.ui.JadeBottomNavView", page[name].tag)
        for old in ("gameNavHome", "replayTabHome", "tsumegoTabHome",
                    "newGameHomeTab", "newGamePlayTab"):
            self.assertFalse(any(old in d for d in
                                 (self.home_layout, self.dialog,
                                  self.replay_layout, self.training_layout)))

    def test_original_five_tab_order_and_real_dynamic_highlight(self):
        self.assertIn('{"首页", "对弈", "棋谱", "训练", "设置"}', self.shared)
        for name, number in (("HOME", "0"), ("PLAY", "1"), ("RECORDS", "2"),
                             ("TRAINING", "3"), ("SETTINGS", "4")):
            self.assertIn("public static final int " + name + " = " + number + ";",
                          self.shared)
        for icon in ("ic_home_ivory", "ic_board_grid_gold", "ic_book_ivory",
                     "ic_training_ivory", "ic_settings_ivory"):
            self.assertIn("R.drawable." + icon, self.shared)
            self.assertTrue((ROOT / "app/src/main/res/drawable-nodpi"
                             / (icon + ".png")).exists())
        self.assertIn("public void setSelectedTab(int tab)", self.shared)
        self.assertIn("underlines[i].setVisibility(active", self.shared)
        self.assertIn("cells[i].setBackground(active ?", self.shared)
        self.assertIn("labels[i].setTextColor(active ?", self.shared)

    def test_each_host_selects_correct_tab(self):
        for source, token in (
            (self.main, "homeNav.setSelectedTab("),
            (self.main, "gameNav.setSelectedTab("),
            (self.main, "dialogNav.setSelectedTab("),
            (self.replay, "nav.setSelectedTab("),
            (self.training, "nav.setSelectedTab("),
        ):
            with self.subTest(token=token):
                self.assertIn(token, source)
        self.assertIn("JadeBottomNavView.RECORDS", self.replay)
        self.assertIn("JadeBottomNavView.TRAINING", self.training)
        self.assertIn("JadeBottomNavView.HOME", self.main)
        self.assertIn("JadeBottomNavView.PLAY", self.main)

    def test_single_navigation_dispatch_without_engine_reset(self):
        for target in ("navigate_home", "navigate_play"):
            self.assertIn(target, self.route)
        for item in ("ReplayActivity.class", "TsumegoActivity.class"):
            self.assertIn(item, self.route)
        self.assertIn("Intent.FLAG_ACTIVITY_CLEAR_TOP", self.route)
        self.assertIn("Intent.FLAG_ACTIVITY_SINGLE_TOP", self.route)
        self.assertIn('getBooleanExtra("navigate_home", false)', self.main)
        self.assertIn('getBooleanExtra("navigate_play", false)', self.main)
        self.assertIn("JadeNavigation.open(this, tab)", self.main)
        self.assertIn("JadeNavigation.open(this, tab)", self.replay)
        self.assertIn("JadeNavigation.open(this, tab)", self.training)
        self.assertIn('Toast.makeText(activity, "设置页面开发中"', self.route)
        self.assertNotIn('setHomeImageArt(R.id.homeNavBarArtwork', self.main)

    def test_no_baked_navbar_or_ghost_home_hitboxes(self):
        self.assertEqual("gone", self.home_layout["homeTabButton"].get(
            ANDROID + "visibility"))
        for id in ("homeGameTabButton", "homeRecordTabButton",
                   "homeTrainingTabButton", "homeSettingsTabButton"):
            self.assertEqual("gone", self.home_layout[id].get(ANDROID + "visibility"))
        home_touch = (JAVA / "ui/GoHomeTouchLayout.java").read_text(encoding="utf-8")
        self.assertIn("R.id.homeNavBarArtwork", home_touch)
        self.assertNotIn("R.id.homeGameTabButton", home_touch)
        self.assertNotIn("home/navbar/bar.png", self.main)

    def test_footers_stay_outside_scrollable_board_content(self):
        for page, nav_id in (
            (self.replay_layout, "replaySharedNav"),
            (self.training_layout, "tsumegoSharedNav"),
            (self.home_layout, "gameSharedNav"),
        ):
            with self.subTest(nav=nav_id):
                self.assertEqual("70dp", page[nav_id].get(ANDROID + "layout_height"))
        self.assertEqual("70dp", self.dialog["newGameSharedNav"].get(
            ANDROID + "layout_height"))


if __name__ == "__main__":
    unittest.main()
