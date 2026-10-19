#!/usr/bin/env python3
"""Static checks for the native jade SGF replay page (no emulator required)."""
import unittest
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
LAYOUT = ROOT / "app/src/main/res/layout/activity_replay.xml"
REPLAY = ROOT / "app/src/main/java/com/badukai/ReplayActivity.java"
BINDER = ROOT / "app/src/main/java/com/badukai/ui/JadeIconBinder.java"
DRAWABLES = ROOT / "app/src/main/res/drawable"
PNG = ROOT / "app/src/main/res/drawable-nodpi"


class JadeReplayUiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root = ET.parse(LAYOUT).getroot()
        cls.java = REPLAY.read_text(encoding="utf-8")
        cls.binder = BINDER.read_text(encoding="utf-8")
        cls.by_id = {}
        cls.parents = {c: p for p in cls.root.iter() for c in p}
        for v in cls.root.iter():
            id_attr = v.get(ANDROID + "id", "")
            if id_attr.startswith("@+id/"):
                name = id_attr[5:]
                if name in cls.by_id:
                    raise AssertionError("Duplicate replay view " + name)
                cls.by_id[name] = v

    def test_all_views_have_layout_dimensions(self):
        for v in self.root.iter():
            with self.subTest(view=v.tag):
                self.assertIsNotNone(v.get(ANDROID + "layout_width"))
                self.assertIsNotNone(v.get(ANDROID + "layout_height"))

    def test_native_square_board_and_read_only_input(self):
        frame = self.by_id["replayBoardFrame"]
        self.assertEqual("com.badukai.ui.JadeSquareBoardFrame", frame.tag)
        self.assertEqual("wrap_content", frame.get(ANDROID + "layout_height"))
        self.assertIn(self.by_id["replayBoard"], list(frame))
        self.assertEqual("com.badukai.ui.GoBoardView", self.by_id["replayBoard"].tag)
        self.assertIn("boardView.setInputEnabled(false)", self.java)
        self.assertIn("GoSgf.position(game,step)", self.java)
        self.assertIn("boardView.setLastMove(", self.java)

    def test_real_sgf_actions_and_playback_are_wired(self):
        actions = (
            "replayBackButton", "replayHistoryButton", "replayFirst",
            "replayPrev", "replayNext", "replayLast", "replayChooseHistory",
            "replayImport", "replayExport", "replayShare",
            "replaySharedNav",
        )
        for name in actions:
            with self.subTest(control=name):
                self.assertIn(name, self.by_id)
                self.assertIn("R.id." + name, self.java)
        for method in ("chooseHistory()", "chooseImport()", "exportSgf()",
                       "shareSgf()", "onActivityResult(", "showStep(", "load("):
            self.assertIn(method, self.java)
        self.assertIn("ACTION_OPEN_DOCUMENT", self.java)
        self.assertIn("ACTION_CREATE_DOCUMENT", self.java)
        self.assertIn("SgfLibraryActivity.class", self.java)
        self.assertIn('getStringExtra("selected_sgf_file")', self.java)
        library = (ROOT / "app/src/main/java/com/badukai/SgfLibraryActivity.java").read_text(encoding="utf-8")
        self.assertIn("SgfArchive.list(this)", library)
        self.assertIn("SgfArchive.save(this, sgf)", library)
        self.assertIn("SgfArchive.save(this,sgf)", self.java)
        self.assertIn("GoSgf.decode(sgf)", self.java)

    def test_empty_state_disables_playback(self):
        self.assertIn('if(game==null)', self.java)
        self.assertIn('setEnabled(game != null && step > 0)', self.java)
        self.assertIn('setEnabled(game != null && step < total)', self.java)
        self.assertIn('setEnabled(currentSgf != null)', self.java)
        self.assertIn('updateNavigationButtons()', self.java)

    def test_replay_progress_and_dynamic_data_ids(self):
        for name in ("replayBoardMeta", "replayMovesMeta", "replayCounter",
                     "replayStepInfo", "replaySeek"):
            with self.subTest(data=name):
                self.assertIn(name, self.by_id)
                self.assertIn("R.id." + name, self.java)
        self.assertEqual("SeekBar", self.by_id["replaySeek"].tag)
        self.assertIn("seek.setMax(game.moves.size())", self.java)
        self.assertIn("seek.setProgress(step)", self.java)
        self.assertIn("boardMeta.setText(", self.java)
        self.assertIn("movesMeta.setText(", self.java)
        self.assertIn("counter.setText(", self.java)

    def test_replay_uses_uploaded_jade_assets_safely(self):
        used_png = set()
        for view in self.root.iter():
            for name in ("background", "src", "drawableTop", "progressDrawable"):
                val = view.get(ANDROID + name, "")
                if not val.startswith("@drawable/"):
                    continue
                res = val.split("/", 1)[1]
                if res.startswith(("ic_", "btn_round_")):
                    self.assertTrue((PNG / (res + ".png")).is_file(),
                                    msg="PNG not found: " + res)
                    used_png.add(res)
                else:
                    self.assertTrue((DRAWABLES / (res + ".xml")).is_file(),
                                    msg="Drawable not found: " + res)
        self.assertGreaterEqual(len(used_png), 4)
        self.assertIn("nav.setSelectedTab(", self.java)
        self.assertIn("nav.setOnTabClickListener(", self.java)
        self.assertIn("JadeNavigation.open(this, tab)", self.java)
        self.assertEqual("com.badukai.ui.JadeBottomNavView",
                         self.by_id["replaySharedNav"].tag)

    def test_fixed_footer_and_scrollable_content(self):
        scrolls = [v for v in self.root.iter() if v.tag == "ScrollView"]
        self.assertEqual(1, len(scrolls))
        self.assertEqual("0dp", scrolls[0].get(ANDROID + "layout_height"))
        self.assertEqual("1", scrolls[0].get(ANDROID + "layout_weight"))
        footer = self.by_id["replaySharedNav"]
        self.assertEqual("96dp", footer.get(ANDROID + "layout_height"))
        self.assertNotIn(footer, list(scrolls[0].iter()))
        self.assertIn(self.by_id["replayBoardFrame"], list(scrolls[0].iter()))


if __name__ == "__main__":
    unittest.main()
