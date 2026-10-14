# 智能裁判：快速 NN 估分 + 严格收官检查（2026-10-14）

应用内「智能裁判」统一使用 `showQuickNnRefereeEstimate()`，调用 KataGo `kata-raw-nn 0` 获取当前局面的 NN 预测，不再开启另一套复核界面。

- 显示黑白预计目数、NN 领先目数、黑白胜率。目数由 `NnAreaScore` 根据 NN ownership 估算，包含贴目或让子补偿。
- 对局中可以随时看预估；双方停棋后仍只显示预估，不把 NN 预测当成官方数子结果。
- 弹窗固定四行：领先目数、黑白预计目数、胜率、**收官：已完成/未确认**；旧版大段文字、劫争和单气提醒已去掉。
- 收官严格检查调用 C++ `BoardHistory::endGameIfAllPassAlive()`：将当前棋盘复制进临时 KataGo Board，只有全盘均为经 Benson/pass-alive 严格识别的区域，并且不存在当前单劫禁时才确认「已完成」。检测不会落子、撤销 PASS 或改动在局棋盘。
- 「未确认」不代表一定没收完，只表示严格证明未成功（包括复杂劫争、不确定区域、缺少新 JNI 版本或其他无法确认的情形）；双方连续 PASS 和 NN 高胜率都不会直接导致「已完成」。
- CPU/Eigen JNI 和 GPU/OpenCL JNI 均导出相同严格检查接口；旧的 PIE 运行模式保守显示「未确认」。
- 已删除旧的 `SmartReferee`、`EndgameAdjudicator`、`MidgameTacticalReview`，以及复核红叉黄圈橙圈、两次点击恢复、人工确认数子和原生搜索裁判的应用流程。
- 双方连续停一手时依然可结束对局，并显示 NN 快速预估；不再回退到旧 `final_score` / `final_status_list dead` 自动数子流程。
- AI 对弈、CPU/GPU 切换、AI 本手用时、胜率与常规形势显示不受旧裁判移除影响。

## 主机端测试

在 `GoEngine-java` 目录下运行：

```bash
python3 tools/test_smart_referee.py
python3 tools/test_smoke_android_referee_app.py
```

前者只编译测试 `NnAreaScore.java`（沿用历史脚本名），后者验证 UI 入口、无旧流程、日志解析、数值边界、报告保存等，不代表已在设备运行。

## Android 真实 SGF 测试

先重新构建并安装新的 Debug APK，**仅重新运行旧 APK 会继续出现旧裁判界面**：

`--apk-only` 会保留独立胜率引擎需要的 `libkatago_exec.so`。如果本地只找到 4 KB 对齐的旧版，脚本会自动运行一次 CPU/Eigen PIE 编译（使用 16 KB 链接对齐）。成功后复用缓存，无需每次重新编译；默认原生并发数是 2，必要时可通过 `KATAGO_JOBS` 调整。


```bash
KATAGO_JOBS=2 bash build.sh
python3 tools/smoke_android_referee_app.py --backend gpu --all --no-upload
```

**注意这次修改了 C++ JNI：首次更新必须运行完整 `bash build.sh`，不能只用 `--apk-only` 重打包旧 `.so`。**

可用 `--backend cpu` 测 CPU，`--midgame` 在棋局尚未两停时测 NN，`--serial <设备序列号>` 指定设备。两设备同时连着时必须指定 `--serial`。也可以直接加 `--build` 由脚本调用 Gradle 构建 APK。

设备脚本依次加载四盘 9 路 SGF，在 **同一真实界面入口** 运行 NN，确认：

1. CPU/GPU 使用预期后端，没有悄悄回退。
2. NN 返回有限的目差、双方胜率、双方目数，以及 `nnMs` 耗时；同时报告真实 JNI 规则证明 `passAlive`。
3. 9 路中国规则 + 7 目贴目时，黑白预计目数合计接近 88。
4. 弹窗显示 NN 预测与「收官：已完成/未确认」，并与原生证明结果一致；关闭后棋盘手数和对局结束状态不变。
5. 真机独立验证一盘构造的全盘双眼活棋返回 true、空棋盘返回 false；不能静默使用旧 JNI 总返回未确认。

默认仍能上传报告至独立的 `ui-test-results` 分支；用 `--no-upload` 禁止上传。所有报告、截图和日志仍保存在 `build/android_referee_smoke/<UTC时间>/`。

> NN 的面积估分不等同于双方确认的正式终局。复杂劫争、双活和未走完的棋仍需要棋手判断。
