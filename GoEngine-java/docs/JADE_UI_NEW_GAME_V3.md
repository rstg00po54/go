# Jade-style new-game page v3

**Design basis**: `docs/jade-ui-25-screens/GoEngine-java/docs/home/13_新建对局.png`
and the user-provided reference screenshot.

This revision replaces the old mint-colored form/popup entirely rather than
changing colors on `Spinner` widgets. It uses a real full-screen Android
layout with independently interactive controls:

- Five miniature wooden 9/11/13/15/19-line boards, drawn by
  `JadeBoardSizeView.java`; bright jade stroke indicates selection.
- Three game-mode cards. Human-vs-AI is fully supported. The human-vs-human
  card is visibly **disabled** until that backend exists, rather than
  starting an AI match under a false label. SGF/replay opens the existing
  ReplayActivity.
- Human SL rank slider from 1k (left) to 18k (right), live rank label.
- Black/white/random color buttons.
- CPU/GPU/NPU buttons, each enabled by **actual bundled hardware/backend**
  availability. Board switch rechecks its matching NPU model, and falls
  back to CPU visibly when NPU for that board size is unavailable.
- Unmodified handicap/rules selection via a slim selector below the backend.
- Bamboo-scene background, emerald scrim, dark jade sections, gold ornament,
  and fixed start and navigation controls. All controls are native views,
  not click targets over a baked screenshot.

The bundled existing `home/background.png` is used as the background image.
The original artwork is a bamboo-room illustration, rather than the exact
forest art in the design mockup; visual matching of background vegetation
can be refined later without touching interactions.

Device logs for confirming the correct version:

```bash
adb logcat -d -v time | grep -E 'JADE_NEW_GAME_DIALOG_V3|JADE_BOARD_SELECTED|JADE_NEW_GAME_START'
```

Host layout regression test:

```bash
python3 tools/test_jade_ui_layout.py
```

Build on 4-KB page-size RK3588 with cached RKNN assets:

```bash
REQUIRE_RKNN_FIVE_SIZES=1 ALLOW_4K_RKNN_RUNTIME=1 bash build.sh --apk-only
```

For vivo MediaTek full NPU build, instead use
`KATAGO_JOBS=2 bash tools/build_vivo_mtk_gtp.sh`. Do not combine Rockchip
native libraries with MediaTek.

Neither the Java game logic nor GTP NN inference has been changed. This
branch is for interface integration, and device UI testing remains required.
