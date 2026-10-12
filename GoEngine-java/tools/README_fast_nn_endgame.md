# Android 围棋：快速 NN 终局实验版

目标：**先实际看到几十毫秒级别的终局估算，再研究与原版 KataGo 的目差**。本版不实现死子/地盘的手动修正，也不会为了快而把规则数子和深度搜索冒充同一种结果。

## 代码

- `app/src/main/java/com/badukai/game/FastEndgameScorer.java`：只用一份 `whiteOwnership` 结果做棋块级死子建议；所有相连棋子必须整体通过反向归属阈值（每颗至少 0.55、平均至少 0.70）才标记死子，避免半块棋被移除。规则计分仍使用 `GoBoard.countChineseScore()`，作为对照日志而非未经确认的正式胜负。
- `MainActivity.finishByScore()`：默认实验开关 `EXPERIMENTAL_FAST_ENDGAME = true`。双方停棋后仅调用一次 `kata-raw-nn 0`，用 **神经网络的 `whiteLead`** 显示「快速AI预估某方领先约 N.N 目」，同时计算/记录死子建议和当前局面的中国数目。如果 NN 不可用，自动回退到旧的 `final_score → final_status_list dead → kata-raw-nn 0`。
- 对局结束页面中的「深度对照」按钮：按需调用原版 `final_score` 和 `final_status_list dead`，展示原算法预测结果，并在 Logcat 记录 `ENDGAME_COMPARE fastLead=... deepLead=... delta=... fastDead=... deepDead=...`。不需要重新下一盘棋。
- 关闭实验只需把 `MainActivity.java` 中 `EXPERIMENTAL_FAST_ENDGAME = true` 改为 `false`，原版默认流程会恢复。

**重要**：单次 NN `whiteLead` 不是 KataGo 的 `final_score`，也不是官方计分。容易误判未收完的地盘、复杂死活、劫或双活；当前结果已经标注为「预估」。它和原版精确预测可能不一致。因此这是验证性能与偏差的实验版，不要拿它进行正式判负。直接规则数子 2ms 的速度不代表神经网络推理/Java UI 的真实速度。

## 验证

在 `GoEngine-java` 目录：

```bash
git pull origin rk3588-engine
python3 tools/test_fast_endgame_scorer.py
python3 tools/test_full_game.py
python3 tools/test_app_ui_units.py
bash build.sh --apk-only
adb -s 8719e18a71a2a66c install -r app/build/outputs/apk/debug/app-debug.apk
python3 tools/test_app_ui.py -s 8719e18a71a2a66c --backends gpu --size 9 --full-game --game-max-actions 110 --settlement-timeout 180
```

`--apk-only` 复用已有 CPU/GPU JNI so；不存在库时需按原项目方式完整编译。测试会自动上传到 `ui-test-results`（不想公开上传可加 `--no-upload`）。运行过程不要求电脑上有 Node.js、WASM 或其他 OGS 组件；只依赖原来 Android APP 的 Java + KataGo。

### 看测试结果

报告 `settlement.scoring_mode=fast_nn` 才说明用了本次实验；`total_ms` 是用户实际等待到显示结果的墙钟时间；`raw_white_lead` 是一次 NN 预测目差，`position_evaluation_ms` 是 NN 计算+传输时间；`gtp_final_score=null` 是正常的，因为没有默认启动慢搜索。**报告 PASS 只表示实验估算链路和棋盘同步通过，不代表结算数字等于原版 `final_score`**。

Logcat：

```text
ENDGAME_TIMING phase=fast_nn_done elapsedMs=... evalMs=... nnWhiteLead=... localWhiteLead=... suggestedDead=...
Chinese area score ... mode=fast_nn
ENDGAME_COMPARE fastLead=... deepLead=... delta=... fastDead=... deepDead=...
```

建议整局完成后先记录快速胜负，再点击一次「深度对照」。对比同局面的 `delta`、`fastDead` 和 `deepDead`，判断这条低成本路线的实际可用性。旧版实测 `raw-nn` 大概 20～30ms，但**整个 APP 的结算耗时目前没有实机数据**。
