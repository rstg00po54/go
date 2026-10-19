# Jade gameplay UI (initial native integration)

The active game page in `activity_main.xml` uses the uploaded original
25-icon pack from commit `da3d901`. This is a functional UI pass, not a
pixel-for-pixel reproduction of the 16_人机对弈 mockup.

## Live controls preserved

- `GoBoardView` is the original interactive board. No model, inference,
  GTP, rules, touch coordinate or capture code was modified.
- The title and four-metric strip are live: board size, mode, Human SL level,
  requested CPU/GPU/NPU backend.
- The dark statistics cards render the current capture totals, current
  move number, NPU/CPU win rates and measured AI move time. The existing
  win-rate progress bar still opens the real chart when tapped.
- The five decorated jade buttons invoke actual handlers: pass, undo,
  situation analysis, AI suggestions, win-rate chart.
- A compact **更多** popup retains four existing features instead of deleting
  them: new game, resign, intelligent referee, variation preview.
- The move-history strip renders real `board.getMoveHistorySnapshot()`
  items; left/right arrows horizontally scroll up to the latest 160 moves.
  It **does not** change the game board or pretend to be SGF replay.
- Home, records, training and setup navigation uses existing handlers.
- Decorative bamboo art is loaded from `assets/home/background.png`, which
  is the pre-existing home artwork; the source PNG is not modified.
- Images in the `drawable-nodpi` directory are fitted to 19-26dp compound
  drawables in `JadeIconBinder.bindGame()`, with native round buttons.

### Future visual refinements

The supplied pack contains icons, not the high-resolution wooden frame or
jade plaque texture seen in the mockup. Those surfaces use rounded-gradient
Android drawables in this pass. The board grid is still drawn by
`GoBoardView`, preserving correct 9/11/13/15/19-line geometry.

### Smoke-check

```bash
cd GoEngine-java
python3 tools/test_jade_ui_layout.py
REQUIRE_RKNN_FIVE_SIZES=1 ALLOW_4K_RKNN_RUNTIME=1 bash build.sh --apk-only
adb logcat -d | grep JADE_GAME_V1
```

Device testing is required. No APK or RK3588 runtime verification occurred
during the GitHub-side change.
