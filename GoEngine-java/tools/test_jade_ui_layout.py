#!/usr/bin/env python3
"""Fast host-side checks for the Jade home/game Android layout."""
import unittest
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
DRAWABLES = ROOT / "app/src/main/res/drawable"
LAYOUT = ROOT / "app/src/main/res/layout/activity_main.xml"
MAIN = ROOT / "app/src/main/java/com/badukai/MainActivity.java"
HOME = ROOT / "app/src/main/java/com/badukai/ui/GoHomeTouchLayout.java"

class JadeUiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root = ET.parse(LAYOUT).getroot()
        cls.main_java = MAIN.read_text(encoding="utf-8")
        cls.home_java = HOME.read_text(encoding="utf-8")
        cls.ids = {}
        for view in cls.root.iter():
            id_value = view.get(ANDROID + "id", "")
            if id_value.startswith("@+id/"):
                name = id_value[5:]
                if name in cls.ids:
                    raise AssertionError("Duplicate UI id " + name)
                cls.ids[name] = view

    def test_existing_actions_remain_wired(self):
        actions = ("aiBattleButton", "homeRecordButton", "homeTrainingButton",
                   "homeSettingsButton", "backButton", "newGameButton",
                   "resignButton", "passButton", "undoButton", "newRoundButton",
                   "situationButton", "aiSuggestionButton",
                   "countTerritoryButton", "variationButton")
        for name in actions:
            with self.subTest(action=name):
                self.assertIn(name, self.ids)
                self.assertIn("R.id." + name, self.main_java)

    def test_live_data_ids(self):
        for name in ("statusText", "boardView", "gameTitleText", "aiDifficultyText",
                     "aiCaptureText", "playerCaptureText", "aiWinRateText",
                     "playerWinRateText", "aiMoveTimeText", "nnAreaScoreText"):
            with self.subTest(data=name):
                self.assertIn(name, self.ids)
                self.assertIn("R.id." + name, self.main_java)

    def test_winrate_progress_is_dynamic(self):
        self.assertEqual("ProgressBar", self.ids["winRateProgress"].tag)
        self.assertIn("winRateProgress = findViewById(", self.main_java)
        self.assertIn("winRateProgress.setProgress(", self.main_java)
        self.assertIn("winRateProgress.setOnClickListener(", self.main_java)

    def test_board_stays_weighted_and_centered(self):
        board_frame = self.ids["boardFrame"]
        self.assertIn(self.ids["boardView"], list(board_frame))
        self.assertEqual("0dp", board_frame.get(ANDROID + "layout_height"))
        self.assertEqual("1", board_frame.get(ANDROID + "layout_weight"))
        self.assertEqual("center", self.ids["boardView"].get(ANDROID + "layout_gravity"))

    def test_home_art_and_navigation(self):
        for name in ("homeArtwork", "homeNavBarArtwork", "homeTopShade",
                     "homeTitle", "homeSubtitle", "homeLocalBadge",
                     "homeGameTabButton", "homeRecordTabButton",
                     "homeTrainingTabButton", "homeSettingsTabButton"):
            self.assertIn(name, self.ids)
        for name in ("homeTopShade", "homeLocalBadge", "aiBattleButton",
                     "homeRecordButton", "homeTrainingButton"):
            self.assertIn("R.id." + name, self.home_java)

    def test_new_game_is_real_selection_page(self):
        dialog = ET.parse(ROOT / "app/src/main/res/layout/dialog_new_game.xml").getroot()
        controls = {}
        for view in dialog.iter():
            value = view.get(ANDROID + "id", "")
            if value.startswith("@+id/"):
                name = value[5:]
                self.assertNotIn(name, controls)
                controls[name] = view

        for size in (9, 11, 13, 15, 19):
            tile = controls["boardTile" + str(size)]
            self.assertEqual("com.badukai.ui.JadeBoardSizeView", tile.tag)
            self.assertIn("R.id.boardTile" + str(size), self.main_java)
        self.assertNotIn("sizeSpinner", controls)
        self.assertNotIn("backendSpinner", controls)
        self.assertNotIn("difficultySpinner", controls)
        self.assertEqual("SeekBar", controls["rankSeekBar"].tag)
        self.assertIn("rankSeek.setOnSeekBarChangeListener", self.main_java)
        for name in ("modeGroup", "modeAiButton", "modeLocalButton", "modeReplayButton",
                     "colorGroup", "blackRadio", "whiteRadio", "randomRadio",
                     "backendGroup", "backendCpuButton", "backendGpuButton",
                     "backendNpuButton", "conditionSpinner", "deviceModelText",
                     "hardwareInfoText", "aiRankValueText", "startGameButton",
                     "closeDialogButton", "dialogSettingsButton", "newGameHomeTab",
                     "newGameRecordTab", "newGamePlayTab", "newGameTrainingTab",
                     "newGameSettingsTab", "newGameBackdrop"):
            with self.subTest(control=name):
                self.assertIn(name, controls)
                self.assertIn("R.id." + name, self.main_java)

        self.assertEqual("ScrollView", controls["newGameSettingsScroll"].tag)
        self.assertEqual("@drawable/jade_dark_action",
                         controls["startGameButton"].get(ANDROID + "background"))
        self.assertIn("JADE_NEW_GAME_DIALOG_V3", self.main_java)
        self.assertIn("JADE_NEW_GAME_START", self.main_java)
        self.assertIn("hasBundledNpuModel(requestedSize)", self.main_java)
        self.assertIn("npuChoice.setEnabled(npuSupported)", self.main_java)
        self.assertIn("gpuChoice.setEnabled(gpuBundled", self.main_java)
        self.assertIn("content.findViewById(R.id.modeLocalButton).setEnabled(false)", self.main_java)
        self.assertEqual("match_parent", dialog.get(ANDROID + "layout_height"))
        self.assertEqual("match_parent", dialog.get(ANDROID + "layout_width"))
        ET.parse(ROOT / "app/src/main/res/layout/item_jade_dark_spinner.xml")
        ET.parse(ROOT / "app/src/main/res/layout/item_jade_dark_dropdown.xml")
        source = (ROOT / "app/src/main/java/com/badukai/ui/JadeBoardSizeView.java").read_text(encoding="utf-8")
        self.assertIn("public void setBoardSize(int n)", source)
        self.assertIn("for(int i=0;i<boardSize;i++)", source)

    def test_every_new_game_view_has_both_layout_dimensions(self):
        # Android's AAPT accepts missing dimensions; the layout inflater
        # crashes at runtime. Guard every view, including unlabeled views.
        dialog_path = ROOT / "app/src/main/res/layout/dialog_new_game.xml"
        for view in ET.parse(dialog_path).getroot().iter():
            label = view.get(ANDROID + "id", view.tag)
            with self.subTest(view=label):
                self.assertIsNotNone(
                    view.get(ANDROID + "layout_width"),
                    msg="Missing android:layout_width in " + str(label))
                self.assertIsNotNone(
                    view.get(ANDROID + "layout_height"),
                    msg="Missing android:layout_height in " + str(label))

    def test_new_game_drawable_resources_exist(self):
        dialog_path = ROOT / "app/src/main/res/layout/dialog_new_game.xml"
        for view in ET.parse(dialog_path).getroot().iter():
            for attr in ("background", "thumb", "progressDrawable"):
                value = view.get(ANDROID + attr, "")
                if value.startswith("@drawable/jade_"):
                    name = value.split("/", 1)[1] + ".xml"
                    with self.subTest(view=view.tag, drawable=name):
                        self.assertTrue((DRAWABLES / name).is_file(),
                            msg="Missing drawable " + name)

    def test_new_game_icons_are_vector_resources_not_unicode_placeholders(self):
        dialog = ET.parse(ROOT / "app/src/main/res/layout/dialog_new_game.xml").getroot()
        controls = {}
        for view in dialog.iter():
            name = view.get(ANDROID + "id", "")
            if name.startswith("@+id/"):
                controls[name[5:]] = view

        for name in ("closeDialogButton", "dialogSettingsButton"):
            with self.subTest(header=name):
                self.assertEqual("ImageView", controls[name].tag)
                self.assertTrue(controls[name].get(ANDROID + "src", "").startswith("@drawable/jade_ic_"))

        section_titles = {
            "棋盘大小": "grid", "对弈模式": "users", "AI 等级": "levels",
            "执子方": "yin", "AI 推理后端": "chip", "对局条件": "rule"
        }
        titles = {view.get(ANDROID + "text"): view
                  for view in dialog.iter() if view.tag == "TextView"}
        for label, icon in section_titles.items():
            with self.subTest(section=label):
                self.assertEqual("@drawable/jade_ic_" + icon,
                                 titles[label].get(ANDROID + "drawableStart"))

        cards = {
            "modeAiButton": "robot", "modeLocalButton": "users",
            "modeReplayButton": "book", "blackRadio": "stone_black",
            "whiteRadio": "stone_white", "randomRadio": "dice",
            "backendCpuButton": "chip", "backendGpuButton": "fan",
            "backendNpuButton": "brain",
            "newGameHomeTab": "home", "newGameRecordTab": "book",
            "newGamePlayTab": "go", "newGameTrainingTab": "training",
            "newGameSettingsTab": "settings"
        }
        for name, icon in cards.items():
            with self.subTest(icon=name):
                self.assertEqual("@drawable/jade_ic_" + icon,
                                 controls[name].get(ANDROID + "drawableTop"))
        self.assertIn("JADE_NEW_GAME_ICONS_V4", self.main_java)

    def test_icon_drawables_are_well_formed(self):
        dialog = ET.parse(ROOT / "app/src/main/res/layout/dialog_new_game.xml").getroot()
        icon_names = set()
        for view in dialog.iter():
            for field in ("src", "drawableStart", "drawableTop"):
                resource = view.get(ANDROID + field, "")
                if resource.startswith("@drawable/jade_ic_"):
                    icon_names.add(resource.split("/", 1)[1])
        self.assertGreaterEqual(len(icon_names), 17)
        for icon in sorted(icon_names):
            path = DRAWABLES / (icon + ".xml")
            with self.subTest(icon=icon):
                self.assertTrue(path.is_file())
                vector = ET.parse(path).getroot()
                self.assertEqual("vector", vector.tag)
                self.assertGreaterEqual(len(vector.findall("path")), 1)
                for subpath in vector.findall("path"):
                    self.assertTrue(subpath.get(ANDROID + "pathData"))
                    self.assertNotEqual("none", subpath.get(ANDROID + "fillColor"))

    def test_jade_drawables_are_valid(self):
        for path in DRAWABLES.glob("jade_*.xml"):
            with self.subTest(drawable=path.name):
                ET.parse(path)
        for view in self.root.iter():
            for attr in ("background", "progressDrawable"):
                value = view.get(ANDROID + attr, "")
                if value.startswith("@drawable/jade_"):
                    self.assertTrue(
                        (DRAWABLES / (value.split("/", 1)[1] + ".xml")).is_file(),
                        msg="Missing drawable " + value,
                    )

if __name__ == "__main__":
    unittest.main()
