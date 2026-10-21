# 5 Jade UI pages, first functional pass

Built together on `feature/jade-home-game-ui`, based on conceptual UI designs
**11 棋谱复盘, 15 我的棋谱, 04 死活训练, 19 死活答案解析,
20 死活训练结果**.

No changes to `activity_main.xml` (existing game/home interface) or
`dialog_new_game.xml` (existing new-game interface).

| Page | Layout | Activity | What actually works |
|---|---|---|---|
| 棋谱复盘 | `activity_replay.xml` | `ReplayActivity` | Real board from SGF, scrubber, first/prev/next/last, import/export/share |
| 我的棋谱 | `activity_sgf_library.xml` | `SgfLibraryActivity` | Local SGF archive, formatted entries, import via Android document picker, select for replay |
| 死活训练 | `activity_tsumego.xml` | `TsumegoActivity` | All existing 8 offline capture puzzles, hints, reset, wrong-attempt checks, progress |
| 死活答案解析 | `activity_tsumego_explanation.xml` | `TsumegoExplanationActivity` | Read-only board **after correct move**, last-move highlight, solution reasoning, next lesson |
| 死活训练结果 | `activity_tsumego_results.xml` | `TsumegoResultsActivity` | Persisted per-puzzle completion, accurate percent, jump to any lesson, resume first incomplete |

## Navigation

- Existing home/game **棋谱** button → ReplayActivity → History → SgfLibraryActivity → returns validated SGF to the same replay screen.
- Existing **训练** button → TsumegoActivity → after correctly solving puzzle **答案解析**, or **训练结果** at any time; selecting a puzzle returns to training at that index.
- Home tabs in Replay and Training use `MainActivity.navigate_home` to show the existing home page **without resetting the active game process**.
- Replay can open Training; Training can open Replay.
- Empty SGF archive and empty replay game show informative empty states. Seeking past ends is disabled.

## Artwork and limitations

Uses original `ic_*_ivory`, `ic_*_gold`, `btn_round_back`,
jade gold/ivory shape drawables, forest scrim and wooden board frame.
No new generated PNGs required. Decorative blank space uses a consistent
color/gradient. The layouts approximate first-round proportions, not
pixel-for-pixel art reproductions.

Existing GoSgf and SgfArchive formats unchanged. Training uses the existing
8 validated 9×9 problems and their same `completed_mask`; no NPU / RKNN /
native build / KataGo inference code modified.

## Verification

The CI workflow `.github/workflows/jade_five_ui_batch.yml` runs:

```bash
python3 tools/test_jade_five_pages.py -v
python3 tools/test_jade_replay_ui.py -v
python3 tools/test_jade_ui_layout.py -v
./gradlew :app:compileDebugJavaWithJavac -PskipKataGoNative=true --no-daemon
```

This validates native layouts, resources, Java and navigation; it is **not**
an RK3588 full native-engine APK or device run. On the RK3588 checkout,
build the full playable APK with the known local cached JNI/RKNN artifacts:

```bash
git pull --ff-only origin feature/jade-home-game-ui
REQUIRE_RKNN_FIVE_SIZES=1 ALLOW_4K_RKNN_RUNTIME=1 bash build.sh --apk-only
adb -s 8719e18a71a2a66c install -r app/build/outputs/apk/debug/app-debug.apk
```
