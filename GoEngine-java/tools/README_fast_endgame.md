# Android KataGo 快速终局结算（2026-10-12）

## 为什么以前慢

原来 MainActivity.finishByScore() 必须串行等待 `final_score`、`final_status_list dead` 和 `kata-raw-nn 0`。
终局 `final_score` 会改变贴目、重复搜索胜率来估算最终目差。在 RK3588 的固定 9×9 SGF 测试中，CPU 约 5.6s、GPU 约 6.1s；死子判断约 2.2/2.4s。它并不是一次简单数棋盘交叉点操作。

## 新版交互（默认）

1. 双方两次 pass 后，立即开始 `final_status_list dead`，**不再自动调用 final_score**；
2. 使用 KataGo 判死结果 + Java 中国数子（移除死子后统计活子与空点、加贴目）得到 **预估目差**，并用 `kata-raw-nn 0` 显示棋盘归属率；
3. 显示 `对局结束 · 预估白胜 N.N目` 及 `黑约X目/白约Y目`，结束等待，允许直接开新一局；
4. 如需原来更耗时的搜索预测，请点击终局按钮 **「深度估算」**。此时才执行 `final_score`、显示它给出的搜索预测目差，并把结果与中国数子的值核对。

两种结果都不应被称为正式裁判确认的精确结果。`final_score` 对未明朗局面会预测后续棋局，因此它与基于死子移除的中国数子结果可能不同；以前固定棋谱测试中已有 3～11 目的差异。

## 构建与实机验证

在 GoEngine-java 目录：

```bash
git pull origin rk3588-engine
python3 tools/test_full_game.py
python3 tools/test_app_ui_units.py
bash build.sh --apk-only
adb -s 8719e18a71a2a66c install -r app/build/outputs/apk/debug/app-debug.apk
python3 tools/test_app_ui.py -s 8719e18a71a2a66c --backends gpu --size 9 --full-game --game-max-actions 110 --settlement-timeout 180
```

如果 CPU/GPU JNI 库尚不存在，不要用 `--apk-only`，改用项目原来的 `bash build.sh`。前述代码仓库改动没有修改 C++，所以使用已有 JNI 库即可。

最新整局 JSON 报告中 `scoring_mode=fast`，`time_to_first_result_ms` 是进入结算到首次展示结果；`final_score_ms=null` 表示默认路径确实没有走慢的胜负搜索。旧 APK 的报告会显示 `scoring_mode=full`，便于对比。

Android 日志也会记录：

```text
ENDGAME_TIMING phase=fast_done elapsedMs=... deadMs=... synced=true deadAvailable=true
ENDGAME_TIMING phase=precise_done elapsedMs=... valid=true
Chinese area score ... mode=fast/precise
```

上次单线程原生 GTP 基准中 GPU 死子判断约 2.4 秒，所以这次第一屏目标是约 2～3 秒，而不是原来的约 8 秒。**这只是基于已有数据的预计值，Java/JNI 真实 APP 必须重新安装并实测，不保证 19×19 有相同效果。**

注意：整局自动测试在快速结果出现后结束，深度估算需在游戏界面手动点击「深度估算」。由于这是有意改变默认计分顺序，旧的必须按 `final_score → dead → raw-nn` 排列的日志断言已经适配为同时支持新旧两种流程。
