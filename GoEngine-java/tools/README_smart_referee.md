# 智能裁判：仅快速 NN 估分（2026-10-14）

应用内「智能裁判」统一使用 `showQuickNnRefereeEstimate()`，调用 KataGo `kata-raw-nn 0` 获取当前局面的 NN 预测，不再开启另一套复核界面。

- 显示黑白预计目数、NN 领先目数、黑白胜率。目数由 `NnAreaScore` 根据 NN ownership 估算，包含贴目或让子补偿。
- 对局中可以随时看预估；双方停棋后仍只显示预估，不把 NN 预测当成官方数子结果。
- 仅提示简单劫禁或单气棋风险，不自动判定死活，不修改棋盘、落子或撤销 PASS。
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
bash build.sh --apk-only
python3 tools/smoke_android_referee_app.py --backend gpu --all --no-upload
```

可用 `--backend cpu` 测 CPU，`--midgame` 在棋局尚未两停时测 NN，`--serial <设备序列号>` 指定设备。两设备同时连着时必须指定 `--serial`。也可以直接加 `--build` 由脚本调用 Gradle 构建 APK。

设备脚本依次加载四盘 9 路 SGF，在 **同一真实界面入口** 运行 NN，确认：

1. CPU/GPU 使用预期后端，没有悄悄回退。
2. NN 返回有限的目差、双方胜率、双方目数，以及 `nnMs` 耗时。
3. 9 路中国规则 + 7 目贴目时，黑白预计目数合计接近 88。
4. 弹窗显示 NN 预测并可关闭；关闭后棋盘手数和对局结束状态不变。
5. 不依赖旧 `READY_TO_COUNT`、争议标记、手工确认数子或 MCTS 的测试断言。

默认仍能上传报告至独立的 `ui-test-results` 分支；用 `--no-upload` 禁止上传。所有报告、截图和日志仍保存在 `build/android_referee_smoke/<UTC时间>/`。

> NN 的面积估分不等同于双方确认的正式终局。复杂劫争、双活和未走完的棋仍需要棋手判断。
