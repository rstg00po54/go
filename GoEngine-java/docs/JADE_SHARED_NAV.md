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
`home/navbar/` via `stageGreenHomeArtwork`. The Java view reads
`home/navbar/bar.png`, caches the decoded bitmap and stretches it to
its 70dp-tall host. Five equal, transparent native touch targets sit above
the art. There are no duplicate redrawn icons or labels.

The source graphic has Home *baked as its selected tab*. When another
page is active the view dims the original Home portion with a translucent
emerald overlay and adds a small gold active-tab underline. This is a
first-pass compromise so all pages can immediately use the exact original
art without fabricating new PNGs; the original image itself is not edited.
When Home is selected, the overlay is absent so the artwork is shown intact.

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
