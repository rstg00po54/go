# 固定 SGF 终局：快速 NN vs KataGo 原版对照

不用自动下完一盘、不用重装 APK、不修改 UI。脚本在 RK3588 真机的原生 KataGo GTP 上加载**完全相同的静态 SGF 局面**，让黑白各停一手，依次记录实验版 NN 计算与原版完整搜索，比较目差、死子及耗时。

## 运行

在 `GoEngine-java` 目录：

```bash
git pull origin rk3588-engine
python3 tools/test_compare_fast_endgame_fixed.py
python3 tools/test_fast_endgame_scorer.py
python3 tools/compare_fast_endgame_fixed_android.py --serial 8719e18a71a2a66c --mode gpu --threads 1 --repeats 2
```

如果想顺便比较 CPU，可把 `--mode gpu` 改成 `--mode both`。线程数默认 1，与 APP 当前设置一致；4 线程耗时会变，也可能使原版搜索目差略变。如果用户只想本地看报告而不上传公开 GitHub，追加 `--no-upload`。

脚本使用现有 `benchmark_endgame_android.py` 的固定棋盘：`simple`（两方两眼）、`territory`（地盘边界）、`tactical`（薄弱棋块）。可以加 `--positions simple,tactical` 控制测哪几盘。这三份是 **合成的固定局面 SGF（AB/AW 摆子）**，不是职业比赛完整对局，也不包含人工认证的最终死活/胜负答案。它们适合复现两种算法的差异，但**不能证明哪种一定对**。有标准结果的实际结束棋谱应作为下一批验证数据。

## 具体执行什么

针对每个 SGF：

```text
loadsgf 同一棋盘（Chinese / 5.5 komi / PL[B]）
play B pass
play W pass
clear_cache
kata-raw-nn 0             ← 一次 NN 推理，得到 whiteLead + whiteOwnership
FastEndgameScorer 等价逻辑 ← 按整个棋块判断，阈值 0.55 / 0.70
Java 中国数子等价遍历     ← 死子移除后算面积
final_score               ← 原版 KataGo 预测目差
final_status_list dead     ← 原版 KataGo 搜索判死
规则数子                  ← 使用原版死子清单作为另一份对照
```

同一个 GTP 引擎实例、同一模型和棋盘，评估与原版搜索之间不重新落子，不修改规则，也不换贴目。每次复测前重新 load SGF，防止串局。

默认用已有资产 `app/src/main/assets/engine/10b.bin` + `default_gtp.cfg`。**Android APP 可使用不同的 `human_gtp.cfg`/Human SL 参数**，因此原生 GTP 对照不等于已测试 APK UI 的每一项行为。它优先验证死子建议算法和两种目差定义之间的差异。

## 结果怎么看

`endgame.json` 和 `endgame.csv` 中：

| 字段 | 含义 |
|---|---|
| `fast_white_lead` | NN 单次推理的预估目差，正数是白棋领先 |
| `original_white_lead` | KataGo 原版 `final_score` 的目差 |
| `lead_delta` | 快速预估 − 原版预测，越接近 0 越相符 |
| `fast_dead` | 实验版 Java 棋块阈值逻辑建议的死子 |
| `original_dead` | 原版 `final_status_list dead` 判死的棋子 |
| `dead_difference.fast_only` | 仅实验版建议移除、原版未判死的棋 |
| `dead_difference.original_only` | 原版判死、实验版未移除的棋 |
| `fast_area_lead` | 实验版建议死子移除后，用中国规则数子的目差 |
| `original_area_lead` | 原版死子清单移除后，用同样中国规则数子的目差 |
| `raw_nn_ms` | 单次 NN GTP 墙钟耗时 |
| `original_score_ms` / `original_dead_ms` | 原版两次 GTP 搜索耗时 |

报告状态为 **`COMPARISON_ONLY`**，不是准确率 PASS。死子一致、目差接近只代表测试的这些局面表现一致；原版 `final_score` 也只是搜索预测，不是经人类确认的绝对标准答案。要真正确认规则正确性，需要用带有人工最终死子标记 / 已确定结果的真实结束棋谱。

默认报告自动上传到项目的**公开、未脱敏** `ui-test-results` 分支。只建议在测试设备上使用，含私人 SGF 的测试应加 `--no-upload`。
