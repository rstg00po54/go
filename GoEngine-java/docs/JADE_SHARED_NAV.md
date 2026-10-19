# Shared Jade navigation: bar0 background + ten aligned icon states

The shared `JadeBottomNavView` powers Home, Game, New Game, Replay and Training.

## Art source

- Background: `docs/navbar/bar0.png`, staged by `stageGreenHomeArtwork`
  as the APK asset `home/navbar/bar0.png`.
- Actual normal/selected images: `app/src/main/res/drawable-nodpi/jade_nav_*.png`
  (5 destinations x 2 states = 10 files).
- High-resolution master files and previews: `docs/navbar/states/`.

Each tab contains exactly one ImageView. Icons, captions and glow are baked
into the respective 256x256 PNG, so the app does not paint captions, draw an
underline, or copy pixels from other tabs. Both states share identical
ImageView bounds, proportions and alignment. The selected state changes only
the drawable resource, not the size, scale or text position.

`bar0.png` is an independent FIT_CENTER backdrop. The renderer may crop
transparent outer margins but never paints over the source background. The
old `bar.png` is no longer used by the shared footer.

## Interaction

Tab order remains 首页 / 对弈 / 棋谱 / 训练 / 设置.
Navigation continues to use `JadeNavigation` and the same host click
handlers. Engine, board, replay and training behavior is unchanged.

## Footer placement and feature entry

Home now anchors the shared footer at the same 96dp height, 9dp side
margins and 3dp bottom margin as Game, Replay, Training and New Game.
Home no longer positions its footer by a percentage of window height.

Records and Training footer taps present a feature choice dialog over
the *currently visible* page instead of launching immediately.
Records supports recent SGF replay, history and SGF import. Training
supports resuming, restarting or choosing a bundled life-and-death puzzle.
Cancel leaves the current game/replay/training page untouched.
After a selection the actual activity starts without a transition
animation, without routing through Home and without restarting KataGo.

## Verification

Run from `GoEngine-java`:

```bash
python3 tools/test_jade_nav_unification.py -v
python3 tools/test_jade_ui_layout.py -v
python3 tools/test_jade_replay_ui.py -v
python3 tools/test_jade_five_pages.py -v
./gradlew :app:compileDebugJavaWithJavac -PskipKataGoNative=true --no-daemon
```

Inspect the installed APK on the RK3588 and vivo for halo clipping or
position shifts; source assertions are not a substitute for device tests.
