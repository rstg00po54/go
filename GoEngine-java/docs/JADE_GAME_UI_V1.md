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
- The unnecessary move-history strip and arrow controls have been removed.
  Move history remains in the game model and the SGF/replay flow.
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

## RK3588 board sizing fix (v2)

The first release used `boardFrame layout_height=0dp, layout_weight=1`
inside a vertical LinearLayout. On the 1080x1920 RK3588 display the
remaining height was much smaller than the board width; `GoBoardView`
measured a tiny square, leaving wide empty wooden margins.

The board frame is now a native `JadeSquareBoardFrame`, with its measured
height always equal to its available width. It is inside
`gameContentScroll`, so on shorter aspect-ratio screens the user can
scroll to the results and controls instead of losing the usable board.
The bottom five-tab bar stays fixed.

`GoBoardView` also draws letter/number grid coordinates and uses a gold
outline for the last stone, like the reference. The underlying intersection
positions, hit testing, game rules and CPU/GPU/NPU paths remain unchanged.

This is a first visual-correction pass, not a pixel-identical reproduction
of the concept art. Device screenshots are required to validate spacing.


## Gameplay controls and capture stats (v3)

Above the board, the statistics panel has a **single horizontal row**:
AI stone / live captures on the left, genuine dynamically updated NN
win-rate percentages and progress bar in the center, player's stone / live
captures on the right. A smaller footer shows current move, turn/status,
and measured per-move time, while optional area-score text remains available.
This keeps all the original data view IDs and click handlers.

Below it, the six actions are a strict **3 columns × 2 rows**, with equal
`layout_weight=1`, `42dp` button heights, matching margins and
jade/ivory backgrounds. The sixth `更多` tile uses the same geometry and
opens the existing menu. Buttons still use their original action IDs.

No game-rule or backend code was changed.

## Removal of decorative move strip (v4)

The horizontal strip that displayed "棋局开始后显示落子记录"
and its previous/next arrow buttons has been removed from
`activity_main.xml`. The matching `updateGameTimeline()` render
method and its click handlers have also been deleted from
`MainActivity.java`.

This only removes the redundant UI. Go move history, SGF
saving, replay, analysis and KataGo CPU/GPU/NPU processing are unchanged.
The game buttons now sit directly beneath the square Go board, with the
fixed five-tab navigation below the scrolling game content.
