Badukai Java/XML migration
==========================

This tree replaces the Kotlin + Jetpack Compose UI/game wrapper with Java + Android XML.
The native KataGo binary remains native C/C++ and is launched exactly as before.

Converted to Java:
- BadukaiApplication
- MainActivity
- KataGoEngine GTP wrapper
- Go board/game logic
- Custom GoBoardView

XML layouts:
- app/src/main/res/layout/activity_main.xml
- app/src/main/res/layout/dialog_new_game.xml

Supported board sizes in the new-game dialog:
9 / 11 / 13 / 15 / 19

Important:
The source package intentionally does not contain the large model files or jniLibs that were excluded from the uploaded source archive.
Keep your existing app/src/main/assets/models and app/src/main/jniLibs directories when applying this version to your working tree.
