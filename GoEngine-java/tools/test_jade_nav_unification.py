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

    def test_layered_background_and_exact_nav_pngs(self):
        self.assertIn('private static final String BACKGROUND_ASSET = "home/navbar/bar0.png";',
                      self.shared)
        self.assertIn("context.getAssets().open(BACKGROUND_ASSET)", self.shared)
        self.assertIn("ImageView.ScaleType.FIT_CENTER", self.shared)
        self.assertNotIn("ImageView.ScaleType.FIT_XY", self.shared)
        self.assertIn("trimTransparentMargins(bitmap)", self.shared)
        self.assertIn('{"首页", "对弈", "棋谱", "训练", "设置"}', self.shared)
        self.assertTrue((ROOT / "docs/navbar/bar0.png").is_file())
        self.assertNotIn('home/navbar/bar.png', self.shared)
        self.assertNotIn("quietOriginalHomeUnderline", self.shared)
        self.assertNotIn("drawSelectedUnderline", self.shared)
        self.assertNotIn("bitmap.setPixel(", self.shared)
        self.assertNotIn("new TextView(context)", self.shared)
        for tab in ("home", "game", "record", "training", "settings"):
            for state in ("normal", "selected"):
                name = f"jade_nav_{tab}_{state}"
                self.assertIn(f"R.drawable.{name}", self.shared)
                self.assertTrue((ROOT / "app/src/main/res/drawable-nodpi" /
                                 (name + ".png")).is_file(), name)
        gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('from(rootProject.file("docs/navbar"))', gradle)
        self.assertIn('into("navbar")', gradle)
        self.assertIn("dependsOn(stageGreenHomeArtwork)", gradle)

    def test_five_tab_order_and_fixed_png_geometry(self):
        for name, number in (("HOME", "0"), ("PLAY", "1"), ("RECORDS", "2"),
                             ("TRAINING", "3"), ("SETTINGS", "4")):
            self.assertIn("public static final int " + name + " = " + number + ";",
                          self.shared)
        self.assertIn("public void setSelectedTab(int tab)", self.shared)
        self.assertIn("tabArtwork[i].setImageResource(TAB_DRAWABLES[i][i == tab ? 1 : 0])",
                      self.shared)
        self.assertIn("image.setAdjustViewBounds(false)", self.shared)
        self.assertIn("hitTargets[i].setSelected(i == tab)", self.shared)
        self.assertIn("hit.setOnClickListener(v ->", self.shared)
        self.assertIn("if (callback != null) callback.onTabClick(tab)", self.shared)

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
        home_touch = (JAVA / "ui/GoHomeTouchLayout.java").read_text(encoding="utf-8")
        self.assertIn("Math.round(96 * getResources().getDisplayMetrics().density)", home_touch)
        self.assertIn("android.view.Gravity.BOTTOM", home_touch)
        self.assertIn("lp.bottomMargin = Math.round(3 *", home_touch)
        self.assertIn("lp.rightMargin = side", home_touch)
        self.assertNotIn("getHeight() * .022f", home_touch)

    def test_footer_positions_match_across_pages(self):
        home_touch = (JAVA / "ui/GoHomeTouchLayout.java").read_text(encoding="utf-8")
        for token in ("LayoutParams.MATCH_PARENT,",
                      "Math.round(96 * getResources().getDisplayMetrics().density)",
                      "android.view.Gravity.BOTTOM", "lp.leftMargin = side",
                      "lp.rightMargin = side", "lp.bottomMargin = Math.round(3 *"):
            self.assertIn(token, home_touch)
        for page, name in (
            (self.home_layout, "gameSharedNav"),
            (self.dialog, "newGameSharedNav"),
            (self.replay_layout, "replaySharedNav"),
            (self.training_layout, "tsumegoSharedNav"),
        ):
            with self.subTest(footer=name):
                node = page[name]
                self.assertEqual("96dp", node.get(ANDROID + "layout_height"))
                self.assertEqual("9dp", node.get(ANDROID + "layout_marginStart"))
                self.assertEqual("9dp", node.get(ANDROID + "layout_marginEnd"))
                self.assertEqual("3dp", node.get(ANDROID + "layout_marginBottom"))

    def test_record_and_training_use_choice_menus_before_activity_launch(self):
        self.assertIn("showRecordChoices(activity)", self.route)
        self.assertIn("showTrainingChoices(activity)", self.route)
        self.assertIn('setTitle("棋谱 · 选择功能")', self.route)
        self.assertIn('setTitle("训练 · 选择内容")', self.route)
        for option in ("复盘最近一局", "选择历史棋谱", "导入 SGF 棋谱",
                       "继续上次练习", "从第一题开始", "按题目选择"):
            self.assertIn(option, self.route)
        self.assertIn("launchWithoutFlash(activity, intent)", self.route)
        self.assertIn("activity.overridePendingTransition(0, 0)", self.route)
        self.assertIn("openHistoryFromNavigation()", self.replay)
        self.assertIn("openImportFromNavigation()", self.replay)
        self.assertIn('getStringExtra("replay_entry_action")', self.replay)
        self.assertIn("openPuzzleFromNavigation(", self.training)
        self.assertIn("JadeNavigation.open(this,", self.main)
        self.assertNotIn("startActivity(new android.content.Intent(this, ReplayActivity.class))",
                         self.main)
        self.assertNotIn("startActivity(new android.content.Intent(this, TsumegoActivity.class))",
                         self.main)

    def test_footers_stay_outside_scrollable_board_content(self):
        for page, nav_id in (
            (self.replay_layout, "replaySharedNav"),
            (self.training_layout, "tsumegoSharedNav"),
            (self.home_layout, "gameSharedNav"),
        ):
            with self.subTest(nav=nav_id):
                self.assertEqual("96dp", page[nav_id].get(ANDROID + "layout_height"))
        self.assertEqual("96dp", self.dialog["newGameSharedNav"].get(
            ANDROID + "layout_height"))


if __name__ == "__main__":
    unittest.main()
