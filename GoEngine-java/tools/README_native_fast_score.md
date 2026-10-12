# KataGo C++ 原生快速数子实验

这版**不是 Java 重写算法**。新的 GTP 命令 `kata-fast-final-score` 位于 KataGo 原生代码 `native/KataGo/cpp/command/gtp.cpp`。默认 APP、`final_score`、`final_status_list` **均不改变**。

## 做了什么

连续两次 `pass` 后执行 `kata-fast-final-score`：

1. 让 KataGo 原生 NN 评估一次棋盘，取得 `whiteOwnerMap` 与 `whiteLead`。
2. 遍历 **KataGo 的原生棋块链表** `Board::next_in_chain`，采用 KataGo `PlayUtils::computeAnticipatedStatusesWithOwnership` 中的同样死活启发式阈值（平均归属 0.2、最差归属 -0.6），区别是直接用一次 NN 原始归属，**不进行 MCTS 搜索**。
3. 对原始棋盘直接调用 `BoardHistory::endAndScoreGameNow()`，得到 `ruleScoreWithoutDead`。
4. 在**副本**上通过 `Board::setStone(...,C_EMPTY)` 移除建议死子，再通过 `BoardHistory::endAndScoreGameNow()` 计算 `fastRuleScore`。
5. 返回 GTP JSON：`fastRuleScore`、`ruleScoreWithoutDead`、`nnWhiteLead`、`dead`、`blackArea`、`whiteArea`、`authoritative:false` 等。原版棋盘和原有 GTP 命令不受影响。

`fastRuleScore` 是**当前棋盘、按快速估计移除死子后的规则数子**；原版 `final_score` 是**预测继续下棋后的目差**，不能仅凭二者不一致就认定其中一个有 bug。**NN 死子仍可能判错**，它用的是 KataGo 的启发式而非完整的搜索结果。

## 构建与验证

在 `GoEngine-java` 目录：

```bash
git pull origin rk3588-engine
python3 tools/test_native_fast_score.py
bash tools/build_katago_from_source.sh opencl
python3 tools/benchmark_native_fast_score_android.py --serial 8719e18a71a2a66c --mode gpu --threads 1 --repeats 2
```

首次构建需要原项目已经安装的 Android NDK、CMake、Ninja、OpenCL headers、Android ARM64 OpenCL stub。构建输出默认是 `build/katago_android_arm64_opencl/libkatago_exec_opencl.so`，独立于正在使用的 APP/JNI GPU 动态库。

脚本先对 Android 上的原生 GTP 执行 `known_command kata-fast-final-score`；如果仍是旧二进制，直接报错要求重新构建，不会偷偷回退或报告伪造的对比结果。

对每个固定 SGF 的 `simple`、`territory`、`tactical`，加载后依次执行黑/白 `pass`，同盘比较：

```text
kata-fast-final-score
final_score
final_status_list dead
```

记录新命令原生规则分数、新命令预测死子、原版搜索预测分数/死子、耗时；自动上传 `endgame.json`、`endgame.csv`、固定 SGF 到公开 `ui-test-results` 分支（如不愿上传，加 `--no-upload`）。

生成报告为 `COMPARISON_ONLY`，不冒充“准确性通过”。暂未在 RK3588 上编译和实测，**这一步由你的设备执行后才能确认编译、运行和速度**。

如要测 CPU，再执行：

```bash
bash tools/build_katago_from_source.sh eigen
python3 tools/benchmark_native_fast_score_android.py --serial 8719e18a71a2a66c --mode both --threads 1 --repeats 2
```

**目前不需要修改/重装 APK。** 实验能否代替正式计分，需要观察死子和目差数据，并在此后加入已确认结果的真实棋谱。
