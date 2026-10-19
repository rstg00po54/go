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

    def test_board_is_square_and_game_content_can_scroll(self):
        # A weighted board was squeezed to about half the available width on
        # the 1080x1920 RK3588 screen. The board's width now sets its height.
        board_frame = self.ids["boardFrame"]
        self.assertEqual("com.badukai.ui.JadeSquareBoardFrame", board_frame.tag)
        self.assertIn(self.ids["boardView"], list(board_frame))
        self.assertEqual("wrap_content", board_frame.get(ANDROID + "layout_height"))
        self.assertIsNone(board_frame.get(ANDROID + "layout_weight"))
        self.assertEqual("center", self.ids["boardView"].get(ANDROID + "layout_gravity"))
        scroll = self.ids["gameContentScroll"]
        self.assertEqual("ScrollView", scroll.tag)
        self.assertEqual("0dp", scroll.get(ANDROID + "layout_height"))
        self.assertEqual("1", scroll.get(ANDROID + "layout_weight"))
        square = (ROOT / "app/src/main/java/com/badukai/ui/JadeSquareBoardFrame.java").read_text(encoding="utf-8")
        self.assertIn("protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)", square)
        self.assertIn("MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)", square)
        self.assertNotIn('android:layout_height="0dp"\n                android:layout_weight="1"\n                android:layout_marginStart="9dp"', (ROOT / "app/src/main/res/layout/activity_main.xml").read_text(encoding="utf-8"))
        board = (ROOT / "app/src/main/java/com/badukai/ui/GoBoardView.java").read_text(encoding="utf-8")
        self.assertIn("drawCoordinates(canvas, n);", board)
        self.assertIn("drawCircle(cx, cy, radius * 1.04f, paint)", board)
        self.assertIn("cellSize * 0.57f", board)
        self.assertIn("holder.setGravity(moves.isEmpty()", self.main_java)
        captions = {view.get(ANDROID + "text") for view in scroll.iter()
                    if view.tag == "TextView"}
        for caption in ("棋盘大小", "对弈模式", "AI难度", "本地推理"):
            self.assertIn(caption, captions)
        # Bottom navigation is deliberately outside the ScrollView.
        game = self.ids["gamePageContainer"]
        outer = [v for v in game if v.tag == "LinearLayout"
                 and v.get(ANDROID + "layout_height") == "match_parent"]
        self.assertEqual(1, len(outer))
        self.assertIn(scroll, list(outer[0]))
        self.assertIn(self.ids["gameNavHome"], list(outer[0])[-1].iter())

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

    def test_new_game_uses_all_original_png_icons(self):
        import json
        import struct
        dialog = ET.parse(ROOT / "app/src/main/res/layout/dialog_new_game.xml").getroot()
        manifest_path = ROOT / "docs/ui/icons/badukai_jade_icons_22/manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        self.assertEqual(22, len(manifest))
        names = [row["name"] for row in manifest]
        self.assertEqual(22, len(set(names)))
        used_icons = set()
        for view in dialog.iter():
            for attr in ("src", "drawableStart", "drawableTop"):
                value = view.get(ANDROID + attr, "")
                if value.startswith("@drawable/ic_"):
                    used_icons.add(value[len("@drawable/"):])
        self.assertEqual(set(names) - {"ic_confirm"}, used_icons)
        self.assertNotIn("ic_confirm", used_icons)
        png_dir = ROOT / "app/src/main/res/drawable-nodpi"
        for row in manifest:
            name = row["name"]
            with self.subTest(png=name):
                path = png_dir / (name + ".png")
                self.assertTrue(path.is_file(), msg=str(path) + " missing")
                with path.open("rb") as file:
                    header = file.read(26)
                self.assertTrue(header.startswith(bytes.fromhex("89504e470d0a1a0a")))
                self.assertEqual((512, 512), struct.unpack(">II", header[16:24]))
                self.assertEqual([512, 512], row["size"])
        self.assertFalse(any("jade_ic_" in (view.get(ANDROID + "src", "")
                + view.get(ANDROID + "drawableStart", "")
                + view.get(ANDROID + "drawableTop", ""))
                for view in dialog.iter()))

    def test_original_png_icons_have_bounded_display_sizes(self):
        dialog = ET.parse(ROOT / "app/src/main/res/layout/dialog_new_game.xml").getroot()
        controls = {}
        for view in dialog.iter():
            name = view.get(ANDROID + "id", "")
            if name.startswith("@+id/"):
                controls[name[5:]] = view
        expected = {
            "closeDialogButton": ("src", "back"),
            "dialogSettingsButton": ("src", "settings_round"),
            "modeAiButton": ("drawableTop", "mode_ai"),
            "modeLocalButton": ("drawableTop", "mode_human"),
            "modeReplayButton": ("drawableTop", "import_sgf"),
            "blackRadio": ("drawableTop", "black_stone"),
            "whiteRadio": ("drawableTop", "white_stone"),
            "randomRadio": ("drawableTop", "random"),
            "backendCpuButton": ("drawableTop", "cpu"),
            "backendGpuButton": ("drawableTop", "gpu"),
            "backendNpuButton": ("drawableTop", "npu"),
            "newGameHomeTab": ("drawableTop", "home"),
            "newGameRecordTab": ("drawableTop", "records"),
            "newGamePlayTab": ("drawableTop", "game_tab"),
            "newGameTrainingTab": ("drawableTop", "training"),
            "newGameSettingsTab": ("drawableTop", "settings_tab"),
        }
        for control, (attr, icon) in expected.items():
            with self.subTest(control=control):
                self.assertEqual("@drawable/ic_" + icon,
                                 controls[control].get(ANDROID + attr))

        sections = {
            "jadeSectionBoardSize": "board_size",
            "jadeSectionMode": "player",
            "jadeSectionRank": "ai_level",
            "jadeSectionColor": "turn",
            "jadeSectionBackend": "backend",
            "jadeSectionRules": "turn",
        }
        for control, icon in sections.items():
            with self.subTest(section=control):
                self.assertEqual("@drawable/ic_" + icon,
                                 controls[control].get(ANDROID + "drawableStart"))

        binder = (ROOT / "app/src/main/java/com/badukai/ui/JadeIconBinder.java").read_text(
                encoding="utf-8")
        self.assertIn("setBounds(0, 0, pixels, pixels)", binder)
        self.assertIn("setCompoundDrawablesRelative", binder)
        for control in expected:
            if control not in ("closeDialogButton", "dialogSettingsButton"):
                self.assertIn("R.id." + control, binder)
        for control in sections:
            self.assertIn("R.id." + control, binder)
        self.assertIn("JadeIconBinder.bindNewGame(content)", self.main_java)
        self.assertIn("JADE_NEW_GAME_ICONS_V5", self.main_java)
        self.assertNotIn('android:drawableTint="#F5D990"',
                (ROOT / "app/src/main/res/layout/dialog_new_game.xml").read_text(encoding="utf-8"))

    def test_all_selector_icons_are_lowered_consistently(self):
        """The mode/color/backend icon rows share the same vertical alignment."""
        binder = (ROOT / "app/src/main/java/com/badukai/ui/JadeIconBinder.java"
                  ).read_text(encoding="utf-8")
        dialog = ET.parse(ROOT / "app/src/main/res/layout/dialog_new_game.xml").getroot()
        controls = {}
        for view in dialog.iter():
            value = view.get(ANDROID + "id", "")
            if value.startswith("@+id/"):
                controls[value[5:]] = view

        card_icons = {
            "modeAiButton": ("mode_ai", 38),
            "modeLocalButton": ("mode_human", 38),
            "modeReplayButton": ("import_sgf", 38),
            "blackRadio": ("black_stone", 34),
            "whiteRadio": ("white_stone", 34),
            "randomRadio": ("random", 34),
            "backendCpuButton": ("cpu", 38),
            "backendGpuButton": ("gpu", 38),
            "backendNpuButton": ("npu", 38),
        }
        for name, (resource, size) in card_icons.items():
            with self.subTest(card=name):
                self.assertIn(
                    "topLowered(root, R.id." + name
                    + ", R.drawable.ic_" + resource
                    + ", " + str(size) + ", 8);", binder)
                self.assertEqual("74dp", controls[name].get(ANDROID + "layout_height"))

        nav_icons = {
            "newGameHomeTab": "home",
            "newGameRecordTab": "records",
            "newGamePlayTab": "game_tab",
            "newGameTrainingTab": "training",
            "newGameSettingsTab": "settings_tab",
        }
        for name, resource in nav_icons.items():
            with self.subTest(nav=name):
                self.assertIn(
                    "topLowered(root, R.id." + name
                    + ", R.drawable.ic_" + resource + ", 29, 4);", binder)

        self.assertIn("import android.graphics.drawable.InsetDrawable;", binder)
        self.assertIn("new InsetDrawable(art, 0, offset, 0, 0)", binder)
        self.assertIn("lowered.setBounds(0, 0, pixels, pixels + offset)", binder)
        self.assertNotIn("R.id.startGameButton", binder)
        self.assertNotIn("R.drawable.ic_confirm", binder)
        self.assertIsNone(controls["startGameButton"].get(ANDROID + "drawableStart"))
        self.assertIsNone(controls["startGameButton"].get(ANDROID + "drawablePadding"))

    def test_jade_game_page_is_real_and_keeps_existing_handlers(self):
        game_root = self.ids["gamePageContainer"]
        self.assertEqual("FrameLayout", game_root.tag)
        self.assertIn("gameBackdrop", self.ids)
        self.assertIn("gameTimelineMoves", self.ids)
        self.assertIn("gameTimelineScroll", self.ids)
        self.assertIn("gameMoreButton", self.ids)
        self.assertIn("gameAnalysisButton", self.ids)
        self.assertEqual("HorizontalScrollView", self.ids["gameTimelineScroll"].tag)
        self.assertIn("JADE_GAME_V1", self.main_java)
        self.assertIn("JadeIconBinder.bindGame(gamePageContainer)", self.main_java)
        self.assertIn("updateGameTimeline()", self.main_java)
        self.assertIn("getMoveHistorySnapshot()", self.main_java)
        self.assertIn("showGameMoreMenu(View anchor)", self.main_java)
        self.assertIn("showWinRateChart()", self.main_java)
        self.assertIn("showEndgameScoringOptions()", self.main_java)
        for name in ("gameNavHome", "gameNavRecords", "gameNavPlay",
                     "gameNavTraining", "gameNavSettings",
                     "gameTimelineBack", "gameTimelineNext"):
            with self.subTest(action=name):
                self.assertIn(name, self.ids)
                self.assertIn("R.id." + name, self.main_java)
        for name in ("aiStoneView", "playerStoneView", "aiCaptureText",
                     "playerCaptureText", "aiWinRateText", "playerWinRateText",
                     "aiMoveTimeText", "nnAreaScoreText", "aiDifficultyText",
                     "statusText", "winRateProgress"):
            with self.subTest(data=name):
                self.assertIn(name, self.ids)
                self.assertIn("R.id." + name, self.main_java)

    def test_game_icons_and_drawables_are_present(self):
        png = ROOT / "app/src/main/res/drawable-nodpi"
        paths = set()
        for view in self.ids["gamePageContainer"].iter():
            for prop in ("background", "drawableStart", "drawableTop", "src"):
                item = view.get(ANDROID + prop, "")
                if item.startswith("@drawable/"):
                    name = item.split("/", 1)[1]
                    if name.startswith("btn_round_") or name.startswith("ic_"):
                        paths.add(name)
                        self.assertTrue((png / (name + ".png")).is_file(),
                                        msg="Missing gameplay PNG: " + name)
                    if name.startswith("jade_"):
                        self.assertTrue((DRAWABLES / (name + ".xml")).is_file(),
                                        msg="Missing gameplay style: " + name)
        self.assertGreaterEqual(len(paths), 15)
        binder = (ROOT / "app/src/main/java/com/badukai/ui/JadeIconBinder.java"
                  ).read_text(encoding="utf-8")
        self.assertIn("public static void bindGame(View root)", binder)
        for view in ("passButton", "undoButton", "situationButton",
                     "aiSuggestionButton", "gameAnalysisButton", "gameNavHome"):
            self.assertIn("R.id." + view, binder)
        for view in self.ids["gamePageContainer"].iter():
            self.assertIsNotNone(view.get(ANDROID + "layout_width"))
            self.assertIsNotNone(view.get(ANDROID + "layout_height"))

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
