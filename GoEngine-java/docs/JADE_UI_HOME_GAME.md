# Jade UI · home and game screen (phase 1)

Design references (kept unchanged in GitHub branch `docs/jade-ui-25-screens`):
- `GoEngine-java/docs/home/10_首页.png`: home hierarchy and jade art style.
- `GoEngine-java/docs/home/16_人机对弈.png`: game screen hierarchy and jade/wood palette.

This implementation is **native Android XML and Java**, not a single flattened UI screenshot.

## Scope

- Reuses the existing independently tappable home PNG artwork in
  `docs/home`, copied into APK assets by `stageGreenHomeArtwork`.
- Adds a gradient title scrim and an offline KataGo badge without replacing
  the home illustration, event handlers, navigation or model preload.
- Re-styles the game header, player/AI status card, action buttons and
  central wood-framed board, keeping the `GoBoardView` coordinate mapping.
- Adds a clickable black/white win-rate bar, connected to the existing
  history and victory probabilities, opening the original win-rate chart.
- Keeps every existing control ID, action handler, board size, AI backend,
  Human SL model and NPU/CPU/GPU inference path.
- Supports differing screen heights through a weighted board frame.
- Does not change the new-game dialog, replay or tsumego screens (later work).

## Quick host check

```bash
cd GoEngine-java
python3 tools/test_jade_ui_layout.py
```

## Build and try

Keep your existing built native libraries and downloaded model assets.

RK3588 (only when Android page size is 4096):

```bash
REQUIRE_RKNN_FIVE_SIZES=1 ALLOW_4K_RKNN_RUNTIME=1 bash build.sh --apk-only
```

vivo X300 Pro (MediaTek GTP JNI):

```bash
KATAGO_JOBS=2 bash tools/build_vivo_mtk_gtp.sh
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

To compare both screens, capture the home screen, start a match, capture the
game screen; test 9x9 and 19x19, AI and player colors reversed, and a tall
phone / 1080x1920 RK3588 portrait display. UI layout checks do not replace
an APK build or real-device interaction test.
