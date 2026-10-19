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
        self.assertIn("gameMoreButton", self.ids)
        self.assertIn("gameAnalysisButton", self.ids)
        self.assertIn("JADE_GAME_V1", self.main_java)
        self.assertIn("JadeIconBinder.bindGame(gamePageContainer)", self.main_java)
        self.assertIn("getMoveHistorySnapshot()", self.main_java)
        self.assertIn("showGameMoreMenu(View anchor)", self.main_java)
        self.assertIn("showWinRateChart()", self.main_java)
        self.assertIn("showEndgameScoringOptions()", self.main_java)
        for name in ("gameNavHome", "gameNavRecords", "gameNavPlay",
                     "gameNavTraining", "gameNavSettings"):
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

    def test_game_timeline_is_removed_without_losing_history_support(self):
        for view_id in ("gameTimelineBack", "gameTimelineNext",
                        "gameTimelineScroll", "gameTimelineMoves"):
            with self.subTest(removed=view_id):
                self.assertNotIn(view_id, self.ids)
                self.assertNotIn("R.id." + view_id, self.main_java)
        self.assertNotIn("updateGameTimeline()", self.main_java)
        self.assertNotIn("timelineRenderedBoard", self.main_java)
        self.assertNotIn("棋局开始后显示落子记录",
                         (ROOT / "app/src/main/res/layout/activity_main.xml")
                         .read_text(encoding="utf-8"))
        # Real SGF and replay history are independent of this decorative bar.
        self.assertIn("getMoveHistorySnapshot()", self.main_java)
        self.assertIn("openReplay()", self.main_java)
        self.assertIn("saveReplaySnapshot(", self.main_java)
        self.assertIn("boardFrame", self.ids)
        self.assertIn("gameActionPanel", self.ids)
        self.assertIn("gameNavHome", self.ids)

    def test_four_game_facts_use_same_icon_and_two_line_text_columns(self):
        # Regression for RK3588: previous horizontal value rows were
        # individually centered and captions independently centered, so their
        # x-positions drifted by the width of the translated label.
        parent = {child: node for node in self.root.iter() for child in node}
        facts = (
            ("gameTitleText", "ic_board_grid_gold", "棋盘大小"),
            ("gameModeSummaryText", "ic_robot_ivory", "对弈模式"),
            ("gameRankSummaryText", "ic_difficulty_gold", "AI难度"),
            ("gameBackendSummaryText", "ic_npu_ivory", "本地推理"),
        )
        summary = None
        for name, icon, caption in facts:
            with self.subTest(fact=name):
                value = self.ids[name]
                self.assertIsNone(value.get(ANDROID + "drawableStart"))
                self.assertEqual("match_parent", value.get(ANDROID + "layout_width"))
                self.assertEqual("21dp", value.get(ANDROID + "layout_height"))
                self.assertEqual("start|center_vertical", value.get(ANDROID + "gravity"))
                text_column = parent[value]
                self.assertEqual("LinearLayout", text_column.tag)
                self.assertEqual("vertical", text_column.get(ANDROID + "orientation"))
                self.assertEqual("0dp", text_column.get(ANDROID + "layout_width"))
                self.assertEqual("1", text_column.get(ANDROID + "layout_weight"))
                title_view, caption_view = list(text_column)
                self.assertIs(title_view, value)
                self.assertEqual(caption, caption_view.get(ANDROID + "text"))
                self.assertEqual("match_parent", caption_view.get(ANDROID + "layout_width"))
                self.assertEqual("start|center_vertical",
                                 caption_view.get(ANDROID + "gravity"))
                self.assertEqual("16dp", caption_view.get(ANDROID + "layout_height"))
                cell = parent[text_column]
                self.assertEqual("horizontal", cell.get(ANDROID + "orientation"))
                self.assertEqual("0dp", cell.get(ANDROID + "layout_width"))
                self.assertEqual("1", cell.get(ANDROID + "layout_weight"))
                icon_view, actual_text_col = list(cell)
                self.assertIs(actual_text_col, text_column)
                self.assertEqual("ImageView", icon_view.tag)
                self.assertEqual("@drawable/" + icon, icon_view.get(ANDROID + "src"))
                self.assertEqual("34dp", icon_view.get(ANDROID + "layout_width"))
                self.assertEqual("34dp", icon_view.get(ANDROID + "layout_height"))
                self.assertEqual("3dp", icon_view.get(ANDROID + "layout_marginEnd"))
                self.assertEqual("center_vertical", icon_view.get(ANDROID + "layout_gravity"))
                self.assertIsNone(icon_view.get(ANDROID + "layout_marginTop"))
                self.assertEqual("11sp", value.get(ANDROID + "textSize"))
                self.assertEqual("1dp", cell.get(ANDROID + "paddingStart"))
                self.assertEqual("0dp", cell.get(ANDROID + "paddingEnd"))
                if summary is None:
                    summary = parent[cell]
                self.assertIs(summary, parent[cell])
        self.assertEqual("62dp", summary.get(ANDROID + "layout_height"))
        self.assertEqual(7, len(summary))  # 4 cells + 3 thin dividers
        for separator in list(summary)[1::2]:
            self.assertEqual("View", separator.tag)
            self.assertEqual("1dp", separator.get(ANDROID + "layout_width"))
        binder = (ROOT / "app/src/main/java/com/badukai/ui/JadeIconBinder.java"
                  ).read_text(encoding="utf-8")
        for name, _, _ in facts:
            self.assertNotIn("R.id." + name, binder)

    def test_compact_game_header_and_statistics_heights(self):
        game = self.ids["gamePageContainer"]
        parents = {child: parent for parent in self.root.iter() for child in parent}
        title = self.ids["backButton"]
        title_bar = parents[title]
        self.assertEqual("52dp", title_bar.get(ANDROID + "layout_height"))
        self.assertEqual("38dp", title.get(ANDROID + "layout_height"))
        self.assertEqual("38dp", self.ids["newGameButton"].get(ANDROID + "layout_height"))
        title_plaque = list(title_bar)[1]
        self.assertEqual("46dp", title_plaque.get(ANDROID + "layout_height"))

        summary = parents[parents[parents[self.ids["gameTitleText"]]]]
        self.assertEqual("62dp", summary.get(ANDROID + "layout_height"))
        self.assertEqual("3dp", summary.get(ANDROID + "layout_marginTop"))
        self.assertEqual("4dp", summary.get(ANDROID + "layout_marginBottom"))

        stats = self.ids["gameStatsPanel"]
        self.assertEqual("4dp", stats.get(ANDROID + "padding"))
        self.assertEqual("5dp", stats.get(ANDROID + "layout_marginBottom"))
        row = list(stats)[0]
        self.assertEqual("60dp", row.get(ANDROID + "layout_height"))
        self.assertEqual("23dp", list(stats)[2].get(ANDROID + "layout_height"))
        self.assertEqual("25dp", self.ids["aiStoneView"].get(ANDROID + "layout_width"))
        self.assertEqual("25dp", self.ids["playerStoneView"].get(ANDROID + "layout_width"))
        # Keep exactly the requested order above the live board.
        siblings = list(parents[stats])
        self.assertLess(siblings.index(stats), siblings.index(self.ids["boardFrame"]))

    def test_capture_winrate_panel_is_above_board_not_below(self):
        # Requested RK3588 layout order:
        # four game facts -> captures/win rate -> board -> six action buttons.
        board = self.ids["boardFrame"]
        stats = self.ids["gameStatsPanel"]
        actions = self.ids["gameActionPanel"]
        parent = {child: node for node in self.root.iter() for child in node}
        self.assertIs(parent[stats], parent[board])
        self.assertIs(parent[board], parent[actions])
        children = list(parent[board])
        self.assertLess(children.index(stats), children.index(board))
        self.assertLess(children.index(board), children.index(actions))
        self.assertEqual(1, children.count(stats))
        # The actual playable board view must still be inside its frame.
        self.assertIn(self.ids["boardView"], list(board))
        # The banner remains within the scrollable game content.
        scroll = self.ids["gameContentScroll"]
        self.assertIn(stats, list(scroll.iter()))

    def test_game_stats_capture_winrate_capture_order(self):
        # Screen: stone/captures | live win rate | stone/captures.
        parent = {child: node for node in self.root.iter() for child in node}
        panel = self.ids["gameStatsPanel"]
        self.assertEqual("vertical", panel.get(ANDROID + "orientation"))
        self.assertEqual("wrap_content", panel.get(ANDROID + "layout_height"))
        line = list(panel)[0]
        self.assertEqual("60dp", line.get(ANDROID + "layout_height"))
        self.assertEqual("horizontal", line.get(ANDROID + "orientation"))
        self.assertEqual(5, len(line))  # three columns + two separators
        left, sep1, center, sep2, right = list(line)
        self.assertEqual("1", left.get(ANDROID + "layout_weight"))
        self.assertEqual("1.42", center.get(ANDROID + "layout_weight"))
        self.assertEqual("1", right.get(ANDROID + "layout_weight"))
        self.assertEqual("1dp", sep1.get(ANDROID + "layout_width"))
        self.assertEqual("1dp", sep2.get(ANDROID + "layout_width"))
        self.assertIs(parent[self.ids["aiStoneView"]], left)
        self.assertIs(parent[self.ids["aiCaptureText"]], left)
        self.assertIs(parent[self.ids["playerStoneView"]], right)
        self.assertIs(parent[self.ids["playerCaptureText"]], right)
        for name in ("playerWinRateText", "aiWinRateText", "winRateProgress"):
            with self.subTest(winrate=name):
                self.assertIs(parent[self.ids[name]], center)
        self.assertEqual("center", self.ids["playerWinRateText"].get(ANDROID + "gravity"))
        self.assertEqual("center", self.ids["aiWinRateText"].get(ANDROID + "gravity"))
        self.assertEqual("6dp", self.ids["winRateProgress"].get(ANDROID + "layout_height"))
        self.assertEqual("@drawable/jade_winrate_progress",
                         self.ids["winRateProgress"].get(ANDROID + "progressDrawable"))
        self.assertEqual("horizontal", list(panel)[2].get(ANDROID + "orientation"))
        for name in ("gameMoveCountText", "statusText", "aiMoveTimeText"):
            self.assertIs(parent[self.ids[name]], list(panel)[2])
        self.assertIs(parent[self.ids["nnAreaScoreText"]], panel)
        self.assertIn("playerCaptureText.setText(", self.main_java)
        self.assertIn("aiCaptureText.setText(", self.main_java)
        self.assertIn("updateWinRateLabels()", self.main_java)

    def test_game_action_buttons_are_two_equal_rows_of_three(self):
        panel = self.ids["gameActionPanel"]
        self.assertEqual("96dp", panel.get(ANDROID + "layout_height"))
        rows = list(panel)
        self.assertEqual(2, len(rows))
        expected = (("passButton", "undoButton", "situationButton"),
                    ("aiSuggestionButton", "gameAnalysisButton", "gameMoreButton"))
        for row, names in zip(rows, expected):
            with self.subTest(row=names):
                self.assertEqual("horizontal", row.get(ANDROID + "orientation"))
                self.assertEqual("48dp", row.get(ANDROID + "layout_height"))
                self.assertEqual(3, len(row))
                for button, name in zip(row, names):
                    self.assertIs(button, self.ids[name])
                    self.assertEqual("Button", button.tag)
                    self.assertEqual("0dp", button.get(ANDROID + "layout_width"))
                    self.assertEqual("1", button.get(ANDROID + "layout_weight"))
                    self.assertEqual("42dp", button.get(ANDROID + "layout_height"))
                    self.assertEqual("3dp", button.get(ANDROID + "layout_margin"))
                    self.assertEqual("center", button.get(ANDROID + "gravity"))
                    self.assertEqual("12sp", button.get(ANDROID + "textSize"))
                    self.assertEqual("@drawable/jade_game_white_button",
                                     button.get(ANDROID + "background"))
        self.assertIn("showGameMoreMenu", self.main_java)
        self.assertIn("showAiSuggestions()", self.main_java)

    def test_game_action_icons_are_inline_and_centered_with_text(self):
        # A Button's drawableStart is anchored to the left edge regardless of
        # gravity=center. ImageSpan joins the PNG and label in the same
        # centered text layout, for short and long button names alike.
        binder = (ROOT / "app/src/main/java/com/badukai/ui/JadeIconBinder.java"
                  ).read_text(encoding="utf-8")
        expected = {
            "passButton": "ic_pass_jade",
            "undoButton": "ic_undo_jade",
            "situationButton": "ic_territory_jade",
            "aiSuggestionButton": "ic_hint_gold",
            "gameAnalysisButton": "ic_analysis_jade",
        }
        for button_id, drawable in expected.items():
            with self.subTest(button=button_id):
                self.assertIsNone(self.ids[button_id].get(ANDROID + "drawableStart"))
                self.assertIsNone(self.ids[button_id].get(ANDROID + "drawablePadding"))
                self.assertIn(
                    "centeredAction(root, R.id." + button_id
                    + ", R.drawable." + drawable + ", 21);", binder)
                self.assertEqual("center", self.ids[button_id].get(ANDROID + "gravity"))
        self.assertIsNone(self.ids["gameMoreButton"].get(ANDROID + "drawableStart"))
        self.assertIn("new ImageSpan(icon, ImageSpan.ALIGN_CENTER)", binder)
        self.assertIn("target.setCompoundDrawablesRelative(null, null, null, null)", binder)
        self.assertIn("target.setText(label, TextView.BufferType.SPANNABLE)", binder)
        self.assertIn("target.setGravity(Gravity.CENTER)", binder)
        self.assertIn("Spanned.SPAN_EXCLUSIVE_EXCLUSIVE", binder)

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
        # Five gameplay button icons are intentionally referenced from Java as
        # centered inline ImageSpans, not XML drawableStart (which pins left).
        binder = (ROOT / "app/src/main/java/com/badukai/ui/JadeIconBinder.java"
                  ).read_text(encoding="utf-8")
        for name in ("ic_pass_jade", "ic_undo_jade", "ic_territory_jade",
                     "ic_hint_gold", "ic_analysis_jade"):
            self.assertIn("R.drawable." + name, binder)
            paths.add(name)
            self.assertTrue((png / (name + ".png")).is_file(),
                            msg="Missing inline gameplay PNG: " + name)
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
