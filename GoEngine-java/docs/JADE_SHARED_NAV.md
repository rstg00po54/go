# One Jade Bottom Navigation for all main screens

## Why it changed

The homepage used a single baked `home/navbar/bar.png` screenshot with
invisible click hotspots, while the game, replay and training pages each drew
five separate `TextView` icons. Their icon assets, selection styles and
order differed. The original screenshot also permanently highlighted Home,
so copying that entire image to other screens would give two selected tabs.

`JadeBottomNavView` is now the only live navbar component. It draws a
translucent emerald capsule with a gold outline, shared five jade/ivory
icons, a shared text size, and a **dynamic gold selected underline**.

**One fixed order: 首页 → 对弈 → 棋谱 → 训练 → 设置**.

Its art is native stateful UI, not an unclickable full-screen mockup.

## Hosts

- `activity_main.xml`: `homeNavBarArtwork` (Home selected),
  `gameSharedNav` (Play selected).
- `dialog_new_game.xml`: `newGameSharedNav` (Play selected).
- `activity_replay.xml`: `replaySharedNav` (Records selected).
- `activity_tsumego.xml`: `tsumegoSharedNav` (Training selected).

The details pages (SGF archive, tsumego explanation/results) remain focused
subpages and keep their Back/Return buttons rather than duplicate a tab bar.

Home hero/background/buttons, live game board, NPU backend, SGF parser and
puzzle rules are not changed.

## Click actions

Navigation to Home or Play from a subpage returns to the same MainActivity
instance with `CLEAR_TOP | SINGLE_TOP` to preserve the live engine.

Records and Training use existing replay and tsumego activities. MainActivity
uses its original `openReplay()` and `openTsumego()` handlers to preserve
the SGF snapshot-on-navigation behavior.

**Settings** does not yet have its own page; all bottom bars consistently
show the same "设置页面开发中" notice. Existing contextual settings buttons
inside the pages retain their own behavior.

The old `docs/navbar/bar.png` and art are retained for reference, but the
fixed-selected PNG is no longer mounted as an interactive navbar.

## Checks

```bash
cd GoEngine-java
python3 tools/test_jade_nav_unification.py -v
python3 tools/test_jade_ui_layout.py -v
python3 tools/test_jade_replay_ui.py -v
python3 tools/test_jade_five_pages.py -v
./gradlew :app:compileDebugJavaWithJavac -PskipKataGoNative=true --no-daemon
```

The GitHub workflow `.github/workflows/jade_five_ui_batch.yml` runs these
checks and Android Java compilation. A full RK3588 APK/device verification is
a separate step.
