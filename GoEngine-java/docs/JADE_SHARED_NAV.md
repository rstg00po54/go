# Shared Jade navigation — original artwork restored

The same `JadeBottomNavView` remains responsible for all footer tab clicks,
with fixed order **首页 / 对弈 / 棋谱 / 训练 / 设置**. The public tab API and
all page-routing handlers remain intact.

## Visual appearance

The native gradient remake was removed. The footer now draws the ORIGINAL
`GoEngine-java/docs/navbar/bar.png` art, unmodified:

- Hand-painted bamboo leaves and clouds
- Gold filigree border and original translucent jade textures
- Original icon glyphs, Chinese typography and glowing gold Home treatment

Gradle already copies `docs/navbar/*.png` into the APK assets path
`home/navbar/` via `stageGreenHomeArtwork`. The Java view loads the
original `bar.png` (2172×724), trims only its large transparent margins,
then displays it with `FIT_CENTER` inside a 96dp footer. Never use
`FIT_XY`: that flattened the illustrated bar. Five equal transparent
native touch targets sit over the original. No redrawn text or icons.

The source graphic has the glowing Home icon and gold underline
*baked into the pixels*. For other tabs, an in-memory copy feathers a
clone of unmarked nearby jade background across only that fixed Home
underline, while softly reducing Home's bloom. The real tab gains its
gold underline independently. Home itself uses the unmodified source.
No source PNG assets are rewritten; the transformed image is only cached
at runtime for non-Home tabs.

Hosts: `activity_main.xml` (home and game), `dialog_new_game.xml`,
`activity_replay.xml`, and `activity_tsumego.xml`.

## Navigation and verification

Tab behavior stays centralized in `JadeNavigation` and host callbacks.
MainActivity's live SGF snapshot actions and KataGo CPU/GPU/NPU engine are
unchanged. Settings remains a placeholder notification.

```bash
python3 tools/test_jade_nav_unification.py -v
python3 tools/test_jade_ui_layout.py -v
python3 tools/test_jade_replay_ui.py -v
python3 tools/test_jade_five_pages.py -v
./gradlew :app:compileDebugJavaWithJavac -PskipKataGoNative=true --no-daemon
```

Android compilation is validated in `.github/workflows/jade_five_ui_batch.yml`.
Actual RK3588 display accuracy should be checked by installing the APK.
