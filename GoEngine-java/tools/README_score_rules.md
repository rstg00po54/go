# KataGo 终局为什么这么慢：直接规则数子 vs 人类友好预测

这项诊断只运行 **原生 ARM64 GTP 对照**，不改变 APK 的默认计分命令，也不改 C++、模型、搜索访问数。目的是区分「计算真耗时」和「为了让人停棋后更合理判分而主动使用 AI 搜索」两种情况。

## 已从 KataGo v1.15.0 C++ 确认的代码路径

- `rules.cpp`: `chinese` 配置含 `scoringRule=AREA`、`koRule=SIMPLE`、`friendlyPassOk=true`；
- `boardhistory.cpp`: 中国面积规则下两次 pass 后 `endAndScoreGameNow()` 使用 `board.calculateArea()` 把已经结束的棋局算出一份**规则分数**；
- `gtp.cpp`: 对 `friendlyPassOk=true` 的面积规则，`final_score` **故意跳过已完成棋局的直接读取路径**，调用 `PlayUtils::computeLead()` 预测人类友好数目；`final_status_list dead` 还会调用 `computeOwnership()` 独立搜索；
- `playutils.cpp`: `computeLead()` 多次改变 komi，每次新 komi 都可能 `getWhiteScoreValues()` 重新运行完整 MCTS 搜索。它不是简单 O(81) 数棋盘；
- 中国规则里的 `friendlyPassOk=false` 表示所有死棋应该在停棋前直接吃掉，而不是停棋时由双方确认死子，所以**直接规则数子不能无条件冒充人类友好最终目数**。

官方参考：https://github.com/lightvector/KataGo/blob/master/docs/GTP_Extensions.md

## 最小化对比实验

在 GoEngine-java 工程目录：

```bash
git pull origin rk3588-engine
python3 tools/test_score_rules.py
python3 tools/compare_score_rules_android.py --serial 8719e18a71a2a66c --mode cpu --threads 4 --positions simple,tactical
```

所有代码只用已有 ARM64 KataGo、10b.bin、ADB 和固定 SGF。每个测试位置：`loadsgf`，设置 Chinese AREA + 相同贴目，依次执行 `play B pass` 和 `play W pass`，之后分别以 `friendlyPassOk=true/false` 独立重新加载相同 SGF、同样两次停棋、清空缓存，然后执行 **相同** `final_score` + `final_status_list dead`。输出：两种规则设置的胜负目差、死子、耗时、SHA256、规则明细。除了 friendlyPassOk 其他 GTP 规则字段必须完全一致，否则报告失败。

其中 `human_predict` 是当前 APP 的原始逻辑（慢搜索）；`finished_rules` 是 KataGo 自己的已结束棋局规则计分（可能很快）。`same_score` 和 `same_dead` 只有都为 true，才能说明某个局面出现了相同结果，**并不能推广到所有对局**。简单人工 SGF 不代表真实棋谱，也未验证真正复杂的死活、双活、劫争、让子。

结果默认上传到公开 `ui-test-results` 的 `runs/*_endgame/`（含 `endgame.json`、`endgame.csv`、`positions/*.sgf`、`fixtures.json`），方便下一轮直接读取。不想上传加 `--no-upload`。没有修改原来的 Android APP 行为。

## 下一步怎样真正优化（不是跳过计算）

如果要求 `final_score` 在所有局面下都返回**完全一样的预测分数**，不能直接替换为规则数子或 raw NN 分数。应首先在 `PlayUtils::evalKomi()` / `getWhiteScoreValues()` 增加每次 komi 搜索的耗时和访问信息，量化实际发起多少轮 MCTS，然后研究在相同搜索预算和输出校验下减少初始化/重复评估，或多种 komi 批量计算。较大并行更容易改变搜索调度和最终预测目差，不能声称完全数值等价。
