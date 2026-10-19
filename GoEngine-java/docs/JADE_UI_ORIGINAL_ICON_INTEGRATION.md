# Jade new-game icon integration — original 22 PNG assets

Source: `docs/ui/icons/badukai_jade_icons_22/manifest.json` and
`app/src/main/res/drawable-nodpi/ic_*.png`, uploaded in commit `bc0c9f0`.

The `dialog_new_game.xml` screen uses all 22 originals. Each icon retains
the emerald/gold lighting and transparent pixels from the image pack, rather
than the earlier flat `jade_ic_*.xml` vector approximations.

| UI area | Resource |
| --- | --- |
| Top bar | `ic_back`, `ic_settings_round` |
| Section titles | `ic_board_size`, `ic_player`, `ic_ai_level`, `ic_turn`, `ic_backend` |
| Game mode cards | `ic_mode_ai`, `ic_mode_human`, `ic_import_sgf` |
| Player color cards | `ic_black_stone`, `ic_white_stone`, `ic_random` |
| NPU selection cards | `ic_cpu`, `ic_gpu`, `ic_npu` |
| Start match | `ic_confirm` |
| Bottom navigation | `ic_home`, `ic_records`, `ic_game_tab`, `ic_training`, `ic_settings_tab` |

**Important Android detail:** each PNG is 512x512 in `drawable-nodpi`.
Using these directly as compound drawables gives a huge intrinsic size,
so `JadeIconBinder.bindNewGame(content)` assigns explicit bounds in dp
before the dialog is shown: 26dp on section headings, 34–38dp on
selection cards, 29dp on the bottom navigation, and 30dp on the launch
button. Header icon views use explicit sizes directly. Do not tint the
PNG artwork.

The original `jade_ic_*.xml` files are left untouched as rollback
assets and for any other screens that might still refer to them.

Static regression checks:

```bash
cd GoEngine-java
python3 tools/test_jade_ui_layout.py
```

Device version marker (after opening New Game):

```bash
adb logcat -d -v time | grep JADE_NEW_GAME_ICONS_V5
```

APK rebuild (RK3588 with prebuilt NN assets, 4096-byte Android pages):

```bash
REQUIRE_RKNN_FIVE_SIZES=1 ALLOW_4K_RKNN_RUNTIME=1 bash build.sh --apk-only
```

No engine or native AI inference code is changed.
