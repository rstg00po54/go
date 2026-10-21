#!/usr/bin/env python3
"""Batch checks: five working Jade pages, real SGF and local puzzles."""
import unittest
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
JAVA = ROOT / "app/src/main/java/com/badukai"
ANDROID = "{http://schemas.android.com/apk/res/android}"
PAGES = {
    "activity_replay": "ReplayActivity.java",
    "activity_sgf_library": "SgfLibraryActivity.java",
    "activity_tsumego": "TsumegoActivity.java",
    "activity_tsumego_explanation": "TsumegoExplanationActivity.java",
    "activity_tsumego_results": "TsumegoResultsActivity.java",
}


class JadeFiveScreensTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.screens = {}
        cls.java = {}
        for layout, activity in PAGES.items():
            cls.screens[layout] = ET.parse(RES / "layout" / (layout + ".xml")).getroot()
            cls.java[layout] = (JAVA / activity).read_text(encoding="utf-8")

    def test_five_native_pages_exist_and_have_valid_layout_structure(self):
        for page, root in self.screens.items():
            with self.subTest(page=page):
                self.assertEqual("FrameLayout", root.tag)
                ids = set()
                for view in root.iter():
                    self.assertIsNotNone(view.get(ANDROID + "layout_width"))
                    self.assertIsNotNone(view.get(ANDROID + "layout_height"))
                    id_attr = view.get(ANDROID + "id", "")
                    if id_attr.startswith("@+id/"):
                        self.assertNotIn(id_attr, ids)
                        ids.add(id_attr)
                    for key in ("background", "src", "drawableTop", "progressDrawable"):
                        attr = view.get(ANDROID + key, "")
                        if not attr.startswith("@drawable/"):
                            continue
                        image = attr.split("/", 1)[1]
                        if image.startswith(("ic_", "btn_round_")):
                            self.assertTrue((RES / "drawable-nodpi" / (image + ".png")).is_file(),
                                            page + ": missing PNG " + image)
                        else:
                            self.assertTrue((RES / "drawable" / (image + ".xml")).is_file(),
                                            page + ": missing shape " + image)
                self.assertGreater(len(ids), 5)
                self.assertIn("setContentView(R.layout." + page + ")", self.java[page])

    def test_manifest_declares_every_page(self):
        manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml")
        activities = {
            node.get(ANDROID + "name")
            for node in manifest.findall(".//activity")
        }
        for name in ("ReplayActivity", "SgfLibraryActivity", "TsumegoActivity",
                     "TsumegoExplanationActivity", "TsumegoResultsActivity"):
            self.assertIn("." + name, activities)

    def test_sgf_navigation_and_model_unchanged(self):
        replay = self.java["activity_replay"]
        library = self.java["activity_sgf_library"]
        self.assertIn("SgfLibraryActivity.class", replay)
        self.assertIn('getStringExtra("selected_sgf_file")', replay)
        self.assertIn("GoSgf.position(game,step)", replay)
        self.assertIn("boardView.setInputEnabled(false)", replay)
        self.assertIn("ACTION_OPEN_DOCUMENT", library)
        self.assertIn("SgfArchive.list(this)", library)
        self.assertIn("SgfArchive.save(this, sgf)", library)
        self.assertIn("GoSgf.position(parsed, parsed.moves.size())", library)
        self.assertIn("setResult(RESULT_OK, result)", library)
        self.assertIn("exportSgf()", replay)
        self.assertIn("shareSgf()", replay)

    def test_training_explanation_results_are_linked(self):
        training = self.java["activity_tsumego"]
        explain = self.java["activity_tsumego_explanation"]
        results = self.java["activity_tsumego_results"]
        for token in ("TsumegoExplanationActivity.class", "TsumegoResultsActivity.class",
                      "puzzle_index", "onBoardTap(", "showHint()", "resetPuzzle()",
                      "completed_mask"):
            self.assertIn(token, training)
        self.assertIn("new Move.Stone(puzzle.answer, puzzle.toPlay)", explain)
        self.assertIn("board.setInputEnabled(false)", explain)
        self.assertIn("setResult(RESULT_OK, selected)", explain)
        self.assertIn('"tsumego_progress"', results)
        self.assertIn('"completed_mask"', results)
        self.assertIn("Math.round(completed * 100f / puzzles.size())", results)
        self.assertIn('setResult(RESULT_OK, new Intent().putExtra("puzzle_index", index))',
                      results)

    def test_game_and_new_game_layouts_are_still_present(self):
        # These existing layouts were not rewritten by the five-screen batch.
        game = (RES / "layout/activity_main.xml").read_text(encoding="utf-8")
        new_game = (RES / "layout/dialog_new_game.xml").read_text(encoding="utf-8")
        main = (JAVA / "MainActivity.java").read_text(encoding="utf-8")
        self.assertIn('android:id="@+id/boardView"', game)
        self.assertIn('android:id="@+id/gameActionPanel"', game)
        self.assertIn('android:id="@+id/startGameButton"', new_game)
        self.assertIn("getMoveHistorySnapshot()", main)
        self.assertIn('getBooleanExtra("navigate_home", false)', main)

    def test_all_navigation_intents_target_declared_activities(self):
        shared = (JAVA / "ui/JadeNavigation.java").read_text(encoding="utf-8")
        for name in ("ReplayActivity", "SgfLibraryActivity", "TsumegoActivity",
                     "TsumegoExplanationActivity", "TsumegoResultsActivity"):
            target = name + ".class"
            self.assertTrue(any(target in code for code in self.java.values())
                            or target in shared,
                            msg="No navigation to " + target)
        self.assertIn('R.id.replaySharedNav', self.java["activity_replay"])
        self.assertIn('R.id.tsumegoSharedNav', self.java["activity_tsumego"])


if __name__ == "__main__":
    unittest.main()
